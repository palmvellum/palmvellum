// Package store is the daemon's local cache, mirroring the Supabase
// schema. Uses modernc.org/sqlite (pure Go, no CGO) so the binary
// cross-compiles trivially and does not depend on Xcode Command Line
// Tools.
package store

import (
	"context"
	"database/sql"
	"embed"
	"fmt"

	_ "modernc.org/sqlite"
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

// DB is a thin wrapper around *sql.DB that owns the schema lifecycle.
type DB struct {
	*sql.DB
}

// Open opens (or creates) the SQLite database at path, runs any
// pending migrations, and returns the handle.
func Open(path string) (*DB, error) {
	conn, err := sql.Open("sqlite", path+"?_pragma=journal_mode(wal)&_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)")
	if err != nil {
		return nil, fmt.Errorf("open: %w", err)
	}
	if err := conn.Ping(); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("ping: %w", err)
	}

	db := &DB{DB: conn}
	if err := db.migrate(); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return db, nil
}

func (db *DB) migrate() error {
	ctx := context.Background()
	if _, err := db.ExecContext(ctx, `
		CREATE TABLE IF NOT EXISTS schema_migrations (
			version TEXT PRIMARY KEY,
			applied_at INTEGER NOT NULL DEFAULT (unixepoch())
		)
	`); err != nil {
		return err
	}

	entries, err := migrationsFS.ReadDir("migrations")
	if err != nil {
		return fmt.Errorf("read migrations: %w", err)
	}

	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		version := e.Name()

		var applied int
		if err := db.QueryRowContext(ctx,
			`SELECT COUNT(*) FROM schema_migrations WHERE version = ?`, version,
		).Scan(&applied); err != nil {
			return err
		}
		if applied > 0 {
			continue
		}

		body, err := migrationsFS.ReadFile("migrations/" + version)
		if err != nil {
			return fmt.Errorf("read %s: %w", version, err)
		}

		tx, err := db.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, string(body)); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("apply %s: %w", version, err)
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO schema_migrations (version) VALUES (?)`, version,
		); err != nil {
			_ = tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}

	return nil
}
