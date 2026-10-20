#!/usr/bin/env python3
"""Veil launcher icon generator (original artwork, AGPL-3.0-or-later).

Neon-pink-on-black "censor bar" look. One set of shape definitions ->
Android VectorDrawable XML (adaptive background / foreground / monochrome)
and PNG previews rasterised here (PIL, 4x supersampling, alpha compositing).
Glow = stacked translucent wide strokes (VectorDrawable has no blur), sized so
it still reads at 48 px. Coordinates: 108x108 adaptive viewport, launchers
show roughly the middle 72 units; keep art inside ~(24..84).

usage: make_icon.py <res_dir> <out_dir> [default_concept]
"""
import math, os, sys
import numpy as np
from PIL import Image, ImageDraw

NEON = "#FF1FA8"; NEON_HOT = "#FF6BC8"; NEON_DEEP = "#B0107A"; INK = "#0B0610"; BG = "#0D0711"

# ---------- layer helpers: dict(d, fill|stroke, alpha, width, evenOdd) ----------
def F(d, c, a=1.0, eo=False): return dict(d=d, fill=c, alpha=a, eo=eo)
def S(d, c, w, a=1.0): return dict(d=d, stroke=c, width=w, alpha=a)
def glow(d, core=2.6, c=NEON):
    """neon tube: soft halo + core + hot centre line"""
    return [S(d, c, core + 8, 0.12), S(d, c, core + 4, 0.28), S(d, c, core), S(d, NEON_HOT, core * 0.35, 0.9)]

def poly(pts, close=True):
    s = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in pts)
    return s + (" Z" if close else "")

def rect(x1, y1, x2, y2): return poly([(x1, y1), (x2, y1), (x2, y2), (x1, y2)])

def rot_rect(cx, cy, w, h, deg):
    a = math.radians(deg); c, s = math.cos(a), math.sin(a)
    return poly([(cx + x * c - y * s, cy + x * s + y * c) for x, y in
                 ((-w/2, -h/2), (w/2, -h/2), (w/2, h/2), (-w/2, h/2))])

def circle(cx, cy, r):
    k = 0.5523 * r
    return (f"M{cx},{cy-r} C{cx+k},{cy-r} {cx+r},{cy-k} {cx+r},{cy} C{cx+r},{cy+k} {cx+k},{cy+r} {cx},{cy+r} "
            f"C{cx-k},{cy+r} {cx-r},{cy+k} {cx-r},{cy} C{cx-r},{cy-k} {cx-k},{cy-r} {cx},{cy-r} Z")

def censor_bar(cx, cy, w, h, deg):
    """thick pink-outlined black bar with glow + a few dim pixel 'scanline' blocks inside"""
    d = rot_rect(cx, cy, w, h, deg)
    out = [S(d, NEON, 11, 0.12), S(d, NEON, 7, 0.25), F(d, INK), S(d, NEON, 2.8), S(d, NEON_HOT, 0.9, 0.9)]
    a = math.radians(deg); c, s = math.cos(a), math.sin(a)
    for i, (u, v, sz) in enumerate(((-0.3, -0.1, 3), (0.05, 0.12, 3), (0.32, -0.05, 3))):
        x, y = u * w, v * h
        out.append(F(rot_rect(cx + x * c - y * s, cy + x * s + y * c, sz, sz, deg), NEON, 0.22))
    return out, d

# ---------- background (shared): near-black + dim pixel blocks ----------
def background():
    L = [F(rect(0, 0, 108, 108), BG)]
    rnd = np.random.default_rng(7)
    cell = 6
    for gy in range(0, 108, cell):
        for gx in range(0, 108, cell):
            cx, cy = gx + cell / 2 - 54, gy + cell / 2 - 54
            r = math.hypot(cx, cy)
            p = min(1.0, max(0.0, (r - 22) / 30))  # denser toward the edges, empty centre
            if rnd.random() < p * 0.55:
                a = float(rnd.choice([0.10, 0.16, 0.24, 0.34]))
                L.append(F(rect(gx, gy, gx + cell, gy + cell), NEON, a))
    # faint sparkles
    for x, y in ((30, 28), (80, 34), (26, 78), (84, 80)):
        L.append(F(rect(x - 0.6, y - 0.6, x + 0.6, y + 0.6), NEON_HOT, 0.8))
    return L

# ---------- concept 1: glossy lips + censor bar ----------
UP = ("M28,55 C34,47.5 42,42.5 48.5,45 C51,46 52.6,47.4 54,47.4 C55.4,47.4 57,46 59.5,45 "
      "C66,42.5 74,47.5 80,55 C72,55.8 63,55.6 54,56 C45,55.6 36,55.8 28,55 Z")
