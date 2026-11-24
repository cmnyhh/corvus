# Dronepal · 执行规格（自包含）

> 本文件是**交接用规格书**：一位没有参与前期讨论的实现者（人或 AI）读完这一份就能开工。
> 所有决策都已定稿并附理由；被否掉的方案列在 §11，**不要重新讨论它们**，除非发现硬性技术障碍。

- 目标产品：Minecraft **1.21.1 / NeoForge** 的随行无人机 mod
- 一句话验收：*在整合包里装备一架无人机，它会跟着你、能挂 TaCZ 的真枪开火、装了模块才有对应功能、芯片决定它怎么说话，且**不能改变世界**、**不联网**。*

---

## 1. 硬约束（不可协商）

| # | 约束 | 实现方式（不是靠权限判断，而是根本不提供接口） |
|---|---|---|
| C1 | **不能改变世界** | 不注册任何 PlaceBlock / DestroyBlock / GiveItem 能力。无人机只有四类能力：移动、说话、**纯客户端**标记、从自己库存取物投递给主人 |
| C2 | **不联网、不接 LLM** | 代码里没有 HTTP 客户端、没有 API key、没有遥测。对话 = 规则触发 + 本地索引 + 台词库 |
| C3 | **能力来自硬件** | 没装模块 → 没有对应 Goal，也没有对应意图分支。不存在"有功能但被禁用" |
| C4 | **全软依赖** | TaCZ / Curios / GeckoLib 全部 `compileOnly` + 运行时 `ModList.isLoaded()`；缺任何一个都能启动 |
| C5 | **数据驱动** | 芯片 / 模块 / 意图 / 台词 / 触发点全部是 datapack JSON；加内容不改 Java |
| C6 | **允许轻微穿模** | 不做模型 AABB 测量、不做 per-gun 兼容数据包、不做动态避让 |

---

## 2. 版本矩阵与环境

| 组件 | 版本 | 必需 |
|---|---|---|
| Minecraft | 1.21.1 | ✅ |
| NeoForge | 21.1.x（≥21.1.190） | ✅ |
| Java | 21 | ✅ 本机已有：`/Users/lyk/Library/Java/JavaVirtualMachines/corretto-21.0.12.1/Contents/Home` |
| TaCZ | 1.2.0（`1.2.0-neoforge-1.21.1`） | 可选 |
| Curios | 9.x（neoforge-1.21.1） | 可选 |
| GeckoLib | 4.7.x（neoforge-1.21.1） | 可选 |

**为什么是 NeoForge**：1.20.2 起 Forge/NeoForge 分叉，TaCZ 在 1.21.1 上只发布 NeoForge 版（已核实仓库分支 `1.21.1` 与 tag `1.2.0-neoforge-1.21.1`）。

**构建**：ModDevGradle（`net.neoforged.moddev`），Java 21 toolchain，`gradle.properties` 里写死 `org.gradle.java.home`。
**开发机注意**：`~/Library/Application Support/minecraft` 为空，`PrismLauncher/instances` 也为空 —— **目前没有可用的整合包实例**，这是 P0 的唯一阻塞项。

---

## 3. 架构定稿

### 3.1 权威归属：实体

```
DroneEntity（权威：电量、当前目标、位置、状态机、名称）
DroneItem  （配置 + 快照：芯片 / 3 模块 / 电池 / 上次电量 / 上次位置）
玩家 Attachment（只存一个 entityUUID，用于重登恢复与防多实例）
```

理由：用户要求无人机能战斗（被打、索敌、开火），这要求它是真实体；纯"物品存全部状态"只适合纯装饰。

### 3.2 搬运点（只有这些时刻写物品）

装备 / 卸下 / 打开 GUI / 换电池 / 每 30 秒兜底 / 实体即将销毁（区块卸载、维度切换、死亡）。

**禁止**在 tick 里写物品组件 —— `DataComponents` 变更会触发整条物品同步链。

### 3.3 实体生命周期

| 情况 | 规则 |
|---|---|
| 装备物品 | 在玩家身边生成实体（若已存在则复用，按 UUID 去重） |
| **卸下物品** | 实体立即 discard —— **这就是"召回"，没有独立的召回动作** |
| 玩家死亡 | 物品按 Curios 规则处理；重生后物品在身上则重建 |
| 换维度 | 物品清引用，目标维度重建 |
| 区块卸载 / 距离 >64 格 | discard + 冷却重建（防抖 1 秒） |
| 无人机被杀死 | **软失败**：30 秒后在玩家身边重建，**物品与内容物全部保留** |
| 物品丢失 / 被 `/clear` | 实体每 40 tick 校验物品存在，否则自毁 |
| 物品被复制 | 绑定物品 UUID，同 UUID 只允许一个实体存活 |

### 3.4 内容物保留（软失败）

芯片、模块、电池、机身库存、挂载的枪及其弹匣**全部保留**。消耗掉的弹药/电量不恢复。
**三条红线**：
1. `die()` 里覆盖 `dropAllDeathLoot()`，**不掉任何东西**（否则可"召唤→打死→捡枪"无限刷）；
2. 组件是唯一真相，实体不留影子副本；
3. 重建时从组件重建，丢弃尸体内存状态。

### 3.5 状态机

```
IDLE（悬停）→ FOLLOW（>8 格加速，>16 格瞬移，>32 格回收）→ ENGAGE（交战）
→ STOWING/STOWED（收纳）→ DEPLOYING（展开）→ CHARGING → REPAIR（30s 重建）
```
`STOWED / STOWING / DEPLOYING` 期间**拒绝开火**。

---

## 4. 数值表（全部定稿，可 config 覆盖）

### 4.1 尺寸

| 项 | 值 |
|---|---|
| 整机跨度（含旋翼） | **1.45 × 1.45 格**（全局缩放 0.66） |
| 总高（含挂枪） | 0.87 格 |
| 机体本体 | 0.58 × 0.58 格 |
| **碰撞箱** | **0.7 × 0.7 × 0.7（只算机体，旋翼不参与碰撞）** |

### 4.2 槽位与电力

`1× AI 芯片` + `3× 功能模块` + `1× 电池`

