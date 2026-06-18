package main

import (
	"bufio"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/spf13/cobra"
	"golang.org/x/term"

	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/auth"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/config"
	"github.com/palmvellum/palmvellum/packages/palm-engine/cloud"
	palmsync "github.com/palmvellum/palmvellum/packages/palm-engine/sync"
)

func authClient() (*auth.Client, *config.Config, error) {
	cfg, err := config.Load()
	if err != nil {
		return nil, nil, err
	}
	return auth.New(cfg.SupabaseURL, cfg.SupabasePublishableKey), cfg, nil
}

func prompt(label string) string {
	fmt.Print(label)
	sc := bufio.NewScanner(os.Stdin)
	sc.Scan()
	return strings.TrimSpace(sc.Text())
}

func promptSecret(label string) string {
	fmt.Print(label)
	if b, err := term.ReadPassword(int(os.Stdin.Fd())); err == nil {
		fmt.Println()
		return strings.TrimSpace(string(b))
	}
	// Non-TTY fallback (echoes).
	return prompt("")
}

func loginCmd() *cobra.Command {
	var otp bool
	cmd := &cobra.Command{
		Use:   "login",
		Short: "Log in to your PalmVellum platform account",
		RunE: func(cmd *cobra.Command, _ []string) error {
			ac, _, err := authClient()
			if err != nil {
				return err
			}
			ctx := cmd.Context()
			email := prompt("Email: ")
			if email == "" {
				return fmt.Errorf("email required")
			}

			var sess *auth.Session
			if otp {
				if err := ac.SendEmailOTP(ctx, email); err != nil {
					return err
				}
				fmt.Println("A 6-digit code was emailed to you.")
				code := prompt("Code: ")
				sess, err = ac.VerifyEmailOTP(ctx, email, code)
			} else {
				pw := promptSecret("Password: ")
				sess, err = ac.SignInPassword(ctx, email, pw)
			}
			if err != nil {
				return err
			}
			if err := auth.Save(sess); err != nil {
				return err
			}
			fmt.Printf("✅ logged in as %s\n", sess.Email)
			return nil
		},
	}
	cmd.Flags().BoolVar(&otp, "otp", false, "log in with an emailed code instead of a password")
	return cmd
}

func logoutCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "logout",
		Short: "Clear the saved login session",
		RunE: func(*cobra.Command, []string) error {
			if err := auth.Clear(); err != nil {
				return err
			}
			fmt.Println("✅ logged out")
			return nil
		},
	}
}

func whoamiCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "whoami",
		Short: "Show the logged-in user",
		RunE: func(cmd *cobra.Command, _ []string) error {
			ac, _, err := authClient()
			if err != nil {
				return err
			}
			s, err := ac.Current(cmd.Context())
			if err != nil {
				return err
			}
			fmt.Printf("%s (%s)\n", s.Email, s.UserID)
			return nil
		},
	}
}

// cardSyncCmd replaces the placeholder: it runs the full card round-trip
// (push → pull → clean) against a Sony MS Backup set directory, scoped
// to the logged-in user via their access token + RLS.
func cardSyncCmd() *cobra.Command {
	var wait time.Duration
	cmd := &cobra.Command{
		Use:   "sync <MSBackup-set-dir>",
		Short: "Sync a Palm card backup folder with the cloud",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			ac, cfg, err := authClient()
			if err != nil {
				return err
			}
			s, err := ac.Current(cmd.Context())
			if err != nil {
				return err
			}
			c := cloud.New(cfg.SupabaseURL, cfg.SupabasePublishableKey, s.AccessToken)

			res, err := palmsync.SyncCardLog(c, s.UserID, args[0], wait, func(line string) {
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
		"wait up to this long for AI to answer (AI) memos before writing back, e.g. 120s")
	return cmd
}

func printCardResult(r palmsync.CardResult) {
	if r.Memo != nil {
		fmt.Printf("memo:  +%d ~%d skip %d  → pulled %d\n",
			r.Memo.Inserted, r.Memo.Updated, r.Memo.Skipped, pulled(r.MemoPull))
	} else {
		fmt.Println("memo:  (no MemoDB.pdb on card)")
	}
	if r.Todo != nil {
		fmt.Printf("todo:  +%d ~%d skip %d  → pulled %d\n",
			r.Todo.Inserted, r.Todo.Updated, r.Todo.Skipped, pulled(r.TodoPull))
	} else {
		fmt.Println("todo:  (no ToDoDB.pdb on card)")
	}
	if len(r.CleanedJunk) > 0 {
		fmt.Printf("clean: removed %d macOS dropping(s): %s\n",
			len(r.CleanedJunk), strings.Join(r.CleanedJunk, ", "))
	}
	fmt.Println("✅ card sync complete — safe to eject and restore on the Palm")
}

func pulled(p *palmsync.PullResult) int {
	if p == nil {
		return 0
	}
	return p.Written
}
