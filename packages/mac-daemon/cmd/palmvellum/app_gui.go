package main

import (
	"context"
	"time"

	"fyne.io/fyne/v2"
	fyneapp "fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/data/binding"
	"fyne.io/fyne/v2/widget"
	"github.com/spf13/cobra"

	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/auth"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/cardwatch"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/config"
	"github.com/palmvellum/palmvellum/packages/palm-engine/cloud"
	palmsync "github.com/palmvellum/palmvellum/packages/palm-engine/sync"
)

// appCmd runs the windowed desktop app: passwordless (email-code) login,
// settings, and a live sync log. This is what the packaged .app launches.
func appCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "app",
		Short: "Run the desktop window app (login, settings, sync)",
		RunE: func(cmd *cobra.Command, _ []string) error {
			newGUI(cmd.Context()).run()
			return nil
		},
	}
}

type gui struct {
	ctx context.Context
	ac  *auth.Client
	cfg *config.Config

	app fyne.App
	win fyne.Window

	// shared state
	autoSync bool
	waitAI   bool
	lastCard *cardwatch.Card

	// main-view bindings
	logText binding.String
	status  binding.String
	cardLbl binding.String
}

func newGUI(ctx context.Context) *gui {
	cfg, _ := config.Load()
	return &gui{
		ctx:      ctx,
		cfg:      cfg,
		ac:       auth.New(cfg.SupabaseURL, cfg.SupabasePublishableKey),
		autoSync: true,
		waitAI:   true,
		logText:  binding.NewString(),
		status:   binding.NewString(),
		cardLbl:  binding.NewString(),
	}
}

func (g *gui) run() {
	g.app = fyneapp.NewWithID("dev.tatliving.palmvellum")
	g.win = g.app.NewWindow("PalmVellum")
	g.win.Resize(fyne.NewSize(480, 460))

	// Stay logged in across restarts: if a session is saved in the
	// Keychain at all, go straight to the main view (it refreshes the
	// token lazily). Only a never-logged-in / explicitly-logged-out
	// state (ErrNoSession) shows the login screen.
	if _, err := auth.Load(); err == nil {
		g.showMain()
	} else {
		g.showLogin()
	}
	g.win.ShowAndRun()
}

// ─────────────────────────── login view ───────────────────────────

func (g *gui) showLogin() {
	email := widget.NewEntry()
	email.SetPlaceHolder("you@example.com")

	code := widget.NewEntry()
	code.SetPlaceHolder("6-digit code from email")
	code.Disable()

	msg := widget.NewLabel("")
	msg.Wrapping = fyne.TextWrapWord

	var verifyBtn *widget.Button

	sendBtn := widget.NewButton("Email me a code", func() {
		addr := email.Text
		if addr == "" {
			msg.SetText("Enter your email first.")
			return
		}
		msg.SetText("Sending…")
		go func() {
			err := g.ac.SendEmailOTP(g.ctx, addr)
			fyne.Do(func() {
				if err != nil {
					msg.SetText("Could not send: " + err.Error())
					return
				}
				code.Enable()
				verifyBtn.Enable()
				msg.SetText("Check your email and enter the 6-digit code.")
			})
		}()
	})

	verifyBtn = widget.NewButton("Log in", func() {
		msg.SetText("Verifying…")
		go func() {
			sess, err := g.ac.VerifyEmailOTP(g.ctx, email.Text, code.Text)
			if err == nil {
				err = auth.Save(sess)
			}
			fyne.Do(func() {
				if err != nil {
					msg.SetText("Login failed: " + err.Error())
					return
				}
				g.showMain()
			})
		}()
	})
	verifyBtn.Disable()

	form := container.NewVBox(
		widget.NewLabelWithStyle("Log in to PalmVellum", fyne.TextAlignCenter, fyne.TextStyle{Bold: true}),
		widget.NewLabel("Passwordless — we email you a one-time code."),
		widget.NewForm(
			widget.NewFormItem("Email", email),
			widget.NewFormItem("Code", code),
		),
		container.NewGridWithColumns(2, sendBtn, verifyBtn),
		msg,
	)
	g.win.SetContent(container.NewPadded(form))
}

