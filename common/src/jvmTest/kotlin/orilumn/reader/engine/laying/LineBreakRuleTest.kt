package orilumn.reader.engine.laying

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文断行**禁则规则**回归（锁定 [KinsokuRules.ZH_EN] 三张表的逐字符行为）。
 *
 * 规则来源是双向实测，不是「我记得的期望值」：
 *  - Chrome `width: min-content` 68 串样本标定（原表来源）
 *  - Skia 实际断点双向矩阵（T2/T2b/T2c，见 `docs/自建断行引擎-测试计划.md`）
 *
 * ⚠ **本测试锁的是「表的行为」，不是「表与 Skia 一致」**。后者要跑真实字体栈的等价性探针
 * （方案 S3 的 T1 复跑，判据假阴性 = 0），本测试无法替代。
 *
 * ⚠ **期望值一律硬编码**。若改成从 [KinsokuRules.ZH_EN] 读出来算期望，本测试即恒真、
 * 变成「用表验证表」。表改了这里必须手工改。
 *
 * 取向约定（`X` 为被测字符，`填` = U+586B，一个宽字）：
 *  - `填X` = X **落行首** → 由 `noBreakBefore` 管
 *  - `X填` = X **落行尾** → 由 `noBreakAfter` 管
 */
class LineBreakRuleTest {

    private fun canBreakIn(text: String, i: Int): Boolean = isBreakOpportunity(text, i)

    // ---- 基线自检（教训 ⑶：探针/测试必须先自检基线，否则整份数据可能一直是错的）----

    @Test
    fun `baseline - wide chars break between each other`() {
        // 这三条是全测试的地基。若它们失败，说明下面的断言全部不可信。
        assertTrue("宽字对宽字应可断", canBreakIn("填充", 1))
        assertTrue("窄字对宽字应可断", canBreakIn("a填", 1))
        assertTrue("宽字对窄字应可断", canBreakIn("填a", 1))
        assertTrue("文档空白处处可断", canBreakIn("a b", 1))
    }

    @Test
    fun `baseline - narrow Latin token has no internal break`() {
        // 拉丁 token 只在空白/连字符处断，这是英文禁则的基础语义。
        assertFalse(canBreakIn("algorithm", 3))
        assertFalse(canBreakIn("and/or", 3))
    }

    // ---- A. noBreakBefore：X 不得落行首（`填X` 禁断）----

    @Test
    fun `no break before ASCII closers and punctuation`() {
        // ASCII 侧（英文标点）——T1 实测这部分本来就无差异，此处锁住防退化。
        for (c in listOf(')', ']', '}', '!', '?', ',', '.', ':', ';', '/')) {
            assertFalse("`填|$c` 应禁断（$c 不得落行首）", canBreakIn("填$c", 1))
            assertTrue("`$c|填` 应可断（$c 落行尾时行首是宽字）", canBreakIn("${c}填", 1))
        }
    }

    @Test
    fun `no break before CJK closers and punctuation`() {
        for (c in listOf(
            '、', '。', '，', '．', '：', '；', '！', '？',            // 中点/句读
            '）', '】', '』', '」', '〉', '》', '〕', '］', '｝', '｣', // 闭合括号（全/半角）
        )) {
            assertFalse("`填|$c` 应禁断", canBreakIn("填$c", 1))
        }
    }

    @Test
    fun `no break before hyphens and dashes and non-starters`() {
        for (c in listOf('-', '‐', '‑', '–', '—')) {
            assertFalse("`填|$c` 应禁断（LB21 连字符类不得落行首）", canBreakIn("填$c", 1))
        }
        for (c in listOf('・', '…', '‥', 'ゝ', 'ゞ', '々')) {
            assertFalse("`填|$c` 应禁断（非起始类）", canBreakIn("填$c", 1))
        }
    }

    @Test
    fun `no break before closing quotes and unit signs`() {
        // T2 补表：`”‘ ’〞` 与 `%℃`
        for (c in listOf('”', '‘', '’', '〞', '%', '℃')) {
            assertFalse("`填|$c` 应禁断", canBreakIn("填$c", 1))
        }
    }

