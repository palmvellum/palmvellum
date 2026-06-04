// Package aiworker polls the Supabase ai_queue, claims pending AI
// requests one at a time, calls the user's preferred AI provider
// using their BYOK key (or the daemon's platform-tier key for users
// on api_mode='platform'), and writes the response back to the
// originating record.
//
// v0.2 — per-user keys via Supabase Vault, ai_usage accounting.
// v0.5 will replace the 2-second poll with a Realtime subscription.
package aiworker

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/rs/zerolog/log"

	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/ai"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/supa"
)

// PlatformKeys carries the daemon-level credentials used when a
// user is on api_mode='platform'. v0.3 will gate this on
// credits_remaining > 0 and deduct from user_settings; v0.2 just
// uses these keys whenever the platform path is selected.
type PlatformKeys struct {
	OpenAIAPIKey    string
	OpenAIModel     string
	AnthropicAPIKey string
	AnthropicModel  string
}

// Worker drains pending AI requests from Supabase.
type Worker struct {
	sb       *supa.Client
	platform PlatformKeys
	deviceID string

	pollInterval   time.Duration
	maxResponseLen int
}

// New builds a Worker.
func New(sb *supa.Client, platform PlatformKeys, deviceID string) *Worker {
	return &Worker{
		sb:             sb,
		platform:       platform,
		deviceID:       deviceID,
		pollInterval:   2 * time.Second,
		maxResponseLen: 1024,
	}
}

// Run blocks until ctx is cancelled. Each tick claims one queue item
// and processes it. Errors are logged but do not stop the loop.
func (w *Worker) Run(ctx context.Context) error {
	if !w.sb.Enabled() {
		log.Info().Msg("ai worker: Supabase not configured, worker idle")
		<-ctx.Done()
		return nil
	}

	log.Info().
		Str("device_id", w.deviceID).
		Dur("interval", w.pollInterval).
		Bool("platform_openai_ready", w.platform.OpenAIAPIKey != "").
		Bool("platform_anthropic_ready", w.platform.AnthropicAPIKey != "").
		Msg("ai worker starting (per-user BYOK)")

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

// tick processes at most one queue item.
func (w *Worker) tick(ctx context.Context) error {
	item, err := w.sb.ClaimNextAIQueueItem(ctx, w.deviceID)
	if err != nil {
		return err
	}
	if item == nil {
		return nil
	}

	log.Info().
		Int64("seq", item.Seq).
		Str("user_id", item.UserID).
		Str("record_id", item.RecordID).
		Msg("ai worker claimed item")

	settings, err := w.sb.GetUserSettings(ctx, item.UserID)
	if err != nil {
		return w.fail(ctx, item.UserID, item.RecordID, "", "", fmt.Errorf("user_settings: %w", err))
	}

	rec, err := w.sb.GetRecord(ctx, item.RecordID)
	if err != nil {
		return w.fail(ctx, item.UserID, item.RecordID, settings.APIMode, settings.PreferredProvider, err)
	}
	if rec.Body == nil || *rec.Body == "" {
		return w.fail(ctx, item.UserID, item.RecordID, settings.APIMode, settings.PreferredProvider,
			errors.New("record body is empty"))
	}

	provider, err := w.buildProvider(ctx, settings)
	if err != nil {
		return w.fail(ctx, item.UserID, item.RecordID, settings.APIMode, settings.PreferredProvider, err)
	}

	// Mark processing so the PWA shows a spinner.
	procStatus := "processing"
	_ = w.sb.UpdateRecordAI(ctx, rec.ID, supa.AIUpdate{Status: procStatus})

	resp, err := provider.Ask(ctx, *rec.Body)
	if err != nil {
		return w.fail(ctx, item.UserID, item.RecordID, settings.APIMode, provider.ProviderName(), err)
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

	// Track usage (cost_credits=0 for BYOK; v0.3 will price platform calls).
	recID := rec.ID
	_ = w.sb.InsertAIUsage(ctx, supa.AIUsage{
		UserID:      item.UserID,
		RecordID:    &recID,
		APIMode:     settings.APIMode,
		Provider:    provider.ProviderName(),
		Model:       resp.Model,
		TokensIn:    resp.TokensIn,
		TokensOut:   resp.TokensOut,
		CostCredits: 0,
	})

	log.Info().
		Str("user_id", item.UserID).
		Str("record_id", rec.ID).
		Str("provider", provider.ProviderName()).
		Str("api_mode", settings.APIMode).
		Int("in_tokens", resp.TokensIn).
		Int("out_tokens", resp.TokensOut).
		Int("response_len", len(text)).
		Msg("ai worker completed item")
	return nil
}

// buildProvider constructs an ai.Provider scoped to a single call,
// using either the user's Vault-stored BYOK key or the daemon's
// platform-tier fallback depending on settings.api_mode.
func (w *Worker) buildProvider(ctx context.Context, s *supa.UserSettings) (ai.Provider, error) {
	switch s.APIMode {
	case "byok":
		key, err := w.sb.ReadUserAPIKey(ctx, s.UserID, s.PreferredProvider)
		if err != nil {
			return nil, fmt.Errorf("read vault: %w", err)
		}
		if key == "" {
			return nil, fmt.Errorf("user %s has no %s key in vault", s.UserID, s.PreferredProvider)
		}
		switch s.PreferredProvider {
		case "openai":
			return ai.NewOpenAI(key, s.OpenAIModel), nil
		case "anthropic":
			return ai.NewAnthropic(key, s.AnthropicModel), nil
		default:
			return nil, fmt.Errorf("unknown provider: %s", s.PreferredProvider)
		}

	case "platform":
		switch s.PreferredProvider {
		case "openai":
			if w.platform.OpenAIAPIKey == "" {
				return nil, errors.New("platform openai key not configured on daemon")
			}
			model := s.OpenAIModel
			if model == "" {
				model = w.platform.OpenAIModel
			}
			return ai.NewOpenAI(w.platform.OpenAIAPIKey, model), nil
		case "anthropic":
			if w.platform.AnthropicAPIKey == "" {
				return nil, errors.New("platform anthropic key not configured on daemon")
			}
			model := s.AnthropicModel
			if model == "" {
				model = w.platform.AnthropicModel
			}
			return ai.NewAnthropic(w.platform.AnthropicAPIKey, model), nil
		default:
			return nil, fmt.Errorf("unknown provider: %s", s.PreferredProvider)
		}
	}

	return nil, fmt.Errorf("unknown api_mode: %s", s.APIMode)
}

// fail records the error on the record, writes an ai_usage row, and
// returns the original error so the caller can log it.
func (w *Worker) fail(ctx context.Context, userID, recordID, apiMode, provider string, cause error) error {
	errStr := cause.Error()
	if recordID != "" {
		_ = w.sb.UpdateRecordAI(ctx, recordID, supa.AIUpdate{
			Status: "error",
			Error:  &errStr,
		})
	}
	rid := recordID
	mode := apiMode
	if mode == "" {
		mode = "byok"
	}
	prov := provider
	if prov == "" {
		prov = "unknown"
	}
	_ = w.sb.InsertAIUsage(ctx, supa.AIUsage{
		UserID:   userID,
		RecordID: &rid,
		APIMode:  mode,
		Provider: prov,
		Error:    &errStr,
	})
	return cause
}
