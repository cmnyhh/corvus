# PORT-REQUESTS-W — 客户端轮盘 / 状态 HUD（W）

> W 只新增/修改了：`client/DroneWheel.java`（新）、`client/DroneHud.java`（新）、
> `client/TerminalScreen.java`（HUD 开关按钮 + 功耗行 + 模块计数），以及 `PORT-LANG-W.json`。
> `CorvusClient.java` / `ScreenRegistry.java` **未改动**：键位、GUI overlay、状态包接收槽都在
> 上述两个新类里自带 `@Mod.EventBusSubscriber`，不需要在别处注册。

## 1. 语言键：**不需要 A 合并任何东西**（`PORT-LANG-W.json` = `{}`）

轮盘、状态 HUD、终端面板新加的按钮与功耗行全部复用现有键：

| 位置 | 复用键 |
| --- | --- |
| 键位名 | `key.dronepal.wheel` / `key.categories.dronepal` |
| 轮盘扇区与提示 | `dronepal.wheel.*`（含 `hint` / `recall` / `deliver` / `supply` / `firemode` / `solar`） |
| 状态 HUD | `hud.dronepal.following/holding/charge/low/no_battery/modules/generation/rate` |
| 终端「状态栏：开/关」按钮 | `hud.dronepal.enabled` / `hud.dronepal.disabled` |
| 该按钮的悬浮说明 | `hud.dronepal.option` |
| 终端模块计数 | `terminal.dronepal.modules` + 纯数字（`模块 2/3`） |
| 终端功耗行 | `hud.dronepal.rate`（现在显示 `— FE/t`，有 telemetry 后自动变真值） |

这样 W 的改动不依赖语言文件的所有权，A 不需要跑合并。

## 2. 需要 A（或 C）决定的服务端增量：功耗（FE/t）没有来源

1.20.1 没有 `DroneTelemetry` 包；`DroneRemote.Status` 里也没有 drain/rate 字段。
`TerminalMenu.syncDisplay()` 里对 `state[9..12]`（consumed/generated）写死的注释就是
「等 telemetry 循环来填，现在报 0 而不是编数字」。所以：

* 终端面板的功耗行现在显示 `功耗 — FE/t`；
* 状态 HUD 显示 `电量 x%` + `模块 在线/已装` + （有太阳能模块时）`光伏 +N FE/t`
  （`Status.solar` 是真实字段，直接用）。

如果要让功耗行变成真数字，最小改动是二选一（都在 W 的文件之外，W 不碰）：

```java
// 方案 1（推荐，改动最小）：DroneRemote.Status 增加第 9 个字段
//   int drain;   // 最近一个采样窗口的净功耗 FE/t（负=放电）
//   encode/decode 各加一行 buffer.writeInt/readInt，CorvusNetwork 的 handler 不用改。
// 客户端拿到后：DroneHud 用 hud.dronepal.rate，TerminalScreen 用 terminal.dronepal.power。
```

```java
// 方案 2：移植 1.21 的 DroneTelemetry（40 tick 一发，含 energy/capacity/fitted/online/rate/mode），
// 在 CorvusNetwork.register() 里加一条 registerMessage，客户端新增一个接收类。
```

在拿到这两个之一之前，W 不会在面板或 HUD 上写任何编造的 FE/t。

## 3. 已确认无需他人改动

* `CorvusNetwork` 已有 `DroneRemote.Query` / `Command` / `Status` 三条消息，W 直接用
  `CorvusNetwork.CHANNEL.sendToServer(...)`，服务端 handler 原样复用（含 (nonce, revision)
  重放保护与 3 tick 限流）。
* `CorvusNetwork.register()` 已在 `Corvus` 构造器里调用（C 的请求已合并），W 不需要再加。
* `HudOptions.SPEC` 已作为 CLIENT 配置注册，`TerminalScreen` 直接 `HudOptions.ENABLED.set(...)` +
  `SPEC.save()` 落盘到 `config/dronepal-client.toml`。
* 终端面板的 HUD 按钮 id 是 `2`（`TerminalScreen.BUTTON_HUD`，本地常量，不进 `TerminalMenu`）。
  `TerminalMenu.clickMenuButton` 对未知 id 返回 `false`，服务端不会报错也不会改物品。
  **如果以后 `TerminalMenu` 要新增按钮，请不要用 2。**

## 4. 与 HUD 开关相关的注意点

`RunClient` 的 dev 环境里 `HudOptions.ENABLED` 在配置未加载时会抛异常；`DroneHud` 用
`HudOptions.hudEnabled()`（无异常读，默认 true），`TerminalScreen.toggleHud()` 用 try/catch 包住
写入，专用服务端不会因此崩溃（客户端类也只会在 Dist.CLIENT 加载）。

## 5. 验收用的 dev 开关（不进入正常玩法路径）

`DroneWheel` 里带一个和 `devtools/SmokeClient` 同类的开关：`-Dcorvus.wheelSmoke=1` 时按 tick 脚本
打开轮盘、发一次 SCAN 指令、拍 `artifacts/client/10-wheel-open.png` / `11-drone-hud.png` /
`12-terminal-panel.png`，然后 `minecraft.stop()`。默认关闭（系统属性不设就完全不执行）。

跑法（不需要改 `build.gradle`，`corvus.artifacts` 已经由 build 脚本指到 `artifacts/client`）：

```
JAVA_TOOL_OPTIONS='-Dcorvus.wheelSmoke=1' \
GRADLE_USER_HOME=<repo>/.dronepal-gradle <gradle 8.14.3>/bin/gradle runClient --no-daemon \
  -PcorvusQuickPlay=CorvusSmoke
```

另外两个环境坑（不是代码问题，但会让人以为客户端崩了）：

* macOS 显示器睡眠后 GLFW 报 `Can't find a primary monitor`，客户端直接起不来 —— 跑之前先
  `caffeinate -d -u` 顶住（`displaysleep` 是 2 分钟）。
* 拍中文界面要 `run/options.txt` 里 `lang:zh_cn`；仓库默认是 `en_us`，W 拍完已经改回 `en_us`。
