package sync

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/palmvellum/palmvellum/packages/palm-engine/addressdb"
	"github.com/palmvellum/palmvellum/packages/palm-engine/cardio"
	"github.com/palmvellum/palmvellum/packages/palm-engine/cloud"
	"github.com/palmvellum/palmvellum/packages/palm-engine/datebookdb"
	"github.com/palmvellum/palmvellum/packages/palm-engine/maildb"
	"github.com/palmvellum/palmvellum/packages/palm-engine/memodb"
	"github.com/palmvellum/palmvellum/packages/palm-engine/pdb"
)

// ─────────────────────────── Date Book ↔ events ───────────────────────────

func alarmUnitMinutes(unit uint8) int {
	switch unit {
	case 1:
		return 60
	case 2:
		return 1440
	default:
		return 1
	}
}

// isFeedEventSource reports whether an event came from a calendar feed
// (a subscribed .ics calendar or a one-off .ics import) rather than from a
// user-owned client. Feed events are read-only and must never be pushed onto
// a Palm device — see DatebookPull. Sources are defined by the PWA:
// 'palm', 'web', 'ai' are user-owned; 'ics-sub' / 'ics-import' are feeds.
func isFeedEventSource(source string) bool {
	return source == "ics-sub" || source == "ics-import"
}

// Date Book sync window: only events starting within [now-1mo, now+1yr] are
// written onto the Palm. The cloud keeps the full history; the vintage device
// (limited RAM) only carries a rolling window so it can't be inflated by a
// large back-catalogue of past/far-future appointments. Events outside the
// window stay in the cloud and re-appear on-device once they enter the window.
const (
	datebookWindowPastMonths = 1
	datebookWindowFutureYear = 1
)

// inDatebookWindow reports whether an event start time falls inside the
// device sync window relative to now.
func inDatebookWindow(start, now time.Time) bool {
	lo := now.AddDate(0, -datebookWindowPastMonths, 0)
	hi := now.AddDate(datebookWindowFutureYear, 0, 0)
	return !start.Before(lo) && !start.After(hi)
}

// DatebookPush upserts a DatebookDB's appointments into the events table.
// Palm times are local; tz converts them to the absolute timestamps the
// cloud stores. Repeating appointments sync as their base occurrence only
// (repeat rules are not yet translated — documented limitation).
func DatebookPush(c Cloud, userID string, data []byte, tz *time.Location) (PushResult, error) {
	var res PushResult
	db, err := pdb.Read(data)
	if err != nil {
		return res, err
	}
	if string(db.Creator[:]) != "date" {
		return res, fmt.Errorf("datebook push: expected creator 'date', got %q", string(db.Creator[:]))
	}
	appts := datebookdb.DecodeAppointments(db)
	res.Total = len(appts)
	for _, a := range appts {
		title := strings.TrimSpace(a.Description)
		if title == "" {
			res.Skipped++
			continue
		}
		deviceID := fmt.Sprintf("date:%06x", a.UniqueID)
		ev := cloud.Event{
			ID: cloud.NewULID(), UserID: userID, Title: clip(title, 256),
			Source: "palm", DeviceID: &deviceID,
		}
		uid := int(a.UniqueID)
		ev.PalmRecordUID = &uid
		if a.Note != "" {
			n := a.Note
			ev.Notes = &n
		}
		if a.Untimed {
			ev.AllDay = true
			// An all-day event is timezone-independent: pin it to UTC midnight
			// (NOT the device tz). Local midnight would shift the UTC date back
			// a day for positive-offset zones like HK (Apple shows it one day
			// early) and disagree with the PWA / Android / mac clients, making
			// the same event flip-flop its date on each cross-client sync.
			ev.StartAt = time.Date(a.Year, time.Month(a.Month), a.Day, 0, 0, 0, 0, time.UTC)
		} else {
			ev.StartAt = time.Date(a.Year, time.Month(a.Month), a.Day, int(a.StartHour), int(a.StartMin), 0, 0, tz)
			end := time.Date(a.Year, time.Month(a.Month), a.Day, int(a.EndHour), int(a.EndMin), 0, 0, tz)
			if !end.Before(ev.StartAt) {
				ev.EndAt = &end
			}
		}
		if a.HasAlarm {
			m := int(a.AlarmAdvance) * alarmUnitMinutes(a.AlarmUnit)
			ev.AlarmMinutes = &m
		}

		existing, err := c.FindEventByDevice(userID, deviceID)
		if err != nil {
			return res, err
		}
		if existing == "" {
			if err := c.InsertEvent(ev); err != nil {
				return res, err
			}
			res.Inserted++
		} else {
			patch := map[string]any{
				"title": ev.Title, "notes": ev.Notes, "alarm_minutes": ev.AlarmMinutes,
			}
			// A Palm record can't store a start-without-end, so an *untimed*
			// record usually means "time unknown", not "all-day". Don't let it
			// clobber a richer timed event already in the cloud (web-created
			// times would otherwise be flattened to all-day/midnight). Only a
			// timed Palm record updates the cloud's start/end/all-day.
			if !a.Untimed {
				patch["start_at"] = ev.StartAt
				patch["end_at"] = ev.EndAt
				patch["all_day"] = ev.AllDay
			}
			if err := c.UpdateEvent(existing, patch); err != nil {
				return res, err
			}
			res.Updated++
		}
	}
	return res, nil
}

