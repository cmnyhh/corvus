# Corvus 选矿 / 设施方块 · 美术交付清单

四个对象的正式模型与贴图：**ore_mill 选矿台（横向 2 格）**、**water_pump 水泵（1 格）**、
**power_tower 无线供电塔（竖向 3 格）**、**water_pipe 水管（1 格，5 种连接形态）**。
风格与已完成的电力三件套一致：深灰机壳 + 亮钢边框 + 琥珀/品红/青蓝发光件，16 单位 = 1 格。
全部为 **Java Block/Item 格式**，可直接被 1.20.1 / 1.21.1 的 `BlockModel` 解析。

产物根目录：`dronepal/art/mill-blocks/`（**本目录之外没有改动任何文件**；工程 `src/` 未触碰）

```
art/mill-blocks/
├─ ore_mill.bbmodel  ore_mill_side.bbmodel                Blockbench 5.2 工程（java_block）
├─ water_pump.bbmodel
├─ power_tower.bbmodel  power_tower_mid.bbmodel  power_tower_top.bbmodel
├─ water_pipe_straight|corner|tee|cross|cap.bbmodel       （5 个工程共用同一张水管贴图）
├─ export/models/block/*.json     11 个方块模型（parent + textures + elements）
├─ export/models/item/*.json      11 个物品模型（parent 指向方块模型 + display）
├─ export/textures/block/*.png    7 张 32×32 图集（水管 5 个模型共用 1 张）
├─ export/textures/item/*.png     4 张 32×32 GUI 图标（ore_mill / water_pump / power_tower / water_pipe）
├─ previews/                      768×768 单视图 + 三视图拼图 + lineup + 水管五形态
└─ tools/                         生成器与校验器（**不进游戏**）
```

---

## 1. 命名空间

工程资源命名空间沿用 **`huhu_dronepal`**，所以模型里的贴图引用都是 `huhu_dronepal:block/<atlas>`。
若之后 modid 改成 `corvus`，只需把 11 个 `models/block/*.json` 里的 `huhu_dronepal:` 前缀
批量替换（`models/item/*.json` 的 parent 一起替换），贴图 PNG 本身不含命名空间。

---

## 2. 复制到 1.20.1 / 1.21.1 工程

两个工程的相对路径完全相同（`<project>/src/main/resources/assets/huhu_dronepal/…`），
**11 个模型 + 7 张贴图**，逐条对应：

| 交付文件 | 复制到（1.20.1 与 1.21.1 相同） |
|---|---|
| `export/models/block/ore_mill.json` | `assets/huhu_dronepal/models/block/ore_mill.json` |
| `export/models/block/ore_mill_side.json` | `assets/huhu_dronepal/models/block/ore_mill_side.json` |
| `export/models/block/water_pump.json` | `assets/huhu_dronepal/models/block/water_pump.json` |
| `export/models/block/power_tower.json` | `assets/huhu_dronepal/models/block/power_tower.json` |
| `export/models/block/power_tower_mid.json` | `assets/huhu_dronepal/models/block/power_tower_mid.json` |
| `export/models/block/power_tower_top.json` | `assets/huhu_dronepal/models/block/power_tower_top.json` |
| `export/models/block/water_pipe_straight.json` | `assets/huhu_dronepal/models/block/water_pipe_straight.json` |
| `export/models/block/water_pipe_corner.json` | `assets/huhu_dronepal/models/block/water_pipe_corner.json` |
| `export/models/block/water_pipe_tee.json` | `assets/huhu_dronepal/models/block/water_pipe_tee.json` |
| `export/models/block/water_pipe_cross.json` | `assets/huhu_dronepal/models/block/water_pipe_cross.json` |
| `export/models/block/water_pipe_cap.json` | `assets/huhu_dronepal/models/block/water_pipe_cap.json` |
| `export/models/item/<同名>.json`（11 个） | `assets/huhu_dronepal/models/item/<同名>.json` |
| `export/textures/block/ore_mill.png` | `assets/huhu_dronepal/textures/block/ore_mill.png` |
| `export/textures/block/ore_mill_side.png` | `assets/huhu_dronepal/textures/block/ore_mill_side.png` |
| `export/textures/block/water_pump.png` | `assets/huhu_dronepal/textures/block/water_pump.png` |
| `export/textures/block/power_tower.png` | `assets/huhu_dronepal/textures/block/power_tower.png` |
| `export/textures/block/power_tower_mid.png` | `assets/huhu_dronepal/textures/block/power_tower_mid.png` |
| `export/textures/block/power_tower_top.png` | `assets/huhu_dronepal/textures/block/power_tower_top.png` |
| `export/textures/block/water_pipe.png` | `assets/huhu_dronepal/textures/block/water_pipe.png` |
| `export/textures/item/{ore_mill,water_pump,power_tower,water_pipe}.png` | `assets/huhu_dronepal/textures/item/…`（**可选**，见 §7） |

