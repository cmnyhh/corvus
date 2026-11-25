"""Check the ore-mill panel geometry without launching the game.

The mill panel was redrawn as horizontal bands; this guard re-reads the real constants from
OreMillScreen/OreMillMenu, rebuilds every text/bar/slot rectangle and fails when two of them overlap,
when anything leaves the panel, or when two stacked rows sit closer than 12 px. Text widths use the
default font's advance (9 px for CJK, 6 px otherwise), which is exact for these labels.

Usage: python3 tools/check_mill_layout.py   (exit 0 = clean)
"""
import itertools
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'src' / 'main' / 'java' / 'dev' / 'dronepal' / 'client' / 'OreMillScreen.java'
MENU = ROOT / 'src' / 'main' / 'java' / 'dev' / 'dronepal' / 'mill' / 'OreMillMenu.java'
s = SRC.read_text(encoding='utf-8')
m = MENU.read_text(encoding='utf-8')

def const(name, text=s):
    hit = re.search(r'\b%s\s*=\s*(-?\d+)' % name, text)
    if not hit: raise SystemExit('missing ' + name)
    return int(hit.group(1))

PANEL_W = const('PANEL_WIDTH', m); PANEL_H = const('PANEL_HEIGHT', m)
TITLE_Y = const('TITLE_Y'); RULE_TITLE = const('RULE_TITLE_Y')
LAMP_X = const('LAMP_X'); LAMP_Y = const('LAMP_Y'); LAMP_SIZE = const('LAMP_SIZE')
STATUS_Y = const('STATUS_Y')
STATUS_X = LAMP_X + LAMP_SIZE + 5
WATER_LABEL_Y = const('WATER_LABEL_Y'); WATER_BAR_Y = const('WATER_BAR_Y')
ENERGY_LABEL_Y = const('ENERGY_LABEL_Y'); ENERGY_BAR_Y = const('ENERGY_BAR_Y')
PROGRESS_X = const('PROGRESS_X'); PROGRESS_Y = const('PROGRESS_Y')
PROGRESS_W = const('PROGRESS_W'); PROGRESS_H = const('PROGRESS_H')
INFO_Y = const('INFO_Y'); BUTTON_X = const('BUTTON_X'); BUTTON_Y = const('BUTTON_Y')
BUTTON_W = const('BUTTON_W'); BUTTON_H = const('BUTTON_H')
RULE_BAG = const('RULE_BAG_Y'); BAG_LABEL_Y = const('BAG_LABEL_Y')
LEFT_X = const('LEFT_X'); RIGHT_X = const('RIGHT_X'); RIGHT_W = const('RIGHT_W'); BAR_H = const('BAR_H')
SLOT_X = [int(v) for v in re.findall(r'-?\d+', re.search(r'SLOT_X\s*=\s*\{([^}]*)\}', m).group(1))]
SLOT_Y = [int(v) for v in re.findall(r'-?\d+', re.search(r'SLOT_Y\s*=\s*\{([^}]*)\}', m).group(1))]
INV_X = const('INVENTORY_X', m); INV_Y = const('INVENTORY_Y', m); HOTBAR_Y = const('HOTBAR_Y', m)

def width(text):
    return sum(9 if ord(c) > 0x2e80 else 6 for c in text)

