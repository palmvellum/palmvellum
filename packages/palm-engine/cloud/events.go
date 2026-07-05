package cloud

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sort"
	"time"
)

// Event mirrors the public.events row (only the columns we touch). Date
// Book syncs here rather than to the records table.
type Event struct {
	ID            string     `json:"id"`
	UserID        string     `json:"user_id"`
	Title         string     `json:"title"`
	StartAt       time.Time  `json:"start_at"`
	EndAt         *time.Time `json:"end_at,omitempty"`
	AllDay        bool       `json:"all_day"`
	Location      *string    `json:"location,omitempty"`
	Notes         *string    `json:"notes,omitempty"`
	AlarmMinutes  *int       `json:"alarm_minutes,omitempty"`
	RepeatRule    *string    `json:"repeat_rule,omitempty"`
	Source        string     `json:"source,omitempty"`
	DeviceID      *string    `json:"device_id,omitempty"`
	PalmRecordUID *int       `json:"palm_record_uid,omitempty"`
}

// ListEventsForUser returns the user's own active events ordered by start.
// Calendar-feed events (ics-sub / ics-import) are excluded server-side so a
// large subscribed calendar isn't downloaded on every HotSync — it is never
// written to the device anyway (see DatebookPull). The or-clause keeps rows
// whose source is null (legacy / device-origin events).
func (c *Client) ListEventsForUser(userID string) ([]Event, error) {
	// Page past PostgREST's hard db.max_rows cap (1000) — a bare select is
	// silently clamped, so once a user's own (non-feed) events exceed 1000
	// the physical Palm would stop receiving the overflow. Page size stays
	// below the cap so a short page reliably means "done". Mirrors the PWA
	// (fetchAllForUser) and native (SupabaseRest.selectAll).
	const pageSize = 500
	var all []Event
	for offset := 0; ; offset += pageSize {
		u := fmt.Sprintf("%s/rest/v1/events"+
			"?select=id,title,start_at,end_at,all_day,location,notes,alarm_minutes,repeat_rule,source,device_id,palm_record_uid"+
			"&user_id=eq.%s&deleted_at=is.null"+
			"&or=(source.is.null,source.not.in.(ics-sub,ics-import))"+
			"&order=id.asc&limit=%d&offset=%d", c.Endpoint, userID, pageSize, offset)
		req, _ := http.NewRequest("GET", u, nil)
		c.auth(req)
		resp, err := c.HTTP.Do(req)
		if err != nil {
			return nil, err
		}
		if resp.StatusCode != 200 {
			b, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			return nil, fmt.Errorf("list events: HTTP %d %s", resp.StatusCode, b)
		}
		var rows []Event
		if err := json.NewDecoder(resp.Body).Decode(&rows); err != nil {
			resp.Body.Close()
			return nil, err
		}
		resp.Body.Close()
		all = append(all, rows...)
		if len(rows) < pageSize {
			break
		}
	}
	// Paged by id for stable windowing; restore the start_at ordering the
	// callers expect.
	sort.SliceStable(all, func(i, j int) bool { return all[i].StartAt.Before(all[j].StartAt) })
	return all, nil
}

// FindEventByDevice returns the event id for a (user, device_id) pair.
func (c *Client) FindEventByDevice(userID, deviceID string) (string, error) {
	u := fmt.Sprintf("%s/rest/v1/events?select=id&user_id=eq.%s&device_id=eq.%s",
		c.Endpoint, userID, deviceID)
	req, _ := http.NewRequest("GET", u, nil)
	c.auth(req)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("find event: HTTP %d %s", resp.StatusCode, b)
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

// InsertEvent creates an event row.
func (c *Client) InsertEvent(e Event) error {
	body, _ := json.Marshal(e)
	req, _ := http.NewRequest("POST", c.Endpoint+"/rest/v1/events", bytes.NewReader(body))
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
		return fmt.Errorf("insert event: HTTP %d %s", resp.StatusCode, b)
	}
	return nil
}

// UpdateEvent PATCHes an event by id.
func (c *Client) UpdateEvent(id string, patch map[string]any) error {
	body, _ := json.Marshal(patch)
	u := fmt.Sprintf("%s/rest/v1/events?id=eq.%s", c.Endpoint, id)
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
		return fmt.Errorf("update event: HTTP %d %s", resp.StatusCode, b)
	}
	return nil
}
