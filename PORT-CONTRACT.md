# 1.20.1 移植共享契约（并行代理必读）

工程：本目录（Forge 1.20.1-47.2.0 / Java 17 / modId `huhu_dronepal`）。
1.21 参考工程：`../dronepal`（只读，禁止修改）。

## 构建命令（唯一可用）
```
GRADLE_USER_HOME=/Users/lyk/Documents/deepseek-harness/default-workspace/.dronepal-gradle \
/Users/lyk/.gradle/wrapper/dists/gradle-8.14.3-all/cbf6zifq8xavouihta8md72jo/gradle-8.14.3/bin/gradle build --no-daemon
```

## 已完成（阶段 1，勿重做）
- `dev.corvus.data.CorvusData`（NBT 适配：string/int/bool/itemList）、`ItemTagData`、`CorvusKeys`
- `Frames`（4 机型/9 涂装别名）、`DroneItem`（序列号/机型/涂装）、`DroneEntity`（跟随+同步机型涂装）
- `DroneCurios`、`DroneDeployment`、`client.DroneMesh/DroneRenderer/CorvusClient`
- 资源已全量拷贝并转 1.20.1 语法（配方 result.item、curios tags/items、data/forge/loot_modifiers）

## 文件所有权（避免并发写冲突）
- **A（数据与硬件）**：`Corvus.java`、`build.gradle`、`data/**`、`Frames.java`、`Hardware*.java`、`Batteries*.java`、`PaintCans*.java`、`ModuleTooltips*.java`、`ServerOptions/HudOptions`、`Loot*.java`
- **B（界面与方块）**：`TerminalMenu/TerminalMenus/client.TerminalScreen`、`CargoMenu/client.CargoScreen`、`AssemblyStation/AssemblyMenu/client.AssemblyScreen`、`SurvivalWorks`、`charging/**`、`client.GuiIcons`
- **C（战斗/扫描/对话）**：`DroneCombat`、`TaczLink`、`Engagement*`、`DroneSystems`、`ScanDisplay/ScanSpeech/ScanMarkerMode`、`Dialogue*`、`LocalDialogue`、`DroneArchive`、`Comms*`、`client.DroneClip` 及动画/挂枪渲染
- 任何人**不得**改他人文件；需要注册项（物品/方块/菜单/事件）时，在 `PORT-REQUESTS-<你的代号>.md` 写一行（类名、注册内容、初始化调用），由 A 合并进 `Corvus.java`。

## 语言键
`assets/huhu_dronepal/lang/{zh_cn,en_us}.json` 由 **A** 独占写入。B/C 需要新键时，写进各自的 `PORT-LANG-<代号>.json`（扁平 `"key": "值"`），A 负责合并。

## 硬性规则
- 1.20.1 没有 DataComponents：禁止 `import net.minecraft.core.component.*`，一律走 `CorvusData`。
- `ResourceLocation` 用构造器；注册用 `ForgeRegistries`。
- 网络用 `SimpleChannel`；能量用 Forge `Capability` + `AttachCapabilitiesEvent`。
- 所有改动必须能通过上面的 build；报告里给出真实命令输出与 jar 字节数。
