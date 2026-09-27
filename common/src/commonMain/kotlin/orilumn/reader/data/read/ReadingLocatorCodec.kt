package orilumn.reader.data.read

import orilumn.reader.io.Logger

/**
 * Q1-9 收敛：阅读定位串编解码单源。
 *
 * 写格式统一为 `chapter:char`（简单、无转义）；读兼容两种历史格式：
 * foliate lastLocation JSON（`{"chapter":N,"char":M}`，旧平板行）与 `chapter:char`
 *（桌面行）。读侧唯一入口是引擎 `BookDocumentController.open`，经此解码。
 */
object ReadingLocatorCodec {
    /** 编码：章节下标 + 章内字符偏移 → `chapter:char`。 */
    fun encode(chapter: Int, char: Int): String = "$chapter:$char"

    /**
     * 解码：`{...}` 走 foliate JSON（缺键按 0，与旧 `optInt` 同义），否则按
     * `chapter:char` 切分（缺 char 按 0）；不可解析回 null（不恢复）。
     */
    fun decode(raw: String?): Pair<Int, Int>? {
        val s = raw?.takeIf { it.isNotBlank() } ?: return null
        val t = s.trim()
        if (t.startsWith("{")) {
            fun num(key: String) =
                Regex(""""$key"\s*:\s*(-?\d+)""").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val ch = num("chapter")
            val c = num("char")
            if (ch == null && c == null) return null
            // 缺键按 0（与旧 optInt 同义，语义保留）：但脏串曾按合法头位置恢复，记 w 区分。
            if (ch == null || c == null) Logger.w("Orilumn.OPEN", "locator partial JSON '$t' → chapter=${ch ?: 0} char=${c ?: 0}")
            if ((ch ?: 0) < 0 || (c ?: 0) < 0) Logger.w("Orilumn.OPEN", "locator negative '$t'")
            return (ch ?: 0) to (c ?: 0)
        }
        val parts = t.split(':')
        val ch = parts.getOrNull(0)?.toIntOrNull() ?: return null
        // 缺 char 按 0（语义保留）；脏串记 w。
        val c = parts.getOrNull(1)?.toIntOrNull() ?: run {
            Logger.w("Orilumn.OPEN", "locator bad char '$t' → char=0")
            0
        }
        if (ch < 0 || c < 0) Logger.w("Orilumn.OPEN", "locator negative '$t'")
        return ch to c
    }
}
