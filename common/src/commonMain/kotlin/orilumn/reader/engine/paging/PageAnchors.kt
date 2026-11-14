package orilumn.reader.engine.paging

import kotlin.math.abs

/**
 * 选「页首最接近 [char] 的那一页」的下标；[slices] 为空回 -1（同分取靠前的一页）。
 *
 * 为什么需要它：正常视图与审阅视图是**两套各自独立**的分页（页界不必对齐，也不该互相比对）。
 * 按「包含 [char] 的那一页」落位时，只要旧页首落在新页末尾一点点，读者就会被送回**上一页**；
 * 按「页首最接近」选页拿到的才是同一处内容，两个方向、两套页界都一样稳。
 *
 * 只用于**换空间**后的重新落位（正常 ↔ 审阅），不要拿它当普通跳转用：普通跳转的锚点可以是页内
 * 任意字，按「最接近的页首」会跳过锚点所在页的大半页。
 */
fun pageNearestStart(slices: List<PageSlice>, char: Int): Int {
    if (slices.isEmpty()) return -1
    var best = 0
    var bestDistance = abs(slices[0].charStart - char)
    for (i in 1 until slices.size) {
        val d = abs(slices[i].charStart - char)
        if (d < bestDistance) {
            best = i
            bestDistance = d
        }
    }
    return best
}
