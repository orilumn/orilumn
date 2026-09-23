package orilumn.reader.data.epub

/**
 * In-memory [EpubResourceReader] that simulates zip entries with a map, for JVM unit tests.
 */
class FakeEpubResourceReader(
    files: Map<String, ByteArray>,
) : EpubResourceReader {
    private val normalized: Map<String, ByteArray> =
        files.entries.associate { normalizePath(it.key) to it.value }

    override fun entries(): Sequence<String> = normalized.keys.asSequence()

    override fun readText(path: String): String? =
        readBytes(path)?.toString(Charsets.UTF_8)

    override fun readBytes(path: String): ByteArray? =
        normalized[normalizePath(path)]
}