package orilumn.reader.engine.skia

import orilumn.reader.engine.css.TextAlign
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-b 验收（内嵌/woff2 例）：真实 woff2 入共用池后可按族名解析命中
 * （系统回退族不同），整形即用书内字体度量。
 */
class BookFontPoolTest {

    private fun woff2(): ByteArray =
        javaClass.getResourceAsStream("/fonts/roboto-latin.woff2")!!.readBytes()

    private fun familyOf(collection: FontCollection, family: String): String? {
        val found = collection.findTypefaces(arrayOf(family), FontStyle.NORMAL)
        try {
            return found.firstOrNull()?.familyName
        } finally {
            found.forEach { it?.close() }
        }
    }

    private fun widthOf(text: String, families: List<String>, collection: FontCollection): Float {
        val style = SkParagraphFactory.paragraphStyle(
            TextAlign.LEFT, 16f, 1.5f, "p", families, 400, false, false, 0f,
        )
        val p = ParagraphBuilder(style, collection).addText(text).build()
        return try {
            p.layout(Float.MAX_VALUE)
            p.maxIntrinsicWidth
        } finally {
            p.close()
        }
    }

    @Test
    fun bookFontResolvesAndShapesWithItsOwnMetrics() {
        val bytes = woff2()
        assertEquals("woff2", orilumn.reader.engine.css.sniffFontFormat(bytes))
        // 平台能力门控：woff2 二进制 → Typeface 走 FontMgr.makeFromData，
        // Linux skiko 构建（fontconfig 后端）不解 woff2（TTF/TTC 可解，
        // 实测 makeFromData(woff2)=null）⇒ 内嵌 woff2 暂为 Linux 已知缺口，
        // 本测试在解不了的平台跳过（macOS CoreText 原生解 woff2，照常跑）。
        if (runCatching {
            systemFonts().makeFromData(Data.makeFromBytes(bytes), 0)
        }.getOrNull() == null) return
        try {
            assertTrue("pool must accept the book font", SkiaFontPool.setEmbedded(listOf(SkiaFontPool.EmbeddedFont("Roboto", bytes))))
            val pool = SkiaFontPool.current()
            // 池命中书内族；裸系统集合回退他族。
            assertEquals("Roboto", familyOf(pool, "Roboto"))
            assertNotEquals("Roboto", familyOf(SkParagraphFactory.defaultCollection(), "Roboto"))
            // 整形度量跟随书内字体（与系统回退不同宽）。
            val text = "Hello world, shaping with the embedded face."
            val bookW = widthOf(text, listOf("Roboto"), pool)
            val sysW = widthOf(text, listOf("Roboto"), SkParagraphFactory.defaultCollection())
            assertTrue("book width must be positive", bookW > 0f)
            assertNotEquals("book font must shape differently than system fallback", sysW, bookW)
        } finally {
            SkiaFontPool.setEmbedded(emptyList())
        }
    }
}
