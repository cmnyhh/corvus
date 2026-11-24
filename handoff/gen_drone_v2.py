#!/usr/bin/env python3
"""Dronepal 无人机 v2 · 按概念图（宽扁悬停机）重建体素草模。

结构分解（对应参考图）：
  1. 宽扁主机身：叠层平板 + 前缘倒角（用阶梯宽度伪造斜切）
  2. 左右侧舱：各带一个「正面朝前的品红环形推进器」
  3. 顶部：立柱 + 圆柱形传感舱（正面品红环）
  4. 正脸：中央凹槽 + 一排 4 个镜头
  5. 侧面：长条品红灯带；橙色警示小条
  6. 尾部：两个推进筒
  7. 底部：滑撬

坐标：16 单位 = 1 方块；-Z 为前；+Y 上。
"""
import json, math, os, zlib, struct

TS = 256
SCALE = 1.0
BASE = os.path.join(os.path.dirname(__file__), "..", "blockbench-ysm-agent", "models", "dronepal", "pack")
OUT_MODEL = os.path.join(BASE, "models", "main.json")
OUT_TEX = os.path.join(BASE, "textures", "default.png")

COLUMNS, PALETTE = [], {}
FACES = ("north", "south", "east", "west", "up", "down")


def hexc(s):
    s = s.lstrip("#")
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


def add_column(name, top, bottom, steps=16):
    x = len(COLUMNS) * 2
    col = [tuple(round(hexc(top)[k] + (hexc(bottom)[k] - hexc(top)[k]) * i / (steps - 1))
                 for k in range(3)) for i in range(steps)]
    COLUMNS.append((x, col))
    PALETTE[name] = x


add_column("hull", "#6a6f78", "#2a2d33")      # 深灰主壳
add_column("plate", "#cdd2d9", "#7c828c")     # 亮灰装甲板
add_column("plateD", "#9aa0a9", "#4e545d")    # 中灰板
add_column("dark", "#2b2e34", "#101216")      # 内构/凹槽
add_column("mag", "#ff8fc4", "#ff1d86")       # 品红发光
add_column("magD", "#ff3d9c", "#8c0a45")      # 品红暗
add_column("orange", "#ffb44d", "#b25a00")    # 橙色警示
add_column("steel", "#e6eaf0", "#8a919c")     # 金属亮

cubes = []


def cube(bone, origin, size, color):
    cubes.append({"bone": bone, "cube": {
        "origin": [round(v, 3) for v in origin],
        "size": [round(v, 3) for v in size],
        "uv": {f: {"uv": [PALETTE[color], 0], "uv_size": [1, 16]} for f in FACES},
    }})


def ring_yz(bone, cx, cy, cz, r, thick, color, seg=10, facing="front"):
    """在 Y-Z 平面画一圈小方块（朝前的圆环）。"""
    for i in range(seg):
        a = 2 * math.pi * i / seg
        y = cy + math.cos(a) * r
        z = cz + math.sin(a) * r
        cube(bone, [cx - thick / 2, y - thick / 2, z - thick / 2], [thick, thick, thick], color)


def ring_xz(bone, cx, cy, cz, r, thick, color, seg=12):
    """水平圆环（顶部传感舱的环）。"""
    for i in range(seg):
        a = 2 * math.pi * i / seg
        x = cx + math.cos(a) * r
        z = cz + math.sin(a) * r
        cube(bone, [x - thick / 2, cy - thick / 2, z - thick / 2], [thick, thick, thick], color)


