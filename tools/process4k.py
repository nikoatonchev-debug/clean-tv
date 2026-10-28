"""Echte Bergfotos -> Hintergründe mit Uhr hinter dem Berg.

Für jedes Foto: Himmel per Bilderkennung (SegFormer, ADE20K-Klasse "sky") finden,
Kante mit einem Guided Filter scharf ziehen, so zuschneiden, dass der Gipfel
mittig unter der Uhr steht, und als back/front-WebP plus Vorschaubild speichern.

Aufruf: <venv-python> photos.py <photos-ordner> <assets-ordner> <inter.ttf>
"""
import json
import re
import sys
from pathlib import Path

import numpy as np
import torch
from PIL import Image, ImageDraw, ImageFont
from transformers import SegformerForSemanticSegmentation

SRC, DST, FONT = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
ONLY = sys.argv[4].split(",") if len(sys.argv) > 4 else None
OW, OH = 3840, 2160

model = SegformerForSemanticSegmentation.from_pretrained("nvidia/segformer-b5-finetuned-ade-640-640").eval()
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)


def sky_prob(img):
    w, h = img.size
    sw = 1024
    sh = int(round(sw * h / w / 32)) * 32
    x = (np.asarray(img.resize((sw, sh), Image.BICUBIC), dtype=np.float32) / 255 - MEAN) / STD
    t = torch.from_numpy(x.transpose(2, 0, 1))[None]
    with torch.no_grad():
        logits = model(pixel_values=t).logits
    # erst bei kleiner Größe rechnen, dann nur die Himmel-Karte vergrößern (spart viel Speicher)
    logits = torch.nn.functional.interpolate(logits, size=(sh, sw), mode="bilinear", align_corners=False)
    p = torch.softmax(logits, 1)[0, 2].numpy()
    return np.asarray(Image.fromarray(p.astype(np.float32), "F").resize((w, h), Image.BILINEAR), dtype=np.float32)


def box(a, r):
    c = np.cumsum(np.cumsum(np.pad(a, ((r + 1, r), (r + 1, r)), mode="edge"), 0), 1)
    return (c[2 * r + 1:, 2 * r + 1:] - c[:-2 * r - 1, 2 * r + 1:] - c[2 * r + 1:, :-2 * r - 1] + c[:-2 * r - 1, :-2 * r - 1]) / (2 * r + 1) ** 2


def guided(I, p, r, eps):
    mI, mp = box(I, r), box(p, r)
    a = (box(I * p, r) - mI * mp) / (box(I * I, r) - mI * mI + eps)
    b = mp - a * mI
    return box(a, r) * I + box(b, r)


