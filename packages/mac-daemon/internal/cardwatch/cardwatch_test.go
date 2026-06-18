package cardwatch

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func mkSet(t *testing.T, root, vol, set string, dbs ...string) string {
	t.Helper()
	dir := filepath.Join(root, vol, "PALM", "PROGRAMS", "MSBackup", set)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	for _, db := range dbs {
		if err := os.WriteFile(filepath.Join(dir, db), []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}

func TestFindCardsPicksNewestSet(t *testing.T) {
	root := t.TempDir()
	// An empty volume (no PALM tree) must be ignored.
	_ = os.MkdirAll(filepath.Join(root, "Macintosh HD"), 0o755)

	old := mkSet(t, root, "NO NAME", "0", "MemoDB.pdb", "ToDoDB.pdb")
	newer := mkSet(t, root, "NO NAME", "1", "MemoDB.pdb")
	// Make set 1 newer.
	future := time.Now().Add(time.Hour)
	_ = os.Chtimes(filepath.Join(newer, "MemoDB.pdb"), future, future)
	_ = old

	cards := FindCards(root)
	if len(cards) != 1 {
		t.Fatalf("found %d cards, want 1", len(cards))
	}
	if cards[0].SetDir != newer {
		t.Fatalf("picked %s, want newest %s", cards[0].SetDir, newer)
	}
}

func TestFindCardsIgnoresEmptyMSBackup(t *testing.T) {
	root := t.TempDir()
	// MSBackup dir exists but the set has no .pdb files.
	mkSet(t, root, "EMPTY", "0") // no dbs
	if cards := FindCards(root); len(cards) != 0 {
		t.Fatalf("found %d cards, want 0", len(cards))
	}
}
