"""Draws the launcher icon (a vector) and the widget picker previews (PNGs).

The previews follow the shapes in Shapes.kt. Run from the repo root:
    python tools/art.py
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
SS = 4  # supersampling


def stamp_vector_path(x0, y0, w, h, r):
    """The stamp outline as SVG path data: semicircle holes along each edge, quarter ones in the corners."""
    def stops(length):
        n = max(2, round(length / (r * 3.6)))
        return [length * i / n for i in range(n + 1)]

    d = []
    xs, ys = stops(w), stops(h)
    a = lambda x, y: f"A{r:.3f},{r:.3f} 0 0 0 {x:.3f},{y:.3f}"
    d.append(f"M{x0 + r:.3f},{y0:.3f}")
    for x in xs[1:-1]:  # top, left to right
        d += [f"L{x0 + x - r:.3f},{y0:.3f}", a(x0 + x + r, y0)]
    d += [f"L{x0 + w - r:.3f},{y0:.3f}", a(x0 + w, y0 + r)]
    for y in ys[1:-1]:  # right, downwards
        d += [f"L{x0 + w:.3f},{y0 + y - r:.3f}", a(x0 + w, y0 + y + r)]
    d += [f"L{x0 + w:.3f},{y0 + h - r:.3f}", a(x0 + w - r, y0 + h)]
    for x in reversed(xs[1:-1]):  # bottom, right to left
        d += [f"L{x0 + x + r:.3f},{y0 + h:.3f}", a(x0 + x - r, y0 + h)]
    d += [f"L{x0 + r:.3f},{y0 + h:.3f}", a(x0, y0 + h - r)]
    for y in reversed(ys[1:-1]):  # left, upwards
        d += [f"L{x0:.3f},{y0 + y + r:.3f}", a(x0, y0 + y - r)]
    d += [f"L{x0:.3f},{y0 + r:.3f}", a(x0 + r, y0), "Z"]
    return " ".join(d)


def icon():
    # 108 x 108 adaptive icon; the stamp sits inside the 66 dp safe zone.
    w, h, r = 46, 56, 2.4
    x0, y0 = (108 - w) / 2, (108 - h) / 2
    outline = stamp_vector_path(x0, y0, w, h, r)
    m = 6.0
    wx0, wy0, wx1, wy1 = x0 + m, y0 + m, x0 + w - m, y0 + h - m
    window = f"M{wx0},{wy0} H{wx1} V{wy1} H{wx0} Z"
    # A sun and a hill in the window, cut out of it.
    sun = f"M{wx1 - 8.5},{wy0 + 9} a3.5,3.5 0 1,0 7,0 a3.5,3.5 0 1,0 -7,0 Z"
    hill = f"M{wx0},{wy1} L{wx0},{wy1 - 12} Q{wx0 + 10},{wy1 - 22} {wx0 + 20},{wy1 - 13} Q{wx0 + 26},{wy1 - 8} {wx1},{wy1 - 14} L{wx1},{wy1} Z"
    fg = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Drawn by tools/art.py: a postage stamp, perforated, with a sun over a hill. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#FFFDF8" android:pathData="{outline}" />
    <path android:fillColor="#C8402F" android:pathData="{window}" />
    <path android:fillColor="#FFFDF8" android:pathData="{sun}" />
    <path android:fillColor="#F3E9D2" android:pathData="{hill}" />
</vector>
"""
    (RES / "drawable/ic_launcher_foreground.xml").write_text(fg, encoding="utf-8")
    mono = fg.replace('"#C8402F"', '"#00000000"').replace('"#F3E9D2"', '"#FFFFFFFF"').replace('"#FFFDF8"', '"#FFFFFFFF"')
    (RES / "drawable/ic_launcher_monochrome.xml").write_text(mono, encoding="utf-8")
    (RES / "values/ic_launcher_colors.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#2B3A55</color>\n</resources>\n',
        encoding="utf-8",
    )
    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""
    for name in ("ic_launcher", "ic_launcher_round"):
        (RES / f"mipmap-anydpi-v26/{name}.xml").write_text(adaptive, encoding="utf-8")


# ------------------------------------------------------------------ previews

