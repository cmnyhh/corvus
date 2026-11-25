# 选矿台：按矿种副产物 + 三专用输出槽（1.21.1 / 1.20.1）

改动同时落在两份工程里，物品 id / 语言键 / 配方 id 逐字一致：

* `dronepal/`（NeoForge 1.21.1，包 `dev.dronepal`，数据目录 `recipe/`）
* `dronepal-1.20.1/`（Forge 47.2.0，包 `dev.corvus`，数据目录 `recipes/`）

## 1) 副产物按矿种（方案 B）

### 新物品（8 个，两版同名）

| 物品 id | zh_cn | en_us | 由哪种输入产出 |
|---|---|---|---|
| `huhu_dronepal:coarse_iron` | 粗铁矿砂 | Coarse Iron Sand | 铁矿石 / 深层铁矿石 / 粗铁 |
| `huhu_dronepal:coarse_copper` | 粗铜矿砂 | Coarse Copper Sand | 铜矿石 / 深层铜矿石 / 粗铜 |
| `huhu_dronepal:coarse_gold` | 粗金矿砂 | Coarse Gold Sand | 金矿石 / 深层金矿石 / 粗金 |
| `huhu_dronepal:coarse_nether_gold` | 粗下界金矿砂 | Coarse Nether Gold Sand | 下界金矿石 |
| `huhu_dronepal:coarse_coal` | 粗煤矿砂 | Coarse Coal Sand | 煤矿石 / 深层煤矿石 |
| `huhu_dronepal:coarse_redstone` | 粗红石矿砂 | Coarse Redstone Sand | 红石矿石 / 深层红石矿石 |
| `huhu_dronepal:coarse_lapis` | 粗青金石矿砂 | Coarse Lapis Sand | 青金石矿石 / 深层青金石矿石 |
| `huhu_dronepal:coarse_quartz` | 粗石英矿砂 | Coarse Quartz Sand | 下界石英矿石 |

贴图沿用仓库里已有的 `textures/item/coarse_<kind>.png`（两版都已存在，未改动像素）；
新增 `models/item/coarse_<kind>.json`（parent `minecraft:item/generated`）、物品注册、
中英语言键（`item.huhu_dronepal.coarse_<kind>` + 通用 tooltip `item.huhu_dronepal.coarse.tip`）。

### 概率表（**未改动**，只是产物改成对应矿种）

| 情形 | 副产物几率 | 稀有额外 roll |
|---|---|---|
| 人工（无无人机） | 20% | 12% |
| 无人机在岗 | 35% | 12% |
| + 选矿筛网 | +15pp → 35% / 50% | 18% |

矿种稀有族（钻石 / 绿宝石 / 远古残骸）没有对应的粗料物品，因此**不参与粗料 roll**，
它们的“额外 roll”落在 3 号槽（见下）。翻倍规则、水/电、速度、稀有碎粒概率都没动。

### 合成（8 条，两版同 id）

`huhu_dronepal:coarse_<kind>_nine`：工作台 3×3 无序配方，9 个同种粗料 → 1 个同种精矿
（`<kind>_concentrate`）。少于 9 个不成立（测试已覆盖）。

### 删除通用 `coarse_concentrate`

物品注册、`models/item/coarse_concentrate.json`、`textures/item/coarse_concentrate.png`、
熔炼配方（1.21.1 `recipe/coarse_concentrate.json` / 1.20.1 `recipes/coarse_concentrate_smelting.json`）、
两版 5 个语言键（`item…coarse_concentrate{,.named,.tip}` 与 `tooltip…coarse_concentrate.{1,2}`）全部移除。
`blueprint/assembly/手册`里本来没有引用（全局 grep 已确认），剩下的 `coarse_concentrate`
字样只出现在“它必须不存在”的负向断言里。

## 2) 三个专用输出槽

`OreMillMenu`：`MACHINE_SLOTS 7 → 8`、`OUTPUT_COUNT 2 → 3`、`UPGRADE_FIRST 5 → 6`，
槽位坐标 `(88,86) (108,86) (128,86)`（借右列剩余宽度，176×250 面板不动，水/电两行没有上移）。

