#!/usr/bin/env python3
"""The Corvus mill / facility blocks, authored element by element in 16^3 space.

Same rules as the power set (see pbgen.py):
    * front of every machine = the **north** face (-Z)
    * every element from/to inside 0..16 *and* every rotated corner inside 0..16
    * one axis of rotation per element, angle only 0 / +-22.5 / +-45 (vanilla gate)
    * texture = 32x32 atlas, 1 px per model unit on structural faces, flat swatches
      for trim; every glow part is a single flat 4x4 swatch

Multi-block machines are authored as separate models that each live in their own
0..16 box (that is how the engine sees them):

    ore_mill      (lower cell)      ore_mill_top   (upper cell)
    water_pump    (single cell)
    power_tower   (lower cell)  power_tower_mid (middle cell)  power_tower_top (upper cell)
    water_pipe_{straight,corner,tee,cross,cap}  (one cell each, one shared atlas)

Seam discipline for stacked models: a lower model never reaches y = 16 and an upper
model never starts at y = 0 (15.8 / 0.2 instead).  So no two parts of the assembled
machine ever put a face on the same plane (the stack cannot z-fight) and no face
needs a cullface it cannot honour.

Face-material discipline: an atlas island is only used on faces whose pixel aspect
matches the island (validated by tools/validate.py); everything else uses a flat
palette swatch, where stretching is invisible by construction.
"""
from __future__ import annotations

import math

from pbgen import PALETTE as P, BlockModel, Atlas, Painter, shade


# =========================================================== painter helpers
def bolt(p: Painter, x: int, y: int, c: str | None = None) -> None:
    c = c or P["steel3"]
    p.set(x, y, c)
    p.set(x + 1, y, shade(c, -40))
    p.set(x, y + 1, shade(c, -60))


def plate(p: Painter, base: str, edge: str, hi: str | None = None, bolts: bool = False) -> None:
    p.rect(0, 0, p.w, p.h, base)
    p.border(edge)
    if hi:
        p.hline(1, 1, p.w - 1, hi)
    if bolts and p.w > 4 and p.h > 4:
        for (x, y) in ((1, 2), (p.w - 3, 2), (1, p.h - 4), (p.w - 3, p.h - 4)):
            bolt(p, x, y)


def ribbed(p: Painter, dark: str, step: int = 3, pad: int = 1) -> None:
    for x in range(pad + 1, p.w - pad, step):
        p.vline(x, pad, p.h - pad, dark)


def hazard(p: Painter, a: str | None = None, b: str | None = None) -> None:
    """Diagonal caution stripes."""
    a = a or P["amber2"]
    b = b or P["hull0"]
    for y in range(p.h):
        for x in range(p.w):
            p.set(x, y, a if ((x + y) % 4) < 2 else b)
    p.border(shade(b, -8))


def grille(p: Painter, base: str, bar: str, dark: str) -> None:
    p.rect(0, 0, p.w, p.h, dark)
    for y in range(0, p.h, 2):
        p.hline(y, 0, p.w, bar)
        p.hline(y + 1, 0, p.w, base)
    p.border(shade(dark, 10))


def wear(p: Painter, col: str, n: int = 6, seed: int = 1) -> None:
    for i in range(n):
        x = (seed * 7 + i * 11) % max(1, p.w)
        y = (seed * 5 + i * 13) % max(1, p.h)
        p.set(x, y, col)


def span(c: float, sign: int, a: float, w: float):
    """(lo, hi) of a strip of width w starting at c + sign*a, growing outward."""
    if sign < 0:
        return c - a - w, c - a
    return c + a, c + a + w


# ---------------------------------------------------------------- primitives
def oct_prism(B: BlockModel, prefix: str, axis: str, lo: float, hi: float,
              side, small, up, down, a: float, r: float,
              c1: float = 8.0, c2: float = 8.0, corners: bool = True,
              corner_w: float | None = None, note: str = "") -> None:
    """Octagonal prism along `axis`, cross-section centred on (c1, c2).

    side  = material of the eight wide faces (core flanks + the outward face of
            each cardinal slab).  They all measure 2a x (hi-lo), so ONE island fits.
    small = material of the narrow shoulder faces, up/down = the two end faces.
    No rotation is involved, so the silhouette always stays inside the block.
    """
    ax = "xyz".index(axis)
    o1, o2 = [i for i in range(3) if i != ax]
    FACENAME = {(0, -1): "west", (0, 1): "east", (1, -1): "down", (1, 1): "up",
                (2, -1): "north", (2, 1): "south"}

    def mk(name, v1lo, v1hi, v2lo, v2hi, outward=None):
        f = [0.0, 0.0, 0.0]
        t = [0.0, 0.0, 0.0]
        f[ax], t[ax] = lo, hi
        f[o1], t[o1] = v1lo, v1hi
        f[o2], t[o2] = v2lo, v2hi
        faces = {"*": small}
        if outward:
            faces[FACENAME[outward]] = side
        B.box(name, tuple(f), tuple(t), faces, note=note)

    # core: its four flanks (perpendicular to the cross-section axes) are the wide ones
    core_out = [FACENAME[(o1, -1)], FACENAME[(o1, 1)],
                FACENAME[(o2, -1)], FACENAME[(o2, 1)]]
    f = [0.0, 0.0, 0.0]
    t = [0.0, 0.0, 0.0]
    f[ax], t[ax] = lo, hi
    f[o1], t[o1] = c1 - a, c1 + a
    f[o2], t[o2] = c2 - a, c2 + a
    core_faces = {"*": small, "up": up, "down": down}
    for d in core_out:
        core_faces[d] = side
    B.box(f"{prefix}_core", tuple(f), tuple(t), core_faces, note=note)

    # four cardinal slabs, each one face outward = "side"
    mk(f"{prefix}_n", c1 - a, c1 + a, c2 - r, c2 - a, (o2, -1))
    mk(f"{prefix}_s", c1 - a, c1 + a, c2 + a, c2 + r, (o2, 1))
    mk(f"{prefix}_w", c1 - r, c1 - a, c2 - a, c2 + a, (o1, -1))
    mk(f"{prefix}_e", c1 + a, c1 + r, c2 - a, c2 + a, (o1, 1))
    if corners:
        # the notch has to stay *inside* r, otherwise the silhouette is a square again
        w = corner_w if corner_w is not None else max(0.3, (r - a) * 0.55)
        for i, (s1, s2) in enumerate(((-1, -1), (1, -1), (-1, 1), (1, 1))):
            l1, h1 = span(c1, s1, a, w)
            l2, h2 = span(c2, s2, a, w)
            mk(f"{prefix}_c{i}", l1, h1, l2, h2)


def radial_box(cx: float, cz: float, base: str, r0: float, r1: float, h: float):
    """Footprint of an axis-aligned box pointing from the centre toward base (E|W|N|S)."""
    if base == "E":
        return cx + r0, cx + r1, cz - h, cz + h
    if base == "W":
        return cx - r1, cx - r0, cz - h, cz + h
    if base == "S":
        return cx - h, cx + h, cz + r0, cz + r1
    return cx - h, cx + h, cz - r1, cz - r0


def bin_tier(B: BlockModel, prefix: str, cx: float, cz: float, hx: float, hz: float,
             t: float, y0: float, y1: float, front_m, side_m, in_m, up_m, dn_m,
             back: bool = False, drop: float = 0.0, note: str = "") -> None:
    """Stepped wall ring of an open hopper: front wall + two side walls (+ optional back).

    `drop` lowers the front (north) wall so the load inside stays visible head-on.
    """
    B.box(f"{prefix}_w", (cx - hx, y0, cz - hz), (cx - hx + t, y1, cz + hz),
          {"*": in_m, "west": side_m, "up": up_m, "down": dn_m}, note=note)
    B.box(f"{prefix}_e", (cx + hx - t, y0, cz - hz), (cx + hx, y1, cz + hz),
          {"*": in_m, "east": side_m, "up": up_m, "down": dn_m}, note=note)
    B.box(f"{prefix}_n", (cx - hx + t, y0, cz - hz), (cx + hx - t, y1 - drop, cz - hz + t),
          {"*": in_m, "north": front_m, "up": up_m, "down": dn_m}, note=note)
    if back:
        B.box(f"{prefix}_s", (cx - hx + t, y0, cz + hz - t), (cx + hx - t, y1, cz + hz),
              {"*": in_m, "south": front_m, "up": up_m, "down": dn_m}, note=note)


def handwheel(B: BlockModel, prefix: str, cy: float, cz: float, R: float,
              rim_m, rim_hi, spoke_m, hub_m, x0: float, x1: float,
              outward: str = "west") -> None:
    """Valve handwheel on an x face; 8-block rim so it reads as a wheel."""
    outer = outward == "west"
    for k in range(8):
        ang = math.radians(k * 45.0)
        dy, dz = R * math.sin(ang), R * math.cos(ang)
        B.box(f"{prefix}_rim{k}", (x0, cy + dy - 0.62, cz + dz - 0.62),
              (x1, cy + dy + 0.62, cz + dz + 0.62),
              {"*": rim_m, "up": rim_hi}, note="handwheel rim segment")
    for nm, (y0_, y1_, z0_, z1_) in (
        ("v_up", (cy + 1.0, cy + R + 0.4, cz - 0.22, cz + 0.22)),
        ("v_dn", (cy - R - 0.4, cy - 1.0, cz - 0.22, cz + 0.22)),
        ("h_fw", (cy - 0.22, cy + 0.22, cz - R - 0.4, cz - 1.0)),
        ("h_bk", (cy - 0.22, cy + 0.22, cz + 1.0, cz + R + 0.4)),
    ):
        B.box(f"{prefix}_spoke_{nm}", (x0 + 0.12, y0_, z0_), (x1, y1_, z1_),
              {"*": spoke_m})
    B.box(f"{prefix}_hub", (x0 - (0.3 if outer else 0.0), cy - 0.62, cz - 0.62),
          (x1 + (0.0 if outer else 0.3), cy + 0.62, cz + 0.62),
          {"*": hub_m, "up": rim_hi})