LO = ("M28,55 C37,57.2 45.5,57.4 54,57.4 C62.5,57.4 71,57.2 80,55 C75,65.5 65,70.5 54,70.5 "
      "C43,70.5 33,65.5 28,55 Z")
LIPS = UP + " " + LO
GLOSS = "M45,64.5 C49,63.4 56,63.6 60,64.8 C56.5,67 48.5,67 45,64.5 Z"
GLOSS_UP = "M36,51.5 C39,48.6 43,46.6 46.5,47.2 C44,49.4 40,51.4 36,51.5 Z"
def lips():
    bar, bard = censor_bar(55, 57.5, 58, 7.5, -9)
    return ([S(LIPS, NEON, 10, 0.14), S(LIPS, NEON, 5, 0.3), F(UP, NEON), F(LO, "#E0108F"),
             F(GLOSS, "#FFD0EC", 0.9), F(GLOSS_UP, "#FFD0EC", 0.75), S(LIPS, NEON_HOT, 1.0, 0.8)] + bar,
            [F(LIPS + " " + bard, "#FFF", eo=True)])

# ---------- concept 2: hourglass curve + glowing bar ----------
HOUR = ("M45,26 C49,24.6 59,24.6 63,26 C67.5,27.6 68,31 66.5,35 C64,41 59.5,45 59.5,51 "
        "C59.5,57 74,60.5 74,70 C74,77 68.5,81 65,86 L56.5,86 L54,80.5 L51.5,86 L43,86 "
        "C39.5,81 34,77 34,70 C34,60.5 48.5,57 48.5,51 C48.5,45 44,41 41.5,35 C40,31 40.5,27.6 45,26 Z")
HOUR_SHADE = ("M54,25 C57,25 61,25.2 63,26 C67.5,27.6 68,31 66.5,35 C64,41 59.5,45 59.5,51 "
              "C59.5,57 74,60.5 74,70 C74,77 68.5,81 65,86 L56.5,86 L54,80.5 Z")
def hourglass():
    bar, bard = censor_bar(54, 69, 52, 9, -6)
    return ([F(HOUR, "#9A0D6A"), F(HOUR_SHADE, "#6E094C")] + glow(HOUR, 2.4) + bar,
            [F(HOUR + " " + bard, "#FFF", eo=True)])

# ---------- concept 3: keyhole with pixel skin/pink blocks ----------
def keyhole_path():
    cx, cy, r = 54, 45, 14
    xl, xr = 48, 60
    a0 = math.acos((xl - cx) / r)  # point on circle at x = xl (lower half)
    pts = []
    for k in range(41):  # from lower-left, over the top, to lower-right
        t = math.pi - (math.pi - a0) + 0  # placeholder, replaced below
    start = math.atan2(math.sqrt(r*r - (xl-cx)**2), xl - cx)   # angle (y down) at left join
    end = math.atan2(math.sqrt(r*r - (xr-cx)**2), xr - cx)
    # walk the long way round: start -> (through top) -> end, angles in y-down space
    span = (2 * math.pi - (start - end))
    for k in range(49):
        t = start + span * k / 48
        pts.append((cx + r * math.cos(t), cy + r * math.sin(t)))
    pts += [(65, 83), (43, 83)]
    return poly(pts), pts
