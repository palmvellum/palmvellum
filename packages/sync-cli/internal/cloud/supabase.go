// Package cloud is a thin Supabase PostgREST client tuned for the
// records table. It does upsert-by-(user_id, device_id) so a repeated
// push of the same VellumDB.pdb is idempotent — the Palm side's
// 24-bit unique record ID is the natural sync key.
package cloud

import (
	"bytes"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type Client struct {
	Endpoint string // e.g. https://jrkwncplngmznfzzqwee.supabase.co
	AdminKey string // SUPABASE_SERVICE_ROLE_KEY
	HTTP     *http.Client
}

func New(endpoint, key string) *Client {
	return &Client{
		Endpoint: strings.TrimRight(endpoint, "/"),
		AdminKey: key,
		HTTP:     &http.Client{Timeout: 30 * time.Second},
	}
}

// Record mirrors the public.records row shape (only columns we touch).
type Record struct {
	ID         string          `json:"id"`
	UserID     string          `json:"user_id"`
	Type       string          `json:"type"`
	Posture    string          `json:"posture"`
	Body       string          `json:"body"`
	Source     string          `json:"source"`
	DeviceID   *string         `json:"device_id,omitempty"`
	AIStatus   *string         `json:"ai_status,omitempty"`
	AIResponse *string         `json:"ai_response,omitempty"`
	Metadata   json.RawMessage `json:"metadata,omitempty"`
	CreatedAt  *time.Time      `json:"created_at,omitempty"`
}

// FindByDevice returns the row's id if a record with the given
// (user_id, device_id) tuple already exists, otherwise "".
func (c *Client) FindByDevice(userID, deviceID string) (string, error) {
	u := fmt.Sprintf("%s/rest/v1/records?select=id&user_id=eq.%s&device_id=eq.%s",
		c.Endpoint, userID, deviceID)
	req, _ := http.NewRequest("GET", u, nil)
	c.auth(req)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		body, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("find: HTTP %d %s", resp.StatusCode, body)
	}
	var rows []struct {
		ID string `json:"id"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&rows); err != nil {
		return "", err
	}
	if len(rows) == 0 {
		return "", nil
	}
	return rows[0].ID, nil
}

// Insert creates a new row.
func (c *Client) Insert(r Record) error {
	body, _ := json.Marshal(r)
	req, _ := http.NewRequest("POST", c.Endpoint+"/rest/v1/records", bytes.NewReader(body))
	c.auth(req)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Prefer", "return=minimal")
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("insert: HTTP %d %s", resp.StatusCode, b)
	}
	return nil
}

// Update PATCHes the row by id.
func (c *Client) Update(id string, patch map[string]any) error {
	body, _ := json.Marshal(patch)
	u := fmt.Sprintf("%s/rest/v1/records?id=eq.%s", c.Endpoint, id)
	req, _ := http.NewRequest("PATCH", u, bytes.NewReader(body))
	c.auth(req)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Prefer", "return=minimal")
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("update: HTTP %d %s", resp.StatusCode, b)
	}
	return nil
}

// ListForUser returns every non-deleted row for the user that originated
// from either Palm (source='palm') or the PWA hotsync inbox
// (source='web' with one of the synced types).
func (c *Client) ListForUser(userID string) ([]Record, error) {
	u := fmt.Sprintf(
		"%s/rest/v1/records"+
			"?select=id,type,posture,body,source,device_id,ai_status,ai_response,metadata,created_at"+
			"&user_id=eq.%s"+
			"&deleted_at=is.null"+
			"&type=in.(aiquery,thought,todo)"+
			"&order=created_at.asc",
		c.Endpoint, userID)
	req, _ := http.NewRequest("GET", u, nil)
	c.auth(req)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("list: HTTP %d %s", resp.StatusCode, b)
	}
	var rows []Record
	if err := json.NewDecoder(resp.Body).Decode(&rows); err != nil {
		return nil, err
	}
	return rows, nil
}

func (c *Client) auth(req *http.Request) {
	req.Header.Set("apikey", c.AdminKey)
	req.Header.Set("Authorization", "Bearer "+c.AdminKey)
}

// ─── ULID generation ──────────────────────────────────────────────

// crockford alphabet
const crockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

// NewULID returns a 26-char Crockford Base32 ULID — matches the
// scheme used in packages/shared-schema/src/ulid.ts and the PWA.
func NewULID() string {
	ms := uint64(time.Now().UnixMilli())
	var raw [16]byte
	raw[0] = byte(ms >> 40)
	raw[1] = byte(ms >> 32)
	raw[2] = byte(ms >> 24)
	raw[3] = byte(ms >> 16)
	raw[4] = byte(ms >> 8)
	raw[5] = byte(ms)
	_, _ = rand.Read(raw[6:])

	var out [26]byte
	// 128 bits → 26 base32 chars (each 5 bits).
	// Walk bit-by-bit so we don't need bigint.
	bits := make([]byte, 0, 128)
	for _, b := range raw {
		for i := 7; i >= 0; i-- {
			bits = append(bits, (b>>i)&1)
		}
	}
	for i := 0; i < 26; i++ {
		var v byte
		for j := 0; j < 5; j++ {
			idx := i*5 + j - 2 // 26*5 = 130, raw is 128 → leading 2 padding bits
			if idx < 0 {
				continue
			}
			v = (v << 1) | bits[idx]
		}
		out[i] = crockford[v]
	}
	return string(out[:])
}
