# 设计变更记录（2026-11-23 · 待实现）

> 本文只写**设计侧改了什么、需要代码侧做什么**。实现请以 `SPEC.md` 为准。
> 我（设计侧）没有修改任何 Java 文件，`src/main/java` 与 `build/` 保持原样。

---

## 一、新增：厂商（Vendor）系统

**新增数据**：`data/dronepal/vendors/*.json`（5 家）

| id | 名称 | 类型 | 等级代号 | 获取 | 特性 |
|---|---|---|---|---|---|
| `ahi` | 铁砧重工 | 军用 | `Mk.I/II/III` | 军用补给箱 | 功耗 ×1.10、散布 ×0.80 |
| `cvs` | 渡鸦系统 | 军用/民品线 | `R-1/2/3` | 军供 / 遗迹终端 | 扫描半径 ×1.20 |
| `omd` | 万向动力 | 民用 | `100/200/300` | **工作台合成** | 功耗 ×0.85、**最高只到第 2 档** |
| `alb` | 白鸦医疗 | 民用 | `Gen 1/2/3` | 村民医生 / 医疗箱 | 治疗 ×1.15、消耗耗材 |
| `rust` | 锈钉作坊 | 黑市 | `G1/G2/G3` | 遗迹/沉船/矿洞箱 | 功耗 ×0.75、**5% 故障停机 3s** |

**需要代码侧**：
1. 启动时装载 `vendors/` 到注册表（`SimpleJsonResourceReloadListener`）；
2. 物品 tooltip 第一行按 `厂商全称 + 等级代号 + 产品名` 渲染（**不显示 AHI/CVS 缩写**）；
3. `traits` 生效点：`drain_multiplier`（电力扣减）、`spread_multiplier`（射击散布）、`scan_radius_multiplier`、`heal_rate_multiplier`、`malfunction_chance`（随机停机）。

---

## 二、模块：补 `vendor` / `tier_label` / `product_name`

21 个模块 JSON 各新增三个字段，例：

```json
// combat_mk3.json
"vendor": "ahi", "tier_label": "Mk.III", "product_name": "火控组件", "product_name_en": "Fire Control Group"
```

**显示名规则**：`铁砧重工 Mk.III 火控组件`（厂商全称 + 等级代号 + 产品名）。

**映射**：combat→ahi、scan→cvs、link→cvs、carry→omd、light→omd、solar→omd、medic→alb。

---

## 三、机体型号（Frame）重做

**旧**：`scout / standard / heavy`（3 个，按尺寸命名，无厂商）
**新**：`peregrine / owl / bedrock / scrap`（4 个，**按厂商产品名命名**）

| 型号 id | 显示名 | 厂商 | 跨度 | 槽 | 电池 | 速度 | 可挂枪 | 获取 |
|---|---|---|---|---|---|---|---|---|
| `peregrine` | **渡鸦系统 游隼** | cvs | 1.45 | 3 | mk3 | ×1.0 | +步枪/狙击 | 合成 |
| `owl` | 渡鸦系统 夜枭 | cvs | 1.10 | 2 | mk2 | ×1.25 | 手枪/冲锋枪 | 军供 |
| `bedrock` | 铁砧重工 磐石 | ahi | 1.80 | 4 | mk3 | ×0.8 | +RPG | 军供 |
| `scrap` | 锈钉作坊 拼装 | rust | 1.35 | 3 | mk2 | ×0.95 | +步枪 | 开箱 |

**「游隼」= 本项目的原型机**：前文所有尺寸、骨骼、挂载规范对应的都是它。

**需要代码侧（当前缺口）**：
- 物品组件 `dronepal:frame`（ResourceLocation，默认 `dronepal:peregrine`）；
- `DroneEntity` 从 frame 读：碰撞箱、`module_slots`、`battery_max_tier`、`speed_multiplier`、`gun_mount_offset`、`allowed_gun_types`、`solar_bonus`、`malfunction_chance`；
- 槽位 GUI / 轮盘按 `module_slots` 动态生成，不要写死 3；
- 挂枪前校验 `allowed_gun_types`（如夜枭不能挂狙击）。

---

## 四、涂装：新增第 9 套

