"""Generates every platform's app icon from one mark: "Lz" in Literata
SemiBold on a rounded square, as in the navigation rail (App.kt NavRail:
primaryContainer tile, onPrimaryContainer text, 16/56 corners).

Needs fontTools and Pillow:
    python3 -m venv /tmp/icons && /tmp/icons/bin/pip install fonttools pillow
    /tmp/icons/bin/python branding/generate_icons.py      # from frontend/

Writes (paths relative to frontend/):
    branding/libraryz-icon.svg                 master, rounded square
    branding/ios-AppIcon-1024.png              full bleed (iOS masks it)
    androidApp/src/main/res/drawable/ic_launcher_{foreground,monochrome}.xml
    androidApp/src/main/res/values/ic_launcher_background.xml
    androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml
    composeApp/src/desktopMain/resources/libraryz-icon.png   window icon
    composeApp/icons/libraryz.{png,ico,icns}                 installers
    composeApp/src/wasmJsMain/resources/{favicon.svg,favicon.ico,
        apple-touch-icon.png,icon-192.png,icon-512.png}
"""

import io
import os

from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "composeApp/src/commonMain/composeResources/font/literata.ttf")

# Theme.kt light scheme.
TILE = "#C6E7D8"  # primaryContainer
INK = "#0A3428"  # onPrimaryContainer
RADIUS = 16 / 56  # of the tile's side
EM = 0.42  # font size, of the tile's side (the rail: 22sp on 56dp ≈ 0.39)
TEXT = "Lz"


def mark_contours():
    """The text's outlines as (commands, ink bounds), in font units, y up."""
    font = instantiateVariableFont(TTFont(FONT), {"wght": 600, "opsz": 24})
    cmap, glyphs = font.getBestCmap(), font.getGlyphSet()
    pen, x = RecordingPen(), 0
    for ch in TEXT:
        name = cmap[ord(ch)]
        glyphs[name].draw(TransformPen(pen, (1, 0, 0, 1, x, 0)))
        x += glyphs[name].width
    xs, ys = [], []
    for _, pts in pen.value:
        for px, py in pts:
            xs.append(px)
            ys.append(py)
    return pen, font["head"].unitsPerEm, (min(xs), min(ys), max(xs), max(ys))


PEN, UPM, (X0, Y0, X1, Y1) = mark_contours()


def placement(side, cx, cy):
    """Affine (a, b, c, d, e, f) putting the ink centered on (cx, cy), y down,
    at EM × side per em."""
    s = EM * side / UPM
    return (s, 0, 0, -s, cx - s * (X0 + X1) / 2, cy + s * (Y0 + Y1) / 2)


def svg_path(transform):
    pen = SVGPathPen(None, ntos=lambda v: f"{v:.2f}".rstrip("0").rstrip("."))
    PEN.replay(TransformPen(pen, transform))
    return pen.getCommands()


def polygons(transform, steps=12):
    """Contours flattened to point lists (quadratic and cubic segments)."""
    a, b, c, d, e, f = transform
    tf = lambda p: (a * p[0] + c * p[1] + e, b * p[0] + d * p[1] + f)
    out, cur = [], []

    def quad(p0, p1, p2):
        for i in range(1, steps + 1):
            t = i / steps
            u = 1 - t
            cur.append(tuple(u * u * p0[k] + 2 * u * t * p1[k] + t * t * p2[k] for k in (0, 1)))

    for op, pts in PEN.value:
        pts = [tf(p) for p in pts]
        if op == "moveTo":
            cur = [pts[0]]
        elif op == "lineTo":
            cur.append(pts[0])
        elif op == "qCurveTo":
            # TrueType implied on-curve points between consecutive off-curve ones.
            start, offs, end = cur[-1], pts[:-1], pts[-1]
            for i, p in enumerate(offs):
                nxt = end if i == len(offs) - 1 else ((p[0] + offs[i + 1][0]) / 2, (p[1] + offs[i + 1][1]) / 2)
                quad(start, p, nxt)
                start = nxt
        elif op == "curveTo":
            p0, (p1, p2, p3) = cur[-1], pts
            for i in range(1, steps + 1):
                t = i / steps
                u = 1 - t
                cur.append(tuple(u**3 * p0[k] + 3 * u * u * t * p1[k] + 3 * u * t * t * p2[k] + t**3 * p3[k] for k in (0, 1)))
        elif op in ("closePath", "endPath"):
            out.append(cur)
            cur = []
    return out


