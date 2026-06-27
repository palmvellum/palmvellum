package main

import (
	"fmt"
	"time"

	"github.com/spf13/cobra"

	"github.com/palmvellum/palmvellum/packages/palm-engine/cloud"
	palmsync "github.com/palmvellum/palmvellum/packages/palm-engine/sync"
)

// hotsyncMergeCmd is the cloud half of a USB HotSync, called by the Node
// sidecar mid-session: the sidecar has already pulled the device's .pdb
// files into <dir>, this merges them with the cloud in place (push → pull
// → rewrite), then the sidecar installs them back onto the Palm.
//
// It is the same engine the card path uses (SyncCardLog); the only
// difference is the transport that delivered the files. aiWait defaults to
// 0 so the live USB link is never held idle waiting on the AI worker —
// answers to newly-asked memos arrive on the next HotSync.
func hotsyncMergeCmd() *cobra.Command {
	var wait time.Duration
	cmd := &cobra.Command{
		Use:    "hotsync-merge <staging-dir>",
		Short:  "Merge HotSync-pulled .pdb files with the cloud (called by the sidecar)",
		Args:   cobra.ExactArgs(1),
		Hidden: true, // internal: invoked by the palm-sync sidecar, not by users
		RunE: func(cmd *cobra.Command, args []string) error {
			ac, cfg, err := authClient()
			if err != nil {
				return err
			}
			s, err := ac.Current(cmd.Context())
			if err != nil {
				return fmt.Errorf("not logged in: %w", err)
			}
			c := cloud.New(cfg.SupabaseURL, cfg.SupabasePublishableKey, s.AccessToken)

			res, err := palmsync.SyncCardLog(c, s.UserID, args[0], wait, time.Local, func(line string) {
				fmt.Println(line)
			})
			if err != nil {
				return err
			}
			printCardResult(res)
			return nil
		},
	}
	cmd.Flags().DurationVar(&wait, "wait", 0,
		"wait up to this long for AI to answer (AI) memos before writing back (default 0: answers arrive next sync)")
	return cmd
}

// datebookExportCmd regenerates a clean DatebookDB.pdb from the cloud
// (subscribed/imported calendar-feed events are excluded by DatebookPull),
// without touching the device. Recovery tool: when a device's Date Book has
// been inflated past what it can hold, drop the exported .pdb on the app's
// install zone to overwrite the bloated on-device database in one shot —
// bypassing the record-by-record pull that otherwise hangs.
func datebookExportCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "datebook-export <out.pdb>",
		Short: "Write a clean DatebookDB.pdb from the cloud (excludes calendar-feed events)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			ac, cfg, err := authClient()
			if err != nil {
				return err
			}
			s, err := ac.Current(cmd.Context())
			if err != nil {
				return fmt.Errorf("not logged in: %w", err)
			}
			c := cloud.New(cfg.SupabaseURL, cfg.SupabasePublishableKey, s.AccessToken)

			// nil appInfo → DatebookDB uses its default display prefs (the
			// device's own prefs are restored on the next normal HotSync).
			res, err := palmsync.DatebookPull(c, s.UserID, args[0], nil, time.Local)
			if err != nil {
				return err
			}
			fmt.Printf("wrote %d appointment(s) to %s\n", res.Written, args[0])
			return nil
		},
	}
}
