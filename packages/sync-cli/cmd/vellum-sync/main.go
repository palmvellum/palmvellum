// vellum-sync — manual sync between PalmOS native databases and
// Supabase records.
//
// The default workflow now targets the stock Memo Pad and To Do apps:
//
//	./vellum memo sync  ~/Downloads/MemoDB.pdb     # push + wait + pull
//	./vellum todo sync  ~/Downloads/ToDoDB.pdb
//
// Or use the individual halves:
//
//	./vellum memo push  ~/Downloads/MemoDB.pdb     # Palm → cloud
//	./vellum memo pull  -out ~/Downloads/MemoDB.pdb   # cloud → Palm
//	./vellum todo push  ~/Downloads/ToDoDB.pdb
//	./vellum todo pull  -out ~/Downloads/ToDoDB.pdb
//	./vellum inspect    <any.pdb>                  # decode + dump
//	./vellum starter memo -out ~/Downloads/MemoDB.pdb  # ship an empty
//	                                                    MemoDB with "AI"
//	                                                    category pre-set
//	./vellum starter todo -out ~/Downloads/ToDoDB.pdb
//
// AI Mode = MemoPad memos in the "AI" category. They land in records as
//           type=aiquery and the AI worker writes ai_response. On pull
//           the response is appended to the memo body, separated by
//           "\n— AI —\n", so the user sees Q + A in a single memo.
// Note    = MemoPad memos in any other category → records type=thought.
//           Bidirectional, latest-mtime-wins (mtime tracked in metadata
//           via the cloud's updated_at vs the memo's pushed_at).
// Todo    = ToDoDB entries → records type=todo with metadata.due_date /
//           priority / completed.
//
// Env vars (required for push/pull/sync):
//
//	SUPABASE_URL              https://<project>.supabase.co
//	SUPABASE_SERVICE_ROLE_KEY service role JWT for unrestricted REST writes
//	PALM_USER_ID              auth.users.id of the test user
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/palmvellum/palmvellum/packages/palm-engine/cloud"
	"github.com/palmvellum/palmvellum/packages/palm-engine/memodb"
	"github.com/palmvellum/palmvellum/packages/palm-engine/pdb"
	"github.com/palmvellum/palmvellum/packages/palm-engine/tododb"
)

// AISeparator divides the user's question from the cloud AI answer in a
// single memo. ASCII-only so it survives the UTF-8→Big5 conversion the
// engine applies before writing to a CJKOS Palm (the old em-dash form
// "— AI —" is not valid Big5 and rendered as 亂碼 on-device).
const AISeparator = "\n-- AI --\n"

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "memo":
		dispatchMemo(os.Args[2:])
	case "todo":
		dispatchTodo(os.Args[2:])
	case "inspect":
		cmdInspect(os.Args[2:])
	case "starter":
		dispatchStarter(os.Args[2:])
	case "-h", "--help", "help":
		usage()
	default:
		fmt.Fprintf(os.Stderr, "unknown subcommand: %s\n\n", os.Args[1])
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, `vellum-sync — PalmOS database ↔ Supabase

Native-app modes (current):
  memo sync <file>          Push + wait + pull MemoDB.pdb in one step
  memo push <file>          Upload memos to cloud
  memo pull -out <file>     Build fresh MemoDB.pdb from cloud
  todo sync <file>          Push + wait + pull ToDoDB.pdb in one step
  todo push <file>          Upload todos
  todo pull -out <file>     Build fresh ToDoDB.pdb

Utilities:
  inspect <file.pdb>        Auto-detect type and dump contents
  starter memo -out <file>  Empty MemoDB with categories Unfiled/Personal
                            /Business/AI ready for install on a Palm
  starter todo -out <file>  Empty ToDoDB

AI Mode  = memo in MemoPad category "AI". Cloud AI worker writes
           ai_response, which the next pull appends to the memo body
           below a "— AI —" separator.
Note     = memo in any other category. Bidirectional with the cloud.
Todo     = native ToDo entry → records.type=todo plus metadata for
           due_date / priority / completed.

Env:
  SUPABASE_URL
  SUPABASE_SERVICE_ROLE_KEY
  PALM_USER_ID`)
}

