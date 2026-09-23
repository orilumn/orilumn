package orilumn.reader.desktop

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * 桌面（macOS）CoreText 本地化族名桥——中文名方案A。
 *
 * 「取字体内置本地化中文名」走本地化名表等价层：`CTFontCopyDisplayName`
 * （cogist/PyObjC 同款调用，JVM 对应物是 JNA 直调 CoreText）。它返回系统语言下的
 * 本地化显示名（中文系统下 PingFang SC →「苹方-简」、Songti SC →「宋体-简 常规体」），
 * 受系统语言影响是方案A的固有语义——故只收**含 CJK**的结果：
 * 中文系统直出真名，英文系统/非 CJK 字体不产出（展示层回退 name 表直读或族名本身；
 * 字典已不再作为兜底）。
 *
 * 展示名若带字重后缀（「宋体-简 常规体」/「冬青黑体简体中文 W3」），按白名单剥离尾部
 * token 取家族名本体，避免与面板副标题的字重表重复（副标题另有平台枚举子族名）。
 * 族名（逻辑键）永远不变，本地化名只落在展示层。
 *
 * TODO(方案B·备忘)：将来断行引擎改 Rust 重写时，顺带改为直接解析 OpenType name 表
 * zh 记录（此处或 engine-skia `systemFontFaces()` 附近），彻底消除对
 * 系统语言的依赖；半路上以本桥（含 CJK 才用）+ name 表直读维持双链。
 * 详见 `docs/TODO-未尽事宜.md`。
 */
object MacFamilyNames {
    private val isMac: Boolean =
        System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true

    /** 测试门控用：当前进程是否 macOS（CoreText 桥只在 mac 上有意义）。 */
    internal val isMacAvailable: Boolean get() = isMac

    private val cf: NativeLibrary by lazy { NativeLibrary.getInstance("CoreFoundation") }
    private val ct: NativeLibrary by lazy { NativeLibrary.getInstance("CoreText") }

    private const val kCFStringEncodingUTF8 = 0x08000100

    /** 白名单字重/风格 token：展示名尾部命中即剥离（拉丁小写 + 常见中文权重词）。 */
    private val styleToken = setOf(
        "regular", "light", "thin", "medium", "semibold", "bold", "black",
        "extralight", "ultralight", "extrabold", "ultrabold", "book", "demi", "heavy",
        "常规体", "细体", "中等", "半粗", "粗体", "特黑", "特粗", "中黑", "超细",
        "纤黑", "中黑体",
    )

    // 冬青系编号权重：W3/W6 等。
    private val weightPattern = Regex("w[0-9]{1,2}")

    // CJK 头部后粘连的无空格拉丁字重尾巴（个别字体 name 表把字重粘在中文名后，
    // 如「寒蝉端黑宋Regular」→「寒蝉端黑宋」）；只在尾巴是纯 [A-Za-z0-9] 且头部以 CJK 结尾时剥。
    private val gluedLatinTail = Regex("(.*[\\u4E00-\\u9FFF])([A-Za-z0-9]{1,}$)")

    /**
     * 族 -> 本地化中文名（仅含 CJK 结果；非 mac / 调用失败返回空表）。
     * [families] 即 `systemFontFaces()` 的族名（逻辑键，取字形/槽位仍用它），
     * 返回值由调用方喂 [orilumn.reader.data.font.FontLibrary.syncSystemFaces] 落 displayName 列。
     */
    fun localizedFamilyNames(families: Collection<String>): Map<String, String> {
        if (!isMac || families.isEmpty()) return emptyMap()
        return runCatching {
            val out = LinkedHashMap<String, String>()
            for (family in families) {
                if (family.isBlank()) continue
                val raw = runCatching { displayNameOf(family) }.getOrNull() ?: continue
                val stripped = stripStyleSuffix(raw).trim()
                if (stripped.isNotEmpty() && containsCjk(stripped) && stripped != family) {
                    out[family] = stripped
                }
            }
            out
        }.getOrDefault(emptyMap())
    }

    /**
     * 剥离尾部字重/风格 token，两段式（均只在涉及 CJK 时动手，防误伤英文族名）：
     * 1) 空格分隔尾巴命中白名单（「宋体-简 常规体」→「宋体-简」、「兰亭黑-简 纤黑」→「兰亭黑-简」、
     *    「冬青黑体简体中文 W3」→「冬青黑体简体中文」）；
     * 2) CJK 后粘连的无空格拉丁尾巴（个别字体 name 表把字重粘在中文名后，
     *    「寒蝉端黑宋Regular」→「寒蝉端黑宋」；「CJK字型ABC」这类真实含数的名字也会被归一，
     *    属可接受代价——栅栏通常不会把拉丁数字写进中文族名）。
     */
    internal fun stripStyleSuffix(name: String): String {
        val stripped = run {
            val idx = name.indexOf(' ')
            if (idx < 0) return@run name
            val head = name.substring(0, idx).trim()
            val tail = name.substring(idx + 1).trim()
            if (head.isEmpty() || tail.isEmpty() || !containsCjk(head) || tail.length > 12) name
            else {
                val norm = tail.lowercase()
                if (norm in styleToken || weightPattern.matches(norm)) head else name
            }
        }
        if (!containsCjk(stripped)) return stripped
        val m = gluedLatinTail.find(stripped.trim())
        return if (m != null) m.groupValues[1].trim() else stripped.trim()
    }

    internal fun containsCjk(s: String): Boolean = s.any { it.code in 0x4E00..0x9FFF }

    // ---- JNA/CoreText 原语（JNA 5.x `Function.invoke` 返回裸 Object，需显式转型）----

    private fun cfString(s: String): Pointer? = runCatching {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val buf = Memory(bytes.size.toLong() + 1)
        buf.write(0, bytes, 0, bytes.size)
        buf.setByte(bytes.size.toLong(), 0)
        cf.getFunction("CFStringCreateWithCString").invoke(
            Pointer::class.java, arrayOf<Any?>(null, buf, kCFStringEncodingUTF8),
        ) as Pointer
    }.getOrNull()

    private fun fromCfString(cfStr: Pointer): String? = runCatching {
        val len = cf.getFunction("CFStringGetLength").invokeLong(arrayOf<Any?>(cfStr))
        val buf = Memory(maxOf(16L, len * 4L + 8))
        val ok = cf.getFunction("CFStringGetCString").invokeInt(
            arrayOf<Any?>(cfStr, buf, buf.size(), kCFStringEncodingUTF8),
        )
        if (ok != 0) buf.getString(0, Charsets.UTF_8.name()) else null
    }.getOrNull()

    private fun displayNameOf(family: String): String? {
        val key = cfString(family) ?: return null
        val font = runCatching {
            ct.getFunction("CTFontCreateWithName").invoke(
                Pointer::class.java, arrayOf<Any?>(key, 12.0, null),
            ) as Pointer
        }.getOrNull() ?: return null
        val disp = runCatching {
            ct.getFunction("CTFontCopyDisplayName").invoke(
                Pointer::class.java, arrayOf<Any?>(font),
            ) as Pointer
        }.getOrNull() ?: return null
        return fromCfString(disp)
    }
}