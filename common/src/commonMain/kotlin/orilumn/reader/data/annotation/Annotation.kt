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
 * 划线（[AnnotationKind.Underline]）的线型。`wire` 是数据库里的稳定字符串；
 * 只对划线有效，高亮 / 批注忽略本字段。
 *
 * - [Straight]：基线下方的实线（默认，也是老数据缺列时的取值）；
 * - [Wavy]：基线下方的波浪线；
 * - [Dashed]：基线下方的虚线；
 * - [Strike]：穿过字身的删除线（位置更高，与下划线共用颜色）。
 */
enum class AnnotationLineStyle(val wire: String) {
    Straight("straight"),
    Wavy("wavy"),
    Dashed("dashed"),
    Strike("strike"),
    ;

    companion object {
        /** 未知 / 空值一律按实线下划线（老数据兼容，不抛错）。 */
        fun fromWire(wire: String?): AnnotationLineStyle =
            entries.firstOrNull { it.wire == wire } ?: Straight
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
    /** 划线线型（实线 / 波浪 / 删除线）；高亮忽略。老数据缺列 = 实线。 */
    val lineStyle: AnnotationLineStyle = AnnotationLineStyle.Straight,
    val note: String? = null,
    /**
     * 创建当时的原文摘录（清单行显示，方案 D-24）。老记录（v1 已有标注）为空，
     * 翻到该章时按章节文本回填一次；仍未回填时清单显示「（无摘录）」。
     */
    val quote: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    init {
        require(charStart >= 0) { "charStart 不能为负：$charStart" }
        require(charEnd > charStart) { "charEnd 必须大于 charStart：[$charStart, $charEnd)" }
    }
}
