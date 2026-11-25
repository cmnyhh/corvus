#!/usr/bin/env python3
"""Corvus 机甲原型机 · BLOCKOUT 生成器（只定比例/关节/驾驶舱/核心槽位置）。

不画细节、不做逐面 UV 图案：每个面都指向 64x64 图集上的一个纯色 4x4 swatch，
所以任何拉伸都看不出来，纯粹用来看剪影与关节。

坐标约定（与 dronepal 现有机体 geo 完全一致）：
  Y 向上，-Z 为前（正面朝北），16 单位 = 1 方块。
  地面 y=0；整机高 48u = 3 格；肩宽 32u = 2 格。

输出：
  <plugin workspace>/models/togawa-sakiko/mecha/{ysm.json,models/main.json,textures/main.png}
  以及 tools/blockout-report.json（骨骼/方块/包围盒统计）
"""
from __future__ import annotations

import json
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.dirname(HERE)
PACK = os.path.join(
    "/Users/lyk/Documents/deepseek-harness/default-workspace/blockbench-ysm-agent",
    "models", "togawa-sakiko", "mecha",
)

TS = 64
CELL = 4
FACES = ("north", "south", "east", "west", "up", "down")

# ---- 纯色 swatch（blockout 用，只有明度区分，不画花纹） --------------------
COLORS = [
    ("shell",   "#4a4f57"),   # 主壳 深灰
    ("shellD",  "#33373d"),   # 深灰暗部
    ("metal",   "#8d949e"),   # 金属边框
    ("metalD",  "#666d76"),   # 金属暗
    ("dark",    "#16181c"),   # 内构 / 凹槽
    ("amber",   "#ffb43c"),   # 发光：驾驶舱视窗 / 眼灯
    ("magenta", "#ff2f8e"),   # 发光：推进器尾焰口
    ("blue",    "#35d0ff"),   # 发光：核心插块（peregrine 占位）
    ("mid",     "#6a707a"),   # 中灰过渡
    ("glass",   "#2b1a0c"),   # 驾驶舱内部（深琥珀黑）
    ("off",     "#2e2a22"),   # 挂点指示灯（空置：暗）
]
SW = {name: i for i, (name, _) in enumerate(COLORS)}

BONES: list[dict] = []
CUBES: list[dict] = []


def bone(name: str, pivot, parent: str | None = None) -> dict:
    b = {"name": name, "pivot": [round(v, 3) for v in pivot]}
    if parent:
        b["parent"] = parent
    b["cubes"] = []
    BONES.append(b)
    return b


def cube(bone_name: str, lo, hi, color: str, rot=None, pivot=None) -> dict:
    """lo/hi = 两个对角点（世界坐标，bind pose）。"""
    origin = [min(lo[i], hi[i]) for i in range(3)]
    size = [abs(hi[i] - lo[i]) for i in range(3)]
    s = SW[color]
    uv = {f: {"uv": [s * CELL, 0], "uv_size": [CELL, CELL]} for f in FACES}
    c = {
        "origin": [round(v, 3) for v in origin],
        "size": [round(v, 3) for v in size],
        "uv": uv,
    }
    if rot is not None:
        c["rotation"] = [round(v, 3) for v in rot]
        c["pivot"] = [round(v, 3) for v in (pivot or [0, 0, 0])]
    CUBES.append({"bone": bone_name, "cube": c})
    return c


def mirror_x(lo, hi):
    return [-hi[0], lo[1], lo[2]], [-lo[0], hi[1], hi[2]]