电池也是**厂商产品**，10 个型号（`data/huhu_dronepal/batteries/*.json`）：

| 厂商 | id | 代号 | 容量 | 充电上限 | 质量 | 特点 |
|---|---|---|---|---|---|---|
| **万向动力** | `omd_bat_100/200/300` | 100 / 200 / 300 | 18万 / 35万 / 60万 FE | 300 / 400 / 500 FE/t | 0.6 / 1.0 / 1.5 | 均衡、便宜；充电偏慢 |
| **铁砧重工** | `ahi_bat_mk1/2/3` | Mk.I / Mk.II / Mk.III | 25万 / 60万 / **120万** FE | 600 / 800 / **1000 FE/t** | 1.3 / 2.0 / **2.8** | 容量最大、充得最快，但**很重** |
| **渡鸦系统** | `cvs_bat_r1/r2` | R-1 / R-2 | 15万 / 30万 FE | 400 / 500 FE/t | **0.35 / 0.55** | 极轻，容量小——给侦察型省重量 |
| **锈钉作坊** | `rn_bat_g1/g2` | G1 / G2 | 40万 / 80万 FE | 500 / 700 FE/t | 1.1 / 1.7 | 容量虚高，**每 10 秒 2~3% 概率漏 5~6% 电** |

**规则**
- 电池有数字 `tier`（1~3），`frame.battery_max_tier` 按**数字**比较，与厂商代号无关；
- 电池**不算"功能模块"**，不占 3 个功能槽，独立一槽；
- **质量（mass）不影响移速**（瞬移兜底让移速无意义），改为影响：**被击退/被爆炸推开的距离**（`knockback_resistance_bonus`）与 **挂载承重**（`mount_capacity_bonus`，决定能挂多长的枪）；
- 漏电/抖动这类副作用**只在锈钉产品上**，是它便宜的代价。

**典型搭配**
| 机体 | 推荐电池 | 理由 |
|---|---|---|
| 夜枭（2 槽） | `cvs_bat_r1` | 轻，不然侦察型失去意义 |
| 游隼（3 槽） | `omd_bat_200` / `ahi_bat_mk1` | 均衡 / 追求续航 |
| 磐石（4 槽） | `ahi_bat_mk3` | 只有它能扛住满配 65 FE/t |
| 拼装 | `rn_bat_g1` | 便宜，反正本来就会抽风 |

电量 <20%：关最贵模块并 HUD 标红；耗尽：模块全离线，只剩跟随+悬浮（此时不耗电）。

### 4.3 模块（7 族 × 3 档）

**规则**
- 每个模块有 `family`（族）与 `tier`（1~3），JSON 按 `data/huhu_dronepal/modules/<family>_mk<tier>.json` 存；
- **同一族只能装一个**（不允许 3 个 combat）；不同族可混装；
- 等级越高：**功耗上升、效果变强**；mk3 需要稀有材料合成；
- 物品层面：**一个物品 `huhu_dronepal:module` + 两个组件** `module_family` / `module_tier`（不为 21 种组合各建一个物品）。

| 族 | mk1 | mk2 | mk3 |
|---|---|---|---|
| **combat** 武器 | 14 FE/t · 射程16 · 3连发 · 散布6° | 18 · 20 · 4连发 · 伤害×1.15 · 4° | 22 · 24 · 5连发 · ×1.30 · 2.5° · 支持单发/点射/连发切换 |
| **scan** 扫描 | 6 · r16 · 仅敌对 | 9 · r24 · 敌对+常见矿 | 12 · r32 · 敌对+全部矿 · 标记持续 20s |
| **carry** 载物 | 5 · 9格 · 拾取r6 | 8 · 18格 · r8 · 自动分类 | 12 · 27格 · r10 · **自动投递**（不用回身） |
| **medic** 医疗 | 4/12 · 1HP/2s · 单次6 · cd40s | 6/16 · 1.5HP/2s · 单次8 · cd30s | 8/20 · 2HP/2s · 单次12 · cd20s · 额外抗性 I 3s |
| **light** 照明 | 2 · 光强12 · r8 | 3 · 光强15 · r12 | 4 · 光强15 · r16 · 水下也亮 |
| **link** 链接 | 3 · 弹药播报 | 5 · +命中/击杀播报 +手动递弹匣 | 7 · +自动补弹 +护甲提示 |
| **solar** 太阳能 | 0 · 晴8 雨2 | 0 · 晴12 雨4 | 0 · 晴16 雨6 夜2 |

**电量参照**：全 mk3 满配 65 FE/t → mk3 电池（3M FE）约 38 分钟；全 mk1 满配 30 FE/t → 约 83 分钟。
**三种典型 build**：打架（combat+medic+link=29）、探索（scan+carry+solar=11，可净回电）、辅助（medic+light+solar=6）。

### 4.4 充电

三处暴露 `Capabilities.EnergyStorage`：电池物品（ITEM）、充电座方块（BLOCK，只收不放）、无人机实体（ENTITY）。
充电座支持两种输入：**FE 线缆**（上限 2,000 FE/t）与**烧煤兜底**（1 煤 = 4,000 FE，上限 200 FE/t），保证无能量 mod 也能玩。
电池 `maxReceive` 上限 500 FE/t，避免大电网瞬间充满。

### 4.5 挂载与姿态

| 项 | 值 |
|---|---|
| 交战态 pitch | 0°（枪口水平朝前） |
| **收纳态 pitch** | **+85°（枪口朝下）** |
| yaw 范围 | ±75°，超出则机身整体转向 |
| 过渡 | 收纳 0.45s / 展开 0.35s（展开 ≤ TaCZ `draw_time`） |
| 收纳时悬停 | +0.4 格（枪口朝下会贴地） |
| 挂点偏移 | 固定 **0.22 格**向下；RPG/狙击等超长枪用 0.32 格 |
| 缩放 | **不缩放**（用枪包自带 hand transform） |
| 接缝 | `gun_mount` 上盖一块 0.06 格挂架板 |

---


### 4.7 机体型号（Frame）

**一机型一 JSON，换型号 = 换数据 + 换模型，不改代码。**

