"""Generate the five drone item icons through the OpenAI-compatible image relay.

The DSH image-gen plugin currently posts to "<base>/images/generations"; this relay
only serves "<base>/v1/images/generations", so we talk to it directly, store the raw
render, and then fold it down into a hard 32x32 item sprite:

    raw render -> background key -> crop -> 32x32 box downsample
               -> alpha threshold -> palette snap -> 1px dark outline -> 1px margin
"""
from __future__ import annotations

import base64
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/huhu_dronepal"
ART = ROOT / "artifacts/frame-icons"
RAW = ART / "raw"
ITEM = ASSETS / "textures/item"
ENDPOINT = "https://vvvvvip.tststs.shop/v1/images/generations"
MODEL = os.environ.get("DRONEPAL_IMAGE_MODEL", "gemini-nano-banana-2.1")
OUT = 32

COMMON = (
    "Minecraft item sprite drawn on a logical 32x32 pixel grid, blown up with hard square "
    "pixels, flat solid colours only, no gradients, no dithering, no text, no letters, no "
    "watermark, no UI, no cast shadow, no reflection, no glow halo. "
    "The object must read as a MACHINE: a central rectangular fuselage plus two clearly "
    "separated rectangular side pods, with a 1-2 pixel gap of background visible between "
    "fuselage and each pod, so the outline is not one lumpy blob. Nose points to the lower "
    "left, tail to the upper right, seen from three-quarter top-down (about 40 degrees above "
    "the deck). Every part outlined with a one-pixel near-black edge, matte shading, upper "
    "left light, at most 7 flat colours total. Object centred and occupying about 80% of the "
    "frame width. Background is one uniform flat chroma green #00FF00, edge to edge."
)

SPRITES = {
    "drone": (
        "A stowed companion drone terminal: wide and flat folded belly, the two rotor pods "
        "tucked in tight against the hull, segmented silver-grey armour plates with thin "
        "panel seams, dark graphite underside, small hot magenta-pink indicator lights on "
        "both outer side edges, a stubby antenna nub at the back. Compact, ready-to-stow "
        "look, no legs. Palette: #C9D0D9 #8C949F #4A5058 #24272C outline, #FF2D8A accents."
    ),
    "frame_peregrine": (
        "Peregrine standard recon drone: medium wingspan, symmetric side engine pods left and "
        "right on the same flight axis, wide flat silver-grey hull with segmented armour, a "
        "raised cylindrical sensor turret on the top rear, thin hot magenta-pink light seams "
        "running along both pod sides, and three small notch marks cut into the underside side "
        "edge. Balanced, tidy, factory-fresh industrial look. "
        "Palette: #D2D8E0 #9BA3AE #545B65 #24272C outline, #FF2D8A light seams."
    ),
    "frame_owl": (
        "Owl scout drone: narrow and compact, short stubby wings, side thruster pods pulled in "
        "close to the hull, arctic polar-white shell plates with pale blue panel lines, a "
        "prominent round reconnaissance camera lens protruding at the front with an ice-blue "
        "glass glow, two small notch marks on the underside side edge. Minimal, cold, "
        "high-tech. Palette: #F2F6FA #C3D2DE #7E94A6 #223040 outline, #6FD8FF lens glow."
    ),
    "frame_bedrock": (
        "Bedrock heavy-lift drone: massively wide and thick, armour slabs stepping outward in "
        "tiers, big blocky black-and-crimson side cargo pods that hang low, dark red glowing "
        "seams between the plates, a wide flat belly cargo rack underneath, and four notch "
        "marks along the underside side edge. Brutal, heavy, industrial. "
        "Palette: #7E1A22 #B02A32 #3A1418 #17110F outline, #FF4234 glow."
    ),
    "frame_scrap": (
        "Scrap workshop patchwork drone: the same flight axis and silhouette family as the "
        "Peregrine but rebuilt from mismatched parts, off-colour riveted patches welded onto "
        "the hull, rust-brown and oxidised steel plates, one panel clearly a different shade, "
        "orange warning lamps on the side pods, three notch marks along the underside side "
        "edge, visible rivet dots. Grimy, improvised, still flying. "
        "Palette: #C98B4A #8A5A32 #5C6B63 #2A231C outline, #FF9A3C lamps."
    ),
}


def read_key() -> str:
    key = os.environ.get("DSH_IMAGE_GEN_OPENAI_COMPAT_KEY")
    if key:
        return key.strip()
    cred = Path.home() / ".dsh/.credentials.yaml"
    for line in cred.read_text().splitlines():
        m = re.match(r"\s*DSH_IMAGE_GEN_OPENAI_COMPAT_KEY:\s*(\S+)\s*$", line)
        if m:
            return m.group(1)
    raise SystemExit("no image relay key found")


def request_image(key: str, prompt: str) -> Image.Image:
    body = json.dumps({
        "model": MODEL,
        "prompt": prompt,
        "n": 1,
        "size": "1024x1024",
        "response_format": "b64_json",
    }).encode()
    req = urllib.request.Request(
        ENDPOINT, data=body,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=300) as resp:
        payload = json.load(resp)
    item = payload["data"][0]
    if item.get("b64_json"):
        blob = base64.b64decode(item["b64_json"])
    else:
        with urllib.request.urlopen(item["url"], timeout=120) as resp:
            blob = resp.read()
    tmp = RAW / "_last.png"
    tmp.write_bytes(blob)
    return Image.open(tmp).convert("RGBA")


