package orilumn.reader.engine

import orilumn.reader.engine.text.LayoutParamKey
import orilumn.reader.io.Logger

/**
 * 用户层共享：设置面板“开抑制 + 关门控”收口（平板/桌面两端宿主同调，禁止各写一遍）。
 *
 * 排版层（上）策略，平台无关：
 * 1. 开面板 [onPanelOpen]：抑制后台 canonical 全章重排（不跟前台拖拽抢 CPU）+ 快照版式指纹；
 * 2. 拖动中：调用方只做本章轻刷（节流/防抖各端自理），整书沉淀一律等关面板；
 * 3. 关面板 [onPanelClose]：解抑 + 比对指纹，**版式真变了才整书 finalize**（当章产物返回
 *    调用方在主线程绑定落位），不变回 null。指纹消费一次：重入的第二次关闭看到无 diff，
 *    不再跑整书（遮罩连点双关事故即此）。
 *
 * 指纹 = [LayoutParamKey]（纯版式参数；亮度等非版式不进，拖完它们关面板零重排）。
 */
class PanelRelayoutGate(
    private val controller: BookDocumentController,
    private val logTag: String = "Orilumn.Engine",
) {
    private var baseHash: Long? = null

    /** 开面板：抑制后台 canonical + 快照版式指纹。 */
    fun onPanelOpen() {
        controller.deferCanonical = true
        baseHash = typographHash()
    }

    /**
     * 关面板：解抑；指纹变化才 `finalizeRelayoutAll`（废他章 + 当章绑定产物 + 整书 B2 沉淀）。
     *
     * @return 当章重排产物（调用方主线程绑定并落位）；版式无变化回 null。
     */
    suspend fun onPanelClose(chapter: Int, anchorChar: Int): BookDocumentController.ReflowResult? {
        controller.deferCanonical = false
        val before = baseHash
        val after = typographHash()
        baseHash = after
        // 关面板事件无条件记（变与不变都记）——"改参关面板没反应"先查这条再查引擎。
        Logger.w(logTag, "close settings panel typographHash $before -> $after changed=${before != null && after != before}")
        if (before == null || after == before) return null
        return controller.finalizeRelayoutAll(chapter, anchorChar)
    }

    /** 当前版式指纹（宽高固定 0 输入，只看排版参数是否变化）。 */
    fun typographHash(): Long = LayoutParamKey.fromProfile(controller.profile, 0, 0).hash()
}
