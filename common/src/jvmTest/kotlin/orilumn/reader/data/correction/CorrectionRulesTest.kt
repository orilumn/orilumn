package orilumn.reader.data.correction

import orilumn.reader.data.assets.BundledAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纠错词表的匹配口径：整段命中才给候选；同音族互指，定向规则不给反方向。
 */
class CorrectionRulesTest {

    /** 候选的提交文字（点下去写进正文的那份）。 */
    private fun values(selection: String, extraRules: List<String> = emptyList()): List<String> =
        CorrectionRules.candidatesFor(selection, extraRules).map { it.value }

    /** 候选的显示形态（操作栏里看到的那份）。 */
    private fun labels(selection: String, extraRules: List<String> = emptyList()): List<String> =
        CorrectionRules.candidatesFor(selection, extraRules).map { it.label }

    @Test
    fun `bundled dictionary loads from resources`() {
        // 词表是随包资源（不在代码里硬编码）：加载不到时这里会是空表。
        assertEquals(listOf("地", "得"), values("的"))
        assertEquals(listOf("再"), values("在"))
        assertEquals(listOf("其他"), values("其它"))
    }

    @Test
    fun `group is symmetric but directional rule is not`() {
        assertEquals(listOf("的", "得"), values("地"))
        // 定向规则只认错写那一侧：正确写法不该反过来推荐错写。
        assertTrue(values("其他").isEmpty())
        assertTrue(values("按捺").isEmpty())
        assertEquals(listOf("按捺"), values("按耐"))
    }

    @Test
    fun `bracket gives quote variants and bare text`() {
        val expected = listOf("“你好”", "‘你好’", "你好")
        assertEquals(expected, values("「你好」"))
        assertEquals(expected, values("『你好』"))
        assertEquals(expected, values("【你好】"))
        assertEquals(expected, values("\"你好\""))
        // 短内容原样显示：三条候选一眼能看出换成了哪种引号。
        assertEquals(expected, labels("「你好」"))
    }

    @Test
    fun `bracket keeps full label and offers collapsed label`() {
        val selection = "「这是一段很长的内容」"
        val candidates = CorrectionRules.candidatesFor(selection)
        assertEquals(
            listOf("“这是一段很长的内容”", "‘这是一段很长的内容’", "这是一段很长的内容"),
            candidates.map { it.value },
        )
        // 完整显示形态就是提交的文字；一行放不下时界面改用中间收成省略号的那份。
        assertEquals(candidates.map { it.value }, candidates.map { it.label })
        assertEquals(
            listOf("\u201c\u2026\u2026\u201d", "\u2018\u2026\u2026\u2019", "\u2026\u2026"),
            candidates.map { it.collapsedLabel },
        )
    }

    @Test
    fun `bracket handles regex metacharacters`() {
        assertEquals(
            listOf("“甲”", "‘甲’", "甲"),
            values("[甲]", listOf("bracket [ ]")),
        )
        assertEquals(
            listOf("“甲”", "‘甲’", "甲"),
            values("(甲)", listOf("bracket ( )")),
        )
    }

    @Test
    fun `whole selection only`() {
        assertTrue(values("").isEmpty())
        assertTrue(values("「你好」。").isEmpty())
        assertTrue(values("你好").isEmpty())
        assertTrue(values("的的").isEmpty())
        assertTrue(values("地 得").isEmpty())
    }

    @Test
    fun `extra rules are appended after bundled ones`() {
        val extra = listOf("word 的 => 底", "regex (.)(.) => \$2\$1")
        assertEquals(listOf("地", "得", "底"), values("的", extra))
        assertEquals(listOf("乙甲"), values("甲乙", extra))
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
        assertEquals("乙", rules[0].apply("甲")?.value)
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
