package orilumn.reader.ui.reader

import android.os.Build

/**
 * Android：卷曲翻页依赖 AGSL `RuntimeShader`（API 33 = Android 13 起）。
 * 更低的系统没有等价能力，按滑动处理（见 [curlFlipSupported] 的 common 声明）。
 */
actual val curlFlipSupported: Boolean
    get() = Build.VERSION.SDK_INT >= 33
