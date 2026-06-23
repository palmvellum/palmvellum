# Cosmo Communicator — UI / screen spec (design reference)

> Reference sheet for the **`cosmo` product flavor** of the native Android app
> (`packages/android-native`). Any future Cosmo UI work should target the real
> hardware numbers below — the Cosmo is a landscape clamshell, not a phone, so
> layouts must be designed for a **wide, short** main display.
>
> Everything Cosmo-specific in code is gated on `BuildConfig.COSMO` (set by the
> `cosmo` flavor in `app/build.gradle.kts`). The `standard` flavor must stay
> visually identical to the classic portrait Palm UI — never let a Cosmo tweak
> leak into `standard`.

## Hardware (Planet Computers Cosmo Communicator, 2019)

| Spec | Value |
|---|---|
| Main display (open) | **5.99″** AM-OLED touchscreen |
| Main resolution | **2160 × 1080 px** (FHD+, **18:9 / 2:1**) |
| Main pixel density | **~403 ppi** |
| Orientation in use | **Landscape** (clamshell open, keyboard along bottom edge) |
| Cover display (closed) | **2.0″** color AMOLED, **570 × 240 px** (~309 ppi) |
| Input | Backlit physical **QWERTY keyboard** + touchscreen + fingerprint |
| SoC | MediaTek **Helio P70** (MT6771), octa-core |
| RAM / storage | **6 GB** / 128 GB + microSD |
| OS | **Android 9 (Pie)** (also multi-boot Linux / Sailfish) |
| Battery | 4220 mAh |

Sources: search results from Planet Computers store, PhoneDB, Liliputing,
CNX-Software, GSMchoice (Jan 2019 launch specs).

## Derived layout numbers (the ones that matter for Compose)

Android works in **dp**, not px. `dp = px / (densityDpi / 160)`.

The hardware is ~403 ppi. Android reports a rounded **`densityDpi`** — for a
panel this sharp the most likely value is **400** (`DENSITY_400`, scale **×2.5**).
**This must be confirmed on the real device** (it is the one number we can't
verify without hardware — see "Verify on device" below). The table assumes
**400 dpi / ×2.5**; if the device actually reports 420 dpi (×2.625), shrink the
dp figures by ~5%.

| Quantity | px | dp @ ×2.5 |
|---|---|---|
| Main width (landscape) | 2160 | **~864 dp** |
| Main height (landscape) | 1080 | **~432 dp** |
| Cover width | 570 | ~285 dp @ ×2.0 |
| Cover height | 240 | ~120 dp @ ×2.0 |

**Key takeaway: the usable canvas is ~864 × 432 dp — very wide, quite short.**
After the 44 dp Palm title bar + status-bar inset, usable content height is only
**~360–390 dp**. Design for height pressure: prefer horizontal splits, keep
vertical chrome thin, make tall content scroll.

The cover display (285×120 dp) is tiny; we do **not** design app screens for it.
Treat it as out of scope unless a future task explicitly targets it.

## Layout rules for the Cosmo flavor

1. **Landscape is locked.** `MainActivity` forces
   `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` when `BuildConfig.COSMO`. Never assume
   portrait. Test every screen at ~864×432 dp.
2. **Use the width, respect the height.** Wide screen → put things side by side
   (rails, two-pane master/detail). Short screen → avoid stacking tall fixed
   chrome; let lists/forms scroll inside their pane.
3. **Width cap.** `PalmScaffold` centres content and caps it at **`760.dp`**
   (`widthIn(max = 760.dp)`). At 864 dp wide this leaves ~52 dp side margins.
   This keeps single-column forms from stretching, but for genuinely wide
   layouts (e.g. the Date Book two-pane) it slightly squeezes them. If a future
   wide layout needs the full width, raise/bypass this cap **for that screen
   only** — do not change the global cap blindly.
4. **Left icon rail, not bottom bar.** On Cosmo the four classic hardware
   buttons (Date Book / Address / To Do / Memo) render as a **60 dp left-edge
   vertical rail, icons only, no labels** (`PalmButtonRail` in `PalmScaffold`).
   The rail starts with a **home button (`⌂`)** back to the launcher (separated
   from the four apps by a hairline), so there's always an explicit way home —
   the bottom docked row (standard-only) has no home button, and standard relies
   on the system back button instead. The bottom docked row is `standard`-only.
   Side rails suit landscape and free up the scarce vertical space.