`data/huhu_dronepal/frames/<id>.json`：
```json
{ "display_name":"dronepal.frame.heavy", "class_name":"重装型", "callsign_prefix":"HV",
  "model":"huhu_dronepal:geo/heavy", "default_paint":"huhu_dronepal:standard",
  "collision":[0.9,0.9,0.9], "span_blocks":1.80,
  "module_slots":4, "battery_max_tier":3,
  "speed_multiplier":0.8, "weight":4.2,
  "gun_mount_offset":0.32,
  "allowed_gun_types":["pistol","smg","rifle","sniper","rpg"],
  "solar_bonus":0.6 }
```

首发 4 型。**型号是厂商的产品名，像飞机一样有名有姓**：

| 型号 id | 厂商 | 产品名 | 跨度 | 槽 | 电池 | 瞬移阈值 | 瞬移冷却 | 加速响应 | 抗击退 | 承重 | 可挂枪 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `peregrine` | **渡鸦系统** | **游隼** | 1.45 | 3 | mk3 | 16 格 | 40t | 1.00 | 0.30 | 1.0 | +步枪/狙击 |
| `owl` | 渡鸦系统 | 夜枭 | 1.10 | 2 | mk2 | **13 格** | **20t** | 1.25 | 0.05 | 0.6 | 手枪/冲锋枪 |
| `bedrock` | 铁砧重工 | 磐石 | 1.80 | 4 | mk3 | **24 格** | **80t** | 0.75 | **0.85** | 1.6 | +RPG |
| `scrap` | 锈钉作坊 | 拼装 | 1.35 | 3 | mk2 | 18 格 | 60t | 0.90 | 0.25 | 1.0 | +步枪 |

> **为什么没有"移速"**：无人机有瞬移兜底，最高速玩家几乎感知不到。可感知的只有三件事——
> **① 多近才瞬移（阈值） ② 多久能再瞬移（冷却） ③ 跟上你时的反应迟滞（加速响应）**。
> 所以夜枭"贴得紧"（13 格就瞬移、20t 冷却），磐石"掉队很远才追"（24 格、80t 冷却），手感差异一玩就知道。

**「游隼」是本项目的原型机**（就是前文所有尺寸/挂载规范对应的那一架）：渡鸦系统民品线的主力机。

**规则**
- 型号决定：模型、碰撞箱、跨度、模块槽数、电池上限、移速、挂点偏移、可挂枪类型、太阳能系数；
- **型号不可在游戏内切换**（换型号 = 换一件无人机物品），避免"背包里换皮"绕过重量/槽位设计；
- 编号 = `型号前缀-数字`（游隼 `PR-07`、夜枭 `OW-03`、磐石 `BR-12`、拼装 `RN-00`），玩家可另起昵称（`huhu_dronepal:name`）；
- 战术芯片念呼号，话痨芯片念昵称；召回播报同样区分。


### 4.8 厂商（Vendor）与命名体系

**每个部件都有制造方。等级代号由厂商决定，所以同为"第二档"，名字完全不同。**

`data/huhu_dronepal/vendors/<id>.json`：
```json
{ "name_zh":"铁砧重工", "name_en":"Anvil Heavy Industries", "prefix":"AHI",
  "tier_labels":["Mk.I","Mk.II","Mk.III"], "category":"military", "badge_color":"#8B1E1E",
  "traits":{ "drain_multiplier":1.10, "spread_multiplier":0.80 },
  "obtain":{ "method":"military_supply", "note":"军用补给箱 / 军械商交易" },
  "flavor":"只卖给有编号的客户。" }
```

| 厂商 | 类型 | 代号样式 | 产品线 | 获取 | 特性 |
|---|---|---|---|---|---|
| **铁砧重工 AHI** | 军用 | `Mk.I / Mk.II / Mk.III` | 火控组件（combat）、重装型机体 | 军用补给箱 / 军械商 | 功耗 +10%，但散布 −20% |
| **渡鸦系统 CVS** | 军用 | `R-1 / R-2 / R-3` | 频谱嗅探器（scan）、弹药链路（link）、侦察型机体 | 军供 / 遗迹终端 | 扫描半径 +20% |
| **万向动力 OMD** | 民用 | `100 / 200 / 300` | 货舱挂架（carry）、照明灯组（light）、光伏膜（solar）、标准型机体 | **工作台合成** | 功耗 −15%，但**最高只到第二档** |
| **白鸦医疗 ALB** | 民用 | `Gen 1 / Gen 2 / Gen 3` | 纳米修复套件（medic） | 村民医生 / 医疗箱 | 治疗 +15%，**消耗医用耗材** |
| **锈钉作坊 RN** | 黑市 | `G1 / G2 / G3` | （v0.5 铺开） | 遗迹 / 沉船 / 矿洞箱 | 功耗 −25%，**5% 概率故障停机 3 秒** |

**命名呈现**：物品名 = `厂商全称 + 等级代号 + 产品名`（**不显示 AHI/CVS 这类缩写**，缩写只用于数据 id 与贴图路径）

| 内部 id | 显示名 |
|---|---|
| `ahi` + `Mk.II` + 火控组件 | **铁砧重工 Mk.II 火控组件** |
| `cvs` + `R-2` + 频谱嗅探器 | **渡鸦系统 R-2 频谱嗅探器** |
| `omd` + `200` + 光伏膜 | **万向动力 200 光伏膜** |
| `alb` + `Gen 3` + 纳米修复套件 | **白鸦医疗 Gen 3 纳米修复套件** |
| `rust` + `G2` + 改装件 | **锈钉作坊 G2 改装件** |

型号同理：`渡鸦系统 游隼`、`铁砧重工 磐石`、`锈钉作坊 拼装`（型号不带等级代号）。

**世界观落到玩法上的三条**
1. **军用件不可合成**——只能从补给箱/军械商拿，所以"好东西"是稀缺的、要跑图的；
2. **民用件便宜好造，但有上限**——OMD 最高只有第二档，想做顶配必须拿军供；
3. **黑市件是赌**——功耗低、来得容易，但会随机停机，适合穷的时候过渡，不适合打硬仗。

