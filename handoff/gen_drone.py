#!/usr/bin/env python3
"""Dronepal 无人机 · 体素草模（设计图用，也是 P1 的模型初稿）。

坐标：16 单位 = 1 方块；-Z 为前；+Y 上。
整机跨度约 26 单位（1.6 格），机体 14×8×14（0.875 格）。
"""
import json, os, zlib, struct

TS = 256
SCALE = 0.66          # 全局缩放：总跨度 2.20 格 → 1.45 格
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


add_column("shell", "#8d949f", "#3a3e46")     # 机身主壳
add_column("shellD", "#5a606b", "#22252b")    # 深壳
add_column("trim", "#cdd3dc", "#7b828e")      # 亮边
add_column("dark", "#2b2e34", "#111317")      # 内构
add_column("cyan", "#9ff5ff", "#12b8d4")      # 发光
add_column("cyanD", "#38d7ea", "#0a6c80")     # 发光暗
add_column("orange", "#ffb44d", "#b25a00")    # 警示
add_column("rotor", "#aab1bc", "#464b54")     # 桨叶
add_column("rubber", "#3a3d44", "#17181c")    # 起落架胶垫

cubes = []


def cube(bone, origin, size, color):
    cubes.append({"bone": bone, "cube": {
        "origin": [round(v, 3) for v in origin],
        "size": [round(v, 3) for v in size],
        "uv": {f: {"uv": [PALETTE[color], 0], "uv_size": [1, 16]} for f in FACES},
    }})


# ---------------------------------------------------------------- 机体
cube("body", [-7, 0, -7], [14, 6, 14], "shell")                 # 主机身
cube("body", [-6, 6, -6], [12, 3, 12], "shellD")                # 上层机壳
cube("body", [-5.2, 9, -5.2], [10.4, 1.2, 10.4], "trim")        # 顶盖
cube("body", [-2.6, 10.2, -2.6], [5.2, 2.2, 5.2], "dark")       # 传感器塔
cube("body", [-1.9, 12.4, -1.9], [3.8, 0.9, 3.8], "trim")       # 塔顶
cube("body", [-1.1, 13.3, -2.4], [2.2, 0.8, 1.2], "cyan")       # 顶灯
# 前脸（镜头窗口）
cube("body", [-3.4, 2.0, -7.6], [6.8, 3.0, 0.8], "dark")
cube("body", [-2.4, 2.6, -8.0], [4.8, 1.8, 0.5], "cyanD")
cube("body", [-1.0, 3.0, -8.2], [2.0, 1.0, 0.4], "cyan")
# 侧面发光条
for sx in (-1, 1):
    cube("body", [sx * 7.0 - (0.25 if sx > 0 else 0), 1.6, -4.0], [0.25, 1.0, 8.0], "cyan")
# 警示条（顶部两侧）
for sx in (-1, 1):
    cube("body", [sx * 4.6, 6.9, -5.6], [1.6, 0.35, 3.2], "orange")

# ---------------------------------------------------------------- 机臂 + 旋翼
def arm(tag, dx, dz):
    """dx/dz 是 -1/0/1 的方向；机臂沿该轴伸出。"""
    if dx:
        x0 = 7 if dx > 0 else -11.5
        cube(tag, [x0, 4.0, -1.2], [6.0, 1.6, 2.4], "shellD")
        cube(tag, [x0 + (0.6 if dx > 0 else 0), 4.4, -0.9], [5.8, 0.5, 1.8], "trim")
        hub = (13.0 if dx > 0 else -13.0, 6.0, 0)
    else:
        z0 = 7 if dz > 0 else -11.5
        cube(tag, [-1.2, 4.0, z0], [2.4, 1.6, 6.0], "shellD")
        cube(tag, [-0.9, 4.4, z0 + (0.6 if dz > 0 else 0)], [1.8, 0.5, 5.8], "trim")
        hub = (0, 6.0, 13.0 if dz > 0 else -13.0)
    hx, hy, hz = hub
    cube(tag, [hx - 1.3, hy - 0.4, hz - 1.3], [2.6, 1.2, 2.6], "dark")     # 电机
    cube(tag, [hx - 0.6, hy + 0.8, hz - 0.6], [1.2, 0.5, 1.2], "trim")     # 桨毂
    cube(tag, [hx - 4.6, hy + 1.1, hz - 0.45], [9.2, 0.28, 0.9], "rotor")  # 桨叶 A
    cube(tag, [hx - 0.45, hy + 1.1, hz - 4.6], [0.9, 0.28, 9.2], "rotor")  # 桨叶 B
    cube(tag, [hx - 0.35, hy + 1.35, hz - 0.35], [0.7, 0.3, 0.7], "cyan")   # 桨尖灯


