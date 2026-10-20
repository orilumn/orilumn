package orilumn.reader.data.annotation

/**
 * 标注类型。`wire` 是数据库里的稳定字符串；枚举改名不改变已存数据。
 */
enum class AnnotationKind(val wire: String) {
    Highlight("highlight"),
    Underline("underline"),
    Note("note"),
    ;

    companion object {
        fun fromWire(wire: String): AnnotationKind =
            entries.firstOrNull { it.wire == wire }
                ?: error("未知标注类型：$wire")
    }
}

/**
 * 文本标注：锚点是「章号 + 章内字符区间 [charStart, charEnd)」，与 PageSlice / ReadingLocator 同源。
 *
 * 颜色是创建当时的 ARGB 快照；改调色板不会回溯已有标注。`note` 供 v2 批注使用。
 * 标注之间不互相引用，也不随排版参数（字号 / 边距 / 主题 / 旋转）漂移。
 */
data class Annotation(
    val id: Long = 0,
    val bookId: Long,
    val chapter: Int,
    val charStart: Int,
    val charEnd: Int,
    val kind: AnnotationKind,
    val color: Int? = null,
    val note: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    init {
        require(charStart >= 0) { "charStart 不能为负：$charStart" }
        require(charEnd > charStart) { "charEnd 必须大于 charStart：[$charStart, $charEnd)" }
    }
}