> 型号也属厂商：`scout → CVS`、`standard → OMD`、`heavy → AHI`。所以"重装型"出厂就是军规，而"标准型"是民用货——这也解释了为什么重装型能挂 RPG。


### 4.9 本地化命名（优先走语言文件）

**规则：显示名不写死在数据里，由「语言键 + 组装格式」在渲染时拼出来。**

| 组成 | 键 | zh_cn | en_us |
|---|---|---|---|
| 厂商 | `dronepal.vendor.<id>` | 渡鸦系统 | Corvus Systems |
| 产品 | `dronepal.product.<family>` | 火控组件 | Fire Control Group |
| 型号名 | `dronepal.frame.<id>` | 游隼 | Peregrine |
| 类别 | `dronepal.class.<class>` | 标准型 | Standard |
| 组装格式（模块） | `dronepal.name.module` | `%s %s %s` | 同 |
| 组装格式（型号） | `dronepal.name.frame` | `%s %s` | 同 |

**拼法**
```
模块名 = I18n(dronepal.name.module, I18n("dronepal.vendor."+vendor), tier_label, I18n("dronepal.product."+family))
        → 中文: 铁砧重工 Mk.III 火控组件
        → 英文: Anvil Heavy Industries Mk.III Fire Control Group

型号名 = I18n(dronepal.name.frame, I18n("dronepal.vendor."+vendor), I18n("dronepal.frame."+id))
        → 中文: 渡鸦系统 游隼
        → 英文: Corvus Systems Peregrine
```

**等级代号不本地化**：`Mk.I / R-2 / 200 / Gen 3 / G1` 是厂商的产品代号，任何语言都照原样显示（这也是"厂商差异"的一部分）。

**回退顺序**：语言键 → JSON 里的 `name_zh`/`name_en` → 原始 id。
**禁止**：把 `"渡鸦系统 游隼"` 这种完整名直接写进 JSON 的 `display_name`——那样切语言就废了。JSON 里只存 **id + tier_label**，名字全部由语言层拼。


### 4.10 组件型号的扩展规则（**先定规则，再加产品**）

产品不是越多越好——**每个槽位只有 3 个，加到 40 个模块也只是 3 选 1**。所以扩展要遵守四条：

| # | 规则 | 理由 |
|---|---|---|
| E1 | **同一族最多 2 个厂商** | 3 家以上会让"选谁"变成查表；2 家正好是"高端 vs 廉价"的二元选择 |
| E2 | **每厂商每族只做 1 条产品线** | 同厂同族两个型号 = 自相残杀，玩家只会选便宜那个 |
| E3 | **档位上限仍是 3** | 第 4 档开始数值就没意义了，靠"厂商特性"做差异比加档更有效 |
| E4 | **厂商的差异写在 traits 上，不写在档位里** | AHI 精密、OMD 省电、RN 便宜但会抽风——差异是"性格"，不是"数值高低" |

按这四条，最终规模大约是 **7 族 × 2 厂商 × ≤3 档 ≈ 30~34 个模块**，而不是 60+。

#### 当前完整目录（27 个模块 · 已定稿）

| 族 | 主厂商产品线 | 第二厂商（锈钉） | 差异 |
|---|---|---|---|
| **combat** | 铁砧重工 Mk.I / Mk.II / Mk.III 火控组件 | G1 土制击发器 | 精密高伤 · 散布 2.5° ↔ 便宜但 6% 卡壳 |
| **scan** | 渡鸦系统 R-1/2/3 频谱嗅探器 | G1 拼装测距仪 | r32 全矿 ↔ r14 仅敌对、8% 误报 |
| **carry** | 万向动力 100/200/300 货舱挂架 | G1 焊铁货筐 | 27 格自动投递 ↔ 12 格但受击 15% 掉件 |
| **medic** | 白鸦医疗 Gen 1/2/3 纳米修复套件 | G1 土法止血包 | 2HP/2s 附加抗性 ↔ 更快但 20% 附加反胃 10s |
| **light** | 万向动力 100/200/300 照明灯组 | G1 拆车大灯 | 光强 15 · r16 水下可用 ↔ 光强 14 但**会闪** |
| **link** | 渡鸦系统 R-1/2/3 弹药链路模块 | —（军品专有） | 只有渡鸦做 |
| **solar** | 万向动力 100/200/300 光伏膜 | G1 捡来的板子 | 晴 16/夜 2 ↔ 晴 5 且输出抖动 ±30% |

**7 族 · 27 个模块 · 5 厂商**。除 link 外每族都有"高端 vs 廉价"两条线；link 是渡鸦系统的军品独门，所以没有廉价替代（这也是它贵的原因）。

#### 下一步可加的方向（按性价比排序）

| 方向 | 例子 | 收益 |
|---|---|---|
| **同族第二厂商补全** | 锈钉的 scan/solar/medic、万向动力的民用 scan | 高：直接多一种玩法取舍 |
| **套装效果**（同厂 3 件） | 「铁砧重工全套」→ 散布额外 −10% | 中：增加收集动机，但要防止逼玩家只用一套 |
| **特化变体**（同档不同取向） | combat「精准型 / 高射速型」 | 中：适合已有 2 厂商后再加 |
| **非功能件** | 装甲板（+1 抗）、散热片（降故障率） | 低：先做完功能件再说 |


### 4.11 交战策略（Engagement）

**默认 = 反击。** 这是宠物类单位最容易做坏的地方：默认"主动"会让玩家在村里、在下界、在服务器里不断惹事。

`data/huhu_dronepal/engage_modes/<id>.json`，5 档：

| 档 | 自动索敌 | 还击 | 打主人目标 | 过滤 | 说明 |
|---|---|---|---|---|---|
| **`passive` 被动** | 关 | 否 | 否 | 无 | 永不主动开火，被打也不还手 |
| **`hostile_only` 仅敌对** | 16 格 | 是 | 否 | 只打 `Monster` | 不碰中立生物与玩家 |
| **`retaliate` 反击（默认）** | 关 | 是 | 否 | 攻击者 | 只有你或它被攻击才还击 |
| **`assist` 协战** | 关 | 是 | **是** | 主人当前目标 | 优先帮你打你正在打的东西 |
| **`aggressive` 主动** | 16 格 | 是 | 是 | 只打 `Monster` | 会自己去找架打 |

