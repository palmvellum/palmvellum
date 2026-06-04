// Package supa is the Supabase REST client used by the AI worker
// and (later) the sync engine. v0.1 implements just enough of
// PostgREST to claim AI queue rows and read/write records.
//
// We hit PostgREST with the daemon's sb_secret_* key, which bypasses
// RLS — appropriate because the daemon is a trusted local process
// running as the user. Per-user scoping is preserved by always
// passing the user_id in queries.
package supa

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

// Client is the Supabase HTTP client.
type Client struct {
	url       string
	secretKey string
	http      *http.Client
}

// New builds a Client. Empty secretKey disables write operations.
func New(url, secretKey string) *Client {
	return &Client{
		url:       strings.TrimRight(url, "/"),
		secretKey: secretKey,
		http:      &http.Client{Timeout: 30 * time.Second},
	}
}

// Enabled returns true if the client is configured with real
// credentials (vs the placeholder "sb_secret_REPLACE_ME").
func (c *Client) Enabled() bool {
	return c.url != "" && c.secretKey != "" &&
		!strings.HasSuffix(c.secretKey, "REPLACE_ME")
}

// Ping verifies credentials by hitting the records table count.
func (c *Client) Ping(ctx context.Context) error {
	if !c.Enabled() {
		return errors.New("SUPABASE_URL / SUPABASE_SECRET_KEY not configured")
	}
	resp, err := c.do(ctx, "GET", "/rest/v1/records?select=count", nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("supabase ping %d: %s", resp.StatusCode, string(b))
	}
	return nil
}

// AIQueueItem mirrors a row in public.ai_queue.
type AIQueueItem struct {
	Seq        int64      `json:"seq"`
	RecordID   string     `json:"record_id"`
	UserID     string     `json:"user_id"`
	EnqueuedAt time.Time  `json:"enqueued_at"`
	ClaimedAt  *time.Time `json:"claimed_at,omitempty"`
	ClaimedBy  *string    `json:"claimed_by,omitempty"`
}

// Record mirrors a row in public.records.
type Record struct {
	ID         string  `json:"id"`
	UserID     string  `json:"user_id"`
	Type       string  `json:"type"`
	Posture    string  `json:"posture"`
	Body       *string `json:"body,omitempty"`
	AIStatus   *string `json:"ai_status,omitempty"`
	AIResponse *string `json:"ai_response,omitempty"`
}

// ClaimNextAIQueueItem fetches the oldest unclaimed ai_queue row and
// marks it claimed_by. Returns nil if the queue is empty. There is a
// narrow race window between the SELECT and the PATCH — fine for the
// v0.1 single-daemon case; a stored procedure replaces this in v0.5.
func (c *Client) ClaimNextAIQueueItem(ctx context.Context, claimedBy string) (*AIQueueItem, error) {
	resp, err := c.do(ctx, "GET",
		"/rest/v1/ai_queue?claimed_at=is.null&order=enqueued_at.asc&limit=1", nil)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("ai_queue fetch %d: %s", resp.StatusCode, string(b))
	}

	var items []AIQueueItem
	if err := json.NewDecoder(resp.Body).Decode(&items); err != nil {
		return nil, fmt.Errorf("decode ai_queue: %w", err)
	}
	if len(items) == 0 {
		return nil, nil
	}
	item := items[0]

	// Claim
	patch := map[string]any{
		"claimed_at": time.Now().UTC().Format(time.RFC3339),
		"claimed_by": claimedBy,
	}
	resp2, err := c.do(ctx, "PATCH",
		fmt.Sprintf("/rest/v1/ai_queue?seq=eq.%d&claimed_at=is.null", item.Seq), patch)
	if err != nil {
		return nil, err
	}
	defer resp2.Body.Close()
	if resp2.StatusCode >= 400 {
		b, _ := io.ReadAll(resp2.Body)
		return nil, fmt.Errorf("ai_queue claim %d: %s", resp2.StatusCode, string(b))
	}

	// PostgREST returns the updated rows in the body. If empty, another
	// worker beat us to the claim — treat as no-op.
	var claimed []AIQueueItem
	if err := json.NewDecoder(resp2.Body).Decode(&claimed); err == nil && len(claimed) == 0 {
		return nil, nil
	}
	return &item, nil
}

// GetRecord fetches a single record by ULID.
func (c *Client) GetRecord(ctx context.Context, id string) (*Record, error) {
	resp, err := c.do(ctx, "GET",
		fmt.Sprintf("/rest/v1/records?id=eq.%s&select=*&limit=1", id), nil)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("get record %d: %s", resp.StatusCode, string(b))
	}
	var out []Record
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, fmt.Errorf("decode record: %w", err)
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("record %s not found", id)
	}
	rec := out[0]
	return &rec, nil
}