4a. **Inline filter/search in the title bar.** Cosmo's display is short, so a
   screen's filter strip / search box moves **up into the empty title-bar space**
   via `PalmScaffold(titleCenter = …)` instead of eating a content row. Use the
   dark-bar variants `TitleCategory` (chips: To Do open/done/all, Date Book
   agenda/week/month) and `TitleSearch` (Address). Gate with `BuildConfig.COSMO`:
   on standard, pass `titleCenter = null` and keep the in-body `PalmCategoryStrip`
   / `PalmField` so the portrait look is unchanged.
5. **Two-pane where it helps.** This is the core Cosmo idiom.
   - List+editor screens (**Address / To Do / Memo**) use
     `MasterDetailScaffold`: list on the **left**, the editor (or a placeholder)
     on the **right**, instead of the portrait full-screen swap. Add new
     list+editor screens through `MasterDetailScaffold` too.
   - Date Book defaults to **month** on Cosmo with **calendar right /
     selected-day schedule left** (`MonthViewTwoPane`).
   - Use `PalmScaffold(wide = true)` for any full-width two-pane layout (it
     bypasses the 760 dp cap). Editors rendered in a right pane must be passed
     `embedded = true` so `EditorScaffold` drops its status-bar inset.
   - Future candidates: **Note Pad** (sketch gallery left / preview right).
     Only split when it still reads at ~432 dp tall.
6. **Glyphs only for nav.** Hard constraint #1 (see project memory): no
   emoji/icons in UI *content*. The launcher tiles + hardware-button glyphs
   (◫ ✦ ☑ ▤ ✎ ✷) are the pre-approved structural-navigation exception. Keep it
   that way on Cosmo too.
7. **Insets.** In landscape the system nav bar sits on a side. Apply
   `WindowInsets.safeDrawing` (Start/Bottom) to edge rails so they clear it
   (already done in `PalmButtonRail`).
8. **Physical keyboard exists** but we currently add no hardware shortcuts
   (user deferred this). If revisited: Esc = back, letter keys = jump to app,
   arrows = navigate calendar/lists.

## Current Cosmo-specific code (as of 2026-06-17)

- `app/build.gradle.kts` — `device` flavor dimension; `cosmo` adds
  `applicationIdSuffix ".cosmo"`, `versionNameSuffix "-cosmo"`,
  `app_name = "Palm Organizers (Cosmo)"`, `buildConfigField COSMO=true`.
- `MainActivity.kt` — landscape lock when `COSMO`.
- `ui/PalmScaffold.kt` — `COSMO` → left `PalmButtonRail` (home `⌂` + four apps,
  no bottom bar) + 760 dp width cap (skipped when `wide = true`); `standard` →
  docked `PalmButtonRow`. `PalmScaffold`/`MasterDetailScaffold` take an optional
  `titleCenter` slot rendered between the title and the action in the title bar.
  Also holds `MasterDetailScaffold<T>` (list left / detail right on Cosmo,
  full-screen swap on standard) and the shared `RailButton`.
- `ui/components/PalmComponents.kt` — `TitleCategory` / `TitleSearch`: compact
  dark-bar filter chips / search field for the Cosmo `titleCenter` slot.
- `ui/components/PalmComponents.kt` — `EditorScaffold(embedded = …)`: omit the
  status-bar inset when shown in a Cosmo detail pane.
- `ui/screens/AddressScreen.kt`, `TodoScreen.kt`, `MemoScreen.kt` — use
  `MasterDetailScaffold`; each editor takes an `embedded` flag.
- `ui/launcher/LauncherScreen.kt` — `COSMO` → `GridCells.Adaptive(160.dp)`
  (~4 cols) + 140 dp tiles; `standard` → `GridCells.Fixed(2)` + 180 dp tiles.
