package orilumn.reader.data.settings

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * kotlinx.serialization codecs for settings persistence (S17：org.json → kotlinx.serialization).
 *
 * - [compact] / [pretty]: full-object codec for [ReaderSettings] — encodes **all** fields including
 *   defaults so `toJsonObject()`/`toJson()` of a default set still emits every key (round-trip safe).
 * - [bookCompact] / [bookPretty]: overlay codec for [BookSettings] — omits null fields
 *   (`encodeDefaults=false`), matching the old "only non-null fields output" semantics.
 *
 * All codecs ignore unknown keys on decode (forward compatibility with newer versions).
 */
@OptIn(ExperimentalSerializationApi::class)
internal object SettingsJson {

    val compact: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    val pretty: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    val bookCompact: Json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    val bookPretty: Json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** org.json.optString(key, default) equivalent: string-typed values only; non-string/missing → default. */
    fun optString(o: JsonObject, key: String, default: String): String =
        (o[key] as? JsonPrimitive)?.contentOrNull ?: default

    /** org.json.optInt(key, default) equivalent. */
    fun optInt(o: JsonObject, key: String, default: Int): Int =
        (o[key] as? JsonPrimitive)?.intOrNull ?: default

    /** org.json.optDouble(key, default) equivalent. */
    fun optDouble(o: JsonObject, key: String, default: Double): Double =
        (o[key] as? JsonPrimitive)?.doubleOrNull ?: default

    /** org.json.optBoolean(key, default) equivalent. */
    fun optBoolean(o: JsonObject, key: String, default: Boolean): Boolean =
        (o[key] as? JsonPrimitive)?.booleanOrNull ?: default
}