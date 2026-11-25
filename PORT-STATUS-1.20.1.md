# Corvus 1.20.1 — 移植状态与已知差异

本分支是 **Minecraft 1.20.1 / Forge 47.x** 的移植版；1.21.1/NeoForge 版在 `1.21.1` 分支。

## 已实现
装备部署/召回（Curios `drone` 槽）、四机体九涂装、27 模块/10 电池、扫描（标记 + 字幕 + 光影自动切粒子）、终端面板、货舱界面、充电座（Forge Capability）、装配台（38 蓝图 + 滚动选单）、照明/拾取/太阳能运行时、帕秋莉手册（Patchouli 1.20.1-85 实测）、TaCZ 1.1.8+ 真实开火与弹药补给。

## 与 1.21.1 的行为对齐
- 灯光对话 `order.light` 两边都是**切换**（1.21.1 已同步改）。
- 子弹穿过主人/非目标玩家：1.20.1 用与 1.21.1 相同的判定（`DroneCombat.mayHitPlayer`）与相同的 TaCZ 注入点（`EntityUtil.getHitResult`，1.1.8 与 1.2.0 签名一致）。
- 手册文案两版逐字一致（见 `assets/huhu_dronepal/lang`）。

## 已知差异（有意接受）
1. **灯光表现**：1.20.1 是客户端光晕 + 地面光池，未接原版 light engine（需 mixin `LightTexture`，与光影冲突风险高）。**已决定暂不做**。
2. **枪械供弹机制**：1.21 给无人机挂 `ITEM_HANDLER` 让 TaCZ 自己换弹；1.20.1 因 `LivingEntity#getCapability(ITEM_HANDLER)` 在派发前返回装备栏包装，改为 `TaczLink.feed()` 本地喂弹。结果一致（实弹命中、真实耗弹），但不会播放 TaCZ 的换弹动画。
3. **`reload.type=inventory` 的枪**：不支持（默认枪包 54 把均为 magazine 型）。
4. **扩展弹匣容量**不模拟（取 `GunData.getAmmoAmount()` 基础值）。

## 验证
- 服务端 smoke：硬件 103 / 缺口 55 / 方块与装配 22 / 模块 50 / TaCZ 20。
- 客户端实拍：机体与动画、扫描标记与字幕、轮盘、HUD、终端面板、货舱、照明开关、帕秋莉手册。