- `ui/screens/DateBookScreen.kt` — `COSMO` → default `month` + `MonthViewTwoPane`
  (calendar right / schedule left); `standard` → default `agenda` + stacked
  `MonthView`. Shared pieces: `MonthCalendarGrid`, `MonthDayDetail`,
  `MonthViewArgs`. Cosmo-specific:
  - **month** — `MonthCalendarGrid(fillHeight = true)`: the six week rows share
    the pane height (weighted), cells drop their square `aspectRatio` and
    `fillMaxHeight` so the grid never overflows the short pane.
  - **agenda** — `AgendaGridCosmo`: the next 7 days (`DT.nextDays`) as a
    `LazyVerticalGrid` (left-to-right, top-to-bottom), each a `DayBlock` card with
    a dark-red header (`PalmDarkRed`, brighter `PalmRed` when today).
  - Mode switcher is **agenda + month only** (week view was removed). Editors in
    the master/detail screens are wrapped in `key(record.id)` so tapping another
    row swaps the editor directly instead of needing Cancel first.
  - **plan with AI** — the input rides in `titleCenter` beside the mode chips
    (`TitleSearch(onSubmit=…)`); parsed `DraftCard`s surface in a capped strip
    above the view so results show in any mode.
  - Date parsing: `DT.parse` uses `OffsetDateTime` so server timestamptz
    (`…+00:00`) no longer fails → falls back to today, which used to pile every
    event onto the current day.

## Verify on device (no hardware available at author time)

Run these on a real Cosmo (or a 2160×1080 landscape emulator) and record the
results back into this file:

```sh
adb shell wm size          # expect 2160x1080 (or 1080x2160 reported portrait)
adb shell wm density       # the real densityDpi — confirm 400 vs 420 vs other
adb shell dumpsys display | grep -i 'density\|mBaseDisplayInfo'
```

If `wm density` ≠ 400, update the dp table above and re-check that the 760 dp
cap and the rail/tile sizes still look right.

## Cosmo-only features (added 2026-06-23)

### USB HotSync (`Routes.HOTSYNC`, launcher tile ⇄, Cosmo-only)
Connect a docked Palm/CLIE to the Cosmo's USB-C port (via USB-OTG) and sync its
Memo Pad + To Do databases with the cloud — no desktop. The HotSync protocol
stack is a from-scratch Kotlin port living in `data/hotsync/`:

- `PalmUsbTransport` — Android USB host: find by vendor id, claim the bulk
  IN/OUT endpoint pair, best-effort vendor init. `UsbPermission` bridges the
  runtime permission to a suspend call. Manifest declares `usb.host`
  (`required=false`) + a `USB_DEVICE_ATTACHED` filter (`res/xml/usb_device_filter.xml`).
- `Slp` / `Padp` / `Cmp` / `Dlp` — the SLP→PADP→CMP→DLP stack over the byte pipe.
- `HotSyncSession` — handshake + DB read/write via DLP.
- `HotSyncConduit` + `PalmCloud` — the cloud merge: a faithful Kotlin port of
  `packages/palm-engine/sync/sync.go` (device_id `memo:<hex>`/`todo:<hex>`,
  last-write-wins). Reuses the existing `PalmPdb`/`PalmCharset` + new
  `MemoDb`/`ToDoDb` codecs (ports of the Go engine).

**Status:** compiles; codec + SLP layers have offline unit tests
(`HotSyncCodecTest`). **The live USB/DLP path is UNTESTED on hardware** — the
USB vendor-init control numbers and DLP arg layouts are the documented
pilot-link/coldsync ones and are the first thing to check against a real device
(see `cross-platform-desktop-sync-feasibility.md`). Date Book / Address / Mail
codecs are the next databases to port (their Go codecs already exist).

### Physical keyboard shortcuts
`MainActivity.dispatchKeyEvent` (Cosmo-gated) forwards to a handler installed by
`PalmVellumRoot`: **Ctrl+1..7** jump to Date Book / To Do / Address / Memo /
Note Pad / Expense / Mail; **Ctrl+0 or Ctrl+H** go Home; **Esc** goes back.
Ctrl-combos + Esc never collide with text entry, so fields keep every key.
