// Package pdb implements a minimal Palm Database (.pdb) binary
// reader and writer.
//
// The on-disk layout (PalmOS 3.x / 4.x, all big-endian):
//
//	Offset  Length  Field
//	------  ------  --------------------------------------------------
//	0       32      Database name, NUL-padded ASCII
//	32      2       Attributes  (UInt16)
//	34      2       Version     (UInt16)
//	36      4       Creation date (UInt32, Palm epoch — seconds since 1904-01-01 UTC)
//	40      4       Modification date
//	44      4       Last backup date
//	48      4       Modification number
//	52      4       App info offset  (0 if none)
//	56      4       Sort info offset (0 if none)
//	60      4       Database type     (4 ASCII chars, e.g. 'Data')
//	64      4       Database creator  (4 ASCII chars, e.g. 'PvV1')
//	68      4       Unique ID seed
//	72      4       Next record list ID (always 0)
//	76      2       Number of records (UInt16)
//	78      8*N     N record entries (see below)
//	78+8N   2       Padding (typically 0x0000)
//	..      *       AppInfo block (if AppInfoOffset != 0)
//	..      *       SortInfo block (if SortInfoOffset != 0; unused here)
//	..      *       Record data chunks
//
// Each record entry (8 bytes):
//
//	Offset  Length  Field
//	0       4       Data offset (UInt32 absolute byte position in file)
//	4       1       Attributes  (UInt8)  — high nibble = category, bit 7 = secret
//	5       3       Unique ID (24-bit) — big-endian
//
// Each record's data is a contiguous byte chunk starting at its offset
// and ending where the next record begins (or EOF for the last record).
package pdb

import (
	"encoding/binary"
	"errors"
	"fmt"
	"time"
)

// PalmEpochOffset is the number of seconds between 1904-01-01 UTC
// (Palm epoch) and 1970-01-01 UTC (Unix epoch).
const PalmEpochOffset = 2082844800

// DB is the in-memory representation of a Palm database file.
type DB struct {
	Name       string
	Attributes uint16
	Version    uint16
	CreatedAt  time.Time
	ModifiedAt time.Time
	BackupAt   time.Time
	ModNumber  uint32
	Type       [4]byte // e.g. 'D','a','t','a'
	Creator    [4]byte // e.g. 'm','e','m','o'
	UniqueSeed uint32

	// AppInfo holds the optional AppInfo block bytes. For MemoDB /
	// ToDoDB this is the standard categories block (see memodb.go,
	// tododb.go). For VellumDB it's empty.
	AppInfo []byte

	Records []Record
}

// Record is one record in a database — the unique ID is the stable
// per-record identity that survives sort/insertion shifts on the Palm.
type Record struct {
	UniqueID   uint32 // 24-bit
	Attributes uint8
	Data       []byte
}

// Read parses a complete PDB byte stream.
func Read(b []byte) (*DB, error) {
	if len(b) < 78 {
		return nil, fmt.Errorf("pdb: short header: %d bytes", len(b))
	}
	db := &DB{}

	// Name — trim NULs
	nameEnd := 0
	for nameEnd < 32 && b[nameEnd] != 0 {
		nameEnd++
	}
	db.Name = string(b[0:nameEnd])

	db.Attributes = binary.BigEndian.Uint16(b[32:34])
	db.Version = binary.BigEndian.Uint16(b[34:36])
	db.CreatedAt = palmTime(binary.BigEndian.Uint32(b[36:40]))
	db.ModifiedAt = palmTime(binary.BigEndian.Uint32(b[40:44]))
	db.BackupAt = palmTime(binary.BigEndian.Uint32(b[44:48]))
	db.ModNumber = binary.BigEndian.Uint32(b[48:52])
	appInfoOff := binary.BigEndian.Uint32(b[52:56])
	sortInfoOff := binary.BigEndian.Uint32(b[56:60])
	copy(db.Type[:], b[60:64])
	copy(db.Creator[:], b[64:68])
	db.UniqueSeed = binary.BigEndian.Uint32(b[68:72])
	// nextRecordListID (72..75) ignored
	n := int(binary.BigEndian.Uint16(b[76:78]))

	entriesStart := 78
	entriesEnd := entriesStart + 8*n
	if len(b) < entriesEnd {
		return nil, fmt.Errorf("pdb: truncated entry list: have %d need %d", len(b), entriesEnd)
	}

	offsets := make([]uint32, n)
	db.Records = make([]Record, n)
	for i := 0; i < n; i++ {
		e := b[entriesStart+8*i : entriesStart+8*i+8]
		offsets[i] = binary.BigEndian.Uint32(e[0:4])
		db.Records[i].Attributes = e[4]
		db.Records[i].UniqueID = uint32(e[5])<<16 | uint32(e[6])<<8 | uint32(e[7])
	}

	// Optional AppInfo block. End boundary is the earliest of: SortInfo
	// offset (if set), first record offset (if any), or EOF.
	if appInfoOff != 0 && int(appInfoOff) <= len(b) {
		end := uint32(len(b))
		if sortInfoOff != 0 && sortInfoOff > appInfoOff && sortInfoOff < end {
			end = sortInfoOff
		}
		if n > 0 && offsets[0] > appInfoOff && offsets[0] < end {
			end = offsets[0]
		}
		if end > appInfoOff {
			db.AppInfo = append([]byte(nil), b[appInfoOff:end]...)
		}
	}

	// Record chunks
	for i := 0; i < n; i++ {
		start := offsets[i]
		var end uint32
		if i+1 < n {
			end = offsets[i+1]
		} else {
			end = uint32(len(b))
		}
		if int(start) > len(b) || int(end) > len(b) || end < start {
			return nil, fmt.Errorf("pdb: bad record %d offset [%d..%d] of %d", i, start, end, len(b))
		}
		db.Records[i].Data = append([]byte(nil), b[start:end]...)
	}

	return db, nil
}

