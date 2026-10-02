package orilumn.reader.engine.skia

import orilumn.reader.engine.css.TextAlign
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **代理对（supplementary plane）必须当一个字位**（渲染层·几何测量 + 落墨）。
 *
 * ## 这个缺陷是怎么被真机逮到的
 *
 * 真机报「代码块里的特殊字符要么不显示、要么显示乱码」。第一轮按「字体缺字形」修（补了保底面表），
 * 真机**仍乱码**。第二轮加了诊断日志才看清：日志里出现
 * `无字体的码本 U+D83D` / `U+DC4D` —— 这是**一对代理的上下半**，不是两个字符。
 *
 * ## 根因：取码本有**两份实现**，且落墨那份是错的
 *
 * - 量宽侧 [SkiaRunMeasurer] 用 `cpAt` **合并**了代理对 ⇒ 该字位拿到**正确**宽度；
 * - 落墨侧 `LineWindowDrawer.drawGlyphPass` 用 `line.text[i].code` ⇒ 拿到**两个孤立代理**
 *   ⇒ 没有任何字体有它们的字形 ⇒ **画出来是两个豆腐块**。
 *
 * 顺带还有一个更阴的：量宽侧把**孤立低位代理也当成独立码本**去查字体，查不到 ⇒ 记 **notdef 宽**
 * （约 0.6em）⇒ **行凭空宽出一个字位，后续字位全体右移**。
 * 书里 emoji 少 ⇒ 症状是「只有没几个字符乱码」，极容易误判成字体问题（我第一轮就误判了）。
 *
 * 修法不是「在落墨侧也合并一下」（那只是把两份实现凑成一致，下次照样漂移），
 * 而是**取码本收成单源** [codePointAt]，量与画都必须用它。
 */
class SurrogatePairLayoutTest {

    /** U+1F642 稍息脸（代理对 D83D DE42）。真书里出现在代码块的注释与输出里。 */
    private val emoji = "🙂"

    @Test
    fun `代理对必须合并成一个码本并报告占两个码元`() {
        val s = "a${emoji}b"
        val at = codePointAt(s, 1)
        assertEquals("U+1F642", "U+%04X".format(at.cp))
        assertEquals("代理对必须报告占 2 个码元", 2, at.units)
        assertTrue("高位代理位置不是「代理尾」", !at.isLowSurrogateTail)
    }

    @Test
    fun `低位代理必须被标记成代理尾而不是独立字位`() {
        val s = "a${emoji}b"
        val at = codePointAt(s, 2)
        assertTrue("低位代理必须标记 isLowSurrogateTail，否则会被当成一个字位去查字体", at.isLowSurrogateTail)
        assertEquals("代理尾不得再触发一次合并", 1, at.units)
    }

    @Test
    fun `普通字符与落单的代理不得被误判`() {
        val plain = codePointAt("A", 0)
        assertEquals('A'.code, plain.cp)
        assertEquals(1, plain.units)
        assertTrue(!plain.isLowSurrogateTail)
        // 落单的高位代理（后面不是低位代理）：只能按自己那个码元走，不能越界读。
        val lone = codePointAt("a\uD83D", 1)
        assertEquals(0xD83D, lone.cp)
        assertEquals(1, lone.units)
        assertTrue(!lone.isLowSurrogateTail)
    }

    /**
     * **量宽侧**：代理对只占**一个**字位，低位代理那个槽位**严格 0 宽**。
     *
     * 修复前低位代理槽位拿到的是 **notdef 宽**（约 0.6em）—— 行凭空宽出一个字位。
     * 这是「量画位置一起错」的那一半，断言必须落在**数值**上，不能只断言「不抛异常」。
     */
    @Test
    fun `代理对的低位代理槽位必须零宽`() {
        val m = SkiaRunMeasurer()
        val s = "a${emoji}b"
        val adv = m.advances(s, 24f, 0f, "p", listOf("sans-serif"), 400, false, false)
        assertEquals("advances 必须逐 UTF-16 码元给一个槽位", s.length, adv.size)
        assertEquals("代理对的低位代理槽位必须严格 0 宽（不是 notdef 宽）", 0f, adv[2], 0f)
        assertTrue("emoji 本身必须有正宽度", adv[1] > 0f)
        // 关键量：行宽必须等于「各字位之和」，没有多出 notdef 幽灵槽位。
        val sum = (0 until 3).sumOf { adv[it].toDouble() }
        assertTrue(
            "a+emoji 的宽度和应等于前三个槽位之和（got=$sum a=${adv[0]} emoji=${adv[1]} tail=${adv[2]}）",
            sum <= adv[0] + adv[1] + 1e-3,
        )
    }

    /**
     * **绘制层**：落墨取到的码本必须是**合并后的码本**，绝不能是孤立代理。
     *
     * ## 为什么不用像素判据（第一版就是这么栽的）
     *
     * 我第一版写的是像素锁，判据取「emoji 区间的墨左右不对称 —— 两个豆腐块是左右对称的」。
     * 实测（MUT-F：落墨退回 `text[i].code` + `substring(i, i+1)`）该锁**恒绿**：
     * `adv=[21.34, 38.64, 0.0, 22.02] x0=21 x1=59 sym=false ink=464` ——
     * 因为 skiko 把**孤立代理编码成 `?`** 画出来，`?` 墨迹存在且左右不对称，
     * 「豆腐块对称」这个前提**在现场根本不成立**。一把**假绿锁**，比没有锁更坏（教训 ⑫）。
     *
     * 而且这条路径压根不需要像素：要钉的契约只是「画出去的是哪个码本」，
     * 绕到像素上去猜反而把判据搞脆。用 [LineWindowDrawer.onGlyphCp] 直接看它取到了什么。
     */
    @Test
    fun `落墨必须整对取码本不得取到孤立代理`() {
        val text = "a${emoji}b"
        val drawer = LineWindowDrawer()
        val drawn = mutableListOf<Pair<Int, Int>>()
        drawer.onGlyphCp = { cp, units -> drawn.add(cp to units) }
        val width = 240
        val bmp = Bitmap()
        bmp.allocN32Pixels(width, 120, true)
        val canvas = Canvas(bmp)
        canvas.clear(Color.WHITE)
        drawer.drawLines(
            canvas, 0f,
            listOf(
                DrawLine(
                    text = text, range = 0..text.length - 1, yTop = 0, yBottom = 100,
                    alignment = TextAlign.LEFT, fontSizePx = 42.18f, lineHeightRatio = 2f,
                    tag = "p", families = listOf("STSong", "serif"), weight = 400,
                    italic = false, monospace = false, letterSpacingEm = 0f,
                    lineWidthPx = width, hyphenAtEnd = false,
                ),
            ), null,
        )
        assertTrue("本行必须有字被画出去（否则这锁什么也没钉住）", drawn.isNotEmpty())
        val lone = drawn.filter { (cp, _) -> cp in 0xD800..0xDFFF }
        assertTrue(
            "落墨取到了孤立代理 ⇒ 代理对被拆开画了（真机症状：emoji 显示乱码/豆腐块）。画到：$lone",
            lone.isEmpty(),
        )
        val target = emoji.codePointAt(0)
        assertTrue(
            "落墨必须画到合并后的码本 U+%04X（drawn=%s）".format(target, drawn.map { "U+%04X/%d".format(it.first, it.second) }),
            drawn.any { (cp, units) -> cp == target && units == 2 },
        )
        // 低位代理尾不得单独成为一次绘制（它已随高位代理整对画过）。
        assertEquals(
            "低位代理尾不得单独落墨",
            0,
            drawn.count { (cp, _) -> cp == text[2].code },
        )
    }
}
