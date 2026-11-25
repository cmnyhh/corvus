#!/usr/bin/env python3
"""Strict validation for the exported mill / facility models.

Checks mirror what Minecraft's `BlockModel.Deserializer` accepts in 1.20.1 and
1.21.1, plus the art-direction rules of the brief:

  * every element from/to inside 0..16, and every *rotated corner* too, so a cell
    can never poke into a neighbour
  * rotation about one axis only, angle in {0, +-22.5, +-45}
  * faces reference a texture key that exists; uv inside 0..16
  * a painted (non flat) face must keep a 1:1 pixel aspect with its uv island
  * textures are 32x32 and fully opaque
  * item model parents its block model; block model parents block/block
  * .bbmodel geometry + per-face uv identical to the java model
  * multi-block seam discipline: a lower cell never reaches y=16, an upper cell
    never starts at y=0 (so stacked cells cannot z-fight)
  * glow faces are single flat colours (ready for emissive maps)

Writes tools/validate-report.txt and exits non-zero on any error.
"""
from __future__ import annotations

import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.dirname(HERE)
DIRS = ("north", "east", "south", "west", "up", "down")
ALLOWED_ANGLES = (0.0, 22.5, -22.5, 45.0, -45.0)
NS = "huhu_dronepal"
PX_PER_UNIT = 2.0

NAMES = ["ore_mill", "ore_mill_side", "water_pump", "power_tower", "power_tower_mid",
         "power_tower_top", "water_pipe_straight", "water_pipe_corner",
         "water_pipe_tee", "water_pipe_cross", "water_pipe_cap"]

# Seam discipline.  Vertically stacked cells: the lower one must stop at/below 15.8
# and the upper one must start at/above 0.2 (0.4 dark joint).  The horizontal ore
# mill is instead split on X, and its flange lips deliberately reach into the gap
# (west to 15.95, east from 0.05) so no two faces are ever coplanar on the seam:
# the joint is a 0.1 dark line between the two flange rims.
SEAM = {
    "ore_mill": ("x", "max", 15.95, "flange lip may enter the seam gap (west half)"),
    "ore_mill_side": ("x", "min", 0.05, "flange lip may enter the seam gap (east half)"),
    "power_tower": ("y", "max", 15.80, "lower cell of the 3-cell tower"),
    "power_tower_mid": ("y", "max", 15.80, "middle cell of the 3-cell tower"),
    "power_tower_top": ("y", "min", 0.20, "upper cell of the 3-cell tower"),
}

GLOW_HINTS = ("glass", "led", "lamp", "beacon", "sight", "node", "spine", "emit",
              "screen_line", "panel_glow", "crown_band", "estop_cap", "btn",
              "coil_feed", "outlet_glow", "cap_bead", "intake_glow", "win_glass",
              "gauge_seg", "accent")


class Report:
    def __init__(self):
        self.lines = []
        self.errors = 0
        self.warns = 0

    def ok(self, msg):
        self.lines.append(f"  PASS  {msg}")

    def err(self, msg):
        self.errors += 1
        self.lines.append(f"  FAIL  {msg}")

    def warn(self, msg):
        self.warns += 1
        self.lines.append(f"  WARN  {msg}")

    def head(self, msg):
        self.lines.append("")
        self.lines.append(msg)