即：

```
dronepal/src/main/resources/assets/huhu_dronepal/{models/block,models/item,textures/block,textures/item}/…
dronepal-1.20.1/src/main/resources/assets/huhu_dronepal/{同上}/…
```

> 与电力占位资源的关系同电力三件套：若同路径已有 `parent: minecraft:block/cube_*` 的程序化占位，
> 直接覆盖这些 `models/block/*.json` 与 `models/item/*.json` 即可；占位用的
> `textures/block/<name>_top|_side|_bottom.png` 不再被引用，可删（保留无害）。

---

## 3. 模型清单（每个 cell 各自 0..16）

| 模型 | 格 | 立方体 | 面数 | 带旋转 | 真实 AABB（旋转后，单位） | 贴图 |
|---|---|---|---|---|---|---|
| `ore_mill` | 左半（主机侧） | 137 | 822 | 4 | 15.93 × 15.2 × 15.5 | `ore_mill.png` |
| `ore_mill_side` | 右半（辅机侧） | 100 | 600 | 4 | 15.63 × 15.8 × 15.5 | `ore_mill_side.png` |
| `water_pump` | 单格 | 118 | 708 | 0 | 14.0 × 13.02 × 14.0 | `water_pump.png` |
| `power_tower` | 下格 | 46 | 276 | 0 | 14.0 × 15.8 × 14.0 | `power_tower.png` |
| `power_tower_mid` | 中格 | 103 | 618 | 8 | 12.7 × 15.6 × 12.7 | `power_tower_mid.png` |
| `power_tower_top` | 上格 | 44 | 264 | 9 | 9.4 × 14.75 × 9.4 | `power_tower_top.png` |
| `water_pipe_straight` | 单格 | 50 | 300 | 0 | 7.5 × 7.5 × 15.9 | `water_pipe.png` |
| `water_pipe_corner` | 单格 | 59 | 354 | 0 | 11.7 × 7.5 × 11.7 | `water_pipe.png` |
| `water_pipe_tee` | 单格 | 84 | 504 | 0 | 11.7 × 7.5 × 15.9 | `water_pipe.png` |
| `water_pipe_cross` | 单格 | 109 | 654 | 0 | 15.9 × 7.5 × 15.9 | `water_pipe.png` |
| `water_pipe_cap` | 单格 | 38 | 228 | 0 | 13.8 × 7.5 × 7.5 | `water_pipe.png` |
| **合计** | | **888** | **5328** | 24 | | 7 张 |

各对象识别点：

