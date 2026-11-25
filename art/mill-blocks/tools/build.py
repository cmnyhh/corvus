#!/usr/bin/env python3
"""Build the mill / facility block set: models, textures, bbmodels, preview packs.

Usage:
    python3 build.py [--bb-workspace <path to the blockbench plugin workspace>]

Writes (all inside art/mill-blocks/):
    export/models/block/<name>.json     Java Block/Item models (one per cell)
    export/models/item/<name>.json      item models (parent = block model)
    export/textures/block/<atlas>.png   32x32 atlases
    export/textures/item/<object>.png   32x32 GUI glyphs (one per placeable object)
    <name>.bbmodel                      Blockbench 5 Java Block/Item project
    tools/atlas/<atlas>.json            atlas layout for review
    tools/build-summary.json            cubes / faces / bbox per model
and, with --bb-workspace, throw-away YSM packs used for the Blockbench renders.
"""
from __future__ import annotations

import base64
import json
import os
import sys

import millblocks as MB
import pbgen
from pbgen import Painter, PALETTE, bbmodel_json, java_block_json, java_item_json, write_png, shade

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.dirname(HERE)
NS = "huhu_dronepal"
PAL = PALETTE

# ------------------------------------------------------------------ model table
# name -> (atlas key, builder)
MODELS = [
    ("ore_mill", "ore_mill"),
    ("ore_mill_side", "ore_mill_side"),
    ("water_pump", "water_pump"),
    ("power_tower", "power_tower"),
    ("power_tower_mid", "power_tower_mid"),
    ("power_tower_top", "power_tower_top"),
]
PIPE_VARIANTS = ["straight", "corner", "tee", "cross", "cap"]

# ------------------------------------------------------------------ icons
def icon_mill(p: Painter) -> None:
    p.rect(0, 0, 32, 32, (0, 0, 0, 0))
    # two cells side by side, slight iso flavour: plinth + body + hopper + disc
    p.rect(2, 27, 30, 30, "#0e1013")
    p.rect(3, 23, 29, 28, PAL["hull2"])
    p.rect(4, 24, 28, 25, PAL["steel1"])
    for x in (4, 13, 21, 27):
        p.rect(x, 28, x + 2, 29, PAL["steel1"])
    p.rect(4, 16, 28, 24, PAL["hull3"])
    p.rect(5, 17, 27, 23, PAL["hull2"])
    p.rect(6, 17, 17, 18, PAL["steel2"])
    p.rect(18, 17, 26, 18, PAL["steel2"])
    # left cell: feed funnel with ore
    p.rect(6, 10, 14, 17, PAL["steel2"])
    p.rect(7, 10, 13, 12, PAL["ore0"])
    p.rect(8, 11, 10, 12, PAL["ore1"])
    p.rect(10.5 if False else 10, 11, 12, 13, PAL["ore1"])
    p.rect(7, 13, 8, 16, PAL["steel1"])
    p.rect(12, 13, 13, 16, PAL["steel1"])
    p.rect(9, 14, 11, 16, PAL["hull0"])
    p.rect(9, 15, 11, 16, PAL["amber1"])
    # left cell: observation window on the end wall + handwheel dot
    p.rect(3, 18, 6, 22, PAL["steel1"])
    p.rect(4, 19, 5, 21, PAL["amber1"])
    # the sorting disc spanning both cells (top)
    p.rect(15, 7, 23, 15, PAL["steel3"])
    p.rect(16, 8, 22, 14, PAL["steel2"])
    p.rect(18, 5, 19, 12, PAL["steel3"])
    p.rect(15, 10, 23, 11, PAL["steel3"])
    p.rect(16, 11, 18, 12, PAL["hull3"])
    p.rect(20, 9, 22, 10, PAL["hull3"])
    # right cell: exhaust + service door
    p.rect(24, 6, 28, 17, PAL["hull4"])
    p.rect(25, 4, 27, 7, PAL["steel2"])
    p.rect(25, 3, 27, 4, PAL["steel3"])
    p.rect(18, 19, 26, 23, PAL["steel1"])
    p.rect(19, 20, 25, 22, PAL["hull3"])
    p.rect(24, 20, 25, 21, PAL["mag1"])


