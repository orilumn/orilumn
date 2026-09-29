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
 * ## R29：把 buildMs 再拆两笔
 *
 * `buildMs` 占 ~75%，但"153 行代码逐字段解析"这个描述**不足以指导改动**——
 * 到底是 72 个字段的查表、还是那几个 `parseEdges`/`parseBorder*` 家族？
 * 两者优化手段完全不同（前者去重复查表，后者去 Regex/列表分配）。
 * 读代码猜过三次都错了，所以这里加细分探针实测。
 *
 * 两笔：
 *  - [edgeMs]  margin/padding/border-width/border-color/border-style/border-radius 六个"边"家族
 *  - [fontMs]  font-family 三连（fontFamily / fontFamilies / monospace 各自解析同一字符串）
 *  其余即 `buildMs` 减去这两笔。
 *
 * 用法：`sink` 非 null 才计时，热路径只多一次静态读。置位方是排版层的 `ShapeProbe`，
 * 只在那一个 shape 窗口内挂（塑形全程单线程串行，挂在实例外的全局上也不会串味）。
 */
object CascadeProbe {
    /** `(matchMs, parseMs, buildMs, secondPassMs, calls)`。null = 关闭（默认）。 */
    var sink: ((Long, Long, Long, Long, Long) -> Unit)? = null

    /** R29：`(edgeMs, fontMs, calls)`；与 [sink] 独立置位，拆分时只挂它。 */
    var splitSink: ((Long, Long, Long) -> Unit)? = null

    /**
     * R29 追加：`cEdge` 的**第三层**拆分——边家族内部到底谁贵。
     *
     * 前两层已经否证了三个假设：
     *  1. `cBuild` 是不是"重复查表" → 重复查表只有 14 处，省不下 87ms，否；
     *  2. 是不是 `Pattern.compile` 现场编译 → 预编译臂 `cEdge=34ms` vs 原样臂 `34ms`，
     *     零差别，否（见 `AbSwitch.regexHoist`）；
     *  3. 是不是切分开销本身 → 手工扫描替代 `Pattern.split` **慢 8ms**，否。
     *
     * 所以那 34ms 在**边家族的实际解析工作**里。这一层按家族分笔：
     *  - [eBoxMs]  `parseEdges`（margin + padding 两次调用）
     *  - [eWidthMs] `parseBorderEdges`
     *  - [eColorMs] `parseBorderColors` / `eStyleMs] `parseBorderStyles`
     *  - [eRadiusMs] `parseBorderRadius`
     *  - [eAnyBorder] 走这条路径的元素数（= `cEdge` 的真实调用次数）
     */
    var familySink: ((Long, Long, Long, Long, Long, Long) -> Unit)? = null

    /**
     * R29 追加：`cEdge` 已占 cBuild 的 72%，再拆一刀成
     * `(regexMs, listMs, calls)`——「切分字符串」与「其余边计算」。
     *
     * 边家族六个函数各自 `Regex("\\s+")` 现场 new（`parseBorderRadius` 两处），
     * Kotlin 的 Regex 构造会**编译模式**，纯 CPU 浪费。但"Regex 占多少"不能靠读代码猜——
     * R29 前三次猜（级联深度 / 通用字体兜底 / 重复查表）全错，故实测。
     */
    var edgeSink: ((Long, Long, Long) -> Unit)? = null

    /** 便捷累加器：给置位方用，避免每个探针点都重复判空。 */
    fun hit(match: Long, parse: Long, build: Long, second: Long) {
        sink?.invoke(match, parse, build, second, 1L)
    }

    fun hitSplit(edge: Long, font: Long) {
        splitSink?.invoke(edge, font, 1L)
    }

    fun hitEdge(regex: Long, list: Long) {
        edgeSink?.invoke(regex, list, 1L)
    }

    fun hitFamily(box: Long, width: Long, color: Long, style: Long, radius: Long, anyBorder: Long) {
        familySink?.invoke(box, width, color, style, radius, anyBorder)
    }

    fun reset() {
        sink = null
        splitSink = null
        edgeSink = null
        familySink = null
    }
}