def inside(pts, x, y):
    n = len(pts); c = False; j = n - 1
    for i in range(n):
        xi, yi = pts[i]; xj, yj = pts[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi: c = not c
        j = i
    return c
def keyhole():
    d, pts = keyhole_path()
    pal = ["#FF1FA8", "#FF5FBF", "#E8A07F", "#F6C2A4", "#C9785E", "#B0107A", "#FF8FD2"]
    rnd = np.random.default_rng(3)
    blocks = []
    cell = 4
    for gy in range(29, 85, cell):
        for gx in range(38, 70, cell):
            if inside(pts, gx + cell / 2, gy + cell / 2):
                blocks.append(F(rect(gx, gy, gx + cell, gy + cell), pal[int(rnd.integers(len(pal)))]))
    return ([F(d, INK)] + blocks + glow(d, 2.8),
            [S(d, "#FFF", 3.2)] + [F(b["d"], "#FFF") for i, b in enumerate(blocks) if i % 2 == 0])

# ---------- concept 4: blocky pixel V + censor bar + subtle curves ----------
V_PAT = ["XX.....XX",
         "XX.....XX",
         ".XX...XX.",
         ".XX...XX.",
         "..XX.XX..",
         "..XX.XX..",
         "...XXX...",
         "...XXX...",
         "....X...."]
def v_blocks(grow=0.0):
    cell = 5; x0 = 54 - 4.5 * cell; y0 = 30
    out = []
    for r, row in enumerate(V_PAT):
        for c, ch in enumerate(row):
            if ch == "X":
                x, y = x0 + c * cell, y0 + r * cell
                out.append(rect(x - grow, y - grow, x + cell + grow, y + cell + grow))
    return out
CURL = "M26,40 C21,50 21,62 27,72"
CURR = "M82,40 C87,50 87,62 81,72"
def pixel_v():
    bar, bard = censor_bar(54, 58, 54, 10, -7)
    halo2 = " ".join(v_blocks(3.2)); halo1 = " ".join(v_blocks(1.4)); core = " ".join(v_blocks())
    hi = " ".join(rect(float(b.split()[0][1:].split(",")[0]) + 0.8, float(b.split()[0].split(",")[1]) + 0.8,
                       float(b.split()[0][1:].split(",")[0]) + 2.2, float(b.split()[0].split(",")[1]) + 2.2)
                  for b in v_blocks())
    curves = [S(CURL, NEON, 5, 0.18), S(CURL, NEON, 1.8, 0.85), S(CURR, NEON, 5, 0.18), S(CURR, NEON, 1.8, 0.85)]
    return ([F(halo2, NEON, 0.13), F(halo1, NEON, 0.3), F(core, NEON)] + curves + bar,
            [F(core + " " + bard, "#FFF", eo=True), S(CURL, "#FFF", 2.2), S(CURR, "#FFF", 2.2)])

CONCEPTS = [("lips", lips), ("hourglass", hourglass), ("keyhole", keyhole), ("pixel-v", pixel_v)]

# ---------- VectorDrawable writer ----------
def vector_xml(layers, comment):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           f'<!-- {comment} Generated by tools/make_icon.py; original artwork, AGPL-3.0-or-later. -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp" android:height="108dp"',
           '    android:viewportWidth="108" android:viewportHeight="108">']
    for l in layers:
        if "fill" in l:
            a = f' android:fillAlpha="{l["alpha"]:.2f}"' if l["alpha"] < 1 else ""
            ft = ' android:fillType="evenOdd"' if l.get("eo") else ""
            out.append(f'    <path android:fillColor="{l["fill"]}"{a}{ft} android:pathData="{l["d"]}" />')
        else:
            a = f' android:strokeAlpha="{l["alpha"]:.2f}"' if l["alpha"] < 1 else ""
            out.append(f'    <path android:strokeColor="{l["stroke"]}" android:strokeWidth="{l["width"]:.2f}"{a}'
                       f' android:strokeLineJoin="round" android:strokeLineCap="round" android:pathData="{l["d"]}" />')
    out.append('</vector>')
    return "\n".join(out) + "\n"

# ---------- rasteriser ----------
def flatten(d):
    toks = d.replace(",", " ").split(); subs, cur, i, pt, closed = [], [], 0, (0.0, 0.0), []
    def num():
        nonlocal i; v = float(toks[i]); i += 1; return v
    cmd = None
    while i < len(toks):
        t = toks[i]
        if t[0].isalpha():
            cmd = t[0]; rest = t[1:]; i += 1
            if rest: toks.insert(i, rest)
            if cmd == 'Z':
                if cur: subs.append((cur, True)); cur = []
                continue
        if cmd == 'M':
            if cur: subs.append((cur, False))
            pt = (num(), num()); cur = [pt]; cmd = 'L'
        elif cmd == 'L':
            pt = (num(), num()); cur.append(pt)
        elif cmd == 'C':
            p1 = (num(), num()); p2 = (num(), num()); p3 = (num(), num()); p0 = pt
            for k in range(1, 25):
                s = k / 24; u = 1 - s
                cur.append((u**3*p0[0] + 3*u*u*s*p1[0] + 3*u*s*s*p2[0] + s**3*p3[0],
                            u**3*p0[1] + 3*u*u*s*p1[1] + 3*u*s*s*p2[1] + s**3*p3[1]))
            pt = p3
        else:
            raise ValueError(cmd)
    if cur: subs.append((cur, False))
    return subs

def rgb(c):
    c = c.lstrip("#")
    if len(c) == 3: c = "".join(ch * 2 for ch in c)
    return np.array([int(c[k:k+2], 16) for k in (0, 2, 4)], np.float32)

