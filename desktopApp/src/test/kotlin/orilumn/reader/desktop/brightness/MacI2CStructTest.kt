package orilumn.reader.desktop.brightness

import com.sun.jna.Memory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JNA 结构体必须与 Apple `IOI2CRequest`（pack(4) LP64）逐字节对齐：
 * 首地址 8、回地址 12、回子地址 17、总尺寸 124。漂移即内核拒收。
 */
class MacI2CStructTest {

    @Test
    fun `request size is 124`() {
        assertEquals(124, MacDisplayBrightness.IOI2CRequest().size())
    }

    @Test
    fun `key field offsets match SDK header`() {
        val r = MacDisplayBrightness.IOI2CRequest()
        r.sendAddress = 0x12345678
        r.replyAddress = 0x11223344
        r.replySubAddress = 0x55
        r.sendBytes = 7
        r.replyBytes = 11
        r.sendBuffer = Memory(8)
        r.write()
        val p = r.pointer
        assertEquals(0x12345678, p.getInt(8))
        assertEquals(0x11223344, p.getInt(12))
        assertEquals(0x55.toByte(), p.getByte(17))
        assertEquals(7, p.getInt(40))
        assertEquals(11, p.getInt(56))
        assertEquals(r.sendBuffer, p.getPointer(68))
    }
}
