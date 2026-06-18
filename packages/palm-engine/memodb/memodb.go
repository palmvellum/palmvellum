// Package memodb encodes and decodes PalmOS Memo Pad's MemoDB.pdb
// records and AppInfo block.
//
// Each memo record is just a NUL-terminated text string. The memo's
// category is encoded in the low nibble of the record entry's
// attributes byte (0..15). The AppInfo block (286 bytes for MemoDB)
// carries the 16 category names + unique IDs.
//
// AppInfo layout for MemoDB (after the optional 2-byte pad that
// follows the record entry list):
//
//	0       2       renamedCategories bitmask (UInt16)
//	2       16*16   categoryLabels — NUL-padded ASCII names
//	258     16      categoryUniqIDs (UInt8 per category)
//	274     1       lastUniqueID (UInt8)
//	275     1       padding
//	276     2       reserved (UInt16)
//	278     2       sortByPriority (UInt16, 0 = manual, 1 = alphabetical)
//	         = 280 bytes total
package memodb

import (
	"encoding/binary"
	"errors"

	"github.com/palmvellum/palmvellum/packages/palm-engine/charset"
	"github.com/palmvellum/palmvellum/packages/palm-engine/pdb"
)

const (
	NumCategories   = 16
	CategoryNameLen = 16
	AppInfoLen      = 280
)

type Category struct {
	Name    string
	UniqID  uint8
	Renamed bool
}

type AppInfo struct {
	Categories     [NumCategories]Category
	LastUniqueID   uint8
	SortByPriority bool
}

// ParseAppInfo reads the categories AppInfo block.
func ParseAppInfo(b []byte) (*AppInfo, error) {
	if len(b) < 276 {
		return nil, errors.New("memodb: appinfo too short")
	}
	ai := &AppInfo{}
	renamed := binary.BigEndian.Uint16(b[0:2])
	for i := 0; i < NumCategories; i++ {
		off := 2 + i*CategoryNameLen
		end := off + CategoryNameLen
		// NUL-terminated string inside 16 bytes
		nameEnd := off
		for nameEnd < end && b[nameEnd] != 0 {
			nameEnd++
		}
		ai.Categories[i].Name = charset.FromPalm(b[off:nameEnd])
		ai.Categories[i].UniqID = b[2+NumCategories*CategoryNameLen+i]
		ai.Categories[i].Renamed = (renamed & (1 << i)) != 0
	}
	ai.LastUniqueID = b[2+NumCategories*CategoryNameLen+NumCategories]
	if len(b) >= 280 {
		// bytes 278..279 = sortByPriority
		ai.SortByPriority = binary.BigEndian.Uint16(b[278:280]) != 0
	}
	return ai, nil
}

// Encode emits the standard 280-byte MemoDB AppInfo block.
func (ai *AppInfo) Encode() []byte {
	out := make([]byte, AppInfoLen)
	var renamed uint16
	for i := range ai.Categories {
		if ai.Categories[i].Renamed {
			renamed |= 1 << i
		}
	}
	binary.BigEndian.PutUint16(out[0:2], renamed)
	for i, c := range ai.Categories {
		off := 2 + i*CategoryNameLen
		name := charset.ToPalm(c.Name)
		if len(name) > CategoryNameLen-1 {
			name = name[:CategoryNameLen-1]
		}
		copy(out[off:off+CategoryNameLen], name)
		out[2+NumCategories*CategoryNameLen+i] = c.UniqID
	}
	out[2+NumCategories*CategoryNameLen+NumCategories] = ai.LastUniqueID
	// 275 = padding
	// 276..277 = reserved
	if ai.SortByPriority {
		binary.BigEndian.PutUint16(out[278:280], 1)
	}
	return out
}

// FindCategoryByName returns the slot index (0..15) of the named
// category, or (0,false) if not present.
func (ai *AppInfo) FindCategoryByName(name string) (uint8, bool) {
	for i, c := range ai.Categories {
		if c.Name == name {
			return uint8(i), true
		}
	}
	return 0, false
}

// EnsureCategory makes sure a category with the given name exists.
// Returns its slot index. If a fresh slot is allocated, the AppInfo
// is mutated (caller should re-encode).
func (ai *AppInfo) EnsureCategory(name string) uint8 {
	if idx, ok := ai.FindCategoryByName(name); ok {
		return idx
	}
	// Find first empty slot (1..14; slot 0 is Unfiled, slot 15 is
	// system-reserved on real Palms).
	for i := 1; i < NumCategories-1; i++ {
		if ai.Categories[i].Name == "" {
			ai.LastUniqueID++
			ai.Categories[i].Name = name
			ai.Categories[i].UniqID = ai.LastUniqueID
			ai.Categories[i].Renamed = true
			return uint8(i)
		}
	}
	return 0 // out of slots — fall back to Unfiled
}

// CategoryName returns the human-readable name of a category index,
// or "Unfiled" for index 0 / unknown.
func (ai *AppInfo) CategoryName(idx uint8) string {
	if idx >= NumCategories {
		return "Unfiled"
	}
	name := ai.Categories[idx].Name
	if name == "" {
		return "Unfiled"
	}
	return name
}

// DefaultAppInfo returns an AppInfo seeded with the PalmOS factory
// categories — slot 0 "Unfiled", slot 1 "Personal", slot 2 "Business".
// All other slots are empty; EnsureCategory("AI") will land in slot 3.
func DefaultAppInfo() *AppInfo {
	ai := &AppInfo{}
	ai.Categories[0] = Category{Name: "Unfiled", UniqID: 0}
	ai.Categories[1] = Category{Name: "Personal", UniqID: 1}
	ai.Categories[2] = Category{Name: "Business", UniqID: 2}
	ai.LastUniqueID = 15
	return ai
}

// ─── Memo records ──────────────────────────────────────────────

type Memo struct {
	UniqueID uint32
	Category uint8
	Text     string
}

// DecodeMemos extracts all memo records from a parsed MemoDB.
func DecodeMemos(db *pdb.DB) []Memo {
	out := make([]Memo, 0, len(db.Records))
	for _, r := range db.Records {
		txt := r.Data
		// Trim trailing NULs (each memo is NUL-terminated)
		for len(txt) > 0 && txt[len(txt)-1] == 0 {
			txt = txt[:len(txt)-1]
		}
		out = append(out, Memo{
			UniqueID: r.UniqueID,
			Category: r.Attributes & 0x0F,
			Text:     charset.FromPalm(txt),
		})
	}
	return out
}

// EncodeMemos turns Memo structs into pdb.Record entries ready for
// pdb.DB.Write. Category is packed into the low nibble of attributes;
// each record's bytes are the memo text plus a single NUL terminator.
func EncodeMemos(memos []Memo) []pdb.Record {
	out := make([]pdb.Record, 0, len(memos))
	for _, m := range memos {
		enc := charset.ToPalm(m.Text)
		data := make([]byte, 0, len(enc)+1)
		data = append(data, enc...)
		data = append(data, 0)
		out = append(out, pdb.Record{
			UniqueID:   m.UniqueID,
			Attributes: m.Category & 0x0F,
			Data:       data,
		})
	}
	return out
}

// NewMemoDB constructs an empty MemoDB skeleton with the standard
// name/type/creator and the supplied AppInfo block already encoded.
func NewMemoDB(ai *AppInfo) *pdb.DB {
	return &pdb.DB{
		Name:    "MemoDB",
		Type:    pdb.FourCC("DATA"),
		Creator: pdb.FourCC("memo"),
		AppInfo: ai.Encode(),
	}
}
