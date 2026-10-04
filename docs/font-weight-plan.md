# Font Weight Settings — Feature Plan (Deferred)

> Status: **视觉模拟（合成粗体 + 合成斜体）已实现**（渲染层，无 UI 开关，恒定行为）；
> 其余设想（可变字体 `wght` 原生驱动、近邻字重吸附）**仍是计划，未实现**。

## Goal
Let the user control text weight in the reader across different font types, from true variable
fonts down to single-weight static fonts, with the best visual quality each font can offer.

## Design ideas

### 1. Variable fonts — true continuous adjustment
When the selected font is a variable font, drive the `wght` axis natively so weight changes are
smooth and lossless.

Candidate variable fonts to support:
- Source Han Sans VF
- Source Han Serif VF
- Noto Sans SC Variable
- Roboto Flex
- Roboto Serif
- Plus Jakarta Sans Variable

### 2. Static fonts with multiple discrete weights — snap + visual simulation
When the font is not variable but ships several discrete weights:
- If the requested weight is **near** an existing discrete weight, snap to that real weight so
  it renders at its true design.
- If the requested weight is **far** from any existing weight, fall back to **visual simulation**
  (e.g. fake bold / synthetic weight), optionally gated behind a toggle.

### 3. Static fonts with a single weight — simulated only
When the font provides only one weight:
- Always use visual simulation (synthetic weight).
- Optionally gated behind a toggle (persistent across the categories above).

## 已实现：视觉模拟（渲染层）

**动机**：中文字体（思源黑体/思源宋体/普惠体/方正悠宋/寒蝉端黑宋…）普遍**没有斜体面**，
书里的 `font-style: italic` 赢了级联却画不出斜体 —— 声明生效、视觉没生效。

**恒定行为，无 UI 开关**（用户拍板：不要开关，默认开）。

### 合成决策单源（纯函数）

`SkParagraphFactory.synthesisFor(faceWeight, faceSlant, reqWeight, reqItalic): FontSynthesis`

只回答「设备画不画得出」，**不改级联结果**（守住 `Cascade.kt` L15-20「without any post-hoc
mutation」铁律）：

```kotlin
embolden = reqWeight >= 600 && reqWeight - faceWeight > 100
oblique  = reqItalic && faceSlant != FontSlant.ITALIC
```

- 阈值用**严格 `>`**：请求 700 落到 600 面是 CSS 正常匹配，不算缺面；500 落 400 同理不合成。
- `faceWeight`/`faceSlant` 取的是**实到那张面**的 `Typeface.fontStyle`（实测：单面族请求
  700/ITALIC → 读回 400/UPRIGHT）。

### Font 构造单源

`SkParagraphFactory.synthFont(tf, sizePx, reqWeight, reqItalic)` —— 渲染层所有从 Typeface 造 Font
的路径（`faceTable` / `faceForCp` 保底 / `universalPass` / `fallbackWidth` / `systemFallbackFace`）
全部改走它，杜绝「某条路径漏了合成」。

### 合成粗体：零绘制侧改动

合成粗体是 `Font.isEmboldened` **属性**，不会被告墨路径漏掉。实测（skiko 0.144.6，jvmTest 钉死）：
加墨 +18.5%，而 **advance 逐值不变**（263.552 前后同值）⇒ **零几何影响、零重排**。

### 合成斜体：基线处画布剪切

```
drawOblique(canvas, baseY, oblique) {
    save(); translate(0, baseY); skew(SYNTHETIC_OBLIQUE_SHEAR, 0f); translate(0, -baseY); block(); restore()
}
```

- `oblique=false` 零开销。
- 切在基线而非原点：切在原点会把整行沿 y 平移 |shear|×baseY ≈ 24px，基线不再水平。

**`SYNTHETIC_OBLIQUE_SHEAR = -0.203f`（负号是刻意的，别改）**。实测 skiko 语义：
`Canvas.skew(a, b)` = `x' = x + a·y`、`y' = y + b·x`（**第一个参数**才是 x-by-y 系数）；
而**画布 y 轴向下** ⇒ 基线上方 `y − baseY < 0` ⇒ 要让字顶**右**倾（这才是斜体）必须 `a < 0`。

实测对照（Arial 80px，字顶 x）：直立 61 / `+0.203` → 49（**反斜，错**）/ `−0.203` → 72（正斜，对）。
真机（vivo PA2353，生产代码路径）同值：`upright(top=58 bot=40) → oblique(top=69 bot=40)`
—— 字顶右移 11px、字脚不动（基线锚定成立）。

倾角 ≈11.5°：一眼看得出是斜体，又不至于把宋体拗坏（浏览器合成斜体约 14°，这里更保守）。

### 真机 A/B 像素证据（合成开 / 关，同页）

同页同底色，只翻 `SYNTHETIC_OBLIQUE_SHEAR`（`−0.203` vs `0`），两次 APK 以 sha256 校验确为
当场构建（**教训**：A/B 若不校验 APK，可能拿旧产物当基线，把对的实现测成错的）：

- 只有**标题行**变化（y=280-348，行高 69，55/69 行有差异）：顶 1/4 dx = **+10.4**，底 1/4 dx = **+0.5**
- 其余 **24 行 dx 恒为 0** ⇒ **槽位隔离**成立，正文零影响

### 明确划在边界外（记录在案的取舍，非遗漏）

`LineWindowDrawer` 类 KDoc 已写明：`paintText`（list marker）与 `drawRuby` 注音走
`Paragraph.paint`，且同一 paragraph 也用于测量 —— 套剪切会破坏**量画同源**，故这两处不合成。

### 测试

`engine-skia/src/jvmTest/.../VisualSynthesisTest.kt` —— 14 条，含：

- 决策纯函数逐例
- 真字体 advance 逐值不变
- 基线锚定（含**负对照**：原点剪切须推移 >8px，否则断言无牙齿）
- 端到端 `drawLines`
- 生产取面出口 `faceForCp` 覆盖
- 槽位隔离（h1/h2 合成、正文不串）
- **方向锁**：裸几何判 `skew` 首参是 x-by-y 系数 + 常量必须为负 —— 独立于实现推导，
  有人把符号改回正立刻红

> 第一版断言写成 `topLo < up.topLo`（顶左移），恰好把**反斜 bug 一起钉住**、测试全绿。
> **方向判据必须独立于实现推导** —— 这是本轮最贵的教训。

## Open questions / notes (not decided yet)
- Whether the whole weight system is exposed as one scalar (e.g. -100..100) or a weight-per-slot
  model, and how it fractions with the existing font-replacement tiers.
- Whether "visual simulation" toggling should be one global switch or per-font.
- Persistence and per-book overlay integration.