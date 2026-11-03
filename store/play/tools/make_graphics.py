#!/usr/bin/env python3
"""Google Play store graphics: 512x512 icon and 1024x500 feature graphic.

A terminal window with the prompt ">DSPi_". Rendered 4x and downscaled for
smooth edges. Font: Noto Sans Mono (SIL OFL).
Usage: make_graphics.py <outdir> [app/src/main/res]  (the second argument also writes
the adaptive launcher icon layers into mipmap-*/)
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

SS = 4  # supersampling
FONT_DIR = Path("/usr/share/fonts/google-noto")
BOLD = str(FONT_DIR / "NotoSansMono-Bold.ttf")
REGULAR = str(FONT_DIR / "NotoSansMono-Regular.ttf")

BG = (24, 27, 32)            # behind the window (full bleed)
WINDOW = (10, 12, 15)        # console body
TITLEBAR = (44, 48, 56)
DOTS = [(237, 106, 94), (245, 191, 79), (98, 197, 84)]
PHOSPHOR = (74, 222, 128)    # prompt text
PROMPT = (62, 196, 160)      # ">" (the app's teal accent)
DIM = (120, 132, 140)


def s(v):
    return int(round(v * SS))


def console(d, box, radius, bar_h, dot_r):
    x0, y0, x1, y1 = box
    d.rounded_rectangle([s(x0), s(y0), s(x1), s(y1)], s(radius), fill=WINDOW)
    # title bar: top corners rounded, bottom square
    d.rounded_rectangle([s(x0), s(y0), s(x1), s(y0 + bar_h)], s(radius), fill=TITLEBAR)
    d.rectangle([s(x0), s(y0 + bar_h - radius), s(x1), s(y0 + bar_h)], fill=TITLEBAR)
    cy = y0 + bar_h / 2
    for i, c in enumerate(DOTS):
        cx = x0 + bar_h * 0.55 + i * dot_r * 3.2
        d.ellipse([s(cx - dot_r), s(cy - dot_r), s(cx + dot_r), s(cy + dot_r)], fill=c)


def prompt(d, x, baseline, size, cursor_on=True):
    """Draw ">DSPi" + a block underscore cursor; returns the right edge."""
    f = ImageFont.truetype(BOLD, s(size))
    adv = f.getlength("M") / SS
    d.text((s(x), s(baseline)), ">", font=f, fill=PROMPT, anchor="ls")
    d.text((s(x + adv), s(baseline)), "DSPi", font=f, fill=PHOSPHOR, anchor="ls")
    cx = x + adv * 5
    if cursor_on:
        h = size * 0.12
        d.rectangle([s(cx + adv * 0.06), s(baseline - h), s(cx + adv * 0.94), s(baseline)], fill=PHOSPHOR)
    return cx + adv


def prompt_width(size):
    return ImageFont.truetype(BOLD, s(size)).getlength("M") / SS * 6


def icon(out):
    W = 512
    im = Image.new("RGB", (s(W), s(W)), BG)
    d = ImageDraw.Draw(im)
    # Play masks the icon to a rounded square; keep the window well inside it.
    m = 52
    bar = 62
    console(d, (m, m + 18, W - m, W - m - 18), 34, bar, 11)
    size = 96
    tw = prompt_width(size)
    body_top, body_bot = m + 18 + bar, W - m - 18
    baseline = (body_top + body_bot) / 2 + size * 0.36
    prompt(d, (W - tw) / 2, baseline, size)
    im.resize((W, W), Image.LANCZOS).save(out / "icon-512.png", optimize=True)


def feature(out):
    W, H = 1024, 500
    im = Image.new("RGB", (s(W), s(H)), BG)
    d = ImageDraw.Draw(im)
    box = (112, 70, W - 112, H - 70)
    bar = 48
    console(d, box, 26, bar, 9)
    size = 120
    x = box[0] + 64
    baseline = box[1] + bar + 60 + size * 0.75
    prompt(d, x, baseline, size)
    small = ImageFont.truetype(REGULAR, s(26))
    lines = ["EQ · crossovers · matrix mixer · crossfeed",
             "live meters · spectrum analyser · presets"]
    y = baseline + 62
    for ln in lines:
        d.text((s(x + 4), s(y)), ln, font=small, fill=DIM, anchor="ls")
        y += 40
    im.resize((W, H), Image.LANCZOS).save(out / "feature-1024x500.png", optimize=True)


LAUNCHER_DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def launcher(res):
    """Adaptive launcher icon layers (108 dp; drawn at xxxhdpi, 4 px/dp).

    The background is the colour resource ic_launcher_background (BG). The
    window stays inside the 66 dp safe circle that every launcher mask keeps.
    The monochrome layer (Android 13 themed icons) is a window outline plus
    the prompt, in white on transparent.
    """
    W = 432
    box = (112, 140, 320, 292)  # 52 x 38 dp, corners within the 33 dp safe radius
    bar, size = 36, 48
    tw = prompt_width(size)
    baseline = (box[1] + bar + box[3]) / 2 + size * 0.36

    fg = Image.new("RGBA", (s(W), s(W)), (0, 0, 0, 0))
    d = ImageDraw.Draw(fg)
    console(d, box, 20, bar, 6.4)
    prompt(d, (W - tw) / 2, baseline, size)

    mono = Image.new("RGBA", (s(W), s(W)), (0, 0, 0, 0))
    m = ImageDraw.Draw(mono)
    white = (255, 255, 255, 255)
    x0, y0, x1, y1 = box
    m.rounded_rectangle([s(x0), s(y0), s(x1), s(y1)], s(20), outline=white, width=s(10))
    m.rectangle([s(x0), s(y0 + bar - 5), s(x1), s(y0 + bar + 5)], fill=white)
    f = ImageFont.truetype(BOLD, s(size))
    adv = f.getlength("M") / SS
    m.text((s((W - tw) / 2), s(baseline)), ">DSPi", font=f, fill=white, anchor="ls")
    cx = (W - tw) / 2 + adv * 5
    m.rectangle([s(cx + adv * 0.06), s(baseline - size * 0.12), s(cx + adv * 0.94), s(baseline)], fill=white)

    for dpi, px in LAUNCHER_DENSITIES.items():
        d_ = res / f"mipmap-{dpi}"
        d_.mkdir(parents=True, exist_ok=True)
        fg.resize((px, px), Image.LANCZOS).save(d_ / "ic_launcher_foreground.png", optimize=True)
        mono.resize((px, px), Image.LANCZOS).save(d_ / "ic_launcher_monochrome.png", optimize=True)


if __name__ == "__main__":
    out = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    out.mkdir(parents=True, exist_ok=True)
    icon(out)
    feature(out)
    if len(sys.argv) > 2:  # app/src/main/res: the launcher icon layers
        launcher(Path(sys.argv[2]))
