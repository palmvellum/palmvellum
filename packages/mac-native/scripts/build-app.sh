#!/bin/bash
# Build "PalmVellum Organizers.app" (release) + ad-hoc sign + package a .dmg
# into the Mac-mini output folder. Works with Command Line Tools only (no
# Xcode): SwiftPM builds the binary, we hand-assemble the .app bundle and sign
# it ad-hoc for sideloading (not notarized — see README).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="0.1.0"
BIN="PalmVellum"                 # the SwiftPM executable product name
DISPLAY="PalmVellum Organizers"  # user-facing app name
BUNDLE_ID="dev.tatliving.palmvellum.organizers.mac"

echo "==> swift build -c release"
swift build -c release

APP="dist/${DISPLAY}.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

cp ".build/release/${BIN}" "$APP/Contents/MacOS/${BIN}"

# App icon: the original "PalmVellum on Mac" mark (cream vellum card +
# orange palm wordmark), now the Organizers icon.
ICON_SRC="packaging/PalmVellum-Organizers.icns"
ICON_PLIST=""
if [ -f "$ICON_SRC" ]; then
  cp "$ICON_SRC" "$APP/Contents/Resources/AppIcon.icns"
  ICON_PLIST="    <key>CFBundleIconFile</key><string>AppIcon</string>"
fi

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>${DISPLAY}</string>
    <key>CFBundleDisplayName</key><string>${DISPLAY}</string>
    <key>CFBundleIdentifier</key><string>${BUNDLE_ID}</string>
    <key>CFBundleExecutable</key><string>${BIN}</string>
${ICON_PLIST}
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>${VERSION}</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
    <key>LSMinimumSystemVersion</key><string>12.0</string>
    <key>NSHighResolutionCapable</key><true/>
    <key>LSApplicationCategoryType</key><string>public.app-category.productivity</string>
</dict>
</plist>
PLIST

echo "==> ad-hoc codesign"
codesign --force --deep --sign - "$APP"
codesign --verify --verbose "$APP" || true

TS="$(date +%Y%m%d-%H%M)"
OUT="$HOME/Desktop/mac-mini-output"
mkdir -p "$OUT"
DMG="$OUT/PalmVellum-Organizers-mac-${VERSION}-${TS}.dmg"

echo "==> hdiutil create $DMG"
rm -f "$DMG"
hdiutil create -volname "${DISPLAY}" -srcfolder "$APP" -ov -format UDZO "$DMG" >/dev/null

echo "DONE"
echo "APP: $ROOT/$APP"
echo "DMG: $DMG"
ls -la "$DMG"
