package maildb

import (
	"bytes"
	"testing"

	"github.com/palmvellum/palmvellum/packages/palm-engine/pdb"
)

func TestMailRoundTrip(t *testing.T) {
	want := []Mail{
		{UniqueID: 0x50, Year: 2026, Month: 6, Day: 19, Hour: 7, Min: 0,
			Subject: "2026-06-19 - 早報", From: "Tat Living Daily",
			Body: "今日重點…\n\n第二則新聞"},
		{UniqueID: 0x51, Subject: "no date", Body: "x"},
	}
	db := NewMailDB(nil)
	db.Records = EncodeMails(want)
	raw, err := db.Write()
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := pdb.Read(raw)
	if err != nil {
		t.Fatal(err)
	}
	got := DecodeMails(parsed)
	if len(got) != len(want) {
		t.Fatalf("count got %d want %d", len(got), len(want))
	}
	if got[0].Subject != "2026-06-19 - 早報" || got[0].From != "Tat Living Daily" {
		t.Errorf("subject/from: %+v", got[0])
	}
	if got[0].Body != "今日重點…\n\n第二則新聞" {
		t.Errorf("body: %q", got[0].Body)
	}
	if got[0].Year != 2026 || got[0].Month != 6 || got[0].Day != 19 {
		t.Errorf("date: %d-%d-%d", got[0].Year, got[0].Month, got[0].Day)
	}
	if got[1].Year != 0 || got[1].Subject != "no date" {
		t.Errorf("no-date record: %+v", got[1])
	}
}

func TestMailEncodeStable(t *testing.T) {
	m := Mail{Year: 2026, Month: 1, Day: 1, Subject: "s", From: "f", Body: "b"}
	b1 := m.encode()
	got, _ := decodeOne(b1)
	b2 := got.encode()
	if !bytes.Equal(b1, b2) {
		t.Fatalf("re-encode differs:\n %x\n %x", b1, b2)
	}
}