# =========================== 骨架 ==========================================
bone("root", (0, 0, 0))
bone("hip", (0, 26, 0), "root")
bone("torso", (0, 30, 0), "hip")
bone("cockpit", (0, 36, -5), "torso")
bone("core_socket", (0, 32, -6), "torso")
bone("core_hatch_l", (-6.6, 32.3, -6.8), "core_socket")
bone("core_hatch_r", (6.6, 32.3, -6.8), "core_socket")
bone("core_plug", (0, 32, -6), "core_socket")
bone("shoulder_l", (-10, 37.5, 0), "torso")
bone("shoulder_r", (10, 37.5, 0), "torso")
bone("arm_l", (-11.4, 37, -0.8), "shoulder_l")
bone("arm_r", (11.4, 37, -0.8), "shoulder_r")
bone("mount_hand_l", (-13.3, 18.4, -3.8), "arm_l")
bone("mount_hand_r", (13.3, 18.4, -3.8), "arm_r")
bone("mount_back", (0, 37.8, 9.5), "torso")
bone("thruster_l", (-6, 34, 8), "torso")
bone("thruster_r", (6, 34, 8), "torso")
bone("vent_l", (-10, 35, 4), "torso")
bone("vent_r", (10, 35, 4), "torso")
bone("head_ring", (0, 40, 0), "torso")
bone("antenna", (0, 46, 2), "head_ring")
bone("leg_l_upper", (-6.5, 26, 0), "hip")
bone("leg_l_lower", (-6.5, 14, 0), "leg_l_upper")
bone("foot_l", (-6.5, 3, 0), "leg_l_lower")
bone("leg_r_upper", (6.5, 26, 0), "hip")
bone("leg_r_lower", (6.5, 14, 0), "leg_r_upper")
bone("foot_r", (6.5, 3, 0), "leg_r_lower")

# =========================== 髋 / 骨盆 =====================================
cube("hip", (-8, 26, -5), (8, 30, 5), "shell")
cube("hip", (-10, 26.5, -4), (-8, 29.5, 4), "metal")
cube("hip", (8, 26.5, -4), (10, 29.5, 4), "metal")
cube("hip", (-3, 24.5, -6.2), (3, 27.8, -4), "shellD")          # 裆甲

# =========================== 躯干 =========================================
cube("torso", (-6, 30, -5), (6, 32.6, 5), "shellD")             # 腰
cube("torso", (-9, 32, -6), (9, 36, 6), "shell")                # 下胸
cube("torso", (-10, 36, -6.5), (10, 40, 6), "shell")            # 上胸
cube("torso", (-11.2, 34, -5), (-9.9, 39.4, 5), "metal")        # 侧装甲 L
cube("torso", (9.9, 34, -5), (11.2, 39.4, 5), "metal")          # 侧装甲 R
cube("torso", (-5.2, 39.4, -5.2), (5.2, 41, 5.2), "metal")      # 领口
cube("torso", (-7, 32, 6), (7, 38.5, 8.6), "shellD")            # 背包

# =========================== 驾驶舱（torso 上部朝前） ======================
cube("cockpit", (-6.8, 35.2, -8.2), (-4.2, 40, -5), "metal")     # 左侧框
cube("cockpit", (4.2, 35.2, -8.2), (6.8, 40, -5), "metal")       # 右侧框
cube("cockpit", (-6.8, 39.2, -8.2), (6.8, 40, -5), "metal")      # 上框（眉檐）
cube("cockpit", (-6.8, 35.2, -8.2), (6.8, 36.4, -5), "shellD")   # 下沿/下巴
# 挡风玻璃：绕下沿后倾 12°，像座舱盖而不是灯带
cube("cockpit", (-4.2, 36.4, -7.95), (4.2, 39.2, -7.55), "amber",
     rot=(12, 0, 0), pivot=(0, 36.4, -7.9))                       # 视窗（发光）
cube("cockpit", (-4.4, 36.5, -7.5), (4.4, 39.0, -5.4), "glass")  # 舱内暗腔
cube("cockpit", (-1.5, 36.6, -7.3), (1.5, 38.2, -6.6), "dark")   # 座舱内座椅/人影占位

