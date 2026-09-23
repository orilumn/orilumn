package orilumn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import orilumn.reader.data.settings.ReaderSettings

/**
 * S28 亮度/护眼遮罩（平移自 Android `BrightnessOverlayView` 语义）：
 *  - 压暗：黑色遮罩，alpha = [ReaderMath.dimAlphaOf]（亮度 < 0 时把系统最暗继续压暗）；
 *  - 护眼：暖色遮罩，alpha = [ReaderMath.warmAlphaOf]，颜色 [ReaderMath.ReaderWarmColor]。
 *
 * 纯绘制、不拦截触摸；**应用物理背光**是宿主职责（通过 [ReaderScreen] 的 onLightChange/onLightCommit
 * 回调上报），本层只画 [ReaderSettings] 快照对应的遮罩。
 */
@Composable
fun ReaderLightMask(light: ReaderSettings, modifier: Modifier = Modifier) {
    val dim = ReaderMath.dimAlphaOf(light.brightness)
    val warm = ReaderMath.warmAlphaOf(light.eyeProtectionLevel)
    if (dim <= 0f && warm <= 0f) return
    Box(modifier = modifier) {
        if (dim > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        }
        if (warm > 0f) {
            Box(Modifier.fillMaxSize().background(Color(ReaderMath.ReaderWarmColor).copy(alpha = warm)))
        }
    }
}

/**
 * 底部亮度手势滑块（平移自 `ReaderActivity.BrightnessGestureIndicator`）：单行深灰状态条 #303030
 * 与阅读底栏一致，横贯底部，中部细金色轨道 + 实心圆点，最右显示当前亮度值。
 */
@Composable
fun BrightnessGestureIndicator(ui: BrightnessGestureUi?, modifier: Modifier = Modifier) {
    val cur = ui ?: return
    val frac = ((cur.brightness - ReaderMath.MIN_BRIGHTNESS) /
        (ReaderMath.MAX_BRIGHTNESS - ReaderMath.MIN_BRIGHTNESS).toFloat()).coerceIn(0f, 1f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BrightBarBg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(modifier = Modifier.weight(1f).height(26.dp)) {
            Box(
                Modifier
                    .fillMaxWidth().height(4.dp)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(2.dp))
                    .background(BrightTrack),
            )
            Box(
                Modifier
                    .fillMaxWidth(frac).height(4.dp)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(2.dp))
                    .background(BrightGold),
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = maxWidth * frac - 9.dp)
                    .size(18.dp)
                    .background(BrightGold, CircleShape),
            )
        }
        Text("${cur.brightness}%", color = BrightBarFg, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

private val BrightBarBg = Color(0xFF303030)
private val BrightBarFg = Color(0xFFF5F5F5)
private val BrightTrack = Color(0x47FFFFFF)
private val BrightGold = Color(0xFFC8A15A)