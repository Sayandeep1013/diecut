"""Draws the README's pictures of the widgets (docs/images), on a stand-in wallpaper.

The shapes follow Shapes.kt and the colours Palettes in Spec.kt, so the
pictures show what the app draws. Run from the repo root:
    python tools/showcase.py
"""
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = Path(__file__).resolve().parent.parent / "docs/images"
SS = 3  # supersampling
U = 2.4  # px a dp in the pictures

PAPER = {
    "paper": ((255, 253, 248), (28, 27, 26)),
    "cream": ((243, 233, 210), (59, 47, 37)),
    "ink": ((27, 27, 29), (242, 238, 232)),
    "red": ((200, 64, 47), (255, 244, 236)),
    "navy": ((35, 50, 79), (241, 233, 218)),
    "sage": ((169, 191, 161), (34, 48, 31)),
    "blush": ((239, 198, 193), (74, 37, 34)),
    "mustard": ((226, 177, 60), (43, 34, 16)),
    "kraft": ((201, 164, 122), (46, 33, 22)),
}


def font(name, size):
    try:
        return ImageFont.truetype(f"C:/Windows/Fonts/{name}", int(size))
    except OSError:
        return ImageFont.load_default()


def light(size): return font("segoeuil.ttf", size)
def regular(size): return font("segoeui.ttf", size)
def semibold(size): return font("seguisb.ttf", size)
def italic(size): return font("georgiai.ttf", size)


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


# ------------------------------------------------------------------ wallpaper

