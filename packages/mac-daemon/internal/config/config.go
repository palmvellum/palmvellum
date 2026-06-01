// Package config loads daemon configuration from the OS environment,
// expanding ~ in file paths and providing sensible macOS defaults.
package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Config is the resolved runtime configuration. All fields are
// populated from environment variables.
type Config struct {
	SupabaseURL       string
	SupabaseSecretKey string
	AnthropicAPIKey   string

	UserID   string
	DeviceID string

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
		SupabaseURL:       env("SUPABASE_URL", ""),
		SupabaseSecretKey: env("SUPABASE_SECRET_KEY", ""),
		AnthropicAPIKey:   env("ANTHROPIC_API_KEY", ""),
		UserID:            env("USER_ID", ""),
		DeviceID:          env("DEVICE_ID", defaultDeviceID()),
		SQLitePath:        expand(env("SQLITE_PATH", filepath.Join(home, ".local/share/palmvellum/cache.db")), home),
		HTTPAddr:          env("HTTP_ADDR", "127.0.0.1:7733"),
		LogLevel:          env("LOG_LEVEL", "info"),
		PalmSyncMode:      env("PALMSYNC_MODE", "stub"),
		PalmSyncSocket:    expand(env("PALMSYNC_SOCKET", filepath.Join(home, ".local/share/palmvellum/palm-sync.sock")), home),
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
