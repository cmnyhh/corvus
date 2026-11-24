# PORT-REQUESTS-C.md — 注册项与合并请求（角色 C：战斗 / 扫描 / 对话）

所有类都在 `dev.corvus`（`dev.corvus.client` / `dev.corvus.devtools` 为子包）。
**C 没有改 `Corvus.java` / `build.gradle` / `data/**` / 语言文件**。下面这些需要 A 合并。

## 1. 唯一必做：在 `Corvus` 构造器里加一行

```java
dev.corvus.CorvusNetwork.register();
```

它一次完成：

* `SimpleChannel` 注册（`huhu_dronepal:main`，协议版本 `"1"`）——7 个包：
  `ScanSnapshot`(S→C)、`CommsMessage`(S→C)、`DroneVoice`(S→C)、`ModuleSync.Table`(S→C)、
  `DroneRemote.Query`(C→S)、`DroneRemote.Command`(C→S)、`DroneRemote.Status`(S→C)。
  客户端类只经 `DistExecutor` 触碰，专用服务器不会加载 GUI 类。
* `Hardware.setIntentHandler(DroneSystems::executeIntent)` —— 硬件层 `execute(intent)` 的回调
  （`scan` / `scan.hostile` / `scan.ore` 走这条；`order.heal` 之类不在本层实现，返回 false）。
* `Hardware.setGunHandler(...)` —— 枪/弹药槽判定（1.20.1 无 TaCZ 时恒为「不接受枪」）。
* `DroneCombat.register()` —— `Engagement` 的 `engage_modes` reload listener + `EngagementPreferences`（`/dronepvp`）。

必须在 `Hardware.register(bus)` **之后**调用（只用到静态注入点，顺序其实不敏感，但先注册更直观）。
`DroneCombat/DroneSystems/DialogueServer/DroneRemote` 四个类也各有一个 `register(IEventBus)` 兼容壳，
内部同样转到幂等的 `CorvusNetwork.register()`，所以 A 里那四行合并点取消注释也不会重复注册。

## 2. 配置项（A 已确认由 A 提供）

| 文件 | 需要的成员 | 用途 |
| --- | --- | --- |
| `HudOptions` | `enum Markers { AUTO, LINES, PARTICLES }`、`EnumValue<Markers> SCAN_MARKERS`、`Markers scanMarkers()`（无异常读，回 AUTO） | `ScanMarkerMode.resolve` 决定矿框用线还是粒子 |
| `ServerOptions` | `IntValue AUTO_SCAN_SECONDS`（默认 15，5..300）、`int autoScanSeconds()`（无异常读） | 自动扫描周期 |

`Corvus` 里按 1.21 那样注册两个 `ModConfigSpec` 即可（CLIENT=HudOptions，SERVER=ServerOptions）。

## 3. 依赖 A 的物品注册（否则功能降级，但不会崩）

* `huhu_dronepal:chip_silent / chip_chatty / chip_tactical` —— 对话/播报的总开关就是芯片本身；
  没有芯片物品时 `Hardware.hasChip` 恒 false，全套字幕静默（这是设计上的降级，不是 bug）。
* `huhu_dronepal:module` + `data/huhu_dronepal/modules/*.json`（scan/combat 系列）—— 扫描半径、
  mark_ticks、交战距离都从这里读。
* 电池（`omd_bat_*` 等）—— 模块 online 判定用容量/电量。
* `data/huhu_dronepal/intents/**`、`lines/**`、`chips/**`、`engage_modes/**` 已随资源一起拷好，
  A 不需要动。

## 4. 事件订阅：无需 A 动手

以下类自带 `@Mod.EventBusSubscriber`，自动挂上：

* `DialogueServer`（`AddReloadListenerEvent` / `RegisterCommandsEvent` / `PlayerTickEvent` /
  摔落 / 死亡 / 重生 / 下线）
* `DroneArchive`（`EntityLeaveLevelEvent` / `/dronepal memory`）
* `ScanDisplay`、`CommsSubtitles`、`ScanHud`、`DroneVoicePlayer`（客户端）
* `SmokeCombat`（仅 `-Dcorvus.smoke=1`，dev-only）