def pipe_flange(B: BlockModel, prefix: str, axis: str, lo: float, hi: float,
                rim_m, face_m, bolt_m) -> None:
    """Bolt-on flange: octagonal collar + four bolt heads on the rim."""
    oct_prism(B, prefix, axis, lo, hi, face_m, rim_m, face_m, face_m, a=2.55, r=3.75,
              corner_w=0.28)
    ax = "xyz".index(axis)
    o1, o2 = [i for i in range(3) if i != ax]
    for k, (s1, s2) in enumerate(((-1, -1), (1, -1), (-1, 1), (1, 1))):
        p = [8.0, 8.0, 8.0]
        p[o1] = 8.0 + s1 * 2.55
        p[o2] = 8.0 + s2 * 2.55
        f = [float(v) for v in p]
        t = [float(v) for v in p]
        f[o1] -= 0.34
        t[o1] += 0.34
        f[o2] -= 0.34
        t[o2] += 0.34
        f[ax], t[ax] = lo, hi
        B.box(f"{prefix}_bolt{k}", tuple(f), tuple(t), {"*": bolt_m},
              note="flange bolt head")



# =========================================================== mill painters (shared)
def mill_plate(p: Painter):
    plate(p, P["hull3"], P["steel1"], P["steel2"], bolts=True)
    ribbed(p, shade(P["hull3"], -9), 4)
    p.hline(p.h - 2, 1, p.w - 1, P["hull1"])
    p.hline(p.h - 3, 1, p.w - 1, P["steel1"])


def mill_end(p: Painter):
    plate(p, P["hull4"], P["steel1"], P["steel2"])
    p.rect(1, 2, p.w - 1, p.h - 2, P["hull3"])
    p.border(P["hull1"])
    for y in range(3, p.h - 2, 3):
        p.hline(y, 1, p.w - 1, P["hull4"])
        p.hline(y + 1, 1, p.w - 1, shade(P["hull3"], -9))
    p.border(P["steel1"])
    wear(p, P["steel2"], 5, 2)


def mill_back(p: Painter):
    plate(p, P["hull3"], P["steel1"], P["steel2"], bolts=True)
    p.rect(2, 3, p.w - 2, p.h - 3, P["hull2"])
    p.border(P["hull0"])
    p.dither(2, 3, p.w - 2, p.h - 3, shade(P["hull2"], 6), 1)


def mill_deck(p: Painter):
    p.rect(0, 0, p.w, p.h, P["hull4"])
    p.border(P["steel1"])
    for y in range(2, p.h - 1, 3):
        p.hline(y, 1, p.w - 1, shade(P["hull4"], -10))
    for x in range(2, p.w - 2, 5):
        p.set(x, 1, P["steel2"])


def mill_plinth(p: Painter):
    p.rect(0, 0, p.w, p.h, P["hull3"])
    p.hline(0, 0, p.w, P["steel3"])
    p.hline(1, 0, p.w, P["hull4"])
    p.hline(p.h - 1, 0, p.w, P["hull0"])
    for x in range(2, p.w - 2, 4):
        p.set(x, p.h - 2, P["steel1"])


def mill_caution(p: Painter):
    hazard(p)


def mill_hopper(p: Painter):
    plate(p, P["hull4"], P["steel2"], P["steel3"])
    for y in range(1, p.h - 1, 2):
        p.hline(y, 1, p.w - 1, shade(P["hull4"], -14))
    for x in range(1, p.w - 1, 3):
        p.set(x, p.h - 1, P["ore1"])


def mill_grille(p: Painter):
    grille(p, P["hull3"], P["steel2"], P["hull0"])


def mill_intake(p: Painter):
    p.rect(0, 0, p.w, p.h, P["hull0"])
    p.border(shade(P["hull0"], 14))
    for x in range(1, p.w - 1, 2):
        p.vline(x, 1, p.h - 1, shade(P["hull0"], 6))


# =========================================================== deck with a seam pit
def deck_ring(B: BlockModel, prefix: str, x0: float, x1: float, z0: float, z1: float,
              y0: float, y1: float, pit_cx: float, pit_cz: float, R: float,
              seam_max: bool, cols: int, mats, note: str = "") -> None:
    """Deck slab that leaves a half-disc pit open toward the seam side.

    `seam_max` True  -> the pit lies at x >= pit_cx - R (west half of a machine)
    `seam_max` False -> the pit lies at x <= pit_cx + R (east half)
    The stair-stepped pit wall is then covered by `pit_rim`.
    """
    xa_all = pit_cx - R if seam_max else x0
    xb_all = x1 if seam_max else pit_cx + R
    if seam_max:
        B.box(f"{prefix}_body", (x0, y0, z0), (xa_all, y1, z1), mats, note=note)
    else:
        B.box(f"{prefix}_body", (xb_all, y0, z0), (x1, y1, z1), mats, note=note)
    step = (xb_all - xa_all) / cols
    for i in range(cols):
        a = xa_all + i * step
        b = min(a + step, xb_all)
        near = b if seam_max else a                     # column edge nearest the pit centre
        d = abs(near - pit_cx)
        if d >= R:
            B.box(f"{prefix}_c{i}", (a, y0, z0), (b, y1, z1), mats, note=note)
            continue
        ch = math.sqrt(max(0.0, R * R - d * d))
        if pit_cz - ch - z0 > 0.15:
            B.box(f"{prefix}_c{i}n", (a, y0, z0), (b, y1, pit_cz - ch), mats, note=note)
        if z1 - (pit_cz + ch) > 0.15:
            B.box(f"{prefix}_c{i}s", (a, y0, pit_cz + ch), (b, y1, z1), mats, note=note)


def pit_rim(B: BlockModel, prefix: str, pit_cx: float, pit_cz: float, R: float,
            y0: float, y1: float, mats, seam_max: bool, n: int = 24) -> None:
    """Ring of blocks along the half-circle pit edge: hides the deck's stair steps."""
    for k in range(n):
        if seam_max:
            ang = math.radians(90.0 + 180.0 * k / (n - 1))
        else:
            ang = math.radians(270.0 + 180.0 * k / (n - 1))
        bx = pit_cx + (R - 0.24) * math.cos(ang)
        bz = pit_cz + (R - 0.24) * math.sin(ang)
        hw = 0.52
        lo_x, hi_x = bx - hw, bx + hw
        if seam_max:
            hi_x = min(hi_x, 15.8)
        else:
            lo_x = max(lo_x, 0.2)
        B.box(f"{prefix}_{k}", (lo_x, y0, bz - hw), (hi_x, y1, bz + hw), mats,
              note="pit rim: covers the stepped deck edge")


def disc_half(B: BlockModel, prefix: str, pit_cx: float, pit_cz: float, R: float,
              y0: float, y1: float, mats, seam_max: bool, rows=4) -> None:
    """Half of the sorting disc, built as chord rows so the outer edge is round."""
    xa_all = pit_cx - R if seam_max else 0.2
    xb_all = 15.8 if seam_max else pit_cx + R
    step = (xb_all - xa_all) / rows
    for i in range(rows):
        a = xa_all + i * step
        b = min(a + step, xb_all)
        near = b if seam_max else a
        d = abs(near - pit_cx)
        ch = math.sqrt(max(0.0, R * R - d * d)) - 0.18
        if ch <= 0.2:
            continue
        B.box(f"{prefix}_r{i}", (a, y0, pit_cz - ch), (b, y1, pit_cz + ch), mats,
              note="sorting disc (half, meets its twin on the seam)")


# =========================================================== ore_mill (west / main half)
def _mill_atlas(name: str):
    """One 32x32 atlas per half; the two halves are separate models (see MANIFEST)."""
    at = Atlas(name)
    I = {
        "front": at.island("front_plate", 14, 10, mill_plate),   # 15.2 x 11.2 -> 1.357|1.4
        "end": at.island("end_plate", 10, 9, mill_end),          # 13.4 x 12.3 -> 1.09|1.11
        "deck": at.island("deck_plate", 7, 10, mill_deck),       # 9.8 x 13.4 -> 0.73|0.70
        "plinth": at.island("plinth_face", 8, 1, mill_plinth),   # 15.2 x 1.9 -> 8.0|8
        "caution": at.island("caution_band", 12, 1, mill_caution),  # 14.4 x 1.15 -> 12.5|12
        "hop0": at.island("hopper_t0", 3, 2, mill_hopper),       # 3.2 x 2.0 -> 1.6|1.5
        "hop1": at.island("hopper_t1", 7, 2, mill_hopper),       # 5.1 x 1.4 -> 3.6|3.5
        "hop2": at.island("hopper_t2", 6, 1, mill_hopper),       # 7.0 x 1.2 -> 5.8|6
        "grille": at.island("vent_grille", 6, 3, mill_grille),   # 4.4 x 2.6 -> 1.7|2.0
        "intake": at.island("intake_mouth", 4, 4, mill_intake),  # 4.0 x 4.0 -> 1.0
    }
    S = {k: at.swatch(k) for k in ("hull0", "hull1", "hull2", "hull3", "hull4",
                                   "steel1", "steel2", "steel3", "steel4",
                                   "amber0", "amber1", "amber2", "ember", "mag1",
                                   "ore0", "ore1", "ore2")}
    return at, I, S


def _mill_base(B: BlockModel, S, I, x0: float, x1: float, note: str = "") -> None:
    """Feet / skid rails / plinth shared by both halves."""
    for i, (fx, fz) in enumerate(((0.9, 0.9), (11.8, 0.9), (0.9, 11.8), (11.8, 11.8))):
        B.box(f"foot_{i}", (fx, 0.0, fz), (fx + 3.0, 1.0, fz + 3.0),
              {"*": S["hull1"], "up": S["hull3"], "down": S["steel1"]},
              note="corner pad; carries the machine off the floor")
        B.box(f"foot_{i}_bolt", (fx + 1.2, 1.0, fz + 1.2), (fx + 1.7, 1.28, fz + 1.7),
              {"*": S["steel3"], "up": S["steel3"]})
    for nm, rx in (("rail_w", 2.2), ("rail_e", 11.8)):
        B.box(nm, (rx, 1.0, 1.5), (rx + 1.0, 1.8, 14.6),
              {"*": S["steel1"], "up": S["steel2"], "down": S["hull0"]})
    B.box("plinth", (x0, 1.0, 0.6), (x1, 2.9, 15.0),
          {"*": S["hull3"], "up": S["hull2"], "down": S["hull0"], "north": I["plinth"],
           "south": I["plinth"], "east": S["hull4"], "west": S["hull4"]})
    B.box("plinth_caution", (x0 + 0.4, 1.15, 0.46), (x1 - 0.4, 2.3, 0.64),
          {"*": S["hull1"], "north": I["caution"], "up": S["steel1"]},
          note="caution stripe band along the plinth edge")


