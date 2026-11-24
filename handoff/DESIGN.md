# Dronepal · 设计定稿（无 LLM 版）

> 一架装在**饰品栏**里的悬浮无人机：会跟随、能战斗、能打杂、能说话，**没有任何修改世界的能力**。
> 对话不接大模型：规则触发 + 本地检索 + 两套台词库。零网络、零延迟、离线可玩。

- 目标版本：Minecraft **1.21.1** / **NeoForge 21.1.x**
- 硬依赖：`neoforge`、`minecraft`
- 软依赖：`curios`（饰品栏，9.x）、`tacz`（枪械联动，**1.2.0 neoforge / 1.21.1**）、`geckolib`（4.7+；缺省降级为方块模型）

> 为什么是 NeoForge：1.20.2 起 Forge 与 NeoForge 分叉，1.21.1 生态（TaCZ 1.2.0、Curios 9、GeckoLib 4.7）都在 NeoForge 侧。

---

## 1. 核心原则

| # | 原则 | 落到代码 |
|---|---|---|
| 1 | **不可改变世界** | 不注册 `PlaceBlock`/`DestroyBlock`/`GiveItem` 任何接口；无人机只有「移动 + 说话 + 标记（纯客户端渲染）+ 从自己仓库取物」四类能力 |
| 2 | **能力来自硬件** | 没装模块 = 没有对应的 Goal，也没有对应的意图处理分支。不存在“有功能但被权限挡住” |
| 3 | **数据驱动** | 芯片、模块、台词、意图全部是 datapack JSON；加内容不改 Java |
| 4 | **人格 = 芯片** | `chip_tactical`（冷静，短句报数据）/ `chip_chatty`（话痨）/ `chip_silent`（只干活） |
| 5 | **不联网** | 无 HTTP 客户端、无 key、无遥测 |

---

## 1.5 交互与生命周期（v2 · 2026-11-23 修订）

### 交互表

| 操作 | 结果 |
|---|---|
| **潜行 + 空手右键无人机** | 打开模块面板（唯一入口） |
| 普通右键无人机 | **什么都不做**（防战斗中误触）；只在被点击时转头看你 |
| **从饰品栏 / 背包卸下无人机物品** | 实体立即消失 = 召回。**没有独立的“召回”动作** |
| 重新装备（放回饰品栏） | 实体在玩家身边重新出现，状态原样 |
| **手持喷罐右键无人机** | 更换涂装（消耗 1 罐），纯外观、不影响数值 |
| **按住 `V`（可改）** | **遥控轮盘**（不需瞄准/靠近，只要装备着）：跟随/待机、武器展开/收纳、开火模式、扫描、医疗、载物、照明、播报开关、**召回**；松开执行、快速点按取消。无人机不在已加载区块时其余动作播报"失去信号"，**只有"召回"可用**（会把它 teleport 或按快照原地重建到你身边，冷却 10s） |

### 架构推论：实体 = 物品的投影

无人机的一切真实状态（芯片、模块、电池、电量）**只存在物品的 `DataComponents` 里**，实体只是它的运行时投影。

```
DroneItem (DataComponents: chip, modules[3], battery, energy)
   │  装备时（curioTick / InventoryTick 检查）
   ▼
DroneEntity（只缓存一份引用 + 客户端表现，不持有权威状态）
```

**带来的好处**（这是把交互简化后白拿的）：
- 不需要“实体 ↔ 物品”双向同步，没有“NBT 对不上”的 bug；
- 不会出现“实体丢了，东西也没了”；实体丢了 → 下一 tick 自动重建；
- 死亡/换维度/区块卸载全部自动正确：物品跟着玩家走，实体跟着物品走；
- 多人下天然安全：没有主人的物品就不会有实体。

**必须处理的边界**：

