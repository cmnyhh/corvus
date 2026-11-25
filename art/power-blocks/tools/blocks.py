#!/usr/bin/env python3
"""The three Corvus power blocks, authored element by element in 16^3 space.

Conventions (matching Minecraft Java block models)
    * front of each machine = the **north** face (-Z), which is what blockstates
      normally rotate toward the player
    * every element stays inside 0..16; nothing dips below y=0 or past the walls
    * rotations are only about one axis and only +-22.5 / +-45 deg (vanilla limit)
    * texture = one 32x32 atlas per block; each face names either a shared atlas
      island ("#key") or a flat palette swatch ("@name")
"""
from __future__ import annotations

from pbgen import Atlas, BlockModel, PALETTE, Painter, shade

import math

# =========================================================== shared painters
def plate(p: Painter, base: str, edge: str, hi: str | None = None, rivets: bool = False):
    """Flat machine plate: 1px dark border, optional top highlight, corner bolts."""
    p.rect(0, 0, p.w, p.h, base)
    p.border(edge)
    if hi:
        p.hline(1, 1, p.w - 1, hi)
    if rivets and p.w > 4 and p.h > 4:
        for (x, y) in ((1, 1), (p.w - 2, 1), (1, p.h - 2), (p.w - 2, p.h - 2)):
            p.set(x, y, PALETTE["steel3"])
            p.set(x, y, PALETTE["steel3"])


def brushed(p: Painter, x0, y0, x1, y1, col, step=3):
    for x in range(x0, x1, step):
        p.vline(x, y0, y1, col)


