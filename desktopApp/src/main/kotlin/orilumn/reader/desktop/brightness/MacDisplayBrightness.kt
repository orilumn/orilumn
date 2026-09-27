package orilumn.reader.desktop.brightness

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * macOS 真背光（DDC/CI，外接显示器）。
 *
 * 通路经真机实测锁定（`ddc-probe` 原型，ViewSonic VP2780 雷电/DP）：
 * `CGDisplayIOServicePort`（deprecated 但可用）→ `IOFBCopyI2CInterfaceForBus` →
 * `IOI2CInterfaceOpen` → `IOI2CSendRequest`。8 位地址（写 0x6E/读 0x6F/子地址 0x51）、
 * 回包 11 字节、组帧与校验见 [DdcPackets]。
 *
 * 全部调用同步阻塞（单次读约 100~200ms，含重试）：必须在 IO 线程调用，
 * 高频写入（滑块拖动）由调用方防抖；本类不做线程切换。
 */
class MacDisplayBrightness : DisplayBrightness {

    /** CGDirectDisplayID 等 32 位 id（JNA 按 Int 走）。 */
    private interface CG : Library {
        fun CGGetOnlineDisplayList(max: Int, displays: IntArray, count: IntArray): Int
        fun CGDisplayIsBuiltin(display: Int): Int
        fun CGDisplayVendorNumber(display: Int): Int
        fun CGDisplayModelNumber(display: Int): Int
        fun CGDisplaySerialNumber(display: Int): Int
        fun CGDisplayIOServicePort(display: Int): Int
    }

    private interface IOKit : Library {
        fun IOFBGetI2CInterfaceCount(framebuffer: Int, count: IntArray): Int
        fun IOFBCopyI2CInterfaceForBus(framebuffer: Int, bus: Int, iface: IntArray): Int
        fun IOI2CInterfaceOpen(iface: Int, options: Int, connect: LongArray): Int
        fun IOI2CSendRequest(connect: Long, options: Int, req: IOI2CRequest): Int
        fun IOI2CInterfaceClose(connect: Long, options: Int): Int
        fun IOObjectRelease(obj: Int): Int
    }

    private interface SysLib : Library {
        fun mach_timebase_info(info: TimebaseInfo): Int
    }

    /** mach 时间基准（AbsoluteTime 换算；Intel 计 numer=denom=1）。 */
    class TimebaseInfo : Structure(ALIGN_NONE) {
        @JvmField var numer: Int = 0
        @JvmField var denom: Int = 0
        override fun getFieldOrder() = listOf("numer", "denom")
    }

    /**
     * IOI2CRequest（Apple 公开头 `IOI2CInterface.h`，`pack(4)` LP64 = 124 字节；
     * JNA 用 ALIGN_NONE 逐字节对齐，[size] 断言锁死，漂移即炸）。
     */
    class IOI2CRequest : Structure(ALIGN_NONE) {
        @JvmField var sendTransactionType: Int = 0
        @JvmField var replyTransactionType: Int = 0
        @JvmField var sendAddress: Int = 0
        @JvmField var replyAddress: Int = 0
        @JvmField var sendSubAddress: Byte = 0
        @JvmField var replySubAddress: Byte = 0
        @JvmField var reservedA: ByteArray = ByteArray(2)
        @JvmField var minReplyDelay: Long = 0
        @JvmField var result: Int = 0
        @JvmField var commFlags: Int = 0
        @JvmField var padA: Int = 0
        @JvmField var sendBytes: Int = 0
        @JvmField var reservedB: IntArray = IntArray(2)
        @JvmField var padB: Int = 0
        @JvmField var replyBytes: Int = 0
        @JvmField var completion: Pointer? = null
        @JvmField var sendBuffer: Pointer? = null
        @JvmField var replyBuffer: Pointer? = null
        @JvmField var reservedC: IntArray = IntArray(10)

        override fun getFieldOrder() = listOf(
            "sendTransactionType", "replyTransactionType", "sendAddress", "replyAddress",
            "sendSubAddress", "replySubAddress", "reservedA", "minReplyDelay", "result",
            "commFlags", "padA", "sendBytes", "reservedB", "padB", "replyBytes",
            "completion", "sendBuffer", "replyBuffer", "reservedC",
        )
    }

    private val cg: CG? = runCatching { Native.load("CoreGraphics", CG::class.java) }.getOrNull()
    private val iokit: IOKit? = runCatching { Native.load("IOKit", IOKit::class.java) }.getOrNull()
    private val timebase: TimebaseInfo? = runCatching {
        Native.load("System", SysLib::class.java).let { sys ->
            TimebaseInfo().also { sys.mach_timebase_info(it) }
        }
    }.getOrNull()

    private fun delayToAbsolute(ms: Long): Long {
        val tb = timebase ?: return ms * 1_000_000L // Intel 计默认纳秒直通
        return ms * 1_000_000L * tb.denom / tb.numer.coerceAtLeast(1)
    }