- **ore_mill（左半 / 主机侧，横版）**：4 脚垫 + 枕木 → 基座（黄黑警示带）→ 机壳（角柱 + 亮钢边框）；
  **正面三级阶梯进料漏斗**（斗口堆矿 + 溢到前沿的矿块，每级侧壁有亮钢压边）+ 漏斗喉部正对的
  **暗色进料口**（内嵌琥珀余烬条）；**正面左侧一条琥珀观察窗**（钢框 + 2 根竖挺，纯色 4×4 发光）；
  **端墙面（西端）一个 8 段手轮**（辐条 + 轮毂 + 安装板）+ 铆接数据板；
  **背面出料托盘**（槽口 + 托盘 + 边缘挡板 + 精矿）；顶部是**平整停机场**（y=15.2 平面）
  与**分选盘坑的西半**（坑半径 5.6、坑壁 26 段圆环、盘面 + 2 片叶片 + 半个轮毂）。
- **ore_mill_side（右半 / 辅机侧，横版）**：脚垫/基座/机壳同上（镜像）→ 正面**散热百叶**（6 条）
  + 空白警示板 + 铆接数据板 + 状态灯；**端墙面（东端）检修门**（铆接门板 + 2 个铰链 + 拉手 +
  品红门锁 + 小舷窗）；**背面尾部出料托盘**（尾矿）；**背面外侧立式排气管**
  （水平接管 + 立管 + 2 道法兰 + 顶盖 + 黑洞，顶端 15.8，位于停机场之外）；
  顶部是停机场的另一半与**分选盘坑的东半**（盘面 + 2 片叶片 + 半个轮毂）+ 2 处埋入式甲板通风口。
- **两个半格拼起来**：32 × 15.2(局部 15.8) × 15.5 的一台机器；**接缝是一圈法兰暗缝**
  （两侧法兰唇各伸进缝里 0.15/0.15，缝宽 0.1，正文 §6）。

- **water_pump**：4 脚垫 + 底座（青蓝渗漏条）→ 台面 → **八边形泵体**（两道铸造箍带 + 4 条辐板）；
  **左右各一个圆形进水法兰**（八角 + 4 颗螺栓 + 深色管口 + 青色口内视窗）；
  **正面竖直水位玻璃窗**（钢框 + 3 道刻度，纯色 4×4 青色发光）；右上**压力表**（白面 + 刻度 + 品红指针）；
  **顶部出水接口**（立管 + 八角法兰 + 管口青色冷却液）；右后电机（3 片散热鳍 + 琥珀运行灯）。
- **power_tower（下格）**：4 个配重脚 + 台面（警示带）→ 机壳 + **正面控制面板**
  （屏幕 + 3 行琥珀读数、品红电源导轨、2 个表、红色急停、4 个按钮琥珀/青蓝、两侧百叶 + 2 根立管）
  → **三级收分基座 + 顶部安装法兰**（中格立柱落在这块法兰上）。
- **power_tower_mid（中格）**：细立柱（3.4 宽，四角角钢 + 3 道抱箍）+ **2 圈谐振线圈**
  （每圈 16 段，每 4 段一个琥珀发光段）+ **8 片向右上倾斜的鳍片**（内外两段逐级抬高 1.2u，
  4 个正向 + 4 个 ±45° 斜向，形成辐射环）+ 4 个琥珀能量节点 + 4 条暗琥珀立柱能量缝；上下对接法兰。
- **power_tower_top（上格）**：立柱续接 + **八边形发射冠**（青蓝发射缝 + 品红环带）
  + 3 条天线（2 条左右 ±22.5°、1 条向前 -22.5°，各带琥珀尖端）
  + **顶部呼吸灯球**（7 层阶梯球，**所有面都是同一个纯白 4×4 色块**，供按状态染色，见 §5）。
- **water_pipe**：管体八边形 Ø6（`r=3.0`），中心轴对齐方块中心；**每个连接面中心一块 Ø7.5 法兰**
  （厚 0.95，贴在方块面上，不侵入邻格）；管身中段**青蓝冷却液视窗环**（两侧带钢压圈）；
  `cross/tee/corner/cap` 额外有**八角形铸造接头体**；cap 有阶梯端盖 + 青色液珠。

---

## 4. blockstate 需要的 variants（双格/三格如何拆、水管怎么选模型）

模型正面 = **north（-Z）**。所有多格对象都是**每格一个独立 block**、各自一个模型。

