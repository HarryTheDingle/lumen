#!/usr/bin/env python3
"""Render Lumen's intro as a PNG sequence, from the logo itself (graphics/Lumen Logo transparent.png).

A star of light appears at the corner of the L, the L fills with light outward from it, "umen" rises in
letter by letter ("ta-dum"), then the camera dives through the logo into a tunnel of light ribbons in the
logo's own colours, and the light fades to black. Same timeline as render_intro.py (dx.bi's), so
lumen_intro_audio.py's score lands on it.

usage: render_lumen_intro.py OUT_DIR [--frames 0,60,120] [--w 1920 --h 1080 --fps 60]
"""
import argparse
import math
import os

import numpy as np
from PIL import Image, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
LOGO = os.path.join(HERE, "..", "graphics", "Lumen Logo transparent.png")

ap = argparse.ArgumentParser()
ap.add_argument("out")
ap.add_argument("--frames", default="")
ap.add_argument("--w", type=int, default=1920)
ap.add_argument("--h", type=int, default=1080)
ap.add_argument("--fps", type=int, default=60)
ap.add_argument("--sub", type=int, default=5, help="motion-blur subframes")
args = ap.parse_args()

W, H, FPS = args.w, args.h, args.fps
DURATION = 4.2
N = int(DURATION * FPS)
os.makedirs(args.out, exist_ok=True)

# Lumen's colours (the icon's): cyan, blue, amber
CYAN = np.array([46, 230, 214], np.float32) / 255.0
BLUE = np.array([47, 139, 255], np.float32) / 255.0
AMBER = np.array([255, 181, 71], np.float32) / 255.0
STAR = np.array([0.85, 0.97, 1.0], np.float32)

# Timeline (seconds)
T_SPARK = 0.12        # the star appears
T_FILL = 0.32         # the L fills with light from the star
T_FILL_END = 1.0
T_LETTERS = 0.62      # "u" starts rising; then m, e, n
T_LETTER_STEP = 0.09
T_LETTER_DUR = 0.55
T_HIT1 = 1.02         # "ta": the L is full
T_HIT2 = 1.42         # "dum": the word has landed
T_DIVE = 1.62
T_DIVE_END = 3.45
T_END = 4.0


def smooth(x):
    x = min(max(x, 0.0), 1.0)
    return x * x * (3 - 2 * x)


def ease_out(x):
    x = min(max(x, 0.0), 1.0)
    return 1 - (1 - x) ** 3


# ---------------------------------------------------------------- the logo, split into its letters
src = Image.open(LOGO).convert("RGBA")
rgba = np.asarray(src, np.float32) / 255.0
LIGHT = rgba[:, :, :3] * rgba[:, :, 3:4]          # the logo as light (premultiplied)
ALPHA = rgba[:, :, 3]
MH, MW = ALPHA.shape

# Letters are where the strokes are solid; the gaps between them are where no column has any
solid = (ALPHA > 0.55).any(axis=0)
runs = []
start = None
for i, s in enumerate(solid):
    if s and start is None:
        start = i
    if not s and start is not None:
        runs.append((start, i))
        start = None
if start is not None:
    runs.append((start, MW))
# merge slivers (a facet line can break a letter in two)
letters = []
for a, b in runs:
    if letters and a - letters[-1][1] < MW * 0.012:
        letters[-1] = (letters[-1][0], b)
    else:
        letters.append((a, b))
assert len(letters) == 5, f"expected 5 letters (L u m e n), found {letters}"
bounds = [(letters[i][1] + letters[i + 1][0]) / 2 for i in range(4)]
widths = [(letters[i + 1][0] - letters[i][1]) for i in range(4)]

# soft masks that add up to 1 everywhere, so the finished word is exactly the logo
cols = np.arange(MW, dtype=np.float32)
edges = [np.clip((cols - (b - w / 2)) / max(w, 1), 0, 1) for b, w in zip(bounds, widths)]
col_w = []
for i in range(5):
    left = edges[i - 1] if i > 0 else np.ones(MW, np.float32)
    right = 1 - edges[i] if i < 4 else np.ones(MW, np.float32)
    col_w.append(left * right)
