package orilumn.reader.data.settings

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.source
import okio.use

/**
 * Reading configuration user-set read/write: `settingsDir/reader.json`.
 *
 * Responsibilities:
 *  - [load]: read the user set; no file or parse failure → fall back to [ReaderSettings.DEFAULT] (not persisted).
 *  - [save]: persist settings as JSON (atomic write: write a temp file first, then rename).
 *  - [reset]: one-tap reset → delete the user file, after which [load] returns the default set.
 *
 * Thread safety: methods are called from IO threads; recommended to run within coroutine Dispatchers.IO.
 * Platform-agnostic via okio [FileSystem.SYSTEM] (S18: java.io.File → okio, joins commonMain).
 */
class ReaderSettingsStore(settingsDir: String) {

    private val fs = FileSystem.SYSTEM

    private val file: Path = settingsDir.toPath() / FILE_NAME
    private val tmp: Path = settingsDir.toPath() / "$FILE_NAME.tmp"

    /** Read the currently effective settings (user set; default set when absent). */
    fun load(): ReaderSettings = runCatching {
        val text = if (fs.exists(file)) fs.source(file).buffer().use { it.readUtf8() } else null
        if (text != null) ReaderSettings.fromJson(text) else ReaderSettings.DEFAULT
    }.getOrDefault(ReaderSettings.DEFAULT)

    /** Persist the user set. */
    fun save(settings: ReaderSettings) {
        fs.createDirectories(file.parent!!)
        // Atomic write: write the temp file first, then replace, to avoid leaving a half-written JSON on interruption
        fs.writeTextAtomic(file, settings.toJson(2))
    }

    /**
     * One-tap reset: delete the user-set file, after which [load] falls back to the default.
     */
    fun reset() {
        fs.delete(file, mustExist = false)
        fs.delete(tmp, mustExist = false)
    }

    private companion object {
        const val FILE_NAME = "reader.json"
    }
}