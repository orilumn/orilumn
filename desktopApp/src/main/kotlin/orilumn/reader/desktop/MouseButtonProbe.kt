package orilumn.reader.desktop

import com.sun.jna.NativeLibrary

/**
 * 系统级「鼠标左键此刻是否按住」（**平台层·桌面壳**，仅桌面 JVM）。
 *
 * ## 为什么是「读状态」而不是「等事件」
 *
 * 拉窗口边缘 / 拖分栏缩放**不是我们的手势**，按下与抬起都轮不到内容区收：
 *  - **macOS**：窗口带系统装饰，边框缩放由 AppKit 在窗口边框的跟踪循环里直接取事件
 *    （`nextEventMatchingMask`），压根不派发给内容视图；
 *  - **Win/Linux**：窗口装饰（含缩放热区）已交回系统默认，缩放同样由窗口管理器接管。
 *
 * 而 AWT **没有**「resize 结束」回调——`componentResized` 只在尺寸**变化**那一刻发，
 * 松手那一下没有任何事件。所以「松手」只能问系统状态：左键还按着吗。
 *
 * 读得到（macOS / Windows）⇒ 手势结束即收口（轮询 ≤ [PollMs]，肉眼等同松手那一刻）；
 * 读不到（Linux 等）⇒ 返回 null，调用方**必须**有自己的回落判据（尺寸静默）。
 *
 * ## 各平台
 *
 * - **macOS**：`[NSEvent pressedMouseButtons]`（类方法，位掩码，bit0 = 左键）。纯读全局状态，
 *   不碰窗口几何，任意线程可调（JNA/ObjC 桥的线程口径同 `MacFamilyNames`：那条禁令针对的是
 *   **改窗口几何**，这里一次消息都不改）。
 * - **Windows**：`GetAsyncKeyState(VK_LBUTTON)`，返回值最高位置位 = 按下。
 * - **其他**：null。Linux 的 X11 要单开一条 Display 连接且 Wayland 下行为不一，
 *   暂无实机可验，宁可让调用方走「静默判据」这条已成立的回落，也不塞一段测不了的探测。
 */
internal object MouseButtonProbe {

    /** 轮询粒度：松手后最迟这么久被发现。 */
    const val PollMs = 25L

    /** 左键是否仍按住；null = 本平台读不到（调用方走回落判据）。 */
    fun isLeftDown(): Boolean? = impl.isLeftDown()

    /** `os.name` 的 Windows 判定（macOS 用壳里已有的 [IS_MAC]，避免两处口径）。 */
    private val IS_WINDOWS: Boolean =
        System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

    private val impl: Impl = when {
        IS_MAC -> MacImpl
        IS_WINDOWS -> WindowsImpl
        else -> UnreadableImpl
    }

    private interface Impl {
        fun isLeftDown(): Boolean?
    }

    /**
     * macOS：`[NSEvent pressedMouseButtons]`。
     *
     * 返回的是 `NSUInteger` 位掩码（bit0 = 左键，bit1 = 右键…），按 64 位整数取回
     * （arm64 / x86_64 上 `NSUInteger` 都是 8 字节）。JNA 缺失或类/选择器解析失败 ⇒ null。
     */
    private object MacImpl : Impl {
        private val libObjc: NativeLibrary? by lazy {
            runCatching { NativeLibrary.getInstance("objc") }.getOrNull()
        }
        private val msgSend by lazy { libObjc?.getFunction("objc_msgSend") }
        private val selRegisterName by lazy { libObjc?.getFunction("sel_registerName") }
        private val objcGetClass by lazy { libObjc?.getFunction("objc_getClass") }

        override fun isLeftDown(): Boolean? = runCatching {
            val nsEvent = objcGetClass?.invokePointer(arrayOf("NSEvent")) ?: return null
            val sel = selRegisterName?.invokePointer(arrayOf("pressedMouseButtons")) ?: return null
            val mask = msgSend?.invoke(Long::class.java, arrayOf(nsEvent, sel)) as? Long
                ?: return null
            (mask and 1L) != 0L
        }.getOrNull()
    }

    /** Windows：`GetAsyncKeyState(VK_LBUTTON=0x01)`，最高位（0x8000）置位 = 当前按下。 */
    private object WindowsImpl : Impl {
        private val getAsyncKeyState by lazy {
            runCatching { NativeLibrary.getInstance("user32").getFunction("GetAsyncKeyState") }
                .getOrNull()
        }

        override fun isLeftDown(): Boolean? = runCatching {
            val fn = getAsyncKeyState ?: return null
            val state = fn.invoke(Short::class.java, arrayOf(0x01)) as? Short ?: return null
            (state.toInt() and 0x8000) != 0
        }.getOrNull()
    }

    /** 读不到的平台：调用方按尺寸静默判据收口。 */
    private object UnreadableImpl : Impl {
        override fun isLeftDown(): Boolean? = null
    }
}