def landscape(w, h):
    """A stand-in photo: an evening sky, a sun and two hills."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    top, low = (63, 94, 150), (233, 160, 122)
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(round(top[i] + (low[i] - top[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = c
    d = ImageDraw.Draw(img)
    d.ellipse((w * 0.58, h * 0.30, w * 0.80, h * 0.52), fill=(255, 232, 196))
    d.polygon([(0, h)] + [(x, h * 0.70 - math.sin(x / w * 3.1) * h * 0.14) for x in range(0, w + 1, max(1, w // 60))] + [(w, h)], fill=(122, 92, 120))
    d.polygon([(0, h)] + [(x, h * 0.84 - math.sin(x / w * 2.2 + 1.4) * h * 0.08) for x in range(0, w + 1, max(1, w // 60))] + [(w, h)], fill=(59, 47, 70))
    return img


def save(img, name, w, h):
    img.resize((w, h), Image.LANCZOS).save(RES / f"drawable-nodpi/{name}.png", optimize=True)


def preview_photo():
    w, h = 300, 300
    W, H = w * SS, h * SS
    hole = max(2.6, min(4.2, 150 * 0.024)) * 2 * SS  # 2 px a dp in the preview
    mask = Image.new("L", (W, H), 255)
    md = ImageDraw.Draw(mask)
    for length, horizontal in ((W, True), (H, False)):
        n = max(2, round(length / (hole * 3.6)))
        for i in range(n + 1):
            p = length * i / n
            for edge in (0, H if horizontal else W):
                cx, cy = (p, edge) if horizontal else (edge, p)
                md.ellipse((cx - hole, cy - hole, cx + hole, cy + hole), fill=0)
    img = Image.new("RGBA", (W, H), (255, 253, 248, 255))
    m = int(hole * 2.7)
    img.paste(landscape(W - 2 * m, H - 2 * m), (m, m))
    img.putalpha(mask)
    save(img, "widget_preview_photo", w, h)


def preview_clock():
    w, h = 300, 300
    W, H = w * SS, h * SS
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((0, 0, W - 1, H - 1), fill=(27, 27, 29, 255))
    ink = (242, 238, 232, 255)
    rim = W * 0.04
    d.ellipse((rim, rim, W - rim, H - rim), outline=(242, 238, 232, 46), width=SS * 2)
    try:
        big = ImageFont.truetype("C:/Windows/Fonts/segoeuil.ttf", int(W * 0.24))
        small = ImageFont.truetype("C:/Windows/Fonts/seguisb.ttf", int(W * 0.055))
    except OSError:
        big = small = ImageFont.load_default()
    d.text((W / 2, H * 0.47), "10:09", font=big, fill=ink, anchor="mm")
    d.text((W / 2, H * 0.64), "MON 26 OCT", font=small, fill=ink, anchor="mm")
    save(img, "widget_preview_clock", w, h)


def preview_apps():
    """A film strip with four apps, one to a frame (Shape.film in Shapes.kt)."""
    h = 150
    band, gap = h * 0.17, h * 0.17 * 0.3
    frame = h - 2 * band
    w = round(4 * frame + 5 * gap)
    W, H, B, G, F = w * SS, h * SS, band * SS, gap * SS, frame * SS
    mask = Image.new("L", (W, H), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle((0, 0, W - 1, H - 1), radius=3 * SS, fill=255)
    hl, hs = B * 0.46, B * 0.42
    n = max(2, round(W / (B * 0.95)))
    for i in range(n):
        a = W * (i + 0.5) / n
        for c in (B / 2, H - B / 2):
            md.rounded_rectangle((a - hl / 2, c - hs / 2, a + hl / 2, c + hs / 2), radius=hs * 0.28, fill=0)
    img = Image.new("RGBA", (W, H), (27, 27, 29, 255))
    d = ImageDraw.Draw(img)
    colours = [(200, 64, 47), (35, 50, 79), (169, 191, 161), (226, 177, 60)]
    for i, c in enumerate(colours):
        x0 = G + i * (F + G)
        d.rounded_rectangle((x0, B, x0 + F, H - B), radius=2 * SS, fill=(55, 55, 56, 255))
        size = F * 0.66
        cx, cy = x0 + F / 2, H / 2
        d.rounded_rectangle((cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), radius=size * 0.3, fill=c + (255,))
    img.putalpha(mask)
    save(img, "widget_preview_apps", w, h)


if __name__ == "__main__":
    icon()
    preview_photo()
    preview_clock()
    preview_apps()
    print("ok")
