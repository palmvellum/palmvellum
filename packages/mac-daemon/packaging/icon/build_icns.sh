#!/usr/bin/env bash
# Render PalmVellum.app icon (../icon.icns) from icon.svg.
#
# Pipeline: headless Chrome rasterises the 1024px master SVG (transparent),
# then sips derives every iconset bucket and iconutil packs the .icns.
# No librsvg/inkscape needed — matches the Android icon's Chrome pipeline.
set -euo pipefail
cd "$(dirname "$0")"

CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
MASTER="_master_1024.png"
ICONSET="PalmVellum.iconset"
OUT="../icon.icns"

echo "→ rasterising icon.svg → $MASTER (1024px)"
"$CHROME" --headless=new --disable-gpu --hide-scrollbars \
  --force-device-scale-factor=1 --window-size=1024,1024 \
  --default-background-color=00000000 \
  --screenshot="$MASTER" "file://$PWD/icon.svg" >/dev/null 2>&1

rm -rf "$ICONSET"; mkdir -p "$ICONSET"
# (size, filename) pairs for a complete macOS iconset.
for spec in \
  16:icon_16x16.png 32:icon_16x16@2x.png \
  32:icon_32x32.png 64:icon_32x32@2x.png \
  128:icon_128x128.png 256:icon_128x128@2x.png \
  256:icon_256x256.png 512:icon_256x256@2x.png \
  512:icon_512x512.png 1024:icon_512x512@2x.png ; do
  px="${spec%%:*}"; name="${spec##*:}"
  sips -z "$px" "$px" "$MASTER" --out "$ICONSET/$name" >/dev/null
done

echo "→ packing $OUT"
iconutil -c icns "$ICONSET" -o "$OUT"
rm -rf "$ICONSET" "$MASTER"
echo "✅ wrote $(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"
