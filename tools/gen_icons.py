"""Original icons for Tank Manager, drawn from simple shapes.

Every icon is a list of elements, written both as an Android VectorDrawable and as an SVG
(for previews and for rendering the PNG launcher icons).
Usage (needs cairosvg and pillow):  python3 tools/gen_icons.py app/src/main/res <preview dir>
Writes res/drawable/ic_*.xml, the legacy launcher icons in res/mipmap-*/ and
app/src/main/ic_launcher-playstore.png, and PNG previews into the preview dir.

Element: ("path", d, attrs) or ("group", transform, children, clip_d)
attrs: fill, stroke, width, cap, join
"""
import os
import sys

BLUE = "#2F5D8A"
DARK = "#1F3A5F"
WATER = "#3BA7E0"
WATER_LIGHT = "#8FD3F4"
TANK = "#EAF6FC"
GAUGE = "#F2C230"
SPARK = "#E8892B"
INK = "#222222"


def p(d, fill=None, stroke=None, width=None, cap="round", join="round"):
    return ("path", d, dict(fill=fill, stroke=stroke, width=width, cap=cap, join=join))


def g(children, rotate=None, translate=(0, 0), scale=1.0, clip=None):
    return ("group", dict(rotate=rotate, translate=translate, scale=scale), children, clip)


# --- Device list: radio mast with a beacon and radio waves ----------------------------------
BLUE_MID = "#5B8CC0"
BLUE_LIGHT = "#9DBFE0"
TUBE_LIGHT = "#7FA8D4"


def tube(d, outer=3.0, inner=1.2, cap="round"):
    """A line drawn as a tube: dark outline with a lighter core"""
    return [p(d, stroke=DARK, width=outer, cap=cap), p(d, stroke=TUBE_LIGHT, width=inner, cap=cap)]


def arc_pair(cx, cy, r, deg, color, width):
    from math import cos, sin, radians
    dx, dy = r * cos(radians(deg)), r * sin(radians(deg))
    left = f"M{cx - dx:.2f},{cy - dy:.2f} A{r},{r} 0 0,0 {cx - dx:.2f},{cy + dy:.2f}"
    right = f"M{cx + dx:.2f},{cy - dy:.2f} A{r},{r} 0 0,1 {cx + dx:.2f},{cy + dy:.2f}"
    return [p(left, stroke=color, width=width), p(right, stroke=color, width=width)]


def devices():
    cx, cy = 12, 8.5
    return [
        *arc_pair(cx, cy, 9.6, 40, BLUE_LIGHT, 1.6),
        *arc_pair(cx, cy, 6.8, 40, BLUE_MID, 1.7),
        *arc_pair(cx, cy, 4.0, 40, BLUE, 1.8),
        # Lattice mast: cross braces between two legs, and a foot
        p("M10.55,13.8 L13.45,13.8 M9.02,17.3 L14.98,17.3 M10.55,13.8 L14.98,17.3 M13.45,13.8 L9.02,17.3 M9.02,17.3 L16.60,21.0 M14.98,17.3 L7.40,21.0", stroke=DARK, width=0.8),
        *tube("M12,10.5 L7.40,21", outer=2.6, inner=1.0),
        *tube("M12,10.5 L16.60,21", outer=2.6, inner=1.0),
        p("M6.5,21.6 L17.5,21.6", stroke=DARK, width=1.8),
        # Beacon on top
        p("M10.1,8.5 A1.9,1.9 0 1,0 13.9,8.5 A1.9,1.9 0 1,0 10.1,8.5 Z", fill=SPARK, stroke=DARK, width=1),
        p("M11.4,7.9 A0.6,0.6 0 1,0 12.6,7.9 A0.6,0.6 0 1,0 11.4,7.9 Z", fill="#FFE2B8"),
    ]


# --- Disconnect: a chain link broken in two, with a spark in the break ---------------------
def star(cx, cy, r_out, r_in, n):
    from math import cos, sin, pi
    pts = []
    for i in range(2 * n):
        r = r_out if i % 2 == 0 else r_in
        a = pi * i / n - pi / 2
        pts.append(f"{cx + r * cos(a):.2f},{cy + r * sin(a):.2f}")
    return "M" + " L".join(pts) + " Z"


