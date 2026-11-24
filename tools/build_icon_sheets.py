"""Assemble review sheets for the batch-generated icons, one sheet per doc section.

Each cell shows the icon at 8x on the same dark background the game uses, with its
Chinese name underneath, so a whole section can be judged at a glance instead of
opening 20 files.
"""
from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/huhu_dronepal/textures"
ART = ROOT / "artifacts/icons-batch"
SHEETS = ART / "sheets"

BOLD = "/System/Library/Fonts/Hiragino Sans GB.ttc"
CELL, LABEL_H, COLS, PAD = 112, 30, 7, 12


def font(size: int) -> ImageFont.FreeTypeFont:
    try:
        return ImageFont.truetype(BOLD, size)
    except OSError:
        return ImageFont.load_default()


def load_rows() -> list[dict]:
    """Rows come from the doc itself; whatever file exists on disk counts as done."""
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import gen_icons_batch as G

    out = []
    for r in G.parse_rows():
        if r["section"] in G.SKIP_SECTIONS:
            continue
        if not (ASSETS / r["rel"]).exists():
            continue
        out.append({"name": r["name"], "rel": r["rel"], "section": r["section"]})
    return out


def main() -> int:
    SHEETS.mkdir(parents=True, exist_ok=True)
    rows = load_rows()
    sections: dict[str, list[dict]] = {}
    for r in rows:
        sections.setdefault(r["section"], []).append(r)

    f = font(15)
    title_f = font(22)
    for section, items in sections.items():
        cols = min(COLS, max(1, len(items)))
        lines = (len(items) + cols - 1) // cols
        w = PAD + cols * (CELL + PAD)
        h = PAD + 34 + lines * (CELL + LABEL_H + PAD)
        sheet = Image.new("RGBA", (w, h), (26, 27, 31, 255))
        d = ImageDraw.Draw(sheet)
        d.text((PAD, 8), f"{section}　{len(items)} 张", font=title_f, fill=(235, 236, 245))

        for i, item in enumerate(items):
            path = ASSETS / item["rel"]
            if not path.exists():
                continue
            icon = Image.open(path).convert("RGBA")
            s = min(CELL // icon.width, CELL // icon.height) or 1
            big = icon.resize((icon.width * s, icon.height * s), Image.NEAREST)
            cx = PAD + (i % cols) * (CELL + PAD)
            cy = PAD + 34 + (i // cols) * (CELL + LABEL_H + PAD)
            box = (cx, cy, cx + CELL, cy + CELL)
            d.rectangle(box, fill=(38, 40, 46, 255), outline=(58, 60, 68, 255))
            sheet.alpha_composite(big, (cx + (CELL - big.width) // 2, cy + (CELL - big.height) // 2))
            name = item["name"]
            d.text((cx + 2, cy + CELL + 6), name[:16], font=f, fill=(205, 208, 218))

        safe = section.replace("/", "_").replace(" ", "")
        out = SHEETS / f"{safe}.png"
        sheet.save(out)
        print("sheet ->", out.relative_to(ROOT), f"({len(items)})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