**硬性免伤规则**（`_guard.json`，**任何策略下都生效、不可被策略覆盖**）：

| 规则 | 值 |
|---|---|
| 永不打 | 主人、主人所在 scoreboard 队伍、主人的宠物（`TamableAnimal` owner = 主人）、村民、铁傀儡、悦灵 |
| PVP | **默认关闭**；服务器可全局禁止，玩家开启需二次确认 |
| 友伤检查 | 开火前检查弹道 1.5 格内是否有队友 → 有则不射（防“挡枪线误伤”） |
| 视线 | 必须有 `hasLineOfSight`，不打墙后的 |
| 距离 | `min_range 3.0`（太近不开火，防贴脸自伤）/ `max_range 16.0` |
| 无 combat 模块 | 整个策略菜单灰掉，播报"没有武器模块" |

**切换入口**：轮盘 `E`（右）扇区循环切换，HUD 常驻显示当前档位图标。
**存档**：物品组件 `huhu_dronepal:engage_mode`（默认 `huhu_dronepal:retaliate`）。


### 4.12 合成链（三级中间产物 + 装配台）

**目标**：一个民用模块 ≈ 8~15 个原料、跨 3 道工序；军用件仍然只能拿，不能造。

#### 工序分层

| 层 | 在哪做 | 产物 | 作用 |
|---|---|---|---|
| **L1 原料** | 挖矿/打怪 | 铁、铜、红石、金、石英、荧石、火药、玻璃、紫水晶、钻石、皮革、线 | 基础 |
| **L2 零件** | **工作台** | 机架 / 伺服电机 / 电路板 / 电池芯 / 光学组件 / 装甲板 / 散热片 | 通用件，多族共用 |
| **L3 子系统** | **装配台**（新方块） | 飞控板 / 电调 / 动力总成 / 传感模块 / 武器接口 / 医疗泵组 | 卡进度，军用件也需要 |
| **L4 模块核心** | **装配台** | 火控核心 / 嗅探核心 / 货舱核心 / 修复核心 / 照明核心 / 光伏核心 / 链路核心 | 决定模块类型 |
| **L5 成品** | 装配台 | 模块 mk1；mk2/mk3 = 上一档 + 升级套件 | |

#### L2 零件（工作台）

| 零件 | 配方 | 用途 |
|---|---|---|
| **机架** | 铁锭×4 + 铜锭×2 | 所有机体与模块外壳 |
| **伺服电机** | 铜锭×3 + 红石×2 + 铁锭×1 | 电调、武器接口 |
| **电路板** | 金锭×1 + 红石×4 + 石英×2 | 飞控、电调、几乎所有核心 |
| **电池芯** | 铜锭×2 + 红石×2 + 纸×1 | 电池与动力总成 |
| **光学组件** | 玻璃×2 + 紫水晶碎片×1 | 传感、扫描、镜头 |
| **装甲板** | 铁锭×3 + 皮革×1 | 重装机体、抗击退 |
| **散热片** | 铁锭×2 + 蓝冰×1 | 高功耗模块（combat/medic mk3）必须 |

#### L3 子系统（装配台）

| 子系统 | 配方 |
|---|---|
| **飞控板** | 电路板×2 + 伺服电机×1 + 金锭×1 |
| **电调** | 电路板×1 + 伺服电机×2 + 铜块×1 |
| **动力总成** | 电池芯×2 + 电调×1 + 机架×1 |
| **传感模块** | 光学组件×2 + 电路板×1 |
| **武器接口** | 机架×1 + 伺服电机×2 + 铁锭×2 |
| **医疗泵组** | 电池芯×1 + 玻璃×2 + 糖×2 |

#### L4 模块核心（装配台）

| 核心 | 配方 | 产出族 |
|---|---|---|
| 火控核心 | 武器接口×1 + 电路板×2 + 火药×4 + 散热片×1 | combat |
| 嗅探核心 | 传感模块×1 + 光学组件×2 + 紫水晶×2 | scan |
| 货舱核心 | 机架×1 + 箱子×1 + 伺服电机×2 | carry |
| 修复核心 | 医疗泵组×1 + 糖×3 + 红石×2 | medic |
| 照明核心 | 光学组件×1 + 荧石×2 + 铜锭×2 | light |
| 光伏核心 | 光学组件×2 + 铜锭×3 + 玻璃板×2 | solar |
| 链路核心 | 电路板×2 + 铁锭×2 + 红石×3 | link |

#### L5 成品

| 产物 | 配方 |
|---|---|
| **民用模块 mk1** | 对应核心×1 + 机架×1 + 散热片×1（combat/medic 必须） |
| **民用模块 mk2** | 同族 mk1 + 升级套件（电路板×2 + 铜块×1 + 紫水晶×1） |
| **民用模块 mk3** | 同族 mk2 + **高级升级套件**（电路板×3 + 钻石×1 + 下界合金碎片×1 + 散热片×2） |
| **游隼机体** | 机架×2 + 动力总成×1 + 飞控板×1 + 传感模块×1 + 玻璃×1 |
| **电池 100** | 电池芯×3 + 机架×1 |
| **电池 200** | 电池 100 + 电池芯×2 + 铜块×2 |
| **电池 300** | 电池 200 + 电池芯×4 + 钻石×1 |
| **芯片** | 电路板×1 + 红石×2 + 差异化件（指南针/书/羊毛） |
| **喷罐** | 铁锭×1 + 染料×1 + 火药×1 |
| **装配台** | 铁锭×4 + 铜块×2 + 活塞×1 + 铁砧×1 |
| **充电座** | 铁锭×6 + 铜锭×2 + 红石×1 |
| **医用耗材** | 糖×1 + 纸×2 + 红石×1 |

#### 成本对比（改前 vs 改后）

| 产物 | 旧（直接合成） | 新（三步链路） |
|---|---|---|
| 一个民用模块 mk1 | 铁×2 + 铜 + 红石×2 + 功能件 ≈ **6 件** | ≈ 铁×13、铜×11、金×3、红石×12、石英×4、玻璃×4、紫水晶×2 等 **≈ 45 件原料** |
| 模块 mk3 | 直接跳档 | 需 3 次升级 + 下界合金碎片 |

