"""Design-driven 32x32 icons: hand-authored volumes, projected like the real model.

The geo models fix the proportions and paint, but a 216-cube drone collapses into a grey
slab at item size. Here each frame is rebuilt from a handful of deliberate volumes - hull,
two pods, turret, glow strips, payload notches - and rasterised with the same camera as the
reference render, then folded to a hard 32x32 grid.

Volumes: (origin, size, colour, kind) in model units, 16 units = 1 block, nose towards -Z.
kind: "matte" (shaded), "lit" (emissive, never darkened), "dark" (recess).
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

AZIMUTH, ELEVATION = 34.0, 20.0
SS = 16

# ---------------------------------------------------------------- palette (per paint)
P = {
    "hull":   "#B9C1CB", "hull2": "#8E97A3", "hull3": "#6A7280",
    "dark":   "#262A30", "panel":  "#D6DBE2", "seam": "#3A4048",
    "mag":    "#FF2D8A", "magD":   "#B01560",
    "arcticA": "#EEF4FA", "arcticB": "#CBD8E4", "arcticC": "#93A8BC",
    "ice":    "#66D9FF", "darkBlue": "#1E2B38", "blueLine": "#5E7C93",
    "crimA":  "#7C1A22", "crimB": "#A82632", "crimC": "#4E1016", "ember": "#FF4234",
    "rustA":  "#C08850", "rustB": "#8A5A32", "rustC": "#5F3D22", "rustD": "#6E7A70",
    "orange": "#FF9A3C",
}
C = {k: np.array([int(v[i:i + 2], 16) for i in (1, 3, 5)], float) for k, v in P.items()}


def hexc(name: str) -> np.ndarray:
    return C[name]


def box(origin, size, colour, kind="matte"):
    return {"o": origin, "s": size, "c": hexc(colour), "k": kind}


# ---------------------------------------------------------------- frame designs
def _ring(v, cx, cy, cz, r, colour, span=0.45):
    """A square ring of glow blocks: reads as a ducted thruster even at 10 px."""
    corners4 = [(cx - r, cz - r), (cx + r, cz - r), (cx - r, cz + r), (cx + r, cz + r)]
    for x, z in corners4:
        v.append(box([x, cy, z], [span, span, span], colour, "lit"))
    for t in (-r, r):
        v.append(box([cx + t, cy, cz - r * 0.35], [span, span, r * 0.7], colour, "lit"))


def peregrine():
    v = [
        # slender spine, so the pods sit clearly apart from it
        box([-4.4, -0.6, -6.6], [8.8, 1.3, 13.2], "hull"),
        box([-4.0, 0.7, -6.0], [8.0, 0.6, 8.6], "panel"),
        box([-3.6, -0.5, -7.6], [7.2, 1.1, 1.2], "hull2"),
        # pods: pushed out with a real background gap on each side
        box([-11.2, -0.4, -5.2], [5.2, 1.7, 10.4], "hull2"),
        box([6.0, -0.4, -5.2], [5.2, 1.7, 10.4], "hull2"),
        box([-11.2, 1.3, -5.2], [5.2, 0.4, 10.4], "panel"),
        box([6.0, 1.3, -5.2], [5.2, 0.4, 10.4], "panel"),
        # running light seam on the outer flank
        box([-11.4, 0.2, -5.2], [0.4, 0.6, 10.4], "mag"),
        box([11.0, 0.2, -5.2], [0.4, 0.6, 10.4], "mag"),
        # sensor turret + magenta band + tail fin
        box([-1.8, 0.9, 1.2], [3.6, 2.2, 4.2], "hull3"),
        box([-1.3, 1.7, 0.9], [2.6, 0.7, 0.4], "mag"),
        box([-0.5, 1.1, 5.0], [1.0, 2.2, 1.4], "hull3"),
    ]
    _ring(v, -8.6, 1.9, -3.4, 1.5, "mag")
    _ring(v, 8.6, 1.9, -3.4, 1.5, "mag")
    for z in (-3.6, 0.0, 3.6):                     # 3 payload notches on the inner edge
        v.append(box([5.6, -0.9, z], [1.4, 0.8, 1.2], "dark"))
        v.append(box([-7.0, -0.9, z], [1.4, 0.8, 1.2], "dark"))
    return v


def owl():
    v = [
        box([-3.8, -0.6, -5.6], [7.6, 1.2, 11.2], "arcticA"),
        box([-3.4, 0.6, -5.0], [6.8, 0.6, 6.2], "arcticB"),
        box([-7.4, -0.4, -4.6], [3.0, 1.4, 9.2], "arcticB"),
        box([4.4, -0.4, -4.6], [3.0, 1.4, 9.2], "arcticB"),
        box([-7.4, 1.0, -4.6], [3.0, 0.4, 9.2], "arcticA"),
        box([4.4, 1.0, -4.6], [3.0, 0.4, 9.2], "arcticA"),
        box([-7.6, 0.1, -4.6], [0.4, 0.55, 9.2], "ice"),
        box([7.2, 0.1, -4.6], [0.4, 0.55, 9.2], "ice"),
        # big recon camera sticking out in front of the nose
        box([-2.2, -0.3, -7.4], [4.4, 1.8, 1.9], "arcticC"),
        box([-1.3, 0.0, -7.9], [2.6, 1.1, 0.5], "ice"),
        box([-0.5, 1.0, 2.6], [1.0, 1.6, 1.6], "darkBlue"),
    ]
    _ring(v, -5.9, 1.5, -2.0, 1.2, "ice")
    _ring(v, 5.9, 1.5, -2.0, 1.2, "ice")
    for z in (-2.8, 2.0):                          # 2 notches
        v.append(box([4.2, -0.8, z], [1.2, 0.7, 1.1], "darkBlue"))
        v.append(box([-5.4, -0.8, z], [1.2, 0.7, 1.1], "darkBlue"))
    return v


def bedrock():
    v = [
        box([-6.6, -1.2, -7.2], [13.2, 2.4, 14.4], "crimA"),
        box([-7.6, 1.2, -6.0], [15.2, 0.9, 11.0], "crimB"),
        box([-5.0, 2.0, -4.4], [10.0, 0.7, 7.6], "crimC"),
        box([-4.6, -1.9, -5.2], [9.2, 0.8, 10.0], "dark"),        # belly cargo rack
        # heavy side pods, much taller than the hull so they own the silhouette
        box([-12.4, -1.3, -5.6], [4.6, 3.2, 11.2], "dark"),
        box([7.8, -1.3, -5.6], [4.6, 3.2, 11.2], "dark"),
        box([-12.0, 0.6, -5.6], [4.0, 0.6, 11.2], "crimB"),
        box([8.0, 0.6, -5.6], [4.0, 0.6, 11.2], "crimB"),
        box([-12.7, 0.0, -5.6], [0.4, 0.8, 11.2], "ember"),
        box([12.3, 0.0, -5.6], [0.4, 0.8, 11.2], "ember"),
        box([-6.2, 1.0, -7.6], [12.4, 0.6, 0.6], "ember"),        # front glow bar
        box([-1.6, 2.6, 2.4], [3.2, 1.4, 3.0], "crimC"),
        box([-0.9, 3.1, 2.0], [1.8, 0.7, 0.4], "ember"),
    ]
    _ring(v, -10.1, 2.4, -3.0, 1.4, "ember")
    _ring(v, 10.1, 2.4, -3.0, 1.4, "ember")
    for z in (-4.2, -1.6, 1.0, 3.6):               # 4 notches
        v.append(box([7.4, -1.9, z], [1.6, 0.8, 1.3], "dark"))
        v.append(box([-9.0, -1.9, z], [1.6, 0.8, 1.3], "dark"))
    return v


def scrap():
    v = [
        box([-4.6, -0.6, -6.6], [9.2, 1.3, 13.2], "rustA"),
        box([-4.2, 0.7, -5.6], [4.4, 0.6, 5.8], "rustB"),         # welded patch
        box([0.6, 0.7, -2.0], [3.4, 0.5, 4.6], "rustD"),          # odd donor panel
        box([-3.6, 0.7, 1.8], [3.0, 0.5, 4.0], "rustC"),
        box([-10.6, -0.4, -5.0], [5.0, 1.5, 10.0], "rustB"),
        box([5.8, -0.4, -5.0], [5.2, 1.5, 10.0], "rustD"),
        box([-10.6, 1.1, -5.0], [5.0, 0.4, 10.0], "rustA"),
        box([5.8, 1.1, -5.0], [5.2, 0.4, 10.0], "rustA"),
        box([-10.9, 0.15, -5.0], [0.4, 0.6, 10.0], "orange"),
        box([10.9, 0.15, -5.0], [0.4, 0.6, 10.0], "orange"),
        box([-1.6, 0.9, 1.4], [3.4, 1.9, 3.6], "rustC"),
        box([-1.0, 1.5, 1.0], [2.2, 0.7, 0.4], "orange"),
        box([-0.4, 1.2, 4.6], [0.9, 2.0, 1.2], "dark"),
    ]
    _ring(v, -8.1, 1.8, -3.2, 1.3, "orange")
    for z in (-3.2, 0.4, 3.4):                     # 3 notches
        v.append(box([5.4, -0.85, z], [1.6, 0.8, 1.2], "dark"))
        v.append(box([-7.0, -0.85, z], [1.6, 0.8, 1.2], "dark"))
    return v


def terminal():
    """Stowed form: compact, pods folded flush, no turret, lights along the fold line."""
    v = [
        box([-4.8, -0.8, -6.0], [9.6, 1.6, 12.0], "hull"),
        box([-4.4, 0.8, -5.4], [8.8, 0.6, 7.6], "panel"),
        box([-3.8, -0.5, -6.8], [7.6, 1.1, 1.0], "hull2"),
        box([-7.6, -0.5, -4.6], [2.8, 1.2, 9.0], "hull3"),
        box([4.8, -0.5, -4.6], [2.8, 1.2, 9.0], "hull3"),
        box([-7.8, 0.1, -4.6], [0.4, 0.6, 9.0], "mag"),
        box([7.4, 0.1, -4.6], [0.4, 0.6, 9.0], "mag"),
        box([-2.8, 1.0, 0.8], [5.6, 0.7, 4.4], "hull3"),      # stowed hatch
        box([-0.5, 1.1, 4.2], [1.0, 1.8, 1.2], "dark"),        # antenna nub
        box([-1.0, -1.0, -6.2], [2.0, 0.7, 0.9], "dark"),
    ]
    _ring(v, -6.2, 1.5, -1.6, 1.1, "mag")
    _ring(v, 6.2, 1.5, -1.6, 1.1, "mag")
    return v


DESIGNS = {
    "drone": terminal,
    "frame_peregrine": peregrine,
    "frame_owl": owl,
    "frame_bedrock": bedrock,
    "frame_scrap": scrap,
}

FACE_SHADE = {"up": 1.00, "down": 0.42, "north": 0.86, "south": 0.60, "east": 0.70, "west": 0.80}
LABEL = {"up": "up", "down": "down", "north": "north", "south": "south", "east": "east", "west": "west"}


def corners(o, s):
    ox, oy, oz = o
    sx, sy, sz = s
    return np.array([[ox, oy, oz], [ox, oy, oz + sz], [ox, oy + sy, oz], [ox, oy + sy, oz + sz],
                     [ox + sx, oy, oz], [ox + sx, oy, oz + sz], [ox + sx, oy + sy, oz],
                     [ox + sx, oy + sy, oz + sz]])


def render(boxes, px: int = OUT) -> Image.Image:
    camera, right, upv = bfi.camera_basis(AZIMUTH, ELEVATION)
    faces = []
    for b in boxes:
        pts = corners(b["o"], b["s"])
        view = pts @ np.array([right, -upv, camera]).T
        for key, idx, n in bfi.FACES:
            shade = 1.0 if b["k"] == "lit" else FACE_SHADE[key]
            if b["k"] == "dark":
                shade *= 0.85
            faces.append((view[list(idx)], b["c"], shade))

    allpts = np.concatenate([f[0] for f in faces])
    span = np.ptp(allpts[:, :2], axis=0)
    scale = min((OUT - 5) * SS / max(span[0], 1e-6), (OUT - 11) * SS / max(span[1], 1e-6))
    centre = (allpts[:, :2].min(0) + allpts[:, :2].max(0)) / 2

    w = h = OUT * SS
    zbuf = np.full((h, w), -np.inf)
    colour = np.zeros((h, w, 3))
    hit = np.zeros((h, w), bool)
    iy, ix = np.mgrid[0:h, 0:w]
    grid = np.stack([ix + 0.5, iy + 0.5], axis=-1)

    for face, col, shade in faces:
        xy = (face[:, :2] - centre) * scale + np.array([w / 2, h / 2])
        axes = np.array([xy[3] - xy[0], xy[1] - xy[0]]).T
        if abs(np.linalg.det(axes)) < 1e-9:
            continue
        st = (grid - xy[0]) @ np.linalg.inv(axes).T
        depth = (face[0, 2] + st[..., 0] * (face[3, 2] - face[0, 2])
                 + st[..., 1] * (face[1, 2] - face[0, 2]))
        mask = (st.min(-1) >= 0) & (st.max(-1) < 1) & (depth > zbuf)
        if not mask.any():
            continue
        zbuf[mask] = depth[mask]
        hit[mask] = True
        colour[mask] = col * shade

    img = np.zeros((h, w, 4), np.uint8)
    img[:, :, :3] = np.clip(colour, 0, 255).astype(np.uint8)
    img[:, :, 3] = np.where(hit, 255, 0)
    big = Image.fromarray(img, "RGBA")

    small = np.array(big.resize((OUT, OUT), Image.BOX)).astype(np.int16)
    solid = small[:, :, 3] >= 128

    neigh = np.zeros(solid.shape, np.int16)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dx or dy:
                neigh += np.roll(np.roll(solid, dy, 0), dx, 1)
    solid &= neigh >= 2

    rgb = small[:, :, :3].copy()
    if solid.any():
        flat = rgb[solid].astype(float)
        mx, mn = flat.max(1), flat.min(1)
        sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1), 0)
        vibe = sat > 0.30
        q = Image.fromarray(flat[~vibe].astype(np.uint8).reshape(-1, 1, 3)).quantize(colors=11, method=Image.MEDIANCUT)
        pal = np.array(q.getpalette()[: 11 * 3]).reshape(-1, 3)
        out_rgb = flat.copy()
        out_rgb[~vibe] = pal[np.array(q).reshape(-1)]
        out_rgb[vibe] = np.clip(out_rgb[vibe] * 1.1 + 6, 0, 255)
        rgb[solid] = out_rgb

    edge = np.zeros(solid.shape, bool)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dx or dy:
                edge |= np.roll(np.roll(solid, dy, 0), dx, 1)
    edge &= ~solid

    out = np.zeros((OUT, OUT, 4), np.uint8)
    out[:, :, :3] = (20, 19, 24)
    out[:, :, 3] = np.where(edge, 255, 0)
    out[solid, :3] = np.clip(rgb[solid], 0, 255)
    out[solid, 3] = 255
    return Image.fromarray(out, "RGBA")


def main() -> int:
    names = sys.argv[1:] or list(DESIGNS)
    made = {}
    for name in names:
        icon = render(DESIGNS[name]())
        icon.save(ITEM / f"{name}.png")
        up(icon).save(ART / f"{name}@8x.png")
        made[name] = icon
        a = np.array(icon)[:, :, 3]
        ys, xs = np.nonzero(a)
        print(f"{name:16s} bbox=({xs.min()},{ys.min()},{xs.max()},{ys.max()})")

    order = [n for n in ("drone", "frame_bedrock", "frame_owl", "frame_peregrine", "frame_scrap") if n in made]
    if order:
        cell, pad = OUT * 8, 16
        sheet = Image.new("RGBA", (cell * len(order) + pad * 2, cell + pad * 2), (32, 33, 38, 255))
        for i, n in enumerate(order):
            sheet.alpha_composite(up(made[n]), (pad + i * cell, pad))
        sheet.save(ART / "sheet@8x.png")
        print("sheet ->", ART / "sheet@8x.png")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
