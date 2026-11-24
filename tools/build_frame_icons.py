"""Build the 32x32 item icons for the four drone frames from their real geo models.

Each frame is rasterised at 8x supersampling with its own paint atlas, then
box-downsampled to a hard 32x32 pixel grid (alpha thresholded, palette snapped,
one-pixel dark outline, one-pixel safety margin). Output therefore always matches
the in-game silhouette instead of being a hand-drawn guess.

Usage:
    python tools/build_frame_icons.py            # write icons into textures/item
    python tools/build_frame_icons.py --preview  # also write 8x contact sheets
"""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/huhu_dronepal"
ITEM = ASSETS / "textures/item"
PREVIEW_DIR = ROOT / "artifacts/frame-icons"

SS = 8                     # supersampling factor (32 -> 256)
OUT = 32                   # final icon size
CONTENT_W = 27             # target width of the drawn body, in final pixels
CONTENT_H = 21             # target height (3/4 view is wider than tall)

# frame -> (geo file, paint atlas, camera azimuth/elevation in degrees)
FRAMES = {
    "frame_peregrine": ("drone.geo.json", "drone_standard.png", 34.0, 41.0),
    "frame_owl":       ("owl.geo.json",   "drone_arctic.png",  34.0, 41.0),
    "frame_bedrock":   ("bedrock.geo.json", "drone_crimson.png", 34.0, 41.0),
    "frame_scrap":     ("scrap.geo.json", "drone_rust.png",    34.0, 41.0),
}
TERMINAL = ("drone.geo.json", "drone_standard.png", 34.0, 41.0)

FACES = (
    ("north", (3, 1, 0, 2), (0, 0, -1)),
    ("south", (6, 4, 5, 7), (0, 0, 1)),
    ("east",  (7, 5, 1, 3), (1, 0, 0)),
    ("west",  (2, 0, 4, 6), (-1, 0, 0)),
    ("up",    (2, 6, 7, 3), (0, 1, 0)),
    ("down",  (4, 0, 1, 5), (0, -1, 0)),
)


def transform(obj):
    x, y, z = np.radians(obj.get("rotation", [0, 0, 0]))
    a, b, c = np.cos([x, y, z])
    u, v, w = np.sin([x, y, z])
    r = np.array([
        [c * b, c * v * u - w * a, c * v * a + w * u],
        [w * b, w * v * u + c * a, w * v * a - c * u],
        [-v, b * u, b * a],
    ])
    m = np.eye(4)
    m[:3, :3] = r
    p = np.array(obj.get("pivot", [0, 0, 0]))
    m[:3, 3] = p - r @ p
    return m


def camera_basis(azimuth_deg: float, elevation_deg: float):
    """Camera looks at origin from the given azimuth/elevation.

    azimuth 0 = straight in front of the craft (-Z / nose), growing to its right.
    """
    az, el = np.radians(azimuth_deg), np.radians(elevation_deg)
    eye = np.array([math.sin(az) * math.cos(el), math.sin(el), -math.cos(az) * math.cos(el)])
    eye /= np.linalg.norm(eye)
    camera = -eye                      # direction the camera looks along
    right = np.cross(camera, [0, 1, 0])
    right /= np.linalg.norm(right)
    up = np.cross(right, camera)
    return camera, right, up


def collect_faces(geo, atlas_size, camera, right, up):
    bones = {b["name"]: b for b in geo["bones"]}

    def bone_matrix(b):
        return (bone_matrix(bones[b["parent"]]) if b.get("parent") in bones else np.eye(4)) @ transform(b)

    faces = []
    for bone in bones.values():
        m = bone_matrix(bone)
        for cube in bone.get("cubes", []):
            world = m @ transform(cube)
            origin = np.array(cube["origin"], float)
            size = np.array(cube["size"], float)
            inflate = float(cube.get("inflate", 0.0))
            lo = origin - inflate
            hi = origin + size + inflate
            pts = np.array([[lo[0], lo[1], lo[2]], [lo[0], lo[1], hi[2]],
                            [lo[0], hi[1], lo[2]], [lo[0], hi[1], hi[2]],
                            [hi[0], lo[1], lo[2]], [hi[0], lo[1], hi[2]],
                            [hi[0], hi[1], lo[2]], [hi[0], hi[1], hi[2]]])
            pts = pts @ world[:3, :3].T + world[:3, 3]
            view = pts @ np.array([right, -up, camera]).T
            for key, idx, n in FACES:
                uv = cube.get("uv", {}).get(key)
                if not uv:
                    continue
                normal = world[:3, :3] @ np.array(n, float)
                norm = np.linalg.norm(normal)
                if norm < 1e-9 or (normal / norm) @ camera <= 0:
                    continue
                shade = 0.62 + 0.38 * max(0.0, (normal / norm) @ np.array([-0.35, 0.86, -0.37]))
                faces.append((view[list(idx)], uv, shade))
    return faces


