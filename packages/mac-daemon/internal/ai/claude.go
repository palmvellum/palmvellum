// Package ai is the Anthropic Claude client used by the AI Oracle
// worker. v0.1 implements a direct HTTP call to the Messages API
// with cache_control on the persona system prompt; the official
// anthropic-sdk-go can replace this in v0.5 without changing the
// caller surface.
package ai

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

const (
	// defaultModel is intentionally a sane default; the operator can
	// override at construction time.
	defaultModel = "claude-sonnet-4-5-20250929"

	// oracleSystemPrompt is the PalmVellum persona. The leading
	// section is marked cache_control=ephemeral on every call so
	// Anthropic billing covers it only on the first request of a
	// 5-minute window (massive win for sub-second oracle responses).
	oracleSystemPrompt = `You are the PalmVellum Oracle.

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
)

// Client wraps the Anthropic Messages API.
type Client struct {
	apiKey string
	http   *http.Client
	model  string
}

// New builds a Client. Empty apiKey disables the worker entirely
// (Enabled() returns false). Placeholder values like "sk-ant-REPLACE_ME"
// are also treated as disabled.
func New(apiKey string) *Client {
	return &Client{
		apiKey: apiKey,
		http:   &http.Client{Timeout: 60 * time.Second},
		model:  defaultModel,
	}
}

// Enabled reports whether the client has real credentials.
func (c *Client) Enabled() bool {
	return c.apiKey != "" && !strings.HasSuffix(c.apiKey, "REPLACE_ME")
}

// SetModel overrides the default model.
func (c *Client) SetModel(m string) { c.model = m }

// Response is what the worker writes back to records.ai_response.
type Response struct {
	Text      string
	Model     string
	TokensIn  int
	TokensOut int
}

// Ask sends a single user message and returns the assistant reply.
func (c *Client) Ask(ctx context.Context, query string) (*Response, error) {
	if !c.Enabled() {
		return nil, errors.New("ANTHROPIC_API_KEY not configured")
	}
	if query == "" {
		return nil, errors.New("empty query")
	}

	payload := map[string]any{
		"model":      c.model,
		"max_tokens": 512,
		"system": []map[string]any{
			{
				"type": "text",
				"text": oracleSystemPrompt,
				"cache_control": map[string]any{
					"type": "ephemeral",
				},
			},
		},
		"messages": []map[string]any{
			{
				"role":    "user",
				"content": query,
			},
		},
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}

	req, err := http.NewRequestWithContext(ctx, "POST",
		"https://api.anthropic.com/v1/messages", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("x-api-key", c.apiKey)
	req.Header.Set("anthropic-version", "2023-06-01")
	req.Header.Set("content-type", "application/json")

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("anthropic %d: %s", resp.StatusCode, string(b))
	}

	var result struct {
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
		Model string `json:"model"`
		Usage struct {
			InputTokens  int `json:"input_tokens"`
			OutputTokens int `json:"output_tokens"`
		} `json:"usage"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("decode anthropic: %w", err)
	}

	var b strings.Builder
	for _, c := range result.Content {
		if c.Type == "text" {
			b.WriteString(c.Text)
		}
	}

	return &Response{
		Text:      b.String(),
		Model:     result.Model,
		TokensIn:  result.Usage.InputTokens,
		TokensOut: result.Usage.OutputTokens,
	}, nil
}
