package memodb

import (
	"testing"

	"github.com/palmvellum/palmvellum/packages/palm-engine/pdb"
)

// A memo with Traditional Chinese + an ASCII AI separator must survive
// the full Encode → .pdb bytes → Parse → Decode cycle unchanged, proving
// the Big5 conversion is wired into both directions of the codec.
func TestMemoChineseRoundTrip(t *testing.T) {
	want := []Memo{
		{UniqueID: 0x111, Category: 0, Text: "買電池\n-- AI --\n好的"},
		{UniqueID: 0x112, Category: 1, Text: "plain ascii memo"},
	}
	db := NewMemoDB(DefaultAppInfo())
	db.Records = EncodeMemos(want)

	raw, err := db.Write()
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := pdb.Read(raw)
	if err != nil {
		t.Fatal(err)
	}
	got := DecodeMemos(parsed)

	if len(got) != len(want) {
		t.Fatalf("count: got %d want %d", len(got), len(want))
	}
	for i := range want {
		if got[i].Text != want[i].Text {
			t.Errorf("memo[%d] text: got %q want %q", i, got[i].Text, want[i].Text)
		}
		if got[i].Category != want[i].Category {
			t.Errorf("memo[%d] cat: got %d want %d", i, got[i].Category, want[i].Category)
		}
	}
}

func TestChineseCategoryRoundTrip(t *testing.T) {
	ai := DefaultAppInfo()
	idx := ai.EnsureCategory("工作")
	raw := ai.Encode()
	got, err := ParseAppInfo(raw)
	if err != nil {
		t.Fatal(err)
	}
	if got.Categories[idx].Name != "工作" {
		t.Fatalf("category: got %q want 工作", got.Categories[idx].Name)
	}
}
