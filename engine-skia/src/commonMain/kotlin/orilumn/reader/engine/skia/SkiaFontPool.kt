package orilumn.reader.engine.skia

import org.jetbrains.skia.paragraph.FontCollection

/**
 * 用户字库的进程共用 Skia 字体集合（G 阶段铁律的落实点）。
 *
 * 断行（[SkiaParagraphBreaker]）与绘制（[LineWindowDrawer]）的默认集合都取自这里，
 * 保证量与画永远是**同一实例**：用户导入字体只改变字形度量（换行位置会动），两边用不同
 * 集合即错位。空字库时退化为 [SkParagraphFactory.defaultCollection]，行为与此前一致。
 *
 * 线程：宿主在 IO 线程 [setEmbedded]（开书前 + 字库增删后），绘制/排版线程只读 [current]。
 * 集合切换后宿主负责重排（新度量 → 新断行），本对象只管“同一实例”。
 */
object SkiaFontPool {

    /** 一份要装入共用池的字体二进制：族名 + 别名（展示名）都注册，槽位存哪个名池里就认哪个名。 */
    data class EmbeddedFont(
        val familyName: String,
        val bytes: ByteArray,
        val aliases: List<String> = emptyList(),
        /** ttc/otc 集合内的面序号（0 为第一个面）：宿主补装系统 CJK 衬线 ttc 时用它指定 SC/TC 面。 */
        val faceIndex: Int = 0,
    ) {
        companion object {
            /**
             * 按“族名 + 展示名双注册”装配条目（展示名与族名相同时不重复）：级联槽位存哪个名，
             * 池里就得认哪个名——该对应策略归引擎，不在各宿主重复。
             */
            fun forFace(familyName: String, displayName: String, bytes: ByteArray): EmbeddedFont =
                EmbeddedFont(
                    familyName = familyName,
                    bytes = bytes,
                    aliases = listOfNotNull(
                        displayName.takeIf { it.isNotBlank() && it != familyName },
                    ),
                )
        }
    }

    @Volatile
    private var fonts: List<EmbeddedFont> = emptyList()

    @Volatile
    private var cached: FontCollection? = null

    /** 当前共用集合（断行/绘制默认参数都取它）。 */
    fun current(): FontCollection =
        cached ?: synchronized(this) {
            cached ?: build().also { cached = it }
        }

    /**
     * 刷新内嵌字库（用户导入/删除后调用）。集合变化才重建；无字库回退系统默认。
     * @return 集合是否发生变化（宿主据此决定是否重排）。
     */
    fun setEmbedded(fonts: List<EmbeddedFont>): Boolean {
        // 签名含字节量：同名重导新文件也视为变化（只比名会漏）。
        val sig = fonts.map { Triple(it.familyName, it.aliases.sorted(), it.bytes.size) }
        val cur = this.fonts.map { Triple(it.familyName, it.aliases.sorted(), it.bytes.size) }
        if (sig == cur) return false
        synchronized(this) {
            this.fonts = fonts.toList()
            cached = null
        }
        return true
    }

    private fun build(): FontCollection {
        val list = fonts
        return if (list.isEmpty()) SkParagraphFactory.defaultCollection()
        else SkParagraphFactory.embeddedFontCollection(list)
    }
}
