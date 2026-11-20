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

    /**
     * 槽位字重解析（单源）：某槽在其字体族上的读者字重；`null` = 没选 / 非法（调用方不写 `font-weight`）。
     *
     * 优先级按槽归属选表：正文 / 标题的字重按书私有（[bySlot]），等宽槽已是全局项（[global]）；
     * 旧版本按族记账的表（[legacy]）只作兜底。等宽槽必须 [global] 优先——旧版本它按书私有，
     * 书文件里可能留着 `fontCode|族` 的旧锚点，若压过全局表，读者在新书里选的代码字重就会被
     * 某本书的旧值顶掉（换书不沿用）。三张表各自先滤掉非法值（不在 100..900 当没选），再比优先级。
     */
    fun weightOf(
        bySlot: Map<String, Int>,
        global: Map<String, Int>,
        legacy: Map<String, Int>,
        slot: String,
        family: String,
    ): Int? {
        if (family.isBlank()) return null
        val trimmed = family.trim()
        val key = "$slot|$trimmed"
        val by = bySlot[key]?.takeIf { it in 100..900 }
        val gl = global[key]?.takeIf { it in 100..900 }
        val scoped = if (slot == "fontCode") gl ?: by else by ?: gl
        return scoped ?: legacy[trimmed]?.takeIf { it in 100..900 }
    }
}
