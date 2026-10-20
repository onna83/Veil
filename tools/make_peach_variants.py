#!/usr/bin/env python3
"""Peach + pixel-censor icon variants (original artwork, AGPL-3.0-or-later).

A: original peach (current default)   B: neon peach with censor bar
C: juicy glossy peach + pixel patch   D: minimal flat peach for 48 px
Writes per variant: PNG (512, launcher mask), themed PNG, and background /
foreground / monochrome VectorDrawable XMLs so any can be swapped into res/.
usage: make_peach_variants.py <out_dir>
"""
import math, os, re, sys
from PIL import Image, ImageDraw
sys.path.insert(0, os.path.dirname(__file__))
import make_icon as P            # original peach paths
import make_icon_neon as N       # rasteriser with strokes/alpha + neon helpers
from make_icon_neon import F, S, rect, circle, censor_bar, vector_xml, render

PLUM = "#2B1E3F"

def fit(d, sc=1.0, dx=0.0, dy=0.0):
    f = lambda m: f"{round(54 + (float(m[1]) - 54) * sc + dx, 2)},{round(54 + (float(m[2]) - 54) * sc + dy, 2)}"
    return re.sub(r"(-?[0-9.]+),(-?[0-9.]+)", f, d)

def conv(old):  # old (color, d[, evenOdd]) tuples -> layer dicts
    return [F(t[1], t[0], eo=len(t) > 2) for t in old]

# ---- A: original ----
def A():
    return [F(rect(0, 0, 108, 108), PLUM)], conv(P.peach_fg()), conv(P.peach_mono())

# ---- B: neon peach + censor bar on near-black ----
def B():
    body, crease = P.PEACH_BODY, P.PEACH_CREASE
    leaves = P.LEAF_L + " " + P.LEAF_R
    bar, bard = censor_bar(54.5, 70.5, 38, 10, -8)
    fg = ([F(body, "#6A1050"), F(P.PEACH_SHADE, "#4E0B3B"), F(P.PEACH_HI, "#FF8FD2", 0.55)]
          + N.glow(body, 2.6) + [F(crease, N.NEON_HOT, 0.95)]
          + [S(leaves, N.NEON, 5, 0.2), F(P.LEAF_L, N.NEON), F(P.LEAF_R, "#D0148A"), F(P.LEAF_VEIN, "#7A0B55")]
          + bar)
    mono = [F(body + " " + bard, "#FFF", eo=True), F(leaves, "#FFF")]
    return N.background(), fg, mono

# ---- C: juicy glossy peach + small pixel patch + pixel sparkles ----
def C():
    body = P.PEACH_BODY
    left_glow = ("M54,42 C48,35.5 30.5,37.5 30.5,57 C30.5,71.5 41,80.5 51.5,79.5 L54,77.2 "
                 "C47,70 46,52 54,42 Z")
    rim = ("M74.5,50 C76.8,55 76.6,63 73.5,69 C71,73.5 67,76.6 62.5,78 C67,74.5 71.5,68.5 72.8,61.5 "
           "C73.6,57.2 73.8,53.2 74.5,50 Z")
    hi_big = "M37.5,48.5 C39.5,43 45.5,40.6 48.5,43 C47.2,46.6 42,51.6 38.6,54.2 C37,52.8 36.8,50.6 37.5,48.5 Z"
    hi_dot = circle(41.2, 58.2, 1.6)
    hi_r = "M60.5,44.6 C64.5,43.2 68.5,44.6 70.2,47.2 C67,46.6 63.5,46.4 60.5,44.6 Z"
    drop = "M66,60 C66,60 63.6,63.4 63.6,65.2 C63.6,66.6 64.7,67.6 66,67.6 C67.3,67.6 68.4,66.6 68.4,65.2 C68.4,63.4 66,60 66,60 Z"
    drop_hi = circle(65.2, 65.4, 0.6)
    leaf_hi = "M40,33.6 C44,33.4 49,36.2 52.6,40.4 C48.4,37.8 44,35.6 40,33.6 Z"
    pal = ["#FF6E55", "#FFB396", "#E0503F", "#FF9070", "#C9443A"]
    patch = [F(dd, c) for c, dd in P.blocks(48.5, 67, 3.5, ["101", "2342", ".03."], pal)]
    sparkles = []
    for x, y, s in ((27, 33, 2.2), (81, 40, 1.8), (80, 76, 2.2), (25, 70, 1.6), (70, 27, 1.4)):
        sparkles += [F(rect(x - s / 2, y - s * 1.5, x + s / 2, y + s * 1.5), "#FFD3E6", 0.95),
                     F(rect(x - s * 1.5, y - s / 2, x + s * 1.5, y + s / 2), "#FFD3E6", 0.95),
                     F(rect(x - s / 2, y - s / 2, x + s / 2, y + s / 2), "#FFFFFF")]
    fg = ([F(body, "#FF7A5A"), F(left_glow, "#FF9A72"), F(P.PEACH_SHADE, "#E9604A", 0.55),
           F(rim, "#FFC0A0", 0.7), F(P.PEACH_CREASE, "#C94A3C"),
           F(hi_big, "#FFFFFF", 0.65), F(hi_dot, "#FFFFFF", 0.8), F(hi_r, "#FFE0D0", 0.6),
           F(drop, "#FFE4EE", 0.75), F(drop_hi, "#FFFFFF"),
           F(P.LEAF_L, "#5CC97A"), F(P.LEAF_R, "#3AA25F"), F(P.LEAF_VEIN, "#2F8A50"), F(leaf_hi, "#B9F2C8", 0.7)]
          + patch + sparkles)
    holes = [b["d"] for i, b in enumerate(patch) if i % 2 == 0]
    mono = [F(" ".join([body, P.PEACH_CREASE_MONO] + holes), "#FFF", eo=True),
            F(P.LEAF_L, "#FFF"), F(P.LEAF_R, "#FFF")] + [F(s_["d"], "#FFF") for s_ in sparkles[0::3] + sparkles[1::3]]
    bg = [F(rect(0, 0, 108, 108), "#2E1A3E")]
    return bg, fg, mono

