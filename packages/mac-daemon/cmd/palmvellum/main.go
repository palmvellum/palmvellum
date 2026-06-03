// Command palmvellum is the PalmVellum Mac daemon and CLI.
//
// Subcommands:
//
//	palmvellum serve      Run the daemon (default for launchd)
//	palmvellum doctor     Verify permissions and dependencies
//	palmvellum sync       One-shot sync (manual trigger)
//	palmvellum version    Print version
//
// The daemon is intended to register itself with launchd via
// SMAppService on macOS 13+ so that it autostarts on login and
// survives sleep/wake cycles.
package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"github.com/rs/zerolog"
	"github.com/rs/zerolog/log"
	"github.com/spf13/cobra"

	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/ai"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/aiworker"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/api"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/config"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/store"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/supa"
)

// version is set at build time via -ldflags.
var version = "pre-alpha"

func main() {
	zerolog.TimeFieldFormat = zerolog.TimeFormatUnix
	log.Logger = log.Output(zerolog.ConsoleWriter{Out: os.Stderr})

	root := &cobra.Command{
		Use:   "palmvellum",
		Short: "PalmVellum Mac daemon",
		Long: `PalmVellum daemon — coordinates HotSync between Palm OS hardware
and the PalmVellum cloud, runs the AI Oracle worker, and exposes a
localhost HTTP API for the PWA / system tray UI.`,
		SilenceUsage: true,
	}

	root.AddCommand(
		serveCmd(),
		doctorCmd(),
		syncCmd(),
		versionCmd(),
	)

	if err := root.Execute(); err != nil {
		log.Fatal().Err(err).Msg("palmvellum failed")
	}
}

func serveCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "serve",
		Short: "Run the daemon (HTTP API + sync engine + AI worker)",
		RunE: func(cmd *cobra.Command, _ []string) error {
			cfg, err := config.Load()
			if err != nil {
				return fmt.Errorf("load config: %w", err)
			}
			setLogLevel(cfg.LogLevel)

			ctx, cancel := signal.NotifyContext(cmd.Context(), syscall.SIGINT, syscall.SIGTERM)
			defer cancel()

			db, err := store.Open(cfg.SQLitePath)
			if err != nil {
				return fmt.Errorf("open sqlite: %w", err)
			}
			defer db.Close()

			sb := supa.New(cfg.SupabaseURL, cfg.SupabaseSecretKey)
			claude := ai.New(cfg.AnthropicAPIKey)
			worker := aiworker.New(sb, claude, cfg.DeviceID)

			srv := api.New(cfg, db)

			log.Info().
				Str("version", version).
				Str("addr", cfg.HTTPAddr).
				Str("sqlite", cfg.SQLitePath).
				Bool("supabase_ready", sb.Enabled()).
				Bool("claude_ready", claude.Enabled()).
				Msg("palmvellum daemon starting")

			// Run the AI worker alongside the HTTP server. Either
			// returning ends the daemon; ctx cancellation propagates
			// to both.
			errCh := make(chan error, 2)
			go func() { errCh <- worker.Run(ctx) }()
			go func() { errCh <- srv.ListenAndServe(ctx) }()

			return <-errCh
		},
	}
}

func doctorCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "doctor",
		Short: "Diagnose environment and permissions",
		RunE: func(cmd *cobra.Command, _ []string) error {
			cfg, err := config.Load()
			if err != nil {
				fmt.Println("❌ config: ", err)
			} else {
				fmt.Println("✅ config loaded")
				fmt.Printf("   sqlite_path = %s\n", cfg.SQLitePath)
				fmt.Printf("   http_addr   = %s\n", cfg.HTTPAddr)
				fmt.Printf("   palm-sync   = %s\n", cfg.PalmSyncMode)
			}

			if _, err := os.Stat("/Applications/OrbStack.app"); err == nil {
				fmt.Println("✅ OrbStack present")
			} else {
				fmt.Println("⚠️  OrbStack not installed; the Palm toolchain image needs Docker")
			}

			// Supabase ping (lightweight — just counts records)
			sb := supa.New(cfg.SupabaseURL, cfg.SupabaseSecretKey)
			if sb.Enabled() {
				if err := sb.Ping(cmd.Context()); err != nil {
					fmt.Printf("⚠️  supabase ping failed: %v\n", err)
				} else {
					fmt.Println("✅ supabase reachable")
				}
			} else {
				fmt.Println("⚠️  supabase not configured (see .env)")
			}

			// Anthropic readiness — we don't ping (would burn a token);
			// just verify the key shape.
			claude := ai.New(cfg.AnthropicAPIKey)
			if claude.Enabled() {
				fmt.Println("✅ anthropic key configured")
			} else {
				fmt.Println("⚠️  anthropic not configured (ai worker will be idle)")
			}

			// TODO(v0.5): check Full Disk Access via IOKit
			// TODO(v0.5): check Removable Media access for serial cradle

			fmt.Println("✅ doctor finished")
			return nil
		},
	}
}

func syncCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "sync",
		Short: "Trigger a one-shot sync (placeholder until issue #10/14)",
		RunE: func(cmd *cobra.Command, _ []string) error {
			fmt.Println("sync not yet implemented — see GitHub issue #14")
			return nil
		},
	}
}

func versionCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "version",
		Short: "Print version",
		Run: func(cmd *cobra.Command, _ []string) {
			fmt.Printf("palmvellum %s\n", version)
		},
	}
}

func setLogLevel(level string) {
	switch level {
	case "debug":
		zerolog.SetGlobalLevel(zerolog.DebugLevel)
	case "info":
		zerolog.SetGlobalLevel(zerolog.InfoLevel)
	case "warn":
		zerolog.SetGlobalLevel(zerolog.WarnLevel)
	case "error":
		zerolog.SetGlobalLevel(zerolog.ErrorLevel)
	default:
		zerolog.SetGlobalLevel(zerolog.InfoLevel)
	}
}

// Ensure the context import stays referenced even if no direct call
// remains in this file after refactors.
var _ = context.Background
