package orilumn.reader.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * 共用字体池的**可观察代次**（用户层·壳与覆盖层之间的信号）。
 *
 * ## 为什么需要它
 *
 * 旁注字形由自研引擎从共用字体池解析（`NoteTextSpec` 只带族名，引擎不认平台字体对象），
 * 而池是**异步**装好的：读者换批注字体后，覆盖层会先按旧池画一版（回退字体），
 * 池装好时那一版已经在覆盖层缓存里（[PageOverlayLayer] 按内容键缓存整页图）。
 * 键里若不认池代次，回退字形会一直留到下次翻页——读者看到的是「换了字体没生效」。
 *
 * 同理，布点的量宽尺子（[rememberNoteTextSizer]）按同一族名缓存宽度：池换代后必须
 * 重建，否则摆位用的还是回退字形的度量，画出来就会多折一行被省略号截掉。
 *
 * ## 写入方
 *
 * 两壳（平板 / 桌面）在装池**确实变了**之后调 [bump]；池没变不调，避免无谓重画。
 * 读数方（[ReaderScreen] 的覆盖层与尺子）把它当键，代次一变即重画 / 重建。
 */
object NoteFontPool {
    /** 池代次：每次池内容真变 +1。 */
    var epoch by mutableLongStateOf(0L)
        private set

    /** 池换过面（两壳装池成功后调用）。 */
    fun bump() {
        epoch++
    }
}
