package orilumn.reader.data.read

import orilumn.reader.io.Logger

/**
 * Q1-9 收敛：阅读定位串编解码单源。
 *
 * 写格式：`chapter:char[:cover]`（简单、无转义）。`:cover` 表示「停在封面」（非页表页）。
 * 读兼容两种历史格式：
 * foliate lastLocation JSON（`{"chapter":N,"char":M}`，旧平板行）与 `chapter:char[:cover]`
 *（桌面行）。读侧唯一入口是引擎 `BookDocumentController.open`，经此解码。
 */
object ReadingLocatorCodec {
    /** 封面位后缀：`chapter:char:cover`。旧读侧只取前两段，后缀天然被忽略。 */
    private const val COVER = "cover"

    /** 编码：章节下标 + 章内字符偏移 → `chapter:char[:cover]`。 */
    fun encode(chapter: Int, char: Int, atCover: Boolean = false): String =
        if (atCover) "$chapter:$char:$COVER" else "$chapter:$char"

    /**
     * 解码：`{...}` 走 foliate JSON（缺键按 0，与旧 `optInt` 同义），否则按
     * `chapter:char[:...]` 切分（缺 char 按 0）；不可解析回 null（不恢复）。
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

    /**
     * 解码「是否停在封面」：仅从定位串推断，不依赖进度行杂项。
     * `chapter:char:cover` → true；foliate JSON 的 `"cover":true` 也可识别（如有）。
     */
    fun decodeAtCover(raw: String?): Boolean {
        val s = raw?.takeIf { it.isNotBlank() } ?: return false
        val t = s.trim()
        if (t.startsWith("{")) {
            if (Regex(""""cover"\s*:\s*true""").containsMatchIn(t)) return true
            return false
        }
        val parts = t.split(':')
        if (parts.size < 3) return false
        if (parts[2].equals(COVER, ignoreCase = true)) return true
        if (parts.drop(2).any { it.equals(COVER, ignoreCase = true) }) return true
        return false
    }
}
