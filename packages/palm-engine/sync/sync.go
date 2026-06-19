// Package sync is the reusable card↔cloud sync engine shared by the
// CLI prototype and the end-user Mac daemon. It ports the on-device-
// verified push/pull logic out of the CLI's main.go into library form:
// functions return result structs instead of printing, take a Cloud
// interface (so they can be unit-tested against a fake), and write the
// regenerated .pdb through package cardio so no AppleDouble droppings
// reach the card.
//
// Identity & idempotency: each Palm record's 24-bit unique ID maps to a
// cloud device_id of "memo:<hex>" / "todo:<hex>". Push upserts on
// (user_id, device_id); pull regenerates the whole .pdb from cloud and
// backfills device_ids onto any cloud-origin rows that lacked one.
package sync

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/palmvellum/palmvellum/packages/palm-engine/cardio"
	"github.com/palmvellum/palmvellum/packages/palm-engine/cloud"
	"github.com/palmvellum/palmvellum/packages/palm-engine/memodb"
	"github.com/palmvellum/palmvellum/packages/palm-engine/pdb"
	"github.com/palmvellum/palmvellum/packages/palm-engine/tododb"
)

// AISeparator divides the user's question from the cloud AI answer in a
// single memo. ASCII-only so it survives the UTF-8→Big5 conversion the
// engine applies before writing to a CJKOS Palm.
const AISeparator = "\n-- AI --\n"

// Cloud is the subset of the Supabase client the engine needs.
// *cloud.Client satisfies it; tests supply a fake.
type Cloud interface {
	FindByDevice(userID, deviceID string) (string, error)
	Insert(r cloud.Record) error
	Update(id string, patch map[string]any) error
	ListForUser(userID string) ([]cloud.Record, error)
	// ListByType returns active records of the given types (for the
	// contact/mail conduits, which ListForUser's filter excludes).
	ListByType(userID string, types ...string) ([]cloud.Record, error)

	// Date Book → events table.
	ListEventsForUser(userID string) ([]cloud.Event, error)
	FindEventByDevice(userID, deviceID string) (string, error)
	InsertEvent(e cloud.Event) error
	UpdateEvent(id string, patch map[string]any) error
}

// PushResult counts the outcome of a push.
type PushResult struct {
	Inserted, Updated, Skipped, Total int
	// PendingAI holds the device_ids of newly-inserted aiquery memos.
	// The cloud AI worker fires on insert, so these are the records to
	// wait on (via WaitForAI) before pulling, so the answer comes back
	// to the device in the same sync.
	PendingAI []string
}

// PullResult counts the outcome of a pull.
type PullResult struct {
	Written         int
	Backfilled      int
	BackfillFailed  int
	OutPath         string
}

// ─────────────────────────── memo ───────────────────────────

func classifyMemo(ai *memodb.AppInfo, m memodb.Memo) (cloudType, catName string) {
	catName = "Unfiled"
	if ai != nil {
		catName = ai.CategoryName(m.Category)
	}
	if catName == "AI" {
		return "aiquery", catName
	}
	return "thought", catName
}

// MemoPush reads a MemoDB.pdb and upserts its memos into the cloud for
// userID. Blank memos are skipped.
func MemoPush(c Cloud, userID string, data []byte) (PushResult, error) {
	var res PushResult
	db, err := pdb.Read(data)
	if err != nil {
		return res, err
	}
	if string(db.Creator[:]) != "memo" {
		return res, fmt.Errorf("memo push: expected creator 'memo', got %q", string(db.Creator[:]))
	}
	var ai *memodb.AppInfo
	if len(db.AppInfo) > 0 {
		ai, _ = memodb.ParseAppInfo(db.AppInfo)
	}

	memos := memodb.DecodeMemos(db)
	res.Total = len(memos)
	for _, m := range memos {
		if strings.TrimSpace(m.Text) == "" {
			res.Skipped++
			continue
		}
		body := m.Text
		if sepAt := strings.Index(body, AISeparator); sepAt >= 0 {
			body = body[:sepAt]
		}
		body = strings.TrimRight(body, "\n")

		cloudType, catName := classifyMemo(ai, m)
		deviceID := fmt.Sprintf("memo:%06x", m.UniqueID)

		existing, err := c.FindByDevice(userID, deviceID)
		if err != nil {
			return res, err
		}

		meta, _ := json.Marshal(map[string]any{
			"palm_memo_uid":      fmt.Sprintf("%06x", m.UniqueID),
			"palm_category_idx":  m.Category,
			"palm_category_name": catName,
		})

		if existing == "" {
			var aiStatus *string
			if cloudType == "aiquery" {
				s := "pending"
				aiStatus = &s
			}
			r := cloud.Record{
				ID:       cloud.NewULID(),
				UserID:   userID,
				Type:     cloudType,
				Posture:  "open",
				Body:     body,
				Source:   "palm",
				DeviceID: &deviceID,
				AIStatus: aiStatus,
				Metadata: meta,
			}
			if err := c.Insert(r); err != nil {
				return res, err
			}
			res.Inserted++
			if cloudType == "aiquery" {
				res.PendingAI = append(res.PendingAI, deviceID)
			}
		} else {
			patch := map[string]any{"body": body, "metadata": json.RawMessage(meta)}
			if err := c.Update(existing, patch); err != nil {
				return res, err
			}
			res.Updated++
		}
	}
	return res, nil
}