### 4.1 ore_mill（**横向** 2 格）：`ore_mill`（左/主机半）+ `ore_mill_side`（右/辅机半）

两格是**两个独立 block**，各自一个模型、各自 0..16 坐标系；拼起来是 32 宽的一台机器。
**模型里 `ore_mill_side` 位于 `ore_mill` 的 +X（东）方向**。工程侧放置时：

| 主方块 `facing` | 侧块应该放在主方块的 | 侧块自己的 blockstate |
|---|---|---|
| north（正面朝北） | **东** `(+1, 0, 0)` | `facing=north` |
| east | **南** `(0, 0, +1)` | `facing=east` |
| south | **西** `(-1, 0, 0)` | `facing=south` |
| west | **北** `(0, 0, -1)` | `facing=west` |

（规则：侧块＝主方块"右手边"那格；两个方块的 `facing` 必须一致，才会一起旋转。）

没有 `facing` 属性时（模型正面固定朝北），侧块永远在东侧：

```json
// blockstates/ore_mill.json      （主方块）
{ "variants": { "": { "model": "huhu_dronepal:block/ore_mill" } } }
// blockstates/ore_mill_side.json  （侧块）
{ "variants": { "": { "model": "huhu_dronepal:block/ore_mill_side" } } }
```

有 `HORIZONTAL_FACING` 时（正面 = north = y:0，两个方块都用同一张表）：

```json
{
  "variants": {
    "facing=north": { "model": "huhu_dronepal:block/ore_mill" },
    "facing=east":  { "model": "huhu_dronepal:block/ore_mill", "y": 90 },
    "facing=south": { "model": "huhu_dronepal:block/ore_mill", "y": 180 },
    "facing=west":  { "model": "huhu_dronepal:block/ore_mill", "y": 270 }
  }
}
```
（`ore_mill_side` 用同样的四行，只换模型名。）

**放置 / 破坏建议**：主方块持有 BlockEntity 与全部逻辑；侧块是一个"部件块"——
`onPlace` 时不要单独存在（主方块 `setBlock` 时连带放置侧块），侧块被破坏时同时破坏主方块，
掉落只在主方块结算；侧块朝向始终从主方块同步（`getStateForPlacement` 里写同一个 `facing`）。
若你们更想要"一格 block 占两格"，那就得用自定义 renderer（本项目不做）。

### 4.2 power_tower（3 格）：`power_tower` + `power_tower_mid` + `power_tower_top`

三格各自一个 block（下/中/上），无 `facing` 时各一行 `{"": {"model": …}}`；
有朝向时同样四行 + `y`。塔身四向对称（鳍片环 + 三根天线），`y` 旋转只影响天线朝向，可接受。

### 4.3 water_pipe（6 位连接掩码 → 模型 + 旋转）

掩码约定：6 位 `N E S W U D`（上/下为 U/D）。**管体轴向 = Z**，所以
`x:90` 得到竖直管（这也是为什么 `straight` 沿 N-S 而不是 E-W）。

| 连接集合 | 模型 | 旋转 |
|---|---|---|
| N+S | `water_pipe_straight` | `y:0` |
| E+W | `water_pipe_straight` | `y:90` |
| U+D | `water_pipe_straight` | `x:90` |
| N+E / E+S / S+W / W+N | `water_pipe_corner` | `y:0` / `y:90` / `y:180` / `y:270` |
| N+S+E / E+W+S / N+S+W / E+W+N | `water_pipe_tee` | `y:0` / `y:90` / `y:180` / `y:270` |
| N+S+E+W | `water_pipe_cross` | `y:0`（任意 y 等价） |
| 仅 N / E / S / W | `water_pipe_cap` | `y:270` / `y:0` / `y:90` / `y:180`（开口朝那一面） |
| 仅 U / 仅 D | `water_pipe_cap` | `x:90` / `x:270`（哪边朝上以实机为准，两值互换算错即可） |

