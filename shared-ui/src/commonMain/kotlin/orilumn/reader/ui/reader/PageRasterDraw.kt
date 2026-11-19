package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 一张栅格贴图 + **内容代数**（强捆绑）。
 *
 * 为什么不是裸 `ImageBitmap`：覆盖层（旁注 / 页码）那张整页位图在尺寸不变时会被
 * **原地重画**（改批注字号时省掉每步一张 20MB 级的新图，见 `PageOverlayLayer`），
 * 所以「实例相同」根本不代表「像素相同」。任何贴图缓存（桌面 skia 快照、卷曲纹理的
 * `ImageShader`）都必须在实例之外再比代数；把两者绑成一个值以后，所有消费方拿到的都是
 * 「这张图的这一版像素」，想只拿图、忘掉版本也做不到。
 *
 * 生产规律：一次性快照（页栅格、封面烤图）每张都是新实例，[generation] 恒 0；
 * 覆盖层每次真正重画就 +1（与页面无关的全局递增，只求前后不同）。
 */
class RasterTexture(
    val image: ImageBitmap,
    val generation: Int = 0,
) {
    // 值语义：同一张位图的同一代就是同一版像素。
    // 让 Compose 状态（页槽发布的纹理）吃到相等的两份值时不去无谓地失效下游。
    override fun equals(other: Any?): Boolean =
        other is RasterTexture && other.image === image && other.generation == generation

    override fun hashCode(): Int = 31 * image.hashCode() + generation
}

/**
 * 整页贴图的绘制缓存（用户层·渲染缝）：槽里那张图的**这一版像素**换了才重建平台侧画柄。
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
 *
 * 判据是 `(source, generation)` 两项：只看实例会在覆盖层原地重画时贴出旧像素，
 * 看过代数才算「这一版」。
 */
internal class RasterDrawCache {
    /** 画柄对应的那张位图（判等用实例，不用 equals：位图内容相等也不代表能复用画柄）。 */
    internal var source: ImageBitmap? = null

    /** 画柄对应的**内容代数**（[source] 的像素版本，见 [RasterTexture]）。 */
    internal var generation: Int = 0

    /** 平台侧直绘画柄（桌面：skia `Image`；Android：不用，直接交给 Compose）。 */
    internal var handle: Any? = null
}

/**
 * 把一整页图贴到 [topLeft]（自然尺寸，不缩放），[alpha] 是整幅浓度（0..1）。
 *
 * 两端各走各的省法：桌面按 [RasterTexture]（位图实例 + 内容代数）缓存 skia `Image`
 * 后用 `nativeCanvas` 直接画（见 [RasterDrawCache]），Android 交回 Compose 的 `drawImage`
 * （那边本来就是位图直画）。
 *
 * 调用点都是**逐帧**的翻页动画路径，别在这里做任何会分配整页内存的事。
 */
internal expect fun DrawScope.drawRasterCached(
    cache: RasterDrawCache,
    texture: RasterTexture,
    topLeft: Offset,
    alpha: Float = 1f,
)
