package orilumn.reader.desktop.brightness

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DDC 组帧/解析回归：向量全部来自真机实测（ViewSonic VP2780）。 */
class DdcPacketsTest {

    private fun hex(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `get brightness request framing`() {
        assertArrayEquals(hex(0x51, 0x82, 0x01, 0x10, 0xAC), DdcPackets.buildGet(0x10))
    }

    @Test
    fun `set brightness request framing`() {
        // 25 → 00 19；CHK = 6E^51^84^03^10^00^19
        var s = 0x6E xor 0x51 xor 0x84 xor 0x03 xor 0x10 xor 0x00 xor 0x19
        assertArrayEquals(hex(0x51, 0x84, 0x03, 0x10, 0x00, 0x19, s), DdcPackets.buildSet(0x10, 25))
    }

    @Test
    fun `parse real get reply cur 7 max 100`() {
        val r = DdcPackets.parseGetReply(
            hex(0x6E, 0x88, 0x02, 0x00, 0x10, 0x00, 0x00, 0x64, 0x00, 0x07, 0xC7, 0x00), 0x10,
        )
        assertEquals(7, r?.current)
        assertEquals(100, r?.max)
    }

    @Test
    fun `parse real get reply after set 25`() {
        val r = DdcPackets.parseGetReply(
            hex(0x6E, 0x88, 0x02, 0x00, 0x10, 0x00, 0x00, 0x64, 0x00, 0x19, 0xD9, 0x00), 0x10,
        )
        assertEquals(0x19, r?.current)
        assertEquals(100, r?.max)
    }

    @Test
    fun `reject tampered reply`() {
        assertNull(
            DdcPackets.parseGetReply(
                hex(0x6E, 0x88, 0x02, 0x00, 0x10, 0x00, 0x00, 0x64, 0x00, 0x08, 0xC7, 0x00), 0x10,
            ),
        )
        assertNull(DdcPackets.parseGetReply(hex(0x6E, 0x88), 0x10))
        // 操作码回显不符
        assertNull(
            DdcPackets.parseGetReply(
                hex(0x6E, 0x88, 0x02, 0x00, 0x12, 0x00, 0x00, 0x64, 0x00, 0x07, 0xC7, 0x00), 0x10,
            ),
        )
    }

    @Test
    fun `set clamps to 16 bit`() {
        val lo = DdcPackets.buildSet(0x10, -5)
        assertEquals(0, lo[4].toInt() and 0xFF)
        assertEquals(0, lo[5].toInt() and 0xFF)
    }
}
