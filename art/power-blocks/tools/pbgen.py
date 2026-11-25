#!/usr/bin/env python3
"""pbgen — small authoring toolkit for Minecraft *Java* block models.

Design notes
------------
* Everything is authored in **Java block-model space**: the block corner is the
  origin, so every element lives inside 0..16 on x/y/z.  Element rotations are
  restricted to the only angles vanilla accepts (0, +-22.5, +-45) and to a
  single axis, so the exported JSON parses in 1.20.1 *and* 1.21.1.
* `--preview` emits a throw-away YSM/Bedrock pack whose geometry is the exact
  inverse of Blockbench's Bedrock<->Java transform, so a Blockbench render of
  that pack shows *the same pixels* as the Java model does in game.  Verified
  against Blockbench 5.2.2 (`js/formats/bedrock/bedrock.js` parseCube/parseBone)
  and by a texture-marker calibration render.
* Textures are 32x32.  Faces are grouped onto shared atlas islands so that the
  texel density stays honest: structural plates get 1 px per model unit (same
  as a 16x16 vanilla block), while small hero details get their own islands.
"""
from __future__ import annotations

import json
import math
import os
import zlib
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Sequence, Tuple

TEX = 32            # texture edge, px
UNITS = 16.0        # model units per block
DIRS = ("north", "east", "south", "west", "up", "down")
OPP = {"north": "south", "south": "north", "east": "west", "west": "east", "up": "down", "down": "up"}
ALLOWED_ANGLES = (0.0, 22.5, -22.5, 45.0, -45.0)


# --------------------------------------------------------------------------- png
def write_png(path: str, img) -> None:
    """img: list of rows of (r,g,b,a) tuples -> PNG (RGBA, no interlace)."""
    import struct

    h = len(img)
    w = len(img[0])
    raw = bytearray()
    for y in range(h):
        raw += b"\x00"
        for x in range(w):
            px = img[y][x]
            raw += bytes(px[:4]) if len(px) >= 4 else bytes((px[0], px[1], px[2], 255))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + \
            struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def rgba(hexstr: str, a: int = 255) -> Tuple[int, int, int, int]:
    s = hexstr.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


def shade(hexstr: str, amount: int) -> str:
    """amount in -255..255"""
    r, g, b, _ = rgba(hexstr)
    f = lambda v: max(0, min(255, v + amount))
    return "#%02x%02x%02x" % (f(r), f(g), f(b))


# --------------------------------------------------------------------------- palette
PALETTE: Dict[str, str] = {
    # machine housing (matches the dronepal drone hull: #2a2d33 .. #6a6f78)
    "hull0": "#17191d",
    "hull1": "#23262c",
    "hull2": "#2d3138",
    "hull3": "#3a3f47",
    "hull4": "#4a5059",
    # metal trim / edge
    "steel1": "#6d747e",
    "steel2": "#929aa5",
    "steel3": "#bcc4cf",
    "steel4": "#e2e8f1",
    # amber / ember (the family accent from the drone)
    "amber0": "#a1590a",
    "amber1": "#ff8a1f",
    "amber2": "#ffb44d",
    "amber3": "#ffdca8",
    "ember": "#ff6a12",
    # magenta (drone thruster accent)
    "mag0": "#8c0a45",
    "mag1": "#ff3fa0",
    # solar panel
    "pv0": "#0a1524",
    "pv1": "#122840",
    "pv2": "#1d4b78",
    "pv3": "#3d86bd",
    "pv4": "#8ed3ff",
    # battery gauge
    "bat0": "#1c3d17",
    "bat1": "#3f8f2e",
    "bat2": "#7dff5f",
    "bat3": "#c4ffb0",
    "inert": "#2a2e33",
}

# swatches that must stay *pure* (glow parts) are allocated 4x4, the rest 2x2
PURE_SWATCHES = {"ember", "amber2", "amber1", "mag1", "bat2", "pv4", "steel4"}


