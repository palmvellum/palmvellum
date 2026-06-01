// Package ai is the Anthropic Claude client used by the AI Oracle
// worker. v0.1 is a stub. Real implementation deferred to issue #7.
package ai

import (
	"context"
	"errors"
)

// Client wraps the Anthropic Messages API. v0.1 stub.
type Client struct {
	apiKey string
}

// New builds a Client. Empty apiKey disables the worker.
func New(apiKey string) *Client {
	return &Client{apiKey: apiKey}
}

// Response holds a single completion plus usage metadata for billing.
type Response struct {
	Text      string
	Model     string
	TokensIn  int
	TokensOut int
}

// Ask sends a prompt and returns the response. v0.1 stub.
func (c *Client) Ask(ctx context.Context, prompt string) (*Response, error) {
	if c.apiKey == "" {
		return nil, errors.New("ANTHROPIC_API_KEY not set")
	}
	// TODO(issue #7): use github.com/anthropics/anthropic-sdk-go with
	// cache_control breakpoints on the oracle persona system prompt.
	return nil, errors.New("ai.Ask not yet implemented; see issue #7")
}
