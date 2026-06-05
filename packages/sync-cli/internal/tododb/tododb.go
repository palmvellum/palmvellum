// Package tododb encodes and decodes PalmOS To Do List's ToDoDB.pdb
// records and AppInfo block.
//
// Each todo record:
//
//	0   2  dueDate (UInt16) — packed Palm DateType, or 0xFFFF for none
//	         bits 0..4   = day (1..31)
//	         bits 5..8   = month (1..12)
//	         bits 9..15  = year offset from 1904
//	2   1  priority — bit 7 = completed, bits 0..6 = priority (1..5)
//	3   *  description — NUL-terminated UTF-8 / Palm Latin-1
//	..  *  notes        — NUL-terminated, optional
//
// Category index = record entry attributes low nibble (same as memos).
//
// AppInfo block is 282 bytes: 276 standard categories + 2 reserved +
// 2 padding + 2 dirtyAppInfo / sort order (we just preserve raw bytes
// past byte 276 to stay compatible with whatever the device wrote).
package tododb

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"time"

	"github.com/palmvellum/palmvellum/packages/sync-cli/internal/memodb"
	"github.com/palmvellum/palmvellum/packages/sync-cli/internal/pdb"
)

const AppInfoLen = 282

// Todo is one to-do item.
type Todo struct {
	UniqueID    uint32
	Category    uint8
	DueDate     *time.Time // nil = no due date
	Priority    uint8      // 1..5; values outside the range clamp on encode
	Completed   bool
	Description string
	Notes       string
}

func decodeDate(d uint16) *time.Time {
	if d == 0xFFFF {
		return nil
	}
	year := int(d>>9) + 1904
	month := int((d >> 5) & 0xF)
	day := int(d & 0x1F)
	if month == 0 || day == 0 {
		return nil
	}
	t := time.Date(year, time.Month(month), day, 0, 0, 0, 0, time.UTC)
	return &t
}

func encodeDate(t *time.Time) uint16 {
	if t == nil {
		return 0xFFFF
	}
	y := t.Year() - 1904
	if y < 0 || y > 127 {
		return 0xFFFF
	}
	return uint16(y)<<9 | uint16(t.Month())<<5 | uint16(t.Day())
}

// DecodeTodo parses one record's bytes.
func DecodeTodo(data []byte) (Todo, error) {
	if len(data) < 4 {
		return Todo{}, fmt.Errorf("tododb: short record %d bytes", len(data))
	}
	t := Todo{}
	t.DueDate = decodeDate(binary.BigEndian.Uint16(data[0:2]))
	prioByte := data[2]
	t.Completed = prioByte&0x80 != 0
	t.Priority = prioByte & 0x7F

	descEnd := 3
	for descEnd < len(data) && data[descEnd] != 0 {
		descEnd++
	}
	t.Description = string(data[3:descEnd])

	if descEnd+1 < len(data) {
		notesStart := descEnd + 1
		notesEnd := notesStart
		for notesEnd < len(data) && data[notesEnd] != 0 {
			notesEnd++
		}
		t.Notes = string(data[notesStart:notesEnd])
	}
	return t, nil
}

// Encode serializes a Todo to record bytes.
func (t Todo) Encode() []byte {
	var buf bytes.Buffer
	_ = binary.Write(&buf, binary.BigEndian, encodeDate(t.DueDate))
	prio := t.Priority & 0x7F
	if prio == 0 {
		prio = 1
	} else if prio > 5 {
		prio = 5
	}
	if t.Completed {
		prio |= 0x80
	}
	buf.WriteByte(prio)
	buf.WriteString(t.Description)
	buf.WriteByte(0)
	if t.Notes != "" {
		buf.WriteString(t.Notes)
	}
	buf.WriteByte(0)
	return buf.Bytes()
}

// DecodeTodos walks the DB and decodes every record.
func DecodeTodos(db *pdb.DB) ([]Todo, error) {
	out := make([]Todo, 0, len(db.Records))
	for i, r := range db.Records {
		td, err := DecodeTodo(r.Data)
		if err != nil {
			return nil, fmt.Errorf("record %d: %w", i, err)
		}
		td.UniqueID = r.UniqueID
		td.Category = r.Attributes & 0x0F
		out = append(out, td)
	}
	return out, nil
}

// EncodeTodos turns Todo structs into pdb.Record entries.
func EncodeTodos(todos []Todo) []pdb.Record {
	out := make([]pdb.Record, 0, len(todos))
	for _, t := range todos {
		out = append(out, pdb.Record{
			UniqueID:   t.UniqueID,
			Attributes: t.Category & 0x0F,
			Data:       t.Encode(),
		})
	}
	return out
}

// ─── AppInfo ──────────────────────────────────────────────────

// AppInfo reuses memodb.AppInfo for categories — the layout is
// identical for the first 276 bytes. The remaining bytes (reserved
// + dirtyAppInfo + sort) are preserved verbatim when round-tripping
// an existing AppInfo, or zeroed for a fresh one.
type AppInfo struct {
	*memodb.AppInfo
	Tail []byte // bytes 276..281, preserved on encode
}

func ParseAppInfo(b []byte) (*AppInfo, error) {
	if len(b) < 276 {
		return nil, errors.New("tododb: appinfo too short")
	}
	cats, err := memodb.ParseAppInfo(b)
	if err != nil {
		return nil, err
	}
	ai := &AppInfo{AppInfo: cats}
	if len(b) > 276 {
		ai.Tail = append([]byte(nil), b[276:]...)
	}
	return ai, nil
}

func (ai *AppInfo) Encode() []byte {
	base := ai.AppInfo.Encode() // 280 bytes
	out := make([]byte, AppInfoLen)
	copy(out[0:280], base)
	if len(ai.Tail) > 0 {
		// overlay original tail past byte 276 (last 6 bytes max)
		copyLen := len(ai.Tail)
		if copyLen > AppInfoLen-276 {
			copyLen = AppInfoLen - 276
		}
		copy(out[276:276+copyLen], ai.Tail[:copyLen])
	}
	return out
}

func DefaultAppInfo() *AppInfo {
	return &AppInfo{
		AppInfo: memodb.DefaultAppInfo(),
		Tail:    make([]byte, AppInfoLen-276),
	}
}

// NewTodoDB constructs an empty ToDoDB skeleton.
func NewTodoDB(ai *AppInfo) *pdb.DB {
	return &pdb.DB{
		Name:    "ToDoDB",
		Type:    pdb.FourCC("DATA"),
		Creator: pdb.FourCC("todo"),
		AppInfo: ai.Encode(),
	}
}
