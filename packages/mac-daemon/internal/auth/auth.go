// Package auth logs the end user into their PalmVellum platform account
// against Supabase GoTrue and persists the session in the macOS
// Keychain. The desktop app ships only the public *publishable* key;
// every cloud call is then made with the user's access_token, so
// Postgres RLS scopes reads/writes to their own rows. The service_role
// key never touches the client.
package auth

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

	"github.com/zalando/go-keyring"
)

const (
	keyringService = "dev.tatliving.palmvellum"
	keyringUser    = "session"
)

// Session is the persisted login state.
type Session struct {
	AccessToken  string    `json:"access_token"`
	RefreshToken string    `json:"refresh_token"`
	ExpiresAt    time.Time `json:"expires_at"`
	UserID       string    `json:"user_id"`
	Email        string    `json:"email"`
}

// Expired reports whether the access token is within 60s of expiry.
func (s *Session) Expired() bool {
	return time.Now().Add(60 * time.Second).After(s.ExpiresAt)
}

// Client talks to GoTrue. APIKey is the publishable key.
type Client struct {
	URL    string // https://<ref>.supabase.co
	APIKey string
	HTTP   *http.Client
}

func New(url, apikey string) *Client {
	return &Client{
		URL:    strings.TrimRight(url, "/"),
		APIKey: apikey,
		HTTP:   &http.Client{Timeout: 30 * time.Second},
	}
}

type tokenResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int    `json:"expires_in"`
	User         struct {
		ID    string `json:"id"`
		Email string `json:"email"`
	} `json:"user"`
}

func (t tokenResponse) toSession() *Session {
	return &Session{
		AccessToken:  t.AccessToken,
		RefreshToken: t.RefreshToken,
		ExpiresAt:    time.Now().Add(time.Duration(t.ExpiresIn) * time.Second),
		UserID:       t.User.ID,
		Email:        t.User.Email,
	}
}

func (c *Client) post(ctx context.Context, path string, body any, bearer string) (*tokenResponse, error) {
	buf, _ := json.Marshal(body)
	req, err := http.NewRequestWithContext(ctx, "POST", c.URL+path, bytes.NewReader(buf))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("apikey", c.APIKey)
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 300 {
		return nil, fmt.Errorf("auth %s: HTTP %d %s", path, resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	var tr tokenResponse
	if len(raw) > 0 {
		if err := json.Unmarshal(raw, &tr); err != nil {
			return nil, err
		}
	}
	return &tr, nil
}

// SignInPassword logs in with email + password.
func (c *Client) SignInPassword(ctx context.Context, email, password string) (*Session, error) {
	tr, err := c.post(ctx, "/auth/v1/token?grant_type=password",
		map[string]string{"email": email, "password": password}, "")
	if err != nil {
		return nil, err
	}
	return tr.toSession(), nil
}

// SendEmailOTP triggers a one-time code / magic link to the address.
func (c *Client) SendEmailOTP(ctx context.Context, email string) error {
	_, err := c.post(ctx, "/auth/v1/otp",
		map[string]any{"email": email, "create_user": false}, "")
	return err
}

// VerifyEmailOTP exchanges the 6-digit code for a session.
func (c *Client) VerifyEmailOTP(ctx context.Context, email, code string) (*Session, error) {
	tr, err := c.post(ctx, "/auth/v1/verify",
		map[string]string{"email": email, "token": code, "type": "email"}, "")
	if err != nil {
		return nil, err
	}
	return tr.toSession(), nil
}

// Refresh exchanges a refresh token for a fresh session.
func (c *Client) Refresh(ctx context.Context, refreshToken string) (*Session, error) {
	tr, err := c.post(ctx, "/auth/v1/token?grant_type=refresh_token",
		map[string]string{"refresh_token": refreshToken}, "")
	if err != nil {
		return nil, err
	}
	return tr.toSession(), nil
}

// ─────────────────────────── persistence ───────────────────────────

// Save writes the session to the macOS Keychain.
func Save(s *Session) error {
	buf, err := json.Marshal(s)
	if err != nil {
		return err
	}
	return keyring.Set(keyringService, keyringUser, string(buf))
}

// Load reads the persisted session, or returns ErrNoSession.
func Load() (*Session, error) {
	raw, err := keyring.Get(keyringService, keyringUser)
	if errors.Is(err, keyring.ErrNotFound) {
		return nil, ErrNoSession
	}
	if err != nil {
		return nil, err
	}
	var s Session
	if err := json.Unmarshal([]byte(raw), &s); err != nil {
		return nil, err
	}
	return &s, nil
}

// Clear deletes the persisted session (logout).
func Clear() error {
	err := keyring.Delete(keyringService, keyringUser)
	if errors.Is(err, keyring.ErrNotFound) {
		return nil
	}
	return err
}

// ErrNoSession means the user has not logged in yet.
var ErrNoSession = errors.New("no saved session — run `palmvellum login`")

// Current returns a valid session, refreshing and re-persisting it if
// the access token has expired. Returns ErrNoSession if not logged in.
func (c *Client) Current(ctx context.Context) (*Session, error) {
	s, err := Load()
	if err != nil {
		return nil, err
	}
	if !s.Expired() {
		return s, nil
	}
	refreshed, err := c.Refresh(ctx, s.RefreshToken)
	if err != nil {
		return nil, fmt.Errorf("session refresh failed (re-login needed): %w", err)
	}
	if err := Save(refreshed); err != nil {
		return nil, err
	}
	return refreshed, nil
}
