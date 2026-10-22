package orilumn.reader.engine

/** 一次重排要用的锚点：章号 + 该章字符流里的锚点字符。 */
data class LayoutAnchor(val chapter: Int, val char: Int)

/**
 * 版式变更期间的锚点钉住（用户层共享；平板 `ReaderActivity` 与桌面 `ReaderView` 同调）。
 *
 * 改参/旋转的落位会把**落位页首**写回当前定位。下一次重排若再从「当前页首」取锚点，
 * 锚点就一路往回走：短章落位取含锚点的那一页，页首恒 `<=` 锚点，于是每刷一次退一截
 * （真机 12:02 拖行距实测 5133→5059→5099→5325→5024→…，松手时已退掉大半页）；
 * 旋转同理，来回转几次能退掉整页。
 *
 * 规矩：**锚点只在用户真的导航时更换**（翻页/跳章/进度条/目录/开书，见
 * `SnapshotReaderHost.onNavigated`）。改参、拖动预览、关面板落位、旋转都不换锚：
 *  - 面板内：开面板钉住，拖动期间每次预览 + 关面板那次最终落位都用它；
 *  - 面板外：一轮防抖重排（字体回填/原书设置）起手钉住，两段（预览 + 静默全套）同锚；
 *  - 旋转/缩放：同一阅读位置，沿用已有钉子。
 *
 * 所属层级：用户层（触发点）。引擎只认传进来的字符锚点，不持有交互状态。
 */
class RelayoutAnchor {
    private var chapter = -1
    private var char = -1

    val isPinned: Boolean get() = chapter >= 0

    /** 钉住锚点；已有钉子只认第一次（落位页首不得改锚）。 */
    fun pin(chapter: Int, char: Int) {
        if (this.chapter < 0) {
            this.chapter = chapter
            this.char = char
        }
    }

    /**
     * 取本次重排要用的锚点：已钉且同章 ⇒ 钉子；否则用调用方给的「当前页首」（不顺手钉，
     * 免得一次性重排把旧锚点带进下一次交互）。
     */
    fun resolve(chapter: Int, char: Int): LayoutAnchor =
        if (isPinned && this.chapter == chapter) LayoutAnchor(this.chapter, this.char)
        else LayoutAnchor(chapter, char)

    /** 用户真的换了位置：松钉，下一次改参按新的当前页首取。 */
    fun release() {
        chapter = -1
        char = -1
    }
}
