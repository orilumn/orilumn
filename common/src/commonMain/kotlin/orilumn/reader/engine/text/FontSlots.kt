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
     * 槽位字重解析（单源）：正文 / 标题槽在其字体族上的读者字重；`null` = 没选 / 非法
     * （调用方不写 `font-weight`）。
     *
     * 只服务正文 / 标题两个按书私有的槽（[bySlot]）；旧版本按族记账的表（[legacy]）只作兜底。
     * 三个全局字体槽（代码 / 衬线 / 无衬线）**不提供字重选择**，字重由排版主题按角色决定，
     * 不在这条解析路径上。两张表各自先滤掉非法值（不在 100..900 当没选），再比优先级。
     */
    fun weightOf(
        bySlot: Map<String, Int>,
        legacy: Map<String, Int>,
        slot: String,
        family: String,
    ): Int? {
        if (family.isBlank()) return null
        val trimmed = family.trim()
        val key = "$slot|$trimmed"
        return bySlot[key]?.takeIf { it in 100..900 }
            ?: legacy[trimmed]?.takeIf { it in 100..900 }
    }
}
