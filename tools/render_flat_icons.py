"""Flat-facet 32x32 renderer: the model's own boxes, but each face gets one solid colour.

Per-pixel texture sampling is what turns a 216-cube drone into grey mush at item size.
Averaging each face's UV patch to a single colour keeps the silhouette and paint identity
while collapsing the detail into readable flat facets - the look real item sprites have.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_frame_icons as bfi  # noqa: E402
from gen_frame_icons_ai import ART, ITEM, OUT, up  # noqa: E402

FRAMES = {
    "drone": ("drone.geo.json", "drone_standard.png"),
    "frame_peregrine": ("drone.geo.json", "drone_standard.png"),
    "frame_owl": ("owl.geo.json", "drone_arctic.png"),
    "frame_bedrock": ("bedrock.geo.json", "drone_crimson.png"),
    "frame_scrap": ("scrap.geo.json", "drone_rust.png"),
}
import os
AZIMUTH = float(os.environ.get("DRONEPAL_AZ", 34.0))
ELEVATION = float(os.environ.get("DRONEPAL_ELEV", 27.0))
SS = 12                     # supersampling per final pixel
GAMMA, GAIN = 0.80, 1.15


def face_colour(atlas: np.ndarray, uv: dict) -> np.ndarray:
    x, y = int(uv["uv"][0]), int(uv["uv"][1])
    w, h = (int(v) for v in uv.get("uv_size", [1, 1]))
    x0, y0 = max(0, x), max(0, y)
    patch = atlas[y0:y0 + max(h, 1), x0:x0 + max(w, 1)].reshape(-1, 4).astype(float)
    if not len(patch):
        return np.array([128, 128, 128])
    keep = patch[patch[:, 3] > 32]
    if not len(keep):
        keep = patch
    return keep[:, :3].mean(axis=0)


def render(geo_name: str, atlas_name: str, px: int = OUT) -> Image.Image:
    geo = json.loads((bfi.ASSETS / "geo" / geo_name).read_text())["minecraft:geometry"][0]
    atlas = np.array(Image.open(bfi.ASSETS / "textures/entity" / atlas_name).convert("RGBA"))

    camera, right, up_v = bfi.camera_basis(AZIMUTH, ELEVATION)
    faces = bfi.collect_faces(geo, atlas.shape, camera, right, up_v)
    if not faces:
        raise SystemExit(f"nothing visible for {geo_name}")

    allpts = np.concatenate([f[0] for f in faces])
    span = np.ptp(allpts[:, :2], axis=0)
    scale = min((OUT - 6) * SS / max(span[0], 1e-6), (OUT - 12) * SS / max(span[1], 1e-6))
    centre = (allpts[:, :2].min(0) + allpts[:, :2].max(0)) / 2

    w = h = OUT * SS
    zbuf = np.full((h, w), -np.inf)
    colour = np.zeros((h, w, 3), float)
    hit = np.zeros((h, w), bool)

    for face, uv, shade in faces:
        xy = (face[:, :2] - centre) * scale + np.array([w / 2, h / 2])
        axes = np.array([xy[3] - xy[0], xy[1] - xy[0]]).T
        if abs(np.linalg.det(axes)) < 1e-9:
            continue
        iy, ix = np.mgrid[0:h, 0:w]
        st = (np.stack([ix + 0.5, iy + 0.5], axis=-1) - xy[0]) @ np.linalg.inv(axes).T
        depth = (face[0, 2] + st[..., 0] * (face[3, 2] - face[0, 2])
                 + st[..., 1] * (face[1, 2] - face[0, 2]))
        mask = (st.min(-1) >= 0) & (st.max(-1) < 1) & (depth > zbuf)
        if not mask.any():
            continue
        zbuf[mask] = depth[mask]
        hit[mask] = True
        colour[mask] = face_colour(atlas, uv) * shade

    img = np.zeros((h, w, 4), np.uint8)
    rgb = np.clip((np.clip(colour / 255.0, 0, 1) ** GAMMA) * GAIN * 255.0, 0, 255)
    img[:, :, :3] = rgb.astype(np.uint8)
    img[:, :, 3] = np.where(hit, 255, 0)
    big = Image.fromarray(img, "RGBA")

    small = big.resize((OUT, OUT), Image.BOX)
    arr = np.array(small).astype(np.int16)
    solid = arr[:, :, 3] >= 128

    neigh = np.zeros(solid.shape, np.int16)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dx or dy:
                neigh += np.roll(np.roll(solid, dy, 0), dx, 1)
    solid &= neigh >= 2

    rgb32 = arr[:, :, :3].copy()
    if solid.any():
        flat_rgb = rgb32[solid].astype(np.float32)
        mx = flat_rgb.max(axis=1)
        mn = flat_rgb.min(axis=1)
        sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1), 0)
        vibrant = sat > 0.28                      # thrusters, lamps, lenses
        q = Image.fromarray(flat_rgb[~vibrant].reshape(-1, 1, 3).astype(np.uint8)).quantize(colors=12, method=Image.MEDIANCUT)
        pal = np.array(q.getpalette()[: 12 * 3]).reshape(-1, 3)
        out_rgb = flat_rgb.copy()
        out_rgb[~vibrant] = pal[np.array(q).reshape(-1)]
        # push the glow a little so it still reads one pixel wide
        out_rgb[vibrant] = np.clip(out_rgb[vibrant] * 1.12 + 8, 0, 255)
        rgb32[solid] = out_rgb

    edge = np.zeros(solid.shape, bool)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dx or dy:
                edge |= np.roll(np.roll(solid, dy, 0), dx, 1)
    edge &= ~solid

    out = np.zeros((OUT, OUT, 4), np.uint8)
    out[:, :, :3] = (22, 20, 26)
    out[:, :, 3] = np.where(edge, 255, 0)
    out[solid, :3] = np.clip(rgb32[solid], 0, 255)
    out[solid, 3] = 255
    return Image.fromarray(out, "RGBA")


def main() -> int:
    names = sys.argv[1:] or list(FRAMES)
    made = {}
    for name in names:
        icon = render(*FRAMES[name])
        icon.save(ITEM / f"{name}.png")
        up(icon).save(ART / f"{name}@8x.png")
        made[name] = icon
        a = np.array(icon)[:, :, 3]
        ys, xs = np.nonzero(a)
        print(f"{name:16s} bbox=({xs.min()},{ys.min()},{xs.max()},{ys.max()})")

    order = [n for n in ("drone", "frame_bedrock", "frame_owl", "frame_peregrine", "frame_scrap") if n in made]
    cell, pad = OUT * 8, 16
    sheet = Image.new("RGBA", (cell * len(order) + pad * 2, cell + pad * 2), (32, 33, 38, 255))
    for i, n in enumerate(order):
        sheet.alpha_composite(up(made[n]), (pad + i * cell, pad))
    sheet.save(ART / "sheet@8x.png")
    print("sheet ->", ART / "sheet@8x.png")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
