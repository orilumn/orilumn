package orilumn.reader.engine.skia

import orilumn.reader.data.font.FontFace
import orilumn.reader.engine.css.FontDemand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F4b 装池共享核单测（沿旧 `ReaderActivity.syncSkiaPool` 语义）：选择→签名→装配，
 * 无需求保书内，缺文件重试。需本机 TTF，缺席即跳过。
 */
class FontPoolSyncTest {

    private val ttf: File? = listOf(
        "/System/Library/Fonts/Supplemental/Arial.ttf",
        "/System/Library/Fonts/Supplemental/Times New Roman.ttf",
    ).map { File(it) }.firstOrNull { it.isFile }

    private fun importedFace(file: File) = FontFace(
        id = 7,
        familyName = "PoolSyncFam",
        displayName = "PoolSyncFam",
        subfamily = "Regular",
        source = FontFace.SOURCE_IMPORTED,
        path = file.absolutePath,
        lang = "latin",
    )

    @Test
    fun selectAssembleRoundTrip() {
        val file = ttf ?: return
        val face = importedFace(file)
        val sizes: (String) -> Long? = { p -> File(p).takeIf { it.isFile }?.length() }
        try {
            val sel = FontPoolSync.select(listOf(face), setOf("PoolSyncFam"), FontDemand.EMPTY, emptyList(), sizes)
            assertEquals(listOf(face), sel.selected)
            assertTrue(sel.sig.isNotBlank())

            val asm = FontPoolSync.assemble(sel.selected, { id -> if (id == face.id) file.readBytes() else null }, emptyList())
            assertEquals(0, asm.failed)
            assertEquals(1, asm.embedded.size)
            assertTrue(SkiaFontPool.setEmbedded(asm.embedded))
            // 同签名二次即命中（调用方比对后跳过，不重建）。
            assertTrue(!SkiaFontPool.setEmbedded(asm.embedded))
        } finally {
            SkiaFontPool.setEmbedded(emptyList())
        }
    }

    @Test
    fun emptyDemandKeepsBookEntries() {
        val book = SkiaFontPool.EmbeddedFont("BookFam", byteArrayOf(1, 2, 3))
        val sel = FontPoolSync.select(emptyList(), emptySet(), FontDemand.EMPTY, listOf(book)) { null }
        assertTrue(sel.selected.isEmpty())
        val asm = FontPoolSync.assemble(sel.selected, { null }, listOf(book))
        assertEquals(listOf(book), asm.embedded)
        assertEquals(0, asm.failed)
    }

    @Test
    fun missingFileRetries() {
        val face = importedFace(File("/nonexistent/x.ttf"))
        val sel = FontPoolSync.select(listOf(face), setOf("PoolSyncFam"), FontDemand.EMPTY, emptyList()) { null }
        assertTrue(sel.selected.isEmpty())
        // 缺文件不进签名：文件出现即签名变化（与有文件时的签名不同）。
        val file = ttf ?: return
        val sel2 = FontPoolSync.select(
            listOf(face.copy(path = file.absolutePath)), setOf("PoolSyncFam"), FontDemand.EMPTY, emptyList(),
        ) { p -> File(p).takeIf { it.isFile }?.length() }
        assertEquals(1, sel2.selected.size)
    }
}
