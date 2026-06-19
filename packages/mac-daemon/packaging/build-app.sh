#!/usr/bin/env bash
# Build PalmVellum.app (menu-bar app) and an optional .dmg.
#
# Usage:
#   packaging/build-app.sh [version]
#
# Produces dist/PalmVellum.app (unsigned by default). If a Developer ID
# is available the script will code-sign and, with notarization creds,
# notarize + staple. None of that is required to run locally — Gatekeeper
# can be bypassed with a right-click → Open on an unsigned build.
#
# Signing (optional) — set before running:
#   DEVELOPER_ID="Developer ID Application: Your Name (TEAMID)"
#   # for notarization, also:
#   AC_KEYCHAIN_PROFILE="notary-profile"   # set up via `xcrun notarytool store-credentials`
#
# Requires: Go (CGO for the systray UI), macOS.
set -euo pipefail

cd "$(dirname "$0")/.."                       # packages/mac-daemon
VERSION="${1:-$(git describe --tags --always 2>/dev/null || echo dev)}"
APP="dist/PalmVellum.app"
MACOS="$APP/Contents/MacOS"
RES="$APP/Contents/Resources"

echo "→ building palmvellum binary ($VERSION)"
rm -rf "$APP"
mkdir -p "$MACOS" "$RES"
CGO_ENABLED=1 go build -trimpath \
  -ldflags "-X main.version=$VERSION" \
  -o "$MACOS/palmvellum" ./cmd/palmvellum

echo "→ assembling bundle"
# No launcher shim: a shim named "PalmVellum" would collide with the
# "palmvellum" binary on macOS's case-insensitive filesystem. Instead the
# binary itself detects a bundle launch (argv contains /Contents/MacOS/)
# and defaults to the `menubar` subcommand — see cmd/palmvellum/main.go.
sed "s/__VERSION__/$VERSION/g" packaging/Info.plist > "$APP/Contents/Info.plist"
[ -f packaging/icon.icns ] && cp packaging/icon.icns "$RES/icon.icns" || \
  echo "  (no packaging/icon.icns — using default app icon)"

if [ -n "${DEVELOPER_ID:-}" ]; then
  echo "→ code-signing with: $DEVELOPER_ID"
  codesign --force --deep --options runtime --timestamp \
    --sign "$DEVELOPER_ID" "$APP"
  codesign --verify --strict --verbose=2 "$APP"
else
  echo "⚠️  DEVELOPER_ID unset — building UNSIGNED (right-click → Open to run)"
fi

# ── Build the distributable .dmg ────────────────────────────────────
# A drag-to-Applications layout plus the usage guide. Always produced so
# the app can be shared; signing/notarization is layered on when creds
# are present.
echo "→ building dmg"
DMG="dist/PalmVellum-$VERSION.dmg"
STAGE="dist/dmg"
rm -rf "$STAGE" "$DMG"
mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
# Usage guide alongside the app (Markdown reads fine as plain text).
[ -f ../../docs/USAGE.md ] && cp ../../docs/USAGE.md "$STAGE/Usage & Read Me.txt"
hdiutil create -volname "PalmVellum" -srcfolder "$STAGE" -ov -format UDZO "$DMG" >/dev/null
rm -rf "$STAGE"

if [ -n "${DEVELOPER_ID:-}" ] && [ -n "${AC_KEYCHAIN_PROFILE:-}" ]; then
  echo "→ notarizing dmg"
  xcrun notarytool submit "$DMG" --keychain-profile "$AC_KEYCHAIN_PROFILE" --wait
  xcrun stapler staple "$APP"
  xcrun stapler staple "$DMG"
  echo "✅ signed + notarized"
elif [ -n "${DEVELOPER_ID:-}" ]; then
  echo "⚠️  AC_KEYCHAIN_PROFILE unset — signed but NOT notarized"
fi

echo "✅ built $APP"
echo "✅ built $DMG"
