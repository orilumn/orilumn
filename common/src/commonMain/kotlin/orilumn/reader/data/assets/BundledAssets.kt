package orilumn.reader.data.assets

/**
 * 随包发布的**纯文本资产**加载器（`common/src/commonMain/resources/text/`）。
 *
 * 与样式表加载器（`ReaderStylesheets`）同一套接缝：资源只留一份，各平台走 classloader，
 * 不再各自带副本。缺失返回 `null`（调用方决定是报错还是降级），不做内联兜底。
 *
 * 当前只有纠错词典（`corrections.txt`）走这里。
 */
object BundledAssets {

    /** 读 `text/<name>` 的 UTF-8 文本；找不到返回 `null`。 */
    fun text(name: String): String? = readBundledText(name)
}

/** 平台实现：从 classloader 读 `text/<name>`；找不到返回 `null`。 */
expect fun readBundledText(name: String): String?
