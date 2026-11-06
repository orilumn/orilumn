package orilumn.reader.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import orilumn.reader.data.font.SubfamilyMetric
import orilumn.reader.data.font.SystemFontFace
import kotlin.math.abs

/**
 * 桌面（Linux）系统字体枚举桥：**直连 fontconfig**（用户层·桌面，与 macOS 的
 * [MacFamilyNames] CoreText 桥同级；平台差异只在本文件）。
 *
 * 为什么不继续用 skiko 的 `FontMgr.matchFamily`：Linux 下它是 fontconfig 后端，实现口径是
 * 「把**全库字体按匹配分排序后整份返回**」，不是「只返回该族的字形」。照原样读会得到
 * 两个可见症状：
 *  1. 每个族都挂着全系统两百多个字形名（面板字重表里冒出 Book/Condensed/Demi… 与本体无关的档）；
 *  2. fontconfig 为「族名 + 字重」另挂的别名族（`思源宋体 CN Medium`、`方正悠宋+ GBK 509R`、
 *     `霞鹜文楷 Light`、`Source Han Serif CN Bold`…）被当成独立族，同一套字体在列表里出现七八行。
 *
 * fontconfig 自己知道真相：一个字体面的 `family` 可以有多条（基名 + 各语言名 + 字重别名），
 * 字重名在 `style` 里。本桥一次 `FcFontList` 取全量，再按 [fold] 把别名族折回基族。
 *
 * 只读枚举，跨平台语义不变：非 Linux（mac/Windows/Android）[available] 恒 false，
 * 宿主回落到 engine-skia 的 skiko 枚举。
 */
object FontconfigFonts {

    /**
     * fontconfig 的一个字体面（一个文件的一个 face）：族名候选 + 该面的风格名 + 该面**自己声明**的
     * 字重与宽度。
     *
     * @property weight fontconfig 字重刻度（[Fc] 的 `weight`，非 OS/2 的 100–900）；读不到为 [WEIGHT_UNKNOWN]。
     * @property width fontconfig 宽度刻度（`Fc` 的 `width`，100 = 正常，50…200 为窄/宽）；读不到为 [WIDTH_UNKNOWN]。
     */
    internal data class Face(
        val families: List<String>,
        val style: String,
        val weight: Int = WEIGHT_UNKNOWN,
        val width: Int = WIDTH_UNKNOWN,
    )

    /**
     * 折叠结果。
     *
     * @property families 基族名（逻辑键，排序后；喂 `SystemFontSource.families`）。
     * @property faces 基族名 → 字重名列表（喂 `SystemFontSource.facesFor`）。
     * @property display 基族名 → 本地化（中文）展示名（仅当基名本身不含 CJK 时才有条目）。
     */
    internal class Folded(
        val families: List<String>,
        val faces: Map<String, List<SystemFontFace>>,
        val display: Map<String, String>,
    )

    /** fontconfig 的最小函数面（只用枚举：初始化 / 模式 / 列表 / 读字符串 / 释放）。 */
    private interface Fc : Library {
        fun FcInit(): Int
        fun FcPatternCreate(): Pointer?
        fun FcPatternDestroy(p: Pointer?)
        fun FcObjectSetBuild(object0: String, vararg rest: String?): Pointer?
        fun FcObjectSetDestroy(os: Pointer?)
        fun FcFontList(config: Pointer?, p: Pointer?, os: Pointer?): Pointer?
        fun FcFontSetDestroy(fs: Pointer?)
        fun FcPatternGetString(p: Pointer, obj: String, n: Int, out: PointerByReference): Int
        fun FcPatternGetInteger(p: Pointer, obj: String, n: Int, out: IntByReference): Int
    }

    /** `FcFontSet { int nfont; int sfont; FcPattern **fonts; }`：两字段后的指针在 32/64 位下都在偏移 8。 */
    private const val FONTSET_NFONT = 0L
    private const val FONTSET_FONTS = 8L

    /** `FcPatternGetString` 的单对象值上限（family 一般 2–4 条；防御性截断）。 */
    private const val MAX_VALUES = 16

    /** 读不到 `weight` 时的哨兵：fontconfig 字重从 0（Thin）起，负数不可能是真值。 */
    internal const val WEIGHT_UNKNOWN = -1

    /** 读不到 `width` 时的哨兵（fontconfig 宽度从 50 起）。 */
    internal const val WIDTH_UNKNOWN = -1

    /** fontconfig 的「正常宽度」（`FC_WIDTH_NORMAL`）。 */
    private const val WIDTH_NORMAL = 100

    /** 名字说的字重与字体自己声明的字重相差多少就算「对不上」：一个档位差 20，取 10 卡在档位之间。 */
    private const val WEIGHT_TOLERANCE = 10