`data/dronepal/paints/rust.json`（锈钉拼装，橙色发光）。**贴图待补**：`assets/dronepal/textures/entity/drone_rust.png`。

> 现有 8 套的贴图与 `PaintCans.java` / `DroneRenderer.java` 里的硬编码列表都只含 8 个 id，加第 9 套时要同步这三处。

---

## 五、命名：这一架叫「渡鸦系统 游隼」

台词库已改（`data/dronepal/lines/{tactical,chatty}/meta.json`）：

- 战术：`"游隼。渡鸦系统民品线，编号 PR-07。"`
- 话痨：`"我叫游隼！渡鸦系统出厂的，好听吧？"`

**编号规则**：`型号前缀-数字` → 游隼 `PR-07`、夜枭 `OW-03`、磐石 `BR-12`、拼装 `RN-00`。
**称呼规则**：战术芯片念编号/名字，话痨芯片念产品名/昵称；玩家改名（`dronepal:name`）后两者都跟着改。

---

## 六、待实现清单（按优先级）

| # | 项 | 依赖 |
|---|---|---|
| 1 | frame 组件 + 实体按 frame 初始化（槽位/碰撞/速度/挂枪白名单） | 无 |
| 2 | vendor 注册表 + **本地化拼名**（见 §七） | 无 |
| 3 | `traits` 生效（功耗/散布/扫描/治疗/故障） | 1、2 |
| 4 | 第 9 套涂装贴图 + 三处硬编码列表 | 无 |
| 5 | 型号专属合成/军供掉落表（`recipe/` `loot_table/` 已有一批，需按新 id 对齐） | 1 |

> `recipe/` 与 `advancement/` 里现有 37 + 29 个 JSON 是**按旧模块 id** 生成的，模块 id 未变、可以复用；但**型号 id 变了**，与 frame 相关的配方需要重新对齐。

---

## 七、本地化命名（新增，优先级高）

显示名改为**语言键拼装**，不再写死中文：

| 组成 | 键 | zh_cn | en_us |
|---|---|---|---|
| 厂商 | `dronepal.vendor.<id>` | 渡鸦系统 | Corvus Systems |
| 产品 | `dronepal.product.<family>` | 火控组件 | Fire Control Group |
| 型号 | `dronepal.frame.<id>` | 游隼 | Peregrine |
| 类别 | `dronepal.class.<id>` | 标准型 | Standard |
| 拼装格式 | `dronepal.name.module` / `dronepal.name.frame` | `%s %s %s` / `%s %s` | 同 |

语言文件已写入 24 个键（`assets/dronepal/lang/{zh_cn,en_us}.json`，各 82 键）。

**实现要点**
```java
String vendor  = Component.translatable("dronepal.vendor." + v).getString();
String product = Component.translatable("dronepal.product." + family).getString();
String name    = Component.translatable("dronepal.name.module", vendor, tierLabel, product).getString();
```
- **等级代号不本地化**（`Mk.III`、`R-2`、`200`、`Gen 3`、`G1` 原样显示）；
- 回退：语言键 → JSON `name_zh`/`name_en` → 原始 id；
- **不要把完整名写进 JSON**，否则切语言就废；
- 现有 58 个界面键（轮盘/终端）保持不动，本次只新增命名相关键。

---

## 八、组件型号扩展（新增数据 + 规则）

**先定规则再加货**，避免清单爆炸（详见 SPEC §4.10 E1–E4）：

- E1 同一族最多 **2 个厂商**；E2 每厂商每族只做 **1 条产品线**；
- E3 档位上限仍是 **3**；E4 厂商差异写在 `traits`，不写在档位里；
- 目标规模：**7 族 × 2 厂商 × ≤3 档 ≈ 30~34 个模块**。

**本次新增 6 个数据**（`data/dronepal/modules/`，锈钉廉价线补全后共 27 个模块）：

