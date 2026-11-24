"""Render high-resolution 3/4 reference views of each frame straight from its geo model.

These are the images handed to the image model as visual reference, so the item icon
keeps the real silhouette and paint instead of inventing a lookalike.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_frame_icons as bfi  # noqa: E402

OUT_DIR = bfi.ROOT / "artifacts/frame-icons/ref"

# a little above and in front-left of the nose, same angle the item sprite should use
VIEWS = {
    "frame_peregrine": ("drone.geo.json", "drone_standard.png"),
    "frame_owl": ("owl.geo.json", "drone_arctic.png"),
    "frame_bedrock": ("bedrock.geo.json", "drone_crimson.png"),
    "frame_scrap": ("scrap.geo.json", "drone_rust.png"),
}
TERMINAL = ("drone.geo.json", "drone_standard.png")


GAMMA = 0.78      # lift the very dark drone atlases so the reference reads clearly
GAIN = 1.18


def render(geo: str, atlas: str, azimuth: float = 34.0, elevation: float = 27.0, px: int = 1024) -> Image.Image:
    ss = px // 56                      # supersample factor for a 56-cell content box
    bfi.SS = ss
    bfi.CONTENT_W = 48
    bfi.CONTENT_H = 34
    img = bfi.rasterise((geo, atlas, azimuth, elevation), px)
    arr = np.array(img).astype(np.float32)
    rgb = arr[:, :, :3] / 255.0
    rgb = np.clip((rgb ** GAMMA) * GAIN, 0, 1) * 255.0
    arr[:, :, :3] = rgb
    return Image.fromarray(arr.astype(np.uint8), "RGBA")


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    made = {}
    for name, (geo, atlas) in VIEWS.items():
        img = render(geo, atlas)
        img.save(OUT_DIR / f"{name}.png")
        made[name] = img
        print("ref ->", OUT_DIR / f"{name}.png")

    img = render(*TERMINAL)
    img.save(OUT_DIR / "drone.png")
    made["drone"] = img

    order = ["drone", "frame_bedrock", "frame_owl", "frame_peregrine", "frame_scrap"]
    cell = 320
    sheet = Image.new("RGBA", (cell * len(order), cell), (255, 255, 255, 255))
    for i, n in enumerate(order):
        t = made[n].convert("RGBA").resize((cell, cell), Image.LANCZOS)
        sheet.alpha_composite(t, (i * cell, 0))
    sheet.save(OUT_DIR / "sheet.png")
    print("sheet ->", OUT_DIR / "sheet.png")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
