package orilumn.reader.engine.skia

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * `IntRange` 的落盘序列化（`start:end` 文本，半开记法写成闭区间两端）。
 *
 * 只服务页面快照（[DrawLine.range] / `imgHidden` / `visibilityHiddenRuns`）：`kotlinx.serialization`
 * 没有 `IntRange` 内置序列化器，而把区间拆成两个字段会污染渲染模型的数据形状。
 * 文本形式的代价可忽略（一页几十行），换来的是落盘文件肉眼可读、越界能当场看出来。
 */
object IntRangeSerializer : KSerializer<IntRange> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("orilumn.reader.engine.skia.IntRange", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: IntRange) {
        encoder.encodeString("${value.first}:${value.last}")
    }

    override fun deserialize(decoder: Decoder): IntRange {
        val raw = decoder.decodeString()
        val sep = raw.indexOf(':')
        // 半开/闭合两种写法都认：`a:b` 是闭区间两端（本会话写出的形状）。
        if (sep <= 0) return IntRange.EMPTY
        val start = raw.substring(0, sep).toIntOrNull() ?: return IntRange.EMPTY
        val end = raw.substring(sep + 1).toIntOrNull() ?: return IntRange.EMPTY
        return start..end
    }
}

/** `List<IntRange>` 的列表包装（同 [IntRangeSerializer]；JSON 里是字符串数组）。 */
object IntRangeListSerializer : KSerializer<List<IntRange>> {
    override val descriptor: SerialDescriptor =
        kotlinx.serialization.builtins.ListSerializer(IntRangeSerializer).descriptor

    override fun serialize(encoder: Encoder, value: List<IntRange>) {
        kotlinx.serialization.builtins.ListSerializer(IntRangeSerializer).serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<IntRange> =
        kotlinx.serialization.builtins.ListSerializer(IntRangeSerializer).deserialize(decoder)
}