// UserSettings mirrors public.user_settings (subset the daemon cares about).
type UserSettings struct {
	UserID            string  `json:"user_id"`
	APIMode           string  `json:"api_mode"`
	PreferredProvider string  `json:"preferred_provider"`
	OpenAISecretID    *string `json:"openai_secret_id,omitempty"`
	OpenAIModel       string  `json:"openai_model"`
	AnthropicSecretID *string `json:"anthropic_secret_id,omitempty"`
	AnthropicModel    string  `json:"anthropic_model"`
	Invited           bool    `json:"invited"`
	CreditsRemaining  int     `json:"credits_remaining"`
}

// GetUserSettings fetches a user's settings row.
func (c *Client) GetUserSettings(ctx context.Context, userID string) (*UserSettings, error) {
	resp, err := c.do(ctx, "GET",
		fmt.Sprintf("/rest/v1/user_settings?user_id=eq.%s&select=*&limit=1", userID), nil)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("user_settings %d: %s", resp.StatusCode, string(b))
	}
	var out []UserSettings
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, err
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("user_settings not found for %s", userID)
	}
	s := out[0]
	return &s, nil
}

// ReadUserAPIKey fetches a decrypted BYOK key from Vault via the
// read_user_api_key SECURITY DEFINER RPC. Returns empty string +
// nil error if the user has not stored a key for that provider.
func (c *Client) ReadUserAPIKey(ctx context.Context, userID, provider string) (string, error) {
	body := map[string]any{
		"target_user":   userID,
		"provider_name": provider,
	}
	resp, err := c.do(ctx, "POST", "/rest/v1/rpc/read_user_api_key", body)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		b, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("read_user_api_key %d: %s", resp.StatusCode, string(b))
	}
	// PostgREST returns the scalar wrapped in JSON ("text") or null.
	var out *string
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", err
	}
	if out == nil {
		return "", nil
	}
	return *out, nil
}

// AIUsage is the row we insert into public.ai_usage on every call.
type AIUsage struct {
	UserID      string  `json:"user_id"`
	RecordID    *string `json:"record_id,omitempty"`
	APIMode     string  `json:"api_mode"`
	Provider    string  `json:"provider"`
	Model       string  `json:"model,omitempty"`
	TokensIn    int     `json:"tokens_in"`
	TokensOut   int     `json:"tokens_out"`
	CostCredits int     `json:"cost_credits"`
	Error       *string `json:"error,omitempty"`
}

// InsertAIUsage records one AI call.
func (c *Client) InsertAIUsage(ctx context.Context, u AIUsage) error {
	resp, err := c.do(ctx, "POST", "/rest/v1/ai_usage", u)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("ai_usage %d: %s", resp.StatusCode, string(b))
	}
	return nil
}

// ResolveHotsyncToken trades the raw token for a user_id via the
// resolve_hotsync_token SECURITY DEFINER RPC. Returns empty user_id
// if no match (token not recognised).
func (c *Client) ResolveHotsyncToken(ctx context.Context, rawToken string) (string, error) {
	body := map[string]any{"raw_token": rawToken}
	resp, err := c.do(ctx, "POST", "/rest/v1/rpc/resolve_hotsync_token", body)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		b, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("resolve_hotsync_token %d: %s", resp.StatusCode, string(b))
	}
	var out *string
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", err
	}
	if out == nil {
		return "", nil
	}
	return *out, nil
}

// AIUpdate is the patch payload for ai_status + response columns.
type AIUpdate struct {
	Status    string  `json:"ai_status"`
	Response  *string `json:"ai_response,omitempty"`
	Model     *string `json:"ai_model,omitempty"`
	TokensIn  *int    `json:"ai_tokens_in,omitempty"`
	TokensOut *int    `json:"ai_tokens_out,omitempty"`
	Error     *string `json:"ai_error,omitempty"`
}

// UpdateRecordAI sets the AI columns on a record.
func (c *Client) UpdateRecordAI(ctx context.Context, recordID string, u AIUpdate) error {
	resp, err := c.do(ctx, "PATCH",
		fmt.Sprintf("/rest/v1/records?id=eq.%s", recordID), u)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("update record %d: %s", resp.StatusCode, string(b))
	}
	return nil
}

// do is the shared HTTP helper.
func (c *Client) do(ctx context.Context, method, path string, body any) (*http.Response, error) {
	var rdr io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		rdr = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.url+path, rdr)
	if err != nil {
		return nil, err
	}
	req.Header.Set("apikey", c.secretKey)
	req.Header.Set("Authorization", "Bearer "+c.secretKey)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	// Always ask PostgREST to return the row representation on writes
	// so we can detect zero-row updates (lost claim race, missing id).
	req.Header.Set("Prefer", "return=representation")
	return c.http.Do(req)
}
