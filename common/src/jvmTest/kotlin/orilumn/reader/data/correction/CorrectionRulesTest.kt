package orilumn.reader.data.correction

import orilumn.reader.data.assets.BundledAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纠错词表的匹配口径：整段命中才给候选；同音族互指，定向规则不给反方向。
 */
class CorrectionRulesTest {

    @Test
    fun `bundled dictionary loads from resources`() {
        // 词表是随包资源（不在代码里硬编码）：加载不到时这里会是空表。
        assertEquals(listOf("地", "得"), CorrectionRules.candidatesFor("的"))
        assertEquals(listOf("再"), CorrectionRules.candidatesFor("在"))
        assertEquals(listOf("其他"), CorrectionRules.candidatesFor("其它"))
    }

    @Test
    fun `group is symmetric but directional rule is not`() {
        assertEquals(listOf("的", "得"), CorrectionRules.candidatesFor("地"))
        // 定向规则只认错写那一侧：正确写法不该反过来推荐错写。
        assertTrue(CorrectionRules.candidatesFor("其他").isEmpty())
        assertTrue(CorrectionRules.candidatesFor("按捺").isEmpty())
        assertEquals(listOf("按捺"), CorrectionRules.candidatesFor("按耐"))
    }

    @Test
    fun `quote rules rewrite both sides at once`() {
        assertEquals(listOf("“你好”"), CorrectionRules.candidatesFor("「你好」"))
        assertEquals(listOf("‘你好’"), CorrectionRules.candidatesFor("『你好』"))
        assertEquals(listOf("“你好”"), CorrectionRules.candidatesFor("\"你好\""))
    }

    @Test
    fun `whole selection only`() {
        assertTrue(CorrectionRules.candidatesFor("").isEmpty())
        assertTrue(CorrectionRules.candidatesFor("「你好」。").isEmpty())
        assertTrue(CorrectionRules.candidatesFor("你好").isEmpty())
        assertTrue(CorrectionRules.candidatesFor("的的").isEmpty())
        assertTrue(CorrectionRules.candidatesFor("地 得").isEmpty())
    }

    @Test
    fun `extra rules are appended after bundled ones`() {
        val extra = listOf("word 的 => 底", "regex (.)(.) => \$2\$1")
        assertEquals(listOf("地", "得", "底"), CorrectionRules.candidatesFor("的", extra))
        assertEquals(listOf("乙甲"), CorrectionRules.candidatesFor("甲乙", extra))
    }

    @Test
    fun `bad lines are skipped without killing the table`() {
        val rules = parseRules(
            listOf(
                "regex ([ => x", // 无效正则
                "随便一行没有箭头",
                "group 只有一个词",
                "word 甲 => 乙",
                "# 注释",
                "",
            ),
        )
        assertEquals(1, rules.size)
        assertEquals("乙", rules[0].apply("甲"))
    }

    @Test
    fun `replacement expands groups and keeps unknown references`() {
        val match = Regex("(.+)/(.+)").matchEntire("甲/乙")!!
        assertEquals("乙-甲 \$ 甲", expandReplacement("\$2-\$1 \$\$ \${1}", match))
        assertEquals("\$9", expandReplacement("\$9", match))
        assertEquals("\$x", expandReplacement("\$x", match))
    }

    @Test
    fun `bundled rules never map a word to itself`() {
        val text = BundledAssets.text(CorrectionRules.FILE_NAME)
        assertTrue("缺少随包词表 ${CorrectionRules.FILE_NAME}", text != null)
        val rules = parseRules(text!!.lineSequence().toList())
        assertTrue("随包词表解析为空", rules.isNotEmpty())
        for (rule in rules) {
            if (rule is WordRule) {
                assertTrue("词表里不该有 ${rule.word} => ${rule.replacement}", rule.word != rule.replacement)
            }
        }
    }
}