    @Test
    fun `ASCII question mark is in no-break-before on purpose`() {
        // 本文件原 KDoc 一直把 `?` 写进 ASCII 闭合类（`) ] } ! ? , . : ; /`），表里却漏了 —— 转写漏字。
        // 收进来后与同族 `!` 和全角 `？` 一致；`?` 属 UAX#14 的 EX 类，本就不该落行首。
        assertFalse("`填|?` 禁断（ASCII ? 不得落行首）", canBreakIn("填?", 1))
        assertFalse("`word|?` 禁断", canBreakIn("word?", 4))
        // 已知代价：S1 的 Skia 探针实测 `填|?` 是**可断**的（疑似 Skia 按旧版 LB13 字面清单实现）。
        // 我们比 Skia 严 = 假阳性，与「—」同性质，有意接受。S3 的 T1 复跑须显式列为已知例外。
        assertTrue("`?|填` 可断（`?` 落行尾正常）", canBreakIn("?填", 1))
    }

    @Test
    fun `U+3006 is removed from no-break-before`() {
        // T2：Skia 允许 填|〆，我们原先禁断 —— T2 补表把它移出。
        assertTrue("`填|〆` 应可断（〆 已从 noBreakBefore 移出）", canBreakIn("填〆", 1))
    }

    @Test
    fun `em dash stays in no-break-before on purpose`() {
        // T2c 已否决把「—」移出：Skia 的 LB21b（—|— 禁断）与「— 前后可断」冲突，
        // 移出反而放行 `—|—` 制造**假阴性**。代价是 `填|—` 上我们比 Skia 严（假阳性），有意接受。
        assertFalse("`填|—` 禁断是**有意为之**，不要「顺手修好」", canBreakIn("填—", 1))
        assertFalse("`—|—` 必须禁断（LB21b）", canBreakIn("——", 1))
    }

    // ---- B. noBreakAfter：X 不得落行尾（`X填` 禁断）----

    @Test
    fun `no break after ASCII and CJK openers`() {
        for (c in listOf('(', '[', '{')) {
            assertFalse("`$c|填` 应禁断", canBreakIn("${c}填", 1))
            assertTrue("`填|$c` 应可断", canBreakIn("填$c", 1))
        }
        for (c in listOf('（', '【', '『', '「', '〈', '《', '〔', '［', '｛', '｢')) {
            assertFalse("`$c|填` 应禁断", canBreakIn("${c}填", 1))
        }
        assertFalse("`\"|填` 应禁断（ASCII 直引号，LB14）", canBreakIn("\"填", 1))
    }

    @Test
    fun `no break after opening quotes - T2 backfill`() {
        // T2 补表：`“‘’` 进 noBreakAfter
        for (c in listOf('“', '‘', '’')) {
            assertFalse("`$c|填` 应禁断", canBreakIn("${c}填", 1))
        }
    }

    @Test
    fun `opening and closing quotes glue on both sides`() {
        // `‘`/`’` 同时在两张表里 → 两侧都粘。实测的 Skia 行为，不是笔误。
        for (c in listOf('‘', '’')) {
            assertFalse("`填|$c` 禁断", canBreakIn("填$c", 1))
            assertFalse("`$c|填` 禁断", canBreakIn("${c}填", 1))
        }
    }

    // ---- C. breakAfter：X 之后必可断，**即使两侧都不宽**（英文连字符）----

    @Test
    fun `break after hyphen even when neither side is wide`() {
        for (c in listOf('-', '‐', '‑')) {
            assertTrue("`$c|a` 应可断（两侧都不宽，靠 breakAfter 放行）", canBreakIn("$c" + "a", 1))
        }
        assertTrue("`a-b` 断在连字符**后**", canBreakIn("a-b", 2))
        assertFalse("`a|-b` 禁断（连字符不落行首，LB21）", canBreakIn("a-b", 1))
        assertTrue("`Wi-Fi` 断在连字符后", canBreakIn("Wi-Fi", 3))
    }

    @Test
    fun `break after ellipsis even when neither side is wide - T2c backfill`() {
        // T2c 的核心新增：`…‥` 原在 noBreakBefore（管 `填|…`），但两侧都不宽时 `…|a` 被禁断，
        // 补进 breakAfter 才放行 —— 即 IN 类「后」可断。
        for (c in listOf('…', '‥')) {
            assertTrue("`$c|a` 应可断（T2c 补表）", canBreakIn("$c" + "a", 1))
            assertFalse("`填|$c` 仍禁断（noBreakBefore 优先于 breakAfter）", canBreakIn("填$c", 1))
        }
    }

