package datebookdb

import (
	"bytes"
	"testing"

	"github.com/palmvellum/palmvellum/packages/palm-engine/pdb"
)

func TestAppointmentRoundTrip(t *testing.T) {
	want := []Appointment{
		{
			UniqueID: 0x100, StartHour: 9, StartMin: 30, EndHour: 10, EndMin: 0,
			Year: 2026, Month: 6, Day: 19,
			Description: "會議 standup", Note: "帶筆記",
			HasAlarm: true, AlarmAdvance: 5, AlarmUnit: 0,
		},
		{
			UniqueID: 0x101, Untimed: true,
			Year: 2026, Month: 12, Day: 25,
			Description: "Christmas",
		},
		{
			UniqueID: 0x102, StartHour: 14, StartMin: 0, EndHour: 15, EndMin: 0,
			Year: 2026, Month: 7, Day: 1,
			Description: "weekly", RepeatRaw: []byte{1, 0, 0xff, 0xff, 1, 0, 0, 0},
		},
	}
	db := NewDatebookDB(nil)
	db.Records = EncodeAppointments(want)
	raw, err := db.Write()
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := pdb.Read(raw)
	if err != nil {
		t.Fatal(err)
	}
	got := DecodeAppointments(parsed)
	if len(got) != len(want) {
		t.Fatalf("count: got %d want %d", len(got), len(want))
	}
	for i := range want {
		w, g := want[i], got[i]
		if g.Description != w.Description || g.Note != w.Note {
			t.Errorf("[%d] text: got desc=%q note=%q want desc=%q note=%q", i, g.Description, g.Note, w.Description, w.Note)
		}
		if g.Untimed != w.Untimed {
			t.Errorf("[%d] untimed: got %v want %v", i, g.Untimed, w.Untimed)
		}
		if !w.Untimed && (g.StartHour != w.StartHour || g.EndMin != w.EndMin) {
			t.Errorf("[%d] time mismatch: %+v vs %+v", i, g, w)
		}
		if g.Year != w.Year || g.Month != w.Month || g.Day != w.Day {
			t.Errorf("[%d] date: got %d-%d-%d", i, g.Year, g.Month, g.Day)
		}
		if g.HasAlarm != w.HasAlarm || g.AlarmAdvance != w.AlarmAdvance {
			t.Errorf("[%d] alarm mismatch", i)
		}
		if !bytes.Equal(g.RepeatRaw, w.RepeatRaw) {
			t.Errorf("[%d] repeat raw: got %x want %x", i, g.RepeatRaw, w.RepeatRaw)
		}
	}
}

// A single encoded record decoded then re-encoded must be byte-identical.
func TestEncodeStable(t *testing.T) {
	a := Appointment{
		UniqueID: 1, StartHour: 8, StartMin: 0, EndHour: 9, EndMin: 15,
		Year: 2026, Month: 1, Day: 2, Description: "x", Note: "y",
		HasAlarm: true, AlarmAdvance: 10, AlarmUnit: 1,
	}
	b1 := a.encode()
	got, _ := decodeOne(b1)
	got.UniqueID = a.UniqueID
	b2 := got.encode()
	if !bytes.Equal(b1, b2) {
		t.Fatalf("re-encode differs:\n %x\n %x", b1, b2)
	}
}