// Write serializes the DB to a byte stream suitable for `.pdb` import.
//
// Layout: header → entries → 2-byte pad → (optional AppInfo) → record blobs.
//
// The unique ID seed is auto-bumped if any record has UniqueID == 0
// (we assign sequentially from seed+1 in that case).
func (db *DB) Write() ([]byte, error) {
	if len(db.Records) > 0xFFFF {
		return nil, errors.New("pdb: too many records (>65535)")
	}
	n := len(db.Records)

	// Auto-assign IDs to any record that came in with UniqueID == 0.
	seed := db.UniqueSeed
	for i := range db.Records {
		if db.Records[i].UniqueID == 0 {
			seed++
			db.Records[i].UniqueID = seed & 0xFFFFFF
		}
	}
	db.UniqueSeed = seed

	headerEntriesLen := 78 + 8*n
	paddingLen := 2 // always emit the pad — Palm Memo Pad expects it
	hasAppInfo := len(db.AppInfo) > 0

	var appInfoOffset uint32
	if hasAppInfo {
		appInfoOffset = uint32(headerEntriesLen + paddingLen)
	}

	recordsStart := uint32(headerEntriesLen + paddingLen + len(db.AppInfo))

	totalLen := int(recordsStart)
	for _, r := range db.Records {
		totalLen += len(r.Data)
	}
	out := make([]byte, totalLen)

	// Header
	copy(out[0:32], []byte(db.Name))
	binary.BigEndian.PutUint16(out[32:34], db.Attributes)
	binary.BigEndian.PutUint16(out[34:36], db.Version)
	binary.BigEndian.PutUint32(out[36:40], toPalm(db.CreatedAt))
	binary.BigEndian.PutUint32(out[40:44], toPalm(db.ModifiedAt))
	binary.BigEndian.PutUint32(out[44:48], toPalm(db.BackupAt))
	binary.BigEndian.PutUint32(out[48:52], db.ModNumber)
	binary.BigEndian.PutUint32(out[52:56], appInfoOffset)
	// sortInfoOffset = 0
	copy(out[60:64], db.Type[:])
	copy(out[64:68], db.Creator[:])
	binary.BigEndian.PutUint32(out[68:72], db.UniqueSeed)
	// nextRecordListID = 0
	binary.BigEndian.PutUint16(out[76:78], uint16(n))

	// Record entries — offsets point past header+entries+padding+appinfo
	cursor := recordsStart
	for i, r := range db.Records {
		e := out[78+8*i : 78+8*i+8]
		binary.BigEndian.PutUint32(e[0:4], cursor)
		e[4] = r.Attributes
		e[5] = byte(r.UniqueID >> 16)
		e[6] = byte(r.UniqueID >> 8)
		e[7] = byte(r.UniqueID)
		copy(out[cursor:cursor+uint32(len(r.Data))], r.Data)
		cursor += uint32(len(r.Data))
	}

	// AppInfo (after 2-byte pad, before first record)
	if hasAppInfo {
		copy(out[appInfoOffset:appInfoOffset+uint32(len(db.AppInfo))], db.AppInfo)
	}

	return out, nil
}

func palmTime(secsPalmEpoch uint32) time.Time {
	if secsPalmEpoch == 0 {
		return time.Time{}
	}
	// Palm epoch is 1904-01-01 UTC; older HotSync-era tools sometimes
	// wrote the value as time_t (1970-based) — detect and convert.
	// Heuristic: < 2082844800 means already Unix.
	if secsPalmEpoch < PalmEpochOffset {
		return time.Unix(int64(secsPalmEpoch), 0).UTC()
	}
	return time.Unix(int64(secsPalmEpoch)-PalmEpochOffset, 0).UTC()
}

func toPalm(t time.Time) uint32 {
	if t.IsZero() {
		return 0
	}
	return uint32(t.Unix() + PalmEpochOffset)
}

// FourCC packs four ASCII bytes into a [4]byte for Type/Creator.
func FourCC(s string) [4]byte {
	var out [4]byte
	copy(out[:], s)
	return out
}
