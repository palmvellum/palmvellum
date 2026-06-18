package cardio

import (
	"os"
	"path/filepath"
	"sort"
	"testing"
)

func TestWriteFileNoSidecarAndCleanSweeps(t *testing.T) {
	dir := t.TempDir()

	// Simulate a Finder copy having left AppleDouble + .DS_Store behind.
	junk := []string{"._MemoDB.pdb", "._ToDoDB.pdb", ".DS_Store"}
	for _, n := range junk {
		if err := os.WriteFile(filepath.Join(dir, n), []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	// Our write must not create a "._" sidecar, and it drops the stale
	// "._MemoDB.pdb" for the file it writes.
	if err := WriteFile(filepath.Join(dir, "MemoDB.pdb"), []byte("data")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, "._MemoDB.pdb")); !os.IsNotExist(err) {
		t.Fatalf("WriteFile left a sidecar for MemoDB.pdb")
	}

	// Clean sweeps the remaining droppings WriteFile didn't touch.
	removed, err := Clean(dir)
	if err != nil {
		t.Fatal(err)
	}
	sort.Strings(removed)
	want := []string{".DS_Store", "._ToDoDB.pdb"}
	if len(removed) != len(want) || removed[0] != want[0] || removed[1] != want[1] {
		t.Fatalf("removed %v, want %v", removed, want)
	}

	// Only the real .pdb should survive.
	left, _ := os.ReadDir(dir)
	if len(left) != 1 || left[0].Name() != "MemoDB.pdb" {
		var names []string
		for _, e := range left {
			names = append(names, e.Name())
		}
		t.Fatalf("survivors %v, want [MemoDB.pdb]", names)
	}
}
