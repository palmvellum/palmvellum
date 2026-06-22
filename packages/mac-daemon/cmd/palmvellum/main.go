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
	"strings"
	"syscall"

	"github.com/rs/zerolog"
	"github.com/rs/zerolog/log"
	"github.com/spf13/cobra"

	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/ai"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/aiworker"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/api"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/config"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/hotsync"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/store"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/supa"
)

// version is the single source of truth for the app version, shown in the
// app's corner and used for the .app bundle / .dmg. Bump it on every change.
var version = "1.0.0"

func main() {
	zerolog.TimeFieldFormat = zerolog.TimeFormatUnix
	log.Logger = log.Output(zerolog.ConsoleWriter{Out: os.Stderr})

	// When double-clicked as PalmVellum.app, macOS launches the binary
	// from inside the bundle with no arguments. In that case default to
	// the menu-bar UI; from a terminal (any other path) keep full CLI
	// semantics so `palmvellum login`, `version`, etc. still work.
	if len(os.Args) == 1 && strings.Contains(os.Args[0], "/Contents/MacOS/") {
		os.Args = append(os.Args, "app")
	}

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
		loginCmd(),
		logoutCmd(),
		whoamiCmd(),
		cardSyncCmd(),
		hotsyncMergeCmd(),
		appCmd(),
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

			// Resolve the linked user from PALMVELLUM_HOTSYNC_TOKEN
			// if set. v0.2 uses this purely as a label; v0.3+ scopes
			// every HotSync write to this user_id.
			boundUserID := ""
			if cfg.HotsyncToken != "" && sb.Enabled() {
				u, err := sb.ResolveHotsyncToken(ctx, cfg.HotsyncToken)
				if err != nil {
					log.Warn().Err(err).Msg("resolve hotsync token failed")
				} else if u == "" {
					log.Warn().Msg("PALMVELLUM_HOTSYNC_TOKEN does not match any enrolled Palm")
				} else {
					boundUserID = u
					log.Info().Str("user_id", u).Msg("hotsync token resolved — daemon bound to user")
				}
			}

			// AI worker now uses per-user BYOK keys via Supabase Vault.
			// The daemon-level OPENAI_API_KEY / ANTHROPIC_API_KEY are
			// the fallback for users on api_mode='platform'.
			platform := aiworker.PlatformKeys{
				OpenAIAPIKey:    cfg.OpenAIAPIKey,
				OpenAIModel:     cfg.OpenAIModel,
				AnthropicAPIKey: cfg.AnthropicAPIKey,
				AnthropicModel:  cfg.AnthropicModel,
			}
			worker := aiworker.New(sb, platform, cfg.DeviceID)

			srv := api.New(cfg, db)

			log.Info().
				Str("version", version).
				Str("addr", cfg.HTTPAddr).
				Str("sqlite", cfg.SQLitePath).
				Bool("supabase_ready", sb.Enabled()).
				Bool("platform_openai_ready", cfg.OpenAIAPIKey != "" && cfg.OpenAIAPIKey != "sk-REPLACE_ME").
				Bool("hotsync_bound", boundUserID != "").
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

			// AI provider readiness — verify key shape only; we don't
			// ping (would burn a token).
			provider := ai.New(ai.Config{
				Provider:        cfg.AIProvider,
				OpenAIAPIKey:    cfg.OpenAIAPIKey,
				OpenAIModel:     cfg.OpenAIModel,
				AnthropicAPIKey: cfg.AnthropicAPIKey,
				AnthropicModel:  cfg.AnthropicModel,
			})
			if provider.Enabled() {
				fmt.Printf("✅ ai provider configured: %s\n", provider.ProviderName())
			} else {
				fmt.Printf("⚠️  ai provider %s not configured (worker will be idle)\n", provider.ProviderName())
			}

			// HotSync sidecar readiness (Node runtime + palm-sync).
			if rt, err := hotsync.Resolve(); err != nil {
				fmt.Printf("⚠️  hotsync sidecar: %v\n", err)
			} else {
				fmt.Printf("✅ hotsync sidecar ready (node=%s)\n", rt.Node)
			}
			if hotsync.DevicePresent() {
				fmt.Println("✅ Palm detected on USB")
			} else {
				fmt.Println("ℹ️  no Palm on USB (normal until you press HotSync)")
			}

			// TODO(v0.5): check Full Disk Access via IOKit
			// TODO(v0.5): check Removable Media access for serial cradle

			fmt.Println("✅ doctor finished")
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
