"""Vector drawables for the panels and the source cards (24 x 24, white strokes, tinted at run time),
the adaptive launcher icon (design `appIcon`: ink house outline with three sound arcs, the outer one
orange-red, on warm off-white #EFE7D8), and its 512 px PNG export for the factory gallery.

    python -I -X utf8 tools/make_icons.py [out.png]
"""
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

HEAD = '<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="24dp"\n    android:height="24dp"\n    android:viewportWidth="24"\n    android:viewportHeight="24">\n'


def stroke(d, w=2.0):
    return '    <path\n        android:pathData="%s"\n        android:strokeColor="#FFFFFFFF"\n        android:strokeWidth="%.1f"\n        android:strokeLineCap="round"\n        android:strokeLineJoin="round"\n        android:fillColor="#00000000" />\n' % (d, w)


def fill(d):
    return '    <path\n        android:pathData="%s"\n        android:fillColor="#FFFFFFFF" />\n' % d


ICONS = {
    # fridge: tall box, door split, two handles
    "ic_fridge": [stroke("M7,2.5h10a1.5,1.5 0,0 1,1.5,1.5v16a1.5,1.5 0,0 1,-1.5,1.5h-10a1.5,1.5 0,0 1,-1.5,-1.5v-16a1.5,1.5 0,0 1,1.5,-1.5z"), stroke("M5.5,9.5h13"), stroke("M9,5.5v2"), stroke("M9,12v3")],
    # wall clock
    "ic_clock": [stroke("M12,3a9,9 0,1 1,0,18a9,9 0,1 1,0,-18z"), stroke("M12,7.5v4.8l3.2,2")],
    # plastic bag with two loop handles and a crumple line
    "ic_bag": [stroke("M5,8h14l-1.2,12.5h-11.6z"), stroke("M8.5,8c0,-3 1.5,-4.5 3.5,-4.5s3.5,1.5 3.5,4.5"), stroke("M9,13l2,1.5 2,-2 2,1.5", 1.6)],
    # water drop
    "ic_drop": [stroke("M12,3c3.5,4.5 6,7.8 6,11a6,6 0,1 1,-12,0c0,-3.2 2.5,-6.5 6,-11z"), stroke("M9.5,15a2.5,2.5 0,0 0,2.5,2.5", 1.6)],
    # tube lamp on the ceiling with buzz marks
    "ic_lamp": [stroke("M3.5,8h17"), stroke("M5,8v-2.5M19,8v-2.5"), stroke("M4.5,8h15v3h-15z"), stroke("M8,15l-1,2.5M12,15v2.8M16,15l1,2.5", 1.6)],
    # phone with a ping
    "ic_phone": [stroke("M8,2.5h8a1.5,1.5 0,0 1,1.5,1.5v16a1.5,1.5 0,0 1,-1.5,1.5h-8a1.5,1.5 0,0 1,-1.5,-1.5v-16a1.5,1.5 0,0 1,1.5,-1.5z"), stroke("M10.5,18.5h3")],
    # speaker on / off
    "ic_volume": [stroke("M4,9.5h3.5l4.5,-4v13l-4.5,-4h-3.5z"), stroke("M15.5,9a4.2,4.2 0,0 1,0,6"), stroke("M18,6.5a7.5,7.5 0,0 1,0,11")],
    "ic_volume_off": [stroke("M4,9.5h3.5l4.5,-4v13l-4.5,-4h-3.5z"), stroke("M15.5,9.5l5,5M20.5,9.5l-5,5")],
    # quiet now: speaker with a hand-flat bar
    "ic_quiet": [stroke("M3.5,9.5h3.5l4.5,-4v13l-4.5,-4h-3.5z"), stroke("M14.5,12h6.5", 2.4)],
    "ic_circle": [stroke("M12,6a6,6 0,1 1,0,12a6,6 0,1 1,0,-12z", 1.8)],
    "ic_key": [stroke("M8,9.5a4,4 0,1 1,0,5a4,4 0,0 1,0,-5z"), stroke("M11.5,12h9M17.5,12v3M20.5,12v2.5")],
    # nearer / farther: arrow towards / away from a dot (the viewer)
    "ic_nearer": [stroke("M12,4v12M7.5,11.5l4.5,4.5 4.5,-4.5"), stroke("M8,20.5h8")],
    "ic_farther": [stroke("M12,20v-12M7.5,12.5l4.5,-4.5 4.5,4.5"), stroke("M8,3.5h8")],
    "ic_info": [stroke("M12,3a9,9 0,1 1,0,18a9,9 0,1 1,0,-18z"), stroke("M12,11v5.5"), fill("M12,6.6a1.2,1.2 0,1 1,0,2.4a1.2,1.2 0,1 1,0,-2.4z")],
    "ic_reset": [stroke("M5,12a7,7 0,1 0,2.1,-5"), stroke("M5,4.5v4h4")],
    "ic_minus": [stroke("M6,12h12", 2.4)],
    "ic_plus": [stroke("M6,12h12M12,6v12", 2.4)],
}