def icon_pump(p: Painter) -> None:
    p.rect(0, 0, 32, 32, (0, 0, 0, 0))
    p.rect(3, 27, 29, 30, "#0e1013")
    p.rect(4, 23, 28, 28, PAL["hull2"])
    p.rect(6, 12, 26, 24, PAL["hull3"])
    p.rect(7, 13, 25, 23, PAL["hull2"])
    p.rect(8, 15, 24, 17, PAL["hull4"])
    p.rect(8, 19, 24, 21, PAL["hull4"])
    # volute circle + inlet flange on the left
    p.rect(3, 15, 6, 22, PAL["steel2"])
    p.rect(2, 17, 3, 21, PAL["steel1"])
    p.rect(4, 18, 5, 20, PAL["aqua0"])
    p.rect(27, 15, 29, 22, PAL["steel2"])
    # sight glass
    p.rect(15, 13, 18, 24, PAL["steel1"])
    p.rect(16, 14, 17, 23, PAL["aqua2"])
    for y in (16, 19, 22):
        p.rect(16, y, 17, y + 1, PAL["steel3"])
    # outlet
    p.rect(13, 6, 19, 12, PAL["hull4"])
    p.rect(11, 4, 21, 7, PAL["steel1"])
    p.rect(12, 3, 20, 5, PAL["steel2"])
    p.rect(15, 3, 18, 4, PAL["aqua2"])
    p.rect(21, 9, 26, 12, PAL["hull3"])
    p.rect(22, 10, 25, 11, PAL["steel2"])


def icon_tower(p: Painter) -> None:
    p.rect(0, 0, 32, 32, (0, 0, 0, 0))
    p.rect(4, 28, 28, 31, "#0e1013")
    p.rect(5, 22, 27, 28, PAL["hull2"])
    p.rect(6, 23, 26, 24, PAL["steel1"])
    # base control panel
    p.rect(8, 24, 24, 27, PAL["hull1"])
    p.rect(9, 25, 16, 26, PAL["amber0"])
    p.rect(17, 25, 23, 26, PAL["mag1"])
    # mast
    p.rect(14, 12, 18, 23, PAL["hull3"])
    p.rect(15, 12, 16, 22, PAL["hull2"])
    p.rect(14, 13, 15, 21, PAL["amber1"])
    # coil rings
    p.rect(8, 18, 24, 20, PAL["steel2"])
    p.rect(8, 15, 24, 17, PAL["steel2"])
    p.rect(7, 14, 9, 21, PAL["steel1"])
    p.rect(23, 14, 25, 21, PAL["steel1"])
    # crown + antennas + lamp
    p.rect(10, 9, 22, 12, PAL["steel1"])
    p.rect(11, 7, 21, 10, PAL["hull3"])
    p.rect(7, 4, 8, 10, PAL["steel2"])
    p.rect(24, 4, 25, 10, PAL["steel2"])
    p.rect(15, 2, 17, 8, PAL["steel2"])
    p.rect(13, 1, 19, 4, PAL["lamp"])
    p.rect(14, 4, 18, 5, PAL["steel2"])
    p.rect(12, 1, 13, 2, PAL["amber2"])
    p.rect(19, 1, 20, 2, PAL["amber2"])


def icon_pipe(p: Painter) -> None:
    p.rect(0, 0, 32, 32, (0, 0, 0, 0))
    p.rect(2, 13, 9, 23, PAL["steel1"])
    p.rect(3, 14, 8, 22, PAL["steel2"])
    p.rect(9, 15, 23, 21, PAL["hull3"])
    p.rect(9, 16, 23, 18, PAL["hull4"])
    p.rect(9, 19, 23, 20, PAL["hull2"])
    p.rect(13, 16, 19, 20, PAL["aqua2"])
    p.rect(23, 13, 30, 23, PAL["steel1"])
    p.rect(24, 14, 29, 22, PAL["steel2"])
    p.rect(25, 15, 26, 16, PAL["steel3"])
    p.rect(27, 20, 28, 21, PAL["steel3"])


ICONS = {"ore_mill": icon_mill, "water_pump": icon_pump,
         "power_tower": icon_tower, "water_pipe": icon_pipe}