## 4b. lead 追加的两项（C 已做完）

1. **按机型决定碰撞箱**：`DroneEntity.getDimensions(Pose)` 用 `Frames.byId(getFrameId()).width()/height()`，
   `setFrame` 与 `onSyncedDataUpdated(DATA_FRAME)` 都会 `refreshDimensions()`。尺寸存在普通字段里（不是同步数据），
   因为 `getDimensions` 会在实体构造器里被调用，那时同步数据还不存在；字段为 0 时回退 `super.getDimensions`，
   不会出现 0 尺寸盒子。smoke 实测：`hitbox=0.55x0.55 frame=owl definition=0.55x0.55`（owl 的 `collision[0/1]`）。
2. **模块 tooltip 的 datapack→客户端同步**：`dev.corvus.ModuleSync`。服务端在 `OnDatapackSyncEvent` +
   `PlayerLoggedInEvent` 把 `Hardware.definitions()` 序列化成一张 JSON 表，走 `CorvusNetwork`（包 id 6）发给客户端；
   客户端解码后调用 `Hardware.acceptSynced(Map<ResourceLocation, Module>)`（A 已提供，直接调用，无反射），
   并缓存一份 `ModuleSync.synced()` 供兜底：`ItemTooltipEvent` 里若 `Hardware.definition(stack)==null`
   就用 `ModuleTooltips.describe(module)` 补齐，不会显示「未知模块」。
   实测日志：`[corvus-modules] synced 27 module definitions to Dev` → `received 27 module definitions, hardware table now synced=true`。

## 5. `DroneEntity` 的增量改动（已由 C 完成，勿重复改）

`dev.corvus.DroneEntity` 增加了**纯增量**内容，已在 port-a 处对齐：

* 同步字段：`DATA_MOUNT_PITCH` / `DATA_AIM_YAW` / `DATA_AIM_PITCH` / `DATA_SCANNING` / `DATA_FLIGHT`
* 访问器：`mountPitch/setMountPitch`、`aimYaw/setAimYaw`、`aimPitch/setAimPitch`、
  `isScanActive/scanning`、`getFlightBlend`
* `controller()` / `controller(ServerPlayer)`、`systems()`、`scanPatrolChanged`、`execute`
* 每 tick：`systems.tick`、`combat.tick`；每 20 tick：`DroneArchive.checkpoint` + `systems.second`
* `Hardware.tickDeployed(this)`（A 要求的那一行）
* `holdingStation`、`archiveTick`、`scanSnapshot`/`scanSnapshot()`、`public final DroneCombat combat`
* 按机型宽度/高度的碰撞箱（`getDimensions` + `refreshDimensions`）
* `remove(RemovalReason)` 覆写：清掉进行中的扫描并推送「清空」快照

## 6. 已知未完成 / 明确标注不做的部分

1. **TaCZ（1.20.1）整体未接**：`TaczLink` 全部返回「没有枪」；`DroneCombat.fire` 会记录
   `NO_FIRE_BACKEND` 而不开火。降级路径（选目标、云台瞄准、收纳/展开、友方保护、五档策略）可用。
   一旦 `build.gradle` 有了 TaCZ 坐标，需要补的实现点就 7 个，都列在 `TaczLink` 类注释里。
2. **`GunEnvelope` 自适应收纳角未移植**（1.21 会按枪长压低最大收纳角）：没有枪就无从测量。
3. **医疗（`order.heal`）与货运（`order.fetch/supply` 的实际搬运）不在本层**：前者属模块电力运行时，
   后者属 `CarryLight`（B）。对话命中这些 intent 时返回「当前无法执行」。
4. **`DroneTelemetry` / `client.DroneHud`（左上角电量 HUD）不在本层**：A/B 的范围。
   扫描 HUD 读数由 `client.ScanHud` 自带（矿/怪数量 + 剩余秒数）。
5. **FTB Teams 用反射**（1.20.1 的 FTB Teams 不在 Maven、build.gradle 无依赖）：找不到 API 时
   只保护主人，宁可不开火。
