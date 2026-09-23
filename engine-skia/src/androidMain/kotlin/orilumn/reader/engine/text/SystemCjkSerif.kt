package orilumn.reader.engine.text

import android.util.Log
import orilumn.reader.engine.skia.SkiaFontPool
import java.io.File

/**
 * 宿主侧「系统宋体补装」：把设备自带的 CJK 衬线字体以浏览器内核通用族映射里的真名
 * （`Noto Serif CJK SC` 等，见 engine-skia [SkParagraphFactory] 的 serif 候选表）装进
 * 与断行/绘制共用的同一 Skia 池，让传统模式 `serif`（内核映射纯走候选链）的中文落到宋体。
 *
 * 背景：skiko-Android 的 FontMgr 只把系统的罗马面注册成独立族，中文衬线
 * （`/system/fonts/NotoSerifCJK-Regular.ttc`）不在族表里（vivo PA2353 实测 `Noto Serif CJK *`
 * 全部 match 不到），内核映射里已列出的宋体名无可栅格化字体；旧 StaticLayout 管线经
 * FontPairing.SYSTEM 的 Minikin 链能落到宋体。这里把系统的宋体 ttc 读出来装进共用池，
 * 内核映射立即命中——未注册的候选由 FontCollection 按字形跳过、桌面等平台行为不变。
 *
 * 取 ttc 的 SC 面（简体书主流，全码点覆盖，繁简差异仅在少量异体字）；字节进程内缓存
 * 只读一次（~24MB），宿主在每个 reader 打开时并入字体池。
 */
object SystemCjkSerif {

    private val TAG = "Orilumn.SystemCjkSerif"

    /** 各 OEM 放置中文衬线字体的路径（按宋体优先排列；命中 Noto CJK ttc 时取 SC 面）。 */
    private const val NOTO_CJK_TTC = "/system/fonts/NotoSerifCJK-Regular.ttc"
    private val candidatePaths = listOf(
        NOTO_CJK_TTC,                                        // Android AOSP / vivo 等
        "/system/fonts/SourceHanSerifSC-Regular.otf",        // 部分国产 ROM
        "/system/fonts/DroidSerifFallback.ttf",              // 老机型仿宋
        "/system/fonts/DroidSansFallback.ttf",               // 兜底：至少保证不是空（黑体观感）
    )

    /** 内核 serif 候选表里的真名（resolveFamiliesFor 映射单源，这里只认它列出的名字）。 */
    private const val NOTO_SERIF_CJK_SC = "Noto Serif CJK SC"

    @Volatile
    private var cached: SkiaFontPool.EmbeddedFont? = null

    /** 首次调用读文件并缓存（进程级）；解析不了返回 null（保持既有黑体兜底）。 */
    fun entry(): SkiaFontPool.EmbeddedFont? =
        cached ?: synchronized(this) {
            cached ?: load().also { cached = it }
        }

    private fun load(): SkiaFontPool.EmbeddedFont? {
        val file = candidatePaths.map(::File).firstOrNull { it.isFile } ?: return null
        val bytes = runCatching { file.readBytes() }.getOrElse {
            Log.w(TAG, "read ${file.path} failed: $it")
            return null
        }
        val isNotoCjk = file.path == NOTO_CJK_TTC
        Log.w(TAG, "loaded system CJK serif ${file.path} ${bytes.size / 1048576}MB family=$NOTO_SERIF_CJK_SC")
        return SkiaFontPool.EmbeddedFont(
            familyName = NOTO_SERIF_CJK_SC,
            bytes = bytes,
            faceIndex = if (isNotoCjk) 2 else 0, // Noto Serif CJK ttc 面序: 0 JP, 1 KR, 2 SC, 3 TC
        )
    }
}