def render(layers, size, ss=4, crop=(18, 90), mask=True, mono_bg=None):
    S_ = size * ss; a, b = crop; sc = S_ / (b - a)
    img = np.zeros((S_, S_, 3), np.float32); al = np.zeros((S_, S_), np.float32)
    if mono_bg is not None:
        img[:] = rgb(mono_bg); al[:] = 1
    tf = lambda p: ((p[0] - a) * sc, (p[1] - a) * sc)
    for l in layers:
        im = Image.new("L", (S_, S_), 0); dr = ImageDraw.Draw(im)
        if "fill" in l:
            m = np.zeros((S_, S_), bool)
            for sub, _ in flatten(l["d"]):
                t = Image.new("1", (S_, S_), 0); ImageDraw.Draw(t).polygon([tf(p) for p in sub], fill=1)
                sm = np.array(t, bool); m = (m ^ sm) if l.get("eo") else (m | sm)
            cov = m.astype(np.float32); col = l["fill"]
        else:
            w = max(1, int(round(l["width"] * sc)))
            for sub, closed in flatten(l["d"]):
                pts = [tf(p) for p in sub] + ([tf(sub[0]), tf(sub[1])] if closed and len(sub) > 1 else [])
                dr.line(pts, fill=255, width=w, joint="curve")
                for p in (pts[0], pts[-1]):
                    dr.ellipse([p[0] - w / 2, p[1] - w / 2, p[0] + w / 2, p[1] + w / 2], fill=255)
            cov = np.array(im, np.float32) / 255; col = l["stroke"]
        k = (cov * l["alpha"])[..., None]
        c = rgb("#E9DDFF") if mono_bg is not None else rgb(col)
        img = img * (1 - k) + c * k; al = al + (1 - al) * k[..., 0]
    out = Image.fromarray(np.dstack([img, al * 255]).clip(0, 255).astype(np.uint8), "RGBA").resize((size, size), Image.LANCZOS)
    if mask:
        mk = Image.new("L", (S_, S_), 0)
        ImageDraw.Draw(mk).rounded_rectangle([0, 0, S_ - 1, S_ - 1], radius=int(S_ * 0.3), fill=255)
        out.putalpha(Image.fromarray(np.minimum(np.array(out)[:, :, 3], np.array(mk.resize((size, size), Image.LANCZOS)))))
    return out

def full_icon(fg, size): return render(background() + fg, size)
def themed(mono, size): return render(mono, size, mono_bg="#2A2238")

def main():
    res, out = sys.argv[1], sys.argv[2]
    default = sys.argv[3] if len(sys.argv) > 3 else "lips"
    os.makedirs(out, exist_ok=True); d = os.path.join(res, "drawable")
    open(os.path.join(d, "ic_launcher_background.xml"), "w").write(vector_xml(background(), "Veil icon background."))
    built = {}
    for i, (name, fn) in enumerate(CONCEPTS, 1):
        fg, mono = fn(); built[name] = (fg, mono)
        full_icon(fg, 512).save(os.path.join(out, f"veil-icon-concept{i}-{name}.png"))
        themed(mono, 512).save(os.path.join(out, f"veil-icon-concept{i}-{name}-themed.png"))
        open(os.path.join(out, f"veil-icon-concept{i}-{name}_foreground.xml"), "w").write(vector_xml(fg, f"Veil icon concept {i}: {name}."))
        open(os.path.join(out, f"veil-icon-concept{i}-{name}_monochrome.xml"), "w").write(vector_xml(mono, f"Veil themed icon concept {i}: {name}."))
    fg, mono = built[default]
    open(os.path.join(d, "ic_launcher_foreground.xml"), "w").write(vector_xml(fg, f"Veil icon ({default})."))
    open(os.path.join(d, "ic_launcher_monochrome.xml"), "w").write(vector_xml(mono, f"Veil themed icon ({default})."))
    full_icon(fg, 512).save(os.path.join(out, "veil-icon-preview.png"))
    themed(mono, 512).save(os.path.join(out, "veil-icon-preview-themed.png"))
    alt = [n for n, _ in CONCEPTS if n != default][0]
    full_icon(built[alt][0], 512).save(os.path.join(out, "veil-icon-alt.png"))
    themed(built[alt][1], 512).save(os.path.join(out, "veil-icon-alt-themed.png"))
    # contact sheet: 4 x 256 icons, then 48 px + 96 px + themed 96 px for each
    W = 4 * 272 + 16; sheet = Image.new("RGBA", (W, 272 + 140), (24, 20, 28, 255))
    for i, (name, _) in enumerate(CONCEPTS):
        fg, mono = built[name]; x = 16 + i * 272
        sheet.alpha_composite(full_icon(fg, 256), (x, 16))
        sheet.alpha_composite(full_icon(fg, 48), (x, 290))
        sheet.alpha_composite(full_icon(fg, 96), (x + 60, 290))
        sheet.alpha_composite(themed(mono, 96), (x + 160, 290))
    sheet.save(os.path.join(out, "veil-icon-contact-sheet.png"))
    print("default:", default, "alt:", alt)

if __name__ == "__main__":
    main()