def key_out_background(img: Image.Image, tol: int = 30) -> Image.Image:
    """Flood-fill the near-uniform background from the border, keep the sprite."""
    arr = np.array(img.convert("RGB")).astype(np.int16)
    h, w, _ = arr.shape
    corners = [arr[0, 0], arr[0, w - 1], arr[h - 1, 0], arr[h - 1, w - 1]]
    bg = np.median(np.array(corners), axis=0)
    near = np.abs(arr - bg).max(axis=2) <= tol

    # flood fill from every border pixel that already matches the background colour
    filled = np.zeros((h, w), bool)
    stack: list[tuple[int, int]] = []
    for x in range(w):
        for y in (0, h - 1):
            if near[y, x]:
                stack.append((y, x))
    for y in range(h):
        for x in (0, w - 1):
            if near[y, x]:
                stack.append((y, x))
    while stack:
        y, x = stack.pop()
        if filled[y, x] or not near[y, x]:
            continue
        filled[y, x] = True
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and not filled[ny, nx] and near[ny, nx]:
                stack.append((ny, nx))

    out = np.array(img.convert("RGBA"))
    out[filled, 3] = 0
    # hairline halo: near-background pixels touching the fill get dropped too
    halo = np.zeros((h, w), bool)
    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        halo |= np.roll(filled, (dy, dx), (0, 1))
    halo &= ~filled
    halo &= near
    out[halo, 3] = 0
    return Image.fromarray(out, "RGBA")


def to_icon(img: Image.Image, outline=(24, 22, 28)) -> Image.Image:
    img = key_out_background(img)
    alpha = np.array(img)[:, :, 3]
    ys, xs = np.nonzero(alpha > 8)
    if not len(xs):
        raise SystemExit("empty sprite after background removal")
    img = img.crop((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1))

    # fit inside OUT-2 square, keep aspect
    box = OUT - 4
    scale = min(box / img.width, box / img.height)
    resized = img.resize((max(1, round(img.width * scale)), max(1, round(img.height * scale))), Image.BOX)

    canvas = Image.new("RGBA", (OUT, OUT), (0, 0, 0, 0))
    canvas.alpha_composite(resized, ((OUT - resized.width) // 2, (OUT - resized.height) // 2))

    arr = np.array(canvas).astype(np.int16)
    solid = arr[:, :, 3] >= 128

    # de-speckle
    neigh = np.zeros(solid.shape, np.int16)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dx or dy:
                neigh += np.roll(np.roll(solid, dy, 0), dx, 1)
    solid &= neigh >= 2

    # palette snap
    rgb = arr[:, :, :3].copy()
    flat = rgb[solid]
    if len(flat):
        q = Image.fromarray(flat.reshape(-1, 1, 3).astype(np.uint8)).quantize(colors=22, method=Image.MEDIANCUT)
        pal = np.array(q.getpalette()[: 22 * 3]).reshape(-1, 3)
        rgb[solid] = pal[np.array(q).reshape(-1)]

    edge = np.zeros(solid.shape, bool)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dx or dy:
                edge |= np.roll(np.roll(solid, dy, 0), dx, 1)
    edge &= ~solid

    out = np.zeros((OUT, OUT, 4), np.uint8)
    out[:, :, :3] = outline
    out[:, :, 3] = np.where(edge, 255, 0)
    out[solid, :3] = np.clip(rgb[solid], 0, 255)
    out[solid, 3] = 255
    return Image.fromarray(out, "RGBA")


def up(img: Image.Image, f: int = 8) -> Image.Image:
    return img.resize((img.width * f, img.height * f), Image.NEAREST)


def main() -> int:
    RAW.mkdir(parents=True, exist_ok=True)
    key = read_key()
    only = sys.argv[1:] or list(SPRITES)
    icons: dict[str, Image.Image] = {}

    for name in only:
        prompt = f"{SPRITES[name]}\n\n{COMMON}"
        print(f"[gen] {name} ({MODEL})", flush=True)
        raw = request_image(key, prompt)
        raw.save(RAW / f"{name}.png")
        icon = to_icon(raw)
        icon.save(ITEM / f"{name}.png")
        up(icon).save(ART / f"{name}@8x.png")
        icons[name] = icon
        a = np.array(icon)[:, :, 3]
        ys, xs = np.nonzero(a)
        print(f"      -> {ITEM / (name + '.png')}  bbox=({xs.min()},{ys.min()},{xs.max()},{ys.max()})")

    order = [n for n in ("drone", "frame_bedrock", "frame_owl", "frame_peregrine", "frame_scrap") if n in icons]
    if order:
        cell = OUT * 8
        pad = 16
        sheet = Image.new("RGBA", (cell * len(order) + pad * 2, cell + pad * 2), (32, 33, 38, 255))
        for i, n in enumerate(order):
            sheet.alpha_composite(up(icons[n]), (pad + i * cell, pad))
        sheet.save(ART / "sheet@8x.png")
        print("sheet ->", ART / "sheet@8x.png")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
