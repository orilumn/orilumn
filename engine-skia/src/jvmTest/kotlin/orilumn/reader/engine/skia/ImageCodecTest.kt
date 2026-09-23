package orilumn.reader.engine.skia

import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCodecTest {

    /** 生成一张纯色 PNG 作测试样本（不依赖仓库静态资源）。 */
    private fun pngBytes(w: Int, h: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawRect(
            Rect.makeWH(w.toFloat(), h.toFloat()),
            Paint().apply { color = Color.makeRGB(200, 30, 30) },
        )
        val data = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG, 100)!!
        return data.bytes
    }

    @Test
    fun probeBoundsReadsHeaderOnly() {
        assertEquals(64 to 48, ImageCodec.probeBounds(pngBytes(64, 48)))
    }

    @Test
    fun decodeMatchesEncodedSize() {
        val d = ImageCodec.decode(pngBytes(32, 24)) ?: error("decode failed")
        assertEquals(32, d.width)
        assertEquals(24, d.height)
        assertEquals(24, d.image.height)
    }

    @Test
    fun decodeScaledDownsamplesToExactWidth() {
        val d = ImageCodec.decodeScaled(pngBytes(128, 64), 32) ?: error("decode failed")
        assertEquals(32, d.width)
        assertEquals(16, d.height)
    }

    @Test
    fun decodeScaledUpscaleKeepsTargetWidth() {
        val d = ImageCodec.decodeScaled(pngBytes(8, 8), 24) ?: error("decode failed")
        assertEquals(24, d.width)
        assertEquals(24, d.height)
    }

    @Test
    fun decodeScaledZeroTargetIsFullDecode() {
        val d = ImageCodec.decodeScaled(pngBytes(16, 8), 0) ?: error("decode failed")
        assertEquals(16, d.width)
        assertEquals(8, d.height)
    }

    @Test
    fun decodeScaledExactWidthIsPassthrough() {
        val d = ImageCodec.decodeScaled(pngBytes(16, 8), 16) ?: error("decode failed")
        assertEquals(16, d.width)
        assertEquals(8, d.height)
    }

    @Test
    fun garbageBytesReturnNull() {
        assertNull(ImageCodec.probeBounds(ByteArray(8)))
        assertNull(ImageCodec.decode(byteArrayOf(0, 1, 2, 3, 4)))
        assertNull(ImageCodec.decodeScaled(ByteArray(4), 32))
    }

    @Test
    fun encodePngRoundTrips() {
        val bytes = pngBytes(48, 32)
        val decoded = ImageCodec.decode(bytes) ?: error("decode failed")
        val encoded = ImageCodec.encodePng(decoded.image) ?: error("encode failed")
        assertTrue(encoded.isNotEmpty())
        val reprobed = ImageCodec.probeBounds(encoded) ?: error("reprobe failed")
        assertEquals(48 to 32, reprobed)
    }
}