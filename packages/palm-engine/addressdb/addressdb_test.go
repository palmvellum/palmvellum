package addressdb

import (
	"bytes"
	"testing"

	"github.com/palmvellum/palmvellum/packages/palm-engine/pdb"
)

func TestContactRoundTrip(t *testing.T) {
	want := []Contact{
		{
			UniqueID: 0x200, Category: 1,
			Last: "陳", First: "大文", Company: "Tat Living",
			Title: "Founder",
			Phones: []Phone{
				{Label: "Work", Value: "+852 1234 5678"},
				{Label: "Mobile", Value: "+852 9000 0000"},
				{Label: "E-mail", Value: "hello@tatliving.dev"},
			},
			Address: "1 Queen's Rd", City: "Central", Country: "HK",
			Note:   "重要客戶",
			Custom: [4]string{"c1", "", "", ""},
		},
		{UniqueID: 0x201, First: "Solo"},
	}
	db := NewAddressDB(nil)
	db.Records = EncodeContacts(want)
	raw, err := db.Write()
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := pdb.Read(raw)
	if err != nil {
		t.Fatal(err)
	}
	got := DecodeContacts(parsed)
	if len(got) != len(want) {
		t.Fatalf("count got %d want %d", len(got), len(want))
	}
	g := got[0]
	if g.Last != "陳" || g.First != "大文" || g.Company != "Tat Living" {
		t.Errorf("name/company: %+v", g)
	}
	if g.Note != "重要客戶" || g.Custom[0] != "c1" {
		t.Errorf("note/custom: note=%q custom=%v", g.Note, g.Custom)
	}
	if len(g.Phones) != 3 || g.Phones[0].Label != "Work" ||
		g.Phones[1].Label != "Mobile" || g.Phones[2].Label != "E-mail" {
		t.Errorf("phones: %+v", g.Phones)
	}
	if g.Phones[1].Value != "+852 9000 0000" {
		t.Errorf("phone value: %q", g.Phones[1].Value)
	}
	if g.City != "Central" || g.Country != "HK" {
		t.Errorf("address fields: %+v", g)
	}
	if got[1].First != "Solo" {
		t.Errorf("contact 2: %+v", got[1])
	}
}

func TestEncodeStableAndCompanyOffset(t *testing.T) {
	c := Contact{
		Last: "Wong", First: "Ada", Company: "ACME",
		Phones: []Phone{{Label: "Home", Value: "111"}},
	}
	b1 := c.encode()
	// byte 8 should point at the company string: 9 + len("Wong\0") + len("Ada\0") - 8
	wantOff := byte(9 + len("Wong") + 1 + len("Ada") + 1 - 8)
	if b1[8] != wantOff {
		t.Errorf("company offset = %d, want %d", b1[8], wantOff)
	}
	got, _ := decodeOne(b1)
	b2 := got.encode()
	if !bytes.Equal(b1, b2) {
		t.Fatalf("re-encode differs:\n %x\n %x", b1, b2)
	}
}