func mustEnv(k string) string {
	v := os.Getenv(k)
	if v == "" {
		fmt.Fprintf(os.Stderr, "missing env %s\n", k)
		os.Exit(2)
	}
	return v
}

func newClient() *cloud.Client {
	// Legacy single-user CLI: service_role as both apikey and bearer
	// (admin, bypasses RLS). The end-user daemon uses anon + user JWT.
	key := mustEnv("SUPABASE_SERVICE_ROLE_KEY")
	return cloud.New(mustEnv("SUPABASE_URL"), key, key)
}

func die(err error) {
	fmt.Fprintf(os.Stderr, "error: %v\n", err)
	os.Exit(1)
}

func snip(s string) string {
	if len(s) > 50 {
		return s[:50] + "…"
	}
	return s
}

// ─────────────────────────── inspect ───────────────────────────

func cmdInspect(args []string) {
	if len(args) < 1 {
		fmt.Fprintln(os.Stderr, "usage: vellum-sync inspect <file.pdb>")
		os.Exit(2)
	}
	b, err := os.ReadFile(args[0])
	if err != nil {
		die(err)
	}
	db, err := pdb.Read(b)
	if err != nil {
		die(err)
	}
	creator := string(db.Creator[:])
	fmt.Printf("PDB    name=%q  type=%q  creator=%q  records=%d  appinfo=%dB\n",
		db.Name, string(db.Type[:]), creator, len(db.Records), len(db.AppInfo))
	switch creator {
	case "memo":
		inspectMemoDB(db)
	case "todo":
		inspectTodoDB(db)
	default:
		fmt.Println("  (raw bytes — no decoder for this creator)")
	}
}

func inspectMemoDB(db *pdb.DB) {
	if len(db.AppInfo) > 0 {
		ai, err := memodb.ParseAppInfo(db.AppInfo)
		if err == nil {
			fmt.Println("  categories:")
			for i, c := range ai.Categories {
				if c.Name == "" {
					continue
				}
				fmt.Printf("    [%2d] %-16s uid=%d  renamed=%v\n",
					i, c.Name, c.UniqID, c.Renamed)
			}
		}
	}
	memos := memodb.DecodeMemos(db)
	for i, m := range memos {
		fmt.Printf("  memo[%d] uid=%06x  cat=%d  %q\n",
			i, m.UniqueID, m.Category, snip(strings.ReplaceAll(m.Text, "\n", "⏎")))
	}
}

func inspectTodoDB(db *pdb.DB) {
	if len(db.AppInfo) > 0 {
		ai, err := tododb.ParseAppInfo(db.AppInfo)
		if err == nil {
			fmt.Println("  categories:")
			for i, c := range ai.Categories {
				if c.Name == "" {
					continue
				}
				fmt.Printf("    [%2d] %-16s uid=%d  renamed=%v\n",
					i, c.Name, c.UniqID, c.Renamed)
			}
		}
	}
	todos, err := tododb.DecodeTodos(db)
	if err != nil {
		fmt.Printf("  todo decode failed: %v\n", err)
		return
	}
	for i, t := range todos {
		date := "—"
		if t.DueDate != nil {
			date = t.DueDate.Format("2006-01-02")
		}
		mark := " "
		if t.Completed {
			mark = "x"
		}
		fmt.Printf("  todo[%d] uid=%06x  cat=%d  [%s] p%d due=%s  %q\n",
			i, t.UniqueID, t.Category, mark, t.Priority, date, snip(t.Description))
	}
}

// ─────────────────────────── starter ───────────────────────────