def read_png(path):
    """Minimal PNG reader for 8-bit RGBA non-interlaced files (what we write)."""
    import struct
    import zlib
    with open(path, "rb") as f:
        data = f.read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    pos = 8
    idat = b""
    w = h = None
    bit_depth = color_type = None
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        tag = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + ln]
        if tag == b"IHDR":
            w, h, bit_depth, color_type = struct.unpack(">IIBB", chunk[:10])
        elif tag == b"IDAT":
            idat += chunk
        pos += 12 + ln
    raw = zlib.decompress(idat)
    assert bit_depth == 8 and color_type == 6, (bit_depth, color_type)
    stride = w * 4
    out = []
    prev = bytearray(stride)
    i = 0
    for y in range(h):
        ft = raw[i]
        i += 1
        line = bytearray(raw[i:i + stride])
        i += stride
        if ft == 1:
            for x in range(4, stride):
                line[x] = (line[x] + line[x - 4]) & 0xFF
        elif ft == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 0xFF
        elif ft == 3:
            for x in range(stride):
                a = line[x - 4] if x >= 4 else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 0xFF
        elif ft == 4:
            for x in range(stride):
                a = line[x - 4] if x >= 4 else 0
                c = prev[x - 4] if x >= 4 else 0
                b = prev[x]
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 0xFF
        out.append(bytes(line))
        prev = line
    return w, h, out


def px(w, h, rows, x, y):
    r = rows[y]
    return tuple(r[x * 4:x * 4 + 4])


