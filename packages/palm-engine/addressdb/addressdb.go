// Package addressdb encodes and decodes PalmOS Address Book's AddressDB.
//
// Record layout (per pilot-link libpisock/address.c):
//
//	0..3   UInt32  phoneflag = pl0<<0 | pl1<<4 | pl2<<8 | pl3<<12 |
//	                           pl4<<16 | showPhone<<20   (5 phone-slot
//	                           label indices + which phone shows in list)
//	4..7   UInt32  contents  (bit v set ⇒ entry[v] present; 19 fields)
//	8      UInt8   company string offset (for list sort; (pos-8) when
//	               the company field is written, else 0)
//	9..    present entries, index order 0..18, each NUL-terminated
//
// Field indices: 0 last, 1 first, 2 company, 3..7 phone1..5 values,
// 8 address, 9 city, 10 state, 11 zip, 12 country, 13 title,
// 14..17 custom1..4, 18 note. The phone-slot label index (0..7) selects
// a type name from phoneLabelNames.
package addressdb

import (
	"encoding/binary"

	"github.com/palmvellum/palmvellum/packages/palm-engine/charset"
	"github.com/palmvellum/palmvellum/packages/palm-engine/memodb"
	"github.com/palmvellum/palmvellum/packages/palm-engine/pdb"
)

const numEntries = 19

// Entry indices (0..18).
const (
	eLast = iota
	eFirst
	eCompany
	ePhone1 // 3..7 are the five phone-value slots
	ePhone2
	ePhone3
	ePhone4
	ePhone5
	eAddress
	eCity
	eState
	eZip
	eCountry
	eTitle
	eCustom1 // 14..17
	eCustom2
	eCustom3
	eCustom4
	eNote // 18
)

// phoneLabelNames maps a phone-slot label index (0..7) to its type name,
// matching the stock AddressDB label table.
var phoneLabelNames = [8]string{"Work", "Home", "Fax", "Other", "E-mail", "Main", "Pager", "Mobile"}

// PhoneLabelIndex returns the slot-label index for a type name, or 0 (Work).
func PhoneLabelIndex(name string) uint8 {
	for i, n := range phoneLabelNames {
		if n == name {
			return uint8(i)
		}
	}
	return 0
}

// Phone is one phone/email slot.
type Phone struct {
	Label string // Work/Home/Fax/Other/E-mail/Main/Pager/Mobile
	Value string
}

// Contact is one address-book entry.
type Contact struct {
	UniqueID  uint32
	Category  uint8
	Last      string
	First     string
	Company   string
	Title     string
	Phones    []Phone // up to 5, in slot order
	ShowPhone uint8   // which phone slot (0..4) shows in the list
	Address   string
	City      string
	State     string
	Zip       string
	Country   string
	Note      string
	Custom    [4]string
}

// DecodeContacts parses all records of an AddressDB.
func DecodeContacts(db *pdb.DB) []Contact {
	out := make([]Contact, 0, len(db.Records))
	for _, r := range db.Records {
		c, ok := decodeOne(r.Data)
		if !ok {
			continue
		}
		c.UniqueID = r.UniqueID
		c.Category = r.Attributes & 0x0F
		out = append(out, c)
	}
	return out
}

func decodeOne(b []byte) (Contact, bool) {
	if len(b) < 9 {
		return Contact{}, false
	}
	var c Contact
	phoneflag := binary.BigEndian.Uint32(b[0:4])
	pl := [5]uint8{
		uint8(phoneflag>>0) & 0x0F,
		uint8(phoneflag>>4) & 0x0F,
		uint8(phoneflag>>8) & 0x0F,
		uint8(phoneflag>>12) & 0x0F,
		uint8(phoneflag>>16) & 0x0F,
	}
	c.ShowPhone = uint8(phoneflag>>20) & 0x0F
	contents := binary.BigEndian.Uint32(b[4:8])

	entries := make([]string, numEntries)
	p := 9
	for v := 0; v < numEntries; v++ {
		if contents&(1<<uint(v)) == 0 {
			continue
		}
		raw, n := readCStr(b[p:])
		entries[v] = charset.FromPalm(raw)
		p += n
		if p > len(b) {
			p = len(b)
		}
	}

	c.Last, c.First, c.Company = entries[eLast], entries[eFirst], entries[eCompany]
	c.Address, c.City, c.State = entries[eAddress], entries[eCity], entries[eState]
	c.Zip, c.Country, c.Title = entries[eZip], entries[eCountry], entries[eTitle]
	c.Note = entries[eNote]
	c.Custom = [4]string{entries[eCustom1], entries[eCustom1+1], entries[eCustom1+2], entries[eCustom1+3]}
	for slot := 0; slot < 5; slot++ {
		val := entries[ePhone1+slot]
		if val == "" {
			continue
		}
		c.Phones = append(c.Phones, Phone{Label: phoneLabelNames[pl[slot]], Value: val})
	}
	return c, true
}

func readCStr(b []byte) (raw []byte, consumed int) {
	for i, ch := range b {
		if ch == 0 {
			return b[:i], i + 1
		}
	}
	return b, len(b)
}