def write_icons():
    for name, paths in ICONS.items():
        p = os.path.join(RES, "drawable", name + ".xml")
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(HEAD + "".join(paths) + "</vector>\n")
    print("icons", len(ICONS))


# ---------------------------------------------------------------------------------------------
# launcher: 108 x 108 viewport, glyph inside the 66 % safe zone (centre 54, radius ~33)
INK = "#FF2B2B2B"
ACCENT = "#FFE0533B"
BG = "#EFE7D8"


def arc_path(cx, cy, r, a0, a1):
    x0, y0 = cx + r * math.cos(math.radians(a0)), cy - r * math.sin(math.radians(a0))
    x1, y1 = cx + r * math.cos(math.radians(a1)), cy - r * math.sin(math.radians(a1))
    return "M%.2f,%.2f A%.2f,%.2f 0 0,0 %.2f,%.2f" % (x0, y0, r, r, x1, y1)


def launcher_parts():
    # house: pentagon (roof apex at 30), walls 44..80, x 30..78; door 49..59 x, 64..80 y
    house = "M30,50 L54,30 L78,50 L78,80 L72,80 L72,66 L64,66 L64,80 L30,80 Z"
    dot = (39, 74)
    arcs = [(7, INK), (13, INK), (19, ACCENT)]
    return house, dot, arcs


def write_launcher():
    house, dot, arcs = launcher_parts()
    out = ['<?xml version="1.0" encoding="utf-8"?>', "<!-- tools/make_icons.py: design appIcon (house + three sound arcs, outer one orange-red) -->",
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"', '    android:width="108dp"', '    android:height="108dp"', '    android:viewportWidth="108"', '    android:viewportHeight="108">',
           '    <path android:pathData="%s" android:strokeColor="%s" android:strokeWidth="4.2" android:strokeLineJoin="round" android:fillColor="#00000000" />' % (house, INK),
           '    <path android:pathData="M%d,%d m-3,0 a3,3 0 1,0 6,0 a3,3 0 1,0 -6,0" android:fillColor="%s" />' % (dot[0], dot[1], INK)]
    for r, col in arcs:
        out.append('    <path android:pathData="%s" android:strokeColor="%s" android:strokeWidth="3.4" android:strokeLineCap="butt" android:fillColor="#00000000" />' % (arc_path(dot[0], dot[1], r, 0, 90), col))
    out.append("</vector>")
    with open(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    with open(os.path.join(RES, "values", "colors.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <!-- app icon background: warm off-white, registered in the factory icon-colors note (design appIcon) -->\n    <color name="ic_launcher_background">%s</color>\n</resources>\n' % BG)
    print("launcher written")


def export_png(path, size=512):
    """Rasterises the launcher (full bleed square, background + glyph) with Pillow."""
    from PIL import Image, ImageDraw

    k = size / 108.0
    ss = 4
    img = Image.new("RGB", (size * ss, size * ss), BG)
    d = ImageDraw.Draw(img)
    s = k * ss
    house = [(30, 50), (54, 30), (78, 50), (78, 80), (72, 80), (72, 66), (64, 66), (64, 80), (30, 80), (30, 50)]
    d.line([(x * s, y * s) for x, y in house], fill=INK.replace("#FF", "#"), width=int(4.2 * s), joint="curve")
    for x, y in house:
        r = 2.1 * s
        d.ellipse([x * s - r, y * s - r, x * s + r, y * s + r], fill=INK.replace("#FF", "#"))
    cx, cy = 39, 74
    r = 3 * s
    d.ellipse([cx * s - r, cy * s - r, cx * s + r, cy * s + r], fill=INK.replace("#FF", "#"))
    for rr, col in [(7, INK), (13, INK), (19, ACCENT)]:
        c = col.replace("#FF", "#")
        box = [(cx - rr) * s, (cy - rr) * s, (cx + rr) * s, (cy + rr) * s]
        d.arc(box, start=270, end=360, fill=c, width=int(3.4 * s))
    img = img.resize((size, size), Image.LANCZOS)
    img.save(path)
    print("png", path)


if __name__ == "__main__":
    write_icons()
    write_launcher()
    if len(sys.argv) > 1:
        export_png(sys.argv[1])
