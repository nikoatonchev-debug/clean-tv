"""Banner (320x180) und App-Symbol aus dem Bergbild erzeugen."""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

src, res, font = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
back = Image.open(src / "back.jpg").convert("RGBA")
front = Image.open(src / "front.png")
full = Image.alpha_composite(back, front)

# Banner: Bildausschnitt um den Gipfel + Schrift
ban = full.crop((480, 160, 1440, 700)).resize((320, 180), Image.LANCZOS)
d = ImageDraw.Draw(ban)
f = ImageFont.truetype(font, 34)
f.set_variation_by_axes([32, 650])
t = "Clean TV"
bb = d.textbbox((0, 0), t, font=f)
d.text(((320 - (bb[2] - bb[0])) / 2, 18), t, font=f, fill=(255, 255, 255, 240))
(res / "drawable-xhdpi").mkdir(parents=True, exist_ok=True)
ban.convert("RGB").save(res / "drawable-xhdpi" / "banner.png")

# Symbol: runder Ausschnitt des Gipfels
ic = full.crop((660, 180, 1260, 780)).resize((192, 192), Image.LANCZOS)
mask = Image.new("L", (192, 192), 0)
ImageDraw.Draw(mask).ellipse((0, 0, 191, 191), fill=255)
out = Image.new("RGBA", (192, 192), (0, 0, 0, 0))
out.paste(ic, (0, 0), mask)
(res / "mipmap-xhdpi").mkdir(parents=True, exist_ok=True)
out.save(res / "mipmap-xhdpi" / "ic_launcher.png")
print("ok")
