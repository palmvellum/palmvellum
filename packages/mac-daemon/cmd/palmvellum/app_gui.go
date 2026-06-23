package main

import (
	"context"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"fyne.io/fyne/v2"
	fyneapp "fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/data/binding"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/theme"
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
	cardBusy bool // a card sync is in progress

	// main-view widgets/bindings
	logText   binding.String
	status    binding.String
	cardLbl   binding.String
	usbStatus binding.String // live USB-listener state shown on the USB tab

	started bool // guards one-time startup of background loops

	// USB listener state (guarded by usbMu)
	usbMu        sync.Mutex
	installQueue []string // pending .prc/.pdb to install on the next HotSync
	wantInstall  bool     // this run should install the queue, not sync
	connected    bool     // a HotSync session is mid-transfer
	usbBusy      bool     // a listen session is active
	queueBox     *fyne.Container
	listenBtn    *widget.Button
}

func newGUI(ctx context.Context) *gui {
	cfg, _ := config.Load()
	return &gui{
		ctx:       ctx,
		cfg:       cfg,
		ac:        auth.New(cfg.SupabaseURL, cfg.SupabasePublishableKey),
		autoSync:  true,
		waitAI:    true,
		logText:   binding.NewString(),
		status:    binding.NewString(),
		cardLbl:   binding.NewString(),
		usbStatus: binding.NewString(),
	}
}

func (g *gui) run() {
	g.app = fyneapp.NewWithID("dev.tatliving.palmvellum")
	g.app.Settings().SetTheme(ui.PalmTheme{}) // retro Palm organizer look
	g.win = g.app.NewWindow("PalmVellum on Mac")
	g.win.Resize(fyne.NewSize(880, 520)) // landscape: controls left, log right

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
	_ = g.usbStatus.Set("● Idle — click the button, then press HotSync on your Palm")
	_ = g.logText.Set("Ready. Press the HotSync button on your Palm to sync.\n")

	statusLbl := widget.NewLabelWithData(g.status)
	cardLbl := widget.NewLabelWithData(g.cardLbl)

	// Shared option (applies to both sync methods).
	waitChk := widget.NewCheck("Wait for AI answers before writing back (slower)", func(b bool) {
		g.waitAI = b
	})
	waitChk.SetChecked(g.waitAI)

	// ── Primary: cable HotSync ──
	// Two steps: click to arm the Mac's listener, THEN press HotSync on the
	// Palm. (Each press re-enumerates the device, so arming fresh per sync is
	// more reliable than a permanent listener.)
	usbStatusLbl := widget.NewLabelWithData(g.usbStatus)
	usbStatusLbl.TextStyle = fyne.TextStyle{Bold: true}
	usbStatusLbl.Wrapping = fyne.TextWrapWord
	g.listenBtn = widget.NewButton("Sync over USB (HotSync)", func() { go g.doListenOnce() })
	g.listenBtn.Importance = widget.HighImportance
	hotsyncHint := widget.NewLabel(
		"Two steps: ① click the button to start the Mac listening, then ② press " +
			"the HotSync button on your Palm.")
	hotsyncHint.Wrapping = fyne.TextWrapWord

	// Drag-and-drop install zone: a queue you build up (drop more, remove
	// any). Queued files install on the NEXT HotSync press instead of a sync.
	dropHint := widget.NewLabel("Drop .prc / .pdb files here to install them. Whatever is listed installs on your next HotSync press (instead of a sync).")
	dropHint.Wrapping = fyne.TextWrapWord
	g.queueBox = container.NewVBox()
	clearBtn := widget.NewButton("Clear list", func() { g.clearQueue() })
	dropZone := widget.NewCard("Install apps / databases", "",
		container.NewPadded(container.NewVBox(
			dropHint,
			g.queueBox,
			clearBtn,
		)))

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
	logScroll.SetMinSize(fyne.NewSize(190, 360))

	logoutBtn := widget.NewButton("Log out", func() {
		_ = auth.Clear()
		g.showLogin()
	})
	aboutBtn := widget.NewButton("About / Help", g.showAbout)

	cardHint := widget.NewLabel("Back up to a Memory Stick on the Palm, insert it in a reader here; the app syncs and ejects, then restore from card on the Palm.")
	cardHint.Wrapping = fyne.TextWrapWord

	// Two pages: cable HotSync (primary) and Memory Stick card sync.
	usbTab := container.NewVBox(g.listenBtn, usbStatusLbl, hotsyncHint, dropZone)
	cardTab := container.NewVBox(cardHint, cardLbl, autoChk, syncBtn)
	tabs := container.NewAppTabs(
		container.NewTabItem("USB HotSync", container.NewPadded(usbTab)),
		container.NewTabItem("Card Sync", container.NewPadded(cardTab)),
	)
	tabs.SetTabLocation(container.TabLocationTop)

	bold := fyne.TextStyle{Bold: true}
	// Landscape: controls on the left (~3/4), the sync log on the right (~1/4).
	mainCol := container.NewVBox(
		statusLbl,
		tabs,
		widget.NewSeparator(),
		waitChk,
		container.NewGridWithColumns(2, logoutBtn, aboutBtn),
	)
	logCol := container.NewBorder(
		widget.NewLabelWithStyle("Sync log", fyne.TextAlignLeading, bold),
		nil, nil, nil, logScroll,
	)
	split := container.NewHSplit(mainCol, logCol)
	split.SetOffset(0.75) // log column ≈ 1/4 of the width

	// Version stamp in the bottom-right corner.
	verLbl := widget.NewLabelWithStyle("PalmVellum on Mac · v"+version,
		fyne.TextAlignTrailing, fyne.TextStyle{Italic: true})
	footer := container.NewHBox(layout.NewSpacer(), verLbl)

	g.win.SetContent(container.NewPadded(
		container.NewBorder(nil, footer, nil, nil, split)))
	g.refreshInstallUI()

	// Window-wide file drop → add to the install queue.
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
			g.appendLog("Only .prc / .pdb / .pqa files can be installed on the Palm.")
			return
		}
		g.addToQueue(files)
	})

	// Start the card watcher once (showMain may run again on re-login).
	if !g.started {
		g.started = true
		go (&cardwatch.Watcher{OnInsert: g.onCardInsert}).Run(g.ctx)
	}
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
	if g.cardBusy {
		g.appendLog("A card sync is already running — wait for it to finish.")
		return
	}
	g.cardBusy = true
	defer func() { g.cardBusy = false }()

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

