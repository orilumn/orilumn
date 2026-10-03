package orilumn.reader.engine.skia

import orilumn.reader.engine.AbSwitch
import orilumn.reader.engine.laying.ParagraphBreaker

/**
 * S3 回归夹具：**同一个不变量，在两个断行器变体上都必须成立**。
 *
 * ## 为什么要夹具而不是各处 `for (b in listOf(Skia…, Inhouse…))`
 *
 * 因为「两侧」不只是把断行器换掉就行。本仓的断行有**三条路径**会用到它：
 * 生产接线（`bodyParagraphBreaker`，受运行期开关控制）、测试显式构造、以及走
 * `BoxChapterLayouter` 轻路径的内部接线。**只换显式构造的那一份，另外两条仍是 Skia**，
 * 于是「重轻两路必须等价」这类不变量会**假失败**（重路径自建、轻路径 Skia，分叉是必然的，
 * 但它证明的不是被测性质，而是测试自己两侧没对齐）。
 *
 * 所以本夹具**拨开关**而不是换构造，并且把要传给被测代码的断行器**从
 * [bodyParagraphBreaker] 取**（同一单源）：显式路径与生产路径不可能分家。
 *
 * ## 为什么要断言变体真的生效
 *
 * 本仓已经两次栽在「改了没生效却量到零差异」上：T2f 的 `paramHash` 不含断行规则身份、
 * 以及 `versionCode` 写死导致构建号作废防线从未真正发生（教训 28）。
 * 一个「两侧都跑」但**两侧其实同一个实现**的锁，比没有锁更坏 —— 它给出虚假的绿灯。
 * 故每次进块都断言实现类型与标称变体一致。
 *
 * ## 为什么两侧都写「显式值」而不靠默认值（默认改成 on 之后踩到的）
 *
 * 变体默认从 off 改成 on 之后，「skia 那一臂」不能只靠 `resetForTest()` 拿 ——
 * reset 现在回的是**各自默认值**（= on），于是 skia 臂实际拿到的是自建，
 * 整把 Parameterized 锁的 skia 臂全部退化成「自建跑两遍」，静默失去对照。
 * 故 [applyBreakerVariant] 对两侧**都写显式值**：`inhouseBreak=1` / `inhouseBreak=0`。
 * 这与「测试不依赖全局默认」是同一条纪律。
 */
internal fun forEachBreakerVariant(
    /**
     * 混排字距（em）的**取值函数**，在开关拨好之后才求值。
     *
     * ## 为什么是函数而不是 Float
     *
     * 该值必须取 [orilumn.reader.engine.text.TypographicProfile.cjkLatinSpacingEmApplied]（**闸过的**），
     * 而那个 getter 自己要读 [AbSwitch.inhouseBreak] —— 本函数体第一件事才是拨开关。
     * 若把 `Float` 在进函数时就求值，读到的是**上一轮/默认值**的开关状态，
     * 于是自建臂可能拿到 0（Skia 臂的值）、Skia 臂可能拿到非 0，两臂都被喂错。
     *
     * ## 为什么默认 0f 仍然大量锁是对的
     *
     * 只有「显式路径 vs 生产路径（走 `BoxChapterLayouter`）」的比较型锁才必须喂 profile 真值；
     * 两侧都不经生产接线的锁留 0f 即正确（生产接线在那些锁里根本没参与）。
     * 喂错方向永远是「显式侧 0、生产侧非 0」⇒ 重轻不等价，症状与本文件开头描述的假失败同型。
     */
    cjkLatinSpacingEm: () -> Float = { 0f },
    block: (ParagraphBreaker, String) -> Unit,
) {
    for (label in breakerVariants().map { it.first }) {
        try {
            applyBreakerVariant(label)
            block(bodyParagraphBreaker(letterSpacingEm = 0f, cjkLatinSpacingEm = cjkLatinSpacingEm()), label)
        } finally {
            // 复位回默认（不是回 skia）：否则污染同 JVM 里后续每个变体敏感的用例。
            AbSwitch.resetForTest()
        }
    }
}

/** `(标称, 是否自建)` 两侧。单一来源，改判据只改这一处。 */
internal fun breakerVariants(): List<Pair<String, Boolean>> =
    listOf("skia" to false, "inhouse" to true)

/**
 * 守卫：变体必须**真的**换掉了。
 *
 * 用**显式抛错**而不是 Kotlin `assert`：后者依赖 JVM `-ea`，Gradle 一旦关掉断言（或换 runner），
 * 这条守卫就静默变成空操作 —— 那等于给一把假锁开了绿灯，比没有锁更坏。
 * 刻意不用 JUnit 的 assert，是为了不把 runner 差异带进夹具。
 */
internal fun guardVariant(label: String, on: Boolean) {
    check(on == AbSwitch.inhouseBreak()) { "变体 $label 的开关未生效，AbSwitch 状态与标称不符" }
    val breaker = bodyParagraphBreaker(letterSpacingEm = 0f)
    check(on == (breaker is InhouseParagraphBreaker)) {
        "变体 $label 未换实现：实际 ${breaker::class.simpleName}（单源工厂或开关脱节）"
    }
}

/**
 * 供 `@RunWith(Parameterized::class)` 用的参数表（用例多的测试用它，比逐个包一层省事得多）。
 *
 * 用法：类构造收一个 `variant: String`，`@Before` 调 [applyBreakerVariant]，
 * `@After` 调 [resetBreakerVariant]。
 */
internal fun breakerVariantParams(): List<Array<String>> = breakerVariants().map { arrayOf(it.first) }

/**
 * [breakerVariantParams] 的配套：把标称变体**显式**拨进运行期开关，并立刻验它生效。
 *
 * 两侧都写显式值，不依赖默认（默认已是 on，见类 KDoc 最后一节）。
 */
internal fun applyBreakerVariant(label: String) {
    AbSwitch.resetForTest()
    val on = breakerVariants().first { it.first == label }.second
    AbSwitch.apply(if (on) "inhouseBreak=1" else "inhouseBreak=0")
    guardVariant(label, on)
}

internal fun resetBreakerVariant() {
    AbSwitch.resetForTest()
}

/** 变体敏感的测试用它取断行器——从**单源工厂**取，不自己 new，保证与生产接线不可能分家。 */
internal fun variantBreaker(letterSpacingEm: Float = 0f): ParagraphBreaker =
    bodyParagraphBreaker(letterSpacingEm)
