// Package hotsync wraps the palm-sync Node sidecar over a Unix
// socket. In "stub" mode (until the FTDI cable arrives) the package
// returns canned responses so the rest of the daemon can develop
// against a stable interface.
//
// See docs/crypto-spec.md §10 and issues #10 / #14 for the full
// specification.
package hotsync

import (
	"context"
	"errors"
	"fmt"
	"time"
)

// Mode controls whether the sidecar talks to a real Palm or returns
// canned responses for development.
type Mode string

const (
	ModeStub Mode = "stub"
	ModeReal Mode = "real"
)

// Sidecar is the high-level wrapper around the palm-sync Node
// subprocess. In v0.1 it only implements the stub mode.
type Sidecar struct {
	mode       Mode
	socketPath string
}

// New returns a Sidecar configured for the given mode.
func New(mode Mode, socketPath string) *Sidecar {
	return &Sidecar{mode: mode, socketPath: socketPath}
}

// CradleEvent is emitted whenever the daemon detects the HotSync
// button press on a cradle.
type CradleEvent struct {
	Time       time.Time
	DevicePath string
	Descriptor string
}

// AwaitCradle blocks until a CradleEvent arrives or ctx is cancelled.
// In stub mode it returns ctx.Err() immediately on context cancel and
// emits no events.
func (s *Sidecar) AwaitCradle(ctx context.Context) (*CradleEvent, error) {
	if s.mode == ModeStub {
		<-ctx.Done()
		return nil, ctx.Err()
	}
	return nil, errors.New("real mode not yet implemented; see issue #10")
}

// PullDB fetches the named PDB from the connected Palm into the
// staging directory.
func (s *Sidecar) PullDB(ctx context.Context, name, outDir string) (string, error) {
	if s.mode == ModeStub {
		return "", fmt.Errorf("hotsync stub: cannot pull %q (cradle not connected)", name)
	}
	return "", errors.New("real mode not yet implemented; see issue #14")
}

// PushPRC installs a compiled .prc onto the connected Palm.
func (s *Sidecar) PushPRC(ctx context.Context, prcPath string) error {
	if s.mode == ModeStub {
		return fmt.Errorf("hotsync stub: cannot push %q (cradle not connected)", prcPath)
	}
	return errors.New("real mode not yet implemented; see issue #14")
}
