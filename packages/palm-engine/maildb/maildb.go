// Package maildb encodes and decodes PalmOS Mail's MailDB.
//
// Record layout (per pilot-link libpisock/mail.c):
//
//	0..1  UInt16 packed date ((year-1904)<<9 | month<<5 | day; 0 = none)
//	2     UInt8  hour
//	3     UInt8  minute
//	4     UInt8  flags  (bit7 read, bit6 signature, bit5 confirmRead,
//	                     bit4 confirmDelivery, bits3-2 priority, bits1-0 addressing)
//	5     UInt8  reserved (0)
//	6..   eight fields in order — subject, from, to, cc, bcc, replyTo,
//	      sentTo, body — each a NUL-terminated string; an absent field is
//	      a single 0x00 byte (empty string).
//
// PalmVellum uses Mail one-way: cloud "mail" digests are written into the
// Palm Inbox so they can be read on-device. Decode is provided for
// round-trip tests and completeness.
package maildb

import (
	"github.com/palmvellum/palmvellum/packages/palm-engine/charset"
	"github.com/palmvellum/palmvellum/packages/palm-engine/memodb"
	"github.com/palmvellum/palmvellum/packages/palm-engine/pdb"
)

const flagRead = 0x80

// Mail is one message. Only the fields PalmVellum maps are first-class;
// the rest round-trip through the codec.
type Mail struct {
	UniqueID uint32
	Category uint8

	Year, Month, Day int
	Hour, Min        uint8
	Read             bool

	Subject string
	From    string
	To      string
	Cc      string
	Bcc     string
	ReplyTo string
	SentTo  string
	Body    string
}

func packDate(y, m, d int) uint16 {
	if y < 1904 || m < 1 || d < 1 {
		return 0
	}
	return uint16(y-1904)<<9 | uint16(m)<<5 | uint16(d)
}

func unpackDate(v uint16) (y, m, d int) {
	if v == 0 {
		return 0, 0, 0
	}
	return int(v>>9) + 1904, int((v >> 5) & 0x0F), int(v & 0x1F)
}

// DecodeMails parses all records of a MailDB.
func DecodeMails(db *pdb.DB) []Mail {
	out := make([]Mail, 0, len(db.Records))
	for _, r := range db.Records {
		m, ok := decodeOne(r.Data)
		if !ok {
			continue
		}
		m.UniqueID = r.UniqueID
		m.Category = r.Attributes & 0x0F
		out = append(out, m)
	}
	return out
}

func decodeOne(b []byte) (Mail, bool) {
	if len(b) < 6 {
		return Mail{}, false
	}
	var m Mail
	m.Year, m.Month, m.Day = unpackDate(uint16(b[0])<<8 | uint16(b[1]))
	m.Hour, m.Min = b[2], b[3]
	m.Read = b[4]&flagRead != 0

	p := 6
	fields := []*string{&m.Subject, &m.From, &m.To, &m.Cc, &m.Bcc, &m.ReplyTo, &m.SentTo, &m.Body}
	for _, f := range fields {
		if p >= len(b) {
			break
		}
		if b[p] == 0 { // absent / empty
			p++
			continue
		}
		raw, n := readCStr(b[p:])
		*f = charset.FromPalm(raw)
		p += n
	}
	return m, true
}

func readCStr(b []byte) (raw []byte, consumed int) {
	for i, c := range b {
		if c == 0 {
			return b[:i], i + 1
		}
	}
	return b, len(b)
}

// EncodeMails turns Mails into pdb records.
func EncodeMails(mails []Mail) []pdb.Record {
	out := make([]pdb.Record, 0, len(mails))
	for _, m := range mails {
		out = append(out, pdb.Record{
			UniqueID:   m.UniqueID,
			Attributes: m.Category & 0x0F,
			Data:       m.encode(),
		})
	}
	return out
}

func (m Mail) encode() []byte {
	buf := make([]byte, 6)
	d := packDate(m.Year, m.Month, m.Day)
	buf[0], buf[1] = byte(d>>8), byte(d)
	buf[2], buf[3] = m.Hour, m.Min
	if m.Read {
		buf[4] |= flagRead
	}
	// buf[5] reserved = 0
	for _, s := range []string{m.Subject, m.From, m.To, m.Cc, m.Bcc, m.ReplyTo, m.SentTo, m.Body} {
		if s == "" {
			buf = append(buf, 0)
			continue
		}
		buf = append(buf, charset.ToPalm(s)...)
		buf = append(buf, 0)
	}
	return buf
}

// NewMailDB builds an empty MailDB shell; appInfo (folder categories) is
// reused verbatim from the card, or defaults when nil.
func NewMailDB(appInfo []byte) *pdb.DB {
	if appInfo == nil {
		appInfo = memodb.DefaultAppInfo().Encode()
	}
	return &pdb.DB{
		Name:    "MailDB",
		Type:    pdb.FourCC("DATA"),
		Creator: pdb.FourCC("mail"),
		AppInfo: appInfo,
	}
}
