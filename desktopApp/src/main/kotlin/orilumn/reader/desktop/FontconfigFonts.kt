package orilumn.reader.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import orilumn.reader.data.font.SystemFontFace

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

    /** fontconfig 的一个字体面（一个文件的一个 face）：族名候选 + 该面的风格名。 */
    internal data class Face(val families: List<String>, val style: String)

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
    }

    /** `FcFontSet { int nfont; int sfont; FcPattern **fonts; }`：两字段后的指针在 32/64 位下都在偏移 8。 */
    private const val FONTSET_NFONT = 0L
    private const val FONTSET_FONTS = 8L

    /** `FcPatternGetString` 的单对象值上限（family 一般 2–4 条；防御性截断）。 */
    private const val MAX_VALUES = 16

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
            styles.getOrPut(canonical) { LinkedHashSet() }.add(f.style.trim())
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

    private fun containsCjk(s: String): Boolean = s.any { it.code in 0x4E00..0x9FFF }

    // ---- fontconfig 枚举 ----

    private fun rawFaces(lib: Fc): List<Face> {
        lib.FcInit()
        val objects = lib.FcObjectSetBuild("family", "style", null) ?: return emptyList()
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
                    out.add(Face(families, valuesOf(lib, p, "style").firstOrNull().orEmpty()))
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
}