    /** 族名尾词（整词）→ fontconfig 字重刻度（`FC_WEIGHT_*`，稳定 ABI）+ 规范写法。 */
    private data class WeightWord(val suffix: String, val weight: Int, val label: String)

    private val WEIGHT_WORDS = listOf(
        WeightWord("extralight", 40, "ExtraLight"),
        WeightWord("ultralight", 40, "UltraLight"),
        WeightWord("extrabold", 205, "ExtraBold"),
        WeightWord("ultrabold", 205, "UltraBold"),
        WeightWord("semibold", 180, "SemiBold"),
        WeightWord("demibold", 180, "DemiBold"),
        WeightWord("semilight", 55, "SemiLight"),
        WeightWord("demilight", 55, "DemiLight"),
        WeightWord("regular", 80, "Regular"),
        WeightWord("medium", 100, "Medium"),
        WeightWord("black", 210, "Black"),
        WeightWord("heavy", 210, "Heavy"),
        WeightWord("light", 50, "Light"),
        WeightWord("thin", 0, "Thin"),
        // 上游把 `SemiBold` 截成了一个词（Noto Sans Sora Sompeng 的 nameID1 就写作「… Semi」，
        // 而它的 PostScript 名仍是 `…Semi-Bold`）。
        WeightWord("semi", 180, "SemiBold"),
    )

    /** 一次 2000 个面的枚举在普通桌面机上 ~10ms；进程内字体集合不变，缓存一次足够。 */
    @Volatile
    private var cached: Folded? = null

    private val lock = Any()

    private val isLinux: Boolean =
        System.getProperty("os.name")?.contains("Linux", ignoreCase = true) == true

    private val fc: Fc? by lazy {
        if (!isLinux) null else runCatching { Native.load("fontconfig", Fc::class.java) }.getOrNull()
    }

    /** fontconfig 可用（Linux 且库能加载）；否 = 宿主该走 skiko 枚举。 */
    fun available(): Boolean = fc != null

    /** 全量折叠结果（进程内缓存；字体集合在进程生命周期内视为不变，与扫描指纹口径一致）。 */
    internal fun folded(): Folded? {
        cached?.let { return it }
        val lib = fc ?: return null
        return synchronized(lock) {
            cached ?: runCatching { fold(rawFaces(lib)) }
                .onFailure { it.printStackTrace() }
                .getOrNull()
                ?.takeIf { it.families.isNotEmpty() }
                ?.also { cached = it }
        }
    }

    /** 基族名列表（`SystemFontSource.families`）。 */
    fun familyNames(): List<String> = folded()?.families ?: emptyList()

    /** 单族字重名列表（`SystemFontSource.facesFor`）。 */
    fun facesFor(family: String): List<SystemFontFace> = folded()?.faces?.get(family) ?: emptyList()

    /** 本地化（中文）展示名（喂 `SystemFontScanner.localizedNames`，替代 name 表直读）。 */
    fun displayNames(families: Collection<String>): Map<String, String> {
        val f = folded() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (family in families) f.display[family]?.let { out[family] = it }
        return out
    }

    // ---- 折叠（纯逻辑，可单测）----

    /**
     * 别名族折叠：fontconfig 的 `family` 值里，基族名会出现在该族**每个字重**上，
     * 而「基名 + 字重」这类别名只挂在对应那一个面上。故每面取**出现次数最多**的族名值作基名
     * （并列取字体面自带顺序更靠前的那个——fontconfig 已按系统语言排过，中文系统上中文名在前），
     * 别名族由此自动归并，且不依赖系统语言。
     *
     * 展示名同理取「其余 CJK 族名值里出现次数最多的那个」，即基名的本地化写法，
     * 避免把「思源宋体 CN Medium」这种带字重的别名当展示名。
     *
     * 「出现次数」有一个覆盖不到的死角：上游打包时把字重并进了 nameID1、又丢了 nameID16/17，
     * 那张面上**根本不出现基族名**，取众数也无从折起（见 [brokenWeightFamily]）。
     *
     * 众数定完还要看宽度轴：这一面的样式名里写着宽度词（`Condensed` 之类）、它自己声明的宽度
     * 也确实不是正常值、且枚举里**确有一族**叫「基族名 + 宽度词」时，就归到那一族（见 [widthFamily]）
     * ——否则同一套 Condensed 会被劈成两处（基族里多出假档、窄体族缺档）。
     */
    internal fun fold(raw: List<Face>): Folded {
        if (raw.isEmpty()) return Folded(emptyList(), emptyMap(), emptyMap())
        val counts = HashMap<String, Int>(raw.size * 2)
        for (f in raw) for (name in f.families) counts[name] = (counts[name] ?: 0) + 1

        val styles = LinkedHashMap<String, LinkedHashSet<String>>()
        val display = LinkedHashMap<String, String>()
        for (f in raw) {
            val names = f.families.filter { it.isNotBlank() }
            if (names.isEmpty()) continue
            var canonical = names.first()
            for (name in names) {
                if ((counts[name] ?: 0) > (counts[canonical] ?: 0)) canonical = name
            }
            canonical = widthFamily(canonical, f.style, f.width, counts)
            var style = f.style.trim()
            val repaired = brokenWeightFamily(canonical, f.weight, counts)
            if (repaired != null) {
                canonical = repaired.first
                style = repaired.second
            }
            style = stripOwnWidth(canonical, style)
            styles.getOrPut(canonical) { LinkedHashSet() }.add(style)
            // 展示名只在基名本身不含 CJK 时才需要（基名已是中文名就不必再套一层）。
            if (!containsCjk(canonical)) {
                if (canonical !in display) {
                    val localized = names
                        .filter { it != canonical && containsCjk(it) }
                        .maxByOrNull { counts[it] ?: 0 }
                    if (localized != null) display[canonical] = localized
                }
            }
        }
        val faces = LinkedHashMap<String, List<SystemFontFace>>(styles.size)
        for ((family, set) in styles) faces[family] = set.map { SystemFontFace(family, it) }
        return Folded(styles.keys.sorted(), faces, display)
    }

