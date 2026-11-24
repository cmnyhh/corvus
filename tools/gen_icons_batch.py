"""Batch-generate every icon still marked "缺专图" in docs/ICON-PROMPTS.md.

The doc already holds a copy-paste prompt per row, so the doc is the source of truth:
this script parses the tables, skips rows that already have art, sends the prompt (plus a
small technical suffix) to the relay, and folds the answer down onto the exact pixel grid
the row asks for (16x16 for items/symbols, full-bleed for block faces).

    python tools/gen_icons_batch.py --list
    python tools/gen_icons_batch.py --section 厂商徽记

Each finished icon is also mirrored into artifacts/icons-batch/ for review sheets.
"""
from __future__ import annotations

import argparse
import base64
import concurrent.futures as futures
import json
import os
import re
import urllib.request
import uuid
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/huhu_dronepal/textures"
DOC = ROOT / "docs/ICON-PROMPTS.md"
ART = ROOT / "artifacts/icons-batch"
RAW = ART / "raw"
ENDPOINT = "https://vvvvvip.tststs.shop/v1/images/generations"
MODEL = os.environ.get("DRONEPAL_IMAGE_MODEL", "gemini-nano-banana-2.1")

SKIP_SECTIONS = {"机体与随行终端", "附录 · 配套实体涂装"}   # drones: on hold; UV: layout-locked

SUFFIX = {
    "item": ("以 16×16 逻辑像素网格绘制，每个逻辑像素是一块方正色块；单个物体居中、占画面约八成；"
             "纯色平涂，无渐变无抗锯齿；左上受光；外缘一圈近黑色描边；背景为纯绿色 #00FF00，满铺到边。"),
    "gui": ("以 16×16 逻辑像素网格绘制，每个逻辑像素是一块方正色块；符号正视居中，高对比实色剪影，"
            "保留负形留白；无外发光；背景为纯绿色 #00FF00，满铺到边。"),
    "block": ("以 16×16 逻辑像素网格绘制，每个逻辑像素是一块方正色块；正交平视，纹理满铺到四边，"
              "硬边像素，不留透明缝，不要整体外描边，不要画透视。"),
}


def read_key() -> str:
    key = os.environ.get("DSH_IMAGE_GEN_OPENAI_COMPAT_KEY")
    if key:
        return key.strip()
    for line in (Path.home() / ".dsh/.credentials.yaml").read_text().splitlines():
        m = re.match(r"\s*DSH_IMAGE_GEN_OPENAI_COMPAT_KEY:\s*(\S+)\s*$", line)
        if m:
            return m.group(1)
    raise SystemExit("no image relay key")


def parse_rows() -> list[dict]:
    rows = []
    section = None
    for line in DOC.read_text().splitlines():
        if line.startswith("## "):
            section = line[3:].strip()
            continue
        if not line.startswith("| "):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 5:
            continue
        name, file_cell, size, status, prompt = cells[:5]
        m = re.search(r"`([^`]+)`", file_cell)
        if not m:
            continue
        rel = m.group(1)
        rows.append({
            "section": section,
            "name": name,
            "rel": rel,
            "size": size,
            "status": status,
            "prompt": prompt,
            "missing": "缺" in status,
        })
    return rows


def target_size(spec: str) -> tuple[int, int] | None:
    m = re.match(r"(\d+)\s*[×x]\s*(\d+)", spec)
    return (int(m.group(1)), int(m.group(2))) if m else None


def mode_of(rel: str) -> str:
    if rel.startswith("block/"):
        return "block"
    if rel.startswith("gui/"):
        return "gui"
    return "item"


