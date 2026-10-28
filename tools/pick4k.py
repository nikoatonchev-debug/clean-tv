"""Vorauswahl: welche Vorschaubilder eignen sich für "Uhr hinter dem Motiv"?

Gut ist: oben viel Himmel, darunter ein Motiv (Gipfel, Felsen, Bäume, Stadt),
dessen höchster Punkt etwa mittig im oberen Drittel bis zur Mitte liegt.
Schreibt candidates.json und Übersichtsbilder sheet_*.jpg.
"""
import json
import sys
from pathlib import Path

import numpy as np
import torch
from PIL import Image, ImageDraw, ImageFont
from transformers import SegformerForSemanticSegmentation

D = Path(sys.argv[1])
FONT = sys.argv[2]
model = SegformerForSemanticSegmentation.from_pretrained("nvidia/segformer-b5-finetuned-ade-640-640").eval()
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)
items = json.load(open(D / "items.json", encoding="utf-8"))
cands = []
for i, it in enumerate(items):
    p = D / "thumbs" / f"{i:03d}.jpg"
    try:
        img = Image.open(p).convert("RGB")
    except Exception:
        continue
    w, h = 512, int(round(512 * img.height / img.width / 32)) * 32
    x = (np.asarray(img.resize((w, h), Image.BICUBIC), dtype=np.float32) / 255 - MEAN) / STD
    with torch.no_grad():
        lg = model(pixel_values=torch.from_numpy(x.transpose(2, 0, 1))[None]).logits
    sky = torch.softmax(lg, 1)[0, 2].numpy()
    sky = np.asarray(Image.fromarray((sky * 255).astype(np.uint8)).resize((w, h)), dtype=np.float32) / 255
    solid = sky < 0.5
    has = solid.any(0)
    sky_y = np.where(has, solid.argmax(0), h) / h
    # Bildausschnitt 16:9 aus der Mitte
    x0, x1 = int(w * 0.25), int(w * 0.75)
    peak = float(sky_y[x0:x1].min())
    px = (x0 + int(np.argmin(sky_y[x0:x1]))) / w
    top_sky = float(sky[: int(h * 0.12)].mean())
    # Gipfel soll deutlich höher sein als die Ränder (sonst nur gerade Horizontlinie)
    edges = float(np.median(np.r_[sky_y[: int(w * 0.15)], sky_y[-int(w * 0.15):]]))
    prominence = edges - peak
    ok = top_sky > 0.8 and 0.12 < peak < 0.5 and prominence > 0.08
    if ok:
        cands.append(dict(i=i, peak=round(peak, 3), px=round(px, 3), prom=round(prominence, 3)))
    print(i, ok, round(top_sky, 2), round(peak, 2), round(prominence, 2), flush=True)

json.dump(cands, open(D / "candidates.json", "w"), indent=0)
f = ImageFont.truetype(FONT, 22)
per = 40
for s in range(0, len(cands), per):
    sheet = Image.new("RGB", (8 * 240, 5 * 150), "black")
    d = ImageDraw.Draw(sheet)
    for k, c in enumerate(cands[s:s + per]):
        im = Image.open(D / "thumbs" / f"{c['i']:03d}.jpg").convert("RGB")
        im.thumbnail((236, 146))
        x, y = (k % 8) * 240, (k // 8) * 150
        sheet.paste(im, (x + 2, y + 2))
        d.text((x + 6, y + 4), str(c["i"]), font=f, fill="yellow", stroke_width=2, stroke_fill="black")
    sheet.save(D / f"sheet_{s // per}.jpg", quality=85)
print("candidates", len(cands))