// MemoPull regenerates a MemoDB.pdb from cloud state for userID and
// writes it to outPath (via cardio, so no AppleDouble sidecar).
func MemoPull(c Cloud, userID, outPath string) (PullResult, error) {
	res := PullResult{OutPath: outPath}
	rows, err := c.ListForUser(userID)
	if err != nil {
		return res, err
	}

	ai := memodb.DefaultAppInfo()
	aiCatIdx := ai.EnsureCategory("AI")

	maxUID := maxDeviceUID(rows, "memo:")

	type backfill struct{ cloudID, devID string }
	var backfills []backfill

	memos := make([]memodb.Memo, 0, len(rows))
	for _, r := range rows {
		if r.Type != "aiquery" && r.Type != "thought" {
			continue
		}
		if r.DeviceID != nil && !strings.HasPrefix(*r.DeviceID, "memo:") {
			continue
		}
		body := r.Body
		if r.Type == "aiquery" && r.AIResponse != nil && *r.AIResponse != "" {
			body = body + AISeparator + *r.AIResponse
		}
		var category uint8
		if r.Type == "aiquery" {
			category = aiCatIdx
		} else if md := metaCategory(r); md != "" && md != "AI" {
			category = ai.EnsureCategory(md)
		}
		var uid uint32
		if r.DeviceID != nil {
			fmt.Sscanf(*r.DeviceID, "memo:%x", &uid)
		} else {
			maxUID++
			uid = maxUID
			backfills = append(backfills, backfill{r.ID, fmt.Sprintf("memo:%06x", uid)})
		}
		memos = append(memos, memodb.Memo{UniqueID: uid, Category: category, Text: body})
	}

	for _, b := range backfills {
		if err := c.Update(b.cloudID, map[string]any{"device_id": b.devID}); err != nil {
			res.BackfillFailed++
		} else {
			res.Backfilled++
		}
	}

	db := memodb.NewMemoDB(ai)
	db.Records = memodb.EncodeMemos(memos)
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
	res.Written = len(memos)
	return res, nil
}

// ─────────────────────────── todo ───────────────────────────

type todoMeta struct {
	CategoryName string
	Priority     uint8
	DueDate      *time.Time
	Completed    bool
	Notes        string
}

func decodeTodoMeta(r cloud.Record) todoMeta {
	var out todoMeta
	if len(r.Metadata) == 0 {
		return out
	}
	var m map[string]any
	if json.Unmarshal(r.Metadata, &m) != nil {
		return out
	}
	if v, ok := m["palm_category_name"].(string); ok {
		out.CategoryName = v
	}
	if v, ok := m["palm_priority"].(float64); ok {
		out.Priority = uint8(v)
	}
	if v, ok := m["palm_completed"].(bool); ok {
		out.Completed = v
	}
	if v, ok := m["palm_notes"].(string); ok {
		out.Notes = v
	}
	if v, ok := m["palm_due_date"].(string); ok && v != "" {
		if t, err := time.Parse("2006-01-02", v); err == nil {
			out.DueDate = &t
		}
	}
	return out
}

