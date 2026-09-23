package orilumn.reader.data.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingLocatorCodecTest {

    @Test
    fun `encode 章节字符`() {
        assertEquals("3:128", ReadingLocatorCodec.encode(3, 128))
    }

    @Test
    fun `decode 新格式`() {
        assertEquals(3 to 128, ReadingLocatorCodec.decode("3:128"))
    }

    @Test
    fun `decode 缺char按0`() {
        assertEquals(3 to 0, ReadingLocatorCodec.decode("3"))
    }

    @Test
    fun `decode 兼容foliate JSON旧行`() {
        assertEquals(3 to 128, ReadingLocatorCodec.decode("{\"v\":1,\"chapter\":3,\"char\":128}"))
    }

    @Test
    fun `decode JSON缺键按0`() {
        assertEquals(0 to 0, ReadingLocatorCodec.decode("{\"chapter\":0}"))
    }

    @Test
    fun `decode 非法回null`() {
        assertNull(ReadingLocatorCodec.decode(null))
        assertNull(ReadingLocatorCodec.decode(""))
        assertNull(ReadingLocatorCodec.decode("abc"))
        assertNull(ReadingLocatorCodec.decode("{}"))
    }

    @Test
    fun `往返一致`() {
        val (ch, c) = ReadingLocatorCodec.decode(ReadingLocatorCodec.encode(5, 42))!!
        assertEquals(5, ch)
        assertEquals(42, c)
    }
}
