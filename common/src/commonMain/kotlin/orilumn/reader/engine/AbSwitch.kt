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

    /**
     * **显式**开启的具名开关（`ab="k=1"`）。具名而非布尔，是为了日志能自解释。
     *
     * 显式开集 / 显式关集 / 默认开集是**三张表**而非一张，原因见 [isOn]：默认开之后
     * 「关」必须能压过「默认开」，单一 `on` 集合表达不了这个优先级。
     */
    private val on = mutableSetOf<String>()

    /** 显式关闭的具名开关（`ab="k=0"`）。优先级高于 [DEFAULT_ON_SWITCHES]。 */
    private val off = mutableSetOf<String>()

    /**
     * 该具名开关当前是否生效：**显式关 > 显式开 > 默认开**。
     *
     * 注意是「显式关」压过「默认开」而非反过来 —— 反了的话
     * `ab="inhouseBreak=0"` 会被默认值翻回 on，回退阀就是个摆设。
     */
    fun isOn(name: String): Boolean = synchronized(on) {
        when (name) {
            in off -> false
            in on -> true
            else -> name in DEFAULT_ON_SWITCHES
        }
    }

    /**
     * 清空显式开/关集与 [warmupBlocks]，**回到各自默认值**（注意不是「全关」）。
     *
     * **只给测试用**，但不是可有可无的：具名开关此前**只有「加」没有「减」**，
     * 于是一旦某个用例 `apply("inhouseBreak=1")`，同 JVM 里后续**每一个**走
     * `LayoutParamKey.fromProfile` 默认值的用例都会被拖进自建变体 ——
     * 失败会以「与本次改动无关」的面貌出现在别的用例上，极难归因。
     *
     * 「回到默认值」而非「全关」是有意的：`inhouseBreak` 默认已是 on，若这里清成全关，
     * 依赖 `resetForTest()` 的用例就会拿到与生产**不同**的静止位，
     * 「复位后与生产一致」这个前提被破坏，测试就测了个别的世界。
     *
     * 生产路径**不要**调它：开关的语义是「进程启动时由 intent 决定、之后不变」，
     * 运行期改动会让已落盘的磁盘表与当前布局不同源（虽然变体进 `paramHash` 能兜住缓存，
     * 但同一本书前后两套布局混在一会话里仍不可取）。
     */
    fun resetForTest() {
        warmupBlocks = 0
        synchronized(on) { on.clear(); off.clear() }
    }

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
     * S3 接线开关：**自建断行器**（`engine-skia` 的 `InhouseParagraphBreaker`）接管正文断行。
     *
     * ## **默认 on = 生产行为就是自建断行器**（2026-10-01 改，见下）
     *
     * 原本默认 off（Skia 为生产行为，自建仅供 `ab="inhouseBreak=1"` 量测）。现在**默认 on**：
     * 装上包跑的就是自建断行器，`[orilumn.reader.engine.skia.InhouseParagraphBreaker]`
     * 从「只在开关内可达」变成生产主路径。Skia 侧保留为**回退阀**，
     * 真机上一条命令退回：`adb shell am start ... --es ab "inhouseBreak=0"`。
     *
     * 为什么改默认值时**必须同时**给 [apply] 补「关」的方向：默认 off 时
     * 「关」等于默认、不写就够用；默认 on 之后不补，`ab="inhouseBreak=0"` 会被静默忽略，
     * 回退阀当场变成单向门 —— 而它存在的全部理由就是「不用回滚安装包」。
     *
     * ## 变体仍必须进 `LayoutParamKey`
     *
     * 断行器换了 ⇒ 断点变了 ⇒ 页切点变了。而 [orilumn.reader.engine.text.LayoutParamKey] 的
     * `paramHash` 只由排版参数算出，**不含断行器身份**（同 T2f 查清的坑：`LayoutParamKey`
     * 也不含禁则表身份，改断行规则 `paramHash` 不变）。若开关不进键，真机上一拨开关就会
     * **命中按 Skia 断点算出的旧磁盘表**，量到「开关没生效」的假零差异。
     * 故 `LayoutParamKey.fromProfile` 的 `inhouseBreak` 形参默认就读本开关，
     * 18 个调用点零改动、单一读取点。
     *
     * 正因为变体进了 `paramHash`，**这次接线不需要 bump `LAYOUT_VERSION`** ——
     * 键已能精确区分两侧，两侧各自独立缓存、回退时能各自命中自己那份。
     *
     * ## 表格侧不在本开关内（TODO Q6）—— 但**不只**因为 `tableBreaker` 恒为 Skia
     *
     * 轻路径侧的 `BoxChapterLayouter.tableBreaker` 确实是恒 Skia，且 13 本语料 49 张表全落在
     * auto 分列三段式的 `avail>=totalMax` 段、min-content 一次都没被读过（§T2f）。
     *
     * 但**重路径原本并没有被隔离**：表格 auto 分列在重路径里用的是
     * `NormalFlowLayout.buildTableRows` → `tableCellPref` 里那个**透传**下来的断行器
     * （`NormalFlowLayout.kt:746`），也就是正文那个。正文一接线，重路径表格列宽就跟着本开关走，
     * 与轻路径分叉；而自建侧 `preferredWidth` 仍是接口默认的 `段长 x fontSizePx` 桩
     * （实测 Latin/URL 高估 2.0~2.6 倍），列宽经 `w[i]=pref[i]` 直通 ⇒ Latin 列宽翻倍。
     * 故接线必须额外用 `engine-skia` 的 `heavyPathBreaker` 把**度量方法**钉回 Skia，
     * 只让**断行**跟着开关走。见该函数 KDoc 与 `TableBreakerStaysSkiaTest`。
     *
     * ## 已知未验证项（默认 on 之后这些都进了生产面）
     *
     * 三道闸门（行数公平性 ≥95%、总行数比值 ∈[0.95,1.05]、章级 prepare ≤1.5x）
     * 与真机 A/B **尚未实测**。当前判断「可上线」依据的是 1110 个单测全绿 +
     * 默认侧逐值一致的锁，**不是**闸门数据。真机一旦量到闸门不达标，先 `inhouseBreak=0` 退回。
     */
    fun inhouseBreak(): Boolean = isOn("inhouseBreak")

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
     *
     * ## 具名开关两个方向都能写（`inhouseBreak` 默认 on 之后才补的）
     *
     * 原实现只有「加」：`ab="xxx=1"`。默认侧是 off 时这够用，因为关就是默认。
     * 但 `inhouseBreak` 改成**默认 on** 之后，「关」必须可达，否则
     * `ab="inhouseBreak=0"` 会被静默忽略 —— 真机上发现自建断行器有问题时，
     * **没有运行时手段退回 Skia**，只能重装包。而这个开关存在的全部理由就是
     * 「出问题关掉即可，无需回滚安装包」。单向门等于没有门。
     *
     * 语法：`=1` / `=on` 开，`=0` / `=off` 关，缺省值按 [DEFAULT_ON_SWITCHES]。
     */
    fun apply(spec: String?) {
        if (spec.isNullOrBlank()) return
        for (part in spec.split(',', ';', ' ')) {
            val kv = part.split('=', limit = 2)
            if (kv.size != 2) continue
            val (k, v) = kv[0].trim() to kv[1].trim()
            when (k) {
                "warm" -> warmupBlocks = v.toIntOrNull()?.coerceIn(0, 64) ?: 0
                else -> synchronized(on) {
                    when (v) {
                        "1", "on" -> { on += k; off -= k }
                        "0", "off" -> { off += k; on -= k }
                        // 其它值静默忽略：测量设施不该有能力把 App 搞崩。
                    }
                }
            }
        }
    }

    /**
     * **默认开启**的具名开关（缺省即为 on，不传 extra 也生效）。
     *
     * 取的是 [isOn] 这一处的真值，而不是让各调用点各自硬编码一个 `true`：
     * 真机上回退的唯一手段就是往 [apply] 写 `k=0`，若默认值在多处各写一份，
     * 一旦漂移就会出现「缓存键按一侧算、实际断行按另一侧跑」的最坏组合 ——
     * 那种错**不会报错**，只会安静地量出假结论（§T2f 查清的正是这类坑）。
     */
    private val DEFAULT_ON_SWITCHES = setOf("inhouseBreak")

    /**
     * 供日志自解释：当前**生效**的开关描述。
     *
     * 必须报「生效值」而不是「显式开集」——`inhouseBreak` 默认 on 且大多数时候
     * 没人显式写过它，若只报显式开集，真机日志会显示 `ab=none` 而实际跑的是自建断行器，
     * 正好在最需要归因的时候给出误导性的一行。
     */
    fun describe(): String {
        val flags = synchronized(on) { (DEFAULT_ON_SWITCHES + on).filter { isOn(it) }.sorted() }
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