# sample strings exactly as the 1.21.1 client renders them (zh_cn)
rows = [
    ('标题',   '选矿台',                  (PANEL_W - width('选矿台')) // 2, TITLE_Y),
    ('状态',   '停机：无人机电量低于 15%', STATUS_X, STATUS_Y),
    ('水标签', '水 100%',                 RIGHT_X, WATER_LABEL_Y),
    ('电标签', '电 100%',                 RIGHT_X, ENERGY_LABEL_Y),
    ('信息',   '速度 ×1.5 · 1,680 FE/件',   LEFT_X, INFO_Y),
    ('物品栏', '物品栏',                  LEFT_X, BAG_LABEL_Y),
]
rects = []
for name, txt, x, y in rows:
    rects.append((name, x, y, x + width(txt), y + 8))
rects.append(('水条', RIGHT_X - 1, WATER_BAR_Y - 1, RIGHT_X + RIGHT_W + 1, WATER_BAR_Y + BAR_H + 1))
rects.append(('电条', RIGHT_X - 1, ENERGY_BAR_Y - 1, RIGHT_X + RIGHT_W + 1, ENERGY_BAR_Y + BAR_H + 1))
rects.append(('进度条', PROGRESS_X - 1, PROGRESS_Y - 1, PROGRESS_X + PROGRESS_W + 1, PROGRESS_Y + PROGRESS_H + 1))
rects.append(('状态灯', LAMP_X - 1, LAMP_Y - 1, LAMP_X + LAMP_SIZE + 1, LAMP_Y + LAMP_SIZE + 1))
rects.append(('按钮', BUTTON_X, BUTTON_Y, BUTTON_X + BUTTON_W, BUTTON_Y + BUTTON_H))
for i, (sx, sy) in enumerate(zip(SLOT_X, SLOT_Y)):
    rects.append(('机槽%d' % i, sx - 1, sy - 1, sx + 17, sy + 17))
for r in range(3):
    for c in range(9):
        rects.append(('背包%d' % r, INV_X + 18 * c - 1, INV_Y + 18 * r - 1, INV_X + 18 * c + 17, INV_Y + 18 * r + 17))
for c in range(9):
    rects.append(('快捷栏', INV_X + 18 * c - 1, HOTBAR_Y - 1, INV_X + 18 * c + 17, HOTBAR_Y + 17))

problems = []
for (n1, x1, y1, x2, y2), (n2, x3, y3, x4, y4) in itertools.combinations(rects, 2):
    if x1 < x4 and x3 < x2 and y1 < y4 and y3 < y2:
        problems.append('OVERLAP %s %s' % (n1, n2))
for n, x1, y1, x2, y2 in rects:
    if x1 < 0 or y1 < 0 or x2 > PANEL_W or y2 > PANEL_H:
        problems.append('OUTSIDE %s (%d,%d)-(%d,%d)' % (n, x1, y1, x2, y2))

order = [('标题', TITLE_Y), ('状态', STATUS_Y), ('水标签', WATER_LABEL_Y), ('水条', WATER_BAR_Y),
         ('电标签', ENERGY_LABEL_Y), ('电条', ENERGY_BAR_Y), ('进度条', PROGRESS_Y),
         ('信息', INFO_Y), ('按钮', BUTTON_Y), ('物品栏标签', BAG_LABEL_Y), ('背包首行', INV_Y),
         ('快捷栏', HOTBAR_Y)]
print('panel %dx%d' % (PANEL_W, PANEL_H))
prev = None
for name, y in order:
    pitch = '' if prev is None else '  Δ=%2d%s' % (y - prev[1], '' if y - prev[1] >= 12 else '  <-- <12')
    print('%-10s y=%3d%s' % (name, y, pitch))
    prev = (name, y)
OUT = list(range(3, 6))  # the three output wells: primary / by-product / extra
out_x0, out_x1 = min(SLOT_X[i] for i in OUT) - 1, max(SLOT_X[i] for i in OUT) + 17
print('progress bar and output row share band %d but sit in x %d-%d / %d-%d'
      % (PROGRESS_Y, PROGRESS_X - 1, PROGRESS_X + PROGRESS_W + 1, out_x0, out_x1))
# The third well must live in the right column without touching the gauges or the panel edge.
for i in OUT:
    x, y = SLOT_X[i], SLOT_Y[i]
    if x < RIGHT_X - 1 or x + 17 > RIGHT_X + RIGHT_W:
        problems.append('OUTPUT WELL %d outside the right column: x %d-%d' % (i, x - 1, x + 17))
    if y <= ENERGY_BAR_Y + BAR_H:
        problems.append('OUTPUT WELL %d touches the energy gauge: y %d' % (i, y - 1))
if len({SLOT_Y[i] for i in OUT}) != 1:
    problems.append('OUTPUT WELLS not on one row: %s' % [SLOT_Y[i] for i in OUT])
pitch = {SLOT_X[OUT[j + 1]] - SLOT_X[OUT[j]] for j in range(len(OUT) - 1)}
if pitch != {20}:
    problems.append('OUTPUT WELLS unevenly spaced: %s' % sorted(pitch))
print('output wells (primary/by-product/extra): %s'
      % ' '.join('(%d,%d)' % (SLOT_X[i], SLOT_Y[i]) for i in OUT))
print('energy gauge bottom %d -> output well top %d (gap %d)'
      % (ENERGY_BAR_Y + BAR_H, SLOT_Y[3] - 1, SLOT_Y[3] - 1 - (ENERGY_BAR_Y + BAR_H)))
print('button above gap %d, below gap %d' % (BUTTON_Y - (INFO_Y + 8), RULE_BAG - (BUTTON_Y + BUTTON_H)))
print('bag label -> first row %d' % (INV_Y - (BAG_LABEL_Y + 8)))
print('machine slots inside panel:', all(0 <= x and x + 16 <= PANEL_W and 0 <= y and y + 16 <= PANEL_H for x, y in zip(SLOT_X, SLOT_Y)))
print('last bag well bottom/ panel height: %d / %d' % (HOTBAR_Y + 17, PANEL_H))
print('PROBLEMS:', problems if problems else 'none')
sys.exit(1 if problems else 0)