func dispatchStarter(args []string) {
	if len(args) < 1 {
		fmt.Fprintln(os.Stderr, "usage: vellum-sync starter (memo|todo) -out <file>")
		os.Exit(2)
	}
	kind := args[0]
	fs := flag.NewFlagSet("starter", flag.ExitOnError)
	out := fs.String("out", "", "output .pdb path")
	_ = fs.Parse(args[1:])
	if *out == "" {
		fmt.Fprintln(os.Stderr, "starter: -out required")
		os.Exit(2)
	}

	now := time.Now().UTC()
	switch kind {
	case "memo":
		ai := memodb.DefaultAppInfo()
		ai.EnsureCategory("AI")
		db := memodb.NewMemoDB(ai)
		db.CreatedAt = now
		db.ModifiedAt = now
		writePDBFile(*out, db)
		fmt.Printf("starter memo: %s — categories include %q\n", *out, "AI")
	case "todo":
		ai := tododb.DefaultAppInfo()
		db := tododb.NewTodoDB(ai)
		db.CreatedAt = now
		db.ModifiedAt = now
		writePDBFile(*out, db)
		fmt.Printf("starter todo: %s\n", *out)
	default:
		fmt.Fprintf(os.Stderr, "starter: unknown kind %q (memo|todo)\n", kind)
		os.Exit(2)
	}
}

func writePDBFile(path string, db *pdb.DB) {
	bytes, err := db.Write()
	if err != nil {
		die(err)
	}
	if err := os.WriteFile(path, bytes, 0o644); err != nil {
		die(err)
	}
}

// ─────────────────────────── memo ───────────────────────────

func dispatchMemo(args []string) {
	if len(args) < 1 {
		fmt.Fprintln(os.Stderr, "usage: vellum-sync memo (sync|push|pull) [args]")
		os.Exit(2)
	}
	switch args[0] {
	case "push":
		cmdMemoPush(args[1:])
	case "pull":
		cmdMemoPull(args[1:])
	case "sync":
		cmdMemoSync(args[1:])
	default:
		fmt.Fprintf(os.Stderr, "memo: unknown sub %q\n", args[0])
		os.Exit(2)
	}
}

// classifyMemo picks records.type based on the memo's category name
// inside the MemoDB AppInfo. "AI" → aiquery, otherwise → thought.
func classifyMemo(ai *memodb.AppInfo, m memodb.Memo) (string, string) {
	catName := "Unfiled"
	if ai != nil {
		catName = ai.CategoryName(m.Category)
	}
	if catName == "AI" {
		return "aiquery", catName
	}
	return "thought", catName
}

func cmdMemoPush(args []string) {
	if len(args) < 1 {
		fmt.Fprintln(os.Stderr, "usage: vellum-sync memo push <file.pdb>")
		os.Exit(2)
	}
	userID := mustEnv("PALM_USER_ID")
	c := newClient()

	b, err := os.ReadFile(args[0])
	if err != nil {
		die(err)
	}
	db, err := pdb.Read(b)
	if err != nil {
		die(err)
	}
	if string(db.Creator[:]) != "memo" {
		fmt.Fprintf(os.Stderr, "memo push: refusing %q — expected MemoDB (creator 'memo')\n",
			string(db.Creator[:]))
		os.Exit(2)
	}
	var ai *memodb.AppInfo
	if len(db.AppInfo) > 0 {
		ai, _ = memodb.ParseAppInfo(db.AppInfo)
	}

	memos := memodb.DecodeMemos(db)
	inserted, updated, skipped := 0, 0, 0
	for i, m := range memos {
		if strings.TrimSpace(m.Text) == "" {
			skipped++
			continue
		}
		// Splitting body from any locally-appended AI answer so the
		// cloud only stores the question portion. The separator
		// matches AISeparator emitted by pull.
		body := m.Text
		if sepAt := strings.Index(body, AISeparator); sepAt >= 0 {
			body = body[:sepAt]
		}
		body = strings.TrimRight(body, "\n")

		cloudType, catName := classifyMemo(ai, m)
		deviceID := fmt.Sprintf("memo:%06x", m.UniqueID)

		existing, err := c.FindByDevice(userID, deviceID)
		if err != nil {
			die(err)
		}

		var aiStatus *string
		if cloudType == "aiquery" {
			s := "pending"
			aiStatus = &s
		}

		meta, _ := json.Marshal(map[string]any{
			"palm_memo_uid":      fmt.Sprintf("%06x", m.UniqueID),
			"palm_category_idx":  m.Category,
			"palm_category_name": catName,
		})

		if existing == "" {
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
				fmt.Printf("[%d] insert failed: %v\n", i, err)
				skipped++
				continue
			}
			inserted++
			fmt.Printf("[%d] + %-7s cat=%s uid=%06x  %s\n",
				i, cloudType, catName, m.UniqueID, snip(body))
		} else {
			patch := map[string]any{
				"body":     body,
				"metadata": json.RawMessage(meta),
			}
			if err := c.Update(existing, patch); err != nil {
				fmt.Printf("[%d] update failed: %v\n", i, err)
				skipped++
				continue
			}
			updated++
			fmt.Printf("[%d] ~ %-7s cat=%s uid=%06x\n",
				i, cloudType, catName, m.UniqueID)
		}
	}
	fmt.Printf("\nmemo push: +%d inserted, ~%d updated, %d skipped (of %d).\n",
		inserted, updated, skipped, len(memos))
}