> 手感目标：第一个模块约 **20~30 分钟**采集（含下矿一次），mk3 约 **2~3 小时**；不追求"磨"，但要让"攒一件好东西"有分量。

#### 防滥用（不变）

1. 升级必须消耗上一档，不可跳档、不可反向回收；
2. **军用件永不进合成表**，不存在"民用 + 稀有 = 军规"的洗白路径；
3. 喷罐只改外观，不参与任何配方；
4. 装配台只做 L3 及以上，**不能替代工作台**做 L2。

## 5. 数据结构

### 5.1 物品组件（`DataComponents`）

```
huhu_dronepal:frame     → ResourceLocation（机体型号 id，默认 huhu_dronepal:standard）
huhu_dronepal:name      → String（玩家起的昵称，空则用呼号）
huhu_dronepal:chip      → ResourceLocation（芯片 id）
huhu_dronepal:modules   → List<ItemStack>（3 格）
huhu_dronepal:battery   → ItemStack
huhu_dronepal:energy    → int（快照，非权威）
huhu_dronepal:drone_uuid→ UUID（与实体绑定）
huhu_dronepal:stash     → List<ItemStack>（机身库存 9 格）
```

### 5.2 datapack

```
data/huhu_dronepal/chips/<id>.json      { display_name, prompt_profile, voice_pack, voice_speed,
                                     bark_rate, bark_cooldown_ticks, max_chars_per_line,
                                     unlocks_chat, allows_smalltalk, tint }
data/huhu_dronepal/modules/<id>.json    { display_name, slot_type, drain_watts, tier,
                                     goals[], intents[], params{}, mod_dependency? }
data/huhu_dronepal/intents/<id>.json    { id, requires_module, aliases[], patterns[], response, line?, denied? }
data/huhu_dronepal/lines/<pack>/<group>.json { pack, group, lines[{id,text,voice,weight}] }
```

现成数据已在 `src/main/resources/data/huhu_dronepal/`，共 **127 个 JSON**：

| 目录 | 数量 | 内容 |
|---|---|---|
| `frames/` | 3 | 机体型号（scout / standard / heavy） |
| `chips/` | 3 | AI 芯片 |
| `modules/` | 21 | 7 族 × 3 档 |
| `paints/` | 8 | 涂装 |
| `intents/` | 17 | 意图（含闲聊/状态/指令） |
| `lines/` | 6 | 台词库（战术/话痨各 3 组） |
| `recipe/` | 37 | 合成表（含模块升档：mk1→mk2→mk3） |
| `advancement/` | 29 | 配方解锁进度 |
| `curios/` | 2 | 饰品槽位定义 |
| `loot_table/` | 1 | 掉落 |

---

### 6.0 按键轮盘（运行时开关）

**键位**：默认 `V`（可改）。**按住显示 → 移动鼠标选扇区 → 松开执行**；快速点按 = 取消。

**遥控语义（重要）**：
- 轮盘是**全局键位**，不要求瞄准无人机、不要求靠近 —— 只要你还装备着它；
- 目标 = **你当前装备的那一架**（通过物品组件里的 `drone_uuid` 定位，不靠射线拾取）；
- 与"潜行+右键开模块面板"的区别：**面板是物理装卸（要靠近），轮盘是遥控（随时）**；
- 无人机不在已加载区块 / 实体暂时缺失 → **其余动作一律失败并播报"失去信号"**（不排队、不缓存），
  但 **`召回` 仍然可用**（见下）。

**扇区内容（8 格 + 中心）**：只显示**已装模块**对应的项，未装的扇区留空并半透明。

| 扇区 | 内容 | 类型 |
|---|---|---|
| N | 跟随 / 原地待机 | toggle（系统项，不需要模块） |
| NE | 武器：展开 / 收纳 | toggle（`mod_combat`） |
| E | **交战策略** 被动/仅敌对/反击/协战/主动 | cycle（需 `mod_combat`） |
| E 长按 | 开火模式 单发/点射/连发（二级环） | cycle（仅 `combat mk3`） |
| SE | 扫描一次 | trigger（`mod_scan`） |
| S | 医疗 开/关（自动治疗） | toggle（`mod_medic`） |
| SW | 载物：投递 / 自动拾取 开/关 | trigger + toggle（`mod_carry`） |
| W | 照明 开/关 | toggle（`mod_light`） |
| NW | 链接播报 开/关 | toggle（`mod_link`） |
| 中心 | 取消 | — |

> 太阳能是 `readonly`：轮盘上只显示当前发电值，不可切换。

**数据驱动**：每个模块 JSON 带 `wheel` 字段（`kind: toggle|trigger|cycle|mix|readonly` + `label`），轮盘按已装模块自动生成扇区——加新模块不用改客户端 UI。

**实现要点（NeoForge 1.21.1）**
1. 键位：`RegisterKeyMappingsEvent` 注册；用 `isDown` 轮询而不是 `consumeClick`（要支持长按）。
2. 渲染：`RegisterGuiLayersEvent` 叠加层，用 `GuiGraphics` 画环（`fill` 拼扇区或用环形贴图 + 角度旋转）。
3. 选择：鼠标相对屏幕中心的 `atan2` → 扇区索引；死区（半径 < 40px）视为取消。
4. 下发：`CustomPacketPayload`（record + `StreamCodec`）发 C2S，**带无人机 entityId + 动作枚举**。
5. **服务端权威**：C2S 包带 `drone_uuid`（不是 entityId，实体可能正在重建）→ 服务端按 uuid 找实体，校验 ① 是你的 ② 该模块已装 ③ 冷却/电量允许；然后改运行态并回写快照。
6. 轮盘打开时禁止开火/用物品（避免误触），松开后立刻恢复。

#### 召回（失去信号时的唯一出路）

