# PORT-REQUESTS-M.md — 注册项与合并请求（角色 M：light 族 / 电力事务 / carry 自动拾取）

新类全部在 `dev.corvus`（客户端在 `dev.corvus.client`），**没有改 `Corvus.java` / `build.gradle` /
`mods.toml` / `TaczLink` / `DroneCombat` / `CombatSlots`**。

## 1. 注册项：零（不需要 A 动手）

* `ModuleRuntime` 自带 `@Mod.EventBusSubscriber(modid = Corvus.ID, bus = Bus.MOD)`，
  在 `FMLCommonSetupEvent` 里做两件事，都是幂等的：
  1. `LightSync.register()` —— 注册**自己的** `SimpleChannel`（`huhu_dronepal:lamp`，协议 `"1"`，
     一个包 `LightSync.State`，S→C）。没有复用 `CorvusNetwork` 的 `main` 通道，避免和 C 的包 id 抢号。
  2. `Hardware.setIntentHandler(ModuleRuntime::dispatch)` —— **链式**：`order.light` 由本层处理，
     其余 intent 原样转给 `DroneSystems.executeIntent`（即 `CorvusNetwork` 装的那个处理器的等价实现）。
     `FMLCommonSetupEvent` 在 mod 构造器之后触发，因此一定覆盖 `CorvusNetwork.register()` 装的那份。
* 客户端：`client.DroneLamps` 自带 `@Mod.EventBusSubscriber(value = Dist.CLIENT)`，`RenderLevelStageEvent`
  + `ClientTickEvent` 自动挂上。
* 语言键见 `PORT-LANG-M.json`（3 个：`dronepal.light.on/off/no_power`）。

> ⚠️ 如果之后有人再调 `Hardware.setIntentHandler(...)`（例如战斗层为了 `order.cover`），
> 请按同样的链式写法包一层，否则 `order.light` 会静默失效。

## 2. 对既有文件的增量改动（仅 3 处，都是加行）

| 文件 | 改动 | 说明 |
| --- | --- | --- |
| `DroneEntity.java` | `tickCount % 20 == 0` 块里加一行 `ModuleRuntime.second(this, pilot, terminal);`（在 `systems.second` 之前） | 每秒一次的电力结算 / 灯状态发布 / 自动拾取。C 那行 `systems.second` 保留不动，两行共存 |
| `LocalDialogue.java` | `respond` 的 `case "scan", ..., "order.supply"` 列表加 `"order.light"` | 否则对话命中 `order.light` 只会回「当前固件尚未实现这项指令」，不会进 `Device.execute` |
| `data/huhu_dronepal/intents/order.light.json` | **新增文件** | 别名：开灯 / 关灯 / 灯光 / 照明 / 把灯打开 / 灯 / light；`response: "action"`，`requires_module: "mod_light"` |

三个改动都只加不改语义；合并时若被覆盖，`-DCORVUS_MODTEST=1` 的 smoke 会立刻红（见第 5 节）。

## 3. 客户端打光：为什么不是 light engine

1.21 参考实现 `client/DroneLamps` + `client/mixin/DroneLampMixin` 是把 `LevelRenderer.getLightColor(...)`
的返回值按距离抬高（改方块亮度）。这条路在 1.20.1 需要 mixin 配置，而 mixin 配置写在
`META-INF/mods.toml`（`[[mixins]]`）里，`mods.toml` 和 `build.gradle` 本轮被冻结给 TaCZ 用，所以本层
**没有**走 light engine，而是用 Forge 1.20.1 提供的 `RenderLevelStageEvent`（`AFTER_PARTICLES`）画：

* 面向相机的加法混合光晕（半径随模块 `radius` 变化）
* 更亮的灯芯圆盘
* 下方第一个实体方块表面上的地面光池

不放置任何方块、不 dirty 任何光照 section、不写世界。`LightSync` 只把
`radius << 4 | light_level | 512·flicker` 打包成整数（和 1.21 的 synched 打包一模一样）+ `tint`
发给**正在追踪该实体**的客户端，且只在状态变化时发。空电 / 离线 / 关灯 → profile 0 → 客户端缓存里
直接删掉这一条（`LightSync.prune` 还会在实体消失后清理）。

如果之后 `mods.toml` 可以加 `[[mixins]]`，把参考实现的 `DroneLampMixin` 搬过来即可接到真正的
方块光照上，`DroneLamps` 里的绘制部分可以保留作为「可见光源」提示。

## 4. 1.20.1 没有 `ItemEntityPickupEvent`

1.21 的 `CarryLight.second` 会 post `ItemEntityPickupEvent.Pre(owner, drop)` 让保护类 mod 否决拾取。
1.20.1 Forge 没有这个事件（`EntityItemPickupEvent` 是**玩家**走到物品上触发的，不适用于无人机）。
所以 `ModuleRuntime.pickupPhase` 把等价条件内联了：距离（`pickup_radius`）、容量（`slots`）、
`hasPickUpDelay`、`ItemEntity.getTarget()`（别人预定的掉落物不捡）、不是终端、没有
`dronepal_delivered` 标记、货舱界面开着时整轮跳过（`CarryLight.viewing`）。

## 5. 验收怎么跑（新增 smoke）

```bash
CORVUS_MODTEST=1 GRADLE_USER_HOME=... gradle runServer --no-daemon -PcorvusLevel=ModuleSmoke
```

`dev.corvus.ModuleSmoke` 只在 `-Dcorvus.modtest=1` 或环境变量 `CORVUS_MODTEST=1` 时启动
（`build.gradle` 的 run 配置没有新的 `property` 行，所以用环境变量传；子进程继承环境）。
它跑完自己 `server.halt(false)`，日志前缀 `[module-smoke]`。

## 6. 已知未做

* 方块光照（light engine）未接，理由见第 3 节；现在的效果是「看得见的光晕 + 地面光池」。
* `underwater` 参数只做了「在水里没有该参数就熄灯」；水下额外效果（颜色/衰减）未做。
* 自动拾取没有「白名单/黑名单物品」配置；1.21 也没有。