// TodoPush reads a ToDoDB.pdb and upserts its todos into the cloud.
func TodoPush(c Cloud, userID string, data []byte) (PushResult, error) {
	var res PushResult
	db, err := pdb.Read(data)
	if err != nil {
		return res, err
	}
	if string(db.Creator[:]) != "todo" {
		return res, fmt.Errorf("todo push: expected creator 'todo', got %q", string(db.Creator[:]))
	}
	var ai *tododb.AppInfo
	if len(db.AppInfo) > 0 {
		ai, _ = tododb.ParseAppInfo(db.AppInfo)
	}
	todos, err := tododb.DecodeTodos(db)
	if err != nil {
		return res, err
	}
	res.Total = len(todos)
	for _, t := range todos {
		if strings.TrimSpace(t.Description) == "" {
			res.Skipped++
			continue
		}
		catName := "Unfiled"
		if ai != nil {
			catName = ai.CategoryName(t.Category)
		}
		deviceID := fmt.Sprintf("todo:%06x", t.UniqueID)
		existing, err := c.FindByDevice(userID, deviceID)
		if err != nil {
			return res, err
		}
		dueISO := ""
		if t.DueDate != nil {
			dueISO = t.DueDate.Format("2006-01-02")
		}
		meta, _ := json.Marshal(map[string]any{
			"palm_todo_uid":      fmt.Sprintf("%06x", t.UniqueID),
			"palm_category_idx":  t.Category,
			"palm_category_name": catName,
			"palm_priority":      t.Priority,
			"palm_due_date":      dueISO,
			"palm_completed":     t.Completed,
			"palm_notes":         t.Notes,
		})
		if existing == "" {
			r := cloud.Record{
				ID: cloud.NewULID(), UserID: userID, Type: "todo", Posture: "open",
				Body: t.Description, Source: "palm", DeviceID: &deviceID, Metadata: meta,
			}
			if err := c.Insert(r); err != nil {
				return res, err
			}
			res.Inserted++
		} else {
			patch := map[string]any{"body": t.Description, "metadata": json.RawMessage(meta)}
			if err := c.Update(existing, patch); err != nil {
				return res, err
			}
			res.Updated++
		}
	}
	return res, nil
}

// TodoPull regenerates a ToDoDB.pdb from cloud state and writes it.
func TodoPull(c Cloud, userID, outPath string) (PullResult, error) {
	res := PullResult{OutPath: outPath}
	rows, err := c.ListForUser(userID)
	if err != nil {
		return res, err
	}
	ai := tododb.DefaultAppInfo()
	maxUID := maxDeviceUID(rows, "todo:")

	type backfill struct{ cloudID, devID string }
	var backfills []backfill

	todos := make([]tododb.Todo, 0)
	for _, r := range rows {
		if r.Type != "todo" {
			continue
		}
		if r.DeviceID != nil && !strings.HasPrefix(*r.DeviceID, "todo:") {
			continue
		}
		md := decodeTodoMeta(r)
		var uid uint32
		if r.DeviceID != nil {
			fmt.Sscanf(*r.DeviceID, "todo:%x", &uid)
		} else {
			maxUID++
			uid = maxUID
			backfills = append(backfills, backfill{r.ID, fmt.Sprintf("todo:%06x", uid)})
		}
		var category uint8
		if md.CategoryName != "" {
			category = ai.EnsureCategory(md.CategoryName)
		}
		todos = append(todos, tododb.Todo{
			UniqueID: uid, Category: category, DueDate: md.DueDate,
			Priority: md.Priority, Completed: md.Completed,
			Description: r.Body, Notes: md.Notes,
		})
	}

	for _, b := range backfills {
		if err := c.Update(b.cloudID, map[string]any{"device_id": b.devID}); err != nil {
			res.BackfillFailed++
		} else {
			res.Backfilled++
		}
	}

	db := tododb.NewTodoDB(ai)
	db.Records = tododb.EncodeTodos(todos)
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
	res.Written = len(todos)
	return res, nil
}

// ─────────────────────────── card-level ───────────────────────────

// CardResult is the outcome of a full-card sync.
type CardResult struct {
	Memo         *PushResult
	MemoPull     *PullResult
	Todo         *PushResult
	TodoPull     *PullResult
	Datebook     *PushResult
	DatebookPull *PullResult
	Address      *PushResult
	AddressPull  *PullResult
	MailPull     *PullResult // Mail is one-way (cloud → card)
	CleanedJunk  []string    // macOS droppings removed before eject
}