可直接照抄的选择函数（掩码位序与 `Direction.values()` 无关，按你工程的习惯调整）：

```java
// bits: N=1, E=2, S=4, W=8, U=16, D=32
static ModelVariant pick(int mask) {
    int h = mask & 0b1111;                       // 水平 4 位
    if (h == 0 && (mask & 0b110000) == 0) return straight(0);      // 孤管
    if (h == (1 | 4))  return straight(0);       // N+S
    if (h == (2 | 8))  return straight(90);      // E+W
    if (h == 0)        return straightX(90);     // U+D
    if (Integer.bitCount(h) == 1) {              // 单头 + 可能有竖向
        if ((mask & 0b110000) != 0) return straightX(90);          // 竖向优先，近似
        return cap(yFor(h));
    }
    if (Integer.bitCount(h) == 2) return corner(yForCorner(h));
    if (Integer.bitCount(h) == 3) return tee(yForTee(h));
    return cross();                              // 4 个水平（含 5/6 位时的近似）
}
```

**已知限制（请逻辑侧决定要不要补模型）**：
1. **竖向 + 水平混合的接头（如 N+U、N+E+U、N+E+S+U+…）现有 5 个模型都无法完整表达**。
   建议按"水平优先，竖向用 straight 近似"，或者告诉我，我再补 `water_pipe_tee_up`
   （水平三通 + 上接）与 `water_pipe_corner_up`（下弯/上弯）两个模型——参数化的生成器已经就绪，
   每个大约 10 分钟。
2. `water_pipe_cap` 的上下朝向（`x:90` / `x:270`）请实机确认一次；`straight` 因为两端完全对称，
   不存在这个歧义。

---

## 5. 发光件（纯色 4×4 UV 矩形，可直接做 emissive 贴图）

所有发光面都指向**单一纯色 4×4 区域**（1.20.1 / 1.21.1 一致，模型不用改）：

| 贴图 | 用途（模型里的元素名） | 像素矩形 | 颜色 |
|---|---|---|---|
| `ore_mill.png` | 进料口余烬 `intake_glow` + **琥珀观察窗** `win_glass` | `(25,10)-(29,14)` | `#ff8a1f` |
| `ore_mill.png` | 状态灯 `led_amber` | `(1,12)-(5,16)` | `#ffb44d` |
| `ore_mill.png` | 品红装饰 `led_mag` | `(22,15)-(26,19)` | `#ff3fa0` |
| `ore_mill_side.png` | 门锁 `door_lock`（同一图集布局，坐标相同） | `(22,15)-(26,19)` | `#ff3fa0` |
| `ore_mill_side.png` | 状态灯 `led_amber` | `(1,12)-(5,16)` | `#ffb44d` |
| `water_pump.png` | **水位玻璃窗** `glass_slot` | `(20,5)-(24,9)` | `#35d7e8` |
| `water_pump.png` | 进出水口冷却液 `inlet_glass_*` / `outlet_glow` | `(20,5)-(24,9)` | `#35d7e8` |
| `water_pump.png` | 运行灯 `led_run` | `(26,1)-(30,5)` | `#ffb44d` |
| `power_tower.png` | 屏幕读数 `screen_line_*` | `(27,1)-(31,5)` | `#ff8a1f` |
| `power_tower.png` | 品红电源导轨 `panel_glow` | `(1,8)-(5,12)` | `#ff3fa0` |
| `power_tower.png` | 急停 `estop_cap` | `(25,6)-(29,10)` | `#ff6a12` |
| `power_tower.png` | 按钮 `btn_*` | `(17,6)-(21,10)` 琥珀 / `(21,6)-(25,10)` 青蓝 | `#ffb44d` / `#35d7e8` |
| `power_tower_mid.png` | 线圈发光段 `coil*_k`（每 4 段 1 个） | `(9,1)-(13,5)` | `#ff8a1f` |
| `power_tower_mid.png` | 能量节点 `node_*` | `(9,1)-(13,5)` | `#ff8a1f`（`up` 面用 `(13,1)-(17,5)`） |
| `power_tower_mid.png` | 立柱能量缝 `spine_*` | `(5,1)-(9,5)` | `#a1590a` |
| `power_tower_top.png` | **呼吸灯球** `lamp_0..6`（7 层，全部面） | `(26,1)-(30,5)` | `#ffffff` |
| `power_tower_top.png` | 天线尖端 `ant_*_tip` | `(18,1)-(22,5)` | `#ffb44d` |
| `power_tower_top.png` | 发射缝 `emit_*` | `(22,1)-(26,5)` | `#35d7e8` |
| `power_tower_top.png` | 品红环带 / 状态灯 | `(7,5)-(11,9)` | `#ff3fa0` |
| `water_pipe.png` | 冷却液视窗环 `sight_*` | `(1,1)-(5,5)` | `#35d7e8` |

