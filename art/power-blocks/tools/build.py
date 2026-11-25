#!/usr/bin/env python3
"""Build the three Corvus power blocks: models, textures, bbmodels, previews.

Usage:
    python3 build.py [--bb-workspace <path to bb plugin workspace>]

Writes (all inside art/power-blocks/):
    <name>.bbmodel
    export/models/block/<name>.json          Java Block/Item
    export/models/item/<name>.json           item model (parent = block model)
    export/textures/block/<name>.png         32x32 block atlas
    export/textures/item/<name>.png          32x32 inventory glyph (GUI icon)
    tools/atlas/<name>.json                  atlas layout for review
and, when --bb-workspace is given, throw-away YSM packs for Blockbench renders.
"""
from __future__ import annotations

import json
import os
import sys

import blocks
import pbgen
from pbgen import (Atlas, Painter, PALETTE, bbmodel_json, java_block_json,
                   java_item_json, write_png, shade)

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.dirname(HERE)
NS = "huhu_dronepal"

PAL = PALETTE


# --------------------------------------------------------------------- icons
def icon_burner(p: Painter) -> None:
    p.rect(0, 0, 32, 32, (0, 0, 0, 0))
    p.rect(8, 29, 24, 31, "#0e1013")
    p.rect(8, 10, 24, 29, PAL["hull2"])
    p.vline(9, 11, 29, shade(PAL["hull2"], -14))
    p.rect(6, 10, 9, 29, PAL["steel2"])
    p.rect(23, 10, 26, 29, PAL["steel1"])
    p.rect(6, 8, 26, 11, PAL["steel1"])
    p.hline(8, 7, 25, PAL["steel3"])
    p.rect(12, 3, 20, 8, PAL["steel2"])
    p.rect(13, 4, 19, 7, PAL["hull1"])
    p.rect(15, 4, 17, 6, PAL["hull0"])
    p.rect(9, 12, 23, 20, PAL["steel1"])
    p.rect(10, 13, 22, 19, PAL["ember"])
    p.rect(13, 13, 14, 19, PAL["hull1"])
    p.rect(16, 13, 17, 19, PAL["hull1"])
    p.rect(19, 13, 20, 19, PAL["hull1"])
    p.rect(9, 21, 23, 26, PAL["steel2"])
    p.rect(10, 22, 22, 25, PAL["hull0"])
    p.rect(10, 24, 14, 25, PAL["amber0"])
    p.rect(15, 24, 17, 25, PAL["ember"])
    p.hline(26, 9, 23, PAL["steel1"])
    p.rect(20, 9, 22, 10, PAL["amber2"])


def icon_solar(p: Painter) -> None:
    p.rect(0, 0, 32, 32, (0, 0, 0, 0))
    p.rect(3, 26, 29, 29, PAL["hull2"])
    p.rect(4, 29, 28, 30, "#0e1013")
    # panel: parallelogram viewed from above-front, rising to the right
    for i, x in enumerate(range(3, 29)):
        y = 20 - int(round(i * 0.42))
        p.rect(x, y, x + 1, y + 6, PAL["pv1"])
        p.rect(x, y, x + 1, y + 2, PAL["pv2"])
    p.hline(17, 3, 29, PAL["steel1"])
    for x in range(3, 29, 5):
        y = 20 - int(round((x - 3) * 0.42))
        p.rect(x, y, x + 1, y + 7, PAL["steel2"])
    p.rect(15, 20 - 5, 16, 26, PAL["steel1"])
    p.rect(10, 23, 12, 26, PAL["steel1"])
    p.rect(20, 21, 22, 26, PAL["steel1"])
    p.rect(4, 21, 6, 26, PAL["steel2"])


def icon_battery(p: Painter) -> None:
    p.rect(0, 0, 32, 32, (0, 0, 0, 0))
    p.rect(3, 8, 29, 27, PAL["hull2"])
    p.rect(3, 7, 29, 9, PAL["steel1"])
    p.hline(7, 4, 28, PAL["steel3"])
    p.rect(5, 9, 27, 11, PAL["hull1"])
    p.rect(6, 11, 26, 13, PAL["hull4"])
    p.hline(11, 6, 26, PAL["steel1"])
    p.rect(6, 14, 26, 16, PAL["hull4"])
    p.hline(14, 6, 26, PAL["steel1"])
    p.rect(5, 17, 27, 19, PAL["hull1"])
    p.rect(6, 20, 26, 22, PAL["hull4"])
    p.hline(20, 6, 26, PAL["steel1"])
    p.rect(4, 23, 28, 26, PAL["hull1"])
    p.rect(6, 23, 12, 26, PAL["bat2"])
    p.rect(13, 23, 19, 26, PAL["bat2"])
    p.rect(20, 23, 26, 26, PAL["bat2"])
    p.rect(3, 27, 29, 29, "#0e1013")
    p.rect(13, 4, 19, 7, PAL["hull0"])
    p.rect(12, 3, 20, 5, PAL["steel2"])


