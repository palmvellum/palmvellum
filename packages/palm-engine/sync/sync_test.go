package sync

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/palmvellum/palmvellum/packages/palm-engine/cloud"
	"github.com/palmvellum/palmvellum/packages/palm-engine/memodb"
	"github.com/palmvellum/palmvellum/packages/palm-engine/pdb"
)

// fakeCloud is an in-memory Cloud for tests.
type fakeCloud struct {
	rows map[string]*cloud.Record // keyed by id
}

func newFake() *fakeCloud { return &fakeCloud{rows: map[string]*cloud.Record{}} }

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