def process(path):
    img = Image.open(path).convert("RGB")
    # erst auf Zielgröße bringen (bedeckt 1920x1080, etwas Reserve zum Verschieben)
    s = max(OW / img.width, OH / img.height) * 1.0
    img = img.resize((round(img.width * s), round(img.height * s)), Image.LANCZOS)
    fg = 1 - sky_prob(img)
    gray = np.asarray(img.convert("L"), dtype=np.float32) / 255
    fg = guided(gray, fg, 8, 1e-3)
    alpha = np.clip((fg - 0.42) / 0.16, 0, 1)

    W, H = img.size
    # Gipfel = höchster Punkt im mittleren Bereich
    solid = alpha > 0.5
    has = solid.any(0)
    sky_y = np.where(has, solid.argmax(0), H)
    x0, x1 = int(W * 0.2), int(W * 0.8)
    px = x0 + int(np.argmin(sky_y[x0:x1]))
    py = int(sky_y[px])
    cx = int(np.clip(px - OW / 2, 0, W - OW))
    cy = int(np.clip(py - 0.27 * OH, 0, H - OH))
    crop = (cx, cy, cx + OW, cy + OH)
    back = img.crop(crop)
    a = Image.fromarray((alpha * 255).astype(np.uint8)).crop(crop)
    peak = (py - cy) / OH

    # Zu wenig Himmel über dem Gipfel: Bild nach unten schieben und den Himmel
    # oben aus seiner obersten Zeile weiterziehen (zum Zenit hin etwas dunkler).
    pad = int(min(max(0.0, 0.27 - peak), 0.16) * OH)
    if pad > 8:
        arr = np.asarray(back, dtype=np.float32)
        top = arr[:24].mean(0)
        top = np.asarray(Image.fromarray(np.clip(top[None], 0, 255).astype(np.uint8)).resize((OW // 160, 1), Image.BOX).resize((OW, 1), Image.BILINEAR), dtype=np.float32)[0]
        t = np.linspace(1, 0, pad, dtype=np.float32)[:, None, None]  # 1 = ganz oben
        band = top[None] * (1 - 0.18 * t)
        new = np.concatenate([band, arr[:OH - pad]], 0)
        # Übergang weich machen
        seam = 80
        w = np.linspace(0, 1, seam, dtype=np.float32)[:, None, None]
        new[pad:pad + seam] = top[None] * (1 - w) + arr[:seam] * w
        back = Image.fromarray(np.clip(new, 0, 255).astype(np.uint8))
        aa = np.asarray(a)
        a = Image.fromarray(np.concatenate([np.zeros((pad, OW), np.uint8), aa[:OH - pad]], 0))
        peak += pad / OH

    # Ziffern sind ~0.2 hoch; der Gipfel soll etwa das untere Drittel verdecken
    # nicht zu weit oben: manche Fernseher schneiden die Ränder ab
    clock_top = float(np.clip(peak - 0.14, 0.12, 0.22))
    # Helligkeit hinter der Uhr -> Schrift hell oder dunkel
    region = np.asarray(back.convert("L"), dtype=np.float32)[int(OH * clock_top):int(OH * (clock_top + 0.2)), int(OW * 0.3):int(OW * 0.7)]
    lum = float(region.mean() / 255)
    behind = (clock_top + 0.2 - peak) / 0.2 < 0.5
    return back, a, clock_top, lum, peak, behind


def credit(meta):
    artist = re.sub(r"\s+", " ", meta.get("artist", "")).strip()
    return f"{artist} · {meta.get('lic', '')} · Wikimedia Commons".strip(" ·")


items = json.load(open(SRC / "items.json", encoding="utf-8"))
DST.mkdir(parents=True, exist_ok=True)
font = ImageFont.truetype(FONT, 600)
font.set_variation_by_axes([32, 600])
walls = []
sheet = []
for f in sorted((SRC / "full").glob("*.jpg")):
    i = int(f.stem)
    wid = f"p{i:03d}"
    if ONLY and wid not in ONLY:
        continue
    back, a, top, lum, peak, behind = process(f)
    front = back.copy()
    front.putalpha(a)
    back.save(DST / f"{wid}_back.webp", quality=90, method=5)
    # Maske in Bildschirmgröße (Fernseher ist Full HD) - Weiß mit Transparenz
    m = a.resize((1920, 1080), Image.LANCZOS)
    Image.merge("LA", (Image.new("L", m.size, 255), m)).save(DST / f"{wid}_mask.png", optimize=True)
    thumb = back.resize((384, 216), Image.LANCZOS)
    thumb.save(DST / f"{wid}_thumb.jpg", quality=84)
    walls.append(dict(id=wid, back=f"{wid}_back.webp", front=f"{wid}_mask.png", mask=True, clockTop=round(top, 4) if behind else 0.12, dark=lum > 0.72, behind=bool(behind), credit=credit(items[i])))
    # Vorschau mit Uhr zur Kontrolle
    prev = back.convert("RGBA")
    layer = Image.new("RGBA", prev.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    bb = d.textbbox((0, 0), "21:45", font=font)
    col = (25, 28, 45, 225) if lum > 0.72 else (255, 255, 255, 235)
    d.text(((OW - (bb[2] - bb[0])) / 2 - bb[0], OH * top - bb[1]), "21:45", font=font, fill=col)
    prev = Image.alpha_composite(Image.alpha_composite(prev, layer), front) if behind else Image.alpha_composite(prev, layer)
    sheet.append((wid, prev.convert("RGB").resize((480, 270), Image.LANCZOS)))
    print(wid, "behind", behind, "peak", round(peak, 3), "top", round(top, 3), "lum", round(lum, 2), flush=True)

json.dump(walls, open(DST / "photos.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
cols = 4
rows = (len(sheet) + cols - 1) // cols
img = Image.new("RGB", (cols * 484, rows * 274), "black")
d = ImageDraw.Draw(img)
small = ImageFont.truetype(FONT, 22)
for k, (wid, im) in enumerate(sheet):
    x, y = (k % cols) * 484, (k // cols) * 274
    img.paste(im, (x + 2, y + 2))
    d.text((x + 8, y + 6), wid, font=small, fill="yellow", stroke_width=2, stroke_fill="black")
img.save(SRC / "check.jpg", quality=85)