### 呼吸灯球"按状态染色"的做法（三种任选）

1. **最省事：blockstate 换模型 + 换贴图**。灯球是 7 个独立 element（`lamp_0..6`），
   把 `power_tower_top.png` 的 `(26,1)-(30,5)` 这一个 4×4 区域改成对应状态色，
   另存 `power_tower_top_<state>.png`，再各复制一份 `models/block/power_tower_top.json`
   指向新贴图，blockstate 按 `state` 属性切模型 —— 模型几何不用改。
2. **单贴图 + 顶点色/quad 染色**：灯球 7 个 element 全部只引用那一个纯白色块，
   自定义 renderer 里按 element 抓取、乘一个状态色即可（白底乘色 = 目标色，不会偏）。
3. **每 tick 改贴图**（不推荐，会掉 atlas 缓存）。

> 之所以用纯白 `#ffffff` 而不是琥珀：乘性染色下白色不偏色，琥珀会越染越暗。

---

## 6. 多格接缝规则（工程侧不用关心，但改模型时必须守）

**横向选矿台（X 接缝）**：
- 主半 `ore_mill` 的几何最远到 **x = 15.95**，辅半 `ore_mill_side` 从 **x = 0.05** 开始 ——
  也就是**两侧法兰唇各伸进缝里 0.15，中间留 0.1 的暗缝**。
- 为什么不是两边都停在 15.8/0.2：那样两个方块的"端面"会在同一平面共面闪烁；
  现在两侧的法兰唇错开 0.1，**任何两个面都不共面**，接缝反而成了设计语言（一圈金属暗缝）。
- 侧块与主块**不允许**有任何重叠体素（当前世界坐标下：主块到 15.95，侧块从 16.05 起）。

**竖向三格供电塔（Y 接缝）**：
- 下格/中格的最高点 ≤ **15.8**，上格的最低点 ≥ **0.2**，中间 0.4 暗缝，两侧各有一圈法兰/抱箍。
- 同样保证堆叠时**没有任何两个面落在同一平面**，不需要"上格底面剔除"这种取巧。

`tools/validate.py` 会把以上两条按 `SEAM` 表逐模型检查（`ore_mill` 查 x 上界、`ore_mill_side` 查 x 下界、
三格塔查 y 上/下界）。

## 7. 物品模型 / 图标

- `models/item/<name>.json` = `{"parent": "huhu_dronepal:block/<name>", "display": {...}}`，
  物品直接用方块模型渲染（30°/225° iso，scale 0.625，与电力三件套一致）。
  **多格对象的物品请用"主"那一格**（`ore_mill` / `power_tower`）；
  `ore_mill_side` 与供电塔的中/上格只做方块渲染，不单独出物品。
- `textures/item/{ore_mill,water_pump,power_tower,water_pipe}.png` 是 32×32 GUI 图标，
  **没有被 item 模型引用**（创造模式分类、Patchouli/JEI、掉落物提示可用）。
  若想直接用平面图标当物品贴图：把 `models/item/<name>.json` 换成
  `{"parent":"item/generated","textures":{"layer0":"huhu_dronepal:item/<name>"}}`。

