"""Release / showcase images from the QA screenshots (2560 x 2558 emulator frames, Chinese UI).

    python -X utf8 tools/make_showcase.py <qa round dir> <factory app dir>

Writes docs/cover.jpg + docs/01..06.jpg (README, 1600 wide), <app>/poster.png and the Vibe Lab pack
<app>/vibelab/: icon-1024.jpg, poster-2560x1440.jpg, shot-01..06.jpg (all 2560 x 1440). Crops only,
no stretching.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QA, APP = sys.argv[1], sys.argv[2]
DOCS = os.path.join(ROOT, "docs")
VL = os.path.join(APP, "vibelab")
os.makedirs(DOCS, exist_ok=True)
os.makedirs(VL, exist_ok=True)

# (source, top of the 1440 px window, README name)
SHOTS = [
    ("S08.png", 380, "01-plateau"),
    ("S03.png", 500, "02-intro"),
    ("S07-L3.png", 380, "03-return"),
    ("S10b-lamp.png", 400, "04-unload"),
    ("S09-from-task.png", 400, "05-quiet"),
    ("S12.png", 400, "06-ending"),
]


def crop(name, top):
    im = Image.open(os.path.join(QA, name)).convert("RGB")
    top = max(0, min(top, im.height - 1440))
    return im.crop((0, top, 2560, top + 1440))


def save_jpg(im, path, q=90):
    im.save(path, "JPEG", quality=q, optimize=True)
    print(os.path.relpath(path, ROOT) if path.startswith(ROOT) else path, im.size, os.path.getsize(path))


for i, (src, top, doc) in enumerate(SHOTS):
    im = crop(src, top)
    save_jpg(im, os.path.join(VL, "shot-%02d.jpg" % (i + 1)))
    save_jpg(im.resize((1600, 900), Image.LANCZOS), os.path.join(DOCS, doc + ".jpg"), 85)

# ---- poster: off-white column (the icon's colour) with icon + title, plateau shot on the right
FONT_SC = "C:/Windows/Fonts/msyhbd.ttc"
FONT_SC_REG = "C:/Windows/Fonts/msyh.ttc"
INK = (43, 43, 43)
ACCENT = (224, 83, 59)
BG = (239, 231, 216)
poster = Image.new("RGB", (2560, 1440), BG)
right = crop("S08.png", 240).crop((560, 0, 1940, 1440))  # 1380 x 1440 slice: the clock and the tray
poster.paste(right, (1180, 0))
d = ImageDraw.Draw(poster)
d.rectangle((0, 0, 1180, 1440), fill=BG)
icon_path = os.path.join(APP, "icon.png")
icon = Image.open(icon_path).convert("RGB").resize((300, 300), Image.LANCZOS)
poster.paste(icon, (120, 150))
title = ImageFont.truetype(FONT_SC, 150)
sub = ImageFont.truetype(FONT_SC_REG, 48)
small = ImageFont.truetype(FONT_SC_REG, 36)
badge = ImageFont.truetype(FONT_SC, 42)
d.text((120, 520), "感官过载", font=title, fill=INK)
d.text((126, 700), "Too Much", font=ImageFont.truetype(FONT_SC, 84), fill=INK)
for k, line in enumerate(["把冰箱、挂钟、塑料袋、滴水、灯管、手机", "六个声音钉在你家里，听它们一点点变大，", "再亲手把它们一个个减掉。"]):
    d.text((124, 860 + k * 70), line, font=sub, fill=INK)
d.rounded_rectangle((120, 1120, 860, 1200), radius=40, outline=ACCENT, width=5)
d.text((160, 1133), "模拟器 QA 通过 · 真机未验证", font=badge, fill=ACCENT)
d.text((124, 1260), "PICO OS 6 · 混合现实 · 不做频闪，随时「立刻安静」", font=small, fill=(90, 85, 78))
poster.save(os.path.join(APP, "poster.png"))
save_jpg(poster, os.path.join(VL, "poster-2560x1440.jpg"))
save_jpg(poster.resize((1600, 900), Image.LANCZOS), os.path.join(DOCS, "cover.jpg"), 85)
