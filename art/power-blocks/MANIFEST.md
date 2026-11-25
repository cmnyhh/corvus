# Corvus 电力方块 · 美术交付清单

三个电力方块的正式模型与贴图，**Blockbench 手工建模**（逐方块拼装 + 逐面 UV），
Java Block/Item 格式，可直接被 1.20.1 / 1.21.1 的 `BlockModel` 解析。

产物根目录：`dronepal/art/power-blocks/`（**本目录之外没有改动任何文件**）

```
art/power-blocks/
├─ burner_generator.bbmodel          Blockbench 5.2 工程（model_format = java_block）
├─ solar_generator.bbmodel
├─ battery_bank.bbmodel
├─ export/models/block/*.json        方块模型（parent + textures + elements）
├─ export/models/item/*.json         物品模型（parent 指向方块模型 + display）
├─ export/textures/block/*.png       32×32 方块图集
├─ export/textures/item/*.png        32×32 物品图标（GUI 用，见 §6）
├─ previews/<name>-front|right|iso.png   768×768 单视图（透明底）
├─ previews/<name>-3view.png             768×768 三视图拼图（含规格卡）
├─ previews/power-blocks-lineup.png      1600×800 并排（同一次渲染 ⇒ 严格同尺度）
├─ previews/power-blocks-lineup-front.png 同上，正立面
└─ tools/                            生成器与校验器（**不进游戏**，见 §8）
```

---

## 1. 命名空间

现有工程的资源命名空间是 **`huhu_dronepal`**（`dronepal/src/main/resources/assets/huhu_dronepal/`），
所以所有模型里的贴图引用都是 `huhu_dronepal:block/<name>`。

> 若之后 modid 改成 `corvus`，只需把三个 `models/block/*.json` 里的
> `huhu_dronepal:` 前缀批量替换，其余文件不引用命名空间（`models/item/*.json` 的
> parent 也要一起替换）。

---

## 2. 复制到 1.20.1 / 1.21.1 工程

两个工程的相对路径完全相同（`<project>/src/main/resources/assets/huhu_dronepal/…`）。

| 交付文件 | 复制到（1.20.1 与 1.21.1 相同） |
|---|---|
| `export/models/block/burner_generator.json` | `assets/huhu_dronepal/models/block/burner_generator.json` |
| `export/models/block/solar_generator.json` | `assets/huhu_dronepal/models/block/solar_generator.json` |
| `export/models/block/battery_bank.json` | `assets/huhu_dronepal/models/block/battery_bank.json` |
| `export/models/item/burner_generator.json` | `assets/huhu_dronepal/models/item/burner_generator.json` |
| `export/models/item/solar_generator.json` | `assets/huhu_dronepal/models/item/solar_generator.json` |
| `export/models/item/battery_bank.json` | `assets/huhu_dronepal/models/item/battery_bank.json` |
| `export/textures/block/burner_generator.png` | `assets/huhu_dronepal/textures/block/burner_generator.png` |
| `export/textures/block/solar_generator.png` | `assets/huhu_dronepal/textures/block/solar_generator.png` |
| `export/textures/block/battery_bank.png` | `assets/huhu_dronepal/textures/block/battery_bank.png` |
| `export/textures/item/burner_generator.png` | `assets/huhu_dronepal/textures/item/burner_generator.png`（可选，见 §6） |
| `export/textures/item/solar_generator.png` | `assets/huhu_dronepal/textures/item/solar_generator.png`（可选） |
| `export/textures/item/battery_bank.png` | `assets/huhu_dronepal/textures/item/battery_bank.png`（可选） |

具体两个工程：

```
dronepal/src/main/resources/assets/huhu_dronepal/models/block/{burner_generator,solar_generator,battery_bank}.json
dronepal/src/main/resources/assets/huhu_dronepal/models/item/{...}.json
dronepal/src/main/resources/assets/huhu_dronepal/textures/block/{...}.png
dronepal/src/main/resources/assets/huhu_dronepal/textures/item/{...}.png

dronepal-1.20.1/src/main/resources/assets/huhu_dronepal/models/block/{...}.json
dronepal-1.20.1/src/main/resources/assets/huhu_dronepal/models/item/{...}.json
dronepal-1.20.1/src/main/resources/assets/huhu_dronepal/textures/block/{...}.png
dronepal-1.20.1/src/main/resources/assets/huhu_dronepal/textures/item/{...}.png
```