| 步骤 | 行为 |
|---|---|
| 1 | 服务端按 `drone_uuid` 查实体。**找到了**（区块已加载）→ 直接 `teleportTo` 玩家身边，**保留运行态**（电量/目标/库存） |
| 2 | **找不到**（区块卸载 / 实体缺失）→ 读物品组件快照，**在玩家身边原地重建**实体；旧实体日后若被加载，按 UUID 去重规则自动 discard |
| 3 | 两种情况都播报"已召回"，并清空交战目标、切入 IDLE |
| 4 | **冷却 10 秒**（战斗中也能用，但会被冷却限制，不能当无限传送门） |
| 5 | 冷却中按 → 提示剩余秒数，不执行 |

> 设计意图：召回是"保命/救场"手段，不是路由捷径。冷却 + 清空目标让它不能用于战斗中刷位移。

**与模块面板的分工**：面板（潜行+右键）= **装卸**模块；轮盘 = **运行时开关**。两者不重叠。

### 5.3 涂装（paint）

- 物品组件 `huhu_dronepal:paint` → `ResourceLocation`（默认 `huhu_dronepal:standard`）；
- 定义：`data/huhu_dronepal/paints/<id>.json` → `{ display_name, texture, glow_color, hud_tint }`；
- 贴图：`assets/huhu_dronepal/textures/entity/drone_<id>.png`（256×256 调色板条，与模型逐面 UV 对应）；
- **换涂装 = 换贴图**，不做运行时着色（英文字体/着色器成本高，且乘算 tint 变不出任意色）；
- 交互：手持 **喷罐 `huhu_dronepal:paint_can_<id>`** 对无人机右键 → 消耗 1 个喷罐、写入组件、客户端即时换贴图；
- 渲染：`getTextureLocation(entity)` 读组件返回对应贴图；发光条颜色读 `glow_color`（`hud_tint` 只用于 HUD）。

首发 8 套：`standard`（出厂灰青）/`arctic`（极地白）/`sand`（沙漠沙）/`jungle`（丛林绿）/`crimson`（血刃黑红）/`neon`（霓虹黑粉）/`void`（虚空紫）/`chrome`（铬银）。
涂装**不影响数值**，纯外观；后续可加"涂装 + 芯片 glow 色"组合。

## 6. 交互与对话

| 操作 | 结果 |
|---|---|
| **潜行 + 空手右键无人机** | 打开模块面板（唯一入口） |
| 普通右键 | 什么都不做（防战斗中误触） |
| 卸下物品 | 实体消失 = 召回 |

对话三层（无 T2）：
```
输入/事件 → T0 触发器（0ms）→ T1 意图匹配（<30ms）→ 兜底话术
```
- **T0**：12 个触发点（低血、敌对≥3、弹尽、摔落、首次进维度、入夜、下雨、低电量、模块离线、死亡、重生、稀有矿）。
- **T1**：归一化 → 精确别名 → 包含 → 正则 → 拼音首字母 → 兜底。
- 越界请求（"帮我放方块/挖掉它"）走 `order.place` 意图，固定回 **"我没有机械臂。"**
- 33 条意图清单见 [DIALOGUE.md](DIALOGUE.md)。

**语音**：预录 ogg + sound event（走 MC 音量与字幕），数字/方位用音素拼读（0~99 只需 20 条）。不做 TTS。

---

## 7. TaCZ 集成（**已核实**，可直接照做）

参考源码：`~/Documents/deepseek-harness/default-workspace/tacz-bow/TACZ`（sparse clone，1.20.1 分支；1.21.1 结构相同，需先 `git fetch origin 1.21.1` 核对）。

| 事实 | 出处 | 对我们的意义 |
|---|---|---|
| `IGunOperator.fromLivingEntity(e)` 就是 `(IGunOperator) e`，靠 Mixin 注入到 **LivingEntity 基类** | `api/entity/IGunOperator.java` 注释「LivingEntity 通过 Mixin 的方式实现了这个接口」 | **任何 LivingEntity 都能当射手**，`TamableAnimal` 天然满足，不用自己实现接口 |
| `shoot()` 开头读 `data.currentGunItem`，为空返回 `NOT_DRAW`；非 `IGun` 返回 `NOT_GUN` | `entity/shooter/LivingEntityShoot.java` ≈54-60 | **必须先 `draw()` 抬枪**，否则永远打不响 |
| 缺弹走 `consumeAmmoFromPlayer()`，内部 `shooter.getCapability(ITEM_HANDLER)` + `findAndExtractInventoryAmmo` | 同文件 ≈269-284 | 无人机必须暴露 **`Capabilities.ItemHandler.ENTITY`**（枪槽 + 弹匣槽） |
| 唯一的 `instanceof ServerPlayer` 只用于时间戳纠错包 | 同文件 ≈83 | 非玩家只是跳过，**不影响开火** |
| 弹药从 `ItemHandler` 扣，不读 Player 背包类 | 同上 | 机内库存即"背包" |

**调用顺序（必须照此）**：
```java
IGunOperator op = IGunOperator.fromLivingEntity(drone);
op.draw(gunStack);                 // 抬枪，绑定 currentGunItem
op.aim(true);                      // 可选
ShootResult r = op.shoot(           // timestamp 用当前时间
    () -> pitch, () -> yaw, System.currentTimeMillis());
```

**渲染**：不要自己转枪。顺序是
`PoseStack.translate(gun_mount 世界位置) → rotate(云台 yaw/pitch) → TaCZ renderer`。
在挂点外面再套"摆正"旋转会与枪自带 hand transform 叠加，导致枪歪（此类挂载最常见的 bug）。

**平衡（必做，否则是自瞄挂）**：射击间隔随机 ±40%、每发瞄准角随机抖动、禁止移动射击、连发上限、交战冷却。

---

## 8. 任务分解

### P0 · 环境与骨架（0.5 天）
- ModDevGradle 工程、Java 21 toolchain、`neoforge.mods.toml`（`modLoader="javafml"`, `loaderVersion="[4,)"`）
- 空主类 + 四个 `DeferredRegister`（items / entities / menus / creative tab）
- `run/mods` 放入 TaCZ 1.2.0、Curios 9、GeckoLib 4.7
- **验收**：`./gradlew build` 出 jar；游戏 Mods 页可见；进世界不崩