    /**
     * 宽度归族：把「宽度面漏在基族里」的字体面归回它自己的宽度族。
     *
     * 为什么需要：宽度是与字重正交的第二根轴，而本仓选字只发族名 + 数字字重（宽度恒为 normal），
     * 宽度变体只有「自己就是一族」才选得到。上游 Noto 的打包口径不统一：
     *  - `Noto Sans Gurmukhi Condensed` 等把宽度写进排版族名（nameID16）⇒ fontconfig 报的就是独立族，天然可用；
     *  - `Noto Sans Arabic` 那批把宽度写进样式（nameID17）⇒ 报告里只有缩写别名，拼不出可用族名，保持原样；
     *  - `Noto Sans Sinhala Condensed` 这批**半拉子**：9 个宽度面里有 3 个（Regular/Bold/Black）的
     *    nameID16 还写着基族名，fontconfig 于是把它们报成「基族 + 别名」，按众数会漏进基族，
     *    结果同一套 Condensed 被劈成两处（基族里多出 `Condensed`/`Thin Condensed` 假档，Condensed 族缺档）。
     *
     * 宽度词**只从名字里取**（这一面 `style` 里写的那个），不从宽度刻度反推：同一批 Sinhala Condensed
     * 文件的 `usWidthClass` 写的是 2（按 OS/2 规范那是 ExtraCondensed），名字却都叫 Condensed——
     * 按刻度造名字会拼出 `… ExtraCondensed` 把同一套字体再劈一次。刻度只当守卫（必须确实不是正常宽度）。
     *
     * 判据只认枚举里真实存在的族名：拼出来的名字若不存在——比如 `Noto Sans Arabic Condensed`
     * 在 fontconfig 里根本没有——就原地不动。造名字是危险的：不存在的族名会被 fontconfig 静默
     * 匹配成**别的字体**（实测落到思源黑体）。
     */
    private fun widthFamily(family: String, style: String, width: Int, counts: Map<String, Int>): String {
        if (width == WIDTH_UNKNOWN || width == WIDTH_NORMAL) return family
        val word = SubfamilyMetric.width(style) ?: return family
        // 族名自己已带宽度词 ⇒ 它就在宽度族里，别再叠一层。
        if (SubfamilyMetric.width(family) != null) return family
        val candidate = "$family $word"
        return if (counts[candidate] != null) candidate else family
    }

    /**
     * 族名已经是那一族（`… Condensed`）时，字重名里重复的同一个宽度词是冗余的：
     * `Noto Sans Condensed` 族里的 `Condensed Bold` 只要 `Bold`，光写 `Condensed` 的那一面
     * 就是常规档（留成空串会被「旧世代空字重残留行」的清理当成过期行删掉，反而丢了这一档）。
     *
     * 只剥**与族名宽度一致**的那个词：宽度对不上（如普通族里的 `Condensed Bold`）不是冗余，
     * 那是「别族混进来的档」，留给调用方判断能不能用（见 `FontLibraryPanel.weightChoices`）。
     */
    private fun stripOwnWidth(family: String, style: String): String {
        val own = SubfamilyMetric.width(family) ?: return style
        if (SubfamilyMetric.width(style) != own) return style
        return SubfamilyMetric.withoutWidth(style).ifBlank { "Regular" }
    }

