package orilumn.reader.host

import android.content.Context
import orilumn.reader.data.font.FontRepository
import orilumn.reader.engine.AppTaskScheduler
import orilumn.reader.engine.SystemFontScanner
import orilumn.reader.engine.SystemFontSource

/**
 * **Android 字体扫描单例**（进程级）：与桌面 `DesktopFontScan` 同位（方案 §5 分层）。
 *
 * 为什么必须应用级：`detected`（启动只检测一次，方案 §2.2）与 `scannedFamilies`
 * （抗抢占续跑的已完成族集，方案 §3.3）都要**跨 Activity 切换保留**——[MainActivity]
 * 书架与 [orilumn.reader.ui.reader.ReaderActivity] 阅读面各有生命周期，若各持一份
 * 扫描器，每次进出阅读页都会重扫一遍系统字体。
 *
 * 本地化名链（方案 §2.1 层次一③）：扫 `/system/fonts` 的 OpenType name 表，建
 * 「拉丁族名 → 中文族名」映射；进程内缓存一次，逐族查询走内存过滤（O(1) 摊还），
 * 不随每族重复落盘 IO。
 *
 * 与 [AndroidDb] 同口径用 `applicationContext`（单例不得持 Activity 引用）。
 */
object AndroidFontScan {

    private val lock = Any()

    @Volatile
    private var scanner: SystemFontScanner? = null

    @Volatile
    private var localizedNamesAll: Map<String, String>? = null

    /** 进程级扫描器（首次创建时打开共享字体库 + 缓存本地化名链）。 */
    fun scanner(context: Context): SystemFontScanner = scanner ?: synchronized(lock) {
        scanner ?: run {
            val app = context.applicationContext
            val repo = FontRepository(app, AndroidDb.library(app))
            SystemFontScanner(
                library = repo.fontLibrary,
                scheduler = AppTaskScheduler.scheduler,
                localizedNames = { families ->
                    val all = localizedNamesAll ?: synchronized(lock) {
                        localizedNamesAll ?: repo.systemFontLocalizedNames().also { localizedNamesAll = it }
                    }
                    val want = families.toSet()
                    all.filterKeys { it in want }
                },
                // §8-2 指纹加固：族名不变但 /system/fonts 文件被换（OTA/重打包）也判「变」。
                source = SystemFontSource(environmentHash = { repo.systemFontEnvironmentHash() }),
            ).also { scanner = it }
        }
    }
}
