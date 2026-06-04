// vellum-sync — manual VellumDB.pdb ↔ Supabase round-trip.
//
// Subcommands:
//
//	push <file.pdb>
//	    Parse a VellumDB backup, upsert each record to the public.records
//	    table on Supabase. Idempotent: re-running with the same .pdb just
//	    refreshes the matched rows (matched by user_id + device_id, where
//	    device_id encodes the Palm record's 24-bit unique ID).
//
//	pull -out <file.pdb>
//	    Read every non-deleted records row of types aiquery/thought/todo
//	    and write a fresh VellumDB.pdb you can import back into the
//	    emulator so AI answers (and PWA-originated entries) show up in
//	    the on-device app.
//
// Env vars (required):
//
//	SUPABASE_URL              https://<project>.supabase.co
//	SUPABASE_SERVICE_ROLE_KEY service role JWT for unrestricted REST writes
//	PALM_USER_ID              auth.users.id of the test user
//
// This is the Phase 1+2 demo path documented in the project changelog —
// shares its PDB code with the future task #14 daemon that will do the
// same job over a real HotSync slave protocol.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/palmvellum/palmvellum/packages/sync-cli/internal/cloud"
	"github.com/palmvellum/palmvellum/packages/sync-cli/internal/pdb"
	"github.com/palmvellum/palmvellum/packages/sync-cli/internal/vellum"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "push":
		cmdPush(os.Args[2:])
	case "pull":
		cmdPull(os.Args[2:])
	case "inspect":
		cmdInspect(os.Args[2:])
	case "-h", "--help", "help":
		usage()
	default:
		fmt.Fprintf(os.Stderr, "unknown subcommand: %s\n\n", os.Args[1])
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, `vellum-sync — VellumDB.pdb ↔ Supabase

Commands:
  push <file.pdb>         Upload Palm records to cloud (idempotent)
  pull -out <file.pdb>    Download cloud records to a fresh VellumDB.pdb
  inspect <file.pdb>      Dump records without touching Supabase

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
	return cloud.New(mustEnv("SUPABASE_URL"), mustEnv("SUPABASE_SERVICE_ROLE_KEY"))
}

// ───────────────────────── inspect ─────────────────────────

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
	fmt.Printf("PDB     name=%q  type=%q creator=%q  records=%d  modnum=%d\n",
		db.Name, string(db.Type[:]), string(db.Creator[:]), len(db.Records), db.ModNumber)
	for i, r := range db.Records {
		rec, err := vellum.Decode(r.Data)
		if err != nil {
			fmt.Printf("  [%d] uid=%06x  <decode error: %v>  bytes=%d\n",
				i, r.UniqueID, err, len(r.Data))
			continue
		}
		excerpt := rec.Body
		if len(excerpt) > 60 {
			excerpt = excerpt[:60] + "…"
		}
		fmt.Printf("  [%d] uid=%06x  type=%d status=%d  body=%q  ans=%dB\n",
			i, r.UniqueID, rec.Type, rec.Status, excerpt, len(rec.Answer))
	}
}

// ─────────────────────────── push ──────────────────────────

func cmdPush(args []string) {
	if len(args) < 1 {
		fmt.Fprintln(os.Stderr, "usage: vellum-sync push <file.pdb>")
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

	pushed := 0
	updated := 0
	skipped := 0
	for i, pr := range db.Records {
		rec, err := vellum.Decode(pr.Data)
		if err != nil {
			fmt.Printf("[%d] decode failed: %v — skipped\n", i, err)
			skipped++
			continue
		}
		cloudType := rec.Type.Cloud()
		if cloudType == "" {
			fmt.Printf("[%d] unknown vellum type %d — skipped\n", i, rec.Type)
			skipped++
			continue
		}
		deviceID := fmt.Sprintf("palm:%06x", pr.UniqueID)

		existing, err := c.FindByDevice(userID, deviceID)
		if err != nil {
			die(err)
		}

		var aiStatus *string
		if rec.Type == vellum.TypeAI {
			s := "pending"
			if rec.Status == vellum.StatusAnswered {
				s = "done"
			}
			aiStatus = &s
		}

		ctime := time.Unix(int64(rec.CtimePalm)-pdb.PalmEpochOffset, 0).UTC()
		meta, _ := json.Marshal(map[string]any{
			"palm_status":        rec.Status,
			"palm_ctime_pe":      rec.CtimePalm,
			"palm_record_uid":    fmt.Sprintf("%06x", pr.UniqueID),
		})

		if existing == "" {
			r := cloud.Record{
				ID:        cloud.NewULID(),
				UserID:    userID,
				Type:      cloudType,
				Posture:   "open",
				Body:      rec.Body,
				Source:    "palm",
				DeviceID:  &deviceID,
				AIStatus:  aiStatus,
				Metadata:  meta,
				CreatedAt: &ctime,
			}
			if rec.Answer != "" {
				a := rec.Answer
				r.AIResponse = &a
				doneStatus := "done"
				r.AIStatus = &doneStatus
			}
			if err := c.Insert(r); err != nil {
				fmt.Printf("[%d] insert failed: %v\n", i, err)
				skipped++
				continue
			}
			pushed++
			fmt.Printf("[%d] + %s  uid=%06x  %s\n", i, cloudType, pr.UniqueID, snip(rec.Body))
		} else {
			patch := map[string]any{
				"body":     rec.Body,
				"metadata": json.RawMessage(meta),
			}
			if rec.Answer != "" {
				patch["ai_response"] = rec.Answer
				patch["ai_status"] = "done"
			}
			if err := c.Update(existing, patch); err != nil {
				fmt.Printf("[%d] update failed: %v\n", i, err)
				skipped++
				continue
			}
			updated++
			fmt.Printf("[%d] ~ %s  uid=%06x  id=%s\n", i, cloudType, pr.UniqueID, existing)
		}
	}
	fmt.Printf("\npush done: +%d inserted, ~%d updated, %d skipped (of %d).\n",
		pushed, updated, skipped, len(db.Records))
}

// ─────────────────────────── pull ──────────────────────────

func cmdPull(args []string) {
	fs := flag.NewFlagSet("pull", flag.ExitOnError)
	out := fs.String("out", "VellumDB.pdb", "output .pdb path")
	if err := fs.Parse(args); err != nil {
		os.Exit(2)
	}
	userID := mustEnv("PALM_USER_ID")
	c := newClient()

	rows, err := c.ListForUser(userID)
	if err != nil {
		die(err)
	}

	db := &pdb.DB{
		Name:       "VellumDB",
		Attributes: 0,
		Version:    1,
		CreatedAt:  time.Now().UTC(),
		ModifiedAt: time.Now().UTC(),
		Type:       pdb.FourCC("Data"),
		Creator:    pdb.FourCC("PvV1"),
		UniqueSeed: 0,
	}

	for _, r := range rows {
		vt := vellum.TypeFromCloud(r.Type)
		if vt == 0 {
			continue
		}
		st := vellum.StatusSynced
		if r.AIStatus != nil && *r.AIStatus == "done" && r.AIResponse != nil && *r.AIResponse != "" {
			st = vellum.StatusAnswered
		}
		ans := ""
		if r.AIResponse != nil {
			ans = *r.AIResponse
		}

		ctimePalm := uint32(0)
		if r.CreatedAt != nil {
			ctimePalm = uint32(r.CreatedAt.Unix() + pdb.PalmEpochOffset)
		}

		vr := vellum.Record{
			Version:   1,
			Type:      vt,
			Status:    st,
			CtimePalm: ctimePalm,
			Body:      r.Body,
			Answer:    ans,
		}

		// Reuse the Palm uniqueID if the row already carries one
		// (device_id like "palm:abcdef"); otherwise pdb.Write will
		// auto-assign from the seed.
		var uid uint32 = 0
		if r.DeviceID != nil {
			fmt.Sscanf(*r.DeviceID, "palm:%x", &uid)
		}

		db.Records = append(db.Records, pdb.Record{
			UniqueID: uid,
			Data:     vr.Encode(),
		})
	}

	bytes, err := db.Write()
	if err != nil {
		die(err)
	}
	if err := os.WriteFile(*out, bytes, 0o644); err != nil {
		die(err)
	}
	fmt.Printf("pull done: wrote %d records to %s (%d bytes).\n",
		len(db.Records), *out, len(bytes))
}

func snip(s string) string {
	if len(s) > 50 {
		return s[:50] + "…"
	}
	return s
}

func die(err error) {
	fmt.Fprintf(os.Stderr, "error: %v\n", err)
	os.Exit(1)
}
