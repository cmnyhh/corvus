# PORT-REQUESTS-G — 指令运行时 & 终端 FE/t（G）

> G 只做两件事：把 `order.heal` / `order.fetch` / `order.supply` 接到 1.20.1 的模块运行时，
> 并把 `DroneRemote.Status` 里的 FE/t 与光伏变成真数字。下面第 1 节是 A 需要合并的三行
> `build.gradle`；第 2 节是 lead 已授权、G 直接改掉的两处他人文件；第 3 节是**没有等价实现**的
> 行为差异，请不要把它们当成 1.21 的等价移植。

## 1. 需要 A 合并：`build.gradle` 的两个 dev-only 系统属性（当前用 `JAVA_TOOL_OPTIONS` 跑）

两个验收 harness 只认 JVM 系统属性（默认不设 = 完全不执行），所以它们**不需要**注册任何东西就能
在 jar 里静默存在；只是 `runServer` / `runClient` 的 JavaExec 不会自动带上它们。

```groovy
// runs.server 里，紧挨现有的 property 'corvus.hwtest'：
property 'corvus.gaps', (project.findProperty('corvusGaps') ?: '0').toString()
// runs.client 里，紧挨现有的 property 'corvus.smoke'：
property 'corvus.gapsShot', (project.findProperty('corvusGapsShot') ?: '0').toString()
```

合并后跑法就是 `gradle runServer -PcorvusGaps=1 -PcorvusLevel=GapSmoke` 与
`gradle runClient -PcorvusGapsShot=1 -PcorvusQuickPlay=CorvusSmoke`。在合并之前 G 是用
`JAVA_TOOL_OPTIONS='-Dcorvus.gaps=1'` 跑的（JVM 会拾取该环境变量，等效）。

新增的两个 dev-only 类（都带 `@Mod.EventBusSubscriber`，不需要在 `Corvus` 里注册）：

| 类 | 开关 | 作用 |
| --- | --- | --- |
| `dev.corvus.GapSmoke` | `-Dcorvus.gaps=1` | 专用服务端；跑完整 `/dronepal 治疗/捡一下/递弹` 链 + `Status.drain` 手算核对，然后 `halt` |
| `dev.corvus.devtools.GapShot` | `-Dcorvus.gapsShot=1` | 客户端；给/戴终端 → 装 `omd_bat_300 + combat_mk1 + scan_mk1` → 开终端面板 → 拍 `artifacts/client/13-terminal-fe.png` |

`GapSmoke` 放在 `dev.corvus`（不是 `devtools`）是故意的：被测的 `DroneSystems` / `CarryLight` /
`DroneRemote` 都是包级作用域，测试如果要求一套比游戏更友好的 API，就没在测游戏走的那条路。

## 2. lead 已授权 G 直接改的两处他人文件（记录在此以免重复改动）

1. `LocalDialogue.respond()` 的 `action` 分支：`default` 的「当前固件尚未实现这项指令」保留，
   但把 `order.heal` / `order.fetch` / `order.supply` 加进执行分支，成功回「指令已执行。」、
   失败回「当前无法执行，请检查能量、目标或冷却。」（与 1.21 的同名分支一致；
   `scan*` 成功仍然回空串，等扫描结果自己说话）。
   **`order.cover` / `order.hold` 没有加进去**：1.20.1 的 `Hardware.execute` 没有像 1.21 那样把
   它们路由到 `DroneCombat.setDeployed`，加了也只会得到「无法执行」，不如留着「尚未实现」。
2. `data/huhu_dronepal/intents/order.fetch.json`（**新增**）：别名 `捡一下/捡/取货/帮我拿/取回来/fetch`，
   `requires_module: mod_carry`，`denied` =「没有货舱模块，我装不下东西。」。
3. `data/huhu_dronepal/intents/order.supply.json`（**改**）：`requires_module` 由 `mod_link` 改为
   `mod_carry`（本工程的货舱递送门禁在 carry 上，link 是 TaCZ 弹药链路；见第 3 节），
   别名补 `递东西`，`denied` 改成「没有货舱模块，我递不了东西。」。

## 3. 行为差异（**不是等价实现**，请照实写文档/发布说明）

1. **无 TaCZ ⇒ `order.supply`（递弹）的判据是降级的。** 1.21 的 supply = `TaczLink.supply`：
   `link` 模块 + `deliver_mags` + TaCZ 的「这堆弹药是不是手上这把枪的」判定。1.20.1 的
   `TaczLink` 是 NOT PORTED（`isAmmo` 恒 false、`supply` 空实现），所以「弹药」这件事在本工程
   没有任何判据。G 的做法：`CarryLight.supply()` 在 `CombatSlots.available()`（= TaCZ 在）
   时用 `CombatSlots::isAmmo`，否则退化成「递送与玩家**主手同款**的货舱库存」。
   这是保守降级，不是弹匣投递；TaCZ 一接上，同一行代码自动切回真弹药判定。
2. **1.20.1 仍然没有逐 tick 能耗事务。** 1.21 的 `DroneSystems.second()` 会算
   `start + charge − drain` 并写回电池；本工程没有这条循环（这是既有缺口，不在 G 的范围）。
   因此：
   * `Status.drain` 是**按规则算出来的瞬时值**（已装且在线模块的 `drain_watts` 之和 × 厂商
     `drain_multiplier`），不是从电池里实测到的消耗。它每天都会变（模块上下线、故障、低电降载），
     和实际扣电一致的前提是那条事务将来接上。
   * `order.heal` 的「耗电」由医疗运行时自己记账：每秒扣
     `20 × (active_drain_watts − drain_watts)` FE（medic_mk1 = 160 FE/s），电池不够就中止本次治疗。
     这条账与将来的能耗事务**不会重复扣**（事务只扣各模块的常驻 `drain_watts`，active 的增量只在这里）。