// ─────────────────────────── main view ───────────────────────────

func (g *gui) showMain() {
	sess, err := g.ac.Current(g.ctx)
	if err != nil {
		g.showLogin()
		return
	}
	_ = g.status.Set("Logged in: " + sess.Email)
	_ = g.cardLbl.Set("No card detected")
	_ = g.logText.Set("Ready. Insert a Palm backup card to sync.\n")

	statusLbl := widget.NewLabelWithData(g.status)
	cardLbl := widget.NewLabelWithData(g.cardLbl)

	autoChk := widget.NewCheck("Sync automatically when a card is inserted", func(b bool) {
		g.autoSync = b
	})
	autoChk.SetChecked(g.autoSync)

	waitChk := widget.NewCheck("Wait for AI answers before writing back (slower)", func(b bool) {
		g.waitAI = b
	})
	waitChk.SetChecked(g.waitAI)

	syncBtn := widget.NewButton("Sync now", func() { go g.doSync() })

	logEntry := widget.NewMultiLineEntry()
	logEntry.Wrapping = fyne.TextWrapWord
	logEntry.Bind(g.logText)
	logEntry.Disable()
	logScroll := container.NewScroll(logEntry)
	logScroll.SetMinSize(fyne.NewSize(440, 200))

	logoutBtn := widget.NewButton("Log out", func() {
		_ = auth.Clear()
		g.showLogin()
	})

	top := container.NewVBox(
		statusLbl,
		cardLbl,
		autoChk,
		waitChk,
		container.NewGridWithColumns(2, syncBtn, logoutBtn),
		widget.NewSeparator(),
		widget.NewLabelWithStyle("Sync log", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}),
	)
	g.win.SetContent(container.NewPadded(container.NewBorder(top, nil, nil, nil, logScroll)))

	// Start the card watcher once.
	go (&cardwatch.Watcher{OnInsert: g.onCardInsert}).Run(g.ctx)
}

func (g *gui) onCardInsert(c cardwatch.Card) {
	g.lastCard = &c
	_ = g.cardLbl.Set("Card: " + c.Volume)
	g.appendLog("Card detected: " + c.SetDir)
	if g.autoSync {
		go g.doSync()
	}
}

func (g *gui) doSync() {
	card := g.lastCard
	if card == nil {
		if cards := cardwatch.FindCards(cardwatch.VolumesDir); len(cards) > 0 {
			card = &cards[0]
			g.lastCard = card
			_ = g.cardLbl.Set("Card: " + card.Volume)
		} else {
			g.appendLog("No Palm card detected.")
			return
		}
	}
	sess, err := g.ac.Current(g.ctx)
	if err != nil {
		g.appendLog("Please log in again.")
		fyne.Do(g.showLogin)
		return
	}
	cl := cloud.New(g.cfg.SupabaseURL, g.cfg.SupabasePublishableKey, sess.AccessToken)
	g.appendLog("──────────")
	var aiWait time.Duration
	if g.waitAI {
		aiWait = 120 * time.Second
	}
	_, err = palmsync.SyncCardLog(cl, sess.UserID, card.SetDir, aiWait, g.appendLog)
	if err != nil {
		g.appendLog("❌ " + err.Error() + " (card left mounted — fix and retry)")
		return
	}
	// Auto-eject on success so the user can pull the card and restore on
	// the Palm. On failure we leave it mounted for a retry.
	if err := cardwatch.Eject(card.Volume); err != nil {
		g.appendLog("⚠️ Could not eject automatically — eject it yourself: " + err.Error())
		return
	}
	g.lastCard = nil
	_ = g.cardLbl.Set("Card ejected — remove it and restore on the Palm")
	g.appendLog("⏏️ Ejected " + card.Volume + " — safe to remove")
}

// appendLog prepends a timestamped line so the newest entry sits at the
// top (newest → oldest). Safe to call from any goroutine.
func (g *gui) appendLog(line string) {
	stamp := time.Now().Format("15:04:05")
	cur, _ := g.logText.Get()
	_ = g.logText.Set(stamp + "  " + line + "\n" + cur)
}
