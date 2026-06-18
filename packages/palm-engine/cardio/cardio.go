// Package cardio handles writing PalmOS databases onto a removable card
// (Memory Stick / SD) without leaving the macOS metadata droppings that
// break a Sony MS Backup "restore from card".
//
// Two macOS artefacts are fatal to restore:
//
//   - AppleDouble files ("._MemoDB.pdb"): created when extended
//     attributes are copied to a FAT volume (Finder, `cp -p`, the
//     copyfile(3) family). MS Backup tries to restore them as databases,
//     fails, and the device soft-resets.
//   - ".DS_Store": Finder folder metadata; harmless to the Palm but still
//     clutter we remove for good measure.
//
// WriteFile writes the .pdb with a plain byte write (os.WriteFile never
// emits an AppleDouble), then Clean sweeps any pre-existing droppings —
// e.g. ones the Finder created earlier — from the backup directory.
package cardio

import (
	"os"
	"path/filepath"
	"strings"
)

// WriteFile writes data to path using a plain byte write (no extended
// attributes, so no "._" sidecar), then removes any stale AppleDouble
// sidecar for this file.
func WriteFile(path string, data []byte) error {
	if err := os.WriteFile(path, data, 0o644); err != nil {
		return err
	}
	// Drop a stale "._<name>" left by an earlier Finder copy.
	dot := filepath.Join(filepath.Dir(path), "._"+filepath.Base(path))
	_ = os.Remove(dot)
	return nil
}

// IsJunk reports whether a directory entry name is a macOS metadata
// dropping that must not reach the Palm restore.
func IsJunk(name string) bool {
	return name == ".DS_Store" || strings.HasPrefix(name, "._")
}

// Clean removes every macOS metadata dropping directly inside dir and
// returns the names removed. It does not recurse: MS Backup restores a
// single set directory, so that is all we sweep.
func Clean(dir string) ([]string, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	var removed []string
	for _, e := range entries {
		if e.IsDir() || !IsJunk(e.Name()) {
			continue
		}
		if err := os.Remove(filepath.Join(dir, e.Name())); err != nil {
			return removed, err
		}
		removed = append(removed, e.Name())
	}
	return removed, nil
}