# ------------------------------------------------------------------ image handling
def request(key: str, prompt: str) -> Image.Image:
    body = json.dumps({"model": MODEL, "prompt": prompt, "n": 1,
                       "size": "1024x1024"}).encode()
    req = urllib.request.Request(ENDPOINT, data=body, headers={
        "Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        payload = json.load(resp)
    item = payload["data"][0]
    if item.get("b64_json"):
        blob = base64.b64decode(item["b64_json"])
    else:
        with urllib.request.urlopen(item["url"], timeout=180) as resp:
            blob = resp.read()
    tmp = RAW / f"_tmp-{uuid.uuid4().hex}.bin"
    tmp.write_bytes(blob)
    img = Image.open(tmp).convert("RGB")
    tmp.unlink(missing_ok=True)
    return img


def backdrop_of(img: Image.Image) -> np.ndarray:
    """Most common colour along the frame edge (the answer may have letterboxing)."""
    arr = np.array(img.convert("RGB")).astype(np.int16)
    h, w, _ = arr.shape
    edge = np.concatenate([arr[0], arr[h - 1], arr[:, 0], arr[:, w - 1]])
    q = (edge // 16).astype(np.int32)
    key = q[:, 0] * 4096 + q[:, 1] * 64 + q[:, 2]
    vals, counts = np.unique(key, return_counts=True)
    winner = vals[counts.argmax()]
    sel = edge[key == winner]
    return np.median(sel, axis=0).astype(float)


def key_green(img: Image.Image) -> Image.Image:
    """Remove only the background that is connected to the border, then its green fringe.

    Keying by colour alone would punch holes in genuinely green objects (the circuit
    board), so the fill is seeded from the frame edge and interior greens are protected.
    """
    arr = np.array(img.convert("RGB")).astype(np.int16)
    h, w, _ = arr.shape
    bg = backdrop_of(img).astype(np.int16)
    dist = np.abs(arr - bg).max(axis=2)
    near = dist <= 48

    filled = np.zeros((h, w), bool)
    stack = [(y, x) for x in range(w) for y in (0, h - 1) if near[y, x]]
    stack += [(y, x) for y in range(h) for x in (0, w - 1) if near[y, x]]
    while stack:
        y, x = stack.pop()
        if filled[y, x] or not near[y, x]:
            continue
        filled[y, x] = True
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and not filled[ny, nx] and near[ny, nx]:
                stack.append((ny, nx))

    r, g, b = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
    greenish = (g > 70) & (g - r > 25) & (g - b > 25)
    pure_green = (g > 200) & (r < 90) & (b < 90)          # unblended chroma backdrop
    halo = np.zeros((h, w), bool)
    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        halo |= np.roll(filled, (dy, dx), (0, 1))
    halo &= ~filled

    out = np.array(img.convert("RGBA"))
    out[filled, 3] = 0
    out[halo & greenish, 3] = 0
    out[pure_green, 3] = 0
    return Image.fromarray(out, "RGBA")


def detect_grid(img: Image.Image) -> int:
    g = np.array(img.convert("L").resize((256, 256), Image.BOX), float)
    best = (1e9, 32)
    for n in (8, 10, 12, 16, 20, 24, 32, 40, 48, 64):
        step = 256 // n
        if step < 2:
            continue
        blocks = g[: n * step, : n * step].reshape(n, step, n, step)
        inner = blocks[:, step // 4: step - step // 4, :, step // 4: step - step // 4]
        score = inner.reshape(n, n, -1).std(axis=2).mean()
        if score < best[0]:
            best = (score, n)
    return best[1]


def sample_grid(img: Image.Image, n: int) -> np.ndarray:
    """One colour per logical cell, taken from its opaque pixels only.

    Reading the median of *all* pixels would bake the green backdrop into half-covered
    edge cells; a cell is kept only if most of it is actual artwork.
    """
    arr = np.array(img.convert("RGBA"))
    h, w, _ = arr.shape
    cells = np.zeros((n, n, 4), np.uint8)
    for i in range(n):
        for j in range(n):
            y0, y1 = int(i * h / n), int((i + 1) * h / n)
            x0, x1 = int(j * w / n), int((j + 1) * w / n)
            patch = arr[y0:y1, x0:x1].reshape(-1, 4)
            opaque = patch[patch[:, 3] > 200]
            if len(opaque) < 0.45 * len(patch):
                continue                       # mostly backdrop: leave transparent
            cells[i, j] = np.median(opaque, axis=0)
            cells[i, j, 3] = 255
    return cells


def merge_to(cells: np.ndarray, k: int) -> np.ndarray:
    n = cells.shape[0]
    if n == k:
        return cells
    if n > k and n % k == 0:
        f = n // k
        out = np.zeros((k, k, 4), np.uint8)
        for i in range(k):
            for j in range(k):
                block = cells[i * f:(i + 1) * f, j * f:(j + 1) * f].reshape(-1, 4)
                out[i, j] = np.median(block, axis=0)
        return out
    img = Image.fromarray(cells, "RGBA").resize((k, k), Image.BOX)
    return np.array(img)


def cell_backdrop(cells: np.ndarray) -> np.ndarray:
    """Colour of the backdrop as seen on the 16x16 grid (median of the four corners)."""
    k = cells.shape[0]
    corners = np.array([cells[0, 0], cells[0, k - 1], cells[k - 1, 0], cells[k - 1, k - 1]], float)
    return np.median(corners, axis=0)


def finish(cells: np.ndarray, mode: str, outline=(22, 21, 26), backdrop=None) -> Image.Image:
    k = cells.shape[0]
    solid = cells[:, :, 3] >= 200
    if backdrop is not None:
        # anything still close to the backdrop colour is chroma residue: the real
        # object greens sit far from a bright key colour, so they survive
        dist = np.abs(cells[:, :, :3].astype(float) - backdrop[:3]).max(axis=2)
        solid &= ~(dist < 70)
    # chroma residue shows up as small isolated green islands; a genuinely green
    # object (the circuit board) is one large connected area, so keep big ones only
    fr, fg, fb = (cells[:, :, i].astype(int) for i in range(3))
    greenish = (fg > 80) & (fg - fr > 28) & (fg - fb > 28)
    seen = np.zeros_like(greenish)
    for y in range(k):
        for x in range(k):
            if not greenish[y, x] or seen[y, x]:
                continue
            comp, stack = [], [(y, x)]
            seen[y, x] = True
            while stack:
                cy, cx = stack.pop()
                comp.append((cy, cx))
                for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    ny, nx = cy + dy, cx + dx
                    if 0 <= ny < k and 0 <= nx < k and greenish[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        stack.append((ny, nx))
            if len(comp) <= 7:
                for cy, cx in comp:
                    solid[cy, cx] = False
    neigh = np.zeros(solid.shape, np.int16)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dx or dy:
                neigh += np.roll(np.roll(solid, dy, 0), dx, 1)
    solid &= neigh >= 2

    # single-pixel islands are always noise at this size
    comp = np.zeros_like(solid)
    for y in range(k):
        for x in range(k):
            if not solid[y, x] or comp[y, x]:
                continue
            comp_id = int(comp.max()) + 1
            stack, cells_in = [(y, x)], []
            comp[y, x] = comp_id
            while stack:
                cy, cx = stack.pop()
                cells_in.append((cy, cx))
                for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    ny, nx = cy + dy, cx + dx
                    if 0 <= ny < k and 0 <= nx < k and solid[ny, nx] and not comp[ny, nx]:
                        comp[ny, nx] = comp_id
                        stack.append((ny, nx))
            if len(cells_in) < 2:
                solid[y, x] = False

    rgb = cells[:, :, :3].astype(float)
    if solid.any():
        ncol = 9 if mode == "item" else 6
        q = Image.fromarray(rgb[solid].astype(np.uint8).reshape(-1, 1, 3)).quantize(colors=ncol, method=Image.MEDIANCUT)
        pal = np.array(q.getpalette()[: ncol * 3]).reshape(-1, 3)
        rgb[solid] = pal[np.array(q).reshape(-1)]

    if mode == "block":
        out = np.zeros((k, k, 4), np.uint8)
        out[:, :, :3] = np.clip(rgb, 0, 255)
        out[:, :, 3] = 255
        return Image.fromarray(out, "RGBA")

    edge = np.zeros(solid.shape, bool)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dx or dy:
                edge |= np.roll(np.roll(solid, dy, 0), dx, 1)
    edge &= ~solid

    out = np.zeros((k, k, 4), np.uint8)
    out[:, :, :3] = outline
    out[:, :, 3] = np.where(edge, 255, 0)
    out[solid, :3] = np.clip(rgb[solid], 0, 255)
    out[solid, 3] = 255
    return Image.fromarray(out, "RGBA")


def build(key: str, row: dict, force: bool = False) -> dict:
    dest = ASSETS / row["rel"]
    size = target_size(row["size"])
    rec = {"name": row["name"], "rel": row["rel"], "size": row["size"],
           "section": row["section"], "ok": False}
    if size is None:
        rec["error"] = "unsupported size"
        return rec
    if dest.exists() and not force:
        rec.update(ok=True, note="already present")
        return rec

    mode = mode_of(row["rel"])
    prompt = f"{row['prompt']}\n\n{SUFFIX[mode]}"
    try:
        raw = request(key, prompt)
    except Exception as exc:  # network / relay hiccup: keep going
        rec["error"] = f"{type(exc).__name__}: {exc}"
        return rec

    flat = row["rel"].replace("/", "__").replace(".png", "")
    raw.save(RAW / f"{flat}-raw.png")

    if mode == "block":
        small = raw.convert("RGBA").resize(size, Image.BOX)
        cells = np.array(small)
        img = finish(cells, "block")
    else:
        bg = backdrop_of(raw)
        keyed = key_green(raw)
        n = detect_grid(raw)
        cells = sample_grid(keyed, n)
        cells = merge_to(cells, size[0])
        img = finish(cells, mode, backdrop=bg)

    dest.parent.mkdir(parents=True, exist_ok=True)
    img.save(dest)
    img.resize((max(1, size[0] * 8), max(1, size[1] * 8)), Image.NEAREST).save(ART / f"{flat}@8x.png")
    rec.update(ok=True, grid=n if mode != "block" else None, out=str(dest.relative_to(ROOT)))
    return rec


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--section", action="append", help="limit to a section title")
    ap.add_argument("--only", action="append", help="limit to a target file")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--include-existing", action="store_true",
                    help="also redraw rows the doc marks as already shipped")
    ap.add_argument("--reprocess", action="store_true",
                    help="rebuild the PNGs from raws already on disk (no API calls)")
    args = ap.parse_args()

    rows = [r for r in parse_rows() if r["section"] not in SKIP_SECTIONS]
    if not args.include_existing:
        rows = [r for r in rows if r["missing"]]
    if args.section:
        rows = [r for r in rows if r["section"] in args.section]
    if args.only:
        rows = [r for r in rows if r["rel"] in args.only]
    # composite atlases / panels need layout work, not a single sprite; skip them here
    rows = [r for r in rows
            if target_size(r["size"]) and max(target_size(r["size"])) <= 32]

    if args.list:
        for r in rows:
            print(f"{r['section']:24s} {r['rel']:36s} {r['size']:8s} {r['name']}")
        print(len(rows), "to build")
        return 0

    RAW.mkdir(parents=True, exist_ok=True)
    if args.reprocess:
        rebuilt = 0
        for row in rows:
            flat = row["rel"].replace("/", "__").replace(".png", "")
            raw_path = RAW / f"{flat}-raw.png"
            if not raw_path.exists():
                continue
            raw = Image.open(raw_path).convert("RGB")
            size = target_size(row["size"])
            mode = mode_of(row["rel"])
            if mode == "block":
                cells = np.array(raw.convert("RGBA").resize(size, Image.BOX))
                img = finish(cells, "block")
            else:
                bg = backdrop_of(raw)
                keyed = key_green(raw)
                n = detect_grid(raw)
                cells = merge_to(sample_grid(keyed, n), size[0])
                img = finish(cells, mode, backdrop=bg)
            dest = ASSETS / row["rel"]
            dest.parent.mkdir(parents=True, exist_ok=True)
            img.save(dest)
            img.resize((size[0] * 8, size[1] * 8), Image.NEAREST).save(ART / f"{flat}@8x.png")
            rebuilt += 1
        print(f"reprocessed {rebuilt} icons")
        return 0

    key = read_key()
    results = []
    with futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        jobs = {pool.submit(build, key, r, args.force): r for r in rows}
        for done in futures.as_completed(jobs):
            rec = done.result()
            results.append(rec)
            mark = "ok " if rec["ok"] else "ERR"
            print(f"[{mark}] {rec['name']:<28s} {rec['rel']:<34s} {rec.get('error', rec.get('note', ''))}", flush=True)

    manifest_path = ART / "manifest.json"
    merged: dict[str, dict] = {}
    if manifest_path.exists():
        for rec in json.loads(manifest_path.read_text()):
            merged[rec["rel"]] = rec
    for rec in results:
        merged[rec["rel"]] = rec
    manifest_path.write_text(json.dumps(list(merged.values()), ensure_ascii=False, indent=2))
    ok = sum(1 for r in results if r["ok"])
    print(f"\n{ok}/{len(results)} written")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
