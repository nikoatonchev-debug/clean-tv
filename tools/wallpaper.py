"""Erzeugt das Berg-Hintergrundbild in zwei Ebenen.

back.jpg  = Himmel + ferne Berge (liegt HINTER der Uhr)
front.png = Hauptgipfel + Vordergrund (liegt VOR der Uhr, mit Transparenz)
preview.png = Vorschau mit Beispiel-Uhr
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)
FONT = sys.argv[2] if len(sys.argv) > 2 else None

S = 2                      # Supersampling
W, H = 1920 * S, 1080 * S
rng = np.random.default_rng(7)
xs = np.arange(W, dtype=np.float32)
ys = np.arange(H, dtype=np.float32)[:, None]


def hexc(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float32)


def fbm1d(n, octaves, base, seed, persistence=0.5):
    r = np.random.default_rng(seed)
    out = np.zeros(n, dtype=np.float32)
    amp, freq, total = 1.0, base, 0.0
    for _ in range(octaves):
        pts = r.uniform(-1, 1, int(freq) + 2).astype(np.float32)
        t = np.linspace(0, freq, n, dtype=np.float32)
        i = np.floor(t).astype(int)
        f = t - i
        f = f * f * (3 - 2 * f)
        out += amp * (pts[i] * (1 - f) + pts[i + 1] * f)
        total += amp
        amp *= persistence
        freq *= 2
    return out / total


def noise2d(w, h, cells_x, cells_y, seed):
    r = np.random.default_rng(seed)
    small = (r.random((cells_y, cells_x)) * 255).astype(np.uint8)
    img = Image.fromarray(small).resize((w, h), Image.BICUBIC)
    return np.asarray(img, dtype=np.float32) / 255.0


def gradient(stops, n):
    pos = np.array([p for p, _ in stops], dtype=np.float32)
    cols = np.array([hexc(c) for _, c in stops])
    t = np.linspace(0, 1, n, dtype=np.float32)
    return np.stack([np.interp(t, pos, cols[:, k]) for k in range(3)], axis=1)


def blend(dst, col, alpha):
    a = alpha[..., None]
    return dst * (1 - a) + col * a


# ---------------- Himmel ----------------
sky_col = gradient([(0.00, "#070a1c"), (0.28, "#141a3d"), (0.48, "#2f3266"),
                    (0.60, "#6b5a8c"), (0.70, "#c98a8a"), (0.78, "#f2b48f"),
                    (1.00, "#f6c9a0")], H)
back = np.repeat(sky_col[:, None, :], W, axis=1)

# warmes Leuchten hinter dem Gipfel (Sonne gerade untergegangen)
gx, gy = 0.5 * W, 0.70 * H
d = np.sqrt(((xs - gx) / (0.55 * W)) ** 2 + ((ys - gy) / (0.30 * H)) ** 2)
glow = np.clip(1 - d, 0, 1) ** 2 * 0.35
back = blend(back, hexc("#ffd2a8"), glow)

# Sterne
n_stars = 900
sx = rng.uniform(0, W, n_stars)
sy = rng.uniform(0, 0.55 * H, n_stars) ** 1.0
star_img = Image.new("L", (W, H), 0)
sd = ImageDraw.Draw(star_img)
for x, y in zip(sx, sy):
    fade = 1 - y / (0.55 * H)
    b = int(rng.uniform(60, 255) * fade ** 1.5)
    r = rng.choice([1, 1, 1, 2, 2, 3]) * S / 2
    sd.ellipse([x - r, y - r, x + r, y + r], fill=b)
star_img = star_img.filter(ImageFilter.GaussianBlur(0.6 * S))
back = blend(back, hexc("#ffffff"), np.asarray(star_img, dtype=np.float32) / 255)


# ---------------- Bergkette-Helfer ----------------
def range_layer(img, base, amp, seed, col, haze_col, haze, octaves=8, freq=3, sharp=1.0):
    ridge = base * H - amp * H * np.abs(fbm1d(W, octaves, freq, seed)) ** sharp * 2.2
    ridge += amp * H * 0.35 * fbm1d(W, 6, 18, seed + 1)
    mask = (ys > ridge[None, :]).astype(np.float32)
    # nach unten in Dunst übergehen
    depth = np.clip((ys - ridge[None, :]) / (0.25 * H), 0, 1)
    colour = col * (1 - haze) + haze_col * haze
    shade = colour[None, None, :] * (1 - 0.18 * depth[..., None]) + haze_col * 0.18 * depth[..., None] * 0
    img = img * (1 - mask[..., None]) + shade * mask[..., None]
    # Dunstband am Fuß
    mist = np.clip(1 - np.abs(ys - (base + 0.06) * H) / (0.07 * H), 0, 1) * 0.35
    img = blend(img, haze_col, mist * mask)
    return img, ridge


back, _ = range_layer(back, 0.66, 0.10, 11, hexc("#5d5590"), hexc("#d79d93"), 0.45, freq=2)
back, _ = range_layer(back, 0.72, 0.07, 23, hexc("#3e3b72"), hexc("#b98597"), 0.30, freq=3)

# ---------------- Hauptgipfel (vor der Uhr) ----------------
front = np.zeros((H, W, 3), dtype=np.float32)
alpha = np.zeros((H, W), dtype=np.float32)

# Massiv aus mehreren Gipfeln: (x, Höhe, Steilheit links, Steilheit rechts)
peaks = [(0.50, 0.215, 1.55, 1.30), (0.36, 0.37, 1.25, 1.6), (0.64, 0.33, 1.7, 1.15),
         (0.24, 0.50, 1.1, 1.5), (0.79, 0.47, 1.5, 1.0)]
fine = noise2d(W, H, 300, 170, 53)
broad = noise2d(W, H, 40, 22, 55)
rock_lit, rock_sh = hexc("#5f5b8f"), hexc("#25244c")
snow_lit, snow_sh = hexc("#f6eef0"), hexc("#8d92c6")


def soft(a, r):
    return np.asarray(Image.fromarray((np.clip(a, 0, 1) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(r)), dtype=np.float32) / 255


# hintere Gipfel zuerst, Hauptgipfel zuletzt (liegt vorne)
for i in [3, 4, 1, 2, 0]:
    px, py, sl, sr = peaks[i]
    d = xs / W - px
    prof = py * H + np.where(d < 0, -d * sl, d * sr) * H
    prof += np.abs(fbm1d(W, 8, 12, 300 + i)) * 0.028 * H * np.clip(np.abs(d) * 8, 0, 1)
    prof = np.minimum(prof, 0.82 * H)
    m = (ys > prof[None, :]).astype(np.float32)

    # Licht von links: Grat vom Gipfel nach unten, rechts davon Schatten
    yy = np.clip((ys - py * H) / H, 0, 1)
    crease = px * W + (0.16 * yy + 0.05 * fbm1d(H, 6, 7, 400 + i)[:, None] * np.sqrt(yy)) * W
    lit = (xs[None, :] < crease).astype(np.float32)
    c2 = px * W - (0.05 - 0.10 * yy + 0.03 * fbm1d(H, 5, 9, 450 + i)[:, None]) * W
    lit = np.where((lit > 0) & (xs[None, :] > c2) & (ys > py * H + 0.06 * H), 0.72, lit)
    lit = soft(lit, 0.8 * S)

    # Schnee ab fester Höhe, mit Zungen in den Rinnen
    snowline = (py + 0.17 + 0.06 * (i == 0)) * H + fbm1d(W, 6, 9, 61 + i) * 0.03 * H
    tongues = np.clip(fbm1d(W, 4, 26, 63 + i), 0, 1) ** 1.6 * 0.12 * H
    jag = (broad - 0.5) * 0.04 * H + (fine - 0.5) * 0.010 * H
    snow = soft((ys < snowline[None, :] + tongues[None, :] * (0.4 + 0.6 * lit) + jag).astype(np.float32), 1.0 * S)

    rock = rock_sh + (rock_lit - rock_sh) * lit[..., None]
    snw = snow_sh + (snow_lit - snow_sh) * lit[..., None]
    tex = (0.90 + 0.14 * broad + 0.06 * fine)[..., None]
    col = rock * tex * (1 - snow[..., None]) + snw * (0.95 + 0.05 * fine[..., None]) * snow[..., None]
    rim = np.clip(1 - (ys - prof[None, :]) / (0.010 * H), 0, 1) * np.clip(lit, 0.3, 1) * 0.55
    col = blend(col, hexc("#ffd9c0"), rim)
    # hintere Gipfel etwas dunstiger
    if i != 0:
        col = blend(col, hexc("#4a4378"), np.full((H, W), 0.18, dtype=np.float32))
    low = np.clip((ys - 0.50 * H) / (0.28 * H), 0, 1) * 0.6
    col = blend(col, hexc("#3a3566"), low)
    front = front * (1 - m[..., None]) + col * m[..., None]
    alpha = np.maximum(alpha, m)

# Nebelschleier am Fuß des Gipfels
mist = np.clip(1 - np.abs(ys - 0.74 * H) / (0.10 * H), 0, 1) ** 2 * 0.40 * (0.6 + 0.8 * noise2d(W, H, 10, 4, 71))
front = blend(front, hexc("#b8a0bf"), mist * alpha)


# ---------------- Vordergrund-Hügel mit Tannen ----------------
def hill(base, amp, seed, colr, front, alpha, trees=0, tree_h=0.05):
    ridge = base * H + fbm1d(W, 7, 3, seed) * amp * H
    m = (ys > ridge[None, :]).astype(np.float32)
    front = front * (1 - m[..., None]) + colr * m[..., None]
    alpha = np.maximum(alpha, m)
    if trees:
        timg = Image.new("L", (W, H), 0)
        td = ImageDraw.Draw(timg)
        r = np.random.default_rng(seed + 5)
        for _ in range(trees):
            tx = r.uniform(0, W)
            if 0.28 * W < tx < 0.72 * W and r.random() < 0.75:
                continue  # Mitte frei lassen
            th = r.uniform(0.5, 1.0) * tree_h * H
            tw = th * r.uniform(0.28, 0.38)
            by = ridge[int(tx) % W] + th * 0.12
            for k in range(4):  # gestufte Tanne
                t0 = by - th * (k / 4) * 0.85
                w = tw * (1 - k / 5)
                td.polygon([(tx - w, t0), (tx + w, t0), (tx, t0 - th * 0.45)], fill=255)
            td.rectangle([tx - tw * 0.06, by - th * 0.05, tx + tw * 0.06, by + th * 0.1], fill=255)
        tm = np.asarray(timg, dtype=np.float32) / 255
        front = front * (1 - tm[..., None]) + colr * tm[..., None]
        alpha = np.maximum(alpha, tm)
    return front, alpha


front, alpha = hill(0.80, 0.035, 81, hexc("#1d1c3d"), front, alpha, trees=90, tree_h=0.045)
front, alpha = hill(0.885, 0.030, 91, hexc("#0e0e24"), front, alpha, trees=60, tree_h=0.075)

# leichte Abdunklung unten für die App-Leiste
shade = np.clip((ys - 0.70 * H) / (0.30 * H), 0, 1) * 0.35
front = blend(front, hexc("#05050f"), shade * alpha)
back = blend(back, hexc("#05050f"), shade)


def save(arr, a, name, size=(1920, 1080)):
    arr = np.clip(arr, 0, 255).astype(np.uint8)
    if a is None:
        im = Image.fromarray(arr, "RGB").resize(size, Image.LANCZOS)
        im.save(OUT / name, quality=92)
    else:
        a8 = (np.clip(a, 0, 1) * 255).astype(np.uint8)
        im = Image.fromarray(np.dstack([arr, a8]), "RGBA").resize(size, Image.LANCZOS)
        im.save(OUT / name, optimize=True)
    return im


b = save(back, None, "back.jpg")
f = save(front, alpha, "front.png")

# Vorschau mit Uhr
prev = b.convert("RGBA")
if FONT:
    font = ImageFont.truetype(FONT, 300)
    try:
        font.set_variation_by_axes([32, 600])
    except Exception as e:
        print("font", e)
    layer = Image.new("RGBA", prev.size, (0, 0, 0, 0))
    dr = ImageDraw.Draw(layer)
    txt = "21:45"
    bbox = dr.textbbox((0, 0), txt, font=font)
    tw = bbox[2] - bbox[0]
    dr.text(((1920 - tw) / 2 - bbox[0], 120 - bbox[1]), txt, font=font, fill=(255, 255, 255, 235))
    small = ImageFont.truetype(FONT, 34)
    d2 = "Dienstag, 27. Oktober"
    bb = dr.textbbox((0, 0), d2, font=small)
    dr.text(((1920 - (bb[2] - bb[0])) / 2, 70), d2, font=small, fill=(255, 255, 255, 200))
    prev = Image.alpha_composite(prev, layer)
prev = Image.alpha_composite(prev, f)
prev.convert("RGB").save(OUT / "preview.png")
print("ok")