---

## 8. 与 Minecraft 解析相关的硬约束（已按此建模）

1. **元素 rotation 只允许 `0 / ±22.5 / ±45`，单轴**（`BlockModel.Deserializer` 对其它值抛
   `Invalid rotation`）。所以"向右上倾斜的鳍片"用 **22.5/45 的 Y 轴偏转 + 外段抬高 1.2u 的阶梯** 表达，
   分选盘叶片是 Y 轴 22.5°，手轮/法兰的"圆"靠**八段/十六段几何**而不是旋转。
2. 所有元素 `from/to` 在 **0..16**，**旋转后的 8 个角点也在 0..16**（校验器逐角点扫）。
3. 与方块边界面齐平的面（脚垫底面 y=0 等）加了 `cullface`，避免与邻块共面闪烁；
   `cullface` 只出现在未旋转、且确实在 0/16 边界的面。
4. `parent` = `minecraft:block/block`（提供 display 与 AO），模型自带 `elements`/`textures`，
   另带 `format_version: "1.12.0"`（Minecraft 忽略未知字段，保留便于工具链读取）。
5. 单模型最大 137 个元素，11 个模型合计 888 元素 / 5328 面，远低于任何限制。

---

## 9. 校验（`tools/validate-report.txt` 全文）

- **11 个模型：0 error / 16 warning**。warning 全是"元素与 0/16 边界齐平（脚垫、底座）"的提示，
  这些面已加 `cullface`，属预期。
- 已逐项核对：
  - 每个元素的 `from/to` 在 0..16；**旋转后的真实 AABB 也在 0..16**；
  - 旋转轴单轴、角度落在 {0, ±22.5, ±45}；
  - **逐面 UV 像素比对**：所有非纯色面"面的像素尺寸 : UV 矩形像素尺寸"偏差 ≤20%，0 例超限；
  - 7 张贴图均 32×32、**全不透明**（无透明洞）；
  - 发光面均为单一纯色；
  - 多格接缝规则（§6）通过；
  - `.bbmodel` 与 Java 模型的**逐块几何、旋转、每面 UV 完全一致**（11/11）；
  - item 模型 parent 指向对应 block 模型。

重建与复验：

```bash
cd dronepal/art/mill-blocks/tools
python3 -B build.py            # 贴图 + 11 个 block/item JSON + 11 个 .bbmodel + atlas 布局
python3 -B validate.py         # 0 error 才算过 → tools/validate-report.txt
python3 -B previews.py         # 需要 Pillow：从 tools/renders-src/ 合成 previews/
```

---

## 10. 预览（`previews/`）

| 文件 | 内容 |
|---|---|
| `ore_mill-{front,right,iso}.png` | 横向两格**拼起来**的整机（32 宽），768×768 透明底 |
| `ore_mill-3view.png` | 三视图 + 规格卡的 2×2 拼图 |
| `water_pump-{front,right,iso}.png` / `-3view.png` | 同上（单格） |
| `power_tower-{front,right,iso}.png` / `-3view.png` | **三格拼起来**的整塔 |
| `water_pipe-{front,right,iso}.png` / `-3view.png` | 以 `cross`（四面全通）为代表件，便于看管体/法兰/视窗 |
| `water_pipe-set.png` | 5 种连接形态同尺度并排（straight / corner / tee / cross / cap） |
| `mill-blocks-lineup.png` | **横版选矿台（两格）+ 水泵 + 三格塔**同一场景、同一尺度、同一光源的并排图（iso） |
| `mill-blocks-lineup-front.png` | 同上，正立面 |

预览全部来自 Blockbench 5.2.2 对同一份几何的渲染（`tools/pbgen.py` 的 Bedrock 预览包是
Java→Bedrock 的精确逆变换），因此**预览里看到的像素与游戏内一致**；三视图与 lineup 是
同一次渲染的不同裁剪，尺度严格相同。