# =========================== 胸口核心槽 ====================================
cube("core_socket", (-5.6, 33.6, -7.6), (5.6, 35.0, -5.4), "metal")   # 上框
cube("core_socket", (-5.6, 28.8, -7.6), (5.6, 30.2, -5.4), "metal")  # 下框
cube("core_socket", (-6.4, 28.8, -7.6), (-5.6, 35.0, -5.4), "metal") # 左框
cube("core_socket", (5.6, 28.8, -7.6), (6.4, 35.0, -5.4), "metal")   # 右框
cube("core_socket", (-5.2, 30.1, -6.2), (5.2, 34, -5.2), "dark")    # 槽底
cube("core_socket", (-5.2, 30.1, -7.5), (-4.4, 33.6, -5.8), "dark")  # 槽壁 L
cube("core_socket", (4.4, 30.1, -7.5), (5.2, 33.6, -5.8), "dark")    # 槽壁 R
cube("core_socket", (-4.4, 30.1, -7.5), (4.4, 30.7, -5.8), "dark")   # 槽底台阶
# 盖板：左右各一片滑门（空置=滑到两侧，槽口敞开；动画里向内滑即可闭合）
cube("core_socket", (-9.8, 29.9, -7.1), (9.8, 30.5, -6.5), "dark")      # 滑轨
cube("core_hatch_l", (-9.6, 30.6, -7.15), (-6.6, 34.2, -6.45), "shell")
cube("core_hatch_l", (-9.6, 33.8, -7.15), (-6.6, 34.2, -6.45), "metal")
cube("core_hatch_r", (6.6, 30.6, -7.15), (9.6, 34.2, -6.45), "shell")
cube("core_hatch_r", (6.6, 33.8, -7.15), (9.6, 34.2, -6.45), "metal")
# 插块（peregrine 占位，四套核心共用这个槽位）
cube("core_plug", (-3.9, 30.9, -7.15), (3.9, 33.5, -6.5), "blue")

# =========================== 肩 / 臂 ======================================
for side, sgn in (("l", -1), ("r", 1)):
    sh = f"shoulder_{side}"
    lo, hi = sorted([sgn * 10, sgn * 16.2])
    cube(sh, (-15.7 if sgn < 0 else 10, 33.2, -5.6),
         (-10 if sgn < 0 else 15.7, 39.6, 5.6), "shell")
    cube(sh, (-15.1 if sgn < 0 else 10, 39.6, -4.4),
         (-10 if sgn < 0 else 15.1, 40.8, 4.4), "metal")
    # 肩部挂点：右肩略外凸的深色接口（先留几何，不建模武器）
    if side == "r":
        cube(sh, (14.2, 35.4, -2.4), (15.9, 38.4, 2.4), "dark")
        cube(sh, (15.7, 34.6, -3.2), (16.4, 39.2, 3.2), "metalD")
    else:
        cube(sh, (-15.9, 35.4, -2.4), (-14.2, 38.4, 2.4), "metalD")

    ar = f"arm_{side}"
    x0, x1 = (-15.2, -11.4) if sgn < 0 else (11.4, 15.2)
    cube(ar, (x0, 28, -4.2), (x1, 36.6, 2.6), "shell")            # 上臂
    cube(ar, (x0 + 0.3, 26, -3.8), (x1 - 0.3, 28.4, 2.2), "metal")  # 肘
    cube(ar, (x0, 20, -4.6), (x1, 26, 3.0), "shellD")             # 前臂
    cube(ar, (x0 + 0.2, 16.8, -3.8), (x1 - 0.2, 20, 2.2), "dark")  # 拳
    # ---- 手部挂点：掌心朝前的标准接口环（空置：暗灯）----
    mt = f"mount_hand_{side}"
    cube(mt, (sgn * 11.1, 19.2, -4.5), (sgn * 15.5, 20.4, -3.8), "metal")   # 环·上
    cube(mt, (sgn * 11.1, 16.4, -4.5), (sgn * 15.5, 17.6, -3.8), "metal")   # 环·下
    cube(mt, (sgn * 11.1, 17.6, -4.5), (sgn * 12.3, 19.2, -3.8), "metal")   # 环·内
    cube(mt, (sgn * 14.3, 17.6, -4.5), (sgn * 15.5, 19.2, -3.8), "metal")   # 环·外
    cube(mt, (sgn * 12.3, 17.6, -4.05), (sgn * 14.3, 19.2, -3.6), "dark")   # 接口凹口
    cube(mt, (sgn * 12.9, 16.8, -4.25), (sgn * 13.7, 17.5, -3.95), "off")   # 指示灯（灭）

