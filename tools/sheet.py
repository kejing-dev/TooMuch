"""Contact sheet of screenshots: python -X utf8 tools/sheet.py OUT.png shot1.png shot2.png ... (3 per row, labelled)."""
import os
import sys

from PIL import Image, ImageDraw

out, files = sys.argv[1], sys.argv[2:]
W = 640
cols = 3
rows = (len(files) + cols - 1) // cols
sheet = Image.new("RGB", (W * cols, (W + 28) * rows), "white")
d = ImageDraw.Draw(sheet)
for i, f in enumerate(files):
    im = Image.open(f).convert("RGB").resize((W, W))
    x, y = (i % cols) * W, (i // cols) * (W + 28)
    sheet.paste(im, (x, y + 28))
    d.text((x + 6, y + 6), os.path.basename(f), fill="black")
sheet.save(out)
print(out, len(files))