func cmdMemoPull(args []string) {
	fs := flag.NewFlagSet("memo pull", flag.ExitOnError)
	out := fs.String("out", "MemoDB.pdb", "output .pdb path")
	_ = fs.Parse(args)
	userID := mustEnv("PALM_USER_ID")
	c := newClient()

	rows, err := c.ListForUser(userID)
	if err != nil {
		die(err)
	}

	ai := memodb.DefaultAppInfo()
	aiCatIdx := ai.EnsureCategory("AI")

	// First pass: find the highest already-assigned Palm uniqueID
	// among cloud rows that already have a memo:* device_id, so the
	// fresh-record path can hand out the next sequential ID.
	var maxUID uint32
	for _, r := range rows {
		if r.DeviceID == nil {
			continue
		}
		if !strings.HasPrefix(*r.DeviceID, "memo:") {
			continue
		}
		var uid uint32
		fmt.Sscanf(*r.DeviceID, "memo:%x", &uid)
		if uid > maxUID {
			maxUID = uid
		}
	}

	type backfill struct {
		cloudID, devID string
	}
	var backfills []backfill

	memos := make([]memodb.Memo, 0, len(rows))
	for _, r := range rows {
		if r.Type != "aiquery" && r.Type != "thought" {
			continue
		}
		if r.DeviceID != nil &&
			!strings.HasPrefix(*r.DeviceID, "memo:") {
			continue
		}
		body := r.Body
		if r.Type == "aiquery" && r.AIResponse != nil && *r.AIResponse != "" {
			body = body + AISeparator + *r.AIResponse
		}

		var category uint8
		if r.Type == "aiquery" {
			category = aiCatIdx
		} else if md := parseMetadataCategory(r); md != "" && md != "AI" {
			category = ai.EnsureCategory(md)
		}

		var uid uint32
		if r.DeviceID != nil {
			fmt.Sscanf(*r.DeviceID, "memo:%x", &uid)
		} else {
			maxUID++
			uid = maxUID
			devID := fmt.Sprintf("memo:%06x", uid)
			backfills = append(backfills, backfill{cloudID: r.ID, devID: devID})
		}
		memos = append(memos, memodb.Memo{
			UniqueID: uid,
			Category: category,
			Text:     body,
		})
	}

	// Backfill device_id on cloud rows that had none — must run BEFORE
	// the next push or we'd duplicate. Errors here are non-fatal but
	// noted so the user can re-run.
	backfillFailures := 0
	for _, b := range backfills {
		if err := c.Update(b.cloudID, map[string]any{"device_id": b.devID}); err != nil {
			fmt.Printf("warn: backfill %s ← %s failed: %v\n", b.cloudID, b.devID, err)
			backfillFailures++
		}
	}

	db := memodb.NewMemoDB(ai)
	db.Records = memodb.EncodeMemos(memos)
	db.UniqueSeed = maxUID
	db.CreatedAt = time.Now().UTC()
	db.ModifiedAt = db.CreatedAt
	writePDBFile(*out, db)

	tail := ""
	if len(backfills) > 0 {
		tail = fmt.Sprintf(" (backfilled %d device_ids", len(backfills))
		if backfillFailures > 0 {
			tail += fmt.Sprintf(", %d failed", backfillFailures)
		}
		tail += ")"
	}
	fmt.Printf("memo pull: wrote %d memos to %s%s.\n", len(memos), *out, tail)
}

