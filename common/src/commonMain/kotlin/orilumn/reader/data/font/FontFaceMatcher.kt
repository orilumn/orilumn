package orilumn.reader.data.font

import kotlin.math.abs

/**
 * Selects the best [FontFace] of a family for a requested CSS weight + style (pure logic, JVM-testable).
 *
 * One family can hold several weight/style files (e.g. Source Han Sans Regular / Bold / Italic).
 * Selection favours an **exact italic match** first, then the **nearest weight class**; this gives a
 * real Bold/Italic file when one exists and a closest-weight fallback (e.g. Medium for a requested
 * 700) when the exact weight is absent. The caller filters by family beforehand.
 */
object FontFaceMatcher {

    /**
     * @param faces candidate faces of one family (same [FontFace.familyName]).
     * @param weight desired CSS numeric weight (e.g. 400 / 700).
     * @param italic desired style flag.
     * @return the best [FontFace], or null when [faces] is empty.
     */
    fun choose(faces: List<FontFace>, weight: Int, italic: Boolean): FontFace? {
        if (faces.isEmpty()) return null
        // Prefer faces whose italic flag matches the request; if none match, fall back to all faces.
        val exactStyle = faces.filter { SubfamilyMetric.italic(it.subfamily) == italic }
        val pool = exactStyle.ifEmpty { faces }
        // Nearest weight; tie-break to the heavier face.
        return pool.minWithOrNull(
            compareBy({ abs(SubfamilyMetric.weight(it.subfamily) - weight) }, { -SubfamilyMetric.weight(it.subfamily) }),
        )
    }
}