// EncodeContacts turns Contacts into pdb records.
func EncodeContacts(cs []Contact) []pdb.Record {
	out := make([]pdb.Record, 0, len(cs))
	for _, c := range cs {
		out = append(out, pdb.Record{
			UniqueID:   c.UniqueID,
			Attributes: c.Category & 0x0F,
			Data:       c.encode(),
		})
	}
	return out
}

func (c Contact) encode() []byte {
	// Build the 19 entry strings (Big5).
	entries := make([][]byte, numEntries)
	set := func(i int, s string) {
		if s != "" {
			entries[i] = charset.ToPalm(s)
		}
	}
	set(eLast, c.Last)
	set(eFirst, c.First)
	set(eCompany, c.Company)
	set(eAddress, c.Address)
	set(eCity, c.City)
	set(eState, c.State)
	set(eZip, c.Zip)
	set(eCountry, c.Country)
	set(eTitle, c.Title)
	set(eNote, c.Note)
	for i := 0; i < 4; i++ {
		set(eCustom1+i, c.Custom[i])
	}

	// Phone slots + label indices.
	var pl [5]uint8
	for slot := 0; slot < 5 && slot < len(c.Phones); slot++ {
		pl[slot] = PhoneLabelIndex(c.Phones[slot].Label)
		set(ePhone1+slot, c.Phones[slot].Value)
	}

	var contents uint32
	for v := 0; v < numEntries; v++ {
		if len(entries[v]) > 0 {
			contents |= 1 << uint(v)
		}
	}

	phoneflag := uint32(pl[0]) | uint32(pl[1])<<4 | uint32(pl[2])<<8 |
		uint32(pl[3])<<12 | uint32(pl[4])<<16 | uint32(c.ShowPhone)<<20

	buf := make([]byte, 9)
	binary.BigEndian.PutUint32(buf[0:4], phoneflag)
	binary.BigEndian.PutUint32(buf[4:8], contents)

	// company offset byte (8): pos of company string within record minus 8.
	var companyOffset byte
	pos := 9
	for v := 0; v < numEntries; v++ {
		if len(entries[v]) == 0 {
			continue
		}
		if v == eCompany {
			companyOffset = byte(pos - 8)
		}
		buf = append(buf, entries[v]...)
		buf = append(buf, 0)
		pos += len(entries[v]) + 1
	}
	buf[8] = companyOffset
	return buf
}

// DisplayName is the list label the cloud stores in records.body.
func (c Contact) DisplayName() string {
	switch {
	case c.Last != "" && c.First != "":
		return c.First + " " + c.Last
	case c.Last != "":
		return c.Last
	case c.First != "":
		return c.First
	case c.Company != "":
		return c.Company
	default:
		return "(no name)"
	}
}

// AppInfoLen is the AddressDB AppInfo size: a 278-byte category block +
// 4-byte labelRenamed + 22×16 labels + 2-byte country + sortByCompany +
// pad.
const AppInfoLen = 638

// stdLabels are the 22 stock AddressDB field labels.
var stdLabels = [22]string{
	"Last name", "First name", "Company", "Work", "Home", "Fax", "Other",
	"E-mail", "Address", "City", "State", "Zip Code", "Country", "Title",
	"Custom 1", "Custom 2", "Custom 3", "Custom 4", "Note", "Main", "Pager", "Mobile",
}

// DefaultAppInfo builds a valid 638-byte AddressDB AppInfo (stock
// categories + the 22 stock field labels). Used only when no card AppInfo
// is available; normally the card's own AppInfo is preserved verbatim so
// any user-renamed labels/categories survive.
func DefaultAppInfo() []byte {
	buf := make([]byte, AppInfoLen)
	// Category block (first 278 bytes) — reuse memodb's category encoder,
	// which lays out the identical renamed/names/uids structure, and take
	// its first 278 bytes (its bytes 278-279 are memo's sortByPriority,
	// which AddressDB does not use — left zero here).
	mem := memodb.DefaultAppInfo()
	mem.Categories[1] = memodb.Category{Name: "Business", UniqID: 1, Renamed: true}
	mem.Categories[2] = memodb.Category{Name: "Personal", UniqID: 2, Renamed: true}
	mem.Categories[3] = memodb.Category{Name: "QuickList", UniqID: 3, Renamed: true}
	copy(buf[0:278], mem.Encode())
	// labelRenamed (bytes 278-281) left zero. Labels start at 282.
	for i, l := range stdLabels {
		off := 282 + i*16
		copy(buf[off:off+15], []byte(l))
	}
	// country (634-635), sortByCompany (636), pad (637) left zero.
	return buf
}

// NewAddressDB builds an empty AddressDB shell. appInfo (with the 22
// labels + categories) is reused verbatim from the card; pass nil to get
// a valid stock 638-byte AppInfo.
func NewAddressDB(appInfo []byte) *pdb.DB {
	// A valid AddressDB AppInfo is 638 bytes (categories + 22 field
	// labels). Anything shorter — nil, or a block a previous bug shrank to
	// the 280-byte memo layout — is rebuilt from stock defaults so the
	// field labels are present and the Palm restore won't fault.
	if len(appInfo) < AppInfoLen {
		appInfo = DefaultAppInfo()
	}
	return &pdb.DB{
		Name:    "AddressDB",
		Type:    pdb.FourCC("DATA"),
		Creator: pdb.FourCC("addr"),
		AppInfo: appInfo,
	}
}
