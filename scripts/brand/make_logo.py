#!/usr/bin/env python3
"""Draws the Jukebox logo and writes every icon made from it.

The mark is a harmonium seen from the front, pumped open: the keyboard board leans out on the
left, the button board on the right, and the bellows fan out between them with folds that
alternate solid and hollow. Colours are "Sunset" (gold, coral, magenta).

Writes:
  web/public/favicon.svg                         light icon, dark icon when the browser is dark
  desktop/icon.svg                               macOS app icon (then: cd desktop && npm run icons)
  android/.../drawable/ic_launcher_foreground.xml  white mark (also the themed-icon layer)
  android/.../drawable/ic_launcher_background.xml  sunset gradient
  android/.../drawable-night/ic_launcher_*.xml     dark mode: near-black, mark in the gradient

Run from the repo root: python3 scripts/brand/make_logo.py
"""
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "android/app/src/main/res"

GOLD, CORAL, MAGENTA = "#FFC857", "#FF5E62", "#C2185B"
NIGHT_TOP, NIGHT_BOTTOM = "#26232B", "#0B0A0D"

# ---- the mark, on a 64-unit grid; everything fans out from a pivot below the instrument ----

PIVOT = (32.0, 72.0)
R_IN, R_OUT, LEAN = 24.0, 54.0, 31.0


def at(r, deg):
    a = math.radians(deg)
    return PIVOT[0] + r * math.sin(a), PIVOT[1] - r * math.cos(a)


def xy(p):
    return f"{p[0]:.2f},{p[1]:.2f}"


def rounded(x, y, w, h, r):
    return (f"M{x + r:.2f},{y:.2f}h{w - 2 * r:.2f}a{r},{r} 0 0 1 {r},{r}v{h - 2 * r:.2f}"
            f"a{r},{r} 0 0 1 -{r},{r}h-{w - 2 * r:.2f}a{r},{r} 0 0 1 -{r},-{r}v-{h - 2 * r:.2f}"
            f"a{r},{r} 0 0 1 {r},-{r}z")


def dot(cx, cy, r):
    return f"M{cx - r:.2f},{cy:.2f}a{r},{r} 0 1 0 {2 * r},0a{r},{r} 0 1 0 -{2 * r},0z"


def mark():
    """The mark as shapes: (path, kind, rotation). kind is 'fill' (even-odd), 'solid' (fill plus
    a thin stroke to round its corners) or 'line' (stroke only). Boards are drawn upright above
    the pivot and turned about it."""
    shapes = []
    # Bellows: folds alternate solid and hollow; the inner edge is a true arc.
    first, last, folds = -(LEAN - 9.5), LEAN - 9.5, 6
    step = (last - first) / folds
    for i in range(folds):
        a0, a1 = first + i * step, first + (i + 1) * step
        t0, t1 = (R_OUT, R_OUT - 3.2) if i % 2 == 0 else (R_OUT - 3.2, R_OUT)
        d = (f"M{xy(at(R_IN, a0))} L{xy(at(t0, a0))} L{xy(at(t1, a1))} L{xy(at(R_IN, a1))} "
             f"A{R_IN},{R_IN} 0 0 0 {xy(at(R_IN, a0))}Z")
        shapes.append((d, "solid" if i % 2 == 0 else "line", 0))
    top, bottom = PIVOT[1] - R_OUT - 2, PIVOT[1] - R_IN + 1
    # Keyboard board: six white keys (holes) with black keys in pairs bridging them.
    w = 15.5
    d = rounded(PIVOT[0] - w / 2, top, w, bottom - top, 4.2)
    keys, key_top = 6, top + 3
    key_h, key_x, key_w = (bottom - top - 6) / keys, PIVOT[0] - w / 2 + 2.8, w - 5.6
    for i in range(keys):
        d += rounded(key_x, key_top + i * key_h + 0.7, key_w, key_h - 1.4, 1.1)
    for i in (1, 2, 4, 5):
        d += rounded(key_x - 0.01, key_top + i * key_h - 1.7, key_w * 0.56, 3.4, 1.0)
    shapes.append((d, "fill", -LEAN))
    # Button board: three round buttons.
    w = 13
    d = rounded(PIVOT[0] - w / 2, top, w, bottom - top, 4.2)
    for i in range(3):
        d += dot(PIVOT[0], top + 7.5 + i * (bottom - top - 15) / 2, 2.4)
    shapes.append((d, "fill", LEAN))
    return shapes