# ---- D: minimal flat silhouette, bold for 48 px ----
def D():
    sc, dy = 1.16, -1.5
    body = fit(P.PEACH_BODY, sc, 0, dy)
    crease = fit("M53.2,44.5 C57.2,52 57.4,60 56,66 L58.6,66 C60,60 59.6,52 55.2,44.5 Z", sc, 0, dy)
    leaf = fit("M54.2,41.5 C57,31.5 66,27.8 72.5,30.6 C69,37.2 61.5,41.5 54.2,41.5 Z", sc, 0, dy)
    pal = ["#FFC2A8", "#D2523F"]
    cells = [(c, fit(dd, sc, 0, dy)) for c, dd in P.blocks(49.5, 61.5, 5.5, ["10", "01", "10"], pal)]
    fg = [F(body, "#FF8A65"), F(crease, PLUM), F(leaf, "#62C27C")] + [F(dd, c) for c, dd in cells]
    mono = [F(" ".join([body, crease] + [dd for i, (c, dd) in enumerate(cells) if i % 2 == 0]), "#FFF", eo=True),
            F(leaf, "#FFF")]
    return [F(rect(0, 0, 108, 108), PLUM)], fg, mono

VARIANTS = [("A", "original", A), ("B", "neon-bar", B), ("C", "juicy", C), ("D", "minimal", D)]

def main():
    out = sys.argv[1]; os.makedirs(out, exist_ok=True)
    W = 4 * 272 + 16; sheet = Image.new("RGBA", (W, 16 + 256 + 30 + 110), (24, 20, 28, 255))
    dr = ImageDraw.Draw(sheet)
    for i, (k, name, fn) in enumerate(VARIANTS):
        bg, fg, mono = fn(); base = os.path.join(out, f"veil-peach-{k}-{name}")
        render(bg + fg, 512).save(base + ".png")
        N.themed(mono, 512).save(base + "-themed.png")
        for part, layers in (("background", bg), ("foreground", fg), ("monochrome", mono)):
            open(f"{base}_{part}.xml", "w").write(vector_xml(layers, f"Veil peach variant {k} ({name}) {part}."))
        x = 16 + i * 272
        sheet.alpha_composite(render(bg + fg, 256), (x, 16))
        dr.text((x + 4, 278), f"{k}: {name}", fill=(235, 225, 240, 255))
        sheet.alpha_composite(render(bg + fg, 48), (x, 300))
        sheet.alpha_composite(render(bg + fg, 96), (x + 60, 300))
        sheet.alpha_composite(N.themed(mono, 96), (x + 166, 300))
    sheet.save(os.path.join(out, "veil-peach-variants.png"))

if __name__ == "__main__":
    main()
