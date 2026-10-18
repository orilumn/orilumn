package orilumn.reader.engine.css

/** CSS `text-decoration-line` 的线型。 */
enum class DecorationLine {
    UNDERLINE,
    LINE_THROUGH,
    OVERLINE,
}

/** CSS `text-decoration-style`（`blink` 无视觉，解析即丢）。 */
enum class DecorationStyle {
    SOLID,
    DOUBLE,
    DOTTED,
    DASHED,
    WAVY,
}

/**
 * 元素**自身声明**的文本装饰（`text-decoration` 简写族 + `text-decoration-skip-ink`）。
 *
 * ## 为什么不是一个布尔（第三刀）
 *
 * 旧模型只有 `ComputedStyle.underline: Boolean`：`line-through` 与 `underline` 被并成同一个 true
 * ⇒ 删除线被画成**下划线**；颜色/粗细/线型（`text-decoration-color` / `-thickness` / `-style`）
 * 全无；`overline` 不画。浏览器把线型、颜色、粗细、样式当**各自独立的参数**（CSS Text Decoration 3），
 * 本类就是那份参数的携带者。
 *
 * ## 非继承、但**传播**（与浏览器的关键差异）
 *
 * `text-decoration` 是**非继承**属性：本字段只装**本元素自己声明的**那份。后代的文本仍会被祖先的
 * 装饰覆盖（"decorating box" 传播），但那是**收集期**的事（`collectTextDecorationRuns` 沿树把祖先
 * 的装饰栈叠到后代段上），不是继承。故「子元素写 `text-decoration: none` 取消父元素装饰」在浏览器里
 * **不成立**，本实现同样不成立 —— 这是规范行为，不是缺口。
 *
 * 同理每个装饰的外观（颜色/粗细/线型）取**声明它的那个元素**，后代只改变字形、不改这些参数。
 *
 * @property lines 线型集合；**空 = `none`**（无装饰）。
 * @property colorHex 声明值（`#rrggbb`）；**null = `currentColor`**，在**收集期**解析成声明元素的有效
 *   颜色（[ComputedStyle.colorHex] 已含继承，正是 currentColor 的定义）。故绘制侧拿到的是已解析值。
 * @property style 线型样式（实线/双线/点线/虚线/波浪）。
 * @property thicknessPx 声明值（px，em/% 已解）；**null = `auto` / `from-font`** —— 两者都由绘制侧
 *   按**声明元素的字体度量**取（`from-font` 与 `auto` 在这个粒度下同路，见 §第三刀实现设计）。
 * @property skipInk `text-decoration-skip-ink`（initial `auto` = true）：装饰线遇到字形墨迹
 *   （下划线遇到降部、上划线遇到升部）时在该处断开，而不是穿过字。`none` = 直画。
 */
data class TextDecoration(
    val lines: Set<DecorationLine> = emptySet(),
    val colorHex: String? = null,
    val style: DecorationStyle = DecorationStyle.SOLID,
    val thicknessPx: Float? = null,
    val skipInk: Boolean = true,
) {
    /** 有装饰线要画（空集合即 `none`）。 */
    val hasLines: Boolean get() = lines.isNotEmpty()

    companion object {
        /** `text-decoration: none`（初始值）。 */
        val NONE = TextDecoration()
    }
}

/** 是否含下划线（旧布尔口径的便捷读法；测试与判据用）。 */
val TextDecoration.hasUnderline: Boolean get() = DecorationLine.UNDERLINE in lines
