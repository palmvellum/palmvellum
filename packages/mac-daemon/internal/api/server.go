// Package api hosts the local HTTP server consumed by the PWA, the
// menu-bar UI (future), and any other localhost client. It exposes:
//
//	GET  /health        — liveness probe
//	GET  /v1/records    — list records (mirror of Supabase)
//	POST /v1/sync       — trigger a sync cycle
//
// All endpoints listen on 127.0.0.1 only — never bound publicly.
package api

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"github.com/rs/zerolog/log"

	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/config"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/store"
)

// StatusInfo is the device/account snapshot returned by GET /v1/status,
// consumed by the native PalmVellum macOS app to show HotSync state.
type StatusInfo struct {
	OK          bool   `json:"ok"`
	Version     string `json:"version"`
	User        string `json:"user,omitempty"`   // signed-in email
	PalmPresent bool   `json:"palm_present"`     // a Palm is on USB
	Card        string `json:"card,omitempty"`   // mounted backup-card volume
}

// Server is the HTTP API.
type Server struct {
	cfg *config.Config
	db  *store.DB
	mux *http.ServeMux

	// Optional capabilities wired by the caller (main package) so the api
	// package stays free of auth/hotsync/cardwatch imports. Nil → the
	// endpoint reports unavailable.
	Version    string
	StatusFn   func(ctx context.Context) StatusInfo
	CardSyncFn func(ctx context.Context) (string, error)
}

// New builds a Server but does not start it.
func New(cfg *config.Config, db *store.DB) *Server {
	s := &Server{cfg: cfg, db: db, mux: http.NewServeMux()}
	s.routes()
	return s
}

func (s *Server) routes() {
	s.mux.HandleFunc("GET /health", s.handleHealth)
	s.mux.HandleFunc("GET /v1/records", s.handleListRecords)
	s.mux.HandleFunc("POST /v1/sync", s.handleSync)
	s.mux.HandleFunc("GET /v1/status", s.handleStatus)
	s.mux.HandleFunc("POST /v1/card-sync", s.handleCardSync)
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	if s.StatusFn == nil {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "version": s.Version})
		return
	}
	writeJSON(w, http.StatusOK, s.StatusFn(r.Context()))
}

func (s *Server) handleCardSync(w http.ResponseWriter, r *http.Request) {
	if s.CardSyncFn == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"ok": false, "message": "card sync unavailable"})
		return
	}
	msg, err := s.CardSyncFn(r.Context())
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]any{"ok": false, "message": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "message": msg})
}

// ListenAndServe runs the HTTP server until ctx is cancelled.
func (s *Server) ListenAndServe(ctx context.Context) error {
	srv := &http.Server{
		Addr:              s.cfg.HTTPAddr,
		Handler:           s.mux,
		ReadHeaderTimeout: 5 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() { errCh <- srv.ListenAndServe() }()

	log.Info().Str("addr", s.cfg.HTTPAddr).Msg("api ready")

	select {
	case <-ctx.Done():
		shutCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		return srv.Shutdown(shutCtx)
	case err := <-errCh:
		return err
	}
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":         true,
		"device_id":  s.cfg.DeviceID,
		"palm_sync":  s.cfg.PalmSyncMode,
		"time":       time.Now().UTC().Format(time.RFC3339),
	})
}

func (s *Server) handleListRecords(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.QueryContext(r.Context(),
		`SELECT id, type, posture, body, created_at, updated_at, ai_status, ai_response
		 FROM records WHERE deleted_at IS NULL
		 ORDER BY updated_at DESC LIMIT 200`,
	)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	defer rows.Close()

	out := []map[string]any{}
	for rows.Next() {
		var (
			id, rtype, posture, createdAt, updatedAt string
			body, aiStatus, aiResponse               *string
		)
		if err := rows.Scan(&id, &rtype, &posture, &body, &createdAt, &updatedAt, &aiStatus, &aiResponse); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		out = append(out, map[string]any{
			"id":          id,
			"type":        rtype,
			"posture":     posture,
			"body":        body,
			"created_at":  createdAt,
			"updated_at":  updatedAt,
			"ai_status":   aiStatus,
			"ai_response": aiResponse,
		})
	}

	writeJSON(w, http.StatusOK, map[string]any{"records": out, "count": len(out)})
}

func (s *Server) handleSync(w http.ResponseWriter, r *http.Request) {
	// TODO(issue #10, #14): trigger real sync via palm-sync sidecar.
	writeJSON(w, http.StatusAccepted, map[string]any{
		"queued": true,
		"note":   "sync stub — see GitHub issue #14",
	})
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