// DatebookPull regenerates a DatebookDB from the events table. appInfo is
// reused verbatim from the card (Date Book display prefs we don't model).
func DatebookPull(c Cloud, userID, outPath string, appInfo []byte, tz *time.Location) (PullResult, error) {
	res := PullResult{OutPath: outPath}
	events, err := c.ListEventsForUser(userID)
	if err != nil {
		return res, err
	}

	var maxUID uint32
	for _, e := range events {
		if e.DeviceID != nil && strings.HasPrefix(*e.DeviceID, "date:") {
			var u uint32
			fmt.Sscanf(*e.DeviceID, "date:%x", &u)
			if u > maxUID {
				maxUID = u
			}
		}
	}

	now := time.Now()
	type backfill struct{ id, dev string }
	var backfills []backfill
	appts := make([]datebookdb.Appointment, 0, len(events))
	for _, e := range events {
		// Only carry a rolling [now-1mo, now+1yr] window on the device; the
		// cloud keeps everything. Skip before back-fill so out-of-window events
		// aren't assigned a date: UID and re-appear naturally when they enter
		// the window. See inDatebookWindow.
		if !inDatebookWindow(e.StartAt, now) {
			continue
		}
		// Calendar-feed events (a subscribed .ics calendar, or a one-off
		// .ics import) live in the cloud / PWA only — they are read-only
		// consumers' data and must NEVER be written onto a vintage Palm.
		// A single subscribed calendar can hold thousands of VEVENTs; pushing
		// them inflates DatebookDB past what the device can hold, and the next
		// HotSync hangs partway through reading the bloated database back.
		// Feed events carry no device_id, so without this guard they would
		// fall through the date:-prefix check below, get a fresh date: UID,
		// and be materialised on-device (and back-filled in the cloud).
		if isFeedEventSource(e.Source) {
			continue
		}
		if e.DeviceID != nil && !strings.HasPrefix(*e.DeviceID, "date:") {
			continue
		}
		var uid uint32
		if e.DeviceID != nil {
			fmt.Sscanf(*e.DeviceID, "date:%x", &uid)
		} else {
			maxUID++
			uid = maxUID
			backfills = append(backfills, backfill{e.ID, fmt.Sprintf("date:%06x", uid)})
		}
		st := e.StartAt.In(tz)
		// All-day events are timezone-independent (pinned to UTC midnight):
		// take their calendar date from UTC so the Palm shows the same day as
		// every other client. Timed events use the device tz for both the date
		// and the wall-clock time below.
		dt := st
		if e.AllDay {
			dt = e.StartAt.UTC()
		}
		a := datebookdb.Appointment{
			UniqueID: uid, Year: dt.Year(), Month: int(dt.Month()), Day: dt.Day(),
			Description: e.Title,
		}
		if e.Notes != nil {
			a.Note = *e.Notes
		}
		// Only an explicitly all-day event is untimed on the Palm. A timed
		// event with no end (e.g. created on the web without a duration)
		// must still show its start time, so default end = start rather
		// than collapsing it to untimed (which hid the time on-device).
		if e.AllDay {
			a.Untimed = true
		} else {
			a.StartHour, a.StartMin = uint8(st.Hour()), uint8(st.Minute())
			if e.EndAt != nil {
				et := e.EndAt.In(tz)
				a.EndHour, a.EndMin = uint8(et.Hour()), uint8(et.Minute())
			} else {
				a.EndHour, a.EndMin = a.StartHour, a.StartMin
			}
		}
		if e.AlarmMinutes != nil {
			a.HasAlarm = true
			a.AlarmAdvance, a.AlarmUnit = compactAlarm(*e.AlarmMinutes)
		}
		appts = append(appts, a)
	}

	for _, b := range backfills {
		if err := c.UpdateEvent(b.id, map[string]any{"device_id": b.dev}); err != nil {
			res.BackfillFailed++
		} else {
			res.Backfilled++
		}
	}

	db := datebookdb.NewDatebookDB(appInfo)
	db.Records = datebookdb.EncodeAppointments(appts)
	db.UniqueSeed = maxUID
	db.CreatedAt = time.Now().UTC()
	db.ModifiedAt = db.CreatedAt
	raw, err := db.Write()
	if err != nil {
		return res, err
	}
	if err := cardio.WriteFile(outPath, raw); err != nil {
		return res, err
	}
	res.Written = len(appts)
	return res, nil
}