def rasterise(spec, canvas_px: int):
    geo_path = ASSETS / "geo" / spec[0]
    atlas_path = ASSETS / "textures/entity" / spec[1]
    geo = json.loads(geo_path.read_text())["minecraft:geometry"][0]
    atlas = np.array(Image.open(atlas_path).convert("RGBA"))

    camera, right, up = camera_basis(spec[2], spec[3])
    faces = collect_faces(geo, atlas.shape, camera, right, up)
    if not faces:
        raise SystemExit(f"no visible faces for {spec[0]}")

    allpts = np.concatenate([f[0] for f in faces])
    span = np.ptp(allpts[:, :2], axis=0)
    # fit the projected body into CONTENT_W x CONTENT_H final pixels
    scale = min(CONTENT_W * SS / max(span[0], 1e-6), CONTENT_H * SS / max(span[1], 1e-6))
    lo = allpts[:, :2].min(0)
    hi = allpts[:, :2].max(0)
    centre = (lo + hi) / 2

    w = h = canvas_px
    zbuf = np.full((h, w), -np.inf)
    uvmap = np.zeros((h, w, 2), int)
    light = np.zeros((h, w))
    hit = np.zeros((h, w), bool)

    for face, uv, shade in faces:
        xy = (face[:, :2] - centre) * scale + np.array([w / 2, h / 2 - 0.5 * SS])
        axes = np.array([xy[3] - xy[0], xy[1] - xy[0]]).T
        det = np.linalg.det(axes)
        if abs(det) < 1e-9:
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
        size = uv.get("uv_size", [1, 1])
        tex = (np.array(uv["uv"]) + st[mask] * size).astype(int)
        uvmap[mask] = tex
        light[mask] = shade

    pixels = np.zeros((h, w, 4), np.uint8)
    ty = np.clip(uvmap[:, :, 1], 0, atlas.shape[0] - 1)
    tx = np.clip(uvmap[:, :, 0], 0, atlas.shape[1] - 1)
    sampled = atlas[ty, tx].astype(float)
    rgb = sampled[:, :, :3] * light[..., None]
    pixels[:, :, :3] = np.clip(rgb, 0, 255).astype(np.uint8)
    pixels[:, :, 3] = np.where(hit & (sampled[:, :, 3] > 32), 255, 0)
    return Image.fromarray(pixels, "RGBA")


def pixelise(sheet: Image.Image, outline=(26, 24, 30)) -> Image.Image:
    """Hard-downsample to OUT px and give it the classic item-sprite outline."""
    small = sheet.resize((OUT, OUT), Image.BOX)
    arr = np.array(small).astype(np.int16)
    alpha = arr[:, :, 3]
    solid = alpha >= 128

    # drop specks: keep pixels with at least 3 solid 8-neighbours
    keep = np.zeros_like(solid)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dx == dy == 0:
                continue
            keep |= np.roll(np.roll(solid, dy, 0), dx, 1)
    neigh = np.zeros_like(solid, dtype=np.int16)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dx == dy == 0:
                continue
            neigh += np.roll(np.roll(solid, dy, 0), dx, 1)
    solid = solid & (neigh >= 2)

    # snap colours onto a small palette so the sprite reads as pixel art
    rgb = arr[:, :, :3]
    flat = rgb[solid]
    if len(flat):
        q = Image.fromarray(flat.reshape(-1, 1, 3).astype(np.uint8)).quantize(colors=20, method=Image.MEDIANCUT)
        pal = np.array(q.getpalette()[: 20 * 3]).reshape(-1, 3)
        idx = np.array(q).reshape(-1)
        rgb_out = rgb.copy()
        rgb_out[solid] = pal[idx]
        rgb = rgb_out

    # one-pixel dark edge just outside the silhouette
    edge = np.zeros_like(solid)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dx == dy == 0:
                continue
            edge |= np.roll(np.roll(solid, dy, 0), dx, 1)
    edge = edge & ~solid

    out = np.zeros((OUT, OUT, 4), np.uint8)
    out[:, :, :3] = outline
    out[:, :, 3] = np.where(edge, 255, 0)
    out[solid, :3] = rgb[solid]
    out[solid, 3] = 255
    return Image.fromarray(out, "RGBA")


def check_margin(img: Image.Image) -> tuple[bool, tuple[int, int, int, int]]:
    a = np.array(img)[:, :, 3]
    ys, xs = np.nonzero(a)
    if not len(xs):
        return False, (0, 0, 0, 0)
    box = (xs.min(), ys.min(), xs.max(), ys.max())
    ok = xs.min() >= 1 and ys.min() >= 1 and xs.max() <= OUT - 2 and ys.max() <= OUT - 2
    return ok, box


def upscale(img: Image.Image, factor: int = 8) -> Image.Image:
    return img.resize((OUT * factor, OUT * factor), Image.NEAREST)


def main() -> int:
    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    jobs = dict(FRAMES)
    jobs["drone"] = TERMINAL

    made = {}
    for name, spec in jobs.items():
        big = rasterise(spec, OUT * SS)
        icon = pixelise(big)
        ok, box = check_margin(icon)
        made[name] = icon
        icon.save(ITEM / f"{name}.png")
        big.save(PREVIEW_DIR / f"{name}@8x-antialiased.png")
        upscale(icon).save(PREVIEW_DIR / f"{name}@8x.png")
        print(f"{name:16s} bbox={box} margin_ok={ok} saved -> {ITEM / (name + '.png')}")

    if "--preview" in sys.argv or True:
        order = ["drone", "frame_bedrock", "frame_owl", "frame_peregrine", "frame_scrap"]
        cell = OUT * 8
        pad = 16
        sheet = Image.new("RGBA", (cell * len(order) + pad * 2, cell + pad * 2 + 8 * 4), (32, 33, 38, 255))
        for i, name in enumerate(order):
            sheet.alpha_composite(upscale(made[name]), (pad + i * cell, pad))
        sheet.save(PREVIEW_DIR / "sheet@8x.png")
        print("sheet ->", PREVIEW_DIR / "sheet@8x.png")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
