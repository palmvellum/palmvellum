// Package ai provides a thin abstraction over the AI provider used
// by the Oracle worker. v0.1 ships OpenAI and Anthropic backends;
// adding a local llama.cpp or Gemini backend is a one-file drop-in.
//
// The Provider interface is intentionally small: Enabled() so the
// worker can stay idle when no credentials are configured, and
// Ask() for the single user→assistant round-trip the Oracle pattern
// needs.
package ai

import "context"

// Provider is an AI backend capable of answering an Oracle query.
type Provider interface {
	// Enabled reports whether credentials are present and look valid.
	Enabled() bool

	// ProviderName returns a short stable identifier used in logs and
	// stored on records.ai_model alongside the model id.
	ProviderName() string

	// Ask sends a single user prompt and returns the assistant reply.
	Ask(ctx context.Context, query string) (*Response, error)
}

// Response is what the worker writes back to records.ai_response.
type Response struct {
	Text      string
	Model     string
	TokensIn  int
	TokensOut int
}

// Config controls which provider to instantiate.
type Config struct {
	// Provider selects the backend: "openai" (default) or "anthropic".
	Provider string

	// OpenAIAPIKey is consumed when Provider == "openai".
	OpenAIAPIKey string
	OpenAIModel  string

	// AnthropicAPIKey is consumed when Provider == "anthropic".
	AnthropicAPIKey string
	AnthropicModel  string
}

// New returns a Provider for the configured backend. Returns a
// disabled instance (not nil) when credentials are missing so the
// worker can log and stay idle rather than crash.
func New(cfg Config) Provider {
	switch cfg.Provider {
	case "anthropic":
		return NewAnthropic(cfg.AnthropicAPIKey, cfg.AnthropicModel)
	case "openai", "":
		return NewOpenAI(cfg.OpenAIAPIKey, cfg.OpenAIModel)
	default:
		// Unknown provider — return disabled OpenAI as a safe default.
		return NewOpenAI("", "")
	}
}

// OracleSystemPrompt is shared across providers so the persona is
// consistent regardless of which backend answers.
//
// Marked cache_control: ephemeral on Anthropic, prompt-cached
// automatically by OpenAI when the prefix is reused within ~10
// minutes (no flag needed since 2024-11).
const OracleSystemPrompt = `You are the PalmVellum Oracle.

You answer questions that arrive from a Palm Pilot — a handheld computer
manufactured between 1996 and 2003 with a 160x160 monochrome screen
and a 16 MHz CPU. The Palm has limited memory: your reply must fit in
a 1024-byte buffer and will be displayed in 12pt monospace on a tiny
screen. The user input was hand-written using Graffiti so it may be
terse, typo-prone, or fragmentary.

Style:
- Plain text only. No Markdown, no headings, no bullet lists, no code blocks.
- 2 to 4 short sentences. Under 800 characters total.
- Direct, factual, and quietly warm. Do not start with "Sure" or "Of course".
- If the question is ambiguous, give your best single interpretation
  and answer it rather than asking a clarifying question — round-trip
  cost on a HotSync is hours.

You may decline only if the request is unsafe. Otherwise, answer.`
