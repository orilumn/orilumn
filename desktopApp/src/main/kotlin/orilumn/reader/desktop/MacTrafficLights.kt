package orilumn.reader.desktop

import com.sun.jna.Callback
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.Window

/**
 * macOS 系统红绿灯（关闭 / 最小化 / 全屏三键）显隐（**平台层·桌面壳**，仅 macOS）。
 *
 * ## 为什么需要它
 *
 * 阅读界面要 Apple Books 式观感：标题栏（红绿灯）**平时隐藏，鼠标移到窗口顶部才浮出**。
 *
 * JDK 的客户端属性做不到：`Window.closeable` / `Window.minimizable` 走 `_STYLE_PROP_BITMASK`，
 * 改 `NSWindowStyleMask` 能把关闭 / 最小化键**隐藏**；但绿键（zoom）走 `_METHOD_PROP_BITMASK`，
 * JDK 只 `[zoomButton setEnabled:]`（**置灰，不隐藏**，见 OpenJDK `AWTWindow.m`
 * `setPropertiesForStyleBits`）。想隐藏绿键只能去掉 `.resizable`，会连窗口缩放一并废掉。
 *
 * 故直接向 AppKit 发消息 `[NSWindow standardWindowButton:i] setHidden:`：只改按钮可见性，
 * 不碰 styleMask，窗口缩放、原生全屏 Space、系统圆角 / 阴影全不受影响。
 *
 * ## 线程（最容易踩的一处）
 *
 * AppKit 的窗口几何**只允许在 AppKit 主线程**改，否则直接
 * `NSInternalInconsistencyException: 'NSWindow geometry should only be modified on the main thread!'`
 * ——这是硬 abort，JVM 侧 catch 不到。而 **macOS 上 AppKit 主线程 ≠ AWT EDT**：实测线程名分别是
 * `AWT-AppKit`（真正跑 NSApplication 运行循环、即主线程）与 `AWT-EventQueue-0`（AWT 事件线程，
 * 也即 Compose 组合 / 副作用所在线程）。故**不能**就地调用，得投到 AppKit 主队列：
 * 队列取 `[[NSOperationQueue mainQueue] underlyingQueue]`（本机实测可取），投递用 libdispatch 的
 * `dispatch_async_f(queue, ctx, fn)`（回调落在 `AWT-AppKit`）。
 *
 * ## 句柄来源
 *
 * Compose 的 `ComposeWindow.windowHandle`（skiko 底层原生句柄）。实测它给的是 JDK 的
 * `AWTWindow_Normal`——**它是 `NSWindow` 的子类**（OpenJDK 的 `AWTWindow` 就是 `NSWindow` 子类，
 * 自己当自己的 delegate），但类名不叫 `NSWindow`。故用**指针级父类链遍历**
 * （`object_getClass` + `class_getSuperclass`，全程不发消息、不取 BOOL）判定它是 `NSWindow`
 * （直接用）还是 `NSView`（取 `[view window]`）；两者都不是再回退 `[NSApp mainWindow]` /
 * `keyWindow`。
 *
 * ⚠️ **绝不能用类名精确比较、也不能靠「发消息试试」探测**：ObjC 对未识别 selector 抛的是
 * `NSException`，从 JVM 侧无法 catch，会直接把进程 abort（本类第一版就是这么炸的：
 * 拿 `AWTWindow_Normal` 发了 `[window]`）。父类链遍历是唯一安全的判定。
 *
 * ## 非 macOS
 *
 * [setTitleBarButtonsHidden] 首行即 [IS_MAC] 门控，其余平台恒为 no-op。
 */
internal object MacTrafficLights {

    // NSWindowButton 枚举（AppKit）：关闭 / 最小化 / 缩放（绿键）。
    private const val BUTTON_CLOSE = 0L
    private const val BUTTON_MINIATURIZE = 1L
    private const val BUTTON_ZOOM = 2L

    /** 投到 AppKit 主队列的工作项（JNA Callback = 一个 C 函数指针）。 */
    private interface Work : Callback {
        fun invoke(context: Pointer)
    }

    // 强引用常驻，防被 GC（JNA 回调一旦被回收，原生侧就是野函数指针）。
    private val hideWork: Work = object : Work {
        override fun invoke(context: Pointer) = applyToWindow(context, hidden = true)
    }
    private val showWork: Work = object : Work {
        override fun invoke(context: Pointer) = applyToWindow(context, hidden = false)
    }