    private fun externals(): List<Int> {
        val c = cg ?: return emptyList()
        val count = IntArray(1)
        if (c.CGGetOnlineDisplayList(0, IntArray(0), count) != 0) return emptyList()
        val ids = IntArray(count[0].coerceAtMost(16))
        val got = IntArray(1)
        if (c.CGGetOnlineDisplayList(ids.size, ids, got) != 0) return emptyList()
        return ids.take(got[0]).filter { c.CGDisplayIsBuiltin(it) == 0 }
    }

    /** 在指定显示器全部 I2C 总线上执行一次送收（读/写），任一总线成功即返回（成功标志+回包）。 */
    private fun transact(display: Int, send: ByteArray, replyLen: Int): Pair<Boolean, ByteArray?> {
        val c = cg ?: return false to null
        val kit = iokit ?: return false to null
        val fb = c.CGDisplayIOServicePort(display)
        if (fb == 0) return false to null
        val busCount = IntArray(1)
        if (kit.IOFBGetI2CInterfaceCount(fb, busCount) != 0 || busCount[0] <= 0) return false to null
        val sendMem = Memory(send.size.toLong()).also { it.write(0, send, 0, send.size) }
        val replyMem = if (replyLen > 0) Memory(replyLen.toLong()).also { it.clear() } else null
        for (bus in 0 until busCount[0].coerceAtMost(8)) {
            val iface = IntArray(1)
            if (kit.IOFBCopyI2CInterfaceForBus(fb, bus, iface) != 0) continue
            val conn = LongArray(1)
            if (kit.IOI2CInterfaceOpen(iface[0], 0, conn) != 0) {
                kit.IOObjectRelease(iface[0])
                continue
            }
            kit.IOObjectRelease(iface[0])
            try {
                // 先合并送收（实测通路），失败回退分开发送后睡再纯读；写无回包，纯写成功即认。
                if (doOnce(kit, conn[0], sendMem, send.size, replyMem, replyLen, ddcReply = true)) {
                    return true to replyMem?.getByteArray(0, replyLen)
                }
                if (replyMem != null && doOnce(kit, conn[0], sendMem, send.size, null, 0, ddcReply = false)) {
                    Thread.sleep(60)
                    replyMem.clear()
                    for (simple in listOf(false, true)) {
                        if (doOnce(kit, conn[0], null, 0, replyMem, replyLen, ddcReply = !simple, simpleRead = simple)) {
                            return true to replyMem.getByteArray(0, replyLen)
                        }
                    }
                }
            } finally {
                kit.IOI2CInterfaceClose(conn[0], 0)
            }
        }
        return false to null
    }

    private fun doOnce(
        kit: IOKit,
        conn: Long,
        sendMem: Memory?,
        sendLen: Int,
        replyMem: Memory?,
        replyLen: Int,
        ddcReply: Boolean,
        simpleRead: Boolean = false,
    ): Boolean {
        val req = IOI2CRequest()
        if (sendMem != null) {
            req.sendAddress = 0x6E
            req.sendTransactionType = 1 // kIOI2CSimpleTransactionType
            req.sendBuffer = sendMem
            req.sendBytes = sendLen
        } else {
            req.sendTransactionType = 0 // kIOI2CNoTransactionType
        }
        if (replyMem != null) {
            req.replyAddress = 0x6F
            req.replySubAddress = 0x51
            req.replyTransactionType = if (simpleRead) 1 else 2 // simple / DDCciReply
            req.replyBuffer = replyMem
            req.replyBytes = replyLen
            if (sendMem != null) req.minReplyDelay = delayToAbsolute(40)
        } else {
            req.replyTransactionType = 0
        }
        req.write()
        if (kit.IOI2CSendRequest(conn, 0, req) != 0) return false
        req.read()
        return req.result == 0
    }

    private fun readVcp(display: Int, op: Int, tries: Int = 6): DdcPackets.GetReply? {
        repeat(tries) {
            val (ok, raw) = transact(display, DdcPackets.buildGet(op), 11)
            if (ok && raw != null) {
                DdcPackets.parseGetReply(raw, op)?.let { return it }
            }
            Thread.sleep(40)
        }
        return null
    }

    override fun probe(): List<DisplayBrightness.Display> {
        val c = cg ?: return emptyList()
        return externals().map { d ->
            val id = "${c.CGDisplayVendorNumber(d)}:${c.CGDisplayModelNumber(d)}:${c.CGDisplaySerialNumber(d)}"
            val r = readVcp(d, DdcPackets.OP_BRIGHTNESS, tries = 2)
            DisplayBrightness.Display(id, builtin = false, ddcCapable = r != null, max = r?.max ?: 0)
        }
    }

    override fun current(): Int? {
        for (d in externals()) {
            readVcp(d, DdcPackets.OP_BRIGHTNESS, tries = 3)?.let { return it.current }
        }
        return null
    }

    override fun set(level: Int): Boolean {
        var ok = false
        for (d in externals()) {
            val pkt = DdcPackets.buildSet(DdcPackets.OP_BRIGHTNESS, level)
            repeat(2) {
                val (sent, _) = transact(d, pkt, 0)
                if (sent) {
                    ok = true
                    return@repeat
                }
                Thread.sleep(40)
            }
            // 写后按 VCP 惯例静置，让显示器固件提交（读回校验由调用方按需做）。
            Thread.sleep(60)
        }
        return ok
    }
}