# =========================== 背部挂点（肩胛之间） =========================
cube("mount_back", (-9, 37, 8.8), (9, 38.6, 10.2), "metal")             # 横向导轨
cube("mount_back", (-6.6, 36.4, 8.6), (-5, 39.2, 10.8), "metalD")       # 锁定卡扣 L
cube("mount_back", (5, 36.4, 8.6), (6.6, 39.2, 10.8), "metalD")         # 锁定卡扣 R
cube("mount_back", (-1.1, 38.7, 9), (1.1, 39.7, 10), "dark")            # 线缆接口（激光供电）
cube("mount_back", (-0.6, 39.6, 9.2), (0.6, 40.1, 9.8), "off")          # 指示灯（灭）

# =========================== 腿 / 脚 ======================================
for side in ("l", "r"):
    sgn = -1 if side == "l" else 1
    up, low, ft = f"leg_{side}_upper", f"leg_{side}_lower", f"foot_{side}"
    xa, xb = (10.2, 3.2) if sgn < 0 else (-3.2, -10.2)
    lo, hi = (min(xa, xb), max(xa, xb))
    cube(up, (lo, 14.4, -4.4), (hi, 26, 4.4), "shell")            # 大腿
    cube(up, (lo + 0.6, 25.2, -3.4), (hi - 0.6, 26.8, 3.4), "metal")  # 髋关节
    cube(low, (lo - 0.4, 12.4, -4.8), (hi + 0.4, 14.6, 4.2), "metal")  # 膝
    cube(low, (lo + 0.4, 4, -4.2), (hi - 0.4, 12.2, 4.2), "shell")     # 小腿
    cube(low, (lo + 0.5, 7.4, 4.2), (hi - 0.5, 11.6, 5.4), "shellD")   # 小腿肚
    # 脚：宽厚站姿（占地 8u 宽 × 13u 深）
    fa, fb = (11.4, 2.6) if sgn < 0 else (-2.6, -11.4)
    flo, fhi = (min(fa, fb), max(fa, fb))
    cube(ft, (flo + 1.1, 3.0, -2.6), (fhi - 1.1, 5.0, 2.6), "metal")   # 踝
    cube(ft, (flo, 0, -6.4), (fhi, 3.6, 4.6), "shell")                 # 脚掌
    cube(ft, (flo + 1.3, 0, -8.4), (fhi - 1.3, 1.4, -6.4), "metalD")   # 前趾
    cube(ft, (flo + 0.6, 0, 4.6), (fhi - 0.6, 2.2, 5.8), "shellD")     # 脚跟

# =========================== 推进器 / 散热口 ==============================
for side, sgn in (("l", -1), ("r", 1)):
    th = f"thruster_{side}"
    thl, thh = sorted([sgn * 9.2, sgn * 3.4])
    cube(th, (thl, 30.4, 8.2), (thh, 36.2, 11.2), "shellD")        # 推进器壳
    cube(th, (thl + 0.7, 29.4, 8.7), (thh - 0.7, 30.4, 10.7), "magenta")  # 尾焰口（发光）
    vt = f"vent_{side}"
    vl, vh = sorted([sgn * 12.5, sgn * 11.2])
    cube(vt, (vl, 33.2, 0.6), (vh, 37.8, 5.0), "dark")             # 侧散热口