3. **光伏取标称值，不含 `output_jitter`。** `solar_rust_g1.json` 有 `output_jitter: 0.3`；1.21 在
   每秒事务里抖动，本工程没有那条循环，所以 `Status.solar` 给的是 `clear/rain/night` 标称值
   （见天空才给，看不到天空给 0，无光伏模块给 -1）。抖动将来应加在事务里，不要加在这个字段上，
   否则 HUD/终端会每帧跳数字。
4. **`Status.drain` 是整数 FE/t。** 1.21 面板显示一位小数（combat_mk1 + scan_mk1 = 21.4 FE/t），
   这里 `int` → 21。取整粒度差异，不是方向差异。
5. **医疗自动开疗**沿用 1.21：只要 medic 在线、玩家不满血，`second()` 会自己 `startHealing`
   （消耗耗材、进冷却）。命令 `/dronepal 治疗` 只是立刻触发；两者共用同一套门禁，所以不会双扣。
   G 额外加了一条 1.21 没有的门禁：**轮盘里把 medic 关掉（`dronepal_off_medic`）即拒绝开疗**，
   否则那个开关在本工程里是死的。
6. **`CarryLight` 仍然没有拾取循环**（1.21 的 `CarryLight.second()`：范围内吸掉落物、自动投递）。
   本工程的货舱只能通过货舱界面装货；G 只加了投递方向（`deliver` / `deliverMatching` / `execute`），
   没有改既有语义。所以「捡一下」的语义是**把货舱里已有的东西递给主人**，不是让无人机去地上捡。

## 4. 语言键：不需要 A 合并任何东西

`PORT-LANG-G.json` = `{}`。新界面/提示全部复用现有键：

| 位置 | 复用键 |
| --- | --- |
| 终端功耗行、HUD 功耗 | `hud.dronepal.rate`（`%s FE/t`） |
| HUD 光伏行 | `hud.dronepal.generation`（`光伏 +%s FE/t`） |
| 递弹成功提示 | `dronepal.link.supplied`（`货舱已递送弹药（%s 件）`） |
| 对话拒绝/成功文案 | `LocalDialogue` 内的字面串（与 1.21 逐字一致，1.21 也没有键） |

## 5. G 改过的文件清单（都在白名单内 + 第 2 节那两处授权改动）

`Hardware.java`（仅新增两个只读方法 `drainPerTick` / `solarOutput`）、
`DroneSystems.java`（医疗运行时 + intent 路由 + 冷却键按 family 化）、
`CarryLight.java`（仅新增 `execute` / `deliver` / `deliverMatching` / `supply` 与私有搬运工具）、
`DroneRemote.java`（仅 `Status` 加 `int drain` 与 status 计算）、
`client/TerminalScreen.java`、`client/DroneHud.java`、
`DialogueServer.java`（仅新增测试钩子 `intentOf`）、
`LocalDialogue.java`（第 2 节第 1 条）、
`data/huhu_dronepal/intents/order.fetch.json`（新）、`order.supply.json`（第 2 节第 3 条）、
`GapSmoke.java`（新）、`devtools/GapShot.java`（新）。
`Corvus.java` / `build.gradle` **未改**。

## 6. 验收结果（真跑，2026-11-24）

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 构建 | 契约里的 `gradle build --no-daemon` | `BUILD SUCCESSFUL`；`build/libs/渡鸦-随行无人机-1.20.1-0.1.0.jar` = **955107 字节** |
| 缺口 smoke | `JAVA_TOOL_OPTIONS='-Dcorvus.gaps=1' gradle runServer -PcorvusLevel=GapSmoke` | **55 passed, 0 failed**（日志 `build/gap-smoke.log`） |
| 硬件回归 | `gradle runServer -PcorvusHwtest=1 -PcorvusLevel=GapRegress` | **103 passed, 0 failed**（未被本次改动破坏） |
| 界面回归 | `JAVA_TOOL_OPTIONS='-Dcorvus.smokeB=1' gradle runClient -PcorvusQuickPlay=CorvusSmokeB` | `SMOKE-B OK`，无 FAIL |
| 终端面板实拍 | `JAVA_TOOL_OPTIONS='-Dcorvus.gapsShot=1' gradle runClient -PcorvusQuickPlay=CorvusSmoke` | `artifacts/client/13-terminal-fe.png`（1708×960），面板功耗行显示 **21 FE/t**（= combat_mk1 14×1.10 + scan_mk1 6×1.00 = 21.4 → 21） |

smoke 覆盖到的断言（编号对应代码里的 `check` 名）：`dialogue.text.{治疗,捡一下,递弹,supply_ammo,heal_sentence}`（文本→intent）、
`drain.{data_combat,data_scan,measured_hand_calc,empty_0}`、`status.{drain_hand_calc,solar_none,wire.drain,wire.labels}`、
`solar.{nominal_clear,night_zero,no_sky_zero,status_matches_world}`、`nomodule.*`（三个 intent 全拒）、
`heal.{needs_supply,supply_consumed,second_order_refused,health_rose,energy_spent,once_per_use,auto_restart_on_cooldown,cooldown_refused,full_health_refused}`、
`supply.{delivered,conserved,empty_hold_refused,handler_false_when_empty}`、`fetch.{hold_emptied,bread,torch,conserved}`、
`offline.*`、`brownout.{carry_off,medic_on,drain_counts_online_only,supply_refused,fetch_refused,medic_only_off,heal_refused}`。