### P1 · 实体与飞行（2~3 天）
- `DroneEntity extends TamableAnimal`（拿现成的 `OwnableEntity` 持久化）
- `MoveControl` 换飞行版（参考原版 `Vex`），`noPhysics` + 无重力
- **复用原版 `FollowOwnerGoal`**，只调 `followDistance`（>16 格自动瞬移）——不要自己重写
- 避障：每 5 tick 向前 raycast 3 格
- `DroneItem`：装备即 spawn、卸下即 discard（P1.5 结论复用）
- **验收**：走 1×2 隧道不卡墙、跑图不掉队、重登/换维度/死亡后仍在

### P1.5 · 真枪挂载 Spike（2 天，**前置，不通过就改架构**）

| # | 任务 | 验收 |
|---|---|---|
| S1 | 主手槽持有 `GunItem` + 暴露 `ItemHandler`（枪 + 弹匣） | TaCZ 能识别 |
| S2 | `fromLivingEntity → draw → shoot` | **真打出一发并命中** |
| S3 | 弹匣真实消耗 | 30 发打完停火 |
| S4 | 枪渲染（复用 TaCZ renderer，套 `gun_mount`，不缩放不额外旋转） | 第三人称可见、枪口朝前、不歪 |
| S5 | 精度节流 | 不是自瞄挂 |
| S6 | 手枪/步枪/狙击/RPG 各挂一次 | 轻微穿模可接受，接缝被挂架盖住 |
| S7 | 云台 0° ↔ +85° 来回 20 次 | 不穿机体、不插地、不卡中间态 |

**若 S2 不通过**（TaCZ 内部还有未发现的玩家假设）→ 回退到"无人机自己的投射物"，不要硬刚。

### P2 · 槽位与模块（3~4 天）
- 状态进 `DataComponents`；槽位 GUI（`AbstractContainerMenu` + `Screen`，入口=潜行+右键）
- 数据驱动装载（`SimpleJsonResourceReloadListener`）+ `/reload` 生效
- 电力系统 + **充电座**（FE + 烧煤兜底）
- 模块→Goal 动态注册；`mod_combat`（接 P1.5）、`mod_light`、`mod_carry`
- **验收**：拆掉模块能力立刻消失；充电座能充；不装 Curios 也能玩

### P3 · 芯片与对话（2~3 天）
- `EventRouter`（T0，12 触发）+ `IntentMatcher`（T1，5 步）+ 槽位填充
- 台词库权重抽取、同 id 不连续重复
- 语音：sound event 注册 + 队列（同机同时只播一条）+ HUD 字幕
- **验收**：断网全功能正常；打字到回复 <50ms；两种人格语气可辨

### P4 · Curios 与 TaCZ 联动（1~2 天）
- Curios `drone` 槽 + `CurioRenderer`；无 Curios 降级为背包
- `mod_link`：弹药余量播报、命中/击杀播报、递弹匣
- **验收**：装/卸饰品栏即出现/消失；不装 TaCZ 不崩

### P5 · 打磨
编队（2~3 架）、升级件、记忆档案（本地 json）、HUD 美化、GeckoLib 全套动画（hovers/boost/scan/damaged）。

---

## 9. 已知坑（照抄能省几天）

1. **不要在 tick 里写 `DataComponents`** —— 会触发整条物品同步链，玩家一多就卡。
2. **不要自己写跟随 Goal** —— 原版 `FollowOwnerGoal` 已含">12 格瞬移回主人"。
3. **不要在外面给枪加旋转** —— 与 hand transform 叠加会歪。
4. **死亡不掉落**必须覆盖 `dropAllDeathLoot()`，否则有大复制漏洞。
5. **碰撞箱只算机体**，旋翼参与碰撞会卡门框。
6. **`ResourceLocation` 构造器在 1.21 私有化** → `fromNamespaceAndPath` / `parse`。
7. **网络包**在 NeoForge 用 `PayloadRegistrar` + `CustomPacketPayload` + `StreamCodec`，不是 `SimpleChannel`。
8. **GPU/渲染**：HUD 用 `RegisterGuiLayersEvent`（NeoForge 1.21）而不是旧版 `RenderGameOverlayEvent`。
9. **实体 capability** 需要每 tick 手动 tick 子能力，漏了就是"电量永远 0"的玄学 bug。
10. **构建**：Gradle 首次拉 NeoForge 依赖约 800MB，国内网络可能超时，可换镜像或手动放缓存。

---

## 10. 现有文件索引

| 文件 | 内容 |
|---|---|
| `docs/DESIGN.md` | 完整设计（约束、交互、生命周期、模块、电力、挂载） |
| `docs/DIALOGUE.md` | 意图表 33 条、触发点、台词库格式、人格差异、语音方案 |
| `docs/PLAN.md` | 阶段排期与验收（本文档是它的执行版） |
| `src/main/resources/data/huhu_dronepal/**` | 15 个已校验的 datapack JSON |
| `gen_drone.py` | 无人机体素模型生成脚本（`SCALE = 0.66`） |
| `../blockbench-ysm-agent/models/dronepal/pack/` | 模型工程（55 块 / 10 骨骼），可直接在 Blockbench 打开 |
| `../tacz-bow/TACZ/` | TaCZ 源码参考（sparse clone） |

---

## 11. 明确不做（已否决，勿重提）

| 方案 | 否决理由 |
|---|---|
| 接 LLM 聊天 | 玩家要的"搭档感"来自及时播报与检索，不需要生成；离线、零延迟、免 key、免审核 |
| 运行时 TTS | sound event 只能播资源包固定 ogg；TTS 要 OpenAL 直放，绕开音量/字幕，且 2 秒延迟与短促播报冲突 |
| 物品存全部状态（纯投影） | 只适合纯装饰；战斗需要真实体 |
| 自己做 AABB 测量 / per-gun 兼容表 | 为消除轻微穿模引入的复杂度不值 |
| 给枪做缩放 | 枪包已适配手部挂点，不缩放反而更准；极端长枪只加一档挂点偏移 |
| 硬失败（打坏要修） | 挫败感太重，用户明确要软失败 |
| 让无人机改世界（放方块/给物品） | 违反 C1 |
| 自己做饰品栏（不依赖 Curios） | 工作量大 30%，Curios 已是生态标准 |
