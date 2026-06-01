// Package supa is the Supabase client used by the sync engine and
// the AI worker. v0.1 is a stub. Real implementation deferred to
// issues #2 and #7.
package supa

import (
	"context"
	"errors"
	"net/http"
	"time"
)

// Client is the Supabase HTTP / Realtime client.
type Client struct {
	url       string
	secretKey string
	http      *http.Client
}

// New builds a Client. Pass the new sb_secret_xxx key (not the
// legacy service_role key which sunsets end of 2026).
func New(url, secretKey string) *Client {
	return &Client{
		url:       url,
		secretKey: secretKey,
		http:      &http.Client{Timeout: 30 * time.Second},
	}
}

// Ping verifies credentials. Returns error if the project is unreachable
// or auth fails.
func (c *Client) Ping(ctx context.Context) error {
	if c.url == "" || c.secretKey == "" {
		return errors.New("SUPABASE_URL / SUPABASE_SECRET_KEY not set")
	}
	// TODO(issue #2): GET {url}/rest/v1/ with Authorization: Bearer {key}
	return errors.New("supa.Ping not yet implemented; see issue #2")
}
