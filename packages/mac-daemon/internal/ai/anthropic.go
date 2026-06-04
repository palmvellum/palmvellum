// Anthropic backend for the Oracle worker.
// Uses the Messages API with cache_control on the persona prompt for
// sub-second second-hit billing.

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
	defaultAnthropicModel   = "claude-sonnet-4-5-20250929"
	anthropicMessagesEndpt  = "https://api.anthropic.com/v1/messages"
	anthropicVersionHeader  = "2023-06-01"
	anthropicRequestTimeout = 60 * time.Second
)

// Anthropic is an AI Provider backed by the Claude Messages API.
type Anthropic struct {
	apiKey string
	model  string
	http   *http.Client
}

// NewAnthropic builds the provider. Empty apiKey disables it.
func NewAnthropic(apiKey, model string) *Anthropic {
	if model == "" {
		model = defaultAnthropicModel
	}
	return &Anthropic{
		apiKey: apiKey,
		model:  model,
		http:   &http.Client{Timeout: anthropicRequestTimeout},
	}
}

// Enabled reports whether the provider has real credentials.
func (a *Anthropic) Enabled() bool {
	if a.apiKey == "" {
		return false
	}
	if strings.HasSuffix(a.apiKey, "REPLACE_ME") {
		return false
	}
	return strings.HasPrefix(a.apiKey, "sk-ant-")
}

// ProviderName returns "anthropic".
func (a *Anthropic) ProviderName() string { return "anthropic" }

// Ask runs a single Oracle round-trip.
func (a *Anthropic) Ask(ctx context.Context, query string) (*Response, error) {
	if !a.Enabled() {
		return nil, errors.New("ANTHROPIC_API_KEY not configured")
	}
	if query == "" {
		return nil, errors.New("empty query")
	}

	payload := map[string]any{
		"model":      a.model,
		"max_tokens": 512,
		"system": []map[string]any{
			{
				"type":          "text",
				"text":          OracleSystemPrompt,
				"cache_control": map[string]any{"type": "ephemeral"},
			},
		},
		"messages": []map[string]any{
			{"role": "user", "content": query},
		},
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, anthropicMessagesEndpt, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("x-api-key", a.apiKey)
	req.Header.Set("anthropic-version", anthropicVersionHeader)
	req.Header.Set("content-type", "application/json")

	resp, err := a.http.Do(req)
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