def render(size, inset=0.0, rounded=True):
    """A size × size RGBA icon: the tile inset by `inset` × size per side."""
    ss = 4  # supersampling
    big = size * ss
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    m = inset * big
    side = big - 2 * m
    if rounded:
        draw.rounded_rectangle((m, m, big - m - 1, big - m - 1), radius=RADIUS * side, fill=TILE)
    else:
        draw.rectangle((0, 0, big, big), fill=TILE)
    for poly in polygons(placement(side, big / 2, big / 2)):
        draw.polygon(poly, fill=INK)
    return img.resize((size, size), Image.LANCZOS)


def write(rel, data, mode="w"):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, mode) as f:
        f.write(data)


def save_png(rel, img):
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    write(rel, buf.getvalue(), "wb")


def svg(side=1024, inset=0.0):
    m = inset * side
    s = side - 2 * m
    path = svg_path(placement(s, side / 2, side / 2))
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {side} {side}">\n'
        f'  <rect x="{m:g}" y="{m:g}" width="{s:g}" height="{s:g}" rx="{RADIUS * s:.2f}" fill="{TILE}"/>\n'
        f'  <path fill="{INK}" d="{path}"/>\n'
        f"</svg>\n"
    )


ANDROID_HEADER = '<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by branding/generate_icons.py. -->\n'


def android():
    # Adaptive icon: a 108dp layer, of which launchers show at least the
    # middle 72dp (masked to their shape). The text sizes to that 72dp tile.
    path = svg_path(placement(72, 54, 54))
    res = "androidApp/src/main/res/"
    for name in ("foreground", "monochrome"):
        color = INK if name == "foreground" else "#FFFFFFFF"
        write(
            f"{res}drawable/ic_launcher_{name}.xml",
            ANDROID_HEADER
            + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            f'    <path\n        android:fillColor="{color}"\n        android:pathData="{path}" />\n'
            "</vector>\n",
        )
    write(
        f"{res}values/ic_launcher_background.xml",
        ANDROID_HEADER + f'<resources>\n    <color name="ic_launcher_background">{TILE}</color>\n</resources>\n',
    )
    adaptive = (
        ANDROID_HEADER
        + '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
        "</adaptive-icon>\n"
    )
    write(f"{res}mipmap-anydpi-v26/ic_launcher.xml", adaptive)
    write(f"{res}mipmap-anydpi-v26/ic_launcher_round.xml", adaptive)


def desktop():
    # Linux and Windows draw the image as is: a rounded square with a little air.
    plain = lambda n: render(n, inset=1 / 32)
    save_png("composeApp/src/desktopMain/resources/libraryz-icon.png", plain(256))
    save_png("composeApp/icons/libraryz.png", plain(512))
    sizes = [16, 24, 32, 48, 64, 128, 256]
    big = plain(256)
    big.save(os.path.join(ROOT, "composeApp/icons/libraryz.ico"), sizes=[(n, n) for n in sizes],
             append_images=[plain(n) for n in sizes[:-1]])
    # macOS: Apple's grid, an 824 tile on a 1024 canvas.
    mac = render(1024, inset=100 / 1024)
    mac.save(os.path.join(ROOT, "composeApp/icons/libraryz.icns"))


def web():
    res = "composeApp/src/wasmJsMain/resources/"
    write(f"{res}favicon.svg", svg())
    tiny = [render(n) for n in (16, 32, 48)]
    tiny[-1].save(os.path.join(ROOT, f"{res}favicon.ico"), sizes=[(16, 16), (32, 32), (48, 48)],
                  append_images=tiny[:-1])
    # iOS home screen: full bleed, Safari rounds it.
    save_png(f"{res}apple-touch-icon.png", render(180, rounded=False).convert("RGB"))
    save_png(f"{res}icon-192.png", render(192))
    save_png(f"{res}icon-512.png", render(512))


if __name__ == "__main__":
    write("branding/libraryz-icon.svg", svg())
    save_png("branding/ios-AppIcon-1024.png", render(1024, rounded=False).convert("RGB"))
    android()
    desktop()
    web()