arm("rotor_fl", 0, -1)
arm("rotor_fr", 1, 0)
arm("rotor_bl", -1, 0)
arm("rotor_br", 0, 1)

# ---------------------------------------------------------------- 云台 + 挂点
cube("gimbal", [-2.2, -2.6, -6.2], [4.4, 3.0, 4.4], "shellD")   # 云台基座
cube("gimbal", [-1.6, -3.4, -7.4], [3.2, 1.2, 3.2], "dark")     # 俯仰轴
cube("gimbal", [-0.9, -4.2, -6.6], [1.8, 0.9, 1.8], "trim")     # 挂架板
# 云台侧面的发光环
for sx in (-1, 1):
    cube("gimbal", [sx * 2.2 - (0.3 if sx > 0 else 0), -1.8, -5.0], [0.3, 0.9, 2.0], "cyan")
# 示意枪体（实际由 TaCZ 渲染，这里只表达挂点位置与比例）
cube("gun_mount", [-1.1, -5.7, -12.4], [2.2, 1.6, 9.4], "shellD")
cube("gun_mount", [-0.5, -5.1, -14.2], [1.0, 1.0, 2.0], "dark")
cube("gun_mount", [-0.9, -7.1, -4.6], [1.8, 2.0, 1.6], "dark")
# ---------------------------------------------------------------- 起落架
for sx in (-1, 1):
    x = 3.6 if sx > 0 else -4.4
    cube("skid_l" if sx < 0 else "skid_r", [x, -3.2, -3.0], [0.8, 3.2, 0.8], "dark")
    cube("skid_l" if sx < 0 else "skid_r", [x, -3.2, 2.2], [0.8, 3.2, 0.8], "dark")
    cube("skid_l" if sx < 0 else "skid_r", [x - 1.2, -3.8, -3.4], [3.2, 0.6, 6.2], "rubber")

# ---------------------------------------------------------------- 骨骼
def bone(name, parent=None, pivot=(0, 0, 0)):
    b = {"name": name, "pivot": [round(v, 3) for v in pivot]}
    if parent:
        b["parent"] = parent
    return b


bones = [
    bone("root"),
    bone("body", "root"),
    bone("rotor_fl", "body"),
    bone("rotor_fr", "body"),
    bone("rotor_bl", "body"),
    bone("rotor_br", "body"),
    bone("gimbal", "root"),
    bone("gun_mount", "gimbal", (0, -4.2, -5.6)),   # TaCZ 枪挂在这里
    bone("skid_l", "root"),
    bone("skid_r", "root"),
]
# 全局缩放（含骨骼枢轴）
for c in cubes:
    c["cube"]["origin"] = [round(v * SCALE, 3) for v in c["cube"]["origin"]]
    c["cube"]["size"] = [round(v * SCALE, 3) for v in c["cube"]["size"]]
for b in bones:
    b["pivot"] = [round(v * SCALE, 3) for v in b["pivot"]]

for b in bones:
    b["cubes"] = [c["cube"] for c in cubes if c["bone"] == b["name"]]
    if not b["cubes"]:
        del b["cubes"]

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

print(f"cubes={len(cubes)} bones={len(bones)} -> {OUT_MODEL}")