# --------------------------------------------------------------------- main
def main(argv):
    bb_ws = None
    if "--bb-workspace" in argv:
        bb_ws = argv[argv.index("--bb-workspace") + 1]

    models = [blocks.build_burner(), blocks.build_solar(), blocks.build_battery()]
    summary = {}

    os.makedirs(os.path.join(ART, "export", "models", "block"), exist_ok=True)
    os.makedirs(os.path.join(ART, "export", "models", "item"), exist_ok=True)
    os.makedirs(os.path.join(ART, "export", "textures", "block"), exist_ok=True)
    os.makedirs(os.path.join(ART, "export", "textures", "item"), exist_ok=True)
    os.makedirs(os.path.join(ART, "tools", "atlas"), exist_ok=True)

    icons = {"burner_generator": icon_burner, "solar_generator": icon_solar,
             "battery_bank": icon_battery}

    for m in models:
        m.atlas.pack()
        img = m.atlas.render()
        tex_rel = f"export/textures/block/{m.name}.png"
        write_png(os.path.join(ART, tex_rel), img)

        # --- Java block model
        tex = f"{NS}:block/{m.name}"
        block = java_block_json(m, {"all": tex, "particle": tex}, "minecraft:block/block")
        with open(os.path.join(ART, "export", "models", "block", f"{m.name}.json"), "w") as f:
            json.dump(block, f, indent=2)

        # --- item model
        item = java_item_json(f"{NS}:block/{m.name}")
        with open(os.path.join(ART, "export", "models", "item", f"{m.name}.json"), "w") as f:
            json.dump(item, f, indent=2)

        # --- item glyph
        ip = Painter(32, 32, "#000000")
        ip.px = [[(0, 0, 0, 0) for _ in range(32)] for _ in range(32)]
        icons[m.name](ip)
        write_png(os.path.join(ART, "export", "textures", "item", f"{m.name}.png"), ip.rows())

        # --- bbmodel (Java Block/Item project), texture embedded so it opens self-contained
        import base64
        with open(os.path.join(ART, tex_rel), "rb") as f:
            data_url = "data:image/png;base64," + base64.b64encode(f.read()).decode("ascii")
        bb = bbmodel_json(m, f"{m.name}.png", tex_rel, f"tex-{m.name}", data_url)
        with open(os.path.join(ART, f"{m.name}.bbmodel"), "w") as f:
            json.dump(bb, f, indent=1)

        # --- atlas layout report
        layout = {
            "texture": f"{m.name}.png",
            "size": [32, 32],
            "islands": {k: {"x": i.x, "y": i.y, "w": i.w, "h": i.h,
                            "uv_units": m.atlas.uv("#" + k)}
                        for k, i in m.atlas.islands.items()},
            "swatches": {k: {"px": m.atlas._placed[k],
                             "size": 4 if k in pbgen.PURE_SWATCHES else 2,
                             "hex": PALETTE[k]}
                         for k in m.atlas.swatches},
        }
        with open(os.path.join(ART, "tools", "atlas", f"{m.name}.json"), "w") as f:
            json.dump(layout, f, indent=1)

        st = m.stats()
        summary[m.name] = {
            "cubes": st["cubes"], "faces": st["faces"], "rotated": st["rotated"],
            "bbox_min": [round(v, 2) for v in st["bbox"][0]],
            "bbox_max": [round(v, 2) for v in st["bbox"][1]],
            "size": [round(st["bbox"][1][i] - st["bbox"][0][i], 2) for i in range(3)],
            "texture": tex_rel,
            "islands": len(m.atlas.islands),
            "swatches": len(m.atlas.swatches),
        }

        if bb_ws:
            pbgen.emit_preview_pack(m, os.path.join(bb_ws, m.name), img, f"preview {m.name}")

    if bb_ws:
        pbgen.emit_lineup_pack(models,
                               os.path.join(bb_ws, "lineup"),
                               [m.atlas.render() for m in models],
                               "power-block lineup")

    with open(os.path.join(ART, "tools", "build-summary.json"), "w") as f:
        json.dump(summary, f, indent=2)

    for name, s in summary.items():
        print(f"{name:18s} cubes={s['cubes']:3d} faces={s['faces']:3d} "
              f"rotated={s['rotated']:2d} size={s['size']} islands={s['islands']} swatches={s['swatches']}")
    print("total cubes:", sum(s["cubes"] for s in summary.values()),
          "total faces:", sum(s["faces"] for s in summary.values()))
    return summary


if __name__ == "__main__":
    main(sys.argv[1:])