### 2b. 与"电力逻辑代理"当前占位资源的关系（重要）

我开工时（14:23 之后）`dronepal` 里已经有一套**程序化占位**资源，同一路径会被我的正式资产替换：

| 现状（占位） | 处理 |
|---|---|
| `models/block/{name}.json` = `parent: minecraft:block/cube_bottom_top` + 3 张贴图 | **直接覆盖**成我的模型（同一路径） |
| `textures/block/{name}_top.png / _side.png / _bottom.png`（16×16 程序生成） | 我的模型不再引用它们，**可删**（保留也无害） |
| `textures/block/{name}.png` | 新增（32×32 图集，我的模型引用它） |
| `models/item/{name}.json` = `{"parent": "huhu_dronepal:block/<name>"}` | **直接覆盖**（我加了 display 字段，item 自动用新方块模型） |
| `blockstates/{name}.json` = `{"variants":{"":{"model":"…"}}}` | **不需要改**（见下） |

也就是说：**三个 `models/block/*.json` + 三个 `textures/block/<name>.png` + 三个 `models/item/*.json`
覆盖进去就生效，blockstate 不用动。**

现在 `BurnerGeneratorBlock` / `SolarGeneratorBlock` / `BatteryBankBlock` 都**没有 `facing` 属性**
（纯 `BaseEntityBlock` + `RenderShape.MODEL`），所以模型正面（north）永远朝北。若之后加了
`HORIZONTAL_FACING`，用下面的片段（模型正面 = north）：

```json
{
  "variants": {
    "facing=north": { "model": "huhu_dronepal:block/burner_generator" },
    "facing=east":  { "model": "huhu_dronepal:block/burner_generator", "y": 90 },
    "facing=south": { "model": "huhu_dronepal:block/burner_generator", "y": 180 },
    "facing=west":  { "model": "huhu_dronepal:block/burner_generator", "y": 270 }
  }
}
```

> 注意 `facing=north → y=0` 这个映射；如果你们的 `Direction` 用了 `getOpposite()` 或
> `HorizontalDirectionalBlock` 的默认取反，把四行的 `y` 轮转 180 即可。

另外三个方块都是 `RenderShape.MODEL` + 默认满方块碰撞箱（`Properties` 没改 shape），
正好满足我模型里 `cullface` 的假设（相邻不透明方块会剔除我贴边的那几面）。

---

## 3. 三个方块

| | burner_generator | solar_generator | battery_bank |
|---|---|---|---|
| 造型尺寸（含旋转的真实 AABB） | **12 × 16 × 12.2** u | **15 × 11.7 × 15** u | **15.1 × 11.4 × 11.2** u |
| 立方体数 | **52** | **39** | **39** |
| 面数 | **312** | **234** | **234** |
| 带 rotation 的块 | 8（两侧百叶鳍） | 24（整块光伏板 + 栅格 + 电芯） | 0 |
| 贴图 | 32×32，7 个共享岛 + 14 个纯色块 | 32×32，5 岛 + 13 色 | 32×32，6 岛 + 13 色 |
| 正面（=north）识别点 | 橙色余烬观察窗 + 品红导轨 + 燃料漏斗 | 12 格倾斜光伏板 + 矮支架 + 配重底座 | 三段横向电芯 + 三段电量窗 + 顶部提手凹槽 |

细节对应需求：

- **burner_generator**：立式；两侧各 **4 条 −22.5° 斜置散热鳍**（外表面是带肋条的独立贴图岛）；
  顶部 **4×4 空心短烟囱**（四壁 + 井底，能看见黑孔）；正面 **橙色余烬观察窗 5×2.1u + 3 根炉栅**；
  下方**燃料漏斗**（上沿挡板 + 深槽 + 前伸托板 + 两侧导轨，内槽底画了余烬）；4 个脚垫把机身抬离地面。
- **solar_generator**：光伏板整体 **−22.5°**（vanilla 只接受 ±22.5/±45，见 §7）绕 X 轴倾斜，
  板面朝北上方，**12 个 2.575×2.533u 的独立电池片 + 3+2 根凸起金属栅条**（栅格是真的几何，不靠贴图），
  每片单独带 4 级浅蓝高光；矮 A 形支架（两高两矮 + 横撑）、底部配重台（4 个角配重块 + 走线槽 + 接线柱）、
  台面前沿一条琥珀状态灯。
