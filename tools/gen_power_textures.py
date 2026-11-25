#!/usr/bin/env python3
"""Generate the 16x16 placeholder textures for the three power blocks.

These are deliberately procedural: the shape lives in the blockstate/model JSON and the real art will
replace these PNGs later, so the point is a readable silhouette and the right colour language next to
`charging_dock` (dark grey housing, warm indicator). Run from the project root:

    python3 tools/gen_power_textures.py

Outputs: src/main/resources/assets/huhu_dronepal/textures/block/{burner_generator,solar_generator,
battery_bank}_{top,side,bottom}.png plus a contact sheet under artifacts/power-blocks/.
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/assets/huhu_dronepal/textures/block"
SHEET = ROOT / "artifacts/power-blocks/textures-preview.png"
SIZE = 16

# Housing palette, shared with the dock's dark metal look.
DARK = (30, 33, 36, 255)
BODY = (52, 57, 62, 255)
BODY_HI = (68, 74, 80, 255)
EDGE = (94, 101, 109, 255)
SHADOW = (22, 24, 27, 255)
ORANGE = (240, 132, 26, 255)
ORANGE_HI = (255, 190, 92, 255)
ORANGE_DK = (150, 72, 10, 255)
PANEL = (18, 44, 72, 255)
PANEL_HI = (28, 66, 104, 255)
GRID = (86, 150, 205, 255)
GRID_HI = (150, 200, 235, 255)
AMBER = (226, 166, 58, 255)
GREEN = (108, 199, 135, 255)
BAR_DK = (46, 40, 26, 255)


def canvas(fill=(0, 0, 0, 0)):
    return [[fill for _ in range(SIZE)] for _ in range(SIZE)]


def rect(px, x0, y0, x1, y1, color):
    """Inclusive rectangle."""
    for y in range(max(0, y0), min(SIZE - 1, y1) + 1):
        for x in range(max(0, x0), min(SIZE - 1, x1) + 1):
            px[y][x] = color


def frame(px, color=EDGE, inner=SHADOW):
    """One pixel of raised metal around the tile, one pixel of shadow inside it."""
    rect(px, 0, 0, SIZE - 1, 0, color)
    rect(px, 0, SIZE - 1, SIZE - 1, SIZE - 1, inner)
    rect(px, 0, 0, 0, SIZE - 1, color)
    rect(px, SIZE - 1, 0, SIZE - 1, SIZE - 1, inner)
    rect(px, 1, 1, SIZE - 2, SIZE - 2, BODY)


def rivets(px, color=EDGE):
    for x, y in ((1, 1), (SIZE - 2, 1), (1, SIZE - 2), (SIZE - 2, SIZE - 2)):
        px[y][x] = color


def save(px, name):
    img = Image.new("RGBA", (SIZE, SIZE))
    img.putdata([px[y][x] for y in range(SIZE) for x in range(SIZE)])
    img.save(OUT / f"{name}.png")
    return img


# ---- burner generator: dark housing, orange indicator grille on the front ----

def burner_side():
    px = canvas()
    frame(px)
    rivets(px)
    # Recessed indicator window.
    rect(px, 3, 4, 12, 11, DARK)
    rect(px, 3, 4, 12, 4, SHADOW)
    for y in (5, 7, 9):
        rect(px, 4, y, 11, y, ORANGE)
        rect(px, 4, y, 11, y, ORANGE_HI)
    rect(px, 4, 6, 11, 6, ORANGE_DK)
    rect(px, 4, 8, 11, 8, ORANGE_DK)
    rect(px, 4, 10, 11, 10, ORANGE_DK)
    # Vent slots at the bottom edge.
    for x in range(3, 13, 3):
        rect(px, x, 13, x + 1, 13, SHADOW)
    return px


def burner_top():
    px = canvas()
    frame(px)
    rivets(px)
    rect(px, 3, 3, 12, 12, DARK)
    rect(px, 4, 4, 11, 11, ORANGE_DK)
    rect(px, 5, 5, 10, 10, ORANGE)
    rect(px, 6, 6, 9, 9, ORANGE_HI)
    rect(px, 7, 7, 8, 8, (255, 236, 200, 255))
    # Grate bars over the flame.
    for x in (5, 8, 11):
        rect(px, x, 4, x, 11, DARK)
    for y in (5, 8, 11):
        rect(px, 4, y, 11, y, DARK)
    return px


def burner_bottom():
    px = canvas()
    frame(px)
    rivets(px)
    rect(px, 3, 3, 12, 12, BODY_HI)
    rect(px, 5, 5, 10, 10, DARK)
    for x in range(4, 12, 2):
        rect(px, x, 12, x, 13, SHADOW)
    return px


# ---- solar generator: deep blue photovoltaic panel with a light blue cell grid ----

def solar_top():
    px = canvas()
    frame(px, color=EDGE, inner=PANEL)
    rivets(px)
    rect(px, 2, 2, 13, 13, PANEL)
    # 2x2 cells, each with a highlight in the top-left corner.
    for cx, cy in ((2, 2), (8, 2), (2, 8), (8, 8)):
        rect(px, cx, cy, cx + 5, cy + 5, PANEL_HI)
        rect(px, cx, cy, cx + 5, cy, GRID)
        rect(px, cx, cy, cx, cy + 5, GRID)
    rect(px, 7, 2, 7, 13, GRID)
    rect(px, 2, 7, 13, 7, GRID)
    rect(px, 3, 3, 4, 3, GRID_HI)
    rect(px, 9, 3, 10, 3, GRID_HI)
    rect(px, 3, 9, 4, 9, GRID_HI)
    rect(px, 9, 9, 10, 9, GRID_HI)
    return px


def solar_side():
    px = canvas()
    frame(px)
    rivets(px)
    # A shallow window into the panel edge, plus a status lamp.
    rect(px, 3, 5, 12, 9, PANEL)
    rect(px, 3, 5, 12, 5, GRID)
    rect(px, 3, 9, 12, 9, DARK)
    for x in range(5, 12, 3):
        rect(px, x, 6, x, 8, PANEL_HI)
    rect(px, 12, 2, 13, 3, GRID_HI)
    for x in range(3, 13, 2):
        rect(px, x, 12, x, 12, SHADOW)
    return px


def solar_bottom():
    px = canvas()
    frame(px)
    rivets(px)
    rect(px, 3, 3, 12, 12, BODY_HI)
    rect(px, 5, 5, 10, 10, DARK)
    rect(px, 6, 6, 9, 9, (40, 46, 52, 255))
    return px


# ---- battery bank: dark housing, three charge bars ----

def bank_side():
    px = canvas()
    frame(px)
    rivets(px)
    rect(px, 3, 3, 12, 12, DARK)
    rect(px, 3, 3, 12, 3, SHADOW)
    # Exactly three charge bars, as the tooltip promises.
    for y, color, lit in ((5, GREEN, 8), (8, AMBER, 6), (11, AMBER, 3)):
        rect(px, 4, y, 11, y + 1, BAR_DK)
        rect(px, 4, y, 3 + lit, y, color)
        rect(px, 4, y, 3 + lit, y, color)
    rect(px, 4, 7, 11, 7, BODY_HI)
    rect(px, 4, 10, 11, 10, BODY_HI)
    return px


def bank_top():
    px = canvas()
    frame(px)
    rivets(px)
    rect(px, 3, 3, 12, 12, BODY_HI)
    # Two terminals: + on the left, - on the right.
    rect(px, 4, 4, 7, 7, AMBER)
    rect(px, 5, 5, 6, 6, (255, 214, 140, 255))
    rect(px, 9, 4, 11, 6, EDGE)
    rect(px, 5, 9, 6, 11, EDGE)
    rect(px, 9, 9, 11, 11, GREEN)
    rect(px, 10, 10, 10, 10, (180, 240, 200, 255))
    return px


def bank_bottom():
    px = canvas()
    frame(px)
    rivets(px)
    rect(px, 3, 3, 12, 12, BODY)
    rect(px, 4, 4, 11, 11, DARK)
    for x in (5, 7, 9, 11):
        rect(px, x, 5, x, 10, SHADOW)
    return px


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    SHEET.parent.mkdir(parents=True, exist_ok=True)
    tiles = [
        ("burner_generator_side", burner_side()),
        ("burner_generator_top", burner_top()),
        ("burner_generator_bottom", burner_bottom()),
        ("solar_generator_side", solar_side()),
        ("solar_generator_top", solar_top()),
        ("solar_generator_bottom", solar_bottom()),
        ("battery_bank_side", bank_side()),
        ("battery_bank_top", bank_top()),
        ("battery_bank_bottom", bank_bottom()),
    ]
    images = [save(px, name) for name, px in tiles]
    scale = 8
    sheet = Image.new("RGBA", (SIZE * scale * 3 + 16, SIZE * scale * 3 + 16), (30, 32, 36, 255))
    for index, img in enumerate(images):
        big = img.resize((SIZE * scale, SIZE * scale), Image.NEAREST)
        sheet.paste(big, ((index % 3) * (SIZE * scale + 4) + 4, (index // 3) * (SIZE * scale + 4) + 4), big)
    sheet.save(SHEET)
    print(f"wrote {len(images)} textures to {OUT}")
    print(f"preview: {SHEET}")


if __name__ == "__main__":
    main()
