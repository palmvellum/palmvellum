// Package datebookdb encodes and decodes PalmOS Date Book's DatebookDB.
//
// Record layout (per pilot-link libpisock/datebook.c):
//
//	0      UInt8   start hour   (0xFF/0xFF start = untimed / all-day)
//	1      UInt8   start minute
//	2      UInt8   end hour
//	3      UInt8   end minute
//	4..5   UInt16  packed date  ((year-1904)<<9 | month<<5 | day)
//	6      UInt8   flags         (alarm 0x40, repeat 0x20, note 0x10,
//	                              exceptions 0x08, description 0x04)
//	7      UInt8   gap (0)
//	8..    optional blocks, in this order, present per flags:
//	         alarm:       UInt8 advance, UInt8 unit
//	         repeat:      8 bytes (type, gap, end-date, freq, daymap, weekStart, gap)
//	         exceptions:  UInt16 count, then count × UInt16 packed dates
//	         description: NUL-terminated string
//	         note:        NUL-terminated string
//
// The repeat and exception blocks are preserved as raw bytes so a decode→
// encode round-trip of a real record is byte-exact; the semantic fields the
// cloud needs (times, date, description, note, alarm) are parsed out.
package datebookdb

import (
	"github.com/palmvellum/palmvellum/packages/palm-engine/charset"
	"github.com/palmvellum/palmvellum/packages/palm-engine/pdb"
)

const (
	flagAlarm  = 0x40
	flagRepeat = 0x20
	flagNote   = 0x10
	flagExcept = 0x08
	flagDesc   = 0x04
)

// Appointment is one Date Book entry.
type Appointment struct {
	UniqueID uint32

	Untimed   bool
	StartHour uint8
	StartMin  uint8
	EndHour   uint8
	EndMin    uint8

	Year  int // full year, e.g. 2026
	Month int // 1-12
	Day   int // 1-31

	Description string
	Note        string

	HasAlarm     bool
	AlarmAdvance uint8
	AlarmUnit    uint8 // 0=minutes, 1=hours, 2=days

	// Raw, opaque blocks preserved verbatim for byte-exact round-trips.
	RepeatRaw     []byte // 8 bytes when present
	ExceptionsRaw []byte // 2 + 2*count bytes when present
}

func packDate(y, m, d int) uint16 {
	if y < 1904 || m < 1 || d < 1 {
		return 0
	}
	return uint16(y-1904)<<9 | uint16(m)<<5 | uint16(d)
}

func unpackDate(v uint16) (y, m, d int) {
	return int(v>>9) + 1904, int((v >> 5) & 0x0F), int(v & 0x1F)
}

// DecodeAppointments parses all records of a DatebookDB.
func DecodeAppointments(db *pdb.DB) []Appointment {
	out := make([]Appointment, 0, len(db.Records))
	for _, r := range db.Records {
		a, ok := decodeOne(r.Data)
		if !ok {
			continue
		}
		a.UniqueID = r.UniqueID
		out = append(out, a)
	}
	return out
}

func decodeOne(b []byte) (Appointment, bool) {
	if len(b) < 8 {
		return Appointment{}, false
	}
	var a Appointment
	a.StartHour, a.StartMin = b[0], b[1]
	a.EndHour, a.EndMin = b[2], b[3]
	a.Untimed = b[0] == 0xFF && b[1] == 0xFF
	a.Year, a.Month, a.Day = unpackDate(uint16(b[4])<<8 | uint16(b[5]))
	flags := b[6]
	p := 8

	if flags&flagAlarm != 0 {
		if p+2 > len(b) {
			return a, true
		}
		a.HasAlarm = true
		a.AlarmAdvance, a.AlarmUnit = b[p], b[p+1]
		p += 2
	}
	if flags&flagRepeat != 0 {
		if p+8 > len(b) {
			return a, true
		}
		a.RepeatRaw = append([]byte(nil), b[p:p+8]...)
		p += 8
	}
	if flags&flagExcept != 0 {
		if p+2 > len(b) {
			return a, true
		}
		n := int(uint16(b[p])<<8 | uint16(b[p+1]))
		end := p + 2 + 2*n
		if end > len(b) {
			end = len(b)
		}
		a.ExceptionsRaw = append([]byte(nil), b[p:end]...)
		p = end
	}
	if flags&flagDesc != 0 {
		s, n := readCStr(b[p:])
		a.Description = charset.FromPalm(s)
		p += n
	}
	if flags&flagNote != 0 {
		s, _ := readCStr(b[p:])
		a.Note = charset.FromPalm(s)
	}
	return a, true
}

func readCStr(b []byte) (raw []byte, consumed int) {
	for i, c := range b {
		if c == 0 {
			return b[:i], i + 1
		}
	}
	return b, len(b)
}

// EncodeAppointments turns Appointments into pdb records.
func EncodeAppointments(appts []Appointment) []pdb.Record {
	out := make([]pdb.Record, 0, len(appts))
	for _, a := range appts {
		out = append(out, pdb.Record{
			UniqueID: a.UniqueID,
			Data:     a.encode(),
		})
	}
	return out
}

func (a Appointment) encode() []byte {
	var flags byte
	if a.HasAlarm {
		flags |= flagAlarm
	}
	if len(a.RepeatRaw) == 8 {
		flags |= flagRepeat
	}
	if len(a.ExceptionsRaw) >= 2 {
		flags |= flagExcept
	}
	if a.Description != "" {
		flags |= flagDesc
	}
	if a.Note != "" {
		flags |= flagNote
	}

	buf := make([]byte, 8)
	if a.Untimed {
		buf[0], buf[1], buf[2], buf[3] = 0xFF, 0xFF, 0xFF, 0xFF
	} else {
		buf[0], buf[1], buf[2], buf[3] = a.StartHour, a.StartMin, a.EndHour, a.EndMin
	}
	d := packDate(a.Year, a.Month, a.Day)
	buf[4], buf[5] = byte(d>>8), byte(d)
	buf[6] = flags
	// buf[7] gap = 0

	if a.HasAlarm {
		buf = append(buf, a.AlarmAdvance, a.AlarmUnit)
	}
	if len(a.RepeatRaw) == 8 {
		buf = append(buf, a.RepeatRaw...)
	}
	if len(a.ExceptionsRaw) >= 2 {
		buf = append(buf, a.ExceptionsRaw...)
	}
	if a.Description != "" {
		buf = append(buf, charset.ToPalm(a.Description)...)
		buf = append(buf, 0)
	}
	if a.Note != "" {
		buf = append(buf, charset.ToPalm(a.Note)...)
		buf = append(buf, 0)
	}
	return buf
}

// NewDatebookDB builds an empty DatebookDB shell. appInfo is reused
// verbatim from the card (Date Book's AppInfo carries display prefs we
// don't model); pass nil for a bare DB.
func NewDatebookDB(appInfo []byte) *pdb.DB {
	return &pdb.DB{
		Name:    "DatebookDB",
		Type:    pdb.FourCC("DATA"),
		Creator: pdb.FourCC("date"),
		AppInfo: appInfo,
	}
}
