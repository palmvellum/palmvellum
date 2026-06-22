// Package hotsync drives a USB HotSync by shelling out to the palm-sync
// Node sidecar (packages/mac-daemon/sidecar). The sidecar holds the live
// HotSync session; this package locates the runtime, spawns it, streams
// its log, and waits for the session to finish.
//
// Architecture (one HotSync button press):
//
//	palmvellum (Go)  ──spawn──►  node palm-sync run conduit.js --usb
//	    │                              │  1. pull DBs → staging dir
//	    │  ◄── exec hotsync-merge ─────┤  2. merge with cloud (this binary)
//	    │                              │  3. install merged DBs back
//	    └──────── waits for exit ──────┘
//
// All OS-specific transport weight lives in the sidecar; this file is
// pure path-resolution + process control. See
// docs/cross-platform-desktop-sync-feasibility.md §3 (Option A).
package hotsync

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// DefaultDBs are the databases PalmVellum syncs, by their on-device names.
// These map to the filenames SyncCardLog expects (MemoDB.pdb, …).
var DefaultDBs = []string{"MemoDB", "ToDoDB", "DatebookDB", "AddressDB", "MailDB"}

// Runtime is a resolved set of paths needed to launch the sidecar.
type Runtime struct {
	Node     string // node executable
	CLI      string // palm-sync dist/bin/cli.js
	Conduit  string // our conduit.js
	MergeBin string // this binary (palmvellum), for `hotsync-merge`
}

// Options configures a HotSync run.
type Options struct {
	// StageDir is an (ideally empty) working directory for the pulled
	// .pdb files. Created if missing.
	StageDir string
	// DBs are the device database names to sync. Defaults to DefaultDBs.
	DBs []string
	// Dry pulls the databases but skips the cloud merge and the write-
	// back — a transport-only smoke test that needs no login.
	Dry bool
	// AIWait, if > 0, holds the live USB session open while the cloud side
	// waits up to this long for the AI worker to answer newly-asked memos,
	// so the answers are written back in the same HotSync. 0 (default)
	// writes back immediately; answers arrive on the next sync.
	AIWait time.Duration
	// Log receives the sidecar's output line by line (may be nil).
	Log func(string)
}

// RunSync launches the sidecar and blocks until the HotSync session
// completes (the user must press HotSync on the Palm) or ctx is cancelled.
func RunSync(ctx context.Context, opts Options) error {
	rt, err := Resolve()
	if err != nil {
		return err
	}
	if opts.StageDir == "" {
		return errors.New("hotsync: StageDir required")
	}
	if err := os.MkdirAll(opts.StageDir, 0o755); err != nil {
		return fmt.Errorf("hotsync: create staging dir: %w", err)
	}
	dbs := opts.DBs
	if len(dbs) == 0 {
		dbs = DefaultDBs
	}

	cmd := exec.CommandContext(ctx, rt.Node, rt.CLI, "run", rt.Conduit, "--usb")
	cmd.Env = append(os.Environ(),
		"PV_STAGE="+opts.StageDir,
		"PV_DBS="+strings.Join(dbs, ","),
		"PV_MERGE_BIN="+rt.MergeBin,
	)
	if opts.Dry {
		cmd.Env = append(cmd.Env, "PV_DRY=1")
	}
	if opts.AIWait > 0 {
		cmd.Env = append(cmd.Env, "PV_MERGE_WAIT="+opts.AIWait.String())
	}

	if err := streamRun(cmd, opts.Log); err != nil {
		return err
	}
	return nil
}

// streamRun runs cmd, forwarding merged stdout+stderr to log line by line.
func streamRun(cmd *exec.Cmd, log func(string)) error {
	pr, pw := io.Pipe()
	cmd.Stdout = pw
	cmd.Stderr = pw
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("hotsync: start sidecar: %w", err)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		sc := bufio.NewScanner(pr)
		sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
		for sc.Scan() {
			if log != nil {
				log(sc.Text())
			}
		}
	}()
	err := cmd.Wait()
	pw.Close() // unblock the scanner
	<-done     // drain remaining output before returning
	if err != nil {
		return fmt.Errorf("hotsync: sidecar exited with error: %w", err)
	}
	return nil
}