def main():
    rep = Report()
    summary = json.load(open(os.path.join(HERE, "build-summary.json")))
    tex_cache = {}
    total_cubes = total_faces = 0

    for name in NAMES:
        rep.head(f"=== {name} ===")
        block_path = os.path.join(ART, "export", "models", "block", f"{name}.json")
        item_path = os.path.join(ART, "export", "models", "item", f"{name}.json")
        bb_path = os.path.join(ART, f"{name}.bbmodel")
        missing = False
        for p in (block_path, item_path, bb_path):
            if not os.path.exists(p):
                rep.err(f"missing file {os.path.relpath(p, ART)}")
                missing = True
        if missing:
            continue

        model = json.load(open(block_path))
        item = json.load(open(item_path))
        bb = json.load(open(bb_path))

        # ---------------- textures ----------------------------------------
        tex = model.get("textures") or {}
        if not tex:
            rep.err("block model has no textures")
            continue
        for k, v in tex.items():
            if not isinstance(v, str) or v.count(":") != 1:
                rep.err(f"texture entry {k}={v!r} is not a namespaced resource location")
            elif v.split(":")[0] != NS:
                rep.err(f"texture {k}={v} namespace != {NS}")
        atlas = sorted({v.split("/")[-1] for v in tex.values()})
        if len(atlas) != 1:
            rep.err(f"model references several atlases: {atlas}")
            continue
        atlas = atlas[0]
        tex_path = os.path.join(ART, "export", "textures", "block", atlas + ".png")
        if not os.path.exists(tex_path):
            rep.err(f"missing texture {os.path.relpath(tex_path, ART)}")
            continue
        rep.ok(f"texture atlas = {atlas}.png")
        if atlas not in tex_cache:
            tex_cache[atlas] = read_png(tex_path)
        w, h, rows = tex_cache[atlas]
        if (w, h) != (32, 32):
            rep.err(f"texture is {w}x{h}, brief asks 32x32")
        else:
            rep.ok("texture is 32x32")
        holes = 0
        for y in range(h):
            for x in range(w):
                if px(w, h, rows, x, y)[3] != 255:
                    holes += 1
        if holes:
            rep.err(f"texture has {holes} non-opaque pixels")
        else:
            rep.ok("texture fully opaque (0 transparent pixels)")

        # ---------------- structure ---------------------------------------
        if model.get("parent") != "minecraft:block/block":
            rep.err(f"block parent {model.get('parent')!r} != minecraft:block/block")
        else:
            rep.ok("block parent = minecraft:block/block (resolves in 1.20.1 and 1.21.1)")
        els = model.get("elements")
        if not isinstance(els, list) or not els:
            rep.err("block model has no elements")
            continue
        rep.ok(f"{len(els)} elements, {len(els) * 6} faces")
        total_cubes += len(els)
        total_faces += len(els) * 6

        bad_rot = 0
        out_of_bounds = 0
        odd_uv = 0
        bad_texref = 0
        aspect_bad = []
        for i, el in enumerate(els):
            frm, to = el.get("from"), el.get("to")
            nm = el.get("name", f"#{i}")
            if not (isinstance(frm, list) and isinstance(to, list) and len(frm) == 3
                    and len(to) == 3):
                rep.err(f"{nm}: from/to malformed")
                continue
            for k in range(3):
                if frm[k] > to[k]:
                    rep.err(f"{nm}: from>to on axis {k}")
                if frm[k] < -1e-6 or to[k] > 16 + 1e-6:
                    out_of_bounds += 1
            rot = el.get("rotation")
            if rot:
                if rot.get("axis") not in ("x", "y", "z"):
                    rep.err(f"{nm}: rotation axis {rot.get('axis')!r} invalid")
                if float(rot.get("angle", 999)) not in ALLOWED_ANGLES:
                    bad_rot += 1
                    rep.err(f"{nm}: rotation angle {rot.get('angle')} rejected by vanilla")
                if not (isinstance(rot.get("origin"), list) and len(rot["origin"]) == 3):
                    rep.err(f"{nm}: rotation.origin malformed")
            faces = el.get("faces") or {}
            for d in DIRS:
                if d not in faces:
                    rep.err(f"{nm}: missing face {d}")
                    continue
                fc = faces[d]
                if "#" not in str(fc.get("texture", "")):
                    bad_texref += 1
                key = str(fc.get("texture", ""))[1:]
                if key not in tex:
                    rep.err(f"{nm}/{d}: texture #{key} not in textures")
                uv = fc.get("uv")
                if not (isinstance(uv, list) and len(uv) == 4):
                    rep.err(f"{nm}/{d}: uv malformed")
                    continue
                if any(float(v) < -1e-6 or float(v) > 16 + 1e-6 for v in uv):
                    odd_uv += 1
                if not (uv[0] < uv[2] and uv[1] < uv[3]):
                    rep.err(f"{nm}/{d}: uv corners not ordered {uv}")
                dx, dy, dz = [to[k] - frm[k] for k in range(3)]
                fw, fh = {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy),
                          "west": (dz, dy), "up": (dx, dz), "down": (dx, dz)}[d]
                fw, fh = fw * PX_PER_UNIT, fh * PX_PER_UNIT
                uw, uh = (uv[2] - uv[0]) * PX_PER_UNIT, (uv[3] - uv[1]) * PX_PER_UNIT
                x0 = int(round(uv[0] * 2)); y0 = int(round(uv[1] * 2))
                x1 = int(round(uv[2] * 2)); y1 = int(round(uv[3] * 2))
                cols = {px(w, h, rows, x, y) for y in range(y0, y1) for x in range(x0, x1)}
                flat = len(cols) == 1
                if not flat:
                    r_face = fw / fh if fh else 99
                    r_uv = uw / uh if uh else 99
                    if abs(r_face / r_uv - 1) > 0.20:
                        aspect_bad.append(
                            f"{nm}/{d}: face {fw:.1f}x{fh:.1f}px vs uv {uw:.0f}x{uh:.0f}px")
        if out_of_bounds:
            rep.err(f"{out_of_bounds} coordinates outside 0..16")
        else:
            rep.ok("every element from/to inside 0..16")
        if bad_rot == 0:
            rep.ok("all rotations single-axis, angle in {0, +-22.5, +-45}")
        if odd_uv == 0:
            rep.ok("all uv coordinates inside 0..16")
        if bad_texref == 0:
            rep.ok("all faces reference an existing texture key")
        if aspect_bad:
            for m in aspect_bad[:10]:
                rep.err("uv/face aspect mismatch -> " + m)
        else:
            rep.ok("every painted face keeps 1:1 pixel aspect (no stretching)")

        # cullface may only sit on an unrotated face flush with the block boundary
        cull_bad = 0
        for el in els:
            for d, fc in (el.get("faces") or {}).items():
                if not fc.get("cullface"):
                    continue
                idx = {"north": 2, "south": 2, "east": 0, "west": 0, "up": 1, "down": 1}[d]
                plane = el["from"][idx] if d in ("north", "west", "down") else el["to"][idx]
                if abs(plane) > 1e-6 and abs(plane - 16) > 1e-6:
                    cull_bad += 1
                    rep.err(f"{el['name']}/{d}: cullface not on the block boundary")
                if el.get("rotation"):
                    cull_bad += 1
                    rep.err(f"{el['name']}/{d}: cullface on a rotated element")
        if cull_bad == 0:
            rep.ok("cullface only on unrotated faces flush with the block boundary")

        # rotated corner sweep: from/to alone is not enough
        true_min = [1e9] * 3
        true_max = [-1e9] * 3
        rot_oob = 0
        for el in els:
            frm, to = el["from"], el["to"]
            pts = [[frm[0] if i & 1 else to[0], frm[1] if i & 2 else to[1],
                    frm[2] if i & 4 else to[2]] for i in range(8)]
            r = el.get("rotation")
            if r:
                ax = "xyz".index(r["axis"])
                a = math.radians(r["angle"])
                ca, sa = math.cos(a), math.sin(a)
                o = r["origin"]
                out = []
                for pt in pts:
                    v = [pt[k] - o[k] for k in range(3)]
                    if ax == 0:
                        v = [v[0], v[1] * ca - v[2] * sa, v[1] * sa + v[2] * ca]
                    elif ax == 1:
                        v = [v[0] * ca + v[2] * sa, v[1], -v[0] * sa + v[2] * ca]
                    else:
                        v = [v[0] * ca - v[1] * sa, v[0] * sa + v[1] * ca, v[2]]
                    out.append([v[k] + o[k] for k in range(3)])
                pts = out
            for pt in pts:
                for k in range(3):
                    true_min[k] = min(true_min[k], pt[k])
                    true_max[k] = max(true_max[k], pt[k])
                    if pt[k] < -1e-6 or pt[k] > 16 + 1e-6:
                        rot_oob += 1
        if rot_oob:
            rep.err(f"{rot_oob} rotated corner(s) leave the 0..16 cell volume")
        else:
            rep.ok("rotated corner sweep stays inside 0..16 "
                   f"(true AABB {[round(v, 2) for v in true_min]} .. "
                   f"{[round(v, 2) for v in true_max]})")

        # seam discipline (see SEAM): no face may land on the shared seam plane
        if name in SEAM:
            axis, kind, limit, why = SEAM[name]
            k = "xyz".index(axis)
            hi = max(el["to"][k] for el in els)
            lo = min(el["from"][k] for el in els)
            if kind == "max" and hi > limit + 1e-6:
                rep.err(f"{axis} reaches {hi} (limit {limit}; {why})")
            elif kind == "min" and lo < limit - 1e-6:
                rep.err(f"{axis} starts at {lo} (limit {limit}; {why})")
            else:
                rep.ok(f"seam discipline OK on {axis} ({lo} .. {hi}; {why})")
        top = max(el["to"][1] for el in els)
        bot = min(el["from"][1] for el in els)
        rep.ok(f"vertical extent {bot} .. {top}")

        # glow faces must be one flat colour
        flat_ok = 0
        for el in els:
            nm = el.get("name", "")
            if not any(t in nm for t in GLOW_HINTS):
                continue
            for d, fc in el["faces"].items():
                uv = fc["uv"]
                x0, y0, x1, y1 = [int(round(v * 2)) for v in uv]
                cols = {px(w, h, rows, x, y) for y in range(y0, y1) for x in range(x0, x1)}
                if len(cols) != 1:
                    rep.err(f"{nm}/{d}: glow face is not a single flat colour "
                            f"({len(cols)} colours)")
                else:
                    flat_ok += 1
        if flat_ok:
            rep.ok(f"{flat_ok} glow faces are single flat colours (emissive-map ready)")

        # boundary contact report (informational)
        for el in els:
            for k, axis in enumerate("xyz"):
                if abs(el["to"][k] - 16) < 1e-6:
                    rep.warn(f"{el['name']}: touches 16 on {axis} (flush with neighbour)")
                if abs(el["from"][k]) < 1e-6:
                    rep.warn(f"{el['name']}: touches 0 on {axis} (flush with neighbour)")

        # ---------------- item model --------------------------------------
        ip = item.get("parent", "")
        if ip != f"{NS}:block/{name}":
            rep.err(f"item parent {ip!r} != {NS}:block/{name}")
        else:
            rep.ok("item model parents the block model")
        if "display" not in item:
            rep.warn("item model has no display block")
        else:
            rep.ok("item model carries explicit gui/ground/hand display transforms")

        # ---------------- bbmodel agreement -------------------------------
        meta = bb.get("meta", {})
        if meta.get("model_format") != "java_block":
            rep.err(f"bbmodel model_format {meta.get('model_format')!r} != java_block")
        if str(meta.get("format_version", "")) < "5.0":
            rep.warn(f"bbmodel format_version {meta.get('format_version')}")
        bel = bb.get("elements", [])
        if len(bel) != len(els):
            rep.err(f"bbmodel has {len(bel)} elements, java model {len(els)}")
        else:
            rep.ok(f"bbmodel element count matches java model ({len(els)})")
        mism = 0
        for a, b in zip(els, bel):
            if [round(v, 3) for v in a["from"]] != [round(v, 3) for v in b["from"]] or \
               [round(v, 3) for v in a["to"]] != [round(v, 3) for v in b["to"]]:
                mism += 1
                continue
            ra, rb = a.get("rotation"), b.get("rotation")
            if bool(ra) != bool(rb):
                mism += 1
                continue
            if ra:
                axis = "xyz".index(ra["axis"])
                if abs(rb[axis] - ra["angle"]) > 1e-6 or [round(v, 3) for v in rb] != \
                        [round(ra["angle"] if i == axis else 0, 3) for i in range(3)]:
                    mism += 1
                    continue
            for d in DIRS:
                ua = [round(v * PX_PER_UNIT, 1) for v in a["faces"][d]["uv"]]
                ub = [round(v, 1) for v in b["faces"][d]["uv"]]
                if ua != ub:
                    mism += 1
                    break
        if mism:
            rep.err(f"{mism} elements differ between .bbmodel and java model")
        else:
            rep.ok(".bbmodel geometry + per-face uv identical to the java model")

        s = summary.get(name)
        if s:
            rep.ok(f"stats: {s['cubes']} cubes / {s['faces']} faces / {s['rotated']} rotated, "
                   f"size {s['size']}")

    # ---------------- cross-model facts for the MANIFEST -------------------
    rep.head("=== glow swatch map (for MANIFEST) ===")
    for f in sorted(os.listdir(os.path.join(HERE, "atlas"))):
        lay = json.load(open(os.path.join(HERE, "atlas", f)))
        glow = {k: v for k, v in lay["swatches"].items()
                if v["size"] == 4 and k in ("amber1", "amber2", "ember", "mag1",
                                            "aqua2", "lamp", "aqua3")}
        if glow:
            desc = ", ".join(f"{k}@({v['px'][0]},{v['px'][1]}) 4x4 {v['hex']}"
                             for k, v in sorted(glow.items()))
            rep.lines.append(f"  {lay['texture']:22s} {desc}")

    rep.head("=== summary ===")
    rep.lines.append(f"  errors: {rep.errors}   warnings: {rep.warns}")
    rep.lines.append(f"  models: {len(NAMES)}   cubes: {total_cubes}   faces: {total_faces}")
    text = "\n".join(rep.lines)
    with open(os.path.join(HERE, "validate-report.txt"), "w") as f:
        f.write(text + "\n")
    print("\n".join(l for l in rep.lines if "FAIL" in l or "===" in l or "errors:" in l
                    or "swatch map" in l or l.startswith("  o")))
    return 0 if rep.errors == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
