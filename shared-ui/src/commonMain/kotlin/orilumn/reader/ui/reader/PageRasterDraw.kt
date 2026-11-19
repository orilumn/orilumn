package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 整页栅格的贴图缓存（用户层·渲染缝）：槽里那张图换了才重建平台侧画柄。
 *
 * 存在的理由是一次实测：600ms 的卷曲动画挂在平板口径的 60fps 上跑不下来，落盘日志里
 * 每帧间隔 40～150ms，进度一帧从 0.07 跳到 0.59 —— 就是读者说的「帧数太少、跳跃」。
 * 根因是 Compose 桌面的 `drawImage(ImageBitmap)` 每次调用都 `Image.makeFromBitmap`：
 * 一整页（版心 1224×1692，约 8MB）等于**每帧重新建一次纹理**，翻页时一帧要贴
 * 正面页 + 正面覆盖层 + 露出页 + 露出页覆盖层四张。把「建一次」挪到图换的时候，
 * 帧间隔立刻回到 16～20ms。
 *
 * 缓存粒度是**一张图一份**：跟着持有它的页槽同寿命，图换了旧画柄跟着丢掉，
 * 不会把用过的页图留在内存里。
 */
internal class RasterDrawCache {
    /** 画柄对应的那张位图（判等用实例，不用 equals：位图内容相等也不代表能复用画柄）。 */
    internal var source: ImageBitmap? = null

    /** 平台侧直绘画柄（桌面：skia `Image`；Android：不用，直接交给 Compose）。 */
    internal var handle: Any? = null
}

/**
 * 把一整页图贴到 [topLeft]（自然尺寸，不缩放），[alpha] 是整幅浓度（0..1）。
 *
 * 两端各走各的省法：桌面按位图实例缓存 skia `Image` 后用 `nativeCanvas` 直接画
 * （见 [RasterDrawCache]），Android 交回 Compose 的 `drawImage`（那边本来就是位图直画）。
 *
 * 调用点都是**逐帧**的翻页动画路径，别在这里做任何会分配整页内存的事。
 */
internal expect fun DrawScope.drawRasterCached(
    cache: RasterDrawCache,
    image: ImageBitmap,
    topLeft: Offset,
    alpha: Float = 1f,
)