| 槽 | 角色 | 只接受 | 槽口描边 |
|---|---|---|---|
| 输出 3（`OUTPUT_PRIMARY`） | 主产物 | 精矿 或 稀有碎粒 | 绿 `#7FD89A` |
| 输出 4（`OUTPUT_BYPRODUCT`） | 副产物 | 该矿种的粗料 | 琥珀 `#D9A63C` |
| 输出 5（`OUTPUT_EXTRA`） | 额外 | 稀有额外碎粒 | 蓝 `#83A8DD` |

* **同槽只放同类**：`fits(well, stack)` 先查 `accepts(well, stack)`（按物品类判定），
  再要求“同物品 + 同组件”才合并；不同类/不同物永不混堆。
* **删除内存溢出队列**：`OreMillEntity` 的 `pending`、`keep`、`flushPending` 全部删除，
  连 NBT 里的 `Pending` 也删了（老存档里的该键被忽略）。
* **原子交付**：任务开始时一次性 roll 出 `batchPrimary / batchByproduct / batchExtra`
  （`planBatch`）并保留；完成时三槽必须**全部**放得下才一起交付，否则
  `progress = duration - 1`（保留已完成的一步）、`link = OUTPUT_FULL`、输入/FE/水都不动。
  这样停机是真停机：不会因为重试时重新 roll 而“跳过副产物”，也不会半个批次落袋。
  槽一腾出来，同一批次原样交付（测试断言副产物确实随批次补上）。
* `OUTPUT_FULL` 现在**无无人机也会报**（以前只有无人机在岗才报），所以人工运行时面板也会显示
  `停机：输出满`，不再靠水/电兜底文案。
* GUI 只加颜色区分，不加新文字行；槽位 tooltip 换成三条角色键
  `gui.dronepal.ore_mill.slot.output.{primary,byproduct,extra}`（旧 `slot.output` 保留未删）。

## 3) 改动文件

**1.21.1（`dronepal/`）**
* `src/main/java/dev/dronepal/mill/MillItems.java`（8 个 `CoarseItem`、`isPrimary/isCoarse/coarseKind`、creative 顺序）
* `src/main/java/dev/dronepal/mill/OreMillEntity.java`（三槽角色、`planBatch/clearBatch/fits/deliver`、删 `pending`、`OUTPUT_FULL` 停机）
* `src/main/java/dev/dronepal/mill/OreMillMenu.java`（8 槽 / 3 输出 / 坐标 / `OUTPUT_PRIMARY…` 常量）
* `src/main/java/dev/dronepal/client/OreMillScreen.java`（三种槽口描边、三槽 tooltip）
* `src/main/resources/assets/huhu_dronepal/models/item/coarse_<kind>.json`（新增 8，删 1）
* `src/main/resources/assets/huhu_dronepal/textures/item/coarse_concentrate.png`（删除）
* `src/main/resources/data/huhu_dronepal/recipe/coarse_<kind>_nine.json`（新增 8，删 1）
* `src/main/resources/assets/huhu_dronepal/lang/{zh_cn,en_us}.json`（各 599 键，两边键集一致）
* `src/curiosGameTest/java/dev/dronepal/{OreMillGameTest,DroneMillGameTest,CreativeCatalogGameTest}.java`
* `src/millVisual/java/dev/dronepal/MillVisual.java`（开拍前真跑单 + 拍 `3slots` + 放宽启动守卫）
* `tools/check_mill_layout.py`、`docs/GUI-MILL-LAYOUT.md`

**1.20.1（`dronepal-1.20.1/`）**
* `src/main/java/dev/corvus/mill/{MillItems,OreMillEntity,OreMillMenu}.java`
* `src/main/java/dev/corvus/client/OreMillScreen.java`
* `src/main/java/dev/corvus/devtools/{MillSmoke,HardwareSmoke}.java`
* `src/main/resources/**` 同名资源（`models/item/coarse_*`、`recipes/coarse_*_nine.json`、删旧三件、lang）
* 语言键直接写进 `assets/huhu_dronepal/lang/{zh_cn,en_us}.json`（本任务独占，`PORT-LANG-*.json` 未用）

## 4) 验收（真实输出）