LAYERS = [Image.fromarray(np.clip(LIGHT * w[None, :, None] * 255, 0, 255).astype(np.uint8)) for w in col_w]

# the star: the brightest point of the L (the flare where its stem meets its foot)
lum = Image.fromarray((LIGHT.max(axis=2) * col_w[0] * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(6))
la = np.asarray(lum, np.float32)
SY, SX = np.unravel_index(np.argmax(la), la.shape)

LOGO_W = W * 0.62                 # the logo's width on screen at zoom 1
base_scale = LOGO_W / MW          # master px -> screen px at zoom 1
CX, CY = W / 2, H / 2


def cam_at(t):
    if t <= T_DIVE:
        return 0.0
    u = min((t - T_DIVE) / (T_DIVE_END - T_DIVE), 1.0)
    return 0.985 * (u ** 2.4)


def zoom_at(t):
    push = 1.0 + 0.055 * smooth((t - 0.9) / (T_DIVE - 0.9))
    return push / max(1.0 - cam_at(t), 0.015)


def to_screen(mx, my, t):
    s = base_scale * zoom_at(t)
    return CX + (mx - MW / 2) * s, CY + (my - MH / 2) * s


xs = np.arange(W, dtype=np.float32)
ys = np.arange(H, dtype=np.float32)
XX, YY = np.meshgrid(xs, ys)


def place(layer, t, dy=0.0, sy=1.0):
    """A layer of the logo as it sits on screen at time t (dy: raised, in master px; sy: stretched)"""
    s = base_scale * zoom_at(t)
    vw, vh = W / s, H / s
    x0 = MW / 2 - vw / 2
    y0 = MH / 2 - vh / 2 - dy
    if sy != 1:
        cyc = y0 + vh / 2
        box = (x0, cyc - vh / sy / 2, x0 + vw, cyc + vh / sy / 2)
    else:
        box = (x0, y0, x0 + vw, y0 + vh)
    return layer.transform((W, H), Image.EXTENT, box, resample=Image.BILINEAR)


def logo_light(t):
    out = np.zeros((H, W, 3), np.float32)
    # the L, filling with light outward from the star
    p = (t - T_FILL) / (T_FILL_END - T_FILL)
    if p > 0:
        img = np.asarray(place(LAYERS[0], t), np.float32) / 255.0
        if p < 1.15:
            sx, sy = to_screen(SX, SY, t)
            reach = math.hypot(MW * 0.22, MH) * base_scale * zoom_at(t)
            r = ease_out(p) * reach
            soft = reach * 0.18
            d = np.sqrt((XX - sx) ** 2 + (YY - sy) ** 2)
            m = np.clip((r - d) / soft, 0, 1)
            # the edge of the light runs a little brighter as it fills
            front = np.exp(-((d - r) / (soft * 0.6)) ** 2) * (1 - smooth((p - 0.85) / 0.3)) * 0.8
            img = img * (m + front)[:, :, None]
        out += img
    # u, m, e, n rise into place one by one, from a soft blur
    for i in range(1, 5):
        start = T_LETTERS + (i - 1) * T_LETTER_STEP
        p = (t - start) / T_LETTER_DUR
        if p <= 0:
            continue
        e = ease_out(p)
        alpha = smooth(p * 1.6)
        layer = LAYERS[i]
        if p < 1:
            blur = (1 - e) * 10 / max(base_scale * zoom_at(t), 1e-3)
            if blur > 0.6:
                layer = layer.filter(ImageFilter.GaussianBlur(blur))
        img = place(layer, t, dy=(1 - e) * 0.22 * MH, sy=1 + (1 - e) * 0.35)
        out += np.asarray(img, np.float32) / 255.0 * alpha
    return out


def star(t, hit):
    """The star at the L's corner: a point, four soft streaks and a halo"""
    a = smooth((t - T_SPARK) / 0.18) * (1 - smooth((t - (T_DIVE + 0.1)) / 0.4))
    a *= 1.0 + 0.6 * hit
    if a <= 0.003:
        return None
    sx, sy = to_screen(SX, SY, t)
    z = zoom_at(t)
    size = H * 0.006 * (1 + 0.6 * smooth((t - T_SPARK) / 0.5)) * min(z, 3)
    dx, dy = XX - sx, YY - sy
    r2 = dx * dx + dy * dy
    core = np.exp(-r2 / (2 * size * size))
    halo = np.exp(-np.sqrt(r2) / (size * 9)) * 0.35
    # short soft streaks, longest as the star first flares and on the beats
    flare = 1 - 0.6 * smooth((t - (T_SPARK + 0.25)) / 0.5)
    streak_len = size * (5 + 9 * hit) * (0.6 + flare)
    streaks = (np.exp(-(dx / streak_len) ** 2 - (dy / (size * 0.5)) ** 2)
               + np.exp(-(dy / (streak_len * 0.7)) ** 2 - (dx / (size * 0.5)) ** 2)) * 0.7 * (0.5 + flare)
    v = (core * 1.6 + halo + streaks) * a
    return v[:, :, None] * STAR[None, None, :]


# ---------------------------------------------------------------- ribbons
# A tunnel of vertical light ribbons, as in dx.bi's intro, each coloured by the part of the logo it
# comes out of: cyan, blue and amber, as the glass is lit
rng = np.random.default_rng(11)
col_cov = ALPHA.sum(axis=0)
col_cov /= col_cov.sum()
col_rgb = LIGHT.sum(axis=0) / np.maximum(ALPHA.sum(axis=0)[:, None], 1e-6)
col_rgb /= np.maximum(col_rgb.max(axis=1, keepdims=True), 1e-6)
palette = np.stack([CYAN, CYAN, BLUE, BLUE, BLUE, AMBER, AMBER, STAR, np.array([0.35, 0.4, 1.0], np.float32)])
pal_p = np.array([3, 2, 4, 3, 2, 3, 2, 1, 1], np.float64)
pal_p /= pal_p.sum()

NR_LOGO = 70
NR_DEEP = 160
NR = NR_LOGO + NR_DEEP
push_end = 1.055
s_logo = base_scale * push_end
seed_cols = rng.choice(MW, size=NR_LOGO, p=col_cov)
R_X = np.concatenate([(seed_cols - MW / 2) * s_logo, rng.normal(0, W * 0.16, NR_DEEP)]).astype(np.float32)
R_Z = np.concatenate([np.ones(NR_LOGO), rng.uniform(1.05, 3.2, NR_DEEP)]).astype(np.float32)
R_w = rng.uniform(1.0, 6.5, NR).astype(np.float32)
R_w[rng.random(NR) < 0.2] *= 0.3
R_col = np.concatenate([col_rgb[seed_cols], palette[rng.choice(len(palette), size=NR_DEEP, p=pal_p)]]).astype(np.float32)
R_gain = rng.uniform(0.35, 0.8, NR).astype(np.float32)
R_gain[NR_LOGO:] *= 0.85
R_halfh = (MH * s_logo * 0.42) * rng.uniform(0.85, 1.15, NR)


def ribbons(t):
    if t < T_DIVE:
        return None
    cam = cam_at(t)
    u = (t - T_DIVE) / (T_DIVE_END - T_DIVE)
    reveal = smooth(u / 0.16)
    tail = 1 - smooth((u - 0.8) / 0.2)
    img = np.zeros((H, W, 3), np.float32)
    for i in range(NR):
        d = R_Z[i] - cam * (1.0 + 2.6 * u)
        if d <= 0.02:
            continue
        x = CX + R_X[i] / d
        w = max(R_w[i] / d, 0.7)
        if x + 4 * w < 0 or x - 4 * w > W:
            continue
        fog = np.clip((3.4 - d) / 2.2, 0, 1)
        near = np.clip((d - 0.02) / 0.22, 0, 1)
        amp = R_gain[i] * fog * near * reveal * tail
        if amp < 0.004:
            continue
        lo, hi = int(max(0, x - 5 * w)), int(min(W, x + 5 * w + 1))
        dx = (xs[lo:hi] - x) / w
        prof = 0.55 * np.exp(-0.5 * dx * dx) + 0.9 * np.exp(-0.5 * (dx * 2.8) ** 2)
        hh = R_halfh[i] / d
        e = np.clip((hh - np.abs(ys - CY)) / max(hh * 0.22, 1.0), 0, 1)
        vy = e * e * (3 - 2 * e)
        img[:, lo:hi, :] += (vy[:, None] * prof[None, :])[:, :, None] * (R_col[i] * amp)[None, None, :]
    return img


# ---------------------------------------------------------------- compositing
def bloom(rgb, radius, strength):
    small = Image.fromarray(np.clip(rgb * 255, 0, 255).astype(np.uint8)).resize((W // 4, H // 4), Image.BILINEAR)
    small = small.filter(ImageFilter.GaussianBlur(radius / 4))
    big = np.asarray(small.resize((W, H), Image.BILINEAR), np.float32) / 255.0
    return rgb + big * strength


WASH = None


def frame_at(t):
    global WASH
    rgb = np.zeros((H, W, 3), np.float32)
    hit = math.exp(-((t - T_HIT1) / 0.12) ** 2) * 0.5 + math.exp(-((t - T_HIT2) / 0.18) ** 2)
    u = max(0.0, (t - T_DIVE) / (T_DIVE_END - T_DIVE))
    if t < T_DIVE_END:
        logo_alpha = 1 - smooth((u - 0.15) / 0.45)
        if logo_alpha > 0:
            # (the glass reads brighter than in the still logo: it has to on a black screen)
            rgb += logo_light(t) * logo_alpha * (1.5 + 0.4 * hit)
    s = star(t, hit)
    if s is not None:
        rgb += s
    r = ribbons(t)
    if r is not None:
        rgb += r
    # the light floods the screen as we pass through, blue at the heart, cyan and amber at the edges
    wash = smooth((t - (T_DIVE_END - 0.35)) / 0.3) * (1 - smooth((t - T_DIVE_END) / (T_END - T_DIVE_END)))
    if wash > 0:
        if WASH is None:
            rad = np.sqrt(((XX - CX) / (W * 0.6)) ** 2 + ((YY - CY) / (H * 0.6)) ** 2)
            fall = np.clip(1 - rad, 0, 1) ** 1.6
            side = np.clip((XX - CX) / (W * 0.5), -1, 1)
            tint = np.where(side[:, :, None] < 0,
                            BLUE * (1 + side[:, :, None]) + CYAN * -side[:, :, None],
                            BLUE * (1 - side[:, :, None]) + AMBER * side[:, :, None])
            WASH = fall[:, :, None] * tint
        rgb += WASH * (0.55 * wash)
    out_fade = 1 - smooth((t - (T_END - 0.45)) / 0.45)
    rgb *= out_fade
    glow = 0.35 + 0.5 * hit + 0.15 * smooth(u / 0.3) * (1 - smooth((u - 0.7) / 0.3))
    rgb = bloom(rgb, 40, glow)
    rgb = bloom(rgb, 8, 0.2)
    lum = rgb.max(axis=2, keepdims=True)
    mapped = 1 - np.exp(-lum * 1.25)
    rgb = rgb * (mapped / np.maximum(lum, 1e-5))
    return rgb


def render(i):
    t0 = i / FPS
    acc = np.zeros((H, W, 3), np.float32)
    sub = args.sub if t0 > T_DIVE - 0.1 else 2
    for k in range(sub):
        acc += frame_at(t0 + (k / sub) / FPS)
    acc /= sub
    acc += (np.random.default_rng(i).random((H, W, 1), np.float32) - 0.5) / 255.0
    Image.fromarray(np.clip(acc * 255 + 0.5, 0, 255).astype(np.uint8)).save(os.path.join(args.out, f"f{i:04d}.png"), compress_level=1)


if __name__ == "__main__":
    frames = [int(x) for x in args.frames.split(",") if x] or list(range(N))
    from concurrent.futures import ProcessPoolExecutor
    with ProcessPoolExecutor(max_workers=max(1, (os.cpu_count() or 4) - 2)) as ex:
        list(ex.map(render, frames))
    print("rendered", len(frames), "frames")
