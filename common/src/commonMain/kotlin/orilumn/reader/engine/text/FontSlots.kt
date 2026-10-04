package orilumn.reader.engine.text

import orilumn.reader.engine.html.CODE_TAGS

/**
 * 用户字体三槽路由（排版层下·纯函数）：
 * code-like（monospace 或 pre/code）→ 代码槽，h1..h6 → 标题槽，其余 → 正文槽；
 * 返回即 `ReaderSettings.fontBody/fontTitle/fontCode` 的别名（空串 = 跟随原书）。
 * 原 `shared-ui ReaderMath.fontSlotFor`（R4 归位：UI 层不做槽位路由；`CODE_TAGS`
 * 单源见 `orilumn.reader.engine.html`）。
 */
object FontSlots {
    fun slotFor(tag: String?, monospace: Boolean, body: String, title: String, code: String): String {
        val codeLike = monospace || (tag != null && tag in CODE_TAGS)
        val heading = tag != null && tag.length == 2 && tag[0] == 'h' && tag[1].digitToIntOrNull() != null
        return when {
            codeLike -> code
            heading -> title
            else -> body
        }
    }

    /** 与 [slotFor] 同一路由，但返回**槽位字段名**（`"fontBody"`/`"fontTitle"`/`"fontCode"`）。
     *  给「按槽位寻址」的键用（当前唯一调用方：`ReaderUiSheet` 查该槽选定的字重）。 */
    fun slotNameOf(tag: String?, monospace: Boolean): String {
        val codeLike = monospace || (tag != null && tag in CODE_TAGS)
        val heading = tag != null && tag.length == 2 && tag[0] == 'h' && tag[1].digitToIntOrNull() != null
        return when {
            codeLike -> "fontCode"
            heading -> "fontTitle"
            else -> "fontBody"
        }
    }
}
