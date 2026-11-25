#!/usr/bin/env python3
"""Compose the deliverable previews from the Blockbench renders.

  previews/<name>-front.png / -right.png / -iso.png   768x768, transparent
  previews/<name>-3view.png                          768x768 2x2 sheet (3 views + spec card)
  previews/power-blocks-lineup.png                   1600x800 iso lineup (one render => one scale)
  previews/power-blocks-lineup-front.png             1600x800 front elevation lineup
"""
from __future__ import annotations

import os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.dirname(HERE)
SRC = os.path.join(HERE, "renders-src")
OUT = os.path.join(ART, "previews")

NAMES = ["burner_generator", "solar_generator", "battery_bank"]
SPECS = {
    "burner_generator": ("BURNER GENERATOR", "12 x 16 x 12 px", "52 cubes / 312 faces"),
    "solar_generator": ("SOLAR GENERATOR", "15 x 11.7 x 15 px", "39 cubes / 234 faces"),
    "battery_bank": ("BATTERY BANK", "15.1 x 11.4 x 11.2 px", "39 cubes / 234 faces"),
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


def normalize(path, canvas=768, fill=0.88):
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


def three_view_sheet(name, views):
    """2x2 sheet: front / right / iso + a small spec card (keeps 768x768)."""
    canvas = 768
    cell = canvas // 2
    sheet = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    for i, key in enumerate(("front", "right", "iso")):
        im = views[key].resize((cell, cell), Image.LANCZOS)
        x = (i % 2) * cell
        y = (i // 2) * cell
        sheet.alpha_composite(im, (x, y))
        # hairline separators so the panels read as three plates
        d = ImageDraw.Draw(sheet)
        if i % 2 == 1:
            d.line([(cell - 1, y), (cell - 1, y + cell - 1)], fill=(255, 255, 255, 26), width=1)
        if i >= 2:
            d.line([(x, cell - 1), (x + cell - 1, cell - 1)], fill=(255, 255, 255, 26), width=1)

    # spec card in the bottom-right cell
    card = Image.new("RGBA", (cell, cell), (0, 0, 0, 0))
    d = ImageDraw.Draw(card)
    title, dims, counts = SPECS[name]
    f_t, f_s = font(26), font(19)
    y = 120
    d.text((26, y), title, font=f_t, fill=(78, 86, 98, 245))
    y += 40
    d.line([(26, y), (26 + 150, y)], fill=(255, 180, 77, 220), width=2)
    y += 22
    d.text((26, y), "block size  " + dims.replace(" px", ""), font=f_s, fill=(96, 104, 116, 245))
    y += 28
    d.text((26, y), counts, font=f_s, fill=(96, 104, 116, 245))
    y += 28
    d.text((26, y), "front = north face / 32x32 atlas", font=f_s, fill=(120, 128, 140, 245))
    sheet.alpha_composite(card, (cell, cell))
    return sheet


def main():
    os.makedirs(OUT, exist_ok=True)
    for name in NAMES:
        views = {}
        for key, suffix in (("front", "front"), ("right", "right"), ("iso", "iso_fr")):
            src = os.path.join(SRC, f"{name}-{suffix}.png")
            im = normalize(src)
            im.save(os.path.join(OUT, f"{name}-{key}.png"))
            views[key] = im
        sheet = three_view_sheet(name, views)
        sheet.save(os.path.join(OUT, f"{name}-3view.png"))
        print("wrote previews for", name)

    for src, dst in (("lineup-iso.png", "power-blocks-lineup.png"),
                     ("lineup-front.png", "power-blocks-lineup-front.png")):
        im = Image.open(os.path.join(SRC, src)).convert("RGBA")
        bbox = im.getbbox()
        im = im.crop(bbox)
        # keep both lineup strips on one common scale: fit width, same margin
        pad = 40
        out = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), (0, 0, 0, 0))
        out.alpha_composite(im, (pad, pad))
        out.save(os.path.join(OUT, dst))
        print("wrote", dst, out.size)


if __name__ == "__main__":
    main()
