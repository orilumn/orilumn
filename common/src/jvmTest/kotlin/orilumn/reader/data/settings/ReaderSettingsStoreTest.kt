package orilumn.reader.data.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Store round-trip tests (regression for the "global settings lost on close" bug):
 * [ReaderSettingsStore.save] used to pass its own `.tmp` path to the atomic writer, so the final
 * `reader.json` was never produced — every global setting (brightness, autoContinue, ...) silently
 * fell back to defaults on the next launch. These tests lock the real on-disk layout:
 * save() must leave a loadable `reader.json` (a leftover `.tmp` is not a saved store).
 */
class ReaderSettingsStoreTest {

    private fun tempDir(): String {
        val dir = File(System.getProperty("java.io.tmpdir"), "orilumn-settings-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir.absolutePath
    }

    @Test
    fun `save then load round-trips global settings via the final file`() {
        val dir = tempDir()
        val store = ReaderSettingsStore(dir)
        val custom = ReaderSettings.DEFAULT.copy(
            brightness = 42,
            brightnessFollowSystem = false,
            brightnessOffset = 7,
            eyeProtectionLevel = 60,
            autoContinue = true,
            pageNum = true,
            fontSize = 21,
            scheme = "night",
        )
        store.save(custom)

        // The target file must exist after save (the regression wrote only reader.json.tmp).
        assertTrue("reader.json should exist after save", File(dir, "reader.json").exists())
        assertEquals("no leftover .tmp from a successful save", false, File(dir, "reader.json.tmp").exists())

        // A fresh store instance (re-opened "later") must see the persisted global values.
        val reread = ReaderSettingsStore(dir).load()
        assertEquals(custom, reread)
    }

    @Test
    fun `brightness-and-continue are the global fields lost by the bug`() {
        val dir = tempDir()
        ReaderSettingsStore(dir).save(
            ReaderSettings.DEFAULT.copy(brightness = 33, brightnessFollowSystem = false, autoContinue = true),
        )
        val loaded = ReaderSettingsStore(dir).load()
        assertEquals(33, loaded.brightness)
        assertEquals(false, loaded.brightnessFollowSystem)
        assertEquals(true, loaded.autoContinue)
    }

    @Test
    fun `missing file still falls back to default`() {
        val loaded = ReaderSettingsStore(tempDir()).load()
        assertEquals(ReaderSettings.DEFAULT, loaded)
    }

    @Test
    fun `reset clears the store back to defaults`() {
        val dir = tempDir()
        val store = ReaderSettingsStore(dir)
        store.save(ReaderSettings.DEFAULT.copy(autoContinue = true, brightness = 10))
        assertTrue(File(dir, "reader.json").exists())
        store.reset()
        assertFalse(File(dir, "reader.json").exists())
        assertEquals(ReaderSettings.DEFAULT, ReaderSettingsStore(dir).load())
    }
}