def disconnect():
    # Lying horizontally; the group turns everything by -45 degrees
    return [g([
        *tube("M8.6,8.6 L5,8.6 A3.4,3.4 0 0,0 5,15.4 L8.6,15.4", outer=3.2, inner=1.3),
        *tube("M15.4,8.6 L19,8.6 A3.4,3.4 0 0,1 19,15.4 L15.4,15.4", outer=3.2, inner=1.3),
        p(star(12, 12, 3.0, 1.3, 7), fill=GAUGE, stroke=SPARK, width=0.8),
        p("M12,7.2 L12,5.4 M12,16.8 L12,18.6 M9.9,6.6 L9.3,5.2 M14.1,17.4 L14.7,18.8",
          stroke=SPARK, width=1.1),
    ], rotate=-45)]


# --- Back: arrow turning back to the left ---------------------------------------------------
def back():
    s = dict(stroke=INK, width=2.2)
    return [
        p("M9,5 L5,9 L9,13", **s),
        p("M5,9 L15,9 A5,5 0 0,1 15,19 L11,19", **s),
    ]


# --- Logo: tank with water and a level gauge (viewport 48) ---------------------------------
TANK_BODY = "M12,12 L36,12 A6,6 0 0,1 42,18 L42,38 A6,6 0 0,1 36,44 L12,44 A6,6 0 0,1 6,38 L6,18 A6,6 0 0,1 12,12 Z"


def logo():
    water = [
        p("M4,27 Q8,24 12,27 T20,27 T28,27 T36,27 T44,27 L44,46 L4,46 Z", fill=WATER),
        p("M4,33 Q8,30.5 12,33 T20,33 T28,33 T36,33 T44,33", stroke=WATER_LIGHT, width=1.6),
    ]
    ticks = [p(f"M33,{y} L{36 if i % 2 else 37.5},{y}", stroke=DARK, width=1.4, cap="butt")
             for i, y in enumerate((9, 13, 17, 21, 25, 29))]
    return [
        p("M20,7 L28,7 L28,12 L20,12 Z", fill=TANK, stroke=DARK, width=2.5),
        p(TANK_BODY, fill=TANK),
        g(water, clip=TANK_BODY),
        p(TANK_BODY, stroke=DARK, width=2.5),
        p("M31,4 L39,4 L39,31 L31,31 Z", fill=GAUGE, stroke=DARK, width=2),
        *ticks,
    ]


def launcher_foreground():
    # Adaptive icon: 108 x 108, content inside the central 66 x 66 safe zone
    return [g(logo(), translate=(21.6, 21.6), scale=1.35)]


# --- writers ---------------------------------------------------------------------------------
def vd_path(d, a, indent):
    lines = [f'{indent}<path', f'{indent}    android:pathData="{d}"']
    if a["fill"]:
        lines.append(f'{indent}    android:fillColor="{a["fill"]}"')
    if a["stroke"]:
        lines += [f'{indent}    android:strokeColor="{a["stroke"]}"',
                  f'{indent}    android:strokeWidth="{a["width"]}"',
                  f'{indent}    android:strokeLineCap="{a["cap"]}"',
                  f'{indent}    android:strokeLineJoin="{a["join"]}"']
    lines[-1] += " />"
    return "\n".join(lines)


def vd_elements(elements, indent, size):
    out = []
    for e in elements:
        if e[0] == "path":
            out.append(vd_path(e[1], e[2], indent))
        else:
            t, children, clip = e[1], e[2], e[3]
            attrs = []
            if t["rotate"] is not None:
                attrs += [f'android:rotation="{t["rotate"]}"', f'android:pivotX="{size / 2}"',
                          f'android:pivotY="{size / 2}"']
            if t["translate"] != (0, 0):
                attrs += [f'android:translateX="{t["translate"][0]}"',
                          f'android:translateY="{t["translate"][1]}"']
            if t["scale"] != 1.0:
                attrs += [f'android:scaleX="{t["scale"]}"', f'android:scaleY="{t["scale"]}"']
            head = f"{indent}<group" + "".join(f"\n{indent}    {a}" for a in attrs) + ">"
            body = []
            if clip:
                body.append(f'{indent}    <clip-path android:pathData="{clip}" />')
            body.append(vd_elements(children, indent + "    ", size))
            out.append("\n".join([head, *body, f"{indent}</group>"]))
    return "\n".join(out)