# ------------------------------------------------------------------ main
def main(argv):
    bb_ws = None
    if "--bb-workspace" in argv:
        bb_ws = argv[argv.index("--bb-workspace") + 1]

    for d in ("export/models/block", "export/models/item", "export/textures/block",
              "export/textures/item", "tools/atlas"):
        os.makedirs(os.path.join(ART, d), exist_ok=True)

    # ---- build geometry -------------------------------------------------
    models = {}
    atlases = {}
    for name, atlas_key in MODELS:
        m = MB.BUILDERS[name]()
        models[name] = m
        atlases[atlas_key] = m.atlas

    pipe_atlas = pbgen.Atlas("water_pipe")
    for v in PIPE_VARIANTS:
        m = MB.build_pipe(pipe_atlas, v)
        models[m.name] = m
    atlases["water_pipe"] = pipe_atlas

    # ---- pack atlases, write textures -----------------------------------
    for key, at in atlases.items():
        at.pack()
        img = at.render()
        write_png(os.path.join(ART, "export", "textures", "block", f"{key}.png"), img)
        layout = {
            "texture": f"{key}.png",
            "size": [pbgen.TEX, pbgen.TEX],
            "islands": {k: {"x": i.x, "y": i.y, "w": i.w, "h": i.h, "uv_units": at.uv("#" + k)}
                        for k, i in at.islands.items()},
            "swatches": {k: {"px": at._placed[k],
                             "size": 4 if k in pbgen.PURE_SWATCHES else 2,
                             "hex": PALETTE[k]}
                         for k in at.swatches},
        }
        with open(os.path.join(ART, "tools", "atlas", f"{key}.json"), "w") as f:
            json.dump(layout, f, indent=1)

    # ---- per model exports ----------------------------------------------
    summary = {}
    for name, m in models.items():
        tex_key = m.atlas.name
        tex = f"{NS}:block/{tex_key}"
        block = java_block_json(m, {"all": tex, "particle": tex}, "minecraft:block/block")
        with open(os.path.join(ART, "export", "models", "block", f"{name}.json"), "w") as f:
            json.dump(block, f, indent=2)
        item = java_item_json(f"{NS}:block/{name}")
        with open(os.path.join(ART, "export", "models", "item", f"{name}.json"), "w") as f:
            json.dump(item, f, indent=2)

        with open(os.path.join(ART, "export", "textures", "block", f"{tex_key}.png"), "rb") as f:
            data_url = "data:image/png;base64," + base64.b64encode(f.read()).decode("ascii")
        bb = bbmodel_json(m, f"{tex_key}.png", f"export/textures/block/{tex_key}.png",
                         f"tex-{tex_key}", data_url)
        with open(os.path.join(ART, f"{name}.bbmodel"), "w") as f:
            json.dump(bb, f, indent=1, ensure_ascii=False)

        st = m.stats()
        summary[name] = {
            "atlas": tex_key,
            "cubes": st["cubes"], "faces": st["faces"], "rotated": st["rotated"],
            "bbox_min": [round(v, 2) for v in st["bbox"][0]],
            "bbox_max": [round(v, 2) for v in st["bbox"][1]],
            "size": [round(st["bbox"][1][i] - st["bbox"][0][i], 2) for i in range(3)],
        }

    # ---- object icons ----------------------------------------------------
    for obj in ("ore_mill", "water_pump", "power_tower", "water_pipe"):
        ip = Painter(32, 32, "#000000")
        ip.px = [[(0, 0, 0, 0) for _ in range(32)] for _ in range(32)]
        ICONS[obj](ip)
        write_png(os.path.join(ART, "export", "textures", "item", f"{obj}.png"), ip.rows())

    with open(os.path.join(ART, "tools", "build-summary.json"), "w") as f:
        json.dump(summary, f, indent=2)

    # ---- preview packs (throw-away, only used to render) ------------------
    if bb_ws:
        scenes = {
            "mill-ore-mill": ([models["ore_mill"], models["ore_mill_side"]],
                              [(0, 0, 0), (16, 0, 0)],
                              "ore mill (2 cells side by side)"),
            "mill-water-pump": ([models["water_pump"]], [(0, 0, 0)], "water pump"),
            "mill-power-tower": ([models["power_tower"], models["power_tower_mid"],
                                  models["power_tower_top"]],
                                 [(0, 0, 0), (0, 16, 0), (0, 32, 0)],
                                 "power tower (3 cells stacked)"),
            "mill-water-pipe": ([models[f"water_pipe_{v}"] for v in PIPE_VARIANTS],
                                [((i - 2) * 22, 0, 0) for i in range(5)],
                                "water pipe connection shapes"),
            "mill-lineup": ([models["ore_mill"], models["ore_mill_side"], models["water_pump"],
                             models["power_tower"], models["power_tower_mid"],
                             models["power_tower_top"]],
                            [(-42, 0, 0), (-26, 0, 0), (0, 0, 0),
                             (30, 0, 0), (30, 16, 0), (30, 32, 0)],
                            "mill block lineup"),
        }
        for key, (ms, offs, title) in scenes.items():
            items = list(zip(ms, offs))
            # unique atlases per scene, in order: each model keeps its own texture column
            pbgen.emit_scene_pack(items, os.path.join(bb_ws, key), title)

    for name, s in summary.items():
        print(f"{name:18s} cubes={s['cubes']:3d} faces={s['faces']:3d} "
              f"rot={s['rotated']:2d} size={s['size']} atlas={s['atlas']}")
    print("total cubes:", sum(s["cubes"] for s in summary.values()),
          " total faces:", sum(s["faces"] for s in summary.values()))
    return summary


if __name__ == "__main__":
    main(sys.argv[1:])
