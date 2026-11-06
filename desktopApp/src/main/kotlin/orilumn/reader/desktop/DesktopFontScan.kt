package orilumn.reader.desktop

import orilumn.reader.data.font.FontLibrary
import orilumn.reader.engine.AppTaskScheduler
import orilumn.reader.engine.SystemFontScanner
import orilumn.reader.engine.SystemFontSource
import orilumn.reader.engine.skia.systemFontFamilyHashOf

/**
 * 桌面**系统字体扫描单例**（进程级，与平板 `AndroidFontScan` 同位，方案 §5 分层）：
 * 全应用唯一 [SystemFontScanner]，共用应用级 [AppTaskScheduler.scheduler]。
 *
 * 平台枚举端口二选一（[fontSource]）：
 *  - **Linux** → [FontconfigFonts]（直连 fontconfig）。原因是 skiko 的 `FontMgr.matchFamily`
 *    在 fontconfig 后端会把**全库**字体整份返回，而不是只返回本族字形；照原样读既会给每族挂上
 *    几百个与本体无关的字重名，也会把「族名 + 字重」的别名族（`思源宋体 CN Medium`、
 *    `方正悠宋+ GBK 509R`…）当成独立族。
 *  - **mac / Windows** → engine-skia 的 skiko 枚举（CoreText / DirectWrite 语义正确）。
 *
 * 本地化名链：Linux 直接用 fontconfig 自己带的本地化族名（`family` 多值里挑 CJK 那条，零文件读取）；
 * 其余平台沿用 name 表直读（方案B）+ macOS CoreText（方案A）补缺，同键以 name 表为准
 * （`plus` 右侧胜出）。
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
            localizedNames = ::localizedNames,
            source = fontSource(),
        ).also { scanner = it }
    }

    /** 平台枚举端口（见类文档）。`internal` 让测试复用**生产同一条**枚举接缝。 */
    internal fun fontSource(): SystemFontSource {
        // §8-2 指纹加固：族名不变但字体文件被换（重打包/系统更新）也判「变」。
        val environmentHash = { DesktopFontEnvironment.hash() }
        if (!FontconfigFonts.available()) return SystemFontSource(environmentHash = environmentHash)
        return SystemFontSource(
            families = FontconfigFonts::familyNames,
            facesFor = FontconfigFonts::facesFor,
            familyHash = { systemFontFamilyHashOf(FontconfigFonts.familyNames()) },
            familyHashOf = ::systemFontFamilyHashOf,
            environmentHash = environmentHash,
        )
    }

    /** 本地化名链（见类文档）。`internal` 让测试复用**生产同一条**本地化接缝。 */
    internal fun localizedNames(families: List<String>): Map<String, String> {
        if (FontconfigFonts.available()) return FontconfigFonts.displayNames(families)
        val nameTable = NameTableChineseNames.namesFor(families)
        val coreText = MacFamilyNames.localizedFamilyNames(families)
        return coreText + nameTable // 同键以右侧（name 表）为准
    }
}