func parseMetadataCategory(r cloud.Record) string {
	if len(r.Metadata) == 0 {
		return ""
	}
	var m map[string]any
	if err := json.Unmarshal(r.Metadata, &m); err != nil {
		return ""
	}
	if v, ok := m["palm_category_name"].(string); ok {
		return v
	}
	return ""
}

func cmdMemoSync(args []string) {
	if len(args) < 1 {
		fmt.Fprintln(os.Stderr, "usage: vellum-sync memo sync <file.pdb> [-wait 8s]")
		os.Exit(2)
	}
	file := args[0]
	fs := flag.NewFlagSet("memo sync", flag.ExitOnError)
	wait := fs.Duration("wait", 8*time.Second, "wait time for AI worker before pull")
	_ = fs.Parse(args[1:])

	fmt.Println("→ push")
	cmdMemoPush([]string{file})
	fmt.Printf("→ waiting %s for AI worker…\n", *wait)
	time.Sleep(*wait)
	fmt.Println("→ pull (in-place)")
	cmdMemoPull([]string{"-out", file})
}

// ─────────────────────────── todo ───────────────────────────

func dispatchTodo(args []string) {
	if len(args) < 1 {
		fmt.Fprintln(os.Stderr, "usage: vellum-sync todo (sync|push|pull) [args]")
		os.Exit(2)
	}
	switch args[0] {
	case "push":
		cmdTodoPush(args[1:])
	case "pull":
		cmdTodoPull(args[1:])
	case "sync":
		cmdTodoSync(args[1:])
	default:
		fmt.Fprintf(os.Stderr, "todo: unknown sub %q\n", args[0])
		os.Exit(2)
	}
}

func cmdTodoPush(args []string) {
	if len(args) < 1 {
		fmt.Fprintln(os.Stderr, "usage: vellum-sync todo push <file.pdb>")
		os.Exit(2)
	}
	userID := mustEnv("PALM_USER_ID")
	c := newClient()

	b, err := os.ReadFile(args[0])
	if err != nil {
		die(err)
	}
	db, err := pdb.Read(b)
	if err != nil {
		die(err)
	}
	if string(db.Creator[:]) != "todo" {
		fmt.Fprintf(os.Stderr, "todo push: refusing %q — expected ToDoDB (creator 'todo')\n",
			string(db.Creator[:]))
		os.Exit(2)
	}
	var ai *tododb.AppInfo
	if len(db.AppInfo) > 0 {
		ai, _ = tododb.ParseAppInfo(db.AppInfo)
	}

	todos, err := tododb.DecodeTodos(db)
	if err != nil {
		die(err)
	}

	inserted, updated, skipped := 0, 0, 0
	for i, t := range todos {
		if strings.TrimSpace(t.Description) == "" {
			skipped++
			continue
		}
		catName := "Unfiled"
		if ai != nil {
			catName = ai.CategoryName(t.Category)
		}
		deviceID := fmt.Sprintf("todo:%06x", t.UniqueID)

		existing, err := c.FindByDevice(userID, deviceID)
		if err != nil {
			die(err)
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
				ID:       cloud.NewULID(),
				UserID:   userID,
				Type:     "todo",
				Posture:  "open",
				Body:     t.Description,
				Source:   "palm",
				DeviceID: &deviceID,
				Metadata: meta,
			}
			if err := c.Insert(r); err != nil {
				fmt.Printf("[%d] insert failed: %v\n", i, err)
				skipped++
				continue
			}
			inserted++
			fmt.Printf("[%d] + todo  uid=%06x  due=%s  %s\n",
				i, t.UniqueID, dueISO, snip(t.Description))
		} else {
			patch := map[string]any{
				"body":     t.Description,
				"metadata": json.RawMessage(meta),
			}
			if err := c.Update(existing, patch); err != nil {
				fmt.Printf("[%d] update failed: %v\n", i, err)
				skipped++
				continue
			}
			updated++
			fmt.Printf("[%d] ~ todo  uid=%06x\n", i, t.UniqueID)
		}
	}
	fmt.Printf("\ntodo push: +%d inserted, ~%d updated, %d skipped (of %d).\n",
		inserted, updated, skipped, len(todos))
}