| 情况 | 规则 |
|---|---|
| 玩家死亡 | 物品按 Curios 规则掉落或保留 → 实体随物品走；重生时若物品在身上则重建 |
| 换维度 | 物品清空实体引用，落地后重建 |
| 区块卸载 / 距离 >64 格 | 实体 discard，进入“冷却重建”（避免反复 spawn 刷屏） |
| 无人机被杀死 | 实体进入 30 秒修复状态；修复完成在玩家身边重建（物品不丢） |
| 物品被放入箱子 | 实体消失（和卸下同一条规则） |
| 同物品被复制（外挂/崩服） | 实体绑定物品 UUID，同 UUID 只允许一个实体存活 |

## 1.7 武器挂载规范（v3 · 定稿）

### 骨骼

```
DroneRig
└─ gimbal（云台：yaw 追目标 + pitch 俯仰，程序化驱动，不做关键帧）
   └─ gun_mount（空骨骼，只有变换）
      └─ [枪的模型由 TaCZ 渲染，不进入我们的模型树]
```

### 姿态参数

| 项 | 值 | 说明 |
|---|---|---|
| 交战态 pitch | 0°（枪口水平朝前） | |
| **收纳态 pitch** | **+85°（枪口朝下）** | 朝下是因为下方是空气；朝上会扫进机腹 |
| yaw 范围 | ±75° | 超出则机身整体转向 |
| 过渡时长 | 收纳 0.45s / 展开 0.35s | 展开 ≤ TaCZ `draw_time`，避免二段延迟 |
| 收纳时禁止开火 | 是 | `STOWED / STOWING / DEPLOYING` 一律拒绝 |
| 收纳时悬停高度 | +0.4 格 | 枪口朝下会贴地 |

### 挂载：复用枪包自带的手部适配

枪包的几何里已经有 `positioning` 定位组、`muzzle_pos` / `shell` / `scope_pos` 定位骨，`display` json 里也有各视角的 `transform.scale` ——**握持姿态是作者调好的**，我们直接用。

```
PoseStack: translate(gun_mount)  →  rotate(云台 yaw/pitch)  →  TaCZ renderer
                                                  ↑ 只有云台转，枪自己管握持朝向
```

- **不做缩放**：枪在无人机上就是它原本的尺寸；
- **不做额外旋转**：外面再套"摆正"的旋转会和枪自带 hand transform 叠加，导致枪歪（此类挂载最常见的 bug）；
- **挂点偏移**：一个固定值（约 0.22 格，向下）；RPG/狙击等超长枪追加一档（约 0.32 格）；
- **允许轻微穿模**：不做 AABB 测量、不做 per-gun 数据包、不做动态避让；接缝用一块 0.06 格挂架板盖住。

> 不同枪的 `display.scale` 本身就不同（步枪常见 0.6、手枪接近 1.0），所以挂上去尺寸不统一——这和它们在玩家手里也一样，不是 bug。

---

## 2. 槽位与电力

```
DroneEntity
├─ AI 芯片槽 ×1        决定：能否说话、人格、音色包、主动播报频率
├─ 功能模块槽 ×3       决定：有哪些 Goal + 哪些意图可用
└─ 电池槽   ×1        决定：续航（容量 × 模块总功耗）
```

**电力预算**：`功耗 = Σ模块.drain`（单位 W），`续航秒 = 电池.capacityWh / 功耗 × 3600`。
电量 <20% 时：先关掉最贵的模块并在 HUD 上标红；电量耗尽 → 模块全部离线，只剩“跟随 + 悬浮”（此时不耗电）。

| 电池 | capacityWh | 说明 |
|---|---|---|
| `battery_mk1` | 120 | 一个 `mod_combat` 约撑 8 分钟 |
| `battery_mk2` | 300 | |
| `battery_mk3` | 720 | 需要 v0.4 的充电座充能 |

> 设计意图：让玩家在“多装一个模块”和“飞得久不久”之间做取舍，而不是全部装满。

---

## 3. 模块清单（7 族 × 3 档）

每族 3 档，等级越高功耗越大、效果越强；**同一族只能装一个**。

