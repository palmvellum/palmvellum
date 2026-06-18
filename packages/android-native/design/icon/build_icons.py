#!/usr/bin/env python3
"""Render PalmVellum launcher icons from the SVG sources.

Pipeline: Chrome headless rasterises the SVGs at high resolution, then Pillow
derives every density bucket, the round icon and the Play Store graphic.
"""
import os, re, subprocess, tempfile
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "res"))
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
RENDER = 1024  # source raster size

def render_svg(svg_path, out_png, size, transparent):
    """Rasterise an SVG to a square PNG of the given size via headless Chrome."""
    with open(svg_path) as f:
        svg = f.read()
    svg = re.sub(r'width="\d+"', f'width="{size}"', svg, count=1)
    svg = re.sub(r'height="\d+"', f'height="{size}"', svg, count=1)
    tmp = tempfile.NamedTemporaryFile(suffix=".svg", delete=False, mode="w")
    tmp.write(svg); tmp.close()
    args = [CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
            f"--screenshot={out_png}", f"--window-size={size},{size}",
            f"--force-device-scale-factor=1"]
    if transparent:
        args.append("--default-background-color=00000000")
    args.append("file://" + tmp.name)
    subprocess.run(args, check=True, capture_output=True)
    os.unlink(tmp.name)

def circle_mask(size):
    from PIL import ImageDraw
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse((0, 0, size - 1, size - 1), fill=255)
    return m

def composite(bg, fg, size, fg_scale=1.18):
    base = bg.resize((size, size), Image.LANCZOS).convert("RGBA")
    fs = int(round(size * fg_scale))
    layer = fg.resize((fs, fs), Image.LANCZOS)
    off = (size - fs) // 2
    base.alpha_composite(layer, (off, off))
    return base

def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("  wrote", os.path.relpath(path, RES) if path.startswith(RES) else path)

# 1. high-res sources
fg = os.path.join(HERE, "_fg.png")
bg = os.path.join(HERE, "_bg.png")
render_svg(os.path.join(HERE, "foreground.svg"), fg, RENDER, transparent=True)
render_svg(os.path.join(HERE, "background.svg"), bg, RENDER, transparent=False)
FG = Image.open(fg).convert("RGBA")
BG = Image.open(bg).convert("RGBA")

# 2. adaptive foreground per density (108dp baseline)
FG_SIZES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
print("adaptive foreground:")
for d, s in FG_SIZES.items():
    save(FG.resize((s, s), Image.LANCZOS),
         os.path.join(RES, f"mipmap-{d}", "ic_launcher_foreground.png"))

# 3. legacy square + round per density (48dp baseline)
SQ_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
print("legacy square + round:")
for d, s in SQ_SIZES.items():
    comp = composite(BG, FG, s)
    save(comp.convert("RGB"), os.path.join(RES, f"mipmap-{d}", "ic_launcher.png"))
    rnd = comp.copy(); rnd.putalpha(circle_mask(s))
    save(rnd, os.path.join(RES, f"mipmap-{d}", "ic_launcher_round.png"))

# 4. Play Store 512 (opaque, square)
play = composite(BG, FG, 512).convert("RGB")
save(play, os.path.join(HERE, "ic_launcher-playstore.png"))
out = os.path.expanduser("~/Desktop/mac-mini-output/PalmVellum-icon-playstore.png")
play.save(out); print("  wrote", out)

os.remove(fg); os.remove(bg)
print("done.")
