// Package aiworker polls the Supabase ai_queue, claims pending AI
// requests one at a time, calls the Anthropic Messages API, and
// writes the response back to the originating record.
//
// v0.1 uses HTTP polling on a 2-second interval. v0.5 will replace
// this with a Supabase Realtime subscription on ai_queue inserts,
// keeping the same public Run/Tick surface so the daemon's main
// loop is unaffected.
package aiworker

import (
	"context"
	"errors"
	"time"

	"github.com/rs/zerolog/log"

	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/ai"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/supa"
)

// Worker drains pending AI requests from Supabase.
type Worker struct {
	sb           *supa.Client
	claude       *ai.Client
	deviceID     string
	pollInterval time.Duration
	// maxResponseLen caps the response stored on the record so the
	// on-device Palm heap (~96 KB dynamic) is never blown. See
	// docs/crypto-spec.md §9.2.
	maxResponseLen int
}

// New builds a Worker.
func New(sb *supa.Client, claude *ai.Client, deviceID string) *Worker {
	return &Worker{
		sb:             sb,
		claude:         claude,
		deviceID:       deviceID,
		pollInterval:   2 * time.Second,
		maxResponseLen: 1024,
	}
}

// Run blocks until ctx is cancelled. It's safe to call when either
// Claude or Supabase is misconfigured — the worker just logs and
// exits cleanly.
func (w *Worker) Run(ctx context.Context) error {
	if !w.claude.Enabled() {
		log.Info().Msg("ai worker: ANTHROPIC_API_KEY not set, worker idle")
		<-ctx.Done()
		return nil
	}
	if !w.sb.Enabled() {
		log.Info().Msg("ai worker: SUPABASE_* not set, worker idle")
		<-ctx.Done()
		return nil
	}

	log.Info().
		Str("device_id", w.deviceID).
		Dur("interval", w.pollInterval).
		Msg("ai worker starting")

	ticker := time.NewTicker(w.pollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			log.Info().Msg("ai worker shutting down")
			return nil
		case <-ticker.C:
			if err := w.tick(ctx); err != nil {
				log.Warn().Err(err).Msg("ai worker tick failed")
			}
		}
	}
}

// tick claims one queue item (if any) and processes it. Exported
// for tests; callers should normally use Run.
func (w *Worker) tick(ctx context.Context) error {
	item, err := w.sb.ClaimNextAIQueueItem(ctx, w.deviceID)
	if err != nil {
		return err
	}
	if item == nil {
		return nil // queue empty, normal path
	}

	log.Info().
		Int64("seq", item.Seq).
		Str("record_id", item.RecordID).
		Msg("ai worker claimed item")

	rec, err := w.sb.GetRecord(ctx, item.RecordID)
	if err != nil {
		return err
	}
	if rec.Body == nil || *rec.Body == "" {
		// Nothing to ask. Mark error and move on.
		errStr := "record body is empty"
		_ = w.sb.UpdateRecordAI(ctx, rec.ID, supa.AIUpdate{
			Status: "error",
			Error:  &errStr,
		})
		return errors.New(errStr)
	}

	// Mark processing so the PWA shows a spinner.
	procStatus := "processing"
	_ = w.sb.UpdateRecordAI(ctx, rec.ID, supa.AIUpdate{Status: procStatus})

	resp, err := w.claude.Ask(ctx, *rec.Body)
	if err != nil {
		errStr := err.Error()
		_ = w.sb.UpdateRecordAI(ctx, rec.ID, supa.AIUpdate{
			Status: "error",
			Error:  &errStr,
		})
		return err
	}

	text := resp.Text
	if len(text) > w.maxResponseLen {
		text = text[:w.maxResponseLen-1] + "…"
	}

	if err := w.sb.UpdateRecordAI(ctx, rec.ID, supa.AIUpdate{
		Status:    "done",
		Response:  &text,
		Model:     &resp.Model,
		TokensIn:  &resp.TokensIn,
		TokensOut: &resp.TokensOut,
	}); err != nil {
		return err
	}

	log.Info().
		Str("record_id", rec.ID).
		Int("in_tokens", resp.TokensIn).
		Int("out_tokens", resp.TokensOut).
		Int("response_len", len(text)).
		Msg("ai worker completed item")
	return nil
}