def build_ore_mill() -> BlockModel:
    """West half of the horizontal two-cell ore mill (main side, seam at x = 15.8)."""
    at, I, S = _mill_atlas("ore_mill")
    B = BlockModel("ore_mill", at)
    X0, X1 = 0.6, 15.8
    CX, CZ, R = 16.0, 8.0, 5.6

    _mill_base(B, S, I, X0, X1)

    # ---------------- housing -------------------------------------------
    B.box("housing", (X0, 2.9, 1.3), (X1, 13.3, 14.7),
          {"north": I["front"], "south": I["front"], "east": S["hull1"],
           "west": I["end"], "up": S["hull2"], "down": S["hull0"]})
    for i, (px, pz) in enumerate(((X0 + 0.05, 1.35), (15.0, 1.35),
                                  (X0 + 0.05, 13.85), (15.0, 13.85))):
        B.box(f"pillar_{i}", (px, 2.9, pz), (px + 0.8, 15.2, pz + 0.8),
              {"*": S["steel2"], "up": S["steel3"], "down": S["hull1"]},
              note="frame pillar")

    # ---------------- seam flange (dark joint line with the side half) ---
    B.box("seam_flange_n", (14.9, 3.2, 1.15), (15.95, 14.5, 1.48),
          {"*": S["steel2"], "north": S["steel3"], "up": S["steel3"], "down": S["hull1"]},
          note="seam flange: pairs with ore_mill_side, leaves a 0.1 dark joint")
    B.box("seam_flange_s", (14.9, 3.2, 14.52), (15.95, 14.5, 14.85),
          {"*": S["steel2"], "south": S["steel3"], "up": S["steel3"], "down": S["hull1"]})

    # ---------------- flat deck with the recessed sorting pit ------------
    deck_m = {"*": S["hull4"], "up": S["steel1"], "down": S["hull2"],
              "north": S["steel2"], "south": S["steel2"]}
    B.box("deck_body", (X0, 13.3, 1.3), (10.4, 15.2, 14.7),
          {"*": S["hull4"], "up": I["deck"], "down": S["hull2"],
           "north": S["steel2"], "south": S["steel2"]},
          note="deck west of the pit (drone landing area)")
    deck_ring(B, "deck", 10.4, X1, 1.3, 14.7, 13.3, 15.2, CX, CZ, R,
              seam_max=True, cols=5, mats=deck_m, note="deck ring around the pit")
    pit_rim(B, "pitrim", CX, CZ, R, 13.3, 15.20,
            {"*": S["steel2"], "up": S["steel3"], "down": S["hull2"]},
            seam_max=True, n=26)
    B.box("deck_trim_n", (X0, 15.06, 1.3), (X1, 15.20, 1.75),
          {"*": S["steel1"], "up": S["steel3"], "north": S["steel2"]},
          note="flush deck inlay (keeps the landing deck flat)")
    B.box("deck_trim_s", (X0, 15.06, 14.25), (X1, 15.20, 14.7),
          {"*": S["steel1"], "up": S["steel3"], "south": S["steel2"]},
          note="flush deck inlay (keeps the landing deck flat)")

    # ---------------- sorter disc: west half + 2 swept vanes -------------
    disc_m = {"*": S["steel2"], "up": S["steel3"], "down": S["hull2"]}
    disc_half(B, "disc", CX, CZ, 4.7, 13.42, 14.10, disc_m, seam_max=True, rows=4)
    hub_m = {"*": S["steel1"], "up": S["steel3"], "down": S["hull2"]}
    B.box("disc_hub", (13.95, 14.10, 7.1), (15.8, 14.95, 8.9), hub_m,
          note="disc hub (its twin sits on the side half)")
    vane_m = {"*": S["steel2"], "up": S["steel3"], "down": S["hull2"]}
    for k, (rot, z0, z1) in enumerate(((45.0, 7.70, 8.30), (-45.0, 7.70, 8.30))):
        B.box(f"vane_{k}", (11.6, 14.14, z0), (14.4, 14.94, z1), vane_m,
              rot=("y", rot), pivot=(CX, 14.72, CZ),
              note="sorting vane, 45 deg box rotation about the disc centre")
        B.box(f"vane_{k}_tip", (12.75, 14.14, z0 + (0.5 if rot > 0 else -0.5)),
              (14.4, 14.86, z1 + (0.5 if rot > 0 else -0.5)), vane_m,
              rot=("y", rot), pivot=(CX, 14.72, CZ),
              note="vane tip, stepped so the blade reads swept")

    # ---------------- front feed funnel --------------------------------
    tiers = ((2.10, 0.95, 4.4, 7.6, 0.50, "hop0", 1.0),
             (3.10, 1.15, 7.6, 10.4, 0.55, "hop1", 1.2),
             (4.10, 1.30, 10.4, 13.0, 0.60, "hop2", 1.4))
    for i, (hx, hz, y0, y1, t, key, drop) in enumerate(tiers):
        bin_tier(B, f"feed_t{i}", 8.0, 1.70, hx, hz, t, y0, y1,
                 front_m=I[key], side_m=S["hull4"], in_m=S["hull3"],
                 up_m=S["steel2"], dn_m=S["hull0"], drop=drop)
        zf, yt = 1.70 - hz, y1 - drop
        B.box(f"feed_cap{i}_n", (8.0 - hx - 0.08, yt - 0.30, zf - 0.10),
              (8.0 + hx + 0.08, yt, zf + 0.20),
              {"*": S["steel3"], "up": S["steel3"], "north": S["steel3"], "down": S["hull1"]})
        for nm, xa, xb in (("w", 8.0 - hx - 0.10, 8.0 - hx + 0.14),
                           ("e", 8.0 + hx - 0.14, 8.0 + hx + 0.10)):
            B.box(f"feed_cap{i}_{nm}", (xa, y1 - 0.30, 1.70 - hz), (xb, y1, 1.70 + hz),
                  {"*": S["steel3"], "up": S["steel3"],
                   "west" if nm == "w" else "east": S["steel3"], "down": S["hull1"]})
    B.box("intake_mouth", (5.7, 3.9, 1.22), (10.3, 8.1, 1.32),
          {"*": S["hull0"], "north": I["intake"], "up": S["hull0"]},
          note="dark inlet where the ore disappears into the mill")
    for nm, f, t in (
        ("intake_l", (5.35, 3.7, 1.18), (5.75, 8.3, 1.32)),
        ("intake_r", (10.25, 3.7, 1.18), (10.65, 8.3, 1.32)),
        ("intake_t", (5.75, 7.9, 1.18), (10.25, 8.3, 1.32)),
        ("intake_b", (5.75, 3.7, 1.18), (10.25, 4.1, 1.32)),
    ):
        B.box(nm, f, t, {"*": S["steel1"], "north": S["steel2"], "up": S["steel3"]})
    B.box("intake_glow", (6.2, 4.5, 1.12), (9.8, 5.2, 1.22),
          {"*": S["amber1"], "north": S["amber1"]},
          note="GLOW hot mill mouth under the incoming ore")
    for i, (ox, oy, oz, s) in enumerate((
            (6.55, 11.10, 1.25, 0.95), (7.55, 11.50, 1.75, 0.85),
            (8.55, 11.20, 1.20, 1.00), (9.55, 11.55, 1.70, 0.80),
            (7.05, 12.30, 1.55, 0.75), (8.95, 12.40, 1.45, 0.70),
            (7.85, 12.00, 0.72, 0.80), (9.30, 11.90, 0.60, 0.70),
            (7.10, 4.30, 1.20, 0.85), (8.60, 4.40, 1.35, 0.80))):
        B.box(f"mouth_ore_{i}", (ox, oy, oz), (ox + s, oy + s * 0.8, oz + s),
              {"*": S["ore0"], "up": S["ore1"], "north": S["ore2"]},
              note="ore chunk")

    # ---------------- amber inspection window (front face, west strip) ----
    for nm, f, t in (
        ("win_frame_t", (0.85, 12.05, 1.16), (3.55, 12.55, 1.36)),
        ("win_frame_b", (0.85, 8.75, 1.16), (3.55, 9.25, 1.36)),
        ("win_frame_w", (0.85, 9.25, 1.16), (1.35, 12.05, 1.36)),
        ("win_frame_e", (3.05, 9.25, 1.16), (3.55, 12.05, 1.36)),
    ):
        B.box(nm, f, t, {"*": S["steel1"], "north": S["steel2"], "up": S["steel3"]})
    B.box("win_glass", (1.35, 9.25, 1.14), (3.05, 12.05, 1.26),
          {"*": S["amber1"], "north": S["amber1"]},
          note="GLOW amber inspection window (pure 4x4 swatch)")
    for k, mx in enumerate((1.92, 2.49)):
        B.box(f"win_mullion_{k}", (mx, 9.25, 1.10), (mx + 0.22, 12.05, 1.24),
              {"*": S["steel1"], "north": S["steel2"]})

    # ---------------- handwheel (main-side end wall) --------------------
    B.box("end_plate", (0.46, 9.4, 4.2), (0.60, 12.4, 7.6),
          {"*": S["steel2"], "west": S["steel3"], "up": S["steel3"]},
          note="riveted data plate on the main end wall")
    for k, (py, pz) in enumerate(((9.6, 4.4), (12.0, 7.2))):
        B.box(f"end_plate_bolt_{k}", (0.40, py, pz), (0.48, py + 0.3, pz + 0.3),
              {"*": S["steel4"], "west": S["steel4"]})
    B.box("wheel_plate", (0.58, 3.6, 8.3), (0.74, 8.9, 13.6),
          {"*": S["hull1"], "west": S["steel1"]}, note="handwheel mounting plate")
    handwheel(B, "wheel", 6.1, 10.9, 2.2, S["steel2"], S["steel3"], S["steel1"],
              S["steel3"], x0=0.32, x1=0.70, outward="west")

    # ---------------- rear outfeed tray (concentrate) --------------------
    B.box("tray_chute", (3.4, 4.4, 14.62), (12.6, 6.6, 14.74),
          {"*": S["hull0"], "south": S["hull0"], "up": S["hull0"]},
          note="outfeed slot on the back face")
    for nm, f, t in (
        ("tray_frame_l", (3.1, 4.2, 14.60), (3.7, 6.9, 14.76)),
        ("tray_frame_r", (12.3, 4.2, 14.60), (12.9, 6.9, 14.76)),
        ("tray_frame_t", (3.7, 6.5, 14.60), (12.3, 6.9, 14.76)),
    ):
        B.box(nm, f, t, {"*": S["steel1"], "south": S["steel2"], "up": S["steel3"]})
    B.box("tray_floor", (2.9, 3.4, 14.7), (13.1, 4.4, 15.8),
          {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"], "south": S["steel3"]},
          note="outfeed tray (concentrate)")
    B.box("tray_lip", (2.9, 4.4, 15.35), (13.1, 5.0, 15.8),
          {"*": S["steel2"], "up": S["steel3"], "south": S["steel3"]})
    for i, (ox, oz) in enumerate(((4.2, 15.0), (6.6, 15.25), (9.4, 15.1))):
        B.box(f"tray_ore_{i}", (ox, 4.4, oz), (ox + 1.1, 5.1, oz + 0.55),
              {"*": S["ore0"], "up": S["ore2"], "south": S["ore1"]},
              note="sorted ore on the tray")

    # ---------------- status --------------------------------------------
    B.box("led_amber", (12.6, 12.2, 1.28), (13.3, 12.7, 1.36),
          {"*": S["amber2"], "north": S["amber2"]}, note="GLOW status lamp")
    B.box("led_mag", (11.7, 12.2, 1.28), (12.4, 12.7, 1.36),
          {"*": S["mag1"], "north": S["mag1"]}, note="GLOW magenta accent")
    return B


# =========================================================== ore_mill_side (east half)
def build_ore_mill_side() -> BlockModel:
    """East half of the horizontal two-cell ore mill (aux side, seam at x = 0.2)."""
    at, I, S = _mill_atlas("ore_mill_side")
    B = BlockModel("ore_mill_side", at)
    X0, X1 = 0.2, 15.4
    CX, CZ, R = -0.2, 8.0, 5.6

    _mill_base(B, S, I, X0, X1)

    # ---------------- housing -------------------------------------------
    B.box("housing", (X0, 2.9, 1.3), (X1, 13.3, 14.7),
          {"north": I["front"], "south": I["front"], "east": I["end"],
           "west": S["hull1"], "up": S["hull2"], "down": S["hull0"]})
    for i, (px, pz) in enumerate(((X0 + 0.05, 1.35), (14.6, 1.35),
                                  (X0 + 0.05, 13.85), (14.6, 13.85))):
        B.box(f"pillar_{i}", (px, 2.9, pz), (px + 0.8, 15.2, pz + 0.8),
              {"*": S["steel2"], "up": S["steel3"], "down": S["hull1"]},
              note="frame pillar")

    # ---------------- seam flange ----------------------------------------
    B.box("seam_flange_n", (0.05, 3.2, 1.15), (1.1, 14.5, 1.48),
          {"*": S["steel2"], "north": S["steel3"], "up": S["steel3"], "down": S["hull1"]},
          note="seam flange: pairs with ore_mill, leaves a 0.1 dark joint")
    B.box("seam_flange_s", (0.05, 3.2, 14.52), (1.1, 14.5, 14.85),
          {"*": S["steel2"], "south": S["steel3"], "up": S["steel3"], "down": S["hull1"]})

    # ---------------- deck + disc east half ------------------------------
    deck_m = {"*": S["hull4"], "up": S["steel1"], "down": S["hull2"],
              "north": S["steel2"], "south": S["steel2"]}
    deck_ring(B, "deck", X0, 5.4, 1.3, 14.7, 13.3, 15.2, CX, CZ, R,
              seam_max=False, cols=5, mats=deck_m, note="deck ring around the pit")
    B.box("deck_body", (5.4, 13.3, 1.3), (X1, 15.2, 14.7),
          {"*": S["hull4"], "up": I["deck"], "down": S["hull2"],
           "north": S["steel2"], "south": S["steel2"]},
          note="deck east of the pit (drone landing area)")
    pit_rim(B, "pitrim", CX, CZ, R, 13.3, 15.20,
            {"*": S["steel2"], "up": S["steel3"], "down": S["hull2"]},
            seam_max=False, n=26)
    B.box("deck_trim_n", (X0, 15.06, 1.3), (X1, 15.20, 1.75),
          {"*": S["steel1"], "up": S["steel3"], "north": S["steel2"]},
          note="flush deck inlay (keeps the landing deck flat)")
    B.box("deck_trim_s", (X0, 15.06, 14.25), (X1, 15.20, 14.7),
          {"*": S["steel1"], "up": S["steel3"], "south": S["steel2"]},
          note="flush deck inlay (keeps the landing deck flat)")
    disc_m = {"*": S["steel2"], "up": S["steel3"], "down": S["hull2"]}
    disc_half(B, "disc", CX, CZ, 4.7, 13.42, 14.10, disc_m, seam_max=False, rows=4)
    B.box("disc_hub", (0.2, 14.10, 7.1), (2.05, 14.95, 8.9),
          {"*": S["steel1"], "up": S["steel3"], "down": S["hull2"]},
          note="disc hub (its twin sits on the main half)")
    vane_m = {"*": S["steel2"], "up": S["steel3"], "down": S["hull2"]}
    for k, (rot, dz) in enumerate(((-45.0, 0.5), (45.0, -0.5))):
        B.box(f"vane_{k}", (1.6, 14.14, 7.70), (4.4, 14.94, 8.30), vane_m,
              rot=("y", rot), pivot=(CX, 14.72, CZ),
              note="sorting vane, 45 deg box rotation about the disc centre")
        B.box(f"vane_{k}_tip", (1.6, 14.14, 7.70 + dz), (3.25, 14.86, 8.30 + dz), vane_m,
              rot=("y", rot), pivot=(CX, 14.72, CZ),
              note="vane tip, stepped so the blade reads swept")

    # ---------------- front: vent bank -----------------------------------
    B.box("vent_plate", (3.2, 6.0, 1.22), (9.6, 9.6, 1.34),
          {"*": S["hull1"], "north": I["grille"], "up": S["steel1"]},
          note="cooling vent bank on the front face")
    for nm, f, t in (
        ("vent_frame_t", (3.0, 9.4, 1.20), (9.8, 9.75, 1.36)),
        ("vent_frame_b", (3.0, 5.85, 1.20), (9.8, 6.2, 1.36)),
    ):
        B.box(nm, f, t, {"*": S["steel1"], "north": S["steel2"], "up": S["steel3"]})
    B.box("front_hazard", (10.4, 6.0, 1.22), (13.6, 9.6, 1.32),
          {"*": S["hull1"], "north": S["steel1"]},
          note="blank plate reserved for a hazard decal")
    B.box("front_plate", (3.0, 11.0, 1.22), (13.6, 12.4, 1.34),
          {"*": S["steel2"], "north": S["steel3"], "up": S["steel2"]},
          note="riveted data plate")
    for k, (px, py) in enumerate(((3.3, 11.2), (13.0, 12.0))):
        B.box(f"front_plate_bolt_{k}", (px, py, 1.18), (px + 0.3, py + 0.3, 1.26),
              {"*": S["steel4"], "north": S["steel4"]})

    # ---------------- end wall: service door + exhaust stack -------------
    B.box("door_plate", (14.82, 3.6, 4.2), (15.46, 11.4, 11.8),
          {"*": S["hull4"], "east": S["hull3"], "up": S["steel2"], "down": S["hull1"]},
          note="bolted service door on the aux end wall")
    B.box("door_inset", (15.40, 4.2, 4.9), (15.52, 10.8, 11.1),
          {"*": S["hull3"], "east": S["hull4"], "up": S["steel1"]})
    for k, hz in enumerate((4.5, 10.9)):
        B.box(f"door_hinge_{k}", (15.40, 4.0, hz), (15.62, 5.0, hz + 1.0),
              {"*": S["steel2"], "east": S["steel3"], "up": S["steel3"]})
    B.box("door_handle", (15.46, 7.0, 8.4), (15.68, 8.2, 8.9),
          {"*": S["steel2"], "east": S["steel3"], "up": S["steel3"]})
    B.box("door_lock", (15.46, 8.6, 7.8), (15.62, 9.6, 8.4),
          {"*": S["mag1"], "east": S["mag1"]}, note="GLOW magenta door lock")
    B.box("door_window", (15.38, 9.2, 5.4), (15.50, 10.4, 7.0),
          {"*": S["hull0"], "east": S["hull1"]}, note="small dark porthole")

    B.box("duct", (11.6, 10.6, 13.9), (13.4, 12.2, 15.2),
          {"*": S["hull4"], "up": S["steel1"], "down": S["hull1"], "south": S["steel2"]},
          note="exhaust duct leaving the back face")
    B.box("stack", (11.7, 4.8, 14.7), (13.3, 15.55, 15.9),
          {"*": S["hull4"], "north": S["steel1"], "up": S["steel1"], "down": S["hull1"],
           "south": S["hull3"]}, note="exhaust stack (behind the deck, top stays flat)")
    for k, fy in enumerate((6.2, 13.2)):
        B.box(f"stack_flange_{k}", (11.5, fy, 14.55), (13.5, fy + 0.6, 16.0 - 0.05),
              {"*": S["steel2"], "up": S["steel3"], "down": S["hull2"]})
    B.box("stack_cap", (11.5, 15.55, 14.55), (13.5, 15.8, 16.0 - 0.05),
          {"*": S["steel1"], "up": S["steel2"], "down": S["hull2"]})
    B.box("stack_bore", (12.1, 15.4, 14.95), (12.9, 15.79, 15.55),
          {"*": S["hull0"], "up": S["hull0"]}, note="dark bore")

    # ---------------- rear outfeed tray (tailings) ------------------------
    B.box("tray_chute", (2.4, 4.4, 14.62), (10.6, 6.6, 14.74),
          {"*": S["hull0"], "south": S["hull0"], "up": S["hull0"]},
          note="outfeed slot on the back face")
    for nm, f, t in (
        ("tray_frame_l", (2.1, 4.2, 14.60), (2.7, 6.9, 14.76)),
        ("tray_frame_r", (10.3, 4.2, 14.60), (10.9, 6.9, 14.76)),
        ("tray_frame_t", (2.7, 6.5, 14.60), (10.3, 6.9, 14.76)),
    ):
        B.box(nm, f, t, {"*": S["steel1"], "south": S["steel2"], "up": S["steel3"]})
    B.box("tray_floor", (1.9, 3.4, 14.7), (11.1, 4.4, 15.8),
          {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"], "south": S["steel3"]},
          note="outfeed tray (tailings)")
    B.box("tray_lip", (1.9, 4.4, 15.35), (11.1, 5.0, 15.8),
          {"*": S["steel2"], "up": S["steel3"], "south": S["steel3"]})
    for i, (ox, oz) in enumerate(((3.0, 15.0), (5.4, 15.2), (8.2, 15.05))):
        B.box(f"tray_ore_{i}", (ox, 4.4, oz), (ox + 1.0, 5.05, oz + 0.55),
              {"*": S["ore0"], "up": S["ore1"], "south": S["ore2"]},
              note="tailings on the tray")

    # ---------------- deck furniture -------------------------------------
    for k, (vx, vz) in enumerate(((7.4, 3.0), (7.4, 11.6))):
        B.box(f"deck_vent_{k}", (vx, 15.08, vz), (vx + 3.4, 15.2, vz + 2.2),
              {"*": S["hull0"], "up": S["hull0"]}, note="recessed deck vent (flush)")
    B.box("led_amber", (12.4, 12.4, 1.26), (13.1, 12.9, 1.36),
          {"*": S["amber2"], "north": S["amber2"]}, note="GLOW status lamp")
    return B


# =========================================================== water_pump
def build_water_pump() -> BlockModel:
    at = Atlas("water_pump")
    B = BlockModel("water_pump", at)

    def base(p: Painter):
        p.rect(0, 0, p.w, p.h, P["hull3"])
        p.hline(0, 0, p.w, P["steel3"])
        p.hline(1, 0, p.w, P["hull4"])
        p.hline(p.h - 1, 0, p.w, P["hull0"])
        for x in range(2, p.w - 2, 4):
            p.set(x, p.h - 2, P["steel1"])

    def barrel(p: Painter):
        plate(p, P["hull4"], P["steel1"], P["steel2"])
        for y in range(2, p.h - 1, 3):
            p.hline(y, 1, p.w - 1, P["hull3"])
            p.hline(y + 1, 1, p.w - 1, shade(P["hull4"], 10))
        for x in range(1, p.w - 1, 3):
            p.set(x, 1, P["aqua1"])
        p.border(P["steel1"])

    def deck(p: Painter):
        p.rect(0, 0, p.w, p.h, P["hull4"])
        p.border(P["steel1"])
        for r in range(2, p.h - 2, 2):
            p.hline(r, 2, p.w - 2, P["steel1"])

    def grille_(p: Painter):
        grille(p, P["hull2"], P["steel1"], P["hull0"])

    def dial(p: Painter):
        p.rect(0, 0, p.w, p.h, P["face"])
        p.border(P["hull1"])
        for a in range(12):
            ang = math.radians(a * 30)
            x = int(round(p.w / 2 - 0.5 + math.cos(ang) * (p.w / 2 - 1.2)))
            y = int(round(p.h / 2 - 0.5 + math.sin(ang) * (p.h / 2 - 1.2)))
            p.set(x, y, P["hull1"])
        p.set(p.w // 2, p.h // 2, P["mag1"])

    I = {
        "base": at.island("base_face", 9, 1, base),            # 13.2 x 1.45 -> 9.1|9
        "barrel": at.island("barrel_plate", 5, 7, barrel),      # 5.2 x 6.8 -> 0.765|0.714
        "deck": at.island("deck_plate", 6, 6, deck),            # 5.2 x 5.2 -> 1.0
        "grille": at.island("vent_grille", 6, 3, grille_),      # 3.7 x 2.0 -> 1.85|2.0
        "dial": at.island("dial_face", 8, 8, dial),             # 2.2 x 2.2 -> 1.0
    }
    S = {k: at.swatch(k) for k in ("hull0", "hull1", "hull2", "hull3", "hull4",
                                   "steel1", "steel2", "steel3", "steel4",
                                   "aqua0", "aqua1", "aqua2", "aqua3",
                                   "amber2", "mag1", "face")}

    # ---------------- base ----------------------------------------------
    for i, (fx, fz) in enumerate(((1.0, 1.0), (12.4, 1.0), (1.0, 12.4), (12.4, 12.4))):
        B.box(f"foot_{i}", (fx, 0.0, fz), (fx + 2.6, 0.55, fz + 2.6),
              {"*": S["hull1"], "up": S["hull2"], "down": S["steel1"]})
    B.box("base", (1.4, 0.55, 1.4), (14.6, 2.0, 14.6),
          {"*": S["hull2"], "up": S["hull2"], "down": S["steel1"], "north": I["base"],
           "south": I["base"], "east": I["base"], "west": I["base"]})
    B.box("base_strip", (1.6, 0.85, 1.22), (14.4, 1.30, 1.44),
          {"*": S["hull2"], "north": S["aqua0"], "up": S["steel1"]},
          note="coolant drip strip along the plinth")
    B.box("deck", (2.4, 2.0, 2.4), (13.6, 2.6, 13.6),
          {"*": S["steel1"], "up": I["deck"], "down": S["hull1"]})
    for i, (px, pz) in enumerate(((2.6, 2.6), (12.6, 2.6), (2.6, 12.6), (12.6, 12.6))):
        B.box(f"deck_bolt_{i}", (px, 2.6, pz), (px + 0.7, 2.9, pz + 0.7),
              {"*": S["steel3"], "up": S["steel3"]})

    # ---------------- pump body (octagonal volute) -----------------------
    oct_prism(B, "barrel", "y", 2.6, 9.4, side=I["barrel"], small=S["hull2"],
              up=S["hull2"], down=S["hull1"], a=2.6, r=5.4,
              note="pump volute casing")
    oct_prism(B, "barrel_top", "y", 9.4, 10.2, side=S["steel1"], small=S["hull3"],
              up=I["deck"], down=S["hull1"], a=2.0, r=4.6,
              note="casing shoulder")
    B.box("barrel_band", (5.6, 5.4, 2.55), (10.4, 6.0, 2.95),
          {"*": S["steel1"], "north": S["steel2"], "up": S["steel3"]})
    # cast ribs: two octagonal hoops, 0.1 proud of the casing surface
    for r, (ry0, ry1) in enumerate(((3.4, 4.1), (8.0, 8.7))):
        for k in range(12):
            ang = math.radians(k * 30.0)
            bx = 8.0 + 5.15 * math.cos(ang)
            bz = 8.0 + 5.15 * math.sin(ang)
            B.box(f"hoop{r}_{k}", (bx - 0.95, ry0, bz - 0.95), (bx + 0.95, ry1, bz + 0.95),
                  {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"]},
                  note="cast rib hoop around the volute")
    for i, (sx, sz) in enumerate(((-1, -1), (1, -1), (-1, 1), (1, 1))):
        lx, hx_ = span(8.0, sx, 4.4, 0.9)
        lz, hz_ = span(8.0, sz, 4.4, 0.9)
        B.box(f"hoop_web{i}", (lx, 3.4, lz), (hx_, 8.7, hz_),
              {"*": S["hull3"], "up": S["steel1"]}, note="rib web between the hoops")

    # ---------------- inlet flanges (both sides) -------------------------
    pipe_flange(B, "inlet_w", "x", 2.15, 2.78, S["steel1"], S["steel3"], S["steel4"])
    pipe_flange(B, "inlet_e", "x", 13.22, 13.85, S["steel1"], S["steel3"], S["steel4"])
    B.box("inlet_bore_w", (2.10, 6.4, 6.4), (2.80, 9.6, 9.6),
          {"*": S["hull0"], "west": S["aqua0"]}, note="suction bore")
    B.box("inlet_bore_e", (13.20, 6.4, 6.4), (13.90, 9.6, 9.6),
          {"*": S["hull0"], "east": S["aqua0"]})
    B.box("inlet_glass_w", (2.02, 7.3, 7.3), (2.14, 8.7, 8.7),
          {"*": S["aqua2"], "west": S["aqua2"]}, note="GLOW coolant sight in the bore")
    B.box("inlet_glass_e", (13.86, 7.3, 7.3), (13.98, 8.7, 8.7),
          {"*": S["aqua2"], "east": S["aqua2"]}, note="GLOW coolant sight in the bore")

    # ---------------- water level sight glass (front) --------------------
    B.box("glass_slot", (7.2, 3.6, 2.44), (8.8, 9.0, 2.58),
          {"*": S["aqua2"], "north": S["aqua2"]},
          note="GLOW cyan water level window (pure 4x4 swatch)")
    for nm, f, t in (
        ("glass_frame_w", (6.90, 3.4, 2.42), (7.20, 9.2, 2.62)),
        ("glass_frame_e", (8.80, 3.4, 2.42), (9.10, 9.2, 2.62)),
        ("glass_frame_t", (7.20, 8.9, 2.42), (8.80, 9.2, 2.62)),
        ("glass_frame_b", (7.20, 3.4, 2.42), (8.80, 3.6, 2.62)),
    ):
        B.box(nm, f, t, {"*": S["steel1"], "north": S["steel2"], "up": S["steel3"]})
    for k, ty in enumerate((5.2, 6.6, 8.0)):
        B.box(f"glass_tick_{k}", (7.2, ty, 2.40), (8.8, ty + 0.2, 2.52),
              {"*": S["steel2"], "north": S["steel3"]})

    # ---------------- pressure dial --------------------------------------
    B.box("dial_bezel", (4.3, 6.2, 2.52), (6.9, 8.8, 2.72),
          {"*": S["steel1"], "north": S["steel2"], "up": S["steel3"]})
    B.box("dial_face", (4.5, 6.4, 2.44), (6.7, 8.6, 2.54),
          {"*": S["hull1"], "north": I["dial"]}, note="painted pressure dial")
    B.box("dial_needle", (5.5, 7.3, 2.38), (6.1, 7.55, 2.46),
          {"*": S["mag1"], "north": S["mag1"]})
    B.box("dial_pipe", (5.4, 8.8, 2.54), (5.9, 9.6, 2.92),
          {"*": S["steel1"], "north": S["steel2"], "up": S["steel2"]})

    # ---------------- outlet stack (top) --------------------------------
    B.box("outlet_neck", (6.5, 10.0, 6.5), (9.5, 12.2, 9.5),
          {"*": S["hull4"], "north": S["hull3"], "up": S["steel1"], "down": S["hull1"]},
          note="vertical discharge neck")
    oct_prism(B, "outlet_flange", "y", 12.2, 13.0, side=S["steel2"], small=S["hull3"],
              up=S["steel3"], down=S["hull2"], a=1.7, r=3.2)
    B.box("outlet_bore", (6.9, 12.55, 6.9), (9.1, 13.02, 9.1),
          {"*": S["aqua0"], "up": S["aqua0"]},
          note="dark bore with coolant tint at the discharge mouth")
    B.box("outlet_glow", (7.5, 12.4, 7.5), (8.5, 12.58, 8.5),
          {"*": S["aqua2"], "up": S["aqua2"]}, note="GLOW coolant at the mouth")

    # ---------------- motor hump on the shoulder ------------------------
    B.box("motor", (9.2, 10.0, 9.2), (13.0, 12.0, 13.0),
          {"*": S["hull3"], "up": S["hull4"], "down": S["hull1"],
           "east": I["grille"], "south": I["grille"], "north": S["steel1"]})
    for k, fy in enumerate((10.4, 11.0, 11.6)):
        B.box(f"motor_fin_{k}", (9.5, fy, 9.5), (12.7, fy + 0.28, 12.7),
              {"*": S["steel1"], "up": S["steel2"]})
    B.box("motor_cap", (10.3, 12.0, 10.3), (11.9, 12.5, 11.9),
          {"*": S["steel2"], "up": S["steel3"], "down": S["hull2"]})
    B.box("led_run", (12.4, 12.4, 12.2), (13.1, 12.9, 12.9),
          {"*": S["amber2"], "up": S["amber2"]}, note="GLOW amber run lamp")
    return B


# =========================================================== power_tower (lower cell)
def build_tower() -> BlockModel:
    at = Atlas("power_tower")
    B = BlockModel("power_tower", at)

    def base(p: Painter):
        p.rect(0, 0, p.w, p.h, P["hull3"])
        p.hline(0, 0, p.w, P["steel3"])
        p.hline(1, 0, p.w, P["hull4"])
        p.hline(p.h - 1, 0, p.w, P["hull0"])
        for x in range(2, p.w - 2, 5):
            p.set(x, p.h - 2, P["steel1"])

    def front(p: Painter):
        plate(p, P["hull3"], P["steel1"], P["steel2"])
        ribbed(p, shade(P["hull3"], -10), 4)
        for y in range(2, p.h - 2, 5):
            p.hline(y, 1, p.w - 1, P["hull1"])

    def side(p: Painter):
        plate(p, P["hull4"], P["steel1"], P["steel2"])
        p.rect(1, 2, p.w - 1, p.h - 2, P["hull3"])
        p.border(P["hull1"])
        for x in range(2, p.w - 2, 3):
            p.vline(x, 2, p.h - 2, shade(P["hull3"], -9))
        p.border(P["steel1"])

    def grille_(p: Painter):
        grille(p, P["hull2"], P["steel1"], P["hull0"])

    def caution(p: Painter):
        hazard(p)

    def mast_plate(p: Painter):
        plate(p, P["hull4"], P["hull1"], P["steel1"])
        for y in range(2, p.h - 1, 2):
            p.hline(y, 1, p.w - 1, shade(P["hull4"], -10))

    I = {
        "base": at.island("base_face", 9, 1, base),            # 12.8 x 1.45 -> 8.8|9
        "front": at.island("front_plate", 8, 6, front),        # 10.4 x 7.8 -> 1.33|1.33
        "side": at.island("side_plate", 8, 6, side),           # 10.4 x 7.8 -> 1.33
        "grille": at.island("vent_grille", 6, 4, grille_),     # 7.2 x 4.8 -> 1.5|1.5
        "caution": at.island("caution_band", 12, 1, caution),  # 12.4 x 1.0 -> 12.4|12
        "plate": at.island("mast_plate", 6, 1, mast_plate),    # 8.4 x 1.0 -> 8.4|6 flat use
    }
    S = {k: at.swatch(k) for k in ("hull0", "hull1", "hull2", "hull3", "hull4",
                                   "steel1", "steel2", "steel3", "steel4",
                                   "amber0", "amber1", "amber2", "mag1", "face", "aqua2",
                                   "ember")}

    # ---------------- feet + deck ----------------------------------------
    for i, (fx, fz) in enumerate(((1.0, 1.0), (11.6, 1.0), (1.0, 11.6), (11.6, 11.6))):
        B.box(f"foot_{i}", (fx, 0.0, fz), (fx + 3.4, 1.6, fz + 3.4),
              {"*": S["hull1"], "up": S["hull3"], "down": S["steel1"]},
              note="ballast foot")
        B.box(f"foot_{i}_bolt", (fx + 1.2, 1.6, fz + 1.2), (fx + 2.2, 2.0, fz + 2.2),
              {"*": S["steel3"], "up": S["steel3"]})
    B.box("deck", (1.6, 1.6, 1.6), (14.4, 3.2, 14.4),
          {"*": S["hull3"], "up": S["hull2"], "down": S["steel1"], "north": I["base"],
           "south": I["base"], "east": I["base"], "west": I["base"]})
    B.box("deck_caution", (1.8, 1.8, 1.42), (14.2, 2.8, 1.60),
          {"*": S["hull1"], "north": I["caution"], "up": S["steel1"]},
          note="caution band on the deck edge")

    # ---------------- housing + control panel ----------------------------
    B.box("housing", (2.8, 3.2, 2.8), (13.2, 11.0, 13.2),
          {"north": I["front"], "south": I["side"], "east": I["side"], "west": I["side"],
           "up": S["hull2"], "down": S["hull0"]})
    B.box("panel", (3.8, 4.6, 2.60), (12.2, 10.2, 2.84),
          {"*": S["hull1"], "north": S["hull2"], "up": S["steel1"], "down": S["hull0"]},
          note="recessed control panel")
    B.box("panel_screen", (4.3, 7.4, 2.54), (7.7, 9.5, 2.64),
          {"*": S["hull0"], "north": S["hull0"], "up": S["steel1"]})
    for k, ty in enumerate((7.9, 8.4, 8.9)):
        B.box(f"screen_line_{k}", (4.5, ty, 2.50), (6.2 + k * 0.4, ty + 0.18, 2.56),
              {"*": S["amber0"], "north": S["amber1"]}, note="GLOW screen readout")
    B.box("panel_glow", (4.3, 5.0, 2.54), (11.7, 5.35, 2.62),
          {"*": S["mag1"], "north": S["mag1"]}, note="GLOW magenta power rail")
    for k, gx in enumerate((8.4, 9.6)):
        B.box(f"gauge_{k}", (gx, 7.6, 2.54), (gx + 1.0, 8.6, 2.62),
              {"*": S["hull0"], "north": S["hull1"], "up": S["steel1"]})
    B.box("estop", (10.8, 7.4, 2.50), (11.9, 8.5, 2.68),
          {"*": S["steel1"], "north": S["steel2"]})
    B.box("estop_cap", (11.0, 7.6, 2.42), (11.7, 8.3, 2.54),
          {"*": S["ember"], "north": S["ember"]}, note="GLOW emergency stop")
    for k, lx in enumerate((4.4, 5.2, 6.0, 6.8)):
        col = "amber2" if k % 2 == 0 else "aqua2"
        B.box(f"btn_{k}", (lx, 5.9, 2.54), (lx + 0.55, 6.45, 2.64),
              {"*": S[col], "north": S[col]}, note="GLOW button row")

    # ---------------- side vents + conduits ------------------------------
    for side, x0 in (("w", 2.62), ("e", 13.02)):
        B.box(f"vent_{side}", (x0, 4.8, 4.4), (x0 + 0.2, 9.6, 11.6),
              {"*": S["hull1"], "west": I["grille"], "east": I["grille"]})
        for k, vz in enumerate((5.4, 7.0, 8.6)):
            B.box(f"vent_slat_{side}{k}", (x0 - 0.16 if side == "w" else x0 + 0.2, vz, 4.6),
                  (x0 + 0.2 if side == "w" else x0 + 0.36, vz + 0.4, 11.4),
                  {"*": S["steel1"], "up": S["steel2"]})
    for k, cx_ in enumerate((3.2, 12.0)):
        B.box(f"conduit_{k}", (cx_, 3.2, 2.5), (cx_ + 0.7, 11.4, 3.2),
              {"*": S["steel1"], "north": S["steel2"], "up": S["steel3"]},
              note="power conduit rising into the mast")

    # ---------------- upper taper + mounting flange ----------------------
    B.box("collar", (2.4, 11.0, 2.4), (13.6, 11.8, 13.6),
          {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"], "north": S["steel2"]})
    for i, (x0, x1, y0, y1) in enumerate((
            (3.8, 12.2, 11.8, 12.8), (4.6, 11.4, 12.8, 13.8), (5.2, 10.8, 13.8, 14.6))):
        B.box(f"taper_{i}", (x0, y0, x0), (x1, y1, x1),
              {"*": S["hull4"], "up": S["steel2"], "down": S["hull1"],
               "north": S["steel1"], "east": S["steel1"], "west": S["steel1"]},
              note="stepped pedestal carrying the mid cell")
    B.box("mount_flange", (4.6, 14.6, 4.6), (11.4, 15.8, 11.4),
          {"*": S["steel1"], "up": S["steel2"], "down": S["hull2"],
           "north": S["steel2"], "east": S["steel2"], "west": S["steel2"]},
          note="flange the mid cell bolts onto (stops 0.2 short of the seam)")
    for i, (px, pz) in enumerate(((5.0, 5.0), (10.4, 5.0), (5.0, 10.4), (10.4, 10.4))):
        B.box(f"flange_bolt_{i}", (px, 15.8, pz), (px + 0.9, 15.8, pz + 0.9),
              {"*": S["steel3"], "up": S["steel3"]})
    for k, gx in enumerate((6.4, 9.6)):
        B.box(f"coil_feed_{k}", (gx, 12.2, 14.40), (gx + 0.8, 14.6, 14.62),
              {"*": S["amber1"], "south": S["amber1"]}, note="GLOW coil feed rail")
    return B


# =========================================================== power_tower_mid
def build_tower_mid() -> BlockModel:
    at = Atlas("power_tower_mid")
    B = BlockModel("power_tower_mid", at)

    def column(p: Painter):
        p.rect(0, 0, p.w, p.h, P["hull4"])
        p.border(P["steel1"])
        for x in range(1, p.w, 2):
            p.vline(x, 0, p.h, shade(P["hull4"], -14))
            p.vline(min(x + 1, p.w - 1), 0, p.h, P["steel1"])
        for y in range(3, p.h - 3, 5):                # energy notches up the mast
            for x in range(1, p.w - 1):
                p.set(x, y, P["amber0"])
                p.set(x, y + 1, P["amber1"])
        p.border(P["steel1"])

    def coil(p: Painter):
        p.rect(0, 0, p.w, p.h, P["steel1"])
        p.hline(0, 0, p.w, P["steel3"])
        p.hline(1, 0, p.w, P["steel2"])
        p.hline(p.h - 1, 0, p.w, P["hull1"])
        for x in range(0, p.w, 2):
            p.set(x, p.h // 2, P["amber1"])

    def fin(p: Painter):
        p.rect(0, 0, p.w, p.h, P["steel2"])
        p.hline(0, 0, p.w, P["steel4"])
        p.hline(1, 0, p.w, P["steel3"])
        p.vline(p.w - 1, 0, p.h, P["amber0"])
        p.vline(p.w - 2, 0, p.h, P["amber1"])

    I = {
        "column": at.island("column_plate", 4, 14, column),    # 3.4 x 13.2 -> 0.258|0.286
        "fin": at.island("fin_plate", 4, 4, fin),              # 1.7 x 1.6 -> 1.06|1.0
    }
    S = {k: at.swatch(k) for k in ("hull0", "hull1", "hull2", "hull3", "hull4",
                                   "steel1", "steel2", "steel3", "amber0", "amber1",
                                   "amber2", "aqua2")}
    CX = CZ = 8.0

    # ---------------- mast -----------------------------------------------
    B.box("mast", (6.3, 1.4, 6.3), (9.7, 14.6, 9.7),
          {"*": I["column"], "up": S["steel1"], "down": S["steel1"],
           "north": I["column"], "east": I["column"], "west": I["column"]},
          note="slim mast: this cell is mostly air, that is the silhouette")
    for i, (sx, sz) in enumerate(((-1, -1), (1, -1), (-1, 1), (1, 1))):
        lx, hx_ = span(CX, sx, 1.8, 0.42)
        lz, hz_ = span(CZ, sz, 1.8, 0.42)
        B.box(f"angle_{i}", (lx, 1.4, lz), (hx_, 14.6, hz_),
              {"*": S["steel2"], "up": S["steel3"], "down": S["hull1"]},
              note="corner angle iron")

    # ---------------- end flanges -----------------------------------------
    for nm, y0, y1 in (("flange_lo", 0.2, 1.4), ("flange_hi", 14.6, 15.8)):
        B.box(nm, (5.0, y0, 5.0), (11.0, y1, 11.0),
              {"*": S["steel2"], "up": S["steel3"], "down": S["hull1"],
               "north": S["steel3"], "east": S["steel3"], "west": S["steel3"]},
              note="bolt flange at the cell boundary")
        for i, (px, pz) in enumerate(((5.3, 5.3), (10.0, 5.3), (5.3, 10.0), (10.0, 10.0))):
            B.box(f"{nm}_bolt{i}", (px, y0 + 0.2, pz), (px + 0.7, y1 - 0.2, pz + 0.7),
                  {"*": S["steel3"], "up": S["steel3"]})

    # ---------------- collar rings ----------------------------------------
    for r, ry in enumerate((3.2, 7.2, 11.4)):
        for nm, f, t in (
            (f"ring{r}_n", (6.0, ry, 5.85), (10.0, ry + 0.7, 6.45)),
            (f"ring{r}_s", (6.0, ry, 9.55), (10.0, ry + 0.7, 10.15)),
            (f"ring{r}_w", (5.85, ry, 6.0), (6.45, ry + 0.7, 10.0)),
            (f"ring{r}_e", (9.55, ry, 6.0), (10.15, ry + 0.7, 10.0)),
        ):
            B.box(nm, f, t, {"*": S["steel2"], "up": S["steel3"], "down": S["hull1"]})
        for i, (sx, sz) in enumerate(((-1, -1), (1, -1), (-1, 1), (1, 1))):
            lx, hx_ = span(CX, sx, 1.85, 0.7)
            lz, hz_ = span(CZ, sz, 1.85, 0.7)
            B.box(f"ring{r}_c{i}", (lx, ry, lz), (hx_, ry + 0.7, hz_),
                  {"*": S["steel2"], "up": S["steel3"], "down": S["hull1"]})

    # ---------------- resonance coils -------------------------------------
    for r, (cy0, cy1) in enumerate(((4.5, 5.3), (12.3, 13.1))):
        for k in range(16):
            ang = math.radians(k * 22.5)
            bx = CX + 4.5 * math.cos(ang)
            bz = CZ + 4.5 * math.sin(ang)
            up = S["amber1"] if k % 4 == 0 else S["steel3"]
            B.box(f"coil{r}_{k}", (bx - 0.62, cy0, bz - 0.62), (bx + 0.62, cy1, bz + 0.62),
                  {"*": S["steel2"], "up": up, "down": S["hull1"],
                   "north": S["steel1"], "south": S["steel1"]},
                  note="resonance coil segment")
        for i, (sx, sz) in enumerate(((1, 0), (-1, 0), (0, 1), (0, -1))):
            lx, hx_ = span(CX, sx, 1.6, 2.6)
            lz, hz_ = span(CZ, sz, 1.6, 2.6)
            B.box(f"coil{r}_tie{i}", (lx, cy0 + 0.15, lz), (hx_, cy1 - 0.15, hz_),
                  {"*": S["steel2"], "up": S["steel3"]})

    # ---------------- radiating fin ring (tilted up and outward) ----------
    fins = (("E", 0.0, 6.9), ("E", 45.0, 7.7), ("N", 0.0, 6.9), ("N", 45.0, 7.7),
            ("W", 0.0, 6.9), ("W", -45.0, 7.7), ("S", 0.0, 6.9), ("S", -45.0, 7.7))
    for k, (base, rot, y0) in enumerate(fins):
        # the wide face of an E/W blade faces north/south and vice versa
        wide = ({"north": I["fin"], "south": I["fin"], "east": S["steel3"],
                 "west": S["steel3"]} if base in ("E", "W") else
                {"east": I["fin"], "west": I["fin"], "north": S["steel3"],
                 "south": S["steel3"]})
        x0, x1, z0, z1 = radial_box(CX, CZ, base, 3.1, 4.9, 0.42)
        faces = {"*": S["steel2"], "up": S["steel3"], "down": S["hull1"]}
        faces.update(wide)
        B.box(f"fin{k}_in", (x0, y0, z0), (x1, y0 + 2.0, z1), faces,
              rot=("y", rot) if abs(rot) > 1e-9 else None, pivot=(CX, y0, CZ),
              note="inner fin blade")
        x0, x1, z0, z1 = radial_box(CX, CZ, base, 4.6, 6.35, 0.42)
        B.box(f"fin{k}_out", (x0, y0 + 1.1, z0), (x1, y0 + 3.1, z1), faces,
              rot=("y", rot) if abs(rot) > 1e-9 else None, pivot=(CX, y0, CZ),
              note="outer fin blade, stepped up so the ring sweeps up and out")
    for k, (bx, bz) in enumerate(((8.0, 2.6), (13.4, 8.0), (8.0, 13.4), (2.6, 8.0))):
        B.box(f"node_{k}", (bx - 0.38, 9.9, bz - 0.38), (bx + 0.38, 10.7, bz + 0.38),
              {"*": S["amber1"], "up": S["amber2"], "down": S["amber1"]},
              note="GLOW energy node (pure swatch)")
    for i, (sx, sz) in enumerate(((1, 0), (-1, 0), (0, 1), (0, -1))):
        lx, hx_ = span(CX, sx, 1.95, 0.28)
        lz, hz_ = span(CZ, sz, 1.95, 0.28)
        B.box(f"spine_{i}", (lx, 2.4, lz), (hx_, 13.6, hz_),
              {"*": S["amber0"], "up": S["amber1"], "north": S["amber1"],
               "south": S["amber1"]}, note="GLOW energy spine (dim amber slit)")
    return B


# =========================================================== power_tower_top
def build_tower_top() -> BlockModel:
    at = Atlas("power_tower_top")
    B = BlockModel("power_tower_top", at)

    def crown(p: Painter):
        plate(p, P["steel1"], P["hull1"], P["steel2"])
        for y in range(2, p.h - 1, 2):
            p.hline(y, 1, p.w - 1, P["hull3"])
        p.border(P["hull0"])

    def deck(p: Painter):
        p.rect(0, 0, p.w, p.h, P["hull4"])
        p.border(P["steel1"])
        for r in range(2, p.h - 1, 3):
            p.hline(r, 1, p.w - 1, P["hull2"])

    def grille_(p: Painter):
        grille(p, P["hull2"], P["steel1"], P["hull0"])

    I = {
        "crown": at.island("crown_plate", 7, 3, crown),        # 4.8 x 2.1 -> 2.29|2.33
        "deck": at.island("deck_plate", 6, 6, deck),           # 4.8 x 4.8 -> 1.0
        "grille": at.island("vent_grille", 5, 3, grille_),     # 0.8 x 1.25 -> flat use
    }
    S = {k: at.swatch(k) for k in ("hull0", "hull1", "hull2", "hull3", "hull4",
                                   "steel1", "steel2", "steel3", "amber1", "amber2",
                                   "mag1", "aqua2", "lamp")}
    CX = CZ = 8.0

    # ---------------- mast foot -------------------------------------------
    B.box("mast", (6.3, 0.2, 6.3), (9.7, 3.4, 9.7),
          {"*": S["hull3"], "up": S["steel1"], "down": S["hull1"],
           "north": S["hull4"], "east": S["hull4"], "west": S["hull4"]},
          note="mast continues from the mid cell (starts 0.2 above the seam)")
    for i, (sx, sz) in enumerate(((-1, -1), (1, -1), (-1, 1), (1, 1))):
        lx, hx_ = span(CX, sx, 1.8, 0.42)
        lz, hz_ = span(CZ, sz, 1.8, 0.42)
        B.box(f"angle_{i}", (lx, 0.2, lz), (hx_, 3.4, hz_),
              {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"]})

    # ---------------- emitter crown --------------------------------------
    oct_prism(B, "crown", "y", 3.4, 5.5, side=I["crown"], small=S["hull2"],
              up=I["deck"], down=S["hull1"], a=2.4, r=4.6, note="emitter crown")
    B.box("crown_ring", (3.3, 5.5, 3.3), (12.7, 5.95, 12.7),
          {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"],
           "north": S["steel2"], "east": S["steel2"], "west": S["steel2"]})
    for k, (vx, vz) in enumerate(((3.5, 4.4), (12.1, 4.4), (3.5, 11.2), (12.1, 11.2))):
        B.box(f"emit_{k}", (vx, 3.9, vz), (vx + 0.45, 5.1, vz + 0.45),
              {"*": S["aqua2"], "north": S["aqua2"], "up": S["aqua2"]},
              note="GLOW emitter slit")
    B.box("crown_band", (3.62, 3.55, 3.62), (12.38, 3.9, 12.38),
          {"*": S["mag1"], "north": S["mag1"], "east": S["mag1"], "west": S["mag1"]},
          note="GLOW magenta accent ring")

    # ---------------- mast + breathing lamp --------------------------------
    B.box("lamp_mast", (7.1, 5.95, 7.1), (8.9, 10.5, 8.9),
          {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"]})
    B.box("lamp_collar", (6.6, 10.3, 6.6), (9.4, 10.8, 9.4),
          {"*": S["steel2"], "up": S["steel3"], "down": S["hull2"]})
    # stepped sphere; every face is the pure white `lamp` swatch (tinted per state)
    for i, (w, y0, y1) in enumerate(((1.55, 10.8, 11.35), (1.95, 11.35, 11.95),
                                     (2.15, 11.95, 12.65), (2.15, 12.65, 13.35),
                                     (1.95, 13.35, 13.95), (1.55, 13.95, 14.5),
                                     (1.0, 14.5, 14.95))):
        B.box(f"lamp_{i}", (CX - w, y0, CZ - w), (CX + w, y1, CZ + w),
              {"*": S["lamp"]}, note="GLOW beacon: single flat colour, tint per state")

    # ---------------- three antennas ---------------------------------------
    for nm, pivot, rot, bx in (
        ("ant_w", (5.4, 6.0, 8.0), ("z", 22.5), (5.05, 5.75, 7.62, 8.38)),
        ("ant_e", (10.6, 6.0, 8.0), ("z", -22.5), (10.25, 10.95, 7.62, 8.38)),
    ):
        B.box(f"{nm}_base", (bx[0] - 0.5, 5.5, 7.35), (bx[1] + 0.5, 6.2, 8.65),
              {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"]})
        B.box(f"{nm}_seg1", (bx[0], 6.0, bx[2]), (bx[1], 8.6, bx[3]),
              {"*": S["steel2"], "up": S["steel3"]}, rot=rot, pivot=pivot)
        B.box(f"{nm}_seg2", (bx[0] + 0.14, 8.6, bx[2] + 0.14),
              (bx[1] - 0.14, 10.9, bx[3] - 0.14),
              {"*": S["steel3"], "up": S["steel3"]}, rot=rot, pivot=pivot)
        B.box(f"{nm}_tip", (bx[0] + 0.03, 10.9, bx[2] + 0.03),
              (bx[1] - 0.03, 11.35, bx[3] - 0.03),
              {"*": S["amber1"], "up": S["amber2"]}, rot=rot, pivot=pivot,
              note="GLOW antenna tip")
    # third antenna points north
    B.box("ant_n_base", (7.35, 5.5, 5.4), (8.65, 6.2, 6.5),
          {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"]})
    B.box("ant_n_seg1", (7.62, 6.0, 5.05), (8.38, 8.6, 5.75),
          {"*": S["steel2"], "up": S["steel3"]}, rot=("x", -22.5), pivot=(8.0, 6.0, 5.4))
    B.box("ant_n_seg2", (7.76, 8.6, 5.19), (8.24, 10.9, 5.61),
          {"*": S["steel3"], "up": S["steel3"]}, rot=("x", -22.5), pivot=(8.0, 6.0, 5.4))
    B.box("ant_n_tip", (7.73, 10.9, 5.16), (8.27, 11.35, 5.64),
          {"*": S["amber1"], "up": S["amber2"]}, rot=("x", -22.5), pivot=(8.0, 6.0, 5.4),
          note="GLOW antenna tip")

    # ---------------- service details --------------------------------------
    B.box("vent_w", (4.55, 5.95, 6.6), (5.35, 7.2, 9.4),
          {"*": S["hull1"], "up": S["steel1"], "west": S["hull2"]})
    B.box("vent_e", (10.65, 5.95, 6.6), (11.45, 7.2, 9.4),
          {"*": S["hull1"], "up": S["steel1"], "east": S["hull2"]})
    B.box("led_mag", (6.9, 5.5, 4.6), (7.4, 5.98, 5.1),
          {"*": S["mag1"], "up": S["mag1"]}, note="GLOW magenta status")
    return B


# =========================================================== water pipes
def build_pipe(atlas: Atlas, variant: str) -> BlockModel:
    """One cell of pipe. Axis = Z, so blockstate `x:90` makes it vertical.

    variant: straight | corner | tee | cross | cap
    A pipe has no large panel to paint, so it is built from flat swatches only
    (lit top / mid sides / dark underside) plus a glowing coolant sight band and
    real bolt geometry on the flanges — that keeps one 32x32 atlas for all five
    connection shapes.
    """
    B = BlockModel(f"water_pipe_{variant}", atlas)
    S = {k: atlas.swatch(k) for k in ("hull0", "hull1", "hull2", "hull3", "hull4",
                                      "steel1", "steel2", "steel3", "steel4",
                                      "aqua0", "aqua1", "aqua2", "aqua3")}

    def tube_mats():
        return {"*": S["hull4"], "up": S["steel2"], "down": S["hull1"],
                "north": S["steel1"], "south": S["steel1"],
                "east": S["hull4"], "west": S["hull4"]}

    def sight_mats(axis: str):
        """Coolant sight band: four glowing flanks, steel shoulders."""
        if axis == "z":
            return {"*": S["steel1"], "east": S["aqua2"], "west": S["aqua2"],
                    "up": S["aqua2"], "down": S["aqua2"],
                    "north": S["steel2"], "south": S["steel2"]}
        return {"*": S["steel1"], "north": S["aqua2"], "south": S["aqua2"],
                "up": S["aqua2"], "down": S["aqua2"],
                "east": S["steel2"], "west": S["steel2"]}

    arms = {"straight": ("N", "S"), "corner": ("N", "E"), "tee": ("N", "S", "E"),
            "cross": ("N", "S", "E", "W"), "cap": ("E",)}[variant]
    joint = variant in ("corner", "tee", "cross", "cap")

    if joint:
        oct_prism(B, "joint", "y", 4.95, 11.05, side=S["steel1"], small=S["hull4"],
                  up=S["steel2"], down=S["hull1"], a=2.35, r=3.05, corner_w=0.25,
                  note="cast junction body")

    for arm in arms:
        if arm in ("N", "S"):
            lo, hi = (1.0, 8.0) if arm == "N" else (8.0, 15.0)
            oct_prism(B, f"arm_{arm.lower()}", "z", lo, hi, side=S["hull4"],
                      small=S["hull3"], up=S["steel2"], down=S["hull1"],
                      a=2.1, r=3.0, corner_w=0.22, note=f"run {'north' if arm == 'N' else 'south'}")
            f_lo, f_hi = (0.05, 1.0) if arm == "N" else (15.0, 15.95)
            pipe_flange(B, f"fl_{arm.lower()}", "z", f_lo, f_hi,
                        S["steel2"], S["steel3"], S["steel4"])
            b0, b1 = (3.0, 5.2) if arm == "N" else (10.8, 13.0)
            for nm, c0, c1 in ((f"sight_{arm.lower()}", b0 + 0.5, b1 - 0.5),
                               (f"sight_{arm.lower()}_r0", b0 - 0.2, b0 + 0.5),
                               (f"sight_{arm.lower()}_r1", b1 - 0.5, b1 + 0.2)):
                glow = "_r" not in nm
                B.box(nm, (4.7, 6.6, c0), (11.3, 9.4, c1),
                      sight_mats("z") if glow else
                      {"*": S["steel2"], "up": S["steel3"], "down": S["hull1"]},
                      note="GLOW coolant sight band" if glow else "sight band rim")
        else:
            lo, hi = (1.0, 8.0) if arm == "W" else (8.0, 15.0)
            oct_prism(B, f"arm_{arm.lower()}", "x", lo, hi, side=S["hull4"],
                      small=S["hull3"], up=S["steel2"], down=S["hull1"],
                      a=2.1, r=3.0, corner_w=0.22, note=f"run {'west' if arm == 'W' else 'east'}")
            f_lo, f_hi = (0.05, 1.0) if arm == "W" else (15.0, 15.95)
            pipe_flange(B, f"fl_{arm.lower()}", "x", f_lo, f_hi,
                        S["steel2"], S["steel3"], S["steel4"])
            b0, b1 = (3.0, 5.2) if arm == "W" else (10.8, 13.0)
            for nm, c0, c1 in ((f"sight_{arm.lower()}", b0 + 0.5, b1 - 0.5),
                               (f"sight_{arm.lower()}_r0", b0 - 0.2, b0 + 0.5),
                               (f"sight_{arm.lower()}_r1", b1 - 0.5, b1 + 0.2)):
                glow = "_r" not in nm
                B.box(nm, (c0, 6.6, 4.7), (c1, 9.4, 11.3),
                      sight_mats("x") if glow else
                      {"*": S["steel2"], "up": S["steel3"], "down": S["hull1"]},
                      note="GLOW coolant sight band" if glow else "sight band rim")

    if variant == "cap":
        # blind end on the far side of the junction, with a coolant bead
        B.box("cap_l0", (3.55, 5.15, 5.15), (4.95, 10.85, 10.85),
              {"*": S["hull3"], "west": S["steel2"], "up": S["steel1"],
               "down": S["hull1"], "north": S["hull4"], "south": S["hull4"]},
              note="blind end plate")
        B.box("cap_l1", (2.9, 6.1, 6.1), (3.55, 9.9, 9.9),
              {"*": S["hull4"], "west": S["steel2"]}, note="blind end step")
        B.box("cap_l2", (2.45, 6.9, 6.9), (2.9, 9.1, 9.1),
              {"*": S["steel1"], "west": S["steel3"]})
        B.box("cap_bead", (2.15, 7.4, 7.4), (2.45, 8.6, 8.6),
              {"*": S["aqua2"], "west": S["aqua2"]}, note="GLOW coolant bead")
    return B


BUILDERS = {
    "ore_mill": build_ore_mill,
    "ore_mill_side": build_ore_mill_side,
    "water_pump": build_water_pump,
    "power_tower": build_tower,
    "power_tower_mid": build_tower_mid,
    "power_tower_top": build_tower_top,
}
