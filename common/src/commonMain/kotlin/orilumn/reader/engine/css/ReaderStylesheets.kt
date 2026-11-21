package orilumn.reader.engine.css

/**
 * 阅读器样式层**唯一单源**加载器（Z1，`docs/平台一致性整改方案.md` 第 1 节）。
 *
 * UA（`ua.css`）与排版主题（`traditional.css` / `modern.css`）的正文收进
 * `common/src/commonMain/resources/css/`，任何平台都不再自带副本（平板 `assets/css/` 与
 * 桌面内联常量为旧副本，Z2/Z3 删除并改为读取本加载器）。三份文件逐字节与旧平板 assets 一致；
 * `__LINK_COLOR__` token 由调用方按 [TypographicProfile] 主题感知链接色替换（`ua()`）。
 *
 * 缺失资源**抛异常**——不做内联兜底，防止再造第二份正文。
 */
object ReaderStylesheets {

    /** UA 样式表中的链接色占位符，下钻前由调用方按 profile 替换。 */
    const val LINK_COLOR_TOKEN = "__LINK_COLOR__"

    /** UA 基线样式文本（含 `__LINK_COLOR__` 占位符）。 */
    fun ua(): String = readRequired("ua.css")

    /**
     * 排版主题样式文本；原书设置（original）/未知主题返回 null（无主题样式表）。
     * traditional / modern 有样式表。
     */
    fun theme(layoutTheme: String): String? = when (layoutTheme) {
        "traditional" -> readRequired("traditional.css")
        "modern" -> readRequired("modern.css")
        else -> null
    }

    /**
     * 排版主题给**正文**声明的字族关键字（该主题 CSS 里 `body{font-family}` 那个词：
     * `serif` / `sans-serif`）。
     *
     * 只给**覆盖层**用：旁注（批注）盖在页面上、不进书的级联，读者没单独给批注挑字时
     * 要「采用正文字体」，就得知道主题表把正文声明成了什么。它**不参与任何层叠**，
     * 真正的声明唯一出处仍是主题 CSS（[theme]）；这里是那份声明的只读镜像，
     * `ReaderStylesheetsTest` 用解析器直接读 CSS 锁死两者一致 —— 改了 CSS 却忘了这里，测试会红。
     *
     * 读者在「衬线 / 无衬线（全局）」里挑过族时，关键字由 `TypographicProfile.genericFontAliases`
     * 换族（与正文同一条通道），这里只回答关键字本身。
     *
     * `null` = 原书设置 / 未知主题：正文由书的 CSS 决定，覆盖层无从得知，退回平台默认字体。
     */
    fun themeBodyFamily(layoutTheme: String): String? = when (layoutTheme) {
        "traditional" -> "serif"
        "modern" -> "sans-serif"
        else -> null
    }

    private fun readRequired(name: String): String =
        readReaderCss(name) ?: error("阅读器样式单源缺失: css/$name，请检查 common/src/commonMain/resources/css/")
}

/** 读 commonMain resources 的 `css/<name>`；找不到返回 null。凡是走 classloader 的平台共用一个实现。 */
expect fun readReaderCss(name: String): String?