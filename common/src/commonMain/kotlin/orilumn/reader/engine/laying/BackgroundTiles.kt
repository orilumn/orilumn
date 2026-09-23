package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BackgroundPosition
import orilumn.reader.engine.css.BackgroundRepeat

/**
 * P3-b: 背景图平铺几何（纯数据处理，无字符流/分页影响）。
 *
 * CSS `background-repeat/position` 的盒内贴图位置：`position` 按规范相对 `box - image`
 * 解算（`x = l + xPct * (bw - imgW) + xPx`，`%` 在图大于盒时为负偏移，即按规范裁掉上/左），
 * `repeat` 轴整幅平铺（定位决定平铺原点：`off = (anchor - edge) mod tile`，正余数）。
 *
 * 不在范围（文档近似）：`background-size`（恒 1:1）、`background-attachment`、
 * `background-origin/clip`（恒 border-box，即本盒几何）、多重背景（只取首层）。
 */
object BackgroundTiles {

    /** 一幅贴图在盒内的目标矩形（含盒左上原点的绝对坐标；调用方裁到盒内/圆角）。 */
    data class Tile(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun tiles(
        boxLeft: Int,
        boxTop: Int,
        boxRight: Int,
        boxBottom: Int,
        imgW: Int,
        imgH: Int,
        repeat: BackgroundRepeat,
        position: BackgroundPosition = BackgroundPosition(),
    ): List<Tile> {
        val bw = boxRight - boxLeft
        val bh = boxBottom - boxTop
        if (bw <= 0 || bh <= 0 || imgW <= 0 || imgH <= 0) return emptyList()
        // 定位锚点（规范式；图大于盒时 pct 项为负，即裁上/左）。
        val ax = boxLeft + (position.xPct * (bw - imgW) + position.xPx).toInt()
        val ay = boxTop + (position.yPct * (bh - imgH) + position.yPx).toInt()
        return when (repeat) {
            BackgroundRepeat.NO_REPEAT -> listOf(Tile(ax, ay, ax + imgW, ay + imgH))
            BackgroundRepeat.REPEAT_X -> {
                val startX = boxLeft + posMod(ax - boxLeft, imgW) - imgW
                val out = ArrayList<Tile>()
                var x = startX
                while (x < boxRight) {
                    out.add(Tile(x, ay, x + imgW, ay + imgH))
                    x += imgW
                }
                out
            }
            BackgroundRepeat.REPEAT_Y -> {
                val startY = boxTop + posMod(ay - boxTop, imgH) - imgH
                val out = ArrayList<Tile>()
                var y = startY
                while (y < boxBottom) {
                    out.add(Tile(ax, y, ax + imgW, y + imgH))
                    y += imgH
                }
                out
            }
            BackgroundRepeat.REPEAT -> {
                val startX = boxLeft + posMod(ax - boxLeft, imgW) - imgW
                val startY = boxTop + posMod(ay - boxTop, imgH) - imgH
                val out = ArrayList<Tile>()
                var y = startY
                while (y < boxBottom) {
                    var x = startX
                    while (x < boxRight) {
                        out.add(Tile(x, y, x + imgW, y + imgH))
                        x += imgW
                    }
                    y += imgH
                }
                out
            }
        }
    }

    /** 正余数（定位偏移为负时仍给出 0..m 的原点偏移）。 */
    private fun posMod(a: Int, m: Int): Int = ((a % m) + m) % m
}