// Resolve locates the Node runtime and sidecar, handling both the packaged
// .app layout and a dev checkout. Order: explicit env override → app
// bundle → dev checkout.
func Resolve() (Runtime, error) {
	exe, err := os.Executable()
	if err != nil {
		return Runtime{}, fmt.Errorf("hotsync: locate self: %w", err)
	}
	exe, _ = filepath.EvalSymlinks(exe)

	// 1. Explicit override (set by dev tooling/tests).
	if dir := os.Getenv("PALMVELLUM_SIDECAR_DIR"); dir != "" {
		return runtimeFromSidecarDir(dir, exe, os.Getenv("PALMVELLUM_NODE"))
	}

	// 2. App bundle:  …/Contents/MacOS/palmvellum  →  …/Contents/Resources/{sidecar,node}
	if i := strings.Index(exe, "/Contents/MacOS/"); i >= 0 {
		res := exe[:i] + "/Contents/Resources"
		sidecar := filepath.Join(res, "sidecar")
		node := filepath.Join(res, "node", "bin", "node")
		if fileExists(node) && dirExists(sidecar) {
			return runtimeFromSidecarDir(sidecar, exe, node)
		}
	}

	// 3. Dev checkout: find packages/mac-daemon/sidecar near the binary
	// or the working directory; use node from PATH.
	for _, base := range devSearchBases(exe) {
		sidecar := filepath.Join(base, "sidecar")
		if dirExists(filepath.Join(sidecar, "node_modules", "palm-sync")) {
			return runtimeFromSidecarDir(sidecar, exe, "")
		}
	}

	return Runtime{}, errors.New(
		"hotsync: sidecar not found. In a dev checkout run `npm install` in " +
			"packages/mac-daemon/sidecar; in the packaged app the sidecar ships " +
			"under Contents/Resources/sidecar")
}

// runtimeFromSidecarDir fills a Runtime from a sidecar directory. nodePath
// empty → resolve `node` from PATH (dev).
func runtimeFromSidecarDir(sidecar, exe, nodePath string) (Runtime, error) {
	if nodePath == "" {
		p, err := exec.LookPath("node")
		if err != nil {
			return Runtime{}, errors.New("hotsync: `node` not found on PATH (install Node.js, or use the packaged app)")
		}
		nodePath = p
	}
	cli := filepath.Join(sidecar, "node_modules", "palm-sync", "dist", "bin", "cli.js")
	conduit := filepath.Join(sidecar, "conduit.js")
	if !fileExists(cli) {
		return Runtime{}, fmt.Errorf("hotsync: palm-sync CLI missing at %s (run npm install)", cli)
	}
	if !fileExists(conduit) {
		return Runtime{}, fmt.Errorf("hotsync: conduit.js missing at %s", conduit)
	}
	return Runtime{Node: nodePath, CLI: cli, Conduit: conduit, MergeBin: exe}, nil
}

// devSearchBases returns candidate packages/mac-daemon directories to probe
// for a dev sidecar checkout.
func devSearchBases(exe string) []string {
	var bases []string
	add := func(p string) {
		if p != "" {
			bases = append(bases, p)
		}
	}
	// `make build` → packages/mac-daemon/bin/palmvellum  → parent is mac-daemon
	add(filepath.Dir(filepath.Dir(exe)))
	// exe sitting directly in mac-daemon
	add(filepath.Dir(exe))
	// `go run` from packages/mac-daemon
	if cwd, err := os.Getwd(); err == nil {
		add(cwd)
		add(filepath.Join(cwd, "packages", "mac-daemon"))
	}
	return bases
}

// DevicePresent reports whether a Palm/Clié is currently enumerated on the
// USB bus. It's only a hint for the UI — a Palm appears on the bus once the
// HotSync button is pressed, so a false result before the press is normal.
// Best-effort: any error is treated as "unknown" → false.
func DevicePresent() bool {
	out, err := exec.Command("ioreg", "-p", "IOUSB").Output()
	if err != nil {
		return false
	}
	s := string(out)
	return strings.Contains(s, "Palm") || strings.Contains(s, "Handheld")
}

func fileExists(p string) bool {
	fi, err := os.Stat(p)
	return err == nil && !fi.IsDir()
}

func dirExists(p string) bool {
	fi, err := os.Stat(p)
	return err == nil && fi.IsDir()
}