- **battery_bank**：**三段横向电芯**（每段含金属肩线、顶面高光条、两端卡箍），
  正面一条**三段式电量窗**（纯绿 4×4 色块，钢制边框 + 两道分隔条，见 §5 变体做法），
  两侧各 2 块金属卡扣，顶部**凹槽提手 + 提手横杆 + 两处防滑齿**，背面接线端子。

---

## 4. 每面的 UV 都对得上（可复核）

- 结构化大面统一 **1 texel / 1 模型单位**（和 16×16 原版方块同密度，只是画布 32×32），
  对称面共享同一个图集岛（左右/背面一套），所以不会有"一边清晰一边糊"。
- 小构件（合金条、卡箍、灯）用**纯色色块**：色块区域内只有一种颜色，任何拉伸都看不出来。
- `tools/validate.py` 会逐面比对 **面的像素尺寸 vs UV 矩形像素尺寸**，非纯色面的宽高比偏差
  > 20% 直接报错；当前 **0 error**（报告见 `tools/validate-report.txt`）。
- 图集布局（每个岛/色块的像素坐标）落在 `tools/atlas/<name>.json`，出问题时可以按坐标取证。

---

## 5. 发光件与后续发光贴图

发光面全部指向**单一纯色 4×4 区域**（这一点 1.20.1 与 1.21.1 都成立，因为只是贴图引用）：

| 方块 | 用途 | 像素矩形（32×32 内） | 颜色 |
|---|---|---|---|
| burner_generator | 余烬观察窗 | `(23,15)-(27,19)` | `#ff6a12` |
| burner_generator | 品红导轨（2 条） | `(27,15)-(31,19)` | `#ff3fa0` |
| burner_generator | 琥珀状态灯 | `(27,10)-(31,14)` | `#ffb44d` |
| burner_generator | 燃料槽内余烬高光 | `(23,10)-(27,14)` | `#ff8a1f` |
| solar_generator | 台面状态灯 | `(16,16)-(20,20)` | `#ffb44d` |
| battery_bank | 三段电量窗（3 段共用） | `(11,21)-(15,25)` | `#7dff5f` |
| battery_bank | "live" 指示点 | `(26,16)-(30,20)` | `#ffb44d` |

做 emissive 贴图时：复制对应 PNG，整体压黑，只保留上表矩形（+1px 外扩可缓解 mipmap 边缘），
按你们的发光管线接即可；模型不需要改。

### 电量窗 1/3、2/3、满 的做法

模型里三段电量窗是**三个独立 element**（`gauge_seg_0/1/2`），UV 都指向同一个纯色块。三条路：

1. **换贴图 + 换模型（最省事）**：复制 `battery_bank.png` → `battery_bank_2.png` / `_1.png`，
   把第 2、3 段（第 3、第 2+3 段）从 `#7dff5f` 改成暗绿 `#1c3d17`；
   再各复制一份 `models/block/battery_bank.json` 指到新贴图，blockstate 按 `charge` 属性切模型。
2. **单贴图 + 三段 UV 偏移**：三段各指向自己的 4×4 色块（`(11,21)` / `(7,21)` / `(15,17)` 等），
   同样要做 3 个模型变体——本质同 (1)。
3. **运行时染色**：如果你用自定义 renderer，可把三段拆成 3 个 quad 用顶点色/染色控制，
   本模型已经把三段做成独立 element，方便你们按 element 抓取。

**这是唯一需要你们逻辑侧配合的地方**（我这边只交付静态满电模型）。

---

## 6. 物品模型 / 图标

- `models/item/<name>.json` = `{"parent": "huhu_dronepal:block/<name>", "display": {...}}`，
  即**物品直接用方块模型渲染**（30°/225° iso，scale 0.625，和原版方块物品一致）。
- `textures/item/<name>.png` 是 **32×32 的 GUI 图标**，**没有被 item 模型引用**。
  用途：创造模式分类页、Patchouli 手册、JEI/EMI 覆盖、掉落物提示等。
  如果你们更想要"平面图标"当物品贴图，把 `models/item/<name>.json` 换成
  `{"parent":"item/generated","textures":{"layer0":"huhu_dronepal:item/<name>"}}` 即可，PNG 已经画好。

---

## 7. 与 Minecraft 解析相关的硬约束（已按此建模）

