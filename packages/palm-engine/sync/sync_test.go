package sync

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/palmvellum/palmvellum/packages/palm-engine/addressdb"
	"github.com/palmvellum/palmvellum/packages/palm-engine/cloud"
	"github.com/palmvellum/palmvellum/packages/palm-engine/datebookdb"
	"github.com/palmvellum/palmvellum/packages/palm-engine/memodb"
	"github.com/palmvellum/palmvellum/packages/palm-engine/pdb"
)

// fakeCloud is an in-memory Cloud for tests.
type fakeCloud struct {
	rows   map[string]*cloud.Record // keyed by id
	events map[string]*cloud.Event  // keyed by id
}

func newFake() *fakeCloud {
	return &fakeCloud{rows: map[string]*cloud.Record{}, events: map[string]*cloud.Event{}}
}

func (f *fakeCloud) ListByType(userID string, types ...string) ([]cloud.Record, error) {
	want := map[string]bool{}
	for _, t := range types {
		want[t] = true
	}
	var out []cloud.Record
	for _, r := range f.rows {
		if r.UserID == userID && want[r.Type] {
			out = append(out, *r)
		}
	}
	return out, nil
}

func (f *fakeCloud) ListEventsForUser(userID string) ([]cloud.Event, error) {
	var out []cloud.Event
	for _, e := range f.events {
		if e.UserID == userID {
			out = append(out, *e)
		}
	}
	return out, nil
}
func (f *fakeCloud) FindEventByDevice(userID, deviceID string) (string, error) {
	for id, e := range f.events {
		if e.UserID == userID && e.DeviceID != nil && *e.DeviceID == deviceID {
			return id, nil
		}
	}
	return "", nil
}
func (f *fakeCloud) InsertEvent(e cloud.Event) error {
	cp := e
	f.events[e.ID] = &cp
	return nil
}
func (f *fakeCloud) UpdateEvent(id string, patch map[string]any) error {
	e, ok := f.events[id]
	if !ok {
		return nil
	}
	if v, ok := patch["device_id"].(string); ok {
		e.DeviceID = &v
	}
	if v, ok := patch["title"].(string); ok {
		e.Title = v
	}
	return nil
}

func (f *fakeCloud) FindByDevice(userID, deviceID string) (string, error) {
	for id, r := range f.rows {
		if r.UserID == userID && r.DeviceID != nil && *r.DeviceID == deviceID {
			return id, nil
		}
	}
	return "", nil
}
func (f *fakeCloud) Insert(r cloud.Record) error {
	cp := r
	f.rows[r.ID] = &cp
	return nil
}
func (f *fakeCloud) Update(id string, patch map[string]any) error {
	r, ok := f.rows[id]
	if !ok {
		return nil
	}
	if v, ok := patch["body"].(string); ok {
		r.Body = v
	}
	if v, ok := patch["device_id"].(string); ok {
		r.DeviceID = &v
	}
	return nil
}
func (f *fakeCloud) ListForUser(userID string) ([]cloud.Record, error) {
	var out []cloud.Record
	for _, r := range f.rows {
		if r.UserID == userID {
			out = append(out, *r)
		}
	}
	return out, nil
}

