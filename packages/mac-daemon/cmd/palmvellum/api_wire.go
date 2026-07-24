package main

import (
	"context"
	"fmt"
	"strings"

	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/api"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/auth"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/cardwatch"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/config"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/hotsync"
	"github.com/palmvellum/palmvellum/packages/palm-engine/cloud"
	palmsync "github.com/palmvellum/palmvellum/packages/palm-engine/sync"
)

// wireAPI injects the HotSync/account capabilities the native macOS app
// consumes via the localhost HTTP API. Kept in the main package so the api
// package needn't import auth/hotsync/cardwatch.
func wireAPI(srv *api.Server, cfg *config.Config) {
	srv.Version = version

	srv.StatusFn = func(ctx context.Context) api.StatusInfo {
		info := api.StatusInfo{OK: true, Version: version, PalmPresent: hotsync.DevicePresent()}
		ac := auth.New(cfg.SupabaseURL, cfg.SupabasePublishableKey)
		if s, err := ac.Current(ctx); err == nil {
			info.User = s.Email
		}
		if cards := cardwatch.FindCards(cardwatch.VolumesDir); len(cards) > 0 {
			info.Card = cards[0].Volume
		}
		return info
	}

	srv.CardSyncFn = func(ctx context.Context) (string, error) {
		cards := cardwatch.FindCards(cardwatch.VolumesDir)
		if len(cards) == 0 {
			return "", fmt.Errorf("no backup card mounted")
		}
		ac := auth.New(cfg.SupabaseURL, cfg.SupabasePublishableKey)
		s, err := ac.Current(ctx)
		if err != nil {
			return "", fmt.Errorf("not logged in")
		}
		c := cloud.New(cfg.SupabaseURL, cfg.SupabasePublishableKey, s.AccessToken)
		res, err := palmsync.SyncCardLog(c, s.UserID, cards[0].SetDir, 0, palmsync.HKLocation(), nil)
		if err != nil {
			return "", err
		}
		return summarizeCard(res), nil
	}
}

func summarizeCard(r palmsync.CardResult) string {
	var parts []string
	if r.Memo != nil {
		parts = append(parts, fmt.Sprintf("memo +%d~%d", r.Memo.Inserted, r.Memo.Updated))
	}
	if r.Todo != nil {
		parts = append(parts, fmt.Sprintf("todo +%d~%d", r.Todo.Inserted, r.Todo.Updated))
	}
	if r.Datebook != nil {
		parts = append(parts, fmt.Sprintf("date +%d~%d", r.Datebook.Inserted, r.Datebook.Updated))
	}
	if r.Address != nil {
		parts = append(parts, fmt.Sprintf("addr +%d~%d", r.Address.Inserted, r.Address.Updated))
	}
	if len(parts) == 0 {
		return "card synced (no databases found on card)"
	}
	return "card synced: " + strings.Join(parts, ", ")
}
