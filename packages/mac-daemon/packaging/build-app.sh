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
# Single source of truth: the `var version` line in main.go (bumped on every
# change). An explicit arg still overrides for one-off builds.
VERSION="${1:-$(grep -oE 'var version = "[^"]+"' cmd/palmvellum/main.go | sed -E 's/.*"([^"]+)".*/\1/')}"
VERSION="${VERSION:-dev}"
APP="dist/PalmVellum Sync on Mac.app"
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

# ── Bundle the HotSync sidecar (Node runtime + palm-sync) ───────────
# So USB HotSync works on a machine with no Node installed. Ships a
# stripped Node binary plus the sidecar's production node_modules under
# Contents/Resources; the Go binary resolves them at runtime (see
# internal/hotsync/sidecar.go → Resolve()).
echo "→ bundling HotSync sidecar (Node + palm-sync)"
NODE_VERSION="${NODE_VERSION:-v22.14.0}"
case "$(uname -m)" in
  arm64)  NODE_ARCH="darwin-arm64" ;;
  x86_64) NODE_ARCH="darwin-x64" ;;
  *) echo "  unsupported arch $(uname -m)"; exit 1 ;;
esac
NODE_PKG="node-$NODE_VERSION-$NODE_ARCH"
NODE_CACHE="${TMPDIR:-/tmp}/palmvellum-node-cache"
mkdir -p "$NODE_CACHE"
if [ ! -x "$NODE_CACHE/$NODE_PKG/bin/node" ]; then
  echo "  downloading $NODE_PKG"
  curl -fsSL "https://nodejs.org/dist/$NODE_VERSION/$NODE_PKG.tar.gz" \
    | tar -xz -C "$NODE_CACHE"
fi
mkdir -p "$RES/node/bin"
cp "$NODE_CACHE/$NODE_PKG/bin/node" "$RES/node/bin/node"

echo "  installing sidecar production deps"
( cd sidecar && { npm ci --omit=dev --no-audit --no-fund 2>/dev/null \
                  || npm install --omit=dev --no-audit --no-fund; } )
mkdir -p "$RES/sidecar"
cp sidecar/conduit.js sidecar/package.json "$RES/sidecar/"
cp -R sidecar/node_modules "$RES/sidecar/node_modules"

if [ -n "${DEVELOPER_ID:-}" ]; then
  echo "→ code-signing with: $DEVELOPER_ID"
  # Sign inner executables first (bundled node + native .node addons),
  # then the app, all with the hardened-runtime entitlements Node needs.
  ENT="packaging/entitlements.plist"
  find "$RES/sidecar/node_modules" -name '*.node' -print0 | while IFS= read -r -d '' f; do
    codesign --force --options runtime --timestamp --entitlements "$ENT" --sign "$DEVELOPER_ID" "$f"
  done
  codesign --force --options runtime --timestamp --entitlements "$ENT" --sign "$DEVELOPER_ID" "$RES/node/bin/node"
  codesign --force --deep --options runtime --timestamp --entitlements "$ENT" \
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
DMG="dist/PalmVellum-Sync-on-Mac-$VERSION.dmg"
STAGE="dist/dmg"
rm -rf "$STAGE" "$DMG"
mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
# Usage guide alongside the app (Markdown reads fine as plain text).
[ -f ../../docs/USAGE.md ] && cp ../../docs/USAGE.md "$STAGE/Usage & Read Me.txt"
hdiutil create -volname "PalmVellum Sync on Mac" -srcfolder "$STAGE" -ov -format UDZO "$DMG" >/dev/null
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
