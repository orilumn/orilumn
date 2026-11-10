package orilumn.reader.data.revision

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 修订携带的**行内样式区间**（规格 `docs/正文修改-行内标记语法.md` §7）。
 *
 * 修订的文字本身仍是纯文本（[TextRevision.text]）；「哪几个字套着什么元素」单独记在这里，
 * 与文字一起落库、一起版本化。这样正文、坐标、分页表都不含标记字符，重放时按区间套元素，
 * 不用再靠「新字挂左邻」去猜。
 *
 * 坐标口径：区间作用于 [TextRevision.text]，**含段界字符**（`RevisionBlocks.SEPARATOR` 占一位），
 * 段数不变的修订没有段界，于是与「渲染空间」逐位相同；段数变了时按块裁剪由重放负责。
 *
 * 与排版层的关系：本文件属于数据层（模型 + 编解码），不认识排版、不认识 Android；
 * 排版层用 `InlineMarkup.StyleSpan` 表达同一件事，转换见 `InlineMarkup.toStored / fromStored`。
 */

/** 行内样式签名：标签 + 属性（已按白名单过滤）。 */
data class RevisionStyle(val tag: String, val attrs: Map<String, String> = emptyMap())

/** 一段行内样式：作用于 `[start, endExclusive)`，可嵌套、按起点排序。 */
data class RevisionStyleSpan(val start: Int, val endExclusive: Int, val style: RevisionStyle)

/**
 * [RevisionStyleSpan] 的 JSON 编解码。
 *
 * 落库形态：`{"v":1,"s":[[start,end,"tag",{attrs}],...]}`；老修订该列为空串，[decode] 返回
 * `null`（= 没有显式样式，行为与引入本列之前完全一致）；新修订一律写 `[]` 或实际区间，
 * 因此「显式声明为空」与「老数据」分得开。
 */
object RevisionStyles {

    /** 编解码版本（改格式时 +1；解析规则版本另见排版层的 `InlineMarkup.VERSION`）。 */
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(spans: List<RevisionStyleSpan>): String {
        val array = JsonArray(
            spans.map { s ->
                JsonArray(
                    listOf(
                        JsonPrimitive(s.start),
                        JsonPrimitive(s.endExclusive),
                        JsonPrimitive(s.style.tag),
                        JsonObject(s.style.attrs.mapValues { JsonPrimitive(it.value) }),
                    ),
                )
            },
        )
        return buildJsonObject {
            put("v", JsonPrimitive(VERSION))
            put("s", array)
        }.toString()
    }

    /** 解析；空串 / 坏数据返回 null（当老修订处理，宁可退回旧行为也不排错页）。 */
    fun decode(raw: String): List<RevisionStyleSpan>? {
        if (raw.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val version = root["v"]?.jsonPrimitive?.intOrNull ?: 0
            if (version > VERSION) return null
            root["s"]?.jsonArray?.mapNotNull { el ->
                val a = el.jsonArray
                if (a.size < 4) return@mapNotNull null
                val start = a[0].jsonPrimitive.intOrNull ?: return@mapNotNull null
                val end = a[1].jsonPrimitive.intOrNull ?: return@mapNotNull null
                val tag = a[2].jsonPrimitive.contentOrNull ?: return@mapNotNull null
                val attrs = a[3].jsonObject.entries
                    .mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }
                    .toMap()
                RevisionStyleSpan(start, end, RevisionStyle(tag, attrs))
            }?.sortedWith(compareBy({ it.start }, { -it.endExclusive }))
        }.getOrNull()
    }
}
