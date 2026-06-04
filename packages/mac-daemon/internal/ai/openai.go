// OpenAI backend for the Oracle worker.
// Uses Chat Completions for broad model compatibility (gpt-4o-mini,
// gpt-4o, gpt-5 family). Switch to /v1/responses if/when needed.

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
	defaultOpenAIModel    = "gpt-4o-mini"
	openAIChatEndpoint    = "https://api.openai.com/v1/chat/completions"
	openAIRequestTimeout  = 60 * time.Second
)

// OpenAI is an AI Provider backed by the OpenAI Chat Completions API.
type OpenAI struct {
	apiKey string
	model  string
	http   *http.Client
}

// NewOpenAI builds the provider. Empty apiKey disables it.
func NewOpenAI(apiKey, model string) *OpenAI {
	if model == "" {
		model = defaultOpenAIModel
	}
	return &OpenAI{
		apiKey: apiKey,
		model:  model,
		http:   &http.Client{Timeout: openAIRequestTimeout},
	}
}

// Enabled reports whether the provider has real credentials.
func (o *OpenAI) Enabled() bool {
	if o.apiKey == "" {
		return false
	}
	if strings.HasSuffix(o.apiKey, "REPLACE_ME") {
		return false
	}
	return strings.HasPrefix(o.apiKey, "sk-")
}

// ProviderName returns "openai".
func (o *OpenAI) ProviderName() string { return "openai" }

// Ask runs a single Oracle round-trip.
func (o *OpenAI) Ask(ctx context.Context, query string) (*Response, error) {
	if !o.Enabled() {
		return nil, errors.New("OPENAI_API_KEY not configured")
	}
	if query == "" {
		return nil, errors.New("empty query")
	}

	payload := map[string]any{
		"model": o.model,
		"messages": []map[string]any{
			{"role": "system", "content": OracleSystemPrompt},
			{"role": "user", "content": query},
		},
		// max_completion_tokens is the modern field; for legacy
		// gpt-4 family models OpenAI silently treats it as max_tokens.
		"max_completion_tokens": 512,
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, openAIChatEndpoint, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+o.apiKey)
	req.Header.Set("Content-Type", "application/json")

	resp, err := o.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("openai %d: %s", resp.StatusCode, string(b))
	}

	var result struct {
		Model   string `json:"model"`
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
			FinishReason string `json:"finish_reason"`
		} `json:"choices"`
		Usage struct {
			PromptTokens     int `json:"prompt_tokens"`
			CompletionTokens int `json:"completion_tokens"`
		} `json:"usage"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("decode openai: %w", err)
	}
	if len(result.Choices) == 0 {
		return nil, errors.New("openai returned zero choices")
	}

	return &Response{
		Text:      result.Choices[0].Message.Content,
		Model:     result.Model,
		TokensIn:  result.Usage.PromptTokens,
		TokensOut: result.Usage.CompletionTokens,
	}, nil
}