// SyncCard runs the full round-trip with no progress logging and no AI
// wait, using the local time zone for Date Book.
func SyncCard(c Cloud, userID, setDir string) (CardResult, error) {
	return SyncCardLog(c, userID, setDir, 0, time.Local, nil)
}

// SyncCardLog runs the full round-trip for a Sony MS Backup set directory
// (the folder holding MemoDB.pdb / ToDoDB.pdb): push each present DB to
// the cloud, pull cloud state back in place, then sweep macOS metadata
// droppings so the device's restore-from-card won't choke. Missing DBs
// are skipped (a card may lack one). Pull is destructive (last-write-
// wins), matching the documented v1 behaviour.
//
// logf, if non-nil, receives a human-readable line at each step so a GUI
// can show live progress.
//
// aiWait > 0 makes the memo phase wait (up to aiWait) for the cloud AI
// worker to answer any newly-pushed "(AI)" memos before pulling, so the
// answers come back to the card in the same sync. 0 = don't wait.
func SyncCardLog(c Cloud, userID, setDir string, aiWait time.Duration, tz *time.Location, logf func(string)) (CardResult, error) {
	var out CardResult
	if tz == nil {
		tz = time.Local
	}
	log := func(format string, a ...any) {
		if logf != nil {
			logf(fmt.Sprintf(format, a...))
		}
	}

	memoPath := filepath.Join(setDir, "MemoDB.pdb")
	if data, err := os.ReadFile(memoPath); err == nil {
		log("Memo Pad → cloud…")
		pr, err := MemoPush(c, userID, data)
		if err != nil {
			return out, fmt.Errorf("memo push: %w", err)
		}
		out.Memo = &pr
		log("  +%d new, ~%d updated, %d skipped", pr.Inserted, pr.Updated, pr.Skipped)
		if aiWait > 0 && len(pr.PendingAI) > 0 {
			log("Waiting for AI to answer %d memo(s)…", len(pr.PendingAI))
			WaitForAI(c, userID, pr.PendingAI, aiWait, 3*time.Second, logf)
		}
		log("Memo Pad ← cloud…")
		pl, err := MemoPull(c, userID, memoPath)
		if err != nil {
			return out, fmt.Errorf("memo pull: %w", err)
		}
		out.MemoPull = &pl
		log("  wrote %d memos to card", pl.Written)
	} else if os.IsNotExist(err) {
		log("(no MemoDB.pdb on card)")
	} else {
		return out, err
	}

	todoPath := filepath.Join(setDir, "ToDoDB.pdb")
	if data, err := os.ReadFile(todoPath); err == nil {
		log("To Do → cloud…")
		pr, err := TodoPush(c, userID, data)
		if err != nil {
			return out, fmt.Errorf("todo push: %w", err)
		}
		out.Todo = &pr
		log("  +%d new, ~%d updated, %d skipped", pr.Inserted, pr.Updated, pr.Skipped)
		log("To Do ← cloud…")
		pl, err := TodoPull(c, userID, todoPath)
		if err != nil {
			return out, fmt.Errorf("todo pull: %w", err)
		}
		out.TodoPull = &pl
		log("  wrote %d todos to card", pl.Written)
	} else if os.IsNotExist(err) {
		log("(no ToDoDB.pdb on card)")
	} else {
		return out, err
	}

	datePath := filepath.Join(setDir, "DatebookDB.pdb")
	if data, err := os.ReadFile(datePath); err == nil {
		log("Date Book → cloud…")
		pr, err := DatebookPush(c, userID, data, tz)
		if err != nil {
			return out, fmt.Errorf("datebook push: %w", err)
		}
		out.Datebook = &pr
		log("  +%d new, ~%d updated, %d skipped", pr.Inserted, pr.Updated, pr.Skipped)
		log("Date Book ← cloud…")
		pl, err := DatebookPull(c, userID, datePath, appInfoOf(data), tz)
		if err != nil {
			return out, fmt.Errorf("datebook pull: %w", err)
		}
		out.DatebookPull = &pl
		log("  wrote %d appointments to card", pl.Written)
	} else if os.IsNotExist(err) {
		log("(no DatebookDB.pdb on card)")
	} else {
		return out, err
	}

	addrPath := filepath.Join(setDir, "AddressDB.pdb")
	if data, err := os.ReadFile(addrPath); err == nil {
		log("Address → cloud…")
		pr, err := AddressPush(c, userID, data)
		if err != nil {
			return out, fmt.Errorf("address push: %w", err)
		}
		out.Address = &pr
		log("  +%d new, ~%d updated, %d skipped", pr.Inserted, pr.Updated, pr.Skipped)
		log("Address ← cloud…")
		pl, err := AddressPull(c, userID, addrPath, appInfoOf(data))
		if err != nil {
			return out, fmt.Errorf("address pull: %w", err)
		}
		out.AddressPull = &pl
		log("  wrote %d contacts to card", pl.Written)
	} else if os.IsNotExist(err) {
		log("(no AddressDB.pdb on card)")
	} else {
		return out, err
	}

	mailPath := filepath.Join(setDir, "MailDB.pdb")
	if data, err := os.ReadFile(mailPath); err == nil {
		log("Mail ← cloud (digests)…")
		pl, err := MailPull(c, userID, mailPath, appInfoOf(data), tz)
		if err != nil {
			return out, fmt.Errorf("mail pull: %w", err)
		}
		out.MailPull = &pl
		log("  wrote %d digests to Inbox", pl.Written)
	} else if os.IsNotExist(err) {
		log("(no MailDB.pdb on card)")
	} else {
		return out, err
	}

	cleaned, err := cardio.Clean(setDir)
	if err != nil {
		return out, fmt.Errorf("clean card: %w", err)
	}
	out.CleanedJunk = cleaned
	if len(cleaned) > 0 {
		log("Cleaned %d macOS dropping(s)", len(cleaned))
	}
	log("✅ Done — safe to eject and restore on the Palm")
	return out, nil
}