// doListenOnce arms the Mac's USB HotSync listener for one session: the user
// clicks the button (this), then presses HotSync on the Palm. Arming fresh per
// sync (rather than a permanent listener) is reliable because each HotSync
// press re-enumerates the device. If files are queued the session installs
// them; otherwise it runs a cloud sync. Times out if no press arrives.
func (g *gui) doListenOnce() {
	g.usbMu.Lock()
	if g.usbBusy {
		g.usbMu.Unlock()
		return
	}
	g.usbBusy = true
	install := g.wantInstall && len(g.installQueue) > 0
	files := append([]string(nil), g.installQueue...)
	g.connected = false
	g.usbMu.Unlock()

	fyne.Do(func() {
		if g.listenBtn != nil {
			g.listenBtn.Disable()
		}
	})
	defer func() {
		g.usbMu.Lock()
		g.usbBusy = false
		g.usbMu.Unlock()
		_ = g.usbStatus.Set("● Idle — click the button, then press HotSync on your Palm")
		g.refreshInstallUI() // restores button label + re-enables
	}()

	if _, err := g.ac.Current(g.ctx); err != nil {
		g.appendLog("Please log in again.")
		fyne.Do(g.showLogin)
		return
	}

	// Give the user time to reach the Palm and press HotSync, then give up so
	// the button frees again.
	sctx, cancel := context.WithTimeout(g.ctx, 120*time.Second)
	defer cancel()

	var opts hotsync.Options
	var stage string
	if install {
		_ = g.usbStatus.Set(fmt.Sprintf("● Listening — now press HotSync to INSTALL %d file(s)…", len(files)))
		opts = hotsync.Options{InstallFiles: files, Log: g.usbLog}
	} else {
		_ = g.usbStatus.Set("● Listening — now press the HotSync button on your Palm…")
		var err error
		stage, err = os.MkdirTemp("", "palmvellum-hotsync-")
		if err != nil {
			g.appendLog("❌ " + err.Error())
			return
		}
		backup := ""
		if home, e := os.UserHomeDir(); e == nil {
			backup = filepath.Join(home, ".local", "share", "palmvellum", "backups",
				"hotsync-"+time.Now().Format("20060102-150405"))
		}
		var aiWait time.Duration
		if g.waitAI {
			aiWait = 120 * time.Second
		}
		opts = hotsync.Options{StageDir: stage, BackupDir: backup, AIWait: aiWait, Log: g.usbLog}
	}

	err := hotsync.RunSync(sctx, opts)
	if stage != "" {
		_ = os.RemoveAll(stage)
	}

	g.usbMu.Lock()
	wasConnected := g.connected
	g.usbMu.Unlock()

	switch {
	case err != nil:
		if wasConnected {
			g.appendLog("❌ " + err.Error())
		} else {
			g.appendLog("⏱️ No HotSync detected — click the button again, then press HotSync on the Palm.")
		}
	case install:
		g.appendLog("⏏️ Install finished — safe to disconnect.")
		g.usbMu.Lock()
		g.installQueue = nil
		g.wantInstall = false
		g.usbMu.Unlock()
	default:
		g.appendLog("⏏️ HotSync finished — safe to disconnect.")
	}
}