# =========================== 头环 / 天线 ==================================
cube("head_ring", (-3, 40, -3), (3, 41.6, 3), "dark")              # 颈
cube("head_ring", (-6, 45, -5), (6, 46.2, 5), "metal")             # 上环
cube("head_ring", (-6, 41.6, -5), (6, 42.6, 5), "metal")           # 下环
cube("head_ring", (-6, 41.6, -5), (-4.4, 46.2, 5), "metal")        # 左立柱
cube("head_ring", (4.4, 41.6, -5), (6, 46.2, 5), "metal")          # 右立柱
cube("head_ring", (-4.4, 42.6, -4.2), (4.4, 45, 0.4), "dark")      # 面部暗腔
cube("head_ring", (-4.2, 43.2, -4.7), (4.2, 44.6, -4.2), "amber")  # 眼灯（发光）
cube("head_ring", (-4.4, 42.6, 0.4), (4.4, 45, 4.2), "shellD")     # 后脑壳
cube("antenna", (-1.2, 46.2, 1.2), (1.2, 47.2, 3.4), "metal")      # 天线座
cube("antenna", (-0.7, 47.2, 1.8), (0.7, 48, 2.8), "shell")        # 天线杆（短，blockout 占位）

# =========================== 组装 JSON ====================================
by_bone = {}
for entry in CUBES:
    by_bone.setdefault(entry["bone"], []).append(entry["cube"])
for b in BONES:
    if b["name"] in by_bone:
        b["cubes"] = by_bone[b["name"]]
    else:
        b.pop("cubes")

model = {
    "format_version": "1.12.0",
    "minecraft:geometry": [{
        "description": {
            "identifier": "geometry.corvus.mecha",
            "texture_width": TS,
            "texture_height": TS,
            "visible_bounds_width": 2.6,
            "visible_bounds_height": 3.4,
            "visible_bounds_offset": [0, 1.7, 0],
        },
        "bones": BONES,
    }],
}

os.makedirs(os.path.join(PACK, "models"), exist_ok=True)
os.makedirs(os.path.join(PACK, "textures"), exist_ok=True)
with open(os.path.join(PACK, "models", "main.json"), "w", encoding="utf-8") as f:
    json.dump(model, f, ensure_ascii=False, indent=1)
with open(os.path.join(PACK, "ysm.json"), "w", encoding="utf-8") as f:
    json.dump({"spec": 2, "metadata": {"name": "Corvus mecha blockout"},
               "files": {"player": {"model": {"main": "models/main.json"},
                                    "texture": ["textures/main.png"]}}},
              f, ensure_ascii=False, indent=1)

# 贴图：64x64，一行 4x4 纯色 swatch
img = Image.new("RGBA", (TS, TS), (0, 0, 0, 0))
px = img.load()
for i, (_name, hexv) in enumerate(COLORS):
    r = int(hexv[1:3], 16); g = int(hexv[3:5], 16); b = int(hexv[5:7], 16)
    for y in range(0, CELL):
        for x in range(i * CELL, i * CELL + CELL):
            px[x, y] = (r, g, b, 255)
img.save(os.path.join(PACK, "textures", "main.png"))

# ---- 统计（写报告） -------------------------------------------------------
mins = [1e9] * 3
maxs = [-1e9] * 3
faces = 0
for entry in CUBES:
    c = entry["cube"]
    faces += len(c["uv"])
    for i in range(3):
        mins[i] = min(mins[i], c["origin"][i])
        maxs[i] = max(maxs[i], c["origin"][i] + c["size"][i])
size = [round(maxs[i] - mins[i], 2) for i in range(3)]
report = {
    "bones": len(BONES),
    "bone_names": [b["name"] for b in BONES],
    "cubes": len(CUBES),
    "faces": faces,
    "bbox_min": [round(v, 2) for v in mins],
    "bbox_max": [round(v, 2) for v in maxs],
    "size_units": size,
    "size_blocks": [round(v / 16, 3) for v in size],
    "per_bone_cubes": {b: len(v) for b, v in sorted(by_bone.items())},
}
with open(os.path.join(HERE, "blockout-report.json"), "w", encoding="utf-8") as f:
    json.dump(report, f, ensure_ascii=False, indent=1)

print(json.dumps(report, ensure_ascii=False, indent=1))
print("pack ->", PACK)