    @Test
    fun `negative control - narrow char in no table does not break`() {
        // C 组的对照：证明上面的「可断」真的是 breakAfter 在起作用，而不是「窄字之间本来就可断」。
        for (c in listOf('~', '@', '#')) {
            assertFalse("`$c|a` 应禁断（不在任何表里且两侧都不宽）", canBreakIn("$c" + "a", 1))
            assertFalse("`a|$c` 应禁断", canBreakIn("a" + c, 1))
        }
    }

    // ---- D. 英文专项（§0.1：中英合一表，英文部分只补 4 个引号字符）----

    @Test
    fun `english - curly apostrophe keeps contractions whole`() {
        // don’t：`’` 两侧都粘（既不落行首也不落行尾）
        assertFalse("`a|’` 禁断（apostrophe 不落行首）", canBreakIn("a’", 1))
        assertFalse("`’|t` 禁断（apostrophe 不落行尾）", canBreakIn("’t", 1))
        // 词内其余位置也不断（窄字 + 窄字，无表命中）
        assertFalse(canBreakIn("don’t", 2))
        assertFalse(canBreakIn("don’t", 3))
    }

    @Test
    fun `english - curly double quotes glue both ways`() {
        assertFalse("`“|q` 禁断（开引号不落行尾）", canBreakIn("“q", 1))
        assertFalse("`q|”` 禁断（闭引号不落行首）", canBreakIn("q”", 1))
        // 开引号本身可以落行首
        assertTrue(canBreakIn("said “", 4))
    }

    @Test
    fun `english - ASCII punctuation never lands at line start`() {
        for (c in listOf('.', ',', ';', ':', '!', '?', ')')) {
            assertFalse("`word|$c` 应禁断", canBreakIn("word$c", 4))
        }
    }

    @Test
    fun `english - URL-like token stays whole`() {
        // 斜杠/点/连字符在 noBreakBefore 或 breakAfter，两侧都不宽 → URL 内部整体不可断，
        // 交给 R1 core（无可用断点且放不下时任意处断开）处置。
        val url = "https://a.example.com/x"
        for (i in 1 until url.length) assertFalse("URL 第 $i 位应不可断", canBreakIn(url, i))
    }

    // ---- E. 成串标点（T2b：省略号/破折号内部不可拆）----

    @Test
    fun `runs of CJK punctuation are not split`() {
        assertFalse("`…|…` 禁断", canBreakIn("……", 1))
        assertFalse("`—|—` 禁断", canBreakIn("——", 1))
        assertFalse("`。|」` 禁断", canBreakIn("。」", 1))
        assertFalse("`。|”` 禁断", canBreakIn("。”", 1))
    }

    // ---- F. 代理对不被劈开 ----

    @Test
    fun `surrogate pair is never split`() {
        val pair = "\uD840\uDC00" // 𠀀 CJK 扩展 B
        assertFalse("代理对内部不得断开", canBreakIn(pair, 1))
        assertTrue("代理对与相邻宽字之间可断", canBreakIn("填${pair}", 1))
    }

    // ---- G. 结构不变量（表与表之间不能自相矛盾）----

    @Test
    fun `no char is both no-break-after and break-after`() {
        // 自相矛盾：一个字符若既「必须不断在后面」又「必须断在后面」，判定结果依赖求值顺序。
        val r = KinsokuRules.ZH_EN
        val overlap = r.noBreakAfter.filter { r.breakAfter.indexOf(it) >= 0 }
        assertTrue("noBreakAfter ∩ breakAfter 应为空，实际 = $overlap", overlap.isEmpty())
    }

    @Test
    fun `no duplicates inside each table`() {
        val r = KinsokuRules.ZH_EN
        for ((name, table) in listOf(
            "noBreakBefore" to r.noBreakBefore,
            "noBreakAfter" to r.noBreakAfter,
            "breakAfter" to r.breakAfter,
        )) {
            assertEquals(name, table.length, table.toSet().size)
        }
    }

    private fun assertEquals(what: String, expected: Int, actual: Int) =
        org.junit.Assert.assertEquals("$what 不应有重复字符", expected, actual)
}