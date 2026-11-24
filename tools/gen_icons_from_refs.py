"""Generate item icons from the rendered geo-model references (image-conditioned).

Flow per frame:
    artifacts/frame-icons/ref/<frame>.png  (render of the real model)
        -> relay /v1/images/edits (reference image + prompt)
        -> raw render kept for inspection
        -> folded to a hard 32x32 sprite with outline + safety margin

The reference keeps the icon honest: same pods, same span, same paint. The prompt only
asks for the item-sprite treatment (pixel grid, flat colour, outline, framing).
"""
from __future__ import annotations

import base64
import json
import mimetypes
import os
import sys
import urllib.request
import uuid
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gen_frame_icons_ai import ITEM, OUT, RAW, read_key, to_icon, up  # noqa: E402

ART = Path(__file__).resolve().parents[1] / "artifacts/frame-icons"
REF = ART / "ref"
EDITS = "https://vvvvvip.tststs.shop/v1/images/edits"
MODEL = os.environ.get("DRONEPAL_IMAGE_MODEL", "gemini-nano-banana-2.1")

STYLE = (
    "Redraw the machine in the reference image as a Minecraft item icon on a logical 32x32 "
    "pixel grid, blown up with hard square pixels. Keep exactly the same silhouette, the same "
    "camera angle (three-quarter view from above front-left), the same pod positions, the same "
    "span, and the same colour scheme as the reference. Simplify to flat solid colours, at most "
    "7 colours plus a one-pixel near-black outline around the whole silhouette; no gradients, "
    "no dithering, no gloss, no reflections, no cast shadow, no glow halo, no text, no letters, "
    "no watermark, no UI. Remove the white background completely and replace it with one "
    "uniform flat chroma green #00FF00 edge to edge. Object centred, occupying about 80% of the "
    "frame width, with clear green margin on all four sides."
)

# per-frame extra notes, on top of the shared style clause
NOTES = {
    "drone": "This is the stowed companion terminal form: fold the parts tight, keep it compact.",
    "frame_peregrine": "Emit the three small notch marks along the lower side edge.",
    "frame_owl": "Keep the bright ice-blue camera lens at the front; the hull stays polar white.",
    "frame_bedrock": "Keep the heavy stepped armour tiers and the black-crimson side cargo pods.",
    "frame_scrap": "Keep the mismatched riveted patches and the orange warning lamps.",
}


def post_multipart(key: str, fields: dict[str, str], files: dict[str, Path]) -> dict:
    boundary = "----dsh" + uuid.uuid4().hex
    body = bytearray()
    for name, value in fields.items():
        body += f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode()
    for name, path in files.items():
        ctype = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; "
                 f"filename=\"{path.name}\"\r\nContent-Type: {ctype}\r\n\r\n").encode()
        body += path.read_bytes() + b"\r\n"
    body += f"--{boundary}--\r\n".encode()

    req = urllib.request.Request(EDITS, data=bytes(body), headers={
        "Authorization": f"Bearer {key}",
        "Content-Type": f"multipart/form-data; boundary={boundary}",
    })
    with urllib.request.urlopen(req, timeout=600) as resp:
        return json.load(resp)


def fetch_image(payload: dict) -> Image.Image:
    item = payload["data"][0]
    if item.get("b64_json"):
        blob = base64.b64decode(item["b64_json"])
    else:
        with urllib.request.urlopen(item["url"], timeout=180) as resp:
            blob = resp.read()
    tmp = RAW / "_last_edit.bin"
    tmp.write_bytes(blob)
    return Image.open(tmp).convert("RGBA")


def main() -> int:
    key = read_key()
    RAW.mkdir(parents=True, exist_ok=True)
    names = sys.argv[1:] or ["drone", "frame_peregrine", "frame_owl", "frame_bedrock", "frame_scrap"]

    icons: dict[str, Image.Image] = {}
    for name in names:
        ref = REF / f"{name}.png"
        if not ref.exists():
            print(f"[skip] {name}: no reference render")
            continue
        prompt = f"{STYLE}\n\n{NOTES.get(name, '')}".strip()
        print(f"[edit] {name} <- {ref.name}", flush=True)
        payload = post_multipart(
            key,
            {"model": MODEL, "prompt": prompt, "n": "1", "size": "1024x1024"},
            {"image": ref},
        )
        raw = fetch_image(payload)
        raw.save(RAW / f"{name}-edit.png")
        icon = to_icon(raw)
        icon.save(ITEM / f"{name}.png")
        up(icon).save(ART / f"{name}@8x.png")
        icons[name] = icon

    if icons:
        order = [n for n in ("drone", "frame_bedrock", "frame_owl", "frame_peregrine", "frame_scrap") if n in icons]
        cell, pad = OUT * 8, 16
        sheet = Image.new("RGBA", (cell * len(order) + pad * 2, cell + pad * 2), (32, 33, 38, 255))
        for i, n in enumerate(order):
            sheet.alpha_composite(up(icons[n]), (pad + i * cell, pad))
        sheet.save(ART / "sheet@8x.png")
        print("sheet ->", ART / "sheet@8x.png")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