// ─────────────────────────── helpers ───────────────────────────

// WaitForAI polls the cloud until every device_id in pending reaches a
// terminal ai_status (done/error) or the budget elapses. It reports the
// number still pending via logf on each round. Returns the count that
// finished. interval is the poll period.
func WaitForAI(c Cloud, userID string, pending []string, budget, interval time.Duration, logf func(string)) int {
	if len(pending) == 0 {
		return 0
	}
	want := map[string]bool{}
	for _, d := range pending {
		want[d] = true
	}
	done := map[string]bool{}
	deadline := time.Now().Add(budget)
	for {
		rows, err := c.ListForUser(userID)
		if err == nil {
			for _, r := range rows {
				if r.DeviceID == nil || !want[*r.DeviceID] || done[*r.DeviceID] {
					continue
				}
				st := ""
				if r.AIStatus != nil {
					st = *r.AIStatus
				}
				answered := r.AIResponse != nil && *r.AIResponse != ""
				if st == "done" || st == "error" || answered {
					done[*r.DeviceID] = true
				}
			}
		}
		if len(done) >= len(want) {
			return len(done)
		}
		if time.Now().After(deadline) {
			if logf != nil {
				logf(fmt.Sprintf("  AI timed out — %d answer(s) not ready, syncing what's available", len(want)-len(done)))
			}
			return len(done)
		}
		if logf != nil {
			logf(fmt.Sprintf("  AI working… %d of %d answered", len(done), len(want)))
		}
		time.Sleep(interval)
	}
}

// appInfoOf returns the AppInfo block of a .pdb so the pull side can
// reuse the card's existing categories / field labels verbatim.
func appInfoOf(data []byte) []byte {
	db, err := pdb.Read(data)
	if err != nil {
		return nil
	}
	return db.AppInfo
}

func metaCategory(r cloud.Record) string {
	if len(r.Metadata) == 0 {
		return ""
	}
	var m map[string]any
	if json.Unmarshal(r.Metadata, &m) != nil {
		return ""
	}
	if v, ok := m["palm_category_name"].(string); ok {
		return v
	}
	return ""
}

func maxDeviceUID(rows []cloud.Record, prefix string) uint32 {
	var max uint32
	for _, r := range rows {
		if r.DeviceID == nil || !strings.HasPrefix(*r.DeviceID, prefix) {
			continue
		}
		var uid uint32
		fmt.Sscanf(*r.DeviceID, prefix+"%x", &uid)
		if uid > max {
			max = uid
		}
	}
	return max
}