def wallpaper(w, h, seed=3):
    """A soft evening wallpaper with a few blurred blobs, so cut-outs read as holes."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    a, b, c = (38, 52, 92), (110, 78, 140), (224, 150, 120)
    for y in range(h):
        for x in range(0, w, 1):
            t = (x / w * 0.45 + y / h * 0.55)
            col = mix(a, b, t * 2) if t < 0.5 else mix(b, c, (t - 0.5) * 2)
            px[x, y] = col
    blobs = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(blobs)
    rnd = random.Random(seed)
    for _ in range(7):
        r = rnd.uniform(0.12, 0.3) * min(w, h)
        x, y = rnd.uniform(0, w), rnd.uniform(0, h)
        col = rnd.choice([(255, 196, 150), (140, 120, 220), (90, 170, 200), (250, 140, 170)])
        d.ellipse((x - r, y - r, x + r, y + r), fill=col + (70,))
    blobs = blobs.filter(ImageFilter.GaussianBlur(min(w, h) * 0.08))
    img.paste(blobs, (0, 0), blobs)
    return img


# --------------------------------------------------------------------- shapes
# Each returns (mask, window mask, safe box) at supersampled size W x H.

def _blank(W, H, v=0):
    m = Image.new("L", (W, H), v)
    return m, ImageDraw.Draw(m)


def stamp(W, H):
    s = min(W, H) / SS / U
    hole = max(2.6, min(4.2, s * 0.024)) * U * SS
    m, d = _blank(W, H, 255)
    for length, horiz in ((W, True), (H, False)):
        n = max(2, round(length / (hole * 3.6)))
        for i in range(n + 1):
            p = length * i / n
            for e in (0, H if horiz else W):
                cx, cy = (p, e) if horiz else (e, p)
                d.ellipse((cx - hole, cy - hole, cx + hole, cy + hole), fill=0)
    g = hole * 2.7
    box = (g, g, W - g, H - g)
    win, wd = _blank(W, H)
    wd.rectangle(box, fill=255)
    return m, win, box


def ticket(W, H):
    s = min(W, H)
    corner, notch, mg = s * 0.09, s * 0.11, s * 0.05

    def cut(inset):
        m, d = _blank(W, H)
        d.rectangle((inset, inset, W - inset, H - inset), fill=255)
        for cx, cy in ((0, 0), (W, 0), (0, H), (W, H)):
            r = corner + inset
            d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=0)
        for cx in (0, W):
            r = notch + inset
            d.ellipse((cx - r, H / 2 - r, cx + r, H / 2 + r), fill=0)
        return m
    return cut(0), cut(mg), (notch + 2 * mg, mg * 1.5, W - notch - 2 * mg, H - mg * 1.5)


def film(W, H, count):
    band = H * 0.17
    gap = band * 0.3
    m, d = _blank(W, H)
    d.rounded_rectangle((0, 0, W - 1, H - 1), radius=1.5 * U * SS, fill=255)
    hl, hs = band * 0.46, band * 0.42
    n = max(2, round(W / (band * 0.95)))
    for i in range(n):
        a = W * (i + 0.5) / n
        for c in (band / 2, H - band / 2):
            d.rounded_rectangle((a - hl / 2, c - hs / 2, a + hl / 2, c + hs / 2), radius=hs * 0.28, fill=0)
    fl = (W - gap * (count + 1)) / count
    frames = [(gap + i * (fl + gap), band, gap + i * (fl + gap) + fl, H - band) for i in range(count)]
    win, wd = _blank(W, H)
    for f in frames:
        wd.rounded_rectangle(f, radius=2 * U * SS, fill=255)
    return m, win, frames


def circle(W, H):
    s = min(W, H)
    m, d = _blank(W, H)
    d.ellipse(((W - s) / 2, (H - s) / 2, (W + s) / 2, (H + s) / 2), fill=255)
    rim = s * 0.04
    box = ((W - s) / 2 + rim, (H - s) / 2 + rim, (W + s) / 2 - rim, (H + s) / 2 - rim)
    win, wd = _blank(W, H)
    wd.ellipse(box, fill=255)
    k = (box[2] - box[0]) * (1 - 1 / math.sqrt(2)) / 2
    return m, win, (box[0] + k, box[1] + k, box[2] - k, box[3] - k)


def superellipse(d, box, n=5.0):
    x0, y0, x1, y1 = box
    a, b = (x1 - x0) / 2, (y1 - y0) / 2
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    pts = []
    for i in range(360):
        t = 2 * math.pi * i / 360
        c, s = math.cos(t), math.sin(t)
        pts.append((cx + a * math.copysign(abs(c) ** (2 / n), c), cy + b * math.copysign(abs(s) ** (2 / n), s)))
    d.polygon(pts, fill=255)


def squircle(W, H):
    rim = min(W, H) * 0.04
    m, d = _blank(W, H)
    superellipse(d, (0, 0, W, H))
    win, wd = _blank(W, H)
    box = (rim, rim, W - rim, H - rim)
    superellipse(wd, box)
    k = min(W, H) * 0.08
    return m, win, (box[0] + k, box[1] + k, box[2] - k, box[3] - k)


def polaroid(W, H):
    side = min(W, H) * 0.065
    bottom = max(side * 2.6, H * 0.2)
    m, d = _blank(W, H)
    d.rounded_rectangle((0, 0, W - 1, H - 1), radius=2 * U * SS, fill=255)
    box = (side, side, W - side, H - bottom)
    win, wd = _blank(W, H)
    wd.rectangle(box, fill=255)
    return m, win, box


def frame(W, H):
    m, d = _blank(W, H)
    d.rounded_rectangle((0, 0, W - 1, H - 1), radius=2 * U * SS, fill=255)
    s = min(W, H)
    b, mat = s * 0.065, s * 0.085
    box = (b + mat, b + mat, W - b - mat, H - b - mat)
    win, wd = _blank(W, H)
    wd.rectangle(box, fill=255)
    return m, win, box


def arch(W, H):
    mg = min(W, H) * 0.045
    top = min(W / 2, H * 0.55)

    def shape(x0, y0, x1, y1, t):
        m, d = _blank(W, H)
        d.ellipse((x0, y0, x1, y0 + 2 * t), fill=255)
        d.rectangle((x0, y0 + t, x1, y1), fill=255)
        return m
    return shape(0, 0, W, H, top), shape(mg, mg, W - mg, H - mg, top - mg), (mg * 1.5, mg + (top - mg) * 0.4, W - mg * 1.5, H - mg * 1.5)


def scallop(W, H):
    s = min(W, H)
    bump, radius = s * 0.055, s * 0.28
    x0, y0, x1, y1, r = bump, bump, W - bump, H - bump, radius
    m, d = _blank(W, H)
    d.rounded_rectangle((x0, y0, x1, y1), radius=r, fill=255)
    sw, sh = (x1 - x0) - 2 * r, (y1 - y0) - 2 * r
    arc = math.pi * r / 2
    total = 2 * sw + 2 * sh + 4 * arc
    segs = [
        (sw, "l", (x0 + r, y0, 1, 0)), (arc, "a", (x1 - r, y0 + r, -math.pi / 2)),
        (sh, "l", (x1, y0 + r, 0, 1)), (arc, "a", (x1 - r, y1 - r, 0)),
        (sw, "l", (x1 - r, y1, -1, 0)), (arc, "a", (x0 + r, y1 - r, math.pi / 2)),
        (sh, "l", (x0, y1 - r, 0, -1)), (arc, "a", (x0 + r, y0 + r, math.pi)),
    ]

    def at(t):
        for length, kind, v in segs:
            if t <= length:
                if kind == "l":
                    return v[0] + v[2] * t, v[1] + v[3] * t
                return v[0] + r * math.cos(v[2] + t / r), v[1] + r * math.sin(v[2] + t / r)
            t -= length
        return x0 + r, y0
    n = max(8, round(total / (bump * 1.75)))
    for i in range(n):
        cx, cy = at(total * i / n)
        d.ellipse((cx - bump, cy - bump, cx + bump, cy + bump), fill=255)
    inset = bump * 1.6
    box = (inset, inset, W - inset, H - inset)
    win, wd = _blank(W, H)
    wd.rounded_rectangle(box, radius=max(0, radius - (inset - bump)), fill=255)
    k = (radius - (inset - bump)) * 0.3
    return m, win, (box[0] + k, box[1] + k, box[2] - k, box[3] - k)


SHAPES = {
    "Stamp": stamp, "Film": None, "Polaroid": polaroid, "Frame": frame, "Circle": circle,
    "Squircle": squircle, "Ticket": ticket, "Scallop": scallop, "Arch": arch,
}


# -------------------------------------------------------------------- content

def photo(W, H, kind=0):
    """A stand-in photo: an evening sky over hills, or a sea at noon."""
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    skies = [((63, 94, 150), (233, 160, 122)), ((96, 170, 214), (220, 236, 240)), ((40, 40, 80), (200, 110, 130))]
    top, low = skies[kind % len(skies)]
    for y in range(H):
        d.line([(0, y), (W, y)], fill=mix(top, low, y / H))
    if kind % 3 == 1:
        r = min(W, H) * 0.09
        d.ellipse((W * 0.75 - r, H * 0.23 - r, W * 0.75 + r, H * 0.23 + r), fill=(255, 250, 225))
        d.rectangle((0, H * 0.58, W, H), fill=(40, 120, 160))
        for i in range(6):
            y = H * (0.62 + i * 0.06)
            d.line([(W * 0.1, y), (W * 0.9, y)], fill=(90, 170, 200), width=max(1, int(H * 0.008)))
        d.polygon([(0, H), (0, H * 0.82), (W * 0.35, H * 0.76), (W * 0.6, H), ], fill=(232, 210, 160))
    else:
        r = min(W, H) * 0.11
        d.ellipse((W * 0.69 - r, H * 0.39 - r, W * 0.69 + r, H * 0.39 + r), fill=(255, 232, 196))
        step = max(1, W // 80)
        d.polygon([(0, H)] + [(x, H * 0.68 - math.sin(x / W * 3.1) * H * 0.14) for x in range(0, W + 1, step)] + [(W, H)], fill=(122, 92, 120))
        d.polygon([(0, H)] + [(x, H * 0.84 - math.sin(x / W * 2.2 + 1.4) * H * 0.08) for x in range(0, W + 1, step)] + [(W, H)], fill=(59, 47, 70))
    return img


ICONS = [((48, 120, 230), "phone"), ((40, 170, 110), "chat"), ((235, 80, 60), "play"), ((250, 190, 50), "camera"),
         ((130, 90, 220), "music"), ((30, 30, 34), "clock"), ((240, 120, 160), "heart"), ((60, 180, 200), "map")]


def icon(d, cx, cy, size, i, mono=None):
    """A stand-in app icon: a round tile with a simple glyph (mono: a glyph in ink on a soft disc)."""
    col, glyph = ICONS[i % len(ICONS)]
    r = size / 2
    if mono:
        disc, ink = mono
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=disc)
        fg = ink
    else:
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=col)
        fg = (255, 255, 255)
    g = r * 0.48
    w = max(2, int(r * 0.13))
    if glyph == "phone":
        d.arc((cx - g * 0.9, cy - g * 0.9, cx + g * 0.9, cy + g * 0.9), 135, 315, fill=fg, width=int(w * 2.2))
        for a in (135, 315):
            t = math.radians(a)
            ex, ey = cx + g * 0.9 * math.cos(t), cy + g * 0.9 * math.sin(t)
            d.ellipse((ex - g * 0.36, ey - g * 0.36, ex + g * 0.36, ey + g * 0.36), fill=fg)
    elif glyph == "chat":
        d.rounded_rectangle((cx - g, cy - g * 0.75, cx + g, cy + g * 0.55), radius=g * 0.4, fill=fg)
        d.polygon([(cx - g * 0.4, cy + g * 0.4), (cx - g * 0.6, cy + g * 0.95), (cx, cy + g * 0.45)], fill=fg)
    elif glyph == "play":
        d.polygon([(cx - g * 0.55, cy - g * 0.8), (cx + g * 0.85, cy), (cx - g * 0.55, cy + g * 0.8)], fill=fg)
    elif glyph == "camera":
        d.rounded_rectangle((cx - g, cy - g * 0.65, cx + g, cy + g * 0.7), radius=g * 0.25, outline=fg, width=w)
        d.ellipse((cx - g * 0.38, cy - g * 0.3, cx + g * 0.38, cy + g * 0.46), outline=fg, width=w)
    elif glyph == "music":
        d.ellipse((cx - g * 0.8, cy + g * 0.2, cx - g * 0.1, cy + g * 0.85), fill=fg)
        d.line([(cx - g * 0.15, cy + g * 0.5), (cx - g * 0.15, cy - g * 0.8), (cx + g * 0.7, cy - g * 0.6)], fill=fg, width=w)
    elif glyph == "clock":
        d.ellipse((cx - g, cy - g, cx + g, cy + g), outline=fg, width=w)
        d.line([(cx, cy), (cx, cy - g * 0.6), ], fill=fg, width=w)
        d.line([(cx, cy), (cx + g * 0.45, cy + g * 0.2)], fill=fg, width=w)
    elif glyph == "heart":
        d.ellipse((cx - g, cy - g * 0.7, cx, cy + g * 0.2), fill=fg)
        d.ellipse((cx, cy - g * 0.7, cx + g, cy + g * 0.2), fill=fg)
        d.polygon([(cx - g * 0.95, cy - g * 0.1), (cx + g * 0.95, cy - g * 0.1), (cx, cy + g * 0.9)], fill=fg)
    else:
        d.polygon([(cx, cy - g), (cx + g * 0.7, cy + g * 0.9), (cx, cy + g * 0.4), (cx - g * 0.7, cy + g * 0.9)], fill=fg)


def text(d, xy, s, f, fill, anchor="mm"):
    d.text(xy, s, font=f, fill=fill, anchor=anchor)


# -------------------------------------------------------------------- widgets
# Each takes a size in dp and returns an RGBA picture at SS x U px a dp.

def px(w, h):
    return int(w * U * SS), int(h * U * SS)


def finish(face, mask):
    face = face.convert("RGBA")
    face.putalpha(mask)
    return face


def photo_widget(shape, w, h, palette="paper", kind=0):
    W, H = px(w, h)
    paper, ink = PAPER[palette]
    m, win, box = SHAPES[shape](W, H)
    face = Image.new("RGB", (W, H), paper)
    if shape == "Frame":
        d = ImageDraw.Draw(face)
        b = min(W, H) * 0.065
        d.rectangle((0, 0, W, H), fill=ink)
        d.rectangle((b, b, W - b, H - b), fill=paper)
    pic = photo(W, H, kind)
    face.paste(pic, (0, 0), win)
    return finish(face, m)


def film_photos(w, h):
    W, H = px(w, h)
    paper, ink = PAPER["ink"]
    count = max(1, round((W - H * 0.05) / ((H - 2 * H * 0.17) * 1.3 + H * 0.05)))
    m, win, frames = film(W, H, count)
    face = Image.new("RGB", (W, H), paper)
    for i, f in enumerate(frames):
        fw, fh = int(f[2] - f[0]), int(f[3] - f[1])
        pic = photo(fw, fh, i)
        fm, fd = _blank(fw, fh)
        fd.rounded_rectangle((0, 0, fw - 1, fh - 1), radius=2 * U * SS, fill=255)
        face.paste(pic, (int(f[0]), int(f[1])), fm)
    return finish(face, m)


def apps_widget(shape, n, w, h, palette="cream", mono=False, names=False):
    W, H = px(w, h)
    paper, ink = PAPER[palette]
    if shape == "Film":
        m, win, frames = film(W, H, n)
        face = Image.new("RGB", (W, H), paper)
        face.paste(Image.new("RGB", (W, H), mix(paper, ink, 0.13)), (0, 0), win)
        d = ImageDraw.Draw(face)
        for i, f in enumerate(frames):
            size = min(f[2] - f[0], f[3] - f[1]) * 0.74
            icon(d, (f[0] + f[2]) / 2, (f[1] + f[3]) / 2, size, i, (mix(paper, ink, 0.25), ink) if mono else None)
        return finish(face, m)
    m, win, box = SHAPES[shape](W, H)
    face = Image.new("RGB", (W, H), paper)
    d = ImageDraw.Draw(face)
    bw, bh = box[2] - box[0], box[3] - box[1]
    cell = bw / n
    size = min(cell, bh) * 0.86
    for i in range(n):
        icon(d, box[0] + cell * (i + 0.5), (box[1] + box[3]) / 2, size, i, (mix(paper, ink, 0.12), ink) if mono else None)
    return finish(face, m)


def dock(n, h=56, palette="cream", mono=False):
    """A ticket as long as [n] apps need, as the widget grows with its apps."""
    side = h - 2 * h * 0.08
    w = n * side + 2 * h * 0.08 + h * 0.3
    return apps_widget("Ticket", n, w, h, palette, mono)


def clock_widget(shape, w, h, palette, style="digital", time=(10, 9)):
    W, H = px(w, h)
    paper, ink = PAPER[palette]
    m, win, box = SHAPES[shape](W, H)
    face = Image.new("RGB", (W, H), paper)
    d = ImageDraw.Draw(face)
    # The hairline round the window.
    edge = Image.new("L", (W, H), 0)
    ImageDraw.Draw(edge).bitmap((0, 0), win.filter(ImageFilter.FIND_EDGES), fill=255)
    face.paste(Image.new("RGB", (W, H), mix(paper, ink, 0.18)), (0, 0), edge.filter(ImageFilter.MaxFilter(3)))
    x0, y0, x1, y1 = box
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    if style == "digital":
        s = f"{time[0]}:{time[1]:02d}"
        size = min((x1 - x0) * 0.9 / 2.55, (y1 - y0) * 0.5)
        text(d, (cx, cy - size * 0.12), s, light(size), ink)
        text(d, (cx, cy + size * 0.6), "Mon 26 Oct", semibold(size * 0.2), ink)
    else:
        r = min(x1 - x0, y1 - y0) / 2
        for i in range(12):
            a = math.pi * 2 * i / 12
            major = i % 3 == 0
            r0, r1 = r * (0.8 if major else 0.86), r * 0.94
            col = ink if major else mix(paper, ink, 0.6)
            d.line([(cx + r0 * math.sin(a), cy - r0 * math.cos(a)), (cx + r1 * math.sin(a), cy - r1 * math.cos(a))], fill=col, width=max(2, int(r * (0.045 if major else 0.025))))
        hh, mm = time
        for deg, length, width in (((hh % 12 + mm / 60) * 30, 0.5, 0.075), (mm * 6, 0.74, 0.05)):
            a = math.radians(deg)
            d.line([(cx - r * 0.08 * math.sin(a), cy + r * 0.08 * math.cos(a)), (cx + r * length * math.sin(a), cy - r * length * math.cos(a))], fill=ink, width=int(r * width))
        d.ellipse((cx - r * 0.07, cy - r * 0.07, cx + r * 0.07, cy + r * 0.07), fill=ink)
    return finish(face, m)


def dates_widget(shape, w, h, palette, today=False):
    W, H = px(w, h)
    paper, ink = PAPER[palette]
    soft = mix(paper, ink, 0.7)
    m, win, box = SHAPES[shape](W, H)
    face = Image.new("RGB", (W, H), paper)
    d = ImageDraw.Draw(face)
    x0, y0, x1, y1 = box
    cx = (x0 + x1) / 2
    bh = y1 - y0
    if today:
        text(d, (cx, y0 + bh * 0.2), "Today", light(bh * 0.22), ink)
        text(d, (cx, y0 + bh * 0.43), "Priya's birthday", semibold(bh * 0.1), ink)
        text(d, (cx, y0 + bh * 0.55), "Mon 26 Oct", regular(bh * 0.075), soft)
        text(d, (cx, y0 + bh * 0.72), "Ask about the new job,", italic(bh * 0.08), ink)
        text(d, (cx, y0 + bh * 0.83), "and the trip to Goa", italic(bh * 0.08), ink)
        rnd = random.Random(4)
        cols = [(232, 72, 58), (242, 179, 61), (61, 163, 93), (59, 125, 216), (182, 95, 207), (242, 123, 163)]
        for _ in range(46):
            x, y = rnd.uniform(0, W), rnd.uniform(0, H)
            a = rnd.uniform(0, math.pi)
            s = rnd.uniform(0.012, 0.022) * W
            pts = [(x + s * math.cos(a + k) , y + s * 0.5 * math.sin(a + k)) for k in (0, math.pi / 2, math.pi, 3 * math.pi / 2)]
            d.polygon([(x + s * math.cos(a), y + s * math.sin(a)), (x + s * 0.5 * math.cos(a + 1.6), y + s * 0.5 * math.sin(a + 1.6)),
                       (x - s * math.cos(a), y - s * math.sin(a)), (x - s * 0.5 * math.cos(a + 1.6), y - s * 0.5 * math.sin(a + 1.6))], fill=rnd.choice(cols))
    else:
        text(d, (cx, y0 + bh * 0.24), "3", light(bh * 0.4), ink)
        text(d, (cx, y0 + bh * 0.5), "days to go", semibold(bh * 0.075), soft)
        text(d, (cx, y0 + bh * 0.64), "Priya & Rahul's birthdays", semibold(bh * 0.085), ink)
        text(d, (cx, y0 + bh * 0.76), "Thu 29 Oct", regular(bh * 0.075), soft)
    return finish(face, m)


# --------------------------------------------------------------------- scenes

def shadow(img, k=0.035):
    """A soft shadow of [img]'s shape, padded so the blur is not cut off. Returns it and its padding."""
    r = int(min(img.size) * k)
    pad = r * 3
    a = Image.new("L", (img.size[0] + 2 * pad, img.size[1] + 2 * pad), 0)
    a.paste(img.split()[3].point(lambda v: v * 0.35), (pad, pad))
    sh = Image.new("RGBA", a.size, (10, 10, 30, 0))
    sh.putalpha(a.filter(ImageFilter.GaussianBlur(r)))
    return sh, pad