// compactAlarm picks the largest whole unit for an alarm in minutes.
func compactAlarm(min int) (advance, unit uint8) {
	if min <= 0 {
		return 0, 0
	}
	if min%1440 == 0 {
		return uint8(min / 1440), 2
	}
	if min%60 == 0 {
		return uint8(min / 60), 1
	}
	return uint8(min), 0
}

// ─────────────────────────── Address ↔ contact records ───────────────────────────

func contactMetadata(c addressdb.Contact, catName string) []byte {
	phones := make([]map[string]string, 0, len(c.Phones))
	for _, p := range c.Phones {
		phones = append(phones, map[string]string{"label": p.Label, "value": p.Value})
	}
	m := map[string]any{
		"palm_first_name": c.First, "palm_last_name": c.Last,
		"palm_company": c.Company, "palm_title": c.Title,
		"palm_phones": phones, "palm_address": c.Address,
		"palm_city": c.City, "palm_state": c.State, "palm_zip": c.Zip,
		"palm_country": c.Country, "palm_notes": c.Note,
		"palm_category_name": catName,
	}
	b, _ := json.Marshal(m)
	return b
}

// AddressPush upserts an AddressDB's contacts into records (type=contact).
func AddressPush(c Cloud, userID string, data []byte) (PushResult, error) {
	var res PushResult
	db, err := pdb.Read(data)
	if err != nil {
		return res, err
	}
	if string(db.Creator[:]) != "addr" {
		return res, fmt.Errorf("address push: expected creator 'addr', got %q", string(db.Creator[:]))
	}
	var ai *memodb.AppInfo
	if len(db.AppInfo) > 0 {
		ai, _ = memodb.ParseAppInfo(db.AppInfo)
	}
	contacts := addressdb.DecodeContacts(db)
	res.Total = len(contacts)
	for _, ct := range contacts {
		if strings.TrimSpace(ct.DisplayName()) == "(no name)" && len(ct.Phones) == 0 {
			res.Skipped++
			continue
		}
		catName := "Unfiled"
		if ai != nil {
			catName = ai.CategoryName(ct.Category)
		}
		deviceID := fmt.Sprintf("addr:%06x", ct.UniqueID)
		meta := contactMetadata(ct, catName)
		existing, err := c.FindByDevice(userID, deviceID)
		if err != nil {
			return res, err
		}
		if existing == "" {
			r := cloud.Record{
				ID: cloud.NewULID(), UserID: userID, Type: "contact", Posture: "open",
				Body: ct.DisplayName(), Source: "palm", DeviceID: &deviceID, Metadata: meta,
			}
			if err := c.Insert(r); err != nil {
				return res, err
			}
			res.Inserted++
		} else {
			patch := map[string]any{"body": ct.DisplayName(), "metadata": json.RawMessage(meta)}
			if err := c.Update(existing, patch); err != nil {
				return res, err
			}
			res.Updated++
		}
	}
	return res, nil
}