def bounds():
    """Box around the mark: the boards' turned corners and the bellows' outer points."""
    pts = []
    top, bottom = PIVOT[1] - R_OUT - 2, PIVOT[1] - R_IN + 1
    for w, deg in ((15.5, -LEAN), (13, LEAN)):
        for x in (PIVOT[0] - w / 2, PIVOT[0] + w / 2):
            for y in (top, bottom):
                dx, dy = x - PIVOT[0], y - PIVOT[1]
                a = math.radians(deg)
                pts.append((PIVOT[0] + dx * math.cos(a) - dy * math.sin(a),
                            PIVOT[1] + dx * math.sin(a) + dy * math.cos(a)))
    pts += [at(R_OUT + 1.3, a) for a in (-(LEAN - 9.5), 0, LEAN - 9.5)]
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    return min(xs), min(ys), max(xs), max(ys)


def fit(size, width, height, cx, cy):
    """Scale and offset that put the mark's box, centred, inside width x height around (cx, cy)."""
    x0, y0, x1, y1 = bounds()
    k = min(width / (x1 - x0), height / (y1 - y0))
    return k, cx - k * (x0 + x1) / 2, cy - k * (y0 + y1) / 2


# ---- SVG ----

def svg_mark(paint):
    out = []
    for d, kind, rot in mark():
        turn = f' transform="rotate({rot} {PIVOT[0]} {PIVOT[1]})"' if rot else ""
        if kind == "fill":
            out.append(f'<path fill="{paint}" fill-rule="evenodd" d="{d}"{turn}/>')
        elif kind == "solid":
            out.append(f'<path fill="{paint}" stroke="{paint}" stroke-width="1.8" stroke-linejoin="round" d="{d}"/>')
        else:
            out.append(f'<path fill="none" stroke="{paint}" stroke-width="2.6" stroke-linejoin="round" d="{d}"/>')
    return "".join(out)


def svg_icon(x, y, size, radius, dark, ids):
    """One rounded-square icon at (x, y). Light: sunset gradient lit from the top-left corner,
    white mark with a coloured shadow. Dark: near-black, mark in the sunset gradient."""
    s = size / 64
    k, tx, ty = fit(64, 42, 40, 32, 32)
    clip = f'<rect width="64" height="64" rx="{radius / s:.2f}"'
    if not dark:
        defs = (f'<linearGradient id="{ids}g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{GOLD}"/>'
                f'<stop offset=".55" stop-color="{CORAL}"/><stop offset="1" stop-color="{MAGENTA}"/></linearGradient>'
                f'<radialGradient id="{ids}h" cx=".22" cy=".05" r=".85"><stop offset="0" stop-color="#fff" stop-opacity=".38"/>'
                f'<stop offset=".55" stop-color="#fff" stop-opacity="0"/></radialGradient>'
                f'<linearGradient id="{ids}v" x1="0" y1="0" x2="0" y2="1"><stop offset=".55" stop-color="#000" stop-opacity="0"/>'
                f'<stop offset="1" stop-color="#000" stop-opacity=".18"/></linearGradient>'
                f'<filter id="{ids}s" x="-30%" y="-30%" width="160%" height="170%"><feDropShadow dx="0" dy="1.6" '
                f'stdDeviation="1.6" flood-color="{MAGENTA}" flood-opacity=".7"/></filter>')
        body = (f'{clip} fill="url(#{ids}g)"/>{clip} fill="url(#{ids}v)"/>{clip} fill="url(#{ids}h)"/>'
                f'<g filter="url(#{ids}s)"><g transform="translate({tx:.3f} {ty:.3f}) scale({k:.4f})">{svg_mark("#fff")}</g></g>')
    else:
        defs = (f'<linearGradient id="{ids}k" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{NIGHT_TOP}"/>'
                f'<stop offset="1" stop-color="{NIGHT_BOTTOM}"/></linearGradient>'
                f'<linearGradient id="{ids}f" gradientUnits="userSpaceOnUse" x1="10" y1="12" x2="54" y2="60">'
                f'<stop offset="0" stop-color="{GOLD}"/><stop offset=".55" stop-color="{CORAL}"/><stop offset="1" stop-color="{MAGENTA}"/></linearGradient>')
        body = (f'{clip} fill="url(#{ids}k)"/>'
                f'<g transform="translate({tx:.3f} {ty:.3f}) scale({k:.4f})">{svg_mark(f"url(#{ids}f)")}</g>')
    return f'<defs>{defs}</defs><g transform="translate({x} {y}) scale({s:.5f})">{body}</g>'


def favicon():
    # Browsers that read the media query show the dark icon on dark tabs.
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64">'
            '<style>.dark{display:none}@media (prefers-color-scheme:dark){.light{display:none}.dark{display:inline}}</style>'
            f'<g class="light">{svg_icon(0, 0, 64, 14.5, False, "l")}</g>'
            f'<g class="dark">{svg_icon(0, 0, 64, 14.5, True, "d")}</g></svg>\n')