| 族 | mk1 | mk2 | mk3 |
|---|---|---|---|
| combat | 14 FE/t · 射程16 · 3连发 · 散布6° | 18 · 20 · 4连发 · ×1.15 · 4° | 22 · 24 · 5连发 · ×1.30 · 2.5° · 三种射击模式 |
| scan | 6 · r16 · 仅敌对 | 9 · r24 · +常见矿 | 12 · r32 · +全部矿 |
| carry | 5 · 9格 | 8 · 18格 · 自动分类 | 12 · 27格 · 自动投递 |
| medic | 4/12 · 1HP/2s · 单次6 · cd40s | 6/16 · 1.5HP/2s · 单次8 · cd30s | 8/20 · 2HP/2s · 单次12 · cd20s · 抗性I 3s |
| light | 2 · 12 · r8 | 3 · 15 · r12 | 4 · 15 · r16 · 水下有效 |
| link | 3 · 弹药播报 | 5 · +命中播报 +递弹匣 | 7 · +自动补弹 +护甲提示 |
| solar | 0 · 晴8 雨2 | 0 · 晴12 雨4 | 0 · 晴16 雨6 夜2 |

**全部模块都不写世界**：`mod_carry` 只能“捡起来”，投递是掉在玩家脚下（`dropItem` 到世界 = 修改世界？→ 实现为直接塞进玩家背包，塞不下才掉，且掉的是它自己捡的）。

---

## 4. 对话：三层路由，T2 永远不存在

```
输入（玩家打字/快捷键）或 事件触发
  ├─ T0 触发器：世界事件 → 台词（毫秒）
  ├─ T1 检索：意图匹配 → 本地索引查询（<30ms）
  └─ 兜底：没匹配到 → 芯片人格的“不知道”话术
```

**T0 触发点**（首批 12 个）：

| 触发 | 条件 | 战术芯片示例 |
|---|---|---|
| `low_health` | HP<30% 且 10s 内未重复 | `"心率异常。建议后撤，或者把那个东西打死。"` |
| `hostile_near` | 16 格内≥3 敌对 | `"三点钟方向三个，12 格。"` |
| `ammo_empty` | 手持 TaCZ 枪弹匣空 | `"空了。你腰上有弹匣。"` |
| `fall_damage` | 摔落>3 | `"下次别跳。"` |
| `first_dimension` | 首次进维度 | `"下界。呼吸注意点。"` |
| `night_start` / `rain_start` | | |
| `low_battery` / `module_offline` | 电力 | |
| `player_death` / `respawn` | | |
| `rare_ore` | `mod_scan` 装且附近有钻石/远古残骸 | |

**T1 意图表**：见 `DIALOGUE.md`（40 条，含槽位模板）。

---

## 5. 语音：预录 + 参数拼接（方案 A）

- **不做 TTS**。每个芯片自带一个语音包，短 ogg（0.6~2 秒），总计约 150（战术）/ 260（话痨）条。
- 数字/方位/物品名用「拼读」：`"弹药剩余" + 读数字(12) + "发"`；`"方位" + 读方位(2点)`。
- 播放：注册 sound event（资源包内固定 ogg）→ **天然走 MC 音量设置、有字幕、多架无人机自动避让叠音**（同一 entity 上一条播报未播完则排队）。
- 语速/音色差异靠芯片的 `voice_pack` 指向不同目录。

> 为什么不 TTS：sound event 只能播资源包里固定的 ogg；真 TTS 要走 OpenAL 直放，绕过音量与字幕，且 2 秒延迟和“战术 AI 的短促播报”是冲突的。

---

## 6. 目录结构

