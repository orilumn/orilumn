package orilumn.reader.engine

import kotlin.time.TimeSource

/**
 * R28 测量基础设施：**运行期 A/B 开关**。
 *
 * ## 为什么要有这个（这一整轮踩的坑）
 *
 * 此前所有 A/B 都是"装 A 版跑 N 次 → 装 B 版跑 N 次"。这个口径有**三个已知缺陷**，
 * 其中两个直到 R27 末才被发现：
 *
 * 1. **不交叉**。慢时段（其它应用、CPU 降频、温控）会**整段落在一侧**。
 *    R31/R32 的 `openT` 就出现过同锚点、同 `tableHit` 下 983/961/949 与 3177 并存
 *    ——同锚点 3.3 倍离群。顺序采样无法把这种离群摊到两侧。
 * 2. **锚点漂移**。采样途中翻页会改写续读位置。R31 自己的锚点就走过
 *    `ch4@3242 → ch3@2075 → ch7@12982 → ch7@12091`，跨锚点的数根本不可比，
 *    而我一度把 `ch7@12091` 的 719ms 和 `ch7@12982` 的 964ms 放在一起比过。
 * 3. **0 值被当通用结论**（另一条，已单列订正）。见 `docs/待分析-GIMP开书慢-结论清单.md`。
 *
 * [warmupBlocks] 就是为此存在的：它把一个**已知答案**的开关搬到运行期，
 * 于是"装一次包、交替 A/B/A/B"成为可能，可以**先验证这套量法本身**，
 * 再拿它去量下一刀（`computeStyle`）那个尚不存在的优化。
 *
 * ## 用法
 *
 * 平台层（Android `ReaderActivity`）读 intent extra 写入 [apply]：
 * ```
 * adb -s <dev> shell am start -n orilumn.reader/.MainActivity --es ab "warm=3"
 * ```
 * 引擎层只管 [warmupBlocks] 的取值，不认识 intent——平台差异留在平台层。
 *
 * **默认值即生产行为**：不传 extra 时 [warmupBlocks] = 0，与 R26 实测定论一致。
 */
object AbSwitch {

    /**
     * 开书锚页预热块数。**实测为净亏，默认 0，勿开。**
     *
     * **两笔实测，结论相反，各自在自己的锚点上成立——不可互相覆盖：**

     * R26/R27（**ch7**，169 叶大章，同锚页 page15 / blocks[125,130) / 5 块，顺序装机各 3 次）：
     * ```
     *   warm=0：openT 1297ms 均，锚页 shape 584ms 均，真排版 sStyles+sSkia 85ms
     *   warm=3：openT 1347ms 均，锚页 shape 262ms 均 + warm 367ms，真排版 45ms
     * ```
     * 预热确实暖到了东西——锚页 Skia 断行 85→45ms——但代价是 3 块 **367ms**，
     * 而锚页 5 块才 262ms。净 +45ms，端到端 +50ms。**判为净亏。**
     *
     * R28（**ch12**，同锚页 12@21972，**单装机运行期交替** 7/8 次，见 `tools/ab_probe.py`）：
     * ```
     *                warm=0    warm=3     差
     *   openT         744ms     770ms    +26ms
     *   DISK-HIT      288ms     262ms    -26ms
     *   anchorShape   165ms      92ms    -73ms
     *   warm            0ms      60ms    +60ms
     * ```
     * 净 **+39ms 赚**。**两个数都对**：ch7 的块比 ch12 贵一个量级，
     * 3 块预热在 ch7 上要 367ms、在 ch12 上只要 60ms。
     *
     * **所以"暖机净亏"这个结论是 ch7 专属的，不是全局的。**
     * 默认仍取 0（未在多数锚点上验证，不拿单点结论改生产行为）；
     * 若日后要按章大小动态决定，这里是入口。
     *
     * 曾把这笔当"class-load/JIT/字体初始化的一次性固定成本"，已否证：同进程内同代码同块数，
     * ch7 warm(3 块)=366ms 而 ch0 只 30~37ms，随内容变 11 倍，不是固定成本。
     *
     * 代码路径保留（默认 0 即完全短路），若日后要试"更便宜的暖机"（例如只暖 1 块，
     * 或换更便宜的块）从这里下手，别直接开 3。
     *
     * R28 起本值可由 intent extra `ab="warm=3"` 运行期覆盖——**仅为让量法本身可被验证**
     * （见本对象 KDoc），生产路径不传 extra 即为 0。
     */
    @Volatile
    var warmupBlocks: Int = 0

    /** 已启用的具名开关（供后续优化用；具名而非布尔，是为了日志能自解释）。 */
    private val on = mutableSetOf<String>()

    fun isOn(name: String): Boolean = name in on

    /**
     * R29 靶子：`regexHoist` 打开时 13 处空白切分用**预编译**的 companion 常量，
     * 关闭时（= 生产默认）保持 R29 之前的原样——每次现场 `new Regex("\\s+")`。
     *
     * 起因（实测，非推断）：R28 量出 `computeStyle` 的 `cBuild` 占 `sStyles` 的 ~75%；
     * R29 再拆发现「边」六兄弟独占 `cEdge=42ms / cBuild=58ms` 的 72%，
     * 而这六兄弟里 13 处 `split(Regex("\\s+"))` 每次都现场 new 一个 Regex
     * （`Pattern.compile`，同一模式被编译上千次）——代码长相上明摆着的浪费。
     *
     * **但第一版替代方案（手工扫描）实测更慢，已否证**（cEdge 35.5ms vs 28ms，8/8 分离）。
     * 那批数据里两臂都已预编译，故"现场构造 `Pattern.compile` 到底多贵"至今**未测**——
     * 这个开关就是为了直接量它。做法与 R26/R28 同一套：单一职责的运行期开关，
     * 定论后把赢家固化、把这个开关删掉。
     */
    fun regexHoist(): Boolean = isOn("regexHoist")