| id | 显示名 | 功耗 | 特点 |
|---|---|---|---|
| `combat_rust_g1` | 锈钉作坊 G1 土制击发器 | 10 | 伤害 ×0.85、散布 9°、**6% 卡壳停机 2.5s** |
| `carry_rust_g1` | 锈钉作坊 G1 焊铁货筐 | 4 | 12 格（比 OMD 100 多），但**受击 15% 掉件** |
| `light_rust_g1` | 锈钉作坊 G1 拆车大灯 | 3 | 光强 14 / r9（比 OMD 100 亮），但**会闪烁** |
| `scan_rust_g1` | 锈钉作坊 G1 拼装测距仪 | 5 | r14 仅敌对（比 R-1 小），**8% 误报**把石头当敌人 |
| `solar_rust_g1` | 锈钉作坊 G1 捡来的板子 | 0 | 晴 5（正品 8），**输出抖动 ±30%** |
| `medic_rust_g1` | 锈钉作坊 G1 土法止血包 | 5 | 治疗更快，**20% 附加反胃 10 秒** |

**需要代码侧**：新增参数 `malfunction_chance` / `malfunction_ticks`（模块级）、`drop_chance_on_hit`（carry）、`flicker`（light）、`output_jitter`（solar）、`side_effect_chance` / `side_effect` / `side_effect_ticks`（medic）。

---

## 九、电池目录（新增 10 个型号）

`data/dronepal/batteries/*.json`，字段：`vendor / tier / tier_label / product_name / capacity_fe / max_receive / weight`（锈钉额外 `leak_chance_per_10s` / `leak_fraction`）。

| 厂商 | 型号 | 容量 | 充电 | 重量 |
|---|---|---|---|---|
| 万向动力 | 100 / 200 / 300 标准电池组 | 18万 / 35万 / 60万 FE | 300 / 400 / 500 | 0.6 / 1.0 / 1.5 |
| 铁砧重工 | Mk.I / Mk.II / Mk.III 装甲电池 | 25万 / 60万 / 120万 FE | 600 / 800 / 1000 | 1.3 / 2.0 / 2.8 |
| 渡鸦系统 | R-1 / R-2 轻量电芯 | 15万 / 30万 FE | 400 / 500 | 0.35 / 0.55 |
| 锈钉作坊 | G1 / G2 拆机电芯 | 40万 / 80万 FE | 500 / 700 | 1.1 / 1.7（**会漏电**） |

**需要一个决策 + 一个实现**：
1. **重量是否影响移速**（建议：是。`总重 = frame.weight + battery.weight + Σ模块.weight`，移速修正 `-2%/kg` 上限 −25%）——若不做，重量字段就只是展示值；
2. 电池接入 `IEnergyStorage`（`Capabilities.EnergyStorage.ITEM`），`max_receive` 直接作为上限；
3. 锈钉漏电按 10 秒一次掷骰，扣除 `leak_fraction × 当前电量`。

---

## 十、交战策略（新增 5 档 + 硬性免伤）

`data/dronepal/engage_modes/*.json`：

| 档 | 自动索敌 | 还击 | 打主人目标 | 过滤 |
|---|---|---|---|---|
| `passive` 被动 | — | ✗ | ✗ | — |
| `hostile_only` 仅敌对 | 16 格 | ✓ | ✗ | 只打 Monster |
| **`retaliate` 反击（默认）** | — | ✓ | ✗ | 攻击者 |
| `assist` 协战 | — | ✓ | ✓ | 主人当前目标 |
| `aggressive` 主动 | 16 格 | ✓ | ✓ | 只打 Monster |

**`_guard.json` 是不可覆盖的硬规则**：永不打主人/队友/主人宠物/村民/铁傀儡/悦灵；PVP 默认关；开火前检查弹道 1.5 格友军；需视线；`min_range 3.0` / `max_range 16.0`。

**实现要点**
1. 组件 `dronepal:engage_mode`（默认 `dronepal:retaliate`）；
2. 轮盘 `E` 扇区循环切换；`combat mk3` 的开火模式改为**长按 E 的二级环**；
3. 无 `mod_combat` 时整个策略入口灰掉并播报"没有武器模块"；
4. **免伤检查必须发生在开火前**（`DroneCombat` 里拦截），不能只靠索敌阶段过滤——玩家会走进弹道。

---

## 十一、机动性重做：删掉"移速"，换成三个可感知量

**问题**：无人机有瞬移兜底（原 `FollowOwnerGoal` >16 格直接传送），最高速对玩家几乎不可感知，`speed_multiplier` 是无效字段。

**替代方案**（帧数据已改）：