func cmdTodoPull(args []string) {
	fs := flag.NewFlagSet("todo pull", flag.ExitOnError)
	out := fs.String("out", "ToDoDB.pdb", "output .pdb path")
	_ = fs.Parse(args)
	userID := mustEnv("PALM_USER_ID")
	c := newClient()

	rows, err := c.ListForUser(userID)
	if err != nil {
		die(err)
	}

	ai := tododb.DefaultAppInfo()

	var maxUID uint32
	for _, r := range rows {
		if r.DeviceID == nil {
			continue
		}
		if !strings.HasPrefix(*r.DeviceID, "todo:") {
			continue
		}
		var uid uint32
		fmt.Sscanf(*r.DeviceID, "todo:%x", &uid)
		if uid > maxUID {
			maxUID = uid
		}
	}

	type backfill struct {
		cloudID, devID string
	}
	var backfills []backfill

	todos := make([]tododb.Todo, 0)
	for _, r := range rows {
		if r.Type != "todo" {
			continue
		}
		if r.DeviceID != nil &&
			!strings.HasPrefix(*r.DeviceID, "todo:") {
			continue
		}
		md := decodeTodoMetadata(r)
		var uid uint32
		if r.DeviceID != nil {
			fmt.Sscanf(*r.DeviceID, "todo:%x", &uid)
		} else {
			maxUID++
			uid = maxUID
			devID := fmt.Sprintf("todo:%06x", uid)
			backfills = append(backfills, backfill{cloudID: r.ID, devID: devID})
		}
		var category uint8
		if md.CategoryName != "" {
			category = ai.EnsureCategory(md.CategoryName)
		}
		todos = append(todos, tododb.Todo{
			UniqueID:    uid,
			Category:    category,
			DueDate:     md.DueDate,
			Priority:    md.Priority,
			Completed:   md.Completed,
			Description: r.Body,
			Notes:       md.Notes,
		})
	}

	backfillFailures := 0
	for _, b := range backfills {
		if err := c.Update(b.cloudID, map[string]any{"device_id": b.devID}); err != nil {
			fmt.Printf("warn: backfill %s ← %s failed: %v\n", b.cloudID, b.devID, err)
			backfillFailures++
		}
	}

	db := tododb.NewTodoDB(ai)
	db.Records = tododb.EncodeTodos(todos)
	db.UniqueSeed = maxUID
	db.CreatedAt = time.Now().UTC()
	db.ModifiedAt = db.CreatedAt
	writePDBFile(*out, db)

	tail := ""
	if len(backfills) > 0 {
		tail = fmt.Sprintf(" (backfilled %d device_ids", len(backfills))
		if backfillFailures > 0 {
			tail += fmt.Sprintf(", %d failed", backfillFailures)
		}
		tail += ")"
	}
	fmt.Printf("todo pull: wrote %d todos to %s%s.\n", len(todos), *out, tail)
}

type todoMeta struct {
	CategoryName string
	Priority     uint8
	DueDate      *time.Time
	Completed    bool
	Notes        string
}

func decodeTodoMetadata(r cloud.Record) todoMeta {
	var out todoMeta
	if len(r.Metadata) == 0 {
		return out
	}
	var m map[string]any
	if err := json.Unmarshal(r.Metadata, &m); err != nil {
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

func cmdTodoSync(args []string) {
	if len(args) < 1 {
		fmt.Fprintln(os.Stderr, "usage: vellum-sync todo sync <file.pdb>")
		os.Exit(2)
	}
	file := args[0]
	fmt.Println("→ push")
	cmdTodoPush([]string{file})
	fmt.Println("→ pull (in-place)")
	cmdTodoPull([]string{"-out", file})
}