```
dronepal/
├─ build.gradle / gradle.properties / settings.gradle
├─ src/main/java/com/lyk/dronepal/
│   ├─ Dronepal.java                    主类、注册
│   ├─ entity/
│   │   ├─ DroneEntity.java             实体 + 槽位数据 + 电力 tick
│   │   ├─ DroneMoveControl.java        悬浮/避障/瞬移兜底
│   │   └─ goal/                        按模块动态注册的 Goal
│   ├─ item/                            drone_summoner、芯片、模块、电池
│   ├─ dialog/
│   │   ├─ EventRouter.java             T0 触发器
│   │   ├─ IntentMatcher.java           T1 意图匹配
│   │   ├─ LineBank.java                台词库装载（datapack）
│   │   └─ VoicePlayer.java             语音排队
│   ├─ menu/DroneBayMenu.java           槽位 GUI
│   ├─ network/                         槽位同步包
│   └─ compat/{curios,tacz}/            软依赖门面（缺失时 Noop）
├─ src/main/resources/
│   ├─ data/dronepal/chips/*.json
│   ├─ data/dronepal/modules/*.json
│   ├─ data/dronepal/intents/*.json
│   ├─ data/dronepal/lines/{tactical,chatty}/*.json
│   ├─ assets/dronepal/...              模型/贴图/音效/语言
│   └─ META-INF/mods.toml
└─ docs/{DESIGN.md, DIALOGUE.md}
```

---

## 7. 里程碑

| 版本 | 交付 | 验收标准 |
|---|---|---|
| v0.1 | 实体 + 跟随/避障/瞬移 + 召唤器 + 槽位 GUI 骨架 | 能召唤、能跟、走隧道不卡墙 |
| v0.2 | 模块系统 + 3 个模块（combat/light/carry）+ 电力 | 拆掉模块能力立刻消失 |
| v0.3 | 芯片 + T0 触发 + T1 意图 + 语音包 A | 断网可玩；播报延迟 <50ms |
| v0.4 | Curios 饰品栏 + `mod_link`（TaCZ）+ 充电座 | 装 TaCZ 后能报弹药、递弹匣 |
| v0.5 | 编队、升级件、记忆档案（本地 json） | |

---

## 8. 明确不做

- 不接 LLM / 不联网 / 不收集数据
- 不改世界（不放方块、不破坏、不刷物）
- 不做“语音自由对话”（只有预录短句）
- 不硬依赖 TaCZ / Curios（缺了也能跑）


---

## 9. 1.21.1 / NeoForge 与 1.20.1 的关键差异（写代码前必读）

| 主题 | 1.20.1 (Forge) | 1.21.1 (NeoForge) | 对本 mod 的影响 |
|---|---|---|---|
| 物品 NBT | `stack.getOrCreateTag()` | **`DataComponents`**（自定义 `DataComponentType`） | 芯片/模块等级、电量改存组件，类型安全，反而更简单 |
| 玩家侧数据 | `Capability` | **`AttachmentType`**（NeoForge 数据附件） | 饰品栏/芯片引用可直接挂 attachment，省掉 Capability 样板 |
| 网络包 | `SimpleChannel` + `IMessage` | `PayloadRegistrar` + `CustomPacketPayload` + `StreamCodec` | 槽位同步包要重写成 record + codec |
| `ResourceLocation` | `new ResourceLocation(ns, path)` | **`ResourceLocation.fromNamespaceAndPath / parse`** | 全部 id 构造改 API |
| 注册 | `DeferredRegister` | 同名（`DeferredItem` / `DeferredHolder`） | 基本不变 |
| 数据包重载 | `SimpleJsonResourceReloadListener` | 同名，但需 `RegistryOps` 包装 | 芯片/模块/台词的 JSON 目录结构不变 |
| 菜单 | `MenuType` + `IMenuTypeExtension` | 同名 | 槽位 GUI 逻辑基本不变 |
| 渲染层 | `RenderType` 在 `client` 包 | 位置变动（`RenderType` 移到 `rendertype`） | 只影响 HUD/光效代码，GeckoLib 吃掉大部分 |
| 方块/实体光照 | `getLightLevel` | `Level` 光照查询 API 调整 | `mod_light` 动态光源实现要按 1.21 写法 |

**结论**：设计层（第 1~8 章）一行不用改；只有第 4 节“模块参数”和第 6 节目录里 Java 侧的 API 调用方式要按上表调整。