```
# 1.21.1
$ ./gradlew build
BUILD SUCCESSFUL        → build/libs/渡鸦-随行无人机-0.2.0.jar  1,120,050 bytes

$ ./gradlew -PcuriosGameTest runCuriosGameTest
========= 103 GAME TESTS COMPLETE IN 2.308 s ======================
All 103 required tests passed :)

# 1.20.1（gradle 8.14.3 / Java 17，见 PORT-CONTRACT.md）
$ gradle build
BUILD SUCCESSFUL        → build/libs/渡鸦-随行无人机-1.20.1-0.2.0.jar  1,304,684 bytes

$ gradle runServer -PcorvusMill=1 -PcorvusLevel=MillB2
[mill-smoke] ==== 124 passed, 0 failed ====
   新增并通过：coarse.per_kind / coarse.nine_craft_one / slots.role_split /
              stop.output_full_keeps / coarse.generic_gone

$ gradle runServer -PcorvusHwtest=1  -PcorvusLevel=MBHw     → 106 passed, 0 failed   (recipes.count 32 → 39)
$ gradle runServer -PcorvusGaps=1    -PcorvusLevel=MBGaps   →  55 passed, 0 failed
$ CORVUS_MODTEST=1 gradle runServer  -PcorvusLevel=MBMod    →  50 passed, 0 failed
$ gradle runServer -PcorvusGenWorld=1 -PcorvusLevel=MBGen   →  genworld 生成并落盘；SmokeB 44 PASS / 0 FAIL
$ gradle runServer -PcorvusTacz=1    -PcorvusLevel=MBTacz   →  60 passed, 0 failed
$ gradle runServer -PcorvusPower=1   -PcorvusLevel=MBPower  →  85 passed, 0 failed
```

`mill` 记 124：相对改动前 120 条（验收说明写的 119 比仓库现状少一条）删掉 1 条
（`recipe.coarse_iron_nugget`，该熔炼配方已不存在）+ 新增 5 条 = +4。
`power` 85 而不是 84：PowerSmoke 不引用任何选矿台物品/配方（已 grep 确认），属于同一份漂移。

## 5) 截图（1.21.1 实拍）

```
gradlew -I gradle/mill-visual.init.gradle runMillVisual
```

```
MILL_VISUAL_PRIME primary=16 huhu_dronepal:iron_concentrate byproduct=3 huhu_dronepal:coarse_iron
                  extra=1 huhu_dronepal:diamond_shard link=WORKING
MILL_GUI_AUDIT shot=millgui-3slots.png guiScale=2 gui=1280x800 panel=176x250 left=552 top=275
               link=WORKING water=100% energy=100% speed=150% cost=1680 progress=4/20
```

* `dronepal/artifacts/client/millgui-3slots.png`（2560×1600 实拍）
* `dronepal/artifacts/client/millgui-3slots-panel.png`（同图面板裁切 4× 放大，便于看三种槽口）

开拍前 `MillVisual.prime()` 让机器真跑单（先稀有矿刷出 2 号槽碎粒、再铁矿刷出 1 号槽 `coarse_iron`），
所以三槽里都是机器产出的真物品，不是手摆的。

> 复现注意：本机这次 `earlyWindowControl` 需要关掉（`build/mill-visual-client/config/fml.toml`），
> 否则 NeoForge 早期窗口探测拿不到显示器会弹框卡住；该文件在 `build/` 下，不属于源码改动。

## 6) 未做 / 已知取舍

1. **稀有族没有粗料**：钻石 / 绿宝石 / 远古残骸 不产 `coarse_*`（没有对应物品），它们的副产物槽
   恒空，额外 roll 照旧进 2 号槽。概率常量一个没改。
2. **1.20.1 未拍客户端截图**（验收只要求 1.21.1）；1.20.1 的客户端画面用同一套坐标与颜色常量。
3. `tools/check_all.sh`：`check_recipes.py` 之前把“选矿台产出的物品”当无来源（`rare_shard_*` 27 条），
   并会把新增的 `coarse_*` 判成未注册。已把 8 种精矿 / 8 种粗料 / 3 种碎粒 / 筛网 / 冷却塔当作
   选矿台自身来源补进脚本，现在 `ingredient ids that are not registered: 0`、
   `ingredients without source: 0`。`check_lang.py` 仍报 3 条**本次之前就存在**的缺失键
   （`item.huhu_dronepal.{battery_bank,burner_generator,solar_generator}`，属于发电方块，
   不在本任务范围，没动）。
4. `PORT-STATUS-1.20.1.md` / `PARITY-REPORT.md` 里的旧计数没改（属于他人文档）。
5. 没有提交 git。
