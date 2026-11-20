package orilumn.reader.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 面板读数合并：`bookStyleReadings` 量出的四个原书读数必须**一起**进面板状态。
 *
 * 漏项的症状是「书里有段距 / 字距，滑块却显示 0，一拖到 0 反而把书的值真的压成 0」。
 */
class SettingsProbeMergeTest {

    private fun measured() = ReaderSettings.DEFAULT.copy(
        probeIndent = 2.0,
        probeLineSpacing = 1.7,
        probeParagraphSpacing = 0.6,
        probeLetterSpacing = 0.05,
    )

    @Test
    fun `四个原书读数一起合并`() {
        val merged = ReaderSettings.DEFAULT.withStyleProbesFrom(measured())
        assertEquals(2.0, merged.probeIndent!!, 0.0)
        assertEquals(1.7, merged.probeLineSpacing!!, 0.0)
        assertEquals(0.6, merged.probeParagraphSpacing!!, 0.0)
        assertEquals(0.05, merged.probeLetterSpacing!!, 0.0)
    }

    @Test
    fun `读者已拖过的显式值不被读数覆盖`() {
        // 段间距显式 0（明确「不要段距」）不能被原书读数 0.6 改回去。
        val reader = ReaderSettings.DEFAULT.copy(paragraphSpacing = 0.0, firstLineIndent = 0.0)
        val merged = reader.withStyleProbesFrom(measured())
        assertEquals(0.0, merged.paragraphSpacing!!, 0.0)
        assertEquals(0.0, merged.firstLineIndent!!, 0.0)
        // 读数本身照常写入，供「未配置项」显示。
        assertEquals(0.6, merged.probeParagraphSpacing!!, 0.0)
        assertEquals(2.0, merged.probeIndent!!, 0.0)
    }

    @Test
    fun `测量失败（全 null）不会抹掉已有读数`() {
        // `bookStyleReadings` 失败时返回的 base 可能带着旧读数：合并即原样保留。
        val base = ReaderSettings.DEFAULT.withStyleProbesFrom(measured())
        val merged = base.withStyleProbesFrom(base)
        assertEquals(0.6, merged.probeParagraphSpacing!!, 0.0)
        assertNull(merged.paragraphSpacing)
    }
}