def place(canvas, img, x, y):
    """Put a widget at (x, y) dp on the scene, with a soft shadow."""
    X, Y = int(x * U * SS), int(y * U * SS)
    sh, pad = shadow(img)
    canvas.alpha_composite(sh, (X - pad + int(2 * U * SS), Y - pad + int(4 * U * SS)))
    canvas.alpha_composite(img, (X, Y))


def label(canvas, s, x, y, size=13):
    d = ImageDraw.Draw(canvas)
    d.text((x * U * SS, y * U * SS), s, font=semibold(size * U * SS), fill=(255, 255, 255, 235), anchor="mm")


def scene(w, h, seed=3):
    W, H = px(w, h)
    return wallpaper(W // 4, H // 4, seed).resize((W, H), Image.BICUBIC).convert("RGBA")


def save(canvas, name):
    OUT.mkdir(parents=True, exist_ok=True)
    w, h = canvas.size
    canvas.resize((w // SS, h // SS), Image.LANCZOS).convert("RGB").save(OUT / name, optimize=True)
    print("wrote", OUT / name)


def hero():
    c = scene(700, 470, 3)
    place(c, photo_widget("Stamp", 150, 150), 28, 26)
    place(c, clock_widget("Circle", 150, 150, "ink"), 200, 26)
    place(c, dates_widget("Scallop", 150, 150, "blush"), 372, 26)
    place(c, clock_widget("Circle", 140, 140, "cream", "analogue", (1, 50)), 540, 30)
    place(c, apps_widget("Ticket", 4, 316, 60, "cream"), 28, 206)
    place(c, apps_widget("Film", 4, 300, 60, "ink"), 372, 206)
    place(c, photo_widget("Polaroid", 140, 150, "paper", 1), 28, 290)
    place(c, photo_widget("Arch", 120, 150, "paper", 2), 194, 290)
    place(c, film_photos(330, 80), 340, 325)
    save(c, "hero.png")


def shapes():
    c = scene(760, 150, 8)
    names = ["Stamp", "Polaroid", "Frame", "Circle", "Squircle", "Ticket", "Scallop", "Arch"]
    pal = ["paper", "paper", "kraft", "cream", "navy", "cream", "blush", "sage"]
    for i, n in enumerate(names):
        place(c, photo_widget(n, 80, 92 if n == "Polaroid" else 80, pal[i], i % 3), 14 + i * 93, 18)
        label(c, n, 14 + i * 93 + 40, 130, 11)
    save(c, "shapes.png")


def apps():
    c = scene(700, 300, 5)
    y = 16
    for n in (2, 3, 4, 5):
        place(c, dock(n, 50), 24, y)
        y += 62
    label(c, "Grows like a dock", 24 + 140, 278, 12)
    place(c, apps_widget("Film", 5, 330, 70, "ink"), 350, 30)
    label(c, "Film strip", 350 + 165, 118, 12)
    place(c, apps_widget("Ticket", 4, 316, 60, "cream", mono=True), 357, 170)
    label(c, "Monochrome icons", 357 + 158, 250, 12)
    save(c, "apps.png")


def dates():
    c = scene(560, 240, 9)
    place(c, dates_widget("Scallop", 170, 170, "blush"), 40, 22)
    label(c, "Counting down", 40 + 85, 214, 12)
    place(c, dates_widget("Stamp", 170, 170, "cream", today=True), 300, 22)
    label(c, "On the day", 300 + 85, 214, 12)
    save(c, "dates.png")


def clocks():
    c = scene(600, 200, 12)
    place(c, clock_widget("Circle", 130, 130, "ink"), 20, 24)
    place(c, clock_widget("Squircle", 130, 130, "navy", "digital", (7, 45)), 166, 24)
    place(c, clock_widget("Scallop", 130, 130, "mustard", "analogue", (10, 10)), 312, 24)
    place(c, clock_widget("Stamp", 130, 130, "paper", "analogue", (4, 35)), 458, 24)
    label(c, "Live clocks, digital or analogue, in any shape", 300, 180, 12)
    save(c, "clocks.png")


if __name__ == "__main__":
    hero()
    shapes()
    apps()
    dates()
    clocks()
