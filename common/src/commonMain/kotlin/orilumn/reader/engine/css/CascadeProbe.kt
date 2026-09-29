package orilumn.reader.engine.css

/**
 * R26 诊断探针（渲染层 · 样式级联）：把「算一个元素的计算样式」这笔开销拆开。
 *
 * 起因：开书锚页 `sStyles=155ms / 134 元素 ≈ 1.16ms/元素`，而排版层只能看到
 * `inlineStyles` 的总账，不知道这 1.16ms 花在**选择器匹配**还是**属性值解析/建对象**上。
 * 本探针提供那把尺子，属于渲染层给自己装仪表，不改变任何层级职责划分。
 *
 * 三笔：
 *  - [matchMs]  [Cascade.winningDeclarations] 本体：规则组遍历 + 标签粗筛 + 选择器匹配 + 胜出收集
 *  - [parseMs]  `style="..."` 内联属性解析（[Cascade.parseInline]）
 *  - [buildMs]  [StyleComputer.computeStyle] 本体：把胜出声明变成一个 [ComputedStyle]
 *
 * 另有一笔 [secondPassMs]：通用字体名兜底那条「级联重跑一遍 + 再建一次样式」的路径。
 * 理论上是双倍开销，但只在 `font-family` 算出来是**单个裸通用名**（光写 `serif`）时触发。
 * Rust 书实测不触发（该书 CSS 只有 1 个表、3 条 font-family，全是带名字的栈）。
 *
 * 用法：`sink` 非 null 才计时，热路径只多一次静态读。置位方是排版层的 `ShapeProbe`，
 * 只在那一个 shape 窗口内挂（塑形全程单线程串行，挂在实例外的全局上也不会串味）。
 */
object CascadeProbe {
    /** `(matchMs, parseMs, buildMs, secondPassMs, calls)`。null = 关闭（默认）。 */
    var sink: ((Long, Long, Long, Long, Long) -> Unit)? = null

    /** 便捷累加器：给置位方用，避免每个探针点都重复判空。 */
    fun hit(match: Long, parse: Long, build: Long, second: Long) {
        sink?.invoke(match, parse, build, second, 1L)
    }

    fun reset() {
        sink = null
    }
}
