package orilumn.reader.desktop

import orilumn.reader.data.font.FontLibrary
import orilumn.reader.engine.AppTaskScheduler
import orilumn.reader.engine.SystemFontScanner

/**
 * 桌面**系统字体扫描单例**（进程级，与平板 `AndroidFontScan` 同位，方案 §5 分层）：
 * 全应用唯一 [SystemFontScanner]，共用应用级 [AppTaskScheduler.scheduler]。
 *
 * 本地化名链（与旧 `DesktopReaderHost.syncPanelFonts` 逐字节同义）：name 表直读
 * （方案B，平台无关）+ macOS CoreText（方案A）补缺；同键以 name 表为准（`plus` 右侧胜出）。
 *
 * 单例的理由：`detected`（启动只检测一次）与 `scannedFamilies`（抗抢占续跑的已完成族集）
 * 都必须**跨书架/阅读面切换保留**，否则每次回书架就重扫。
 */
object DesktopFontScan {

    @Volatile
    private var scanner: SystemFontScanner? = null

    fun scanner(library: FontLibrary): SystemFontScanner = scanner ?: synchronized(this) {
        scanner ?: SystemFontScanner(
            library = library,
            scheduler = AppTaskScheduler.scheduler,
            localizedNames = { families ->
                val nameTable = NameTableChineseNames.namesFor(families)
                val coreText = MacFamilyNames.localizedFamilyNames(families)
                coreText + nameTable // 同键以右侧（name 表）为准
            },
        ).also { scanner = it }
    }
}
