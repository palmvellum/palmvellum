// Package cardwatch detects when a Palm backup card (Sony Memory Stick
// or SD) is mounted and locates the active MS Backup set directory on
// it — the folder holding MemoDB.pdb / ToDoDB.pdb that the sync engine
// operates on.
//
// Detection is by polling /Volumes. macOS auto-mounts removable media
// there; a 2s poll is imperceptible to the user and needs no cgo or
// DiskArbitration entitlement. (An event-driven DiskArbitration watcher
// is a later optimisation — see docs/mscard-sync-plan.md.)
package cardwatch

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"time"
)

// Eject unmounts and ejects a mounted volume so the user can safely pull
// the card out (and the watcher will fire again on re-insertion).
func Eject(volume string) error {
	out, err := exec.Command("diskutil", "eject", volume).CombinedOutput()
	if err != nil {
		return fmt.Errorf("eject %s: %v: %s", volume, err, out)
	}
	return nil
}

// VolumesDir is where macOS mounts removable media.
const VolumesDir = "/Volumes"

// msBackupRel is the Sony MS Backup tree relative to a card root.
var msBackupRel = filepath.Join("PALM", "PROGRAMS", "MSBackup")

// Card describes a mounted card with a usable backup set.
type Card struct {
	Volume string // e.g. /Volumes/NO NAME
	SetDir string // e.g. /Volumes/NO NAME/PALM/PROGRAMS/MSBackup/0
}

// FindCards scans volumesDir and returns every mounted volume that has a
// non-empty MS Backup set. The internal boot volume is skipped.
func FindCards(volumesDir string) []Card {
	entries, err := os.ReadDir(volumesDir)
	if err != nil {
		return nil
	}
	var out []Card
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		vol := filepath.Join(volumesDir, e.Name())
		if set := activeSet(filepath.Join(vol, msBackupRel)); set != "" {
			out = append(out, Card{Volume: vol, SetDir: set})
		}
	}
	return out
}

// activeSet picks the backup set directory under an MSBackup folder that
// actually holds Palm databases, preferring the one whose MemoDB/ToDoDB
// was modified most recently (the latest backup the user took).
func activeSet(msBackup string) string {
	sets, err := os.ReadDir(msBackup)
	if err != nil {
		return ""
	}
	type cand struct {
		dir   string
		mtime time.Time
	}
	var cands []cand
	for _, s := range sets {
		if !s.IsDir() {
			continue
		}
		dir := filepath.Join(msBackup, s.Name())
		var newest time.Time
		found := false
		for _, db := range []string{"MemoDB.pdb", "ToDoDB.pdb"} {
			if fi, err := os.Stat(filepath.Join(dir, db)); err == nil {
				found = true
				if fi.ModTime().After(newest) {
					newest = fi.ModTime()
				}
			}
		}
		if found {
			cands = append(cands, cand{dir, newest})
		}
	}
	if len(cands) == 0 {
		return ""
	}
	sort.Slice(cands, func(i, j int) bool { return cands[i].mtime.After(cands[j].mtime) })
	return cands[0].dir
}

// Watcher polls for newly-mounted cards and invokes OnInsert once per
// volume appearance. It de-dupes so a card that stays mounted fires only
// once; re-inserting (unmount → mount) fires again.
type Watcher struct {
	VolumesDir string
	Interval   time.Duration
	OnInsert   func(Card)

	seen map[string]bool
}

// Run polls until ctx is cancelled. On the first pass it records already-
// mounted cards as "seen" WITHOUT firing, so a card present at launch
// doesn't trigger an unprompted sync; only fresh insertions fire.
func (w *Watcher) Run(ctx context.Context) {
	if w.Interval <= 0 {
		w.Interval = 2 * time.Second
	}
	if w.VolumesDir == "" {
		w.VolumesDir = VolumesDir
	}
	w.seen = map[string]bool{}
	for _, c := range FindCards(w.VolumesDir) {
		w.seen[c.Volume] = true
	}

	t := time.NewTicker(w.Interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			w.poll()
		}
	}
}

func (w *Watcher) poll() {
	current := map[string]bool{}
	for _, c := range FindCards(w.VolumesDir) {
		current[c.Volume] = true
		if !w.seen[c.Volume] {
			w.seen[c.Volume] = true
			if w.OnInsert != nil {
				w.OnInsert(c)
			}
		}
	}
	// Forget volumes that went away so re-insertion fires again.
	for v := range w.seen {
		if !current[v] {
			delete(w.seen, v)
		}
	}
}