1. **元素 rotation 只允许 `0 / ±22.5 / ±45`**，单轴。`BlockModel.Deserializer` 对其它角度会抛
   `Invalid rotation …`。所以"30° 光伏板"实际是 **−22.5°**（板本身还做了 12 片独立电池片 + 凸起栅条，
   视觉上倾斜感足够）。若你们坚持 30°，只能上自定义 renderer / 换成阶梯状几何。
2. 所有元素 `from/to` 在 **0..16**，含旋转后的角点也在 0..16（`validate.py` 会扫 8 个角点）。
3. 与方块边界面齐平的面（脚垫底面 y=0、烟囱顶面 y=16、底座底面 y=0）加了 **`cullface`**，
   避免和相邻方块共面 z-fighting；`cullface` 只出现在未旋转、且确实在边界的面。
4. `parent` = `minecraft:block/block`（提供 display 与 AO），模型自带 `elements`/`textures`；
   另带一个 `format_version: "1.12.0"` 字段——**Minecraft 会忽略未知字段**，保留是为了工具链可读。
5. 面数总和 780，单模型 < 62 KB，远低于任何限制。

---

## 8. tools/ 里有什么（不进游戏）

| 文件 | 作用 |
|---|---|
| `tools/pbgen.py` | 建模/导出核心：图集打包、Java 模型、.bbmodel、预览包（Bedrock 转换） |
| `tools/blocks.py` | 三个方块的**几何与逐面材质定义**（改这里就能改模型） |
| `tools/build.py` | 一键盘：贴图 + 三个 JSON + .bbmodel + 校验用预览包 |
| `tools/validate.py` | 严格校验器 → `tools/validate-report.txt` |
| `tools/previews.py` | 预览图归一化 / 三视图拼图 / 并排图 |
| `tools/atlas/*.json` | 每个图集的岛与色块坐标（含 UV 单位值） |
| `tools/build-summary.json` | 立方体数/面数/包围盒 |

重建：

```bash
cd dronepal/art/power-blocks/tools
python3 -B build.py            # 只出模型/贴图/bbmodel
python3 -B validate.py         # 0 error 才算过
```

---

## 9. 校验结果（`tools/validate-report.txt` 全文）

- 三个方块：**0 error / 10 warning**；warning 全是"元素与 0/16 边界齐平"的提示
  （脚垫、烟囱、底座底面），这些面已经用 `cullface` 标注，属于预期。
- 已核对：贴图 32×32 全不透明、无透明洞；每面 UV 在 0..16；`.bbmodel` 与 Java 模型的
  几何/旋转/UV 逐块完全一致；物品模型 parent 指向方块模型。

---

## 10. 我拿不准 / 建议你们过一眼的点

1. **正面朝向**：我把正面定在 **north**。当前 block 类没有 `facing` 属性，所以机器永远朝北；
   要"放置时朝向玩家"得加 `HORIZONTAL_FACING` + 上面 §2b 的 blockstate。
2. **手感尺寸**：burner 是"瘦高"12×16×12（刻意留 2u 边距，看着像机器不是实心块）；
   solar / battery 接近满 16 宽。如果你们希望三个都顶满 16×16×16，我可以放大外框（不影响 UV 逻辑）。
3. **光伏板 22.5° 而非 30°**：见 §7-1。如果视觉上觉得不够倾斜，我可以改成 45°（更夸张）或
   阶梯化几何近似 30°。
4. **电量窗只有"满"状态**：需要配合 §5 的三个模型变体或渲染侧染色。
5. **`cullface` 的假设**：默认相邻方块是完整不透明块。如果电力方块本身不是满方块
   （自定义 VoxelShape），`cullface` 仍安全（只在邻面被剔除时生效），但如果你们把方块做成
   非满形状又希望脚垫/烟囱可见，可以删掉这几个 `cullface`。
6. **32×32 是硬要求，密度为 1 texel/单位**：如果哪天想升到 64×64，我建议整体重做图集
   （不是简单放大），因为现在的岛是按 32 排布的。
7. **`.bbmodel` 的打开验证**：文件按 Blockbench 5.2.2 的工程 schema 生成（`meta.format_version=5.0`、
   `model_format=java_block`、face uv 用贴图像素坐标，贴图以 base64 内嵌 + `relative_path` 双保险，
   打开即可见），并与导出的 Java 模型逐块比对一致；
   但我这边**没有 GUI 侧的打开/保存通道**（bb_* 只能开 YSM/Bedrock 包），所以"用 Blockbench 点开看"
   这一步请你们手动确认一次（预期直接可开）。
