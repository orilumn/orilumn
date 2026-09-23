package orilumn.reader.data.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationTest {

    @Test
    fun `章首个位置的全书进度为 0`() {
        assertEquals(0.0, Location(0).bookProgress(3), 1e-9)
    }

    @Test
    fun `第二章章首在全书三分之一处`() {
        assertEquals(1.0 / 3.0, Location(1).bookProgress(3), 1e-9)
    }

    @Test
    fun `章内进度参与全书进度折算`() {
        // chapter 0 at 50% → (0+0.5)/3
        assertEquals(0.5 / 3.0, Location(0, progress = 0.5).bookProgress(3), 1e-9)
    }

    @Test
    fun `chapter 越界钳制到末章起点`() {
        assertEquals(2.0 / 3.0, Location(99).bookProgress(3), 1e-9)
    }

    @Test
    fun `progress 越界自动钳制`() {
        assertEquals(1.0, Location(2, progress = 5.0).bookProgress(3), 1e-9)
        assertEquals(2.0 / 3.0, Location(2, progress = -1.0).bookProgress(3), 1e-9)
    }

    @Test
    fun `章节数为 0 时进度为 0`() {
        assertEquals(0.0, Location(0).bookProgress(0), 1e-9)
    }

    @Test
    fun `chapter 为负抛异常`() {
        try {
            Location(-1)
            throw AssertionError("应抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `全书进度反推位置`() {
        val loc = ProgressConverter.locationFromBookProgress(0.5, 3)
        requireNotNull(loc)
        assertEquals(1, loc.chapter)
        assertEquals(0.5, loc.progress, 1e-9)
    }

    @Test
    fun `全书进度反推对首尾的钳制`() {
        val first = ProgressConverter.locationFromBookProgress(0.0, 3)
        requireNotNull(first)
        assertEquals(0, first.chapter)
        assertEquals(0.0, first.progress, 1e-9)

        // 100% should land at the end of the last chapter
        val last = ProgressConverter.locationFromBookProgress(1.0, 3)
        requireNotNull(last)
        assertEquals(2, last.chapter)
        assertEquals(1.0, last.progress, 1e-9)
    }

    @Test
    fun `章节数非法反推返回 null`() {
        assertNull(ProgressConverter.locationFromBookProgress(0.5, 0))
    }

    @Test
    fun `换算往返一致`() {
        val original = Location(1, progress = 0.3)
        val back = ProgressConverter.locationFromBookProgress(original.bookProgress(3), 3)
        requireNotNull(back)
        assertEquals(original.chapter, back.chapter)
        assertEquals(original.progress, back.progress, 1e-9)
    }
}