// usbLog filters the sidecar's chatter: connection waits stay in the status
// line (not the log), a real connection opens a log entry, and everything
// else (pull/merge/push progress) is logged.
func (g *gui) usbLog(line string) {
	switch {
	case strings.Contains(line, "Connected!"):
		g.usbMu.Lock()
		g.connected = true
		g.usbMu.Unlock()
		_ = g.usbStatus.Set("● HotSync in progress…")
		g.appendLog("──────────")
		g.appendLog("HotSync started…")
		return
	case strings.TrimSpace(line) == "",
		strings.Contains(line, "Waiting for"),
		strings.Contains(line, "No supported devices"),
		strings.Contains(line, "Found device"),
		strings.Contains(line, "Closing device"),
		strings.Contains(line, "Device disconnected"),
		strings.Contains(line, "Disconnected"),
		strings.Contains(line, "Running function"),
		strings.HasPrefix(strings.TrimSpace(line), "=>"):
		return
	}
	g.appendLog(line)
}

// addToQueue appends dropped files (de-duped). Queued files install on the
// next listen session instead of a sync.
func (g *gui) addToQueue(files []string) {
	g.usbMu.Lock()
	for _, f := range files {
		dup := false
		for _, q := range g.installQueue {
			if q == f {
				dup = true
				break
			}
		}
		if !dup {
			g.installQueue = append(g.installQueue, f)
		}
	}
	g.wantInstall = len(g.installQueue) > 0
	g.usbMu.Unlock()
	g.refreshInstallUI()
}

// removeFromQueue drops one file (by path).
func (g *gui) removeFromQueue(path string) {
	g.usbMu.Lock()
	out := g.installQueue[:0]
	for _, q := range g.installQueue {
		if q != path {
			out = append(out, q)
		}
	}
	g.installQueue = out
	g.wantInstall = len(g.installQueue) > 0
	g.usbMu.Unlock()
	g.refreshInstallUI()
}

// clearQueue empties the queue.
func (g *gui) clearQueue() {
	g.usbMu.Lock()
	g.installQueue = nil
	g.wantInstall = false
	g.usbMu.Unlock()
	g.refreshInstallUI()
}

// refreshInstallUI rebuilds the queued-files list and updates the primary
// button (label = sync vs install N; disabled while a session is running).
func (g *gui) refreshInstallUI() {
	g.usbMu.Lock()
	files := append([]string(nil), g.installQueue...)
	busy := g.usbBusy
	g.usbMu.Unlock()
	fyne.Do(func() {
		g.queueBox.RemoveAll()
		for _, f := range files {
			path := f
			rm := widget.NewButtonWithIcon("", theme.DeleteIcon(), func() { g.removeFromQueue(path) })
			rm.Importance = widget.LowImportance
			g.queueBox.Add(container.NewBorder(nil, nil, nil, rm,
				widget.NewLabel("• "+filepath.Base(f))))
		}
		g.queueBox.Refresh()
		if g.listenBtn != nil {
			if n := len(files); n > 0 {
				g.listenBtn.SetText(fmt.Sprintf("Install %d file(s) via HotSync", n))
			} else {
				g.listenBtn.SetText("Sync over USB (HotSync)")
			}
			if busy {
				g.listenBtn.Disable()
			} else {
				g.listenBtn.Enable()
			}
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
	dialog.ShowCustom("About PalmVellum on Mac · v"+version, "Close", scroll, g.win)
}

// appendLog prepends a timestamped line so the newest entry sits at the
// top (newest → oldest). Safe to call from any goroutine.
func (g *gui) appendLog(line string) {
	stamp := time.Now().Format("15:04:05")
	cur, _ := g.logText.Get()
	_ = g.logText.Set(stamp + "  " + line + "\n" + cur)
}