---

## 11. tools/ 里有什么（不进游戏）

| 文件 | 作用 |
|---|---|
| `tools/pbgen.py` | 建模/导出核心：图集打包、Java 模型、.bbmodel、多模型场景预览包 |
| `tools/millblocks.py` | **11 个模型的几何与逐面材质定义**（改这里就能改模型） |
| `tools/build.py` | 一键盘：贴图 + 11 个 JSON + 11 个 .bbmodel + 预览包 |
| `tools/validate.py` | 严格校验器 → `tools/validate-report.txt` |
| `tools/previews.py` | 预览归一化 / 三视图拼图 / lineup / 水管五形态 |
| `tools/atlas/*.json` | 每张图集的岛与色块坐标（含像素与 UV 单位值） |
| `tools/renders-src/*.png` | Blockbench 原始渲染（previews.py 的输入） |
| `tools/build-summary.json` | 立方体数 / 面数 / AABB |

---

## 12. 我拿不准 / 建议你们过一眼的点

1. **叶片倾角是 45° 而不是 22.5°**。分选盘跨在两格接缝上，叶片若做成 22.5° 的辐条，
   其中两片必须以"沿 ±Z 的长条"为基准再绕盘心转 22.5°，而盘心正好落在方块边界 ——
   长条会伸出 0..16 的格子（校验器会直接报错）。所以 4 片叶片落在 4 条对角线上、
   各自绕盘心转 ±45°（vanilla 只接受 0/±22.5/±45），再各加一段 0.5 的错位台阶做出"被扭转"的观感：
   视觉上仍是"4 片斜置叶片的分选盘"。若你们更在意 22.5° 这个数字，我可以把盘心移到主机半中央
   （x=8）并改用 22.5/112.5/202.5/292.5 四片 —— 代价是盘不再跨两格，机器仍有 32 宽。
2. **分选盘是"下沉的圆坑"**：为了满足"顶部平整留给无人机悬停"，盘面在 y=14.10、周围甲板 15.2，
   坑深 1.1（坑壁 26 段圆环）。**甲板平面严格 15.2，机器最高点也是 15.2**（只有辅机侧背面的
   排气管到 15.8，且在停机场footprint 之外）。如果你们希望盘面与甲板齐平（完全无坑），
   把 `disc_half` 的 y 抬到 15.2 即可，但那样盘就只剩一张贴地圆片。
3. **主/侧两块的归属**：我默认主方块持有 BlockEntity、侧块只是部件（§4.1 的建议做法）。
   如果你们想让两格都是"完整方块"，只需要让侧块的 blockstate 也带 `facing` 并由主方块同步即可，
   模型不用改。
4. **`facing` 与 `getOpposite()`**：若你们的 `facing` 语义取反，把 §4.1/§4.2 的 `y` 各轮转 180，
   同时把"侧块放在右手边"改成左手边。
5. **竖向 + 水平混合的管接头没有模型**（§4.3 限制 1），建议先按"水平优先"跑，需要我再加
   `tee_up` / `corner_up`。
6. **`cap` 的 `x:90 / x:270` 哪个朝上**：请实机确认一次（`straight` 两端对称，无此歧义）。
7. **呼吸灯球是纯白 `#ffffff`**（乘性染色不偏色）；若你们的状态色是叠加而非相乘，
   把 `power_tower_top.png` 的 `(26,1)-(30,5)` 换成中性浅色即可，几何不用动。
8. **`.bbmodel` 的打开验证**：11 个工程按 Blockbench 5.2.2 schema 生成，与 Java 模型逐块比对一致；
   我这边没有 GUI 侧"点开 .bbmodel"的通道，请手动点开确认一次。
9. **预览用的临时 Blockbench 包**：渲染时在 `blockbench-ysm-agent/models/togawa-sakiko/` 下建过
   `mill-*` 临时包（渲染完已删除），与工程 `src/` 无关。
