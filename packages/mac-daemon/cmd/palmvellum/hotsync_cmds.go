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
