package orilumn.reader.desktop.brightness

/**
 * DDC/CI 报文组帧/解析（纯逻辑，无平台依赖，可单测）。
 *
 * 口径经真机实测锁定（ViewSonic VP2780，雷电/DP）：
 * - 8 位总线地址：写 0x6E、读 0x6F、回包子地址 0x51；
 * - 读亮度请求 5 字节 `51 82 01 10 CHK`，回包 11 字节
 *   `6E 88 02 00 10 TYPE MAXH MAXL CURH CURL CHK`；
 * - 写亮度请求 7 字节 `51 84 03 10 HI LO CHK`；
 * - 校验和 = XOR(0x6E, 首部…数据)（发送方向），回包方向 = XOR(0x6F, 0x51, 回包[1..9])。
 */
object DdcPackets {
    /** VCP 操作码：亮度。 */
    const val OP_BRIGHTNESS = 0x10

    /** 发送方向校验和：XOR(0x6E, msg…)（msg = 源地址起全部字节，不含校验位本身）。 */
    fun sendChecksum(msg: ByteArray): Int {
        var s = 0x6E
        for (b in msg) s = s xor (b.toInt() and 0xFF)
        return s and 0xFF
    }

    /** 读 VCP 请求（5 字节）。 */
    fun buildGet(op: Int): ByteArray {
        val body = byteArrayOf(0x51, 0x82.toByte(), 0x01, op.toByte())
        return body + sendChecksum(body).toByte()
    }

    /** 写 VCP 请求（7 字节，16 位值大端）。 */
    fun buildSet(op: Int, value: Int): ByteArray {
        val v = value.coerceIn(0, 0xFFFF)
        val body = byteArrayOf(0x51, 0x84.toByte(), 0x03, op.toByte(), (v shr 8).toByte(), (v and 0xFF).toByte())
        return body + sendChecksum(body).toByte()
    }

    /** GetVCP 回包解析结果（当前值/最大值）。 */
    data class GetReply(val current: Int, val max: Int)

    /**
     * 解析 GetVCP 回包（期望 11 字节）。校验帧头（0x6E/0x02/操作码回显）与校验和，
     * 任一不符回 null（调用方重试或判不支持）。
     */
    fun parseGetReply(reply: ByteArray, op: Int): GetReply? {
        if (reply.size < 11) return null
        val b = IntArray(11) { reply[it].toInt() and 0xFF }
        if (b[0] != 0x6E || b[2] != 0x02 || b[4] != (op and 0xFF)) return null
        var chk = 0x6F xor 0x51
        for (i in 1..9) chk = chk xor b[i]
        if (chk != b[10]) return null
        return GetReply(current = (b[8] shl 8) or b[9], max = (b[6] shl 8) or b[7])
    }
}