func writeMemoCard(t *testing.T, dir string, memos []memodb.Memo) string {
	t.Helper()
	db := memodb.NewMemoDB(memodb.DefaultAppInfo())
	db.Records = memodb.EncodeMemos(memos)
	raw, err := db.Write()
	if err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(dir, "MemoDB.pdb")
	if err := os.WriteFile(p, raw, 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestMemoPushPullRoundTrip(t *testing.T) {
	const uid = "u-123"
	f := newFake()
	dir := t.TempDir()
	path := writeMemoCard(t, dir, []memodb.Memo{
		{UniqueID: 0x10, Category: 0, Text: "買電池"}, // Chinese, non-AI → thought
		{UniqueID: 0x11, Category: 0, Text: ""},      // blank → skipped
	})

	data, _ := os.ReadFile(path)
	push, err := MemoPush(f, uid, data)
	if err != nil {
		t.Fatal(err)
	}
	if push.Inserted != 1 || push.Skipped != 1 {
		t.Fatalf("push = %+v, want 1 inserted / 1 skipped", push)
	}

	pull, err := MemoPull(f, uid, path)
	if err != nil {
		t.Fatal(err)
	}
	if pull.Written != 1 {
		t.Fatalf("pull wrote %d, want 1", pull.Written)
	}

	// The pulled card must contain the Chinese memo verbatim.
	raw, _ := os.ReadFile(path)
	parsed, err := pdb.Read(raw)
	if err != nil {
		t.Fatal(err)
	}
	got := memodb.DecodeMemos(parsed)
	if len(got) != 1 || got[0].Text != "買電池" {
		t.Fatalf("round-trip memos = %+v, want [買電池]", got)
	}
}

func TestSyncCardDatebookAndAddress(t *testing.T) {
	const uid = "u-pim"
	f := newFake()
	dir := t.TempDir()

	// Date Book card with one timed appointment.
	ddb := datebookdb.NewDatebookDB(nil)
	ddb.Records = datebookdb.EncodeAppointments([]datebookdb.Appointment{
		{UniqueID: 0x30, StartHour: 9, StartMin: 0, EndHour: 10, EndMin: 0,
			Year: 2026, Month: 6, Day: 19, Description: "標準會議"},
	})
	draw, _ := ddb.Write()
	if err := os.WriteFile(filepath.Join(dir, "DatebookDB.pdb"), draw, 0o644); err != nil {
		t.Fatal(err)
	}

	// Address card with one contact.
	adb := addressdb.NewAddressDB(nil)
	adb.Records = addressdb.EncodeContacts([]addressdb.Contact{
		{UniqueID: 0x40, First: "Ada", Last: "黃", Company: "ACME",
			Phones: []addressdb.Phone{{Label: "Mobile", Value: "555"}}},
	})
	araw, _ := adb.Write()
	if err := os.WriteFile(filepath.Join(dir, "AddressDB.pdb"), araw, 0o644); err != nil {
		t.Fatal(err)
	}

	res, err := SyncCard(f, uid, dir)
	if err != nil {
		t.Fatal(err)
	}
	if res.Datebook == nil || res.Datebook.Inserted != 1 {
		t.Fatalf("datebook push: %+v", res.Datebook)
	}
	if res.Address == nil || res.Address.Inserted != 1 {
		t.Fatalf("address push: %+v", res.Address)
	}
	if len(f.events) != 1 {
		t.Fatalf("events in cloud: %d", len(f.events))
	}

	// Pull regenerated the card; decode back and check the contact name.
	araw2, _ := os.ReadFile(filepath.Join(dir, "AddressDB.pdb"))
	pdb2, _ := pdb.Read(araw2)
	cs := addressdb.DecodeContacts(pdb2)
	if len(cs) != 1 || cs[0].Last != "黃" || cs[0].Company != "ACME" {
		t.Fatalf("pulled contacts: %+v", cs)
	}
	if len(cs[0].Phones) != 1 || cs[0].Phones[0].Label != "Mobile" {
		t.Fatalf("pulled phones: %+v", cs[0].Phones)
	}
}

func TestWaitForAI(t *testing.T) {
	const uid = "u"
	dev := "memo:000abc"
	pending := "pending"
	f := newFake()
	f.rows["r1"] = &cloud.Record{ID: "r1", UserID: uid, Type: "aiquery", DeviceID: &dev, AIStatus: &pending}

	// Still pending → times out with 0 finished.
	if n := WaitForAI(f, uid, []string{dev}, 80*time.Millisecond, 20*time.Millisecond, nil); n != 0 {
		t.Fatalf("pending: want 0 done, got %d", n)
	}

	// Worker writes the answer → terminal, returns 1 quickly.
	done := "done"
	f.rows["r1"].AIStatus = &done
	if n := WaitForAI(f, uid, []string{dev}, time.Second, 10*time.Millisecond, nil); n != 1 {
		t.Fatalf("done: want 1, got %d", n)
	}
}

func TestSyncCardCleansJunk(t *testing.T) {
	const uid = "u-9"
	f := newFake()
	dir := t.TempDir()
	writeMemoCard(t, dir, []memodb.Memo{{UniqueID: 0x20, Text: "hi"}})
	// Finder droppings that would soft-reset the Palm.
	_ = os.WriteFile(filepath.Join(dir, "._MemoDB.pdb"), []byte("x"), 0o644)
	_ = os.WriteFile(filepath.Join(dir, ".DS_Store"), []byte("x"), 0o644)

	res, err := SyncCard(f, uid, dir)
	if err != nil {
		t.Fatal(err)
	}
	if res.Memo == nil || res.Memo.Inserted != 1 {
		t.Fatalf("memo push = %+v", res.Memo)
	}
	// No macOS droppings may survive.
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if e.Name() == "._MemoDB.pdb" || e.Name() == ".DS_Store" {
			t.Fatalf("junk survived: %s", e.Name())
		}
	}
}