def mac_icon():
    # macOS icon grid: an 824 px body with 185 px corners inside the 1024 px canvas.
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">'
            f'{svg_icon(100, 100, 824, 185, False, "m")}</svg>\n')


# ---- Android vector drawables ----

AAPT = 'xmlns:aapt="http://schemas.android.com/aapt"'


def android_colour(hex_colour, alpha=1.0):
    return f"#{round(alpha * 255):02X}{hex_colour.lstrip('#').upper()}"


def android_gradient(attr, stops, x1, y1, x2, y2):
    items = "".join(f'<item android:offset="{o}" android:color="{c}"/>' for o, c in stops)
    return (f'<aapt:attr name="{attr}"><gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" '
            f'android:endX="{x2}" android:endY="{y2}">{items}</gradient></aapt:attr>')


def android_mark(paint):
    """paint is a colour, or a gradient (as aapt:attr XML) for fill and stroke."""
    def colour(attr):
        if paint.startswith("#"):
            return f' android:{attr}="{paint}"', ""
        return "", paint.replace('name="X"', f'name="android:{attr}"')
    out = []
    for d, kind, rot in mark():
        fa, fg = colour("fillColor")
        sa, sg = colour("strokeColor")
        if kind == "fill":
            p = f'<path android:fillType="evenOdd"{fa} android:pathData="{d}">{fg}</path>'
        elif kind == "solid":
            p = (f'<path{fa}{sa} android:strokeWidth="1.8" android:strokeLineJoin="round" '
                 f'android:pathData="{d}">{fg}{sg}</path>')
        else:
            p = (f'<path{sa} android:strokeWidth="2.6" android:strokeLineJoin="round" '
                 f'android:pathData="{d}">{sg}</path>')
        if rot:
            p = f'<group android:rotation="{rot}" android:pivotX="{PIVOT[0]}" android:pivotY="{PIVOT[1]}">{p}</group>'
        out.append(p)
    return "\n        ".join(out)


def android_foreground(paint):
    # Adaptive icons are 108 dp with the visible part in the middle 72 and a 66 dp safe circle.
    k, tx, ty = fit(108, 48, 44, 54, 54)
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- Made by scripts/brand/make_logo.py; edit that, not this. -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android" {AAPT}\n'
            f'    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
            f'    <group android:translateX="{tx:.3f}" android:translateY="{ty:.3f}" android:scaleX="{k:.4f}" android:scaleY="{k:.4f}">\n'
            f'        {android_mark(paint)}\n    </group>\n</vector>\n')


def android_background(dark):
    if dark:
        fill = android_gradient("android:fillColor", [(0, android_colour(NIGHT_TOP)), (1, android_colour(NIGHT_BOTTOM))], 54, 0, 54, 108)
        layers = f'<path android:pathData="M0,0h108v108h-108z">{fill}</path>'
    else:
        fill = android_gradient("android:fillColor", [(0, android_colour(GOLD)), (0.55, android_colour(CORAL)), (1, android_colour(MAGENTA))], 0, 0, 108, 108)
        shade = android_gradient("android:fillColor", [(0.55, android_colour("#000000", 0)), (1, android_colour("#000000", 0.18))], 54, 0, 54, 108)
        light = ('<aapt:attr name="android:fillColor"><gradient android:type="radial" android:centerX="24" android:centerY="5" '
                 f'android:gradientRadius="92"><item android:offset="0" android:color="{android_colour("#FFFFFF", 0.38)}"/>'
                 f'<item android:offset="0.55" android:color="{android_colour("#FFFFFF", 0)}"/></gradient></aapt:attr>')
        layers = "\n    ".join(f'<path android:pathData="M0,0h108v108h-108z">{g}</path>' for g in (fill, shade, light))
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- Made by scripts/brand/make_logo.py; edit that, not this. -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android" {AAPT}\n'
            f'    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
            f'    {layers}\n</vector>\n')


def main():
    sunset = android_gradient("X", [(0, android_colour(GOLD)), (0.55, android_colour(CORAL)), (1, android_colour(MAGENTA))], 10, 12, 54, 60)
    files = {
        ROOT / "web/public/favicon.svg": favicon(),
        ROOT / "desktop/icon.svg": mac_icon(),
        RES / "drawable/ic_launcher_foreground.xml": android_foreground("#FFFFFFFF"),
        RES / "drawable/ic_launcher_background.xml": android_background(False),
        RES / "drawable-night/ic_launcher_foreground.xml": android_foreground(sunset),
        RES / "drawable-night/ic_launcher_background.xml": android_background(True),
        RES / "drawable/ic_launcher_monochrome.xml": android_foreground("#FFFFFFFF"),
    }
    for path, text in files.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        print("wrote", path.relative_to(ROOT))


if __name__ == "__main__":
    main()