// AddressPull regenerates an AddressDB from contact records. appInfo is
// reused verbatim from the card (carries the 22 field labels + categories).
func AddressPull(c Cloud, userID, outPath string, appInfo []byte) (PullResult, error) {
	res := PullResult{OutPath: outPath}
	rows, err := c.ListByType(userID, "contact")
	if err != nil {
		return res, err
	}
	// Parse categories for name→index lookup only. The AppInfo itself is
	// preserved verbatim (below) — re-encoding via memodb would drop the
	// AddressDB field-label tail and shrink it from 638 to 280 bytes,
	// which crashes the Palm restore (DmWrite: DmWriteCheck failed).
	var ai *memodb.AppInfo
	if len(appInfo) > 0 {
		ai, _ = memodb.ParseAppInfo(appInfo)
	}

	maxUID := maxDeviceUID(rows, "addr:")
	type backfill struct{ id, dev string }
	var backfills []backfill

	contacts := make([]addressdb.Contact, 0, len(rows))
	for _, r := range rows {
		if r.DeviceID != nil && !strings.HasPrefix(*r.DeviceID, "addr:") {
			continue
		}
		var uid uint32
		if r.DeviceID != nil {
			fmt.Sscanf(*r.DeviceID, "addr:%x", &uid)
		} else {
			maxUID++
			uid = maxUID
			backfills = append(backfills, backfill{r.ID, fmt.Sprintf("addr:%06x", uid)})
		}
		ct := contactFromMetadata(r)
		ct.UniqueID = uid
		// Map to an existing category only; unknown names fall to Unfiled
		// (0) so we never have to mutate the verbatim AppInfo.
		if ai != nil {
			if md := metaCategory(r); md != "" {
				if idx, ok := ai.FindCategoryByName(md); ok {
					ct.Category = idx
				}
			}
		}
		contacts = append(contacts, ct)
	}

	for _, b := range backfills {
		if err := c.Update(b.id, map[string]any{"device_id": b.dev}); err != nil {
			res.BackfillFailed++
		} else {
			res.Backfilled++
		}
	}

	db := addressdb.NewAddressDB(appInfo) // verbatim 638-byte AppInfo
	db.Records = addressdb.EncodeContacts(contacts)
	db.UniqueSeed = maxUID
	db.CreatedAt = time.Now().UTC()
	db.ModifiedAt = db.CreatedAt
	raw, err := db.Write()
	if err != nil {
		return res, err
	}
	if err := cardio.WriteFile(outPath, raw); err != nil {
		return res, err
	}
	res.Written = len(contacts)
	return res, nil
}

