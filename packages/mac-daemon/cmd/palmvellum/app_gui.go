package main

import (
	"context"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	"fyne.io/fyne/v2"
	fyneapp "fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/data/binding"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/widget"
	"github.com/spf13/cobra"

	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/auth"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/cardwatch"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/config"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/hotsync"
	"github.com/palmvellum/palmvellum/packages/mac-daemon/internal/ui"
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
	busy     bool // a HotSync/install session is in progress

	// main-view widgets/bindings
	hotsyncBtn *widget.Button
	logText    binding.String
	status     binding.String
	cardLbl    binding.String
	dropLbl    binding.String
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
		dropLbl:  binding.NewString(),
	}
}

func (g *gui) run() {
	g.app = fyneapp.NewWithID("dev.tatliving.palmvellum")
	g.app.Settings().SetTheme(ui.PalmTheme{}) // retro Palm organizer look
	g.win = g.app.NewWindow("PalmVellum")
	g.win.Resize(fyne.NewSize(480, 480))

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
		widget.NewSeparator(),
		widget.NewButton("About / Help", g.showAbout),
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
	_ = g.dropLbl.Set("⬇  Drop a .prc or .pdb file here to install it on your Palm")
	_ = g.logText.Set("Ready. Connect your Palm by USB/cradle and press HotSync to sync.\n")

	statusLbl := widget.NewLabelWithData(g.status)
	cardLbl := widget.NewLabelWithData(g.cardLbl)

	// Shared option (applies to both sync methods).
	waitChk := widget.NewCheck("Wait for AI answers before writing back (slower)", func(b bool) {
		g.waitAI = b
	})
	waitChk.SetChecked(g.waitAI)

	// ── Primary: cable HotSync ──
	g.hotsyncBtn = widget.NewButton("① Sync over USB (HotSync)", func() { go g.doHotSync() })
	g.hotsyncBtn.Importance = widget.HighImportance
	hotsyncHint := widget.NewLabel(
		"Two steps: ① click the button above, then ② press the HotSync " +
			"button on the Palm. (Click first — the Mac must be listening " +
			"before the Palm dials in.)")
	hotsyncHint.Wrapping = fyne.TextWrapWord

	// Drag-and-drop install zone (window-wide drop; this card is the cue).
	dropLbl := widget.NewLabelWithData(g.dropLbl)
	dropLbl.Wrapping = fyne.TextWrapWord
	dropLbl.Alignment = fyne.TextAlignCenter
	dropZone := widget.NewCard("Install apps / databases", "",
		container.NewPadded(dropLbl))

	// ── Secondary: Memory Stick card sync ──
	autoChk := widget.NewCheck("Sync automatically when a card is inserted", func(b bool) {
		g.autoSync = b
	})
	autoChk.SetChecked(g.autoSync)
	syncBtn := widget.NewButton("Sync card now", func() { go g.doSync() })

	logEntry := widget.NewMultiLineEntry()
	logEntry.Wrapping = fyne.TextWrapWord
	logEntry.Bind(g.logText)
	logEntry.Disable()
	logScroll := container.NewScroll(logEntry)
	logScroll.SetMinSize(fyne.NewSize(440, 180))

	logoutBtn := widget.NewButton("Log out", func() {
		_ = auth.Clear()
		g.showLogin()
	})
	aboutBtn := widget.NewButton("About / Help", g.showAbout)

	bold := fyne.TextStyle{Bold: true}
	top := container.NewVBox(
		statusLbl,
		widget.NewSeparator(),
		widget.NewLabelWithStyle("Sync your Palm — USB HotSync", fyne.TextAlignLeading, bold),
		g.hotsyncBtn,
		hotsyncHint,
		waitChk,
		dropZone,
		widget.NewSeparator(),
		widget.NewLabelWithStyle("Memory Stick card sync (no cable)", fyne.TextAlignLeading, bold),
		cardLbl,
		autoChk,
		syncBtn,
		widget.NewSeparator(),
		container.NewGridWithColumns(2, logoutBtn, aboutBtn),
		widget.NewSeparator(),
		widget.NewLabelWithStyle("Sync log", fyne.TextAlignLeading, bold),
	)
	g.win.SetContent(container.NewPadded(container.NewBorder(top, nil, nil, nil, logScroll)))

	// Window-wide file drop → install onto the Palm.
	g.win.SetOnDropped(func(_ fyne.Position, uris []fyne.URI) {
		var files []string
		for _, u := range uris {
			if u == nil {
				continue
			}
			ext := strings.ToLower(filepath.Ext(u.Path()))
			if ext == ".prc" || ext == ".pdb" || ext == ".pqa" {
				files = append(files, u.Path())
			}
		}
		if len(files) == 0 {
			g.appendLog("Drop a .prc or .pdb file to install it on the Palm.")
			return
		}
		go g.doInstall(files)
	})

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
	if g.busy {
		g.appendLog("A sync is already running — wait for it to finish.")
		return
	}
	g.busy = true
	defer func() { g.busy = false }()

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
	_, err = palmsync.SyncCardLog(cl, sess.UserID, card.SetDir, aiWait, time.Local, g.appendLog)
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

// doHotSync runs a live USB HotSync: the Node sidecar pulls the Palm's
// databases, this app merges them with the cloud (in a subprocess), and the
// sidecar writes the merged result back — all in one button press on the
// Palm. Unlike the card path it needs no Memory Stick; the device stays
// connected over USB the whole time.
func (g *gui) doHotSync() {
	if _, err := g.ac.Current(g.ctx); err != nil {
		g.appendLog("Please log in again.")
		fyne.Do(g.showLogin)
		return
	}
	if !g.beginSession() {
		return
	}
	defer g.endSession()

	stage, err := os.MkdirTemp("", "palmvellum-hotsync-")
	if err != nil {
		g.appendLog("❌ " + err.Error())
		return
	}
	defer os.RemoveAll(stage)

	g.appendLog("──────────")
	if !hotsync.DevicePresent() {
		g.appendLog("No Palm seen on USB yet — connect the cradle/cable.")
	}
	g.appendLog("👉 Step ②: press the HotSync button on your Palm now…")

	// Honour the same "wait for AI" toggle as the card path. For HotSync
	// this holds the live USB link open during the wait, so only enable it
	// if the user opted in.
	var aiWait time.Duration
	if g.waitAI {
		aiWait = 120 * time.Second
	}

	// Keep a clean restore point of the device's databases (as pulled,
	// before any write-back) under the app's data dir.
	backup := ""
	if home, err := os.UserHomeDir(); err == nil {
		backup = filepath.Join(home, ".local", "share", "palmvellum", "backups",
			"hotsync-"+time.Now().Format("20060102-150405"))
	}

	if backup != "" {
		g.appendLog("Restore point will be saved to: " + backup)
	}
	err = hotsync.RunSync(g.ctx, hotsync.Options{
		StageDir:  stage,
		BackupDir: backup,
		AIWait:    aiWait,
		Log:       g.appendLog,
	})
	if err != nil {
		g.appendLog("❌ " + err.Error())
		return
	}
	g.appendLog("⏏️ HotSync finished — safe to disconnect.")
}

// doInstall pushes dropped .prc/.pdb files onto the Palm in one HotSync
// session (no cloud, no pull). Triggered by the drag-and-drop zone.
func (g *gui) doInstall(files []string) {
	if !g.beginSession() {
		return
	}
	defer g.endSession()

	names := make([]string, 0, len(files))
	for _, f := range files {
		names = append(names, filepath.Base(f))
	}
	g.appendLog("──────────")
	g.appendLog("Install to Palm: " + strings.Join(names, ", "))
	if !hotsync.DevicePresent() {
		g.appendLog("No Palm seen on USB yet — connect the cradle/cable.")
	}
	g.appendLog("👉 Step ②: press the HotSync button on your Palm now…")

	if err := hotsync.RunSync(g.ctx, hotsync.Options{InstallFiles: files, Log: g.appendLog}); err != nil {
		g.appendLog("❌ " + err.Error())
		return
	}
	g.appendLog("⏏️ Install finished — safe to disconnect.")
}

// beginSession guards against overlapping HotSync/install runs and flips the
// primary button into its "now press HotSync" reminder state. Returns false
// if a session is already running.
func (g *gui) beginSession() bool {
	if g.busy {
		g.appendLog("A sync is already running — wait for it to finish.")
		return false
	}
	g.busy = true
	fyne.Do(func() {
		if g.hotsyncBtn != nil {
			g.hotsyncBtn.SetText("② Now press HotSync on the Palm…")
			g.hotsyncBtn.Disable()
		}
	})
	return true
}

func (g *gui) endSession() {
	g.busy = false
	fyne.Do(func() {
		if g.hotsyncBtn != nil {
			g.hotsyncBtn.SetText("① Sync over USB (HotSync)")
			g.hotsyncBtn.Enable()
		}
	})
}

// ─────────────────────────── about / help ───────────────────────────

func mustURL(s string) *url.URL { u, _ := url.Parse(s); return u }

// showAbout opens the project intro, links, usage, compatibility and the
// at-your-own-risk disclaimer.
func (g *gui) showAbout() {
	intro := widget.NewLabel(
		"PalmVellum — slow tools for fast lives.\n\n" +
			"Cloud sync + AI for the native apps on a 1996–2003 Palm. " +
			"Connect by USB and press HotSync, and the app syncs Memo, To Do, " +
			"Date Book, Address and Mail with the cloud — in one button press.")
	intro.Wrapping = fyne.TextWrapWord

	how := widget.NewLabel(
		"How to use — USB HotSync (recommended):\n" +
			"1. Connect the Palm to your Mac by USB cable or cradle.\n" +
			"2. Log in here, then click \"Sync over USB (HotSync)\".\n" +
			"3. Press the HotSync button on the Palm. Done — both sides merge.\n\n" +
			"No cable? Memory Stick card sync:\n" +
			"1. On the Palm, use the built-in MS Backup to back up to the card.\n" +
			"2. Put the Memory Stick in a reader; the app syncs it, then ejects.\n" +
			"3. Put the card back and restore from card in MS Backup.")
	how.Wrapping = fyne.TextWrapWord

	compat := widget.NewLabel(
		"Compatibility:\n" +
			"• Sony Clié over USB HotSync — tested.\n" +
			"• Sony Clié + Memory Stick + built-in MS Backup — tested.\n" +
			"• Other USB Palm models / SD-card Palms: not yet tested.\n" +
			"• On write-back the Clié may do a brief, harmless soft reset.")
	compat.Wrapping = fyne.TextWrapWord

	disclaimer := widget.NewLabelWithStyle(
		"⚠️ No warranty of any kind. Use entirely at your own risk. "+
			"PalmVellum keeps a restore point before each write-back.",
		fyne.TextAlignLeading, fyne.TextStyle{Bold: true})
	disclaimer.Wrapping = fyne.TextWrapWord

	body := container.NewVBox(
		intro,
		widget.NewHyperlink("Website — tatliving.dev/palmvellum", mustURL("https://tatliving.dev/palmvellum")),
		widget.NewHyperlink("Source — github.com/palmvellum/palmvellum", mustURL("https://github.com/palmvellum/palmvellum")),
		widget.NewSeparator(),
		how,
		widget.NewSeparator(),
		compat,
		widget.NewSeparator(),
		disclaimer,
	)
	scroll := container.NewScroll(body)
	scroll.SetMinSize(fyne.NewSize(440, 420))
	dialog.ShowCustom("About PalmVellum", "Close", scroll, g.win)
}

// appendLog prepends a timestamped line so the newest entry sits at the
// top (newest → oldest). Safe to call from any goroutine.
func (g *gui) appendLog(line string) {
	stamp := time.Now().Format("15:04:05")
	cur, _ := g.logText.Get()
	_ = g.logText.Set(stamp + "  " + line + "\n" + cur)
}
