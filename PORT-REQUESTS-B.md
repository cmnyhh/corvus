# PORT-REQUESTS-B — 界面与方块层（B）需要的注册项

> 由 A 合并进 `Corvus.java` 构造器。所有类已在 `src/main/java/dev/corvus/` 下就位，B 不改 `Corvus.java`。
> 每个 `register(bus)` 内部只注册自己的 registry，可重复调用（内部有 `wired` 幂等守卫，网络/事件监听不会重复挂）。

## 1. 在 `Corvus()` 构造器里加三行

```java
TerminalMenus.register(bus);        // 菜单 + shift-右击打开终端
SurvivalWorks.register(bus);        // 装配台方块/方块实体/菜单 + 23 零件 + 蓝图目录网络包 + 数据重载 + 牧师交易
ChargingDockRegistry.register(bus); // 充电座方块/物品/方块实体 + 电池/无人机的 FE Capability
```

（这三行建议放在 `TABS.register(bus)` 之后、`bus.addListener(Corvus::attributes)` 之前。）

## 2. 菜单 `ForgeRegistries.MENU_TYPES`

| 注册名 | MenuType | 说明 |
|---|---|---|
| `huhu_dronepal:terminal` | `TerminalMenu` | 终端面板（15 个部件格 + 背包） |
| `huhu_dronepal:cargo_one` | `CargoMenu` 1 行 | 容量 9 |
| `huhu_dronepal:cargo_two` | `CargoMenu` 2 行 | 容量 18（锈钉 12 也用 2 行） |
| `huhu_dronepal:cargo_three` | `CargoMenu` 3 行 | 容量 27 |

## 3. 方块 / 方块实体 / 方块物品

| 注册名 | 类型 | 类 |
|---|---|---|
| `huhu_dronepal:assembly_bench` | `Block` + `BlockItem` | `AssemblyBlock`（`BaseEntityBlock`，金属音，硬度 3.5，有 ticker） |
| `huhu_dronepal:assembly_bench` | `BlockEntityType` | `AssemblyStation`（9 输入 + 1 产物 + 任务状态/NBT） |
| `huhu_dronepal:assembly_bench` | `MenuType` | `AssemblyMenu` |
| `huhu_dronepal:charging_dock` | `Block` + `BlockItem` | `ChargingDockBlock`（8/16 高，noOcclusion，有 ticker） |
| `huhu_dronepal:charging_dock` | `BlockEntityType` | `ChargingDockEntity` |

## 4. 物品：23 个装配零件（`SurvivalWorks.PARTS`，同名注册）

`airframe, servo_motor, circuit_board, battery_cell, optics, armor_plate, heat_sink, flight_controller,
speed_controller, powertrain, sensor_array, weapon_interface, medical_pump, combat_core, scan_core,
carry_core, medic_core, light_core, solar_core, link_core, upgrade_kit, advanced_upgrade_kit, medical_supply`

## 5. 创造页

A 的 drone 创造页 `displayItems` 里追加两行（共 24 项：装配台 + 23 零件 + 充电座）：

```java
SurvivalWorks.addToTab(output);       // 装配台 + 23 零件
ChargingDockRegistry.addToTab(output); // 充电座
```

## 6. 网络（1.20.1 用 SimpleChannel，不是 payload registry）

`SurvivalWorks` 内部自建 `SimpleChannel("huhu_dronepal:assembly", protocol "1")`，消息 0 = 蓝图目录
（服务端在装配台菜单打开时发给该玩家）。**只需调用 `SurvivalWorks.register(bus)` 一次**，不要重复。

## 7. 客户端屏幕注册

不需要动 `Corvus.java`：`client/ScreenRegistry.java` 自己带
`@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = MOD)` + `FMLClientSetupEvent`，
里面 `MenuScreens.register` 了终端 1 个、货舱 3 个、装配台 1 个屏幕。

## 8. 需要 A 的类补 public（B 已按这些签名写，否则编译不过）

```java
// Hardware.java
public static List<Hardware.Module> installed(ItemStack controller);   // 货舱容量要读 carry 模块 params
public static Hardware.Module definition(ItemStack part);              // （可选，B 目前未直接调用）
// Hardware.Module
public int integer(String key);                                        // 已被 record 组件方法覆盖即可

// ServerOptions.java
public static double chargeMultiplier();    // 充电座线缆 2000 FE/t × 倍率
```

已按 A 的答复实现并依赖：`Hardware.controller/contents/save/installError/removalError/energy/capacity/charge/chipId/batteryTier/MODULE(RegistryObject)/moduleStack/productId/BATTERIES(List<RegistryObject<Item>>)/online/powered`，
`Batteries.model`，`PaintCans.PALETTE`，`Frames.of/Frame.moduleSlots()/ammoSlots()/maxBatteryTier()/Frames.ammoSlots(String)/Frames.name/IDS/PAINTS/create/paint/id`。
由 C 提供并依赖：`CombatSlots.isGun/isAmmo/installError`、`DroneRemote.barksOn/toggleBarks`、`DroneEntity.ownerId()`。

## 9. 资源路径（`data/**` 归 A，请注意 1.20.1 是复数目录）

当前仓库里是 1.21 的单数路径，1.20.1 不认，需要改名，否则方块不掉落、配方不加载：

- `data/huhu_dronepal/loot_table/` → `loot_tables/`（`blocks/assembly_bench.json`、`blocks/charging_dock.json` 必须生效）
- `data/huhu_dronepal/recipe/` → `recipes/`
- `data/huhu_dronepal/advancement/` → `advancements/`
- `assets/huhu_dronepal/lang/*` 已包含 B 用到的全部键，`PORT-LANG-B.json` 为空对象。
