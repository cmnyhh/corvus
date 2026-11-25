#!/usr/bin/env python3
"""Face/UV calibration probe.

Builds a tiny YSM pack containing a single 16^3 cube whose faces sample
distinct, asymmetric marker regions of a 32x32 texture, so that a Blockbench
render tells us exactly

  * which texture axis maps to which model axis per face direction, and
  * whether the pack/render pipeline mirrors x.

Run:  python3 calib.py <out_pack_dir>
"""
import json, os, sys, struct, zlib

TEX = 32

# ---------------------------------------------------------------- png writer
def write_png(path, w, h, pixels):
    raw = b""
    for y in range(h):
        row = bytearray(b"\x00")
        for x in range(w):
            px = pixels[y][x]
            row += bytes(px[:4]) if len(px) >= 4 else bytes((px[0], px[1], px[2], 255))
        raw += bytes(row)
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


class Img:
    def __init__(self, w, h, fill=(30, 32, 38, 255)):
        self.w, self.h = w, h
        self.p = [[fill for _ in range(w)] for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.p[y][x] = c if len(c) == 4 else (c[0], c[1], c[2], 255)

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1):
            for x in range(x0, x1):
                self.set(x, y, c)

    def rows(self):
        return self.p


# 2px-tall "arrow" glyph patterns: F=fill so each region is visually distinct
GOLD = (255, 196, 80, 255)
RED = (232, 72, 72, 255)
CYAN = (90, 200, 255, 255)
WHITE = (240, 244, 250, 255)
GREY = (110, 118, 128, 255)


def draw_glyph(img, x, y, kind):
    """4x4 glyphs, distinguishable at a glance."""
    if kind == "F":       # forward-pointing arrow (points toward +x of the region)
        img.rect(x, y + 1, x + 3, y + 3, RED)
        img.rect(x + 3, y + 0, x + 4, y + 4, RED)
    elif kind == "L":     # L shape
        img.rect(x, y, x + 1, y + 4, CYAN)
        img.rect(x, y + 3, x + 4, y + 4, CYAN)
    elif kind == "T":
        img.rect(x, y, x + 4, y + 1, GOLD)
        img.rect(x + 1, y, x + 2, y + 4, GOLD)
    elif kind == "U":
        img.rect(x, y, x + 1, y + 4, WHITE)
        img.rect(x + 3, y, x + 4, y + 4, WHITE)
        img.rect(x, y + 3, x + 4, y + 4, WHITE)


def build(pack_dir):
    img = Img(TEX, TEX)
    # every face gets its own 16x16 quadrant, each with a 4x4 glyph in the
    # TOP-LEFT of the quadrant plus a 1px border so ordering is obvious
    parts = {
        "north": (0, 0, (60, 40, 40, 255), "F"),
        "east":  (16, 0, (40, 60, 40, 255), "L"),
        "south": (0, 16, (40, 40, 60, 255), "T"),
        "west":  (16, 16, (60, 60, 40, 255), "U"),
        # up/down reuse halves of the bottom-right quadrant borders
        "up":    (0, 0, None, None),
        "down":  (0, 0, None, None),
    }
    for name, (x, y, bg, glyph) in parts.items():
        if bg is None:
            continue
        img.rect(x, y, x + 16, y + 16, bg)
        img.rect(x, y, x + 16, y + 1, (200, 200, 200, 255))       # top edge
        img.rect(x, y, x + 1, y + 16, (200, 200, 200, 255))       # left edge
        img.rect(x + 15, y, x + 16, y + 16, (90, 90, 90, 255))    # right edge
        img.rect(x, y + 15, x + 16, y + 16, (90, 90, 90, 255))    # bottom edge
        draw_glyph(img, x + 3, y + 3, glyph)

    os.makedirs(os.path.join(pack_dir, "textures"), exist_ok=True)
    os.makedirs(os.path.join(pack_dir, "models"), exist_ok=True)
    write_png(os.path.join(pack_dir, "textures", "main.png"), TEX, TEX, img.rows())

    def face(qx, qy):
        return {"uv": [qx, qy], "uv_size": [16, 16]}

    cube = {
        "origin": [0, 0, 0],
        "size": [16, 16, 16],
        "uv": {
            "north": face(0, 0),
            "east": face(16, 0),
            "south": face(0, 16),
            "west": face(16, 16),
            "up": face(0, 0),
            "down": face(0, 16),
        },
    }
    geo = {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": "geometry.calib",
                "texture_width": TEX,
                "texture_height": TEX,
                "visible_bounds_width": 4,
                "visible_bounds_height": 4,
                "visible_bounds_offset": [0, 1, 0],
            },
            "bones": [{"name": "block", "pivot": [8, 0, 8], "cubes": [cube]}],
        }],
    }
    with open(os.path.join(pack_dir, "models", "main.json"), "w") as f:
        json.dump(geo, f, indent=2)

    ysm = {
        "spec": 2,
        "metadata": {"name": "calib", "license": {"type": "All Rights Reserved"}},
        "files": {"player": {
            "model": {"main": "models/main.json", "arm": "models/arm.json"},
            "texture": [{"uv": "textures/main.png"}],
            "animation": {},
            "animation_controllers": [],
        }},
        "properties": {"height_scale": 0.7, "width_scale": 0.7,
                       "preview_animation": "idle", "default_texture": "main"},
    }
    with open(os.path.join(pack_dir, "ysm.json"), "w") as f:
        json.dump(ysm, f, indent=2)
    print("wrote pack:", pack_dir)


if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else "calib")