# =========================================================== burner generator
def build_burner() -> BlockModel:
    at = Atlas("burner_generator")
    B = BlockModel("burner_generator", at)

    # ---------------- atlas: islands ------------------------------------
    def body_front(p: Painter):
        plate(p, PALETTE["hull2"], PALETTE["hull0"], PALETTE["hull3"])
        brushed(p, 2, 2, p.w - 2, p.h - 2, shade(PALETTE["hull2"], -6), 3)
        # two horizontal structural seams
        p.hline(3, 1, p.w - 1, PALETTE["hull1"])
        p.hline(p.h - 4, 1, p.w - 1, PALETTE["hull1"])
        # scorch gradient above the burner slot (heat rises)
        for y in range(p.h - 8, p.h - 4):
            for x in range(2, p.w - 2):
                if (x * 7 + y * 3) % 5 == 0:
                    p.set(x, y, shade(PALETTE["hull2"], -10))
        for (x, y) in ((2, 2), (p.w - 3, 2)):
            p.set(x, y, PALETTE["steel2"])

    def body_side(p: Painter):
        plate(p, PALETTE["hull3"], PALETTE["hull1"], PALETTE["hull4"])
        # recessed louvre field between the corner pillars
        p.rect(1, 4, p.w - 1, p.h - 3, PALETTE["hull2"])
        p.border(PALETTE["hull0"])
        for y in range(5, p.h - 3, 2):
            p.hline(y, 1, p.w - 1, PALETTE["hull3"])
            p.hline(y + 1, 1, p.w - 1, shade(PALETTE["hull2"], -8))

    def cap_top(p: Painter):
        plate(p, PALETTE["steel1"], PALETTE["hull1"], PALETTE["steel2"])
        p.rect(1, 1, p.w - 1, p.h - 1, PALETTE["hull3"])
        # soot bloom around the 4x4 chimney footprint, centred
        cx, cy = p.w / 2.0, p.h / 2.0
        for y in range(p.h):
            for x in range(p.w):
                d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                if 2.2 < d < 3.4:
                    p.set(x, y, shade(PALETTE["hull3"], -14))
                elif d <= 2.2:
                    p.set(x, y, shade(PALETTE["hull3"], -6))
        p.border(PALETTE["steel2"])
        for (x, y) in ((1, 1), (p.w - 2, 1), (1, p.h - 2), (p.w - 2, p.h - 2)):
            p.set(x, y, PALETTE["steel3"])

    def louver_face(p: Painter):
        # one fin, seen from outside: lit top edge, dark throat underneath
        p.hline(0, 0, p.w, PALETTE["steel2"])
        p.hline(1, 0, p.w, PALETTE["hull4"])
        for x in range(1, p.w, 2):
            p.set(x, 1, PALETTE["hull2"])

    def slot_cavity(p: Painter):
        # 2 px per unit: a deep throat with a bed of embers
        p.rect(0, 0, p.w, p.h, PALETTE["hull0"])
        p.hline(0, 0, p.w, shade(PALETTE["hull0"], -8))
        p.hline(1, 0, p.w, shade(PALETTE["hull0"], 4))
        for x in range(2, p.w - 2, 3):
            p.set(x, 1, PALETTE["hull1"])
        for (x, c) in ((3, PALETTE["amber0"]), (4, PALETTE["ember"]), (5, PALETTE["amber1"]),
                       (6, PALETTE["ember"]), (7, PALETTE["amber0"]), (9, PALETTE["amber0"]),
                       (10, PALETTE["ember"])):
            p.set(x, p.h - 1, c)
            if x % 2 == 0:
                p.set(x, p.h - 2, PALETTE["amber0"])

    def rear_grille(p: Painter):
        p.rect(0, 0, p.w, p.h, PALETTE["hull0"])
        for y in range(0, p.h, 2):
            p.hline(y, 0, p.w, PALETTE["steel1"])
            p.hline(y + 1, 0, p.w, PALETTE["hull2"])
        p.border(PALETTE["hull1"])

    def base_face(p: Painter):
        p.rect(0, 0, p.w, p.h, PALETTE["hull2"])
        p.hline(0, 0, p.w, PALETTE["steel2"])
        p.hline(1, 0, p.w, PALETTE["hull3"])
        p.hline(p.h - 1, 0, p.w, PALETTE["hull0"])
        for x in range(2, p.w - 1, 4):
            p.set(x, p.h - 2, PALETTE["steel1"])

    I = {
        "front": at.island("body_front", 10, 12, body_front),
        "side": at.island("body_side", 10, 12, body_side),
        "cap": at.island("cap_top", 12, 12, cap_top),
        "louver": at.island("louver_face", 9, 2, louver_face),
        "cavity": at.island("slot_cavity", 12, 5, slot_cavity),
        "grille": at.island("rear_grille", 8, 8, rear_grille),
        "base": at.island("base_face", 12, 2, base_face),
    }
    S = {k: at.swatch(k) for k in ("hull0", "hull1", "hull2", "hull3", "hull4",
                                   "steel1", "steel2", "steel3", "steel4",
                                   "amber0", "amber1", "amber2", "ember", "mag1")}

    # ---------------- geometry ------------------------------------------
    # feet (4 pads, they carry the plinth off the floor)
    for i, (fx, fz) in enumerate(((2.2, 2.2), (11.8, 2.2), (2.2, 11.8), (11.8, 11.8))):
        B.box(f"foot_{i}", (fx, 0, fz), (fx + 2, 0.8, fz + 2),
              {"*": S["hull1"], "up": S["hull2"], "down": S["steel1"]})

    # plinth
    B.box("plinth", (2, 0.8, 2), (14, 2.5, 14),
          {"*": I["base"], "up": S["hull3"], "down": S["hull0"]})

    # main housing
    B.box("housing", (3, 2.5, 3), (13, 14, 13),
          {"north": I["front"], "south": I["side"], "east": I["side"], "west": I["side"],
           "up": S["hull2"], "down": S["hull1"]})

    # corner pillars (the metal frame that reads as "industrial")
    for i, (px, pz) in enumerate(((2, 2), (13, 2), (2, 13), (13, 13))):
        B.box(f"pillar_{i}", (px, 2.5, pz), (px + 1, 14, pz + 1),
              {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"]})

    # top cap + short chimney (4x4)
    B.box("top_cap", (2, 14, 2), (14, 15, 14),
          {"*": S["steel1"], "up": I["cap"], "down": S["hull2"]})
    B.box("chimney_n", (6, 15, 6), (10, 16, 7), {"*": S["hull3"], "up": S["steel1"], "down": S["hull0"]})
    B.box("chimney_s", (6, 15, 9), (10, 16, 10), {"*": S["hull3"], "up": S["steel1"], "down": S["hull0"]})
    B.box("chimney_w", (6, 15, 7), (7, 16, 9), {"*": S["hull3"], "up": S["steel1"], "down": S["hull0"]})
    B.box("chimney_e", (9, 15, 7), (10, 16, 9), {"*": S["hull3"], "up": S["steel1"], "down": S["hull0"]})
    B.box("chimney_bore", (7, 15.35, 7), (9, 15.55, 9),
          {"*": S["hull0"], "up": S["hull0"]})

    # side cooling louvres: three slanted slats per side (the "3-4 条斜面")
    for side, sx0, sx1, ang, pvx in (("e", 12.7, 13.7, -22.5, 13.2), ("w", 2.3, 3.3, 22.5, 2.8)):
        B.box(f"louvre_back_{side}", (sx0 - 0.15 if side == "e" else sx0 + 0.15, 3.4, 3.4),
              (sx1 - 0.15 if side == "e" else sx1 + 0.15, 11.4, 12.6),
              {"*": S["hull2"]}, note="recessed field behind the slats")
        for k, yc in enumerate((4.2, 6.4, 8.6, 10.8)):
            outer = "east" if side == "e" else "west"
            B.box(f"louvre_{side}{k}", (sx0, yc - 0.85, 3.5), (sx1, yc + 0.85, 12.5),
                  {"*": S["hull3"], outer: I["louver"], "up": S["hull3"], "down": S["hull2"]},
                  rot=("z", ang), pivot=(pvx, yc, 8.0),
                  note="slanted fin: outer face is the ribbed louvre strip")

    # ---- front: ember observation window -------------------------------
    for nm, f, t in (
        ("win_frame_t", (4.6, 11.6, 2.45), (11.4, 12.5, 2.95)),
        ("win_frame_b", (4.6, 8.6, 2.45), (11.4, 9.5, 2.95)),
        ("win_frame_l", (4.6, 9.5, 2.45), (5.5, 11.6, 2.95)),
        ("win_frame_r", (10.5, 9.5, 2.45), (11.4, 11.6, 2.95)),
    ):
        B.box(nm, f, t, {"*": S["steel2"], "up": S["steel3"], "down": S["hull1"],
                         "north": S["steel2"]})
    B.box("ember_glass", (5.5, 9.5, 2.62), (10.5, 11.6, 2.86),
          {"*": S["ember"], "north": S["ember"]},
          note="GLOW: pure ember swatch, own 4x4 atlas block")
    for k, gx in enumerate((6.3, 7.9, 9.45)):
        B.box(f"ember_grate_{k}", (gx, 9.5, 2.45), (gx + 0.25, 11.6, 2.70),
              {"*": S["hull1"], "north": S["hull1"]})

    for k, gx in enumerate((4.75, 11.15)):
        B.box(f"accent_mag_{k}", (gx, 9.5, 2.42), (gx + 0.35, 11.6, 2.72),
              {"*": S["mag1"], "north": S["mag1"]},
              note="GLOW magenta accent rail (drone family colour)")

    # ---- front: fuel funnel (tells the player "feed me from the front") --
    B.box("fuel_hood", (4.4, 7.0, 2.0), (11.6, 7.6, 3.0),
          {"*": S["steel2"], "up": S["steel3"], "north": S["steel2"], "down": S["hull1"]})
    B.box("fuel_cavity", (4.9, 4.7, 2.3), (10.9, 7.0, 3.0),
          {"*": S["hull0"], "north": I["cavity"], "up": S["hull0"]})
    B.box("fuel_tray", (4.2, 4.1, 1.8), (11.8, 4.7, 3.05),
          {"*": S["steel2"], "up": S["steel3"], "north": S["steel2"], "down": S["hull1"]})
    B.box("fuel_guide_l", (4.15, 4.7, 1.95), (4.9, 7.0, 2.6),
          {"*": S["steel1"], "north": S["steel1"]})
    B.box("fuel_guide_r", (10.9, 4.7, 1.95), (11.65, 7.0, 2.6),
          {"*": S["steel1"], "north": S["steel1"]})

    # lower trim band + rivets + status lights
    B.box("front_band", (3.3, 2.85, 2.9), (12.7, 3.9, 3.12),
          {"*": S["hull3"], "north": S["steel1"], "up": S["steel2"], "down": S["hull0"]})
    for k, (rx, ry) in enumerate(((3.4, 13.2), (12.0, 13.2))):
        B.box(f"rivet_{k}", (rx, ry, 2.8), (rx + 0.5, ry + 0.5, 3.05),
              {"*": S["steel3"], "north": S["steel3"]})
    B.box("led_amber", (11.0, 14.2, 2.05), (11.7, 14.9, 2.45),
          {"*": S["amber2"], "north": S["amber2"]}, note="GLOW amber indicator")
    B.box("led_mag", (9.9, 14.2, 2.05), (10.6, 14.9, 2.45),
          {"*": S["mag1"], "north": S["mag1"]}, note="GLOW magenta indicator")

    # ---- rear exhaust ---------------------------------------------------
    B.box("rear_grille_plate", (4.0, 4.0, 13.0), (12.0, 12.0, 13.25),
          {"*": S["hull1"], "south": I["grille"]})
    for k, gy in enumerate((4.6, 6.0, 7.4, 8.8, 10.2)):
        B.box(f"rear_slat_{k}", (4.4, gy, 13.2), (11.6, gy + 0.45, 13.5),
              {"*": S["steel1"], "south": S["steel2"], "up": S["steel3"], "down": S["hull0"]})
    return B


# =========================================================== solar generator
def build_solar() -> BlockModel:
    at = Atlas("solar_generator")
    B = BlockModel("solar_generator", at)

    PIV = (8.0, 9.5, 7.5)
    ANG = -22.5

    def base_side(p: Painter):
        p.rect(0, 0, p.w, p.h, PALETTE["hull2"])
        p.hline(0, 0, p.w, PALETTE["steel2"])
        p.hline(1, 0, p.w, PALETTE["hull3"])
        p.hline(p.h - 1, 0, p.w, PALETTE["hull0"])
        for x in range(1, p.w - 1, 5):
            p.set(x, 1, PALETTE["steel1"])

    def base_top(p: Painter):
        plate(p, PALETTE["hull3"], PALETTE["hull0"], PALETTE["hull4"])
        p.rect(1, 1, p.w - 1, p.h - 1, PALETTE["hull2"])
        p.rect(1, 1, p.w - 1, 2, PALETTE["hull3"])
        # four corner mounting plates
        for (cx, cy) in ((1, 1), (p.w - 4, 1), (1, p.h - 4), (p.w - 4, p.h - 4)):
            p.rect(cx, cy, cx + 3, cy + 3, PALETTE["steel1"])
            p.set(cx + 1, cy + 1, PALETTE["steel3"])
        # cable channel down the middle
        p.rect(p.w // 2 - 1, 4, p.w // 2 + 1, p.h - 4, PALETTE["hull1"])
        p.dither(p.w // 2 - 1, 4, p.w // 2 + 1, p.h - 4, PALETTE["hull0"], 1)

    def panel_under(p: Painter):
        p.rect(0, 0, p.w, p.h, PALETTE["pv0"])
        for y in range(1, p.h - 1, 2):
            p.hline(y, 1, p.w - 1, PALETTE["pv1"])
        p.border(PALETTE["hull0"])

    def cell_top(p: Painter):
        # deep blue wafer with a four-step sheen falling away from the top edge
        p.rect(0, 0, p.w, p.h, PALETTE["pv2"])
        p.hline(0, 0, p.w, PALETTE["pv1"])
        p.hline(1, 0, p.w, PALETTE["pv2"])
        p.hline(2, 0, p.w, PALETTE["pv3"])
        p.hline(3, 0, p.w, PALETTE["pv4"])
        p.vline(0, 0, p.h - 1, shade(PALETTE["pv3"], -20))
        p.vline(p.w - 1, 1, p.h, PALETTE["pv0"])
        p.hline(p.h - 1, 0, p.w, PALETTE["pv0"])

    def cell_side(p: Painter):
        p.rect(0, 0, p.w, p.h, PALETTE["pv0"])
        p.hline(0, 0, p.w, PALETTE["pv2"])

    I = {
        "base_side": at.island("base_side", 15, 3, base_side),
        "base_top": at.island("base_top", 15, 15, base_top),

        "under": at.island("panel_under", 13, 10, panel_under),
        "cell": at.island("cell_top", 4, 4, cell_top),
        "cell_side": at.island("cell_side", 3, 1, cell_side),
    }
    S = {k: at.swatch(k) for k in ("hull0", "hull1", "hull2", "hull3", "hull4",
                                   "steel1", "steel2", "steel3", "steel4",
                                   "pv0", "pv1", "pv2", "amber2")}

    # ---- ground assembly ------------------------------------------------
    B.box("base", (0.5, 0, 0.5), (15.5, 2.6, 15.5),
          {"*": I["base_side"], "up": S["hull2"], "down": S["hull1"],
           "north": I["base_side"], "south": I["base_side"]})
    B.box("base_deck", (1.2, 2.6, 1.2), (14.8, 3.15, 14.8),
          {"*": S["steel1"], "up": I["base_top"], "down": S["hull1"]})
    for i, (cx, cz) in enumerate(((0.7, 0.7), (12.7, 0.7), (0.7, 12.7), (12.7, 12.7))):
        B.box(f"weight_cap_{i}", (cx, 3.15, cz), (cx + 2.6, 3.95, cz + 2.6),
              {"*": S["steel1"], "up": S["steel2"], "down": S["hull2"]},
              note="corner ballast so the panel does not read as a floating sheet")
    # cable column + junction box: explains where the power comes out
    B.box("junction_box", (6.6, 3.15, 6.2), (9.4, 4.3, 7.9),
          {"*": S["hull2"], "up": S["steel1"], "north": S["hull3"], "down": S["hull1"]})
    B.box("cable_column", (7.2, 4.3, 6.6), (8.8, 9.3, 7.6),
          {"*": S["hull3"], "up": S["steel1"], "north": S["steel1"], "down": S["hull1"]})
    B.box("deck_led", (7.1, 3.24, 1.14), (8.9, 3.66, 1.32),
          {"*": S["amber2"], "north": S["amber2"]},
          note="GLOW amber status strip, flush on the deck edge")

    # ---- A-frame stand --------------------------------------------------
    for nm, x0 in (("post_r_w", 3.6), ("post_r_e", 11.0)):
        B.box(nm, (x0, 3.95, 10.2), (x0 + 1.4, 10.5, 11.8),
              {"*": S["steel1"], "up": S["steel3"], "down": S["hull1"],
               "north": S["steel2"], "south": S["steel2"], "east": S["steel1"], "west": S["steel1"]})
    for nm, x0 in (("post_f_w", 3.6), ("post_f_e", 11.0)):
        B.box(nm, (x0, 3.95, 4.0), (x0 + 1.4, 8.1, 5.6),
              {"*": S["steel1"], "up": S["steel3"], "down": S["hull1"],
               "north": S["steel2"], "south": S["steel2"], "east": S["steel1"], "west": S["steel1"]})
    B.box("cross_brace", (5.0, 5.6, 4.3), (11.0, 6.3, 5.5),
          {"*": S["hull3"], "up": S["hull4"], "north": S["steel1"], "down": S["hull1"]})
    B.box("rear_beam", (5.0, 8.6, 10.4), (11.0, 9.3, 11.6),
          {"*": S["hull3"], "up": S["steel1"], "north": S["steel1"], "down": S["hull1"]})

    # ---- the tilted PV panel (all of it shares one pivot) ---------------
    R = ("x", ANG)
    B.box("panel_backing", (1.6, 9.25, 2.6), (14.4, 9.52, 12.4),
          {"*": S["hull2"], "up": I["under"], "down": S["hull1"]}, rot=R, pivot=PIV)
    B.box("pv_frame_n", (1.6, 9.32, 2.6), (14.4, 9.88, 3.4),
          {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"], "north": S["steel2"]},
          rot=R, pivot=PIV)
    B.box("pv_frame_s", (1.6, 9.32, 11.6), (14.4, 9.88, 12.4),
          {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"], "south": S["steel2"]},
          rot=R, pivot=PIV)
    B.box("pv_frame_w", (1.6, 9.32, 3.4), (2.4, 9.88, 11.6),
          {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"], "west": S["steel2"]},
          rot=R, pivot=PIV)
    B.box("pv_frame_e", (13.6, 9.32, 3.4), (14.4, 9.88, 11.6),
          {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"], "east": S["steel2"]},
          rot=R, pivot=PIV)

    # raised metal grid: 3 dividers along z + 2 along x => 12 cells
    for k, gx in enumerate((4.98, 7.85, 10.73)):
        B.box(f"grid_z{k}", (gx, 9.52, 3.4), (gx + 0.3, 9.92, 11.6),
              {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"]}, rot=R, pivot=PIV)
    for k, gz in enumerate((5.93, 8.77)):
        B.box(f"grid_x{k}", (2.4, 9.52, gz), (13.6, 9.92, gz + 0.3),
              {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"]}, rot=R, pivot=PIV)

    cw, cd = 2.575, 2.533
    xs = [2.4, 2.4 + cw + 0.3, 2.4 + 2 * (cw + 0.3), 2.4 + 3 * (cw + 0.3)]
    zs = [3.4, 3.4 + cd + 0.3, 3.4 + 2 * (cd + 0.3)]
    n = 0
    for zz in zs:
        for xx in xs:
            n += 1
            B.box(f"pv_cell_{n}", (round(xx, 3), 9.52, round(zz, 3)),
                  (round(xx + cw, 3), 9.70, round(zz + cd, 3)),
                  {"*": S["pv0"], "up": I["cell"], "north": I["cell_side"],
                   "south": I["cell_side"], "east": I["cell_side"], "west": I["cell_side"]},
                  rot=R, pivot=PIV, note="12 grid cells, 2px sheen drawn per cell")

    for k, hx in enumerate((4.0, 10.4)):
        B.box(f"hinge_{k}", (hx, 9.2, 12.0), (hx + 1.6, 9.6, 12.7),
              {"*": S["steel2"], "up": S["steel3"], "down": S["hull1"]}, rot=R, pivot=PIV)
    return B


# =========================================================== battery bank
def build_battery() -> BlockModel:
    at = Atlas("battery_bank")
    B = BlockModel("battery_bank", at)

    def front_plate(p: Painter):
        plate(p, PALETTE["hull2"], PALETTE["hull1"], PALETTE["hull3"])
        # rails that run between the three cell bays
        for y in (2, 5):
            p.hline(y, 1, p.w - 1, PALETTE["steel1"])
            p.hline(y + 1, 1, p.w - 1, PALETTE["hull0"])
        for x in range(3, p.w - 2, 5):
            p.set(x, 1, PALETTE["steel1"])

    def side_plate(p: Painter):
        p.rect(0, 0, p.w, p.h, PALETTE["hull4"])
        p.border(PALETTE["hull1"])
        for x in range(2, p.w - 2, 2):
            p.vline(x, 2, p.h - 2, PALETTE["hull3"])
            p.vline(x + 1, 2, p.h - 2, shade(PALETTE["hull4"], 8))
        p.hline(1, 1, p.w - 1, PALETTE["steel1"])

    def back_plate(p: Painter):
        plate(p, PALETTE["hull3"], PALETTE["hull1"], PALETTE["hull4"])
        p.rect(2, 2, p.w - 2, p.h - 2, PALETTE["hull1"])
        p.border(PALETTE["hull0"])
        for (x, y) in ((3, 3), (p.w - 4, 3), (3, p.h - 4), (p.w - 4, p.h - 4)):
            p.set(x, y, PALETTE["steel2"])
        p.rect(4, 4, p.w - 4, p.h - 4, PALETTE["hull2"])
        p.dither(4, 4, p.w - 4, p.h - 4, PALETTE["hull1"], 0)

    def cap_top(p: Painter):
        p.rect(0, 0, p.w, p.h, PALETTE["hull3"])
        p.hline(0, 0, p.w, PALETTE["steel2"])
        p.hline(1, 0, p.w, PALETTE["steel1"])
        p.hline(p.h - 2, 0, p.w, PALETTE["hull2"])
        p.hline(p.h - 1, 0, p.w, PALETTE["hull1"])
        p.vline(0, 0, p.h, PALETTE["steel1"])
        p.vline(p.w - 1, 0, p.h, PALETTE["hull1"])
        for x in range(3, p.w - 3, 6):                 # lid bolts
            p.set(x, 2, PALETTE["steel3"])
            p.set(x, 3, PALETTE["hull2"])

    def cell_face(p: Painter):
        # horizontal cell barrel: 4 shading bands = rounded metal tube
        p.rect(0, 0, p.w, p.h, PALETTE["hull4"])
        p.hline(0, 0, p.w, PALETTE["steel2"])      # shoulder catching the light
        p.hline(1, 0, p.w, PALETTE["steel1"])
        p.hline(2, 0, p.w, PALETTE["hull4"])
        p.hline(3, 0, p.w, PALETTE["hull1"])       # belly in shadow
        for x in range(3, p.w - 3, 5):             # faint machining marks
            p.set(x, 0, PALETTE["steel3"])

    def cell_cap(p: Painter):
        p.rect(0, 0, p.w, p.h, PALETTE["steel2"])
        p.vline(0, 0, p.h, PALETTE["steel3"])
        p.vline(p.w - 1, 0, p.h, PALETTE["hull2"])
        p.hline(0, 0, p.w, PALETTE["steel3"])
        p.hline(p.h - 1, 0, p.w, PALETTE["hull2"])

    I = {
        "front": at.island("front_plate", 14, 8, front_plate),
        "side": at.island("side_plate", 10, 9, side_plate),
        "back": at.island("back_plate", 14, 8, back_plate),
        "cap": at.island("cap_top", 28, 5, cap_top),
        "cell": at.island("cell_face", 25, 4, cell_face),
        "cellcap": at.island("cell_cap", 2, 4, cell_cap),
    }
    S = {k: at.swatch(k) for k in ("hull0", "hull1", "hull2", "hull3", "hull4",
                                   "steel1", "steel2", "steel3",
                                   "bat1", "bat2", "bat3", "inert", "amber2")}

    # ---- shell ---------------------------------------------------------
    B.box("base", (1, 0, 3), (15, 1.2, 13),
          {"*": S["hull2"], "up": S["hull1"], "down": S["hull0"]})
    B.box("body", (1, 1.2, 3), (15, 10.2, 13),
          {"north": I["front"], "south": I["back"], "east": I["side"], "west": I["side"],
           "up": S["hull2"], "down": S["hull1"]})
    B.box("trim_band", (0.9, 9.9, 2.9), (15.1, 10.3, 13.1),
          {"*": S["steel1"], "up": S["steel2"], "down": S["hull1"]},
          note="the metal strap that holds the bank closed")

    # ---- top cap with recessed carry handle ----------------------------
    B.box("cap_n", (1, 10.2, 3), (15, 11.4, 5.5),
          {"*": S["hull3"], "up": I["cap"], "down": S["hull2"], "north": S["steel1"]})
    B.box("cap_s", (1, 10.2, 10.5), (15, 11.4, 13),
          {"*": S["hull3"], "up": I["cap"], "down": S["hull2"], "south": S["steel1"]})
    B.box("cap_w", (1, 10.2, 5.5), (5.0, 11.4, 10.5),
          {"*": S["hull3"], "up": S["hull4"], "down": S["hull2"], "west": S["steel1"]})
    B.box("cap_e", (11.0, 10.2, 5.5), (15, 11.4, 10.5),
          {"*": S["hull3"], "up": S["hull4"], "down": S["hull2"], "east": S["steel1"]})
    B.box("handle_recess", (5.0, 10.2, 5.5), (11.0, 10.75, 10.5),
          {"*": S["hull0"], "up": S["hull1"]}, note="recessed groove (the 'carry' read)")
    B.box("handle_bar", (5.6, 10.7, 6.9), (10.4, 11.15, 7.9),
          {"*": S["steel1"], "up": S["steel2"], "down": S["hull2"]})
    for k, gx in enumerate((6.4, 9.6)):
        B.box(f"handle_grip_{k}", (gx, 11.15, 7.0), (gx + 0.5, 11.35, 7.8),
              {"*": S["steel2"], "up": S["steel3"], "down": S["hull2"]})

    # ---- three horizontal cells ---------------------------------------
    for k, (y0, tag) in enumerate(((8.0, "a"), (5.9, "b"), (3.8, "c"))):
        B.box(f"cell_{tag}", (1.8, y0, 2.35), (14.2, y0 + 1.8, 3.15),
              {"*": S["hull3"], "north": I["cell"], "up": S["hull4"], "down": S["hull1"]},
              note="cell barrel")
        B.box(f"cell_{tag}_spec", (2.2, y0 + 1.46, 2.3), (13.8, y0 + 1.64, 2.7),
              {"*": S["steel1"], "north": S["steel1"], "up": S["steel2"], "down": S["hull1"]},
              note="specular highlight along the top of the barrel")
        for side, x0 in (("w", 1.45), ("e", 13.65)):
            B.box(f"cell_{tag}_cap_{side}", (x0, y0 - 0.05, 2.4), (x0 + 1.0, y0 + 1.85, 3.1),
                  {"*": S["steel1"], "north": I["cellcap"], "up": S["steel2"],
                   "down": S["hull2"], "east": S["steel2"], "west": S["steel2"]})

    # ---- charge gauge: 3 segments, pure colour (glow-ready) ------------
    B.box("gauge_frame", (2.0, 1.55, 2.6), (14.0, 3.45, 3.05),
          {"*": S["hull1"], "north": S["hull1"], "up": S["hull2"], "down": S["hull0"]})
    for k, (x0, x1, col) in enumerate(((2.4, 6.1, "bat2"), (6.5, 10.2, "bat2"), (10.6, 13.6, "bat2"))):
        B.box(f"gauge_seg_{k}", (x0, 1.95, 2.45), (x1, 3.15, 2.95),
              {"*": S[col], "north": S[col]},
              note="GLOW: 1/3, 2/3, full segments - recolour or split into model variants")
    for nm, (y0, y1) in (("gauge_bezel_t", (1.72, 1.98)), ("gauge_bezel_b", (3.12, 3.38))):
        B.box(nm, (2.2, y0, 2.4), (13.8, y1, 3.0),
              {"*": S["steel1"], "north": S["steel1"], "up": S["steel2"], "down": S["hull1"]})
    for k, gx in enumerate((6.25, 10.35)):
        B.box(f"gauge_div_{k}", (gx, 1.9, 2.4), (gx + 0.25, 3.2, 3.0),
              {"*": S["steel1"], "north": S["steel1"]})
    B.box("gauge_led", (2.4, 3.5, 2.5), (3.0, 4.05, 2.9),
          {"*": S["amber2"], "north": S["amber2"]}, note="GLOW amber 'live' dot")

    # ---- side clamps ---------------------------------------------------
    for side, x0 in (("w", 0.45), ("e", 14.65)):
        for k, (y0, y1) in enumerate(((6.4, 7.9), (3.5, 5.0))):
            B.box(f"clamp_{side}{k}", (x0, y0, 4.2), (x0 + 0.9, y1, 9.6),
                  {"*": S["steel2"], "up": S["steel3"], "down": S["hull1"],
                   "east": S["steel2"], "west": S["steel2"]})

    # ---- rear terminals ------------------------------------------------
    B.box("rear_panel", (2.6, 4.6, 13.0), (13.4, 8.4, 13.35),
          {"*": S["hull1"], "south": S["hull2"]})
    for k, tx in enumerate((4.6, 9.4)):
        B.box(f"terminal_{k}", (tx, 8.4, 12.9), (tx + 1.4, 9.3, 13.5),
              {"*": S["steel3"], "up": S["steel3"], "down": S["hull2"], "south": S["steel2"]},
              note="post terminal, reads on the back face")
    return B
