#!/usr/bin/env python3
"""Compose the deliverable previews from the Blockbench renders.

  previews/<object>-front.png / -right.png / -iso.png   768x768, transparent
  previews/<object>-3view.png                           768x768 2x2 sheet (3 views + spec card)
  previews/mill-blocks-lineup.png                        iso lineup, one render => one scale
  previews/mill-blocks-lineup-front.png                  front elevation, same scale

`<object>` is one of ore_mill / water_pump / power_tower / water_pipe; the two
multi-cell machines are rendered as their stacked assemblies (that is what the
player actually sees), the pipes as the five connection shapes in one scene.
"""
from __future__ import annotations

import json
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.dirname(HERE)
SRC = os.path.join(HERE, "renders-src")
OUT = os.path.join(ART, "previews")

OBJECTS = ["ore_mill", "water_pump", "power_tower", "water_pipe"]
SUMMARY = json.load(open(os.path.join(HERE, "build-summary.json")))

# per-object spec card text: title, cell count / real footprint, cubes+faces
CELLS = {
    "ore_mill": ["ore_mill", "ore_mill_side"],
    "water_pump": ["water_pump"],
    "power_tower": ["power_tower", "power_tower_mid", "power_tower_top"],
    "water_pipe": ["water_pipe_straight", "water_pipe_corner", "water_pipe_tee",
                   "water_pipe_cross", "water_pipe_cap"],
}
TITLES = {
    "ore_mill": "ORE MILL  (2 cells wide)",
    "water_pump": "WATER PUMP",
    "power_tower": "POWER TOWER  (3 cells)",
    "water_pipe": "WATER PIPE  (5 shapes)",
}

FONT_CANDIDATES = [
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/System/Library/Fonts/Supplemental/Arial.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
    "/System/Library/Fonts/SFNSMono.ttf",
]


def font(size):
    for p in FONT_CANDIDATES:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    return ImageFont.load_default()


def normalize(path, canvas=768, fill=0.90):
    im = Image.open(path).convert("RGBA")
    bbox = im.getbbox()
    im = im.crop(bbox)
    w, h = im.size
    target = canvas * fill
    s = min(target / w, target / h)
    im = im.resize((max(1, round(w * s)), max(1, round(h * s))), Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.alpha_composite(im, ((canvas - im.width) // 2, (canvas - im.height) // 2))
    return out


AABB_TEXT = {
    "ore_mill": "AABB 15.9x15.2x15.5 / 15.6x15.8x15.5",
    "water_pump": "AABB 14.0x13.0x14.0",
    "power_tower": "AABB 14x15.8 / 12.7x15.6 / 9.4x14.8",
    "water_pipe": "tube D6.0, flange D7.5",
}


def spec_lines(obj):
    cells = CELLS[obj]
    cubes = sum(SUMMARY[c]["cubes"] for c in cells)
    faces = sum(SUMMARY[c]["faces"] for c in cells)
    rot = sum(SUMMARY[c]["rotated"] for c in cells)
    lines = [
        f"{len(cells)} cell{'s' if len(cells) > 1 else ''}, 16 units per cell",
        f"{cubes} cubes / {faces} faces / {rot} rotated",
        AABB_TEXT[obj],
        "front = north / 32x32 atlas",
    ]
    if obj == "water_pipe":
        lines[0] = "5 shapes, 16 units per cell"
        lines.insert(1, "straight = N-S; x:90 = vertical")
    return lines


def three_view_sheet(obj, views):
    canvas = 768
    cell = canvas // 2
    sheet = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    for i, key in enumerate(("front", "right", "iso")):
        im = views[key].resize((cell, cell), Image.LANCZOS)
        x = (i % 2) * cell
        y = (i // 2) * cell
        sheet.alpha_composite(im, (x, y))
        d = ImageDraw.Draw(sheet)
        if i % 2 == 1:
            d.line([(cell - 1, y), (cell - 1, y + cell - 1)], fill=(255, 255, 255, 26), width=1)
        if i >= 2:
            d.line([(x, cell - 1), (x + cell - 1, cell - 1)], fill=(255, 255, 255, 26), width=1)

    card = Image.new("RGBA", (cell, cell), (0, 0, 0, 0))
    d = ImageDraw.Draw(card)
    f_t, f_s = font(25), font(15)
    y = 96
    d.text((26, y), TITLES[obj], font=f_t, fill=(84, 92, 104, 246))
    y += 38
    d.line([(26, y), (26 + 150, y)], fill=(255, 180, 77, 220), width=2)
    y += 18
    for ln in spec_lines(obj):
        d.text((26, y), ln, font=f_s, fill=(98, 106, 118, 246))
        y += 26
    sheet.alpha_composite(card, (cell, cell))
    return sheet


def main():
    os.makedirs(OUT, exist_ok=True)
    for obj in OBJECTS:
        views = {}
        for key, suffix in (("front", "front"), ("right", "right"), ("iso", "iso_fr")):
            src = os.path.join(SRC, f"{obj}-{suffix}.png")
            im = normalize(src)
            im.save(os.path.join(OUT, f"{obj}-{key}.png"))
            views[key] = im
        three_view_sheet(obj, views).save(os.path.join(OUT, f"{obj}-3view.png"))
        print("wrote previews for", obj)

    for src, dst in (("lineup-iso.png", "mill-blocks-lineup.png"),
                     ("lineup-front.png", "mill-blocks-lineup-front.png"),
                     ("water_pipe-set-iso.png", "water_pipe-set.png")):
        im = Image.open(os.path.join(SRC, src)).convert("RGBA")
        im = im.crop(im.getbbox())
        pad = 34
        out = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), (0, 0, 0, 0))
        out.alpha_composite(im, (pad, pad))
        out.save(os.path.join(OUT, dst))
        print("wrote", dst, out.size)


if __name__ == "__main__":
    main()