    private val libObjc: NativeLibrary? by lazy {
        if (!IS_MAC) null else runCatching { NativeLibrary.getInstance("objc") }.getOrNull()
    }
    private val libSystem: NativeLibrary? by lazy {
        if (!IS_MAC) null else runCatching { NativeLibrary.getInstance("libSystem.B.dylib") }.getOrNull()
    }
    private val selRegisterName by lazy { libObjc?.getFunction("sel_registerName") }
    private val objcGetClass by lazy { libObjc?.getFunction("objc_getClass") }
    private val objectGetClass by lazy { libObjc?.getFunction("object_getClass") }
    private val classGetSuperclass by lazy { libObjc?.getFunction("class_getSuperclass") }
    private val msgSend by lazy { libObjc?.getFunction("objc_msgSend") }
    private val dispatchAsyncF by lazy { libSystem?.getFunction("dispatch_async_f") }

    /** AppKit 主队列：`[[NSOperationQueue mainQueue] underlyingQueue]`（纯读，任意线程可调）。 */
    private val appKitQueue: Pointer? by lazy {
        val nsoqClass = objcGetClass?.invokePointer(arrayOf("NSOperationQueue"))
        val nsoq = sendPtr(nsoqClass, "mainQueue")
        sendPtr(nsoq, "underlyingQueue")
    }

    /**
     * 显示 / 隐藏系统红绿灯三键（[hidden] = true 即 Apple Books 的「平时不显示」态）。
     * 异步投到 AppKit 主线程执行（见类注释「线程」）；解析不到窗口时静默 no-op。
     */
    fun setTitleBarButtonsHidden(window: Window, hidden: Boolean) {
        if (!IS_MAC) return
        // 读句柄是纯字段读，可在调用线程做；真正的 AppKit 交互全部留给主线程回调。
        val handle = runCatching {
            (window as? androidx.compose.ui.awt.ComposeWindow)?.windowHandle ?: 0L
        }.getOrDefault(0L)
        if (handle == 0L) return
        val queue = appKitQueue ?: return
        val work = if (hidden) hideWork else showWork
        runCatching { dispatchAsyncF?.invokeVoid(arrayOf(queue, Pointer(handle), work)) }
    }

    /** 已在 AppKit 主线程（由 [dispatchAsyncF] 投递上来）执行。 */
    private fun applyToWindow(handle: Pointer, hidden: Boolean) {
        val nsWindow = resolveNsWindow(handle) ?: return
        val flag = if (hidden) 1L else 0L
        for (button in longArrayOf(BUTTON_CLOSE, BUTTON_MINIATURIZE, BUTTON_ZOOM)) {
            val view = sendPtr(nsWindow, "standardWindowButton:", button) ?: continue
            sendVoid(view, "setHidden:", flag)
        }
    }

    /** 从原生句柄解析出 NSWindow（见类注释「句柄来源」）。 */
    private fun resolveNsWindow(handle: Pointer): Pointer? {
        when {
            isKindOf(handle, "NSWindow") -> return handle
            isKindOf(handle, "NSView") -> sendPtr(handle, "window")?.let { return it }
        }
        // 兜底：向 NSApp 要主窗口 / 键窗口。
        val nsApp = sendPtr(objcGetClass?.invokePointer(arrayOf("NSApplication")), "sharedApplication")
            ?: return null
        return sendPtr(nsApp, "mainWindow") ?: sendPtr(nsApp, "keyWindow")
    }

    /**
     * [obj] 是否 [className] 或其子类实例（沿 `class_getSuperclass` 走父类链）。
     * 全程只读类对象，不向 [obj] 发任何消息，故对「是什么都还没确定」的对象也安全。
     */
    private fun isKindOf(obj: Pointer, className: String): Boolean {
        val target = objcGetClass?.invokePointer(arrayOf(className)) ?: return false
        var cls = objectGetClass?.invokePointer(arrayOf(obj)) ?: return false
        // 深度上限兜底，防异常类结构造成死循环。
        repeat(64) {
            if (cls == target) return true
            cls = classGetSuperclass?.invokePointer(arrayOf(cls)) ?: return false
        }
        return false
    }

    private fun sel(name: String): Pointer? =
        selRegisterName?.invokePointer(arrayOf(name))

    private fun sendPtr(receiver: Pointer?, selector: String, vararg args: Any?): Pointer? {
        val actual = receiver ?: return null
        val sel = sel(selector) ?: return null
        return runCatching {
            msgSend?.invoke(Pointer::class.java, arrayOf(actual, sel, *args)) as? Pointer
        }.getOrNull()
    }

    private fun sendVoid(receiver: Pointer, selector: String, vararg args: Any?) {
        val sel = sel(selector) ?: return
        runCatching { msgSend?.invokeVoid(arrayOf(receiver, sel, *args)) }
    }
}
