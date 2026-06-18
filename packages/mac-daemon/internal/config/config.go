// Package config loads daemon configuration from the OS environment,
// expanding ~ in file paths and providing sensible macOS defaults.
package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Baked-in public defaults so the shipped end-user app works with no
// configuration. Both are safe to distribute: the publishable key only
// permits RLS-scoped access once a user logs in.
const (
	DefaultSupabaseURL            = "https://jrkwncplngmznfzzqwee.supabase.co"
	DefaultSupabasePublishableKey = "sb_publishable_UoFQ7p6EPTm0cbqimURGPQ_J1HO_aR-"
)

// Config is the resolved runtime configuration. All fields are
// populated from environment variables.
type Config struct {
	SupabaseURL string
	// SupabasePublishableKey is the public apikey sent on every request.
	// The per-user bearer token comes from the saved login session.
	SupabasePublishableKey string
	SupabaseSecretKey      string

	// AI provider selection: "openai" (default) or "anthropic".
	AIProvider      string
	OpenAIAPIKey    string
	OpenAIModel     string
	AnthropicAPIKey string
	AnthropicModel  string

	UserID   string
	DeviceID string

	// HotsyncToken is the raw 64-char hex string issued by enroll_palm
	// and pasted by the user. The daemon trades it for a user_id at
	// startup via resolve_hotsync_token.
	HotsyncToken string

	SQLitePath string
	HTTPAddr   string
	LogLevel   string

	PalmSyncMode   string // "stub" or "real"
	PalmSyncSocket string
}

// Load reads configuration from the environment and returns it
// with paths expanded.
func Load() (*Config, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return nil, fmt.Errorf("user home: %w", err)
	}

	cfg := &Config{
		SupabaseURL:            env("SUPABASE_URL", DefaultSupabaseURL),
		SupabasePublishableKey: env("SUPABASE_PUBLISHABLE_KEY", DefaultSupabasePublishableKey),
		SupabaseSecretKey:      env("SUPABASE_SECRET_KEY", ""),

		AIProvider:      env("AI_PROVIDER", "openai"),
		OpenAIAPIKey:    env("OPENAI_API_KEY", ""),
		OpenAIModel:     env("OPENAI_MODEL", "gpt-4o-mini"),
		AnthropicAPIKey: env("ANTHROPIC_API_KEY", ""),
		AnthropicModel:  env("ANTHROPIC_MODEL", "claude-sonnet-4-5-20250929"),

		UserID:         env("USER_ID", ""),
		DeviceID:       env("DEVICE_ID", defaultDeviceID()),
		HotsyncToken:   env("PALMVELLUM_HOTSYNC_TOKEN", ""),
		SQLitePath:     expand(env("SQLITE_PATH", filepath.Join(home, ".local/share/palmvellum/cache.db")), home),
		HTTPAddr:       env("HTTP_ADDR", "127.0.0.1:7733"),
		LogLevel:       env("LOG_LEVEL", "info"),
		PalmSyncMode:   env("PALMSYNC_MODE", "stub"),
		PalmSyncSocket: expand(env("PALMSYNC_SOCKET", filepath.Join(home, ".local/share/palmvellum/palm-sync.sock")), home),
	}

	if err := os.MkdirAll(filepath.Dir(cfg.SQLitePath), 0o755); err != nil {
		return nil, fmt.Errorf("create sqlite dir: %w", err)
	}

	return cfg, nil
}

func env(key, def string) string {
	if v, ok := os.LookupEnv(key); ok {
		return v
	}
	return def
}

func expand(path, home string) string {
	if strings.HasPrefix(path, "~/") {
		return filepath.Join(home, path[2:])
	}
	return path
}

func defaultDeviceID() string {
	host, err := os.Hostname()
	if err != nil {
		return "mac-unknown"
	}
	return "mac-" + host
}
