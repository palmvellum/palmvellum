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
			ev.StartAt = time.Date(a.Year, time.Month(a.Month), a.Day, 0, 0, 0, 0, tz)
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
				"title": ev.Title, "start_at": ev.StartAt, "end_at": ev.EndAt,
				"all_day": ev.AllDay, "notes": ev.Notes, "alarm_minutes": ev.AlarmMinutes,
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

	type backfill struct{ id, dev string }
	var backfills []backfill
	appts := make([]datebookdb.Appointment, 0, len(events))
	for _, e := range events {
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
		a := datebookdb.Appointment{
			UniqueID: uid, Year: st.Year(), Month: int(st.Month()), Day: st.Day(),
			Description: e.Title,
		}
		if e.Notes != nil {
			a.Note = *e.Notes
		}
		if e.AllDay || e.EndAt == nil {
			a.Untimed = true
		} else {
			et := e.EndAt.In(tz)
			a.StartHour, a.StartMin = uint8(st.Hour()), uint8(st.Minute())
			a.EndHour, a.EndMin = uint8(et.Hour()), uint8(et.Minute())
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
	var ai *memodb.AppInfo
	if len(appInfo) > 0 {
		ai, _ = memodb.ParseAppInfo(appInfo)
	}
	if ai == nil {
		ai = memodb.DefaultAppInfo()
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
		if md := metaCategory(r); md != "" {
			ct.Category = ai.EnsureCategory(md)
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

	db := addressdb.NewAddressDB(ai.Encode())
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
func MailPull(c Cloud, userID, outPath string, appInfo []byte, tz *time.Location) (PullResult, error) {
	res := PullResult{OutPath: outPath}
	rows, err := c.ListByType(userID, "mail")
	if err != nil {
		return res, err
	}

	maxUID := maxDeviceUID(rows, "mail:")
	type backfill struct{ id, dev string }
	var backfills []backfill

	mails := make([]maildb.Mail, 0, len(rows))
	for _, r := range rows {
		if r.DeviceID != nil && !strings.HasPrefix(*r.DeviceID, "mail:") {
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
