# PORT-REQUESTS-H（手册验证 / Patchouli）

给 **A**（拥有 `Corvus.java` / `build.gradle` / `mods.toml` / `data/**` 合并权）的请求。本人只做验证，不改这些文件。

## 1. `mods.toml`：加 `patchouli` 可选依赖（建议）

现状：`src/main/resources/META-INF/mods.toml` 没有 patchouli 依赖项，但本模组带了一整本 Patchouli 手册
（`data/huhu_dronepal/patchouli_books/corvus_manual/book.json`）、手册配方
（`data/huhu_dronepal/recipes/corvus_manual.json`，已经用 `forge:mod_loaded` 条件保护），
且 `book.json` 声明 `"creative_tab": "huhu_dronepal:drones"`，由 Patchouli 在创造栏注入本书。

建议在 `[[dependencies.huhu_dronepal]]` 末尾追加（`mandatory=false`，顺序 AFTER，和 tacz 的写法一致）：

```toml
[[dependencies.huhu_dronepal]]
    # Optional: the manual backend. Patchouli only supplies patchouli:guide_book and the book GUI;
    # every reference is data-side (book.json + a conditional recipe), so a 1.20.1 instance without
    # Patchouli still loads — the book item simply never exists.
    modId="patchouli"
    mandatory=false
    versionRange="[1.20.1-84,)"
    ordering="AFTER"
    side="BOTH"
```

验证过的组合：Patchouli `1.20.1-85-FORGE`（`Patchouli-1.20.1-85-FORGE.jar`，642732 bytes）在 Forge 47.2.0 客户端里加载本书成功。

## 2. 已由本人修掉的数据（不需要 A 动手，仅知会）

- `data/huhu_dronepal/recipes/corvus_manual.json`：`result` 从 1.21 的 `components` 改成 1.20.1 的 **NBT** 写法。
  注意 SNBT 里带冒号的键必须**加引号**，否则 `RecipeManager` 直接报
  `JsonSyntaxException: Invalid NBT Entry ... 缺少"}"`：
  ```json
  "result": { "item": "patchouli:guide_book", "count": 1,
              "nbt": "{\"patchouli:book\":\"huhu_dronepal:corvus_manual\"}" }
  ```
- `data/huhu_dronepal/patchouli_books/corvus_manual/book.json` 位置本来就是对的（Patchouli 1.20.1 的数据驱动书
  在 `data/<modid>/patchouli_books/<book>/book.json`），无需移动。

## 3. 给 A 的提醒：`HardwareSmoke` 的配方计数

`dev/corvus/devtools/HardwareSmoke.java:437` 断言“19 个配方，corvus_manual 因为没装 Patchouli 不加载”。
装上 Patchouli 后服务端会数到 **20**，这条断言会失败。它只在 `-PcorvusHwtest=1` 时跑，不影响正常构建；
如果要保留这条冒烟测试，判定条件建议改成“19 个，或 20 个且 MODLIST 含 patchouli”。（本人未改 Java。）
