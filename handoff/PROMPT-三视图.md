# 三视图生成提示词（交接用）

> 用途：给「Dronepal 无人机」生成一张**正交三视图**，作为建模参考。
> 工具：任意支持「图生图」的模型（推荐 Nano Banana Pro / Gemini 3 Pro Image，2K）。

---

## 1. 参考图（先喂这 4 张，按顺序）

| # | 文件 | 视角 |
|---|---|---|
| 1 | `refs/drone/front_560.png` | 正视图 |
| 2 | `refs/drone/right_560.png` | 右视（侧视） |
| 3 | `refs/drone/top_560.png` | 俯视 |
| 4 | `refs/drone/iso_fr_560.png` | 立体（辅助理解） |

实际路径：`blockbench-ysm-agent/models/togawa-sakiko/refs/drone/`

---

## 2. 参数

| 项 | 值 |
|---|---|
| 尺寸 | 2K |
| 比例 | 16:9 |
| 变体数 | 1（不满意再加，成本 ×N） |
| 参考图 | 上面 4 张 |

---

## 3. 提示词（英文，直接复制）

```
Redraw the reference drone as a clean orthographic TURNAROUND SHEET: three panels
side by side on plain white — FRONT, SIDE (right profile), TOP — identical scale,
aligned on a common baseline, no perspective.

Keep the exact same design and proportions as the reference: compact cubic body,
raised top sensor tower with one glowing cyan lens, four short arms with cylindrical
motors and X-shaped rotor blades, cyan camera window on the front face, cyan light
strips on both sides, two thin landing skids, and under the belly a two-axis gimbal
rail carrying a compact rifle pointing forward.

Style: clean hard-surface industrial design, matte gunmetal grey body, lighter grey
trim panels, cyan emissive accents, small orange warning stripes on the top shell.
Flat cel shading, crisp panel lines, no textures, no dirt, no background scenery,
no ground shadow, no text labels. Silhouettes must read clearly and be left/right
symmetric.
```

---

## 4. 追加约束（按需追加到提示词末尾）

| 遇到的问题 | 追加这句 |
|---|---|
| 枪画得太大/太小 | `The rifle must be no longer than 1.5× the drone body width.` |
| 三格比例不一致 | `Add thin guide lines separating the three panels; keep all three views exactly the same height.` |
| 细节乱加部件 | `Do not invent parts that are not visible in the reference.` |
| 想要纯白底便于抠图 | `Pure white background #FFFFFF, no gradients, no vignette.` |

---

## 5. 如果用「概念图」而不是体素模型做参考

若参考换成那张**宽扁悬停机概念图**（品红光环 + 侧舱推进器 + 四镜头），提示词替换为：

```
Redraw this hover drone as a clean orthographic TURNAROUND SHEET: three panels
side by side on plain white — FRONT, SIDE (right profile), TOP — identical scale,
no perspective.

Keep the exact design: wide flat hull built from stacked armour plates with a
chamfered nose, a raised cylindrical sensor pod on top with a magenta glow ring,
two side pods each carrying a forward-facing magenta circular thruster, a row of
four recessed lenses on the front face, long magenta light strips down both sides,
small orange warning stripes, twin rear exhaust nozzles, and a sled-like landing
skid underneath.

Style: clean hard-surface industrial design, matte charcoal body with light grey
armour plates, magenta emissive accents, flat cel shading, crisp panel lines,
no textures, no dirt, no background, no ground shadow, no text.
```

---

## 6. 注意（重要）

1. **AI 生成的是「推断」不是「还原」**：背面、内侧、被遮挡处都是模型脑补的，必须人工复核；不要拿生成图当精确尺寸依据。
2. 三视图容易**左右不对称**、**凭空虚增零件**（尤其枪的细节），这两点每次都要检查。
3. 真正用于建模的尺寸依据是 [MODEL-NOTES.md](MODEL-NOTES.md) 里的数字（跨度 1.45 格、碰撞箱 0.7 格等），不是生成图。