# ================================================================ 主机身（叠层 + 前端倒角）
# 底层（最宽）
cube("body", [-12, 0, -9], [24, 2.2, 18], "hull")
# 中层
cube("body", [-11.4, 2.2, -9.4], [22.8, 2.4, 17.6], "hull")
# 亮灰装甲盖（略窄、略短）
cube("body", [-9.8, 4.6, -8.4], [19.6, 1.8, 15.6], "plate")
# 中灰过渡层
cube("body", [-10.6, 4.2, -8.9], [21.2, 0.5, 16.6], "plateD")
# 前缘倒角：3 级递减宽度
cube("body", [-8.6, 1.0, -10.4], [17.2, 3.0, 1.6], "plateD")
cube("body", [-6.6, 1.4, -11.2], [13.2, 2.4, 1.0], "plate")
cube("body", [-4.4, 1.8, -11.8], [8.8, 1.8, 0.8], "steel")
# 尾部倒角
cube("body", [-9.0, 1.0, 9.0], [18.0, 2.6, 1.4], "plateD")

# ================================================================ 正脸：中央凹槽 + 4 个镜头
cube("body", [-5.6, 1.4, -11.4], [11.2, 3.6, 0.9], "dark")
for i, x in enumerate((-4.2, -1.5, 1.5, 4.2)):
    cube("body", [x - 1.1, 2.2, -12.0], [2.2, 2.2, 0.8], "dark")
    cube("body", [x - 0.7, 2.6, -12.4], [1.4, 1.4, 0.5], "mag")
# 凹槽上下的亮边
cube("body", [-5.8, 5.0, -11.5], [11.6, 0.4, 1.0], "plate")
cube("body", [-5.8, 0.9, -11.5], [11.6, 0.4, 1.0], "plateD")

# ================================================================ 侧灯带 + 橙色警示
for sx in (-1, 1):
    x0 = 11.4 if sx > 0 else -11.8
    cube("body", [x0, 2.6, -8.6], [0.4, 0.7, 15.6], "mag")
    cube("body", [sx * 5.2 - (0.5 if sx > 0 else 0), 4.7, -8.0], [1.0, 0.35, 2.4], "orange")
    cube("body", [sx * 7.6 - (0.4 if sx > 0 else 0), 4.7, -5.0], [0.8, 0.35, 1.8], "orange")

# ================================================================ 左右侧舱 + 环形推进器（朝前）
for sx in (-1, 1):
    bone = "pod_r" if sx > 0 else "pod_l"
    x0 = 11.2 if sx > 0 else -15.6
    cube(bone, [x0, 0.4, -6.4], [4.4, 5.2, 12.4], "hull")
    cube(bone, [x0 + (0.5 if sx > 0 else 0), 5.4, -5.8], [3.4, 0.7, 11.2], "plateD")
    # 舱体正面的凹陷 + 品红圆环
    cx = sx * 13.4
    cube(bone, [cx - 2.0, 2.4, -7.0], [4.0, 4.0, 0.8], "dark")
    ring_yz(bone, cx, 4.4, -7.3, 1.45, 0.5, "mag", seg=10)
    cube(bone, [cx - 0.7, 3.7, -7.4], [1.4, 1.4, 0.5], "magD")
    # 侧舱外侧的橙色小条
    cube(bone, [sx * 15.4 - (0.35 if sx > 0 else 0), 1.4, -3.0], [0.35, 0.8, 3.0], "orange")

# ================================================================ 顶部立柱 + 传感舱
cube("body", [-1.6, 6.4, -2.4], [3.2, 2.4, 3.2], "dark")
cube("sensor", [-4.2, 8.8, -3.6], [8.4, 3.0, 6.4], "plateD")
cube("sensor", [-3.6, 11.8, -3.0], [7.2, 0.8, 5.2], "plate")
# 传感舱正面：镜头 + 环
cube("sensor", [-1.8, 9.6, -4.2], [3.6, 2.0, 0.9], "dark")
cube("sensor", [-1.1, 10.1, -4.6], [2.2, 1.1, 0.6], "mag")
ring_yz("sensor", 0.0, 10.6, -4.4, 2.6, 0.42, "mag", seg=14)
# 顶部橙色条
cube("sensor", [-2.4, 12.6, -1.0], [1.6, 0.3, 2.6], "orange")