| 字段 | 含义 | 玩家怎么感知 |
|---|---|---|
| `follow_teleport_threshold` | 离多远开始瞬移 | 夜枭 13 格紧贴你，磐石 24 格才肯追 |
| `teleport_cooldown_ticks` | 瞬移冷却 | 磐石 80t = 战斗中容易掉队 |
| `accel_response` | 加速响应（迟滞） | 起跑/转向"慢半拍"，手感差异 |
| `knockback_resistance` | 抗击退 | 爆炸/近战能否把它打飞 |
| `mount_capacity` | 挂载承重 | 决定能挂多长的枪 |

**电池同步调整**：`weight` → **`mass`**，不再影响移速，改为给 `knockback_resistance_bonus` / `mount_capacity_bonus`。

**需要代码侧**
1. `FollowOwnerGoal` 的 `followDistance` 从 `follow_teleport_threshold` 读，并在瞬移前检查 `teleport_cooldown_ticks`；
2. `accel_response` 作用在 `MoveControl` 的目标速度插值系数（不是最大值）；
3. 锈钉拼装的 `teleport_fail_chance: 0.08` → 8% 概率瞬移失败（原地"卡一下"，并播报"信号不稳"）；
4. 挂枪前校验 `mount_capacity >= 枪长系数`（长枪需要更大承重）。

---

## 十二、合成链（三级中间产物 + 装配台，替换现有 37 个配方）

**原则：能合成的只有民用；军用/黑市只能拿。**

| 可合成 | 不可合成 |
|---|---|
| 游隼（本体）、三款芯片、万向动力 100/200/300、万向动力电池 100/200/300、涂装喷罐、充电座、医用耗材 | 铁砧重工全部、渡鸦系统全部、白鸦医疗全部（交易）、锈钉全部（开箱）、夜枭、磐石 |

**关键配方**
- **游隼** = 铁锭×4 + 铜锭×2 + 红石×2 + 玻璃×1（中心玻璃）
- **民用模块 mk1** = 铁×2 + 铜×1 + 红石×2 + 该族功能件（combat 火药 / scan 紫水晶 / carry 箱子 / light 荧石粉 / solar 玻璃板+铜 / medic 耗材 / link 铁+红石）
- **模块升级** mk1→mk2→mk3，**消耗同族上一档**（不能跳档、不能降级回收）
- **电池** 100 = 铁×3+铜×4+红石×4；200/300 逐级升级
- **芯片** = 铁+红石+差异化件（战术=指南针 / 话痨=书 / 静默=羊毛）
- **喷罐** = 铁+染料+火药；**充电座** = 铁×6+铜×2+红石

**三条防滥用**
1. 升级必须消耗上一档，不可跳档，不可反向回收；
2. 军用件永不进合成表，也不存在"民用+稀有=军规"的洗白路径；
3. 喷罐只改外观，不参与任何配方。

**待做**：`recipe/` 37 个 + `advancement/` 29 个按本表重做（模块 id 不变，文件名可复用）。

---

### 十二·附：合成链加长（成本 ×7）

**新增 20 个中间物品**（需要注册 + 语言键 + 贴图）：

| 层 | 物品 |
|---|---|
| L2 零件（工作台） | 机架、伺服电机、电路板、电池芯、光学组件、装甲板、散热片 |
| L3 子系统（装配台） | 飞控板、电调、动力总成、传感模块、武器接口、医疗泵组 |
| L4 核心（装配台） | 火控核心、嗅探核心、货舱核心、修复核心、照明核心、光伏核心、链路核心 |
| 套件 | 升级套件、高级升级套件 |
| 方块 | **装配台**、充电座 |

**成本变化**：一个民用模块 mk1 从 ≈6 件原料 → **≈45 件原料、跨 3 道工序**；mk3 还需下界合金碎片。
**手感目标**：首模块 20~30 分钟，mk3 2~3 小时。

**实现要点**
1. 新增方块 `dronepal:assembly_bench`（装配台），只用 `RecipeType` 区分/或用普通工作台+配方白名单，二选一；
2. 所有中间物品 `stacksTo(64)`，不进创造页签除了一批 `dronepal:parts` 分组；
3. 军用件（AHI/CVS/ALB/RN）**不进任何配方**，只走 loot/交易。
