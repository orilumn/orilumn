package orilumn.reader.ui.reader.curl

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 把一帧卷曲画到 Compose 画布上（**平台缝**，`shared-ui` 公共能力的 expect）。
 *
 * 两端共用同一份 [CURL_SKSL] 与 [CurlFrame]；差异只有「谁来编译这个着色器、谁来画这个矩形」：
 * 桌面 skia `RuntimeEffect`、Android AGSL `RuntimeShader`（API 33+）。
 *
 * @return false = 本平台/本次画不出来（着色器编译失败、低版本 Android），调用方**保持原样**
 *   （不画卷曲、也别切动画档）；true = 已画满整页矩形，调用方可以不再画两张页画布。
 */
expect fun DrawScope.drawCurlFrame(frame: CurlFrame, front: ImageBitmap, back: ImageBitmap): Boolean