# --------------------------------------------------------------------------- atlas
class Painter:
    """Tiny pixel canvas handed to island painters, local (0,0) = top-left."""

    def __init__(self, w: int, h: int, fill: str):
        self.w, self.h = w, h
        self.px: List[List[Tuple[int, int, int, int]]] = [[rgba(fill) for _ in range(w)] for _ in range(h)]

    def set(self, x: int, y: int, col) -> None:
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = rgba(col) if isinstance(col, str) else col

    def get(self, x: int, y: int):
        return self.px[max(0, min(self.h - 1, y))][max(0, min(self.w - 1, x))]

    def rect(self, x0: int, y0: int, x1: int, y1: int, col) -> None:
        for y in range(y0, y1):
            for x in range(x0, x1):
                self.set(x, y, col)

    def border(self, col, t: int = 1) -> None:
        for i in range(t):
            self.rect(0, i, self.w, i + 1, col)
            self.rect(0, self.h - 1 - i, self.w, self.h - i, col)
            self.rect(i, 0, i + 1, self.h, col)
            self.rect(self.w - 1 - i, 0, self.w - i, self.h, col)

    def hline(self, y: int, x0: int, x1: int, col) -> None:
        self.rect(x0, y, x1, y + 1, col)

    def vline(self, x: int, y0: int, y1: int, col) -> None:
        self.rect(x, y0, x + 1, y1, col)

    def dither(self, x0, y0, x1, y1, col, parity=0) -> None:
        for y in range(y0, y1):
            for x in range(x0, x1):
                if (x + y) % 2 == parity:
                    self.set(x, y, col)

    def rows(self):
        return self.px


@dataclass
class Island:
    key: str
    w: int
    h: int
    painter: Callable[[Painter], None]
    x: int = 0
    y: int = 0