    /**
     * R29 第三层靶子 `borderSides` **已定论并删除**（R39，详见
     * `docs/待分析-GIMP开书慢-结论清单.md` §3b.7）。
     *
     * 实测（单装机交叉 8+8、锚点一致 12@21972、两臂 `eBrd=59`/`sEls=52` 逐值相同）：
     * ```
     *              原样      borderSides    差
     *   eCol       5.0ms         1.0ms      -4.0ms
     *   eSty       8.0ms         5.0ms      -3.0ms
     *   cEdge     37.5ms        28.0ms      -9.5ms (-25%)
     *   cBuild    57.5ms        51.0ms      -6.5ms
     *   openT      731ms        708ms      -22ms (-3.1%)
     * ```
     * 改动本体已固化进 [orilumn.reader.engine.css.StyleComputer]：`parseBorderColors` /
     * `parseBorderStyles` 走显式四槽 + 预建 key 常量，不再每次 `listOf(4)` 与
     * `"border-$side-*"` 拼接查表。语义等价（同 key 同顺序同回退链）。
     */

    /**
     * 应用一串 `k=v` 形式的对（`warm=3`），解析失败或未识别的键**静默忽略**
     * ——测量设施不该有能力把 App 搞崩。
     */
    fun apply(spec: String?) {
        if (spec.isNullOrBlank()) return
        for (part in spec.split(',', ';', ' ')) {
            val kv = part.split('=', limit = 2)
            if (kv.size != 2) continue
            val (k, v) = kv[0].trim() to kv[1].trim()
            when (k) {
                "warm" -> warmupBlocks = v.toIntOrNull()?.coerceIn(0, 64) ?: 0
                else -> if (v == "1" || v == "on") synchronized(on) { on += k }
            }
        }
    }

    /** 供日志自解释：当前变体描述，`none` 表示生产默认。 */
    fun describe(): String {
        val flags = synchronized(on) { on.sorted() }
        return (flags + if (warmupBlocks != 0) listOf("warm=$warmupBlocks") else emptyList())
            .takeIf { it.isNotEmpty() }?.joinToString(",") ?: "none"
    }

    // ---- 控制量探针 ------------------------------------------------------

    @Volatile
    private var sink: Int = 0

    private val ctrlBuf = IntArray(1024) { (it * 0x9E3779B1u.toInt()) xor 0x5A5A5A5A }

    private val timeSource = TimeSource.Monotonic

    /**
     * 与书无关的定长 CPU 负载，返回耗时（毫秒，小数）。
     *
     * ## 它管什么，不管什么
     *
     * **管**：把"这一跑整体就是慢"从不可观测变成可观测。R31 那次同锚点 3177ms
     * 的离群，事后无法归因——现在可以看控制量是否同时变慢。
     *
     * **不管**：它**不是**精确归一化器。想拿 `openT / ctl` 直接当指标是错的：
     * 负载是纯整数运算，与开书期间的塑形/IO/GC 不是同一资源，比例关系不保证线性。
     * 正确用法是**离群标记**——控制量离群就丢弃该样本，剩下用中位数。
     *
     * 标定（探针自身成本必须先量，否则量出来的是探针不是设备）：
     * 60000 轮实测 55~80ms，占 `openT`(约 700ms) 的 10%，**它在扰动被测量**；
     * 降到 4000 轮后实测 1.6~5.1ms（占比 <1%），仍能看出设备整体快慢——
     * R33 那批 55~80ms 的长尾，正是 R31 那种 3 倍离群的同期现象。
     * 因此**相对**离散阈值取 50%，不取绝对值：控制量本身只有几毫秒。
     *
     * 放在开书末尾测：此时 JIT 已暖，作为"当下设备状态"的代理比开书前更有代表性。
     */
    fun controlMs(): Double {
        val t0 = timeSource.markNow()
        var a = sink
        // 每轮一次**内存查表变址**（依赖不可预测，JIT 折叠不掉）+ 一次移位异或。
        // 刻意**不用**纯算术：单一乘数的算术循环会被识别成常量折叠，耗时塌到 ~0。
        for (r in 0 until CTRL_ROUNDS) {
            a += ctrlBuf[a and 1023]
            a = a xor (a ushr 11)
        }
        sink = a
        return t0.elapsedNow().inWholeMicroseconds / 1000.0
    }

    /**
     * 轮数标定：**探针自身成本必须先量**，否则量出来的是探针不是设备。
     * 60000 轮实测 55~80ms——`openT` 才 700ms，探针独占 10%，它在扰动被测量。
     * 按此线性折算取 4000 轮（≈4~5ms）：高于计时噪声（~1ms），占比 <1%。
     */
    private const val CTRL_ROUNDS = 4_000
}