# ================================================================ 尾部推进筒
for sx in (-1, 1):
    cx = sx * 12.6
    cube("body", [cx - 1.8, 1.2, 9.2], [3.6, 3.4, 3.2], "hull")
    cube("body", [cx - 1.3, 1.7, 12.2], [2.6, 2.4, 0.8], "dark")
    ring_yz("body", cx, 2.9, 12.5, 0.95, 0.35, "mag", seg=8)

# ================================================================ 底部滑撬
cube("bottom", [-6.0, -1.6, -3.0], [12.0, 1.2, 7.0], "plateD")
cube("bottom", [-4.6, -2.4, -2.2], [9.2, 0.9, 5.4], "hull")
for sx in (-1, 1):
    cube("bottom", [sx * 3.4 - (0.5 if sx > 0 else 0), -1.6, -1.0], [1.0, 0.6, 3.0], "steel")

# ================================================================ 骨骼
def bone(name, parent=None, pivot=(0, 0, 0)):
    b = {"name": name, "pivot": [round(v, 3) for v in pivot]}
    if parent:
        b["parent"] = parent
    return b


bones = [
    bone("root"),
    bone("body", "root"),
    bone("pod_l", "body"),
    bone("pod_r", "body"),
    bone("sensor", "body", (0, 8.8, -0.4)),
    bone("gimbal", "bottom"),          # 云台挂在底部
    bone("gun_mount", "gimbal", (0, -2.4, -4.0)),
    bone("bottom", "root"),
]
for b in bones:
    b["cubes"] = [c["cube"] for c in cubes if c["bone"] == b["name"]]
    if not b["cubes"]:
        del b["cubes"]

for c in cubes:
    c["cube"]["origin"] = [round(v * SCALE, 3) for v in c["cube"]["origin"]]
    c["cube"]["size"] = [round(v * SCALE, 3) for v in c["cube"]["size"]]
for b in bones:
    b["pivot"] = [round(v * SCALE, 3) for v in b["pivot"]]

geo = {"format_version": "1.12.0", "minecraft:geometry": [{
    "description": {
        "identifier": "geometry.dronepal",
        "texture_width": TS, "texture_height": TS,
        "visible_bounds_width": 4, "visible_bounds_height": 3,
        "visible_bounds_offset": [0, 0.5, 0],
    },
    "bones": bones,
}]}

os.makedirs(os.path.dirname(OUT_MODEL), exist_ok=True)
json.dump(geo, open(OUT_MODEL, "w"), indent="\t", ensure_ascii=False)

# 贴图（调色板条，与逐面 UV 对应；顺序与 add_column 一致）
pix = [[(0, 0, 0, 0)] * TS for _ in range(TS)]
for x, col in COLUMNS:
    for y in range(16):
        for dx in (0, 1):
            pix[y][x + dx] = col[y] + (255,)
raw = b"".join(b"\x00" + b"".join(struct.pack("4B", *pix[y][x]) for x in range(TS))
               for y in range(TS))


def chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data +
            struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


os.makedirs(os.path.dirname(OUT_TEX), exist_ok=True)
open(OUT_TEX, "wb").write(
    b"\x89PNG\r\n\x1a\n" +
    chunk(b"IHDR", struct.pack(">IIBBBBB", TS, TS, 8, 6, 0, 0, 0)) +
    chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))

xs = [v for c in cubes for v in (c["cube"]["origin"][0], c["cube"]["origin"][0] + c["cube"]["size"][0])]
ys = [v for c in cubes for v in (c["cube"]["origin"][1], c["cube"]["origin"][1] + c["cube"]["size"][1])]
zs = [v for c in cubes for v in (c["cube"]["origin"][2], c["cube"]["origin"][2] + c["cube"]["size"][2])]
print(f"cubes={len(cubes)}  span={(max(xs)-min(xs))/16:.2f}x{(max(zs)-min(zs))/16:.2f}  h={(max(ys)-min(ys))/16:.2f} 格")