def vector_drawable(elements, size, dp):
    return (f'<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- Generated by the icon script; drawn for this project, no third-party artwork. -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{dp}dp"\n    android:height="{dp}dp"\n'
            f'    android:viewportWidth="{size}"\n    android:viewportHeight="{size}">\n'
            f'{vd_elements(elements, "    ", size)}\n</vector>\n')


def svg_elements(elements, size, defs):
    out = []
    for e in elements:
        if e[0] == "path":
            a = e[2]
            style = f'fill="{a["fill"] or "none"}"'
            if a["stroke"]:
                style += (f' stroke="{a["stroke"]}" stroke-width="{a["width"]}"'
                          f' stroke-linecap="{a["cap"]}" stroke-linejoin="{a["join"]}"')
            out.append(f'<path d="{e[1]}" {style}/>')
        else:
            t, children, clip = e[1], e[2], e[3]
            tr = []
            if t["translate"] != (0, 0):
                tr.append(f"translate({t['translate'][0]},{t['translate'][1]})")
            if t["scale"] != 1.0:
                tr.append(f"scale({t['scale']})")
            if t["rotate"] is not None:
                tr.append(f"rotate({t['rotate']},{size / 2},{size / 2})")
            attr = f' transform="{" ".join(tr)}"' if tr else ""
            inner = svg_elements(children, size, defs)
            if clip:
                cid = f"c{len(defs)}"
                defs.append(f'<clipPath id="{cid}"><path d="{clip}"/></clipPath>')
                inner = f'<g clip-path="url(#{cid})">{inner}</g>'
            out.append(f"<g{attr}>{inner}</g>")
    return "".join(out)


def svg(elements, size, background=None, shape=None):
    defs = []
    body = svg_elements(elements, size, defs)
    bg = ""
    if background:
        if shape == "circle":
            bg = f'<circle cx="{size / 2}" cy="{size / 2}" r="{size / 2}" fill="{background}"/>'
        elif shape == "rounded":
            bg = f'<rect width="{size}" height="{size}" rx="{size * 0.18}" fill="{background}"/>'
        else:
            bg = f'<rect width="{size}" height="{size}" fill="{background}"/>'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}">'
            f'<defs>{"".join(defs)}</defs>{bg}{body}</svg>')


if __name__ == "__main__":
    res, preview = sys.argv[1], sys.argv[2]
    import cairosvg
    from PIL import Image
    import io

    icons = {"ic_devices": (devices(), 24, 48), "ic_disconnect": (disconnect(), 24, 48),
             "ic_back": (back(), 24, 48), "ic_logo": (logo(), 48, 56),
             "ic_launcher_foreground": (launcher_foreground(), 108, 108)}
    os.makedirs(preview, exist_ok=True)
    for name, (els, size, dp) in icons.items():
        with open(os.path.join(res, "drawable", name + ".xml"), "w") as f:
            f.write(vector_drawable(els, size, dp))
        cairosvg.svg2png(bytestring=svg(els, size).encode(), write_to=os.path.join(preview, name + ".png"),
                         output_width=256, output_height=256)

    # Legacy launcher icons (API 24-25) and the Play Store icon: background + foreground
    BG = "#F4EDCF"
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for density, px in sizes.items():
        for name, shape in (("ic_launcher", "rounded"), ("ic_launcher_round", "circle")):
            png = cairosvg.svg2png(bytestring=svg(launcher_foreground(), 108, BG, shape).encode(),
                                   output_width=px, output_height=px)
            Image.open(io.BytesIO(png)).save(os.path.join(res, f"mipmap-{density}", name + ".webp"),
                                             "WEBP", lossless=True)
    cairosvg.svg2png(bytestring=svg(launcher_foreground(), 108, BG).encode(),
                     write_to=os.path.join(res, "..", "ic_launcher-playstore.png"),
                     output_width=512, output_height=512)
    cairosvg.svg2png(bytestring=svg(launcher_foreground(), 108, BG, "circle").encode(),
                     write_to=os.path.join(preview, "launcher_round.png"), output_width=256, output_height=256)