class Atlas:
    """Shelf packer + painter driver for one 32x32 texture."""

    def __init__(self, name: str, size: int = TEX, fill: str = "#141619"):
        self.name = name
        self.size = size
        self.islands: Dict[str, Island] = {}
        self.swatches: Dict[str, Tuple[int, int, int, int]] = {}
        self.fill = fill
        self.order: List[str] = []

    # --- registration -----------------------------------------------------
    def island(self, key: str, w: int, h: int, painter: Callable[[Painter], None]) -> str:
        if key in self.islands:
            old = self.islands[key]
            assert (old.w, old.h) == (w, h), f"island {key}: size {w}x{h} != {old.w}x{old.h}"
            return "#" + key
        self.islands[key] = Island(key, w, h, painter)
        self.order.append(key)
        return "#" + key

    def swatch(self, name: str) -> str:
        assert name in PALETTE, name
        self.swatches.setdefault(name, rgba(PALETTE[name]))
        return "@" + name

    # --- packing ----------------------------------------------------------
    def pack(self) -> None:
        """Skyline packer: lowest-then-leftmost placement, 1px gutter everywhere."""
        cells = [(isl.w, isl.h, key, "island") for key, isl in self.islands.items()]
        for name in self.swatches:
            s = 4 if name in PURE_SWATCHES else 2
            cells.append((s, s, name, "swatch"))
        cells.sort(key=lambda c: (-c[0] * c[1], -c[1], c[2]))

        skyline = [1] * self.size          # occupied height per column
        placed = {}
        for w, h, key, kind in cells:
            best = None
            for x in range(1, self.size - w):
                y = max(skyline[x:x + w])
                if y + h > self.size - 1:
                    continue
                score = (y + h, x)
                if best is None or score < best[0]:
                    best = (score, x, y)
            if best is None:
                raise RuntimeError(f"{self.name}: atlas overflow at {key} ({w}x{h})")
            _, x, y = best
            placed[key] = (x, y)
            if kind == "island":
                self.islands[key].x, self.islands[key].y = x, y
            for c in range(x, x + w):
                skyline[c] = y + h + 1
        self._placed = placed
        self.free_px = sum(max(0, self.size - 2 - v) for v in skyline)

    # --- rendering --------------------------------------------------------
    def render(self) -> List[List[Tuple[int, int, int, int]]]:
        img = Painter(self.size, self.size, self.fill)
        # 1. base: subtle dark checker so unused space never reads as "hole"
        for y in range(self.size):
            for x in range(self.size):
                img.set(x, y, shade(self.fill, 2 if (x // 4 + y // 4) % 2 else 0))
        # 2. islands
        for key, isl in self.islands.items():
            p = Painter(isl.w, isl.h, self.fill)
            isl.painter(p)
            for yy in range(isl.h):
                for xx in range(isl.w):
                    img.set(isl.x + xx, isl.y + yy, p.px[yy][xx])
        # 3. pure swatches (2x2 / 4x4 blocks of flat colour)
        for name, col in self.swatches.items():
            sx, sy = self._placed[name]
            s = 4 if name in PURE_SWATCHES else 2
            for yy in range(s):
                for xx in range(s):
                    img.set(sx + xx, sy + yy, col)
        return img.rows()

    # --- uv lookup --------------------------------------------------------
    def uv(self, ref: str) -> List[float]:
        """Return Java face uv [u1,v1,u2,v2] in 0..16 units for a face ref."""
        if ref.startswith("#"):
            isl = self.islands[ref[1:]]
            x, y, w, h = isl.x, isl.y, isl.w, isl.h
        elif ref.startswith("@"):
            name = ref[1:]
            x, y = self._placed[name]
            s = 4 if name in PURE_SWATCHES else 2
            w = h = s
        else:
            raise ValueError(ref)
        f = UNITS / self.size
        return [round(x * f, 3), round(y * f, 3), round((x + w) * f, 3), round((y + h) * f, 3)]

    def face_px(self, ref: str) -> Tuple[int, int]:
        if ref.startswith("#"):
            i = self.islands[ref[1:]]
            return i.w, i.h
        s = 4 if ref[1:] in PURE_SWATCHES else 2
        return s, s


# --------------------------------------------------------------------------- model
@dataclass
class Elem:
    name: str
    frm: Tuple[float, float, float]
    to: Tuple[float, float, float]
    faces: Dict[str, str]
    rot_axis: Optional[str] = None
    rot_angle: float = 0.0
    pivot: Tuple[float, float, float] = (8.0, 8.0, 8.0)
    note: str = ""

    @property
    def size(self):
        return (self.to[0] - self.frm[0], self.to[1] - self.frm[1], self.to[2] - self.frm[2])

    def face_units(self, d: str) -> Tuple[float, float]:
        dx, dy, dz = self.size
        return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy),
                "west": (dz, dy), "up": (dx, dz), "down": (dx, dz)}[d]


class BlockModel:
    def __init__(self, name: str, atlas: Atlas):
        self.name = name
        self.atlas = atlas
        self.elems: List[Elem] = []

    def add(self, name: str, frm, to, faces: Dict[str, str], rot=None, pivot=(8, 8, 8), note="") -> Elem:
        frm = tuple(float(v) for v in frm)
        to = tuple(float(v) for v in to)
        for i in range(3):
            assert frm[i] <= to[i] + 1e-9, f"{name}: from>to on axis {i}"
        assert min(frm) >= -1e-9 and max(to) <= 16 + 1e-9, f"{name}: outside 0..16 -> {frm} {to}"
        faces = {d: faces.get(d, faces.get("*")) for d in DIRS}
        assert all(v for v in faces.values()), f"{name}: missing face material"
        axis, angle = (rot or (None, 0.0))
        if axis:
            assert axis in "xyz", axis
            assert any(abs(angle - a) < 1e-6 for a in ALLOWED_ANGLES), f"{name}: illegal angle {angle}"
        e = Elem(name, frm, to, faces, axis, float(angle), tuple(float(v) for v in pivot), note)
        self.elems.append(e)
        return e

    def box(self, name, frm, to, mat, rot=None, pivot=(8, 8, 8), note="", per_face=None):
        """mat: single ref for all faces, or dict."""
        faces = dict(mat) if isinstance(mat, dict) else {d: mat for d in DIRS}
        if per_face:
            faces.update(per_face)
        return self.add(name, frm, to, faces, rot=rot, pivot=pivot, note=note)

    # --- stats ------------------------------------------------------------
    def stats(self):
        cubes = len(self.elems)
        faces = sum(6 for _ in self.elems)
        rotated = sum(1 for e in self.elems if e.rot_axis)
        bbox = (
            [min(e.frm[i] for e in self.elems) for i in range(3)],
            [max(e.to[i] for e in self.elems) for i in range(3)],
        )
        return {"cubes": cubes, "faces": faces, "rotated": rotated, "bbox": bbox}


# --------------------------------------------------------------------------- java export
def java_block_json(model: BlockModel, textures: Dict[str, str], parent: str) -> dict:
    elements = []
    for e in model.elems:
        el = {
            "from": [round(v, 3) for v in e.frm],
            "to": [round(v, 3) for v in e.to],
        }
        if e.rot_axis:
            el["rotation"] = {
                "origin": [round(v, 3) for v in e.pivot],
                "axis": e.rot_axis,
                "angle": round(e.rot_angle, 3),
            }
        el["shade"] = True
        el["faces"] = {}
        for d in DIRS:
            key = _texture_key_for(d, textures)
            face = {"uv": model.atlas.uv(e.faces[d]), "texture": "#" + key}
            plane = _face_plane(e, d)
            if plane is not None and (abs(plane) < 1e-6 or abs(plane - UNITS) < 1e-6):
                face["cullface"] = d          # flush with the block boundary
            el["faces"][d] = face
        el["name"] = e.name            # ignored by Minecraft, useful for humans
        elements.append(el)
    return {
        "format_version": "1.12.0",
        "parent": parent,
        "textures": {k: v for k, v in textures.items()},
        "elements": elements,
    }


def _face_plane(e: Elem, direction: str) -> Optional[float]:
    """Coordinate of the face plane along its normal (None if the cube is rotated)."""
    if e.rot_axis:
        return None
    idx = {"north": 2, "south": 2, "east": 0, "west": 0, "up": 1, "down": 1}[direction]
    lo = direction in ("north", "west", "down")
    return e.frm[idx] if lo else e.to[idx]


def _texture_key_for(direction: str, textures: Dict[str, str]) -> str:
    if direction in textures:
        return direction
    if "all" in textures:
        return "all"
    raise KeyError(f"no texture for {direction} in {list(textures)}")


def java_item_json(parent: str) -> dict:
    return {
        "parent": parent,
        "display": {
            "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
            "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
            "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
            "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
        },
    }


# --------------------------------------------------------------------------- bbmodel
def bbmodel_json(model: BlockModel, texture_name: str, texture_rel: str, texture_uuid: str,
                 texture_data_url: str = "") -> dict:
    els, outl = [], []
    for i, e in enumerate(model.elems):
        uid = f"pb-{model.name}-{i:03d}"
        el = {
            "name": e.name,
            "box_uv": False,
            "rescale": False,
            "locked": False,
            "light_emission": 15 if _is_glowy(e) else 0,
            "render_order": "default",
            "allow_mirror_modeling": True,
            "from": [round(v, 3) for v in e.frm],
            "to": [round(v, 3) for v in e.to],
            "autouv": 0,
            "color": 0,
            "origin": [round(v, 3) for v in e.pivot],
            "faces": {
                d: {
                    "uv": [round(v * TEX / UNITS, 2) for v in model.atlas.uv(e.faces[d])],
                    "texture": texture_uuid if d in ("north", "south", "east", "west", "up", "down") else texture_uuid,
                    "rotation": 0,
                    "cullface": "",
                    "tint": -1,
                    "enabled": True,
                }
                for d in DIRS
            },
            "type": "cube",
            "uuid": uid,
        }
        if e.rot_axis:
            r = [0, 0, 0]
            r["xyz".index(e.rot_axis)] = round(e.rot_angle, 3)
            el["rotation"] = r
        els.append(el)
        outl.append(uid)

    return {
        "meta": {"format_version": "5.0", "model_format": "java_block", "box_uv": False},
        "name": model.name,
        "model_identifier": model.name,
        "parent": "block/block",
        "ambientocclusion": True,
        "texture_size": [TEX, TEX],
        "resolution": {"width": TEX, "height": TEX},
        "elements": els,
        "outliner": outl,
        "textures": [{
            "path": "",
            "name": texture_name,
            "folder": "block",
            "namespace": "",
            "id": "0",
            "width": TEX,
            "height": TEX,
            "uv_width": TEX,
            "uv_height": TEX,
            "particle": True,
            "use_as_default": False,
            "layers_enabled": False,
            "sync_to_project": "",
            "render_mode": "default",
            "render_sides": "auto",
            "frame_time": 1,
            "frame_order": "loop",
            "frame_interpolate": False,
            "visible": True,
            "internal": True,
            "saved": False,
            "uuid": texture_uuid,
            "relative_path": texture_rel,
            "source": texture_data_url,
        }],
        "display": {
            "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
            "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
            "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
        },
    }


def _is_glowy(e: Elem) -> bool:
    return any(ref.strip("@#") in PURE_SWATCHES or ref.strip("@#") in ("ember", "amber1", "bat2") for ref in e.faces.values()) and \
        any(ref.startswith("@") for ref in e.faces.values())


# --------------------------------------------------------------------------- preview pack
def emit_preview_pack(model: BlockModel, pack_dir: str, atlas_img, title: str) -> str:
    """Emit a YSM/Bedrock pack whose Blockbench render equals the Java model.

    Inverse of Blockbench's parseCube():
        from[0] = -(bedrock.origin[0] + size[0]),  pivot[0] = -bedrock.pivot[0]
        rotation[x] = -bedrock.rotation[x], rotation[y] = -bedrock.rotation[y], rotation[z] unchanged
        up/down faces: blockbench uv = [u2,v2,u1,v1]
    """
    swap = {"north": "north", "south": "south", "east": "west", "west": "east", "up": "up", "down": "down"}
    cubes = []
    for e in model.elems:
        sx, sy, sz = e.size
        cube = {
            "origin": [-round(e.to[0], 3), round(e.frm[1], 3), round(e.frm[2], 3)],
            "size": [round(sx, 3), round(sy, 3), round(sz, 3)],
            "name": e.name,
        }
        if e.rot_axis:
            r = [0.0, 0.0, 0.0]
            r["xyz".index(e.rot_axis)] = e.rot_angle
            cube["rotation"] = [-r[0], -r[1], r[2]]
            cube["pivot"] = [-round(e.pivot[0], 3), round(e.pivot[1], 3), round(e.pivot[2], 3)]
        uv = {}
        for d in DIRS:
            u1, v1, u2, v2 = model.atlas.uv(e.faces[d])
            px1, py1, px2, py2 = [v * TEX / UNITS for v in (u1, v1, u2, v2)]
            if d in ("up", "down"):
                uv[swap[d]] = {"uv": [px2, py2], "uv_size": [-(px2 - px1), -(py2 - py1)]}
            else:
                uv[swap[d]] = {"uv": [px1, py1], "uv_size": [px2 - px1, py2 - py1]}
        cube["uv"] = uv
        cubes.append(cube)

    os.makedirs(os.path.join(pack_dir, "models"), exist_ok=True)
    os.makedirs(os.path.join(pack_dir, "textures"), exist_ok=True)
    write_png(os.path.join(pack_dir, "textures", "main.png"), atlas_img)
    geo = {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": f"geometry.{model.name}",
                "texture_width": TEX,
                "texture_height": TEX,
                "visible_bounds_width": 3,
                "visible_bounds_height": 3,
                "visible_bounds_offset": [0, 0.5, 0],
            },
            "bones": [{"name": "block", "pivot": [-8, 0, 8], "cubes": cubes}],
        }],
    }
    with open(os.path.join(pack_dir, "models", "main.json"), "w") as f:
        json.dump(geo, f, indent=1)
    ysm = {
        "spec": 2,
        "metadata": {"name": title, "license": {"type": "All Rights Reserved"}},
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
        json.dump(ysm, f, indent=1)
    return pack_dir


def emit_lineup_pack(models: Sequence[BlockModel], pack_dir: str, atlas_imgs: Sequence, title: str,
                     spacing: float = 22.0) -> str:
    """All blocks side by side inside ONE model => one render, one exact scale."""
    cubes = []
    swap = {"north": "north", "south": "south", "east": "west", "west": "east", "up": "up", "down": "down"}
    for bi, model in enumerate(models):
        off = (bi - (len(models) - 1) / 2) * spacing
        for e in model.elems:
            sx, sy, sz = e.size
            cube = {
                "origin": [-round(e.to[0] + off, 3), round(e.frm[1], 3), round(e.frm[2], 3)],
                "size": [round(sx, 3), round(sy, 3), round(sz, 3)],
            }
            if e.rot_axis:
                r = [0.0, 0.0, 0.0]
                r["xyz".index(e.rot_axis)] = e.rot_angle
                cube["rotation"] = [-r[0], -r[1], r[2]]
                cube["pivot"] = [-round(e.pivot[0] + off, 3), round(e.pivot[1], 3), round(e.pivot[2], 3)]
            uv = {}
            for d in DIRS:
                u1, v1, u2, v2 = model.atlas.uv(e.faces[d])
                px1, px2 = [v * TEX / UNITS + bi * TEX for v in (u1, u2)]
                py1, py2 = [v * TEX / UNITS for v in (v1, v2)]
                if d in ("up", "down"):
                    uv[swap[d]] = {"uv": [px2, py2], "uv_size": [-(px2 - px1), -(py2 - py1)]}
                else:
                    uv[swap[d]] = {"uv": [px1, py1], "uv_size": [px2 - px1, py2 - py1]}
            cube["uv"] = uv
            cubes.append(cube)

    os.makedirs(os.path.join(pack_dir, "models"), exist_ok=True)
    os.makedirs(os.path.join(pack_dir, "textures"), exist_ok=True)
    # lineup can never share one 32px texture; give each block its own island row
    # by compositing all three atlases into a 96x32 sheet
    merged = [[(20, 22, 26, 255) for _ in range(TEX * len(atlas_imgs))] for _ in range(TEX)]
    for i, img in enumerate(atlas_imgs):
        for y in range(TEX):
            for x in range(TEX):
                merged[y][i * TEX + x] = img[y][x]
    write_png(os.path.join(pack_dir, "textures", "main.png"), merged)

    geo = {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {"identifier": f"geometry.{title}", "texture_width": TEX * len(atlas_imgs),
                            "texture_height": TEX, "visible_bounds_width": 8,
                            "visible_bounds_height": 4, "visible_bounds_offset": [0, 0.5, 0]},
            "bones": [{"name": "block", "pivot": [-8, 0, 8], "cubes": cubes}],
        }],
    }
    with open(os.path.join(pack_dir, "models", "main.json"), "w") as f:
        json.dump(geo, f, indent=1)
    ysm = {
        "spec": 2,
        "metadata": {"name": title, "license": {"type": "All Rights Reserved"}},
        "files": {"player": {"model": {"main": "models/main.json", "arm": "models/arm.json"},
                             "texture": [{"uv": "textures/main.png"}],
                             "animation": {}, "animation_controllers": []}},
        "properties": {"height_scale": 0.7, "width_scale": 0.7,
                       "preview_animation": "idle", "default_texture": "main"},
    }
    with open(os.path.join(pack_dir, "ysm.json"), "w") as f:
        json.dump(ysm, f, indent=1)
    return pack_dir