    /**
     * 上游打包 bug 的兜底：族名以字重词结尾、基族名在本次枚举里存在、而**这个面自己声明的字重
     * 与名字里那个字重对不上**时，认定名字里的字重是打包残留，把该面折回基族，字重名取名字里那个词。
     *
     * 三条同时成立才动手，缺一条就返回 null（保持原样）：
     *  1. 尾词是 [WEIGHT_WORDS] 里的字重词（整词比对，不是前缀）——**绝不能剥宽度词**
     *     （`… Condensed` 是独立族）：这里只剥最后一个词，宽度词永远不会被当成字重词剥掉
     *     （`… Condensed Black` 剥的是 Black，剩余基名仍带 Condensed）；
     *  2. 去掉尾词后的基族名在本次枚举里出现过（不敢凭空造族）；
     *  3. 名字说的字重与它自己声明的字重差出一个档位以上（[WEIGHT_TOLERANCE]）。
     *     这条是防误吞的关键：`Arial Black` 这类「族名带字重词、自己就是那个字重」的正经独立族
     *     名字与自身自洽，不受影响；只有「名字说 600、自己说 400」这种自相矛盾的面才折。
     *
     * 真实现场（Noto 三款小语种字体）：nameID1 写成「Noto Sans Gunjala Gondi Semibold」、
     * nameID2 却写成 Regular、nameID16/17 缺失、OS/2 仍是 400 ⇒ fontconfig 报出的族名与字重自相矛盾。
     *
     * @return 基族名 to 折叠后该面的字重名（用名字里的说法，避免与真 Regular 撞档）。
     */
    private fun brokenWeightFamily(
        family: String,
        ownWeight: Int,
        counts: Map<String, Int>,
    ): Pair<String, String>? {
        if (ownWeight == WEIGHT_UNKNOWN) return null
        val cut = family.lastIndexOf(' ')
        if (cut <= 0) return null
        val tail = family.substring(cut + 1).lowercase()
        val word = WEIGHT_WORDS.firstOrNull { it.suffix == tail } ?: return null
        val base = family.substring(0, cut).trim()
        if (base.isEmpty() || counts[base] == null) return null
        // 名字与自身一致 ⇒ 这是正经族名（Arial Black / DejaVu Sans Light），不折。
        if (abs(word.weight - ownWeight) <= WEIGHT_TOLERANCE) return null
        return base to word.label
    }

    private fun containsCjk(s: String): Boolean = s.any { it.code in 0x4E00..0x9FFF }

    // ---- fontconfig 枚举 ----

    private fun rawFaces(lib: Fc): List<Face> {
        lib.FcInit()
        val objects = lib.FcObjectSetBuild("family", "style", "weight", "width", null) ?: return emptyList()
        // 空模式（不是 null 指针）= 全库：fontconfig 只认「空模式匹配一切」，
        // 传 null 会得到空集（fc-list 不带参数走的就是空模式这条路）。
        val pattern = lib.FcPatternCreate() ?: return emptyList()
        try {
            // 对象集限定只要 family/style（模式里其它字段一律不取）。
            val set = lib.FcFontList(null, pattern, objects) ?: return emptyList()
            try {
                val n = set.getInt(FONTSET_NFONT)
                val array = if (n <= 0) null else set.getPointer(FONTSET_FONTS)
                if (array == null) return emptyList()
                val patterns = array.getPointerArray(0, n)
                val out = ArrayList<Face>(n)
                for (p in patterns) {
                    if (p == null) continue
                    val families = valuesOf(lib, p, "family")
                    if (families.isEmpty()) continue
                    out.add(
                        Face(
                            families = families,
                            style = valuesOf(lib, p, "style").firstOrNull().orEmpty(),
                            weight = intOf(lib, p, "weight", WEIGHT_UNKNOWN),
                            width = intOf(lib, p, "width", WIDTH_UNKNOWN),
                        ),
                    )
                }
                return out
            } finally {
                lib.FcFontSetDestroy(set)
            }
        } finally {
            lib.FcPatternDestroy(pattern)
            lib.FcObjectSetDestroy(objects)
        }
    }

    /** 读一个对象的多值（family 有多个本地化/别名；style 通常 1–2 个）。 */
    private fun valuesOf(lib: Fc, pattern: Pointer, obj: String): List<String> {
        val out = ArrayList<String>(2)
        for (i in 0 until MAX_VALUES) {
            val ref = PointerByReference()
            if (lib.FcPatternGetString(pattern, obj, i, ref) != 0) break
            val value = ref.value?.getString(0, Charsets.UTF_8.name()) ?: break
            value.trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out
    }

    /** 读一个整数对象（`weight` / `width`；fontconfig 每个面都有），读不到按 [fallback]。 */
    private fun intOf(lib: Fc, pattern: Pointer, obj: String, fallback: Int): Int {
        val ref = IntByReference(fallback)
        return if (lib.FcPatternGetInteger(pattern, obj, 0, ref) == 0) ref.value else fallback
    }
}