func contactFromMetadata(r cloud.Record) addressdb.Contact {
	var ct addressdb.Contact
	if len(r.Metadata) == 0 {
		return ct
	}
	var m map[string]any
	if json.Unmarshal(r.Metadata, &m) != nil {
		return ct
	}
	s := func(k string) string {
		if v, ok := m[k].(string); ok {
			return v
		}
		return ""
	}
	ct.First, ct.Last = s("palm_first_name"), s("palm_last_name")
	ct.Company, ct.Title = s("palm_company"), s("palm_title")
	ct.Address, ct.City, ct.State = s("palm_address"), s("palm_city"), s("palm_state")
	ct.Zip, ct.Country, ct.Note = s("palm_zip"), s("palm_country"), s("palm_notes")
	if arr, ok := m["palm_phones"].([]any); ok {
		for _, e := range arr {
			if pm, ok := e.(map[string]any); ok {
				lbl, _ := pm["label"].(string)
				val, _ := pm["value"].(string)
				if val != "" {
					ct.Phones = append(ct.Phones, addressdb.Phone{Label: lbl, Value: val})
				}
			}
		}
	}
	return ct
}

// ─────────────────────────── Mail (cloud → card, one-way) ───────────────────────────

// MailPull writes the cloud's "mail" digest records into the Palm Inbox
// (category 0) so they can be read on-device. This is one-way: Palm Mail
// is a read surface for digests, so there is no MailPush. appInfo (folder
// categories) is reused verbatim from the card.
// mailSyncDays bounds how much mail history is written to the Palm (the cloud
// keeps everything). Mac + Android HotSync both honour this window.
const mailSyncDays = 5

func MailPull(c Cloud, userID, outPath string, appInfo []byte, tz *time.Location) (PullResult, error) {
	res := PullResult{OutPath: outPath}
	rows, err := c.ListByType(userID, "mail")
	if err != nil {
		return res, err
	}

	maxUID := maxDeviceUID(rows, "mail:")
	type backfill struct{ id, dev string }
	var backfills []backfill

	// Only the last few days of mail are written to the Palm — old digests are
	// noise on a storage-limited handheld. The cloud keeps the full history.
	cutoff := time.Now().Add(-mailSyncDays * 24 * time.Hour)

	mails := make([]maildb.Mail, 0, len(rows))
	for _, r := range rows {
		if r.DeviceID != nil && !strings.HasPrefix(*r.DeviceID, "mail:") {
			continue
		}
		if r.CreatedAt != nil && r.CreatedAt.Before(cutoff) {
			continue
		}
		var uid uint32
		if r.DeviceID != nil {
			fmt.Sscanf(*r.DeviceID, "mail:%x", &uid)
		} else {
			maxUID++
			uid = maxUID
			backfills = append(backfills, backfill{r.ID, fmt.Sprintf("mail:%06x", uid)})
		}
		subject, source := mailMeta(r)
		m := maildb.Mail{
			UniqueID: uid, Category: 0, // Inbox
			Subject: subject, From: source, Body: r.Body,
		}
		if r.CreatedAt != nil {
			t := r.CreatedAt.In(tz)
			m.Year, m.Month, m.Day = t.Year(), int(t.Month()), t.Day()
			m.Hour, m.Min = uint8(t.Hour()), uint8(t.Minute())
		}
		mails = append(mails, m)
	}

	for _, b := range backfills {
		if err := c.Update(b.id, map[string]any{"device_id": b.dev}); err != nil {
			res.BackfillFailed++
		} else {
			res.Backfilled++
		}
	}

	db := maildb.NewMailDB(appInfo)
	db.Records = maildb.EncodeMails(mails)
	db.UniqueSeed = maxUID
	db.CreatedAt = time.Now().UTC()
	db.ModifiedAt = db.CreatedAt
	raw, err := db.Write()
	if err != nil {
		return res, err
	}
	if err := cardio.WriteFile(outPath, raw); err != nil {
		return res, err
	}
	res.Written = len(mails)
	return res, nil
}

func mailMeta(r cloud.Record) (subject, source string) {
	if len(r.Metadata) == 0 {
		return "(digest)", ""
	}
	var m map[string]any
	if json.Unmarshal(r.Metadata, &m) != nil {
		return "(digest)", ""
	}
	if v, ok := m["mail_subject"].(string); ok {
		subject = v
	}
	if v, ok := m["mail_source_name"].(string); ok {
		source = v
	}
	if subject == "" {
		subject = "(digest)"
	}
	return subject, source
}

func clip(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}
