package orilumn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import orilumn.reader.data.font.FontRepository
import orilumn.reader.net.FontUploadServer
import kotlinx.coroutines.launch
import java.io.File

/** MIME types of font files selectable via SAF (compatible with format variations reported across systems). */
internal val FONT_MIMES = arrayOf(
    "font/ttf", "font/otf", "font/woff", "font/woff2",
    "application/x-font-ttf", "application/vnd.ms-opentype",
    "application/octet-stream",
)

internal val DeleteRed = Color(0xFFD9534F)

/**
 * 对话框调色板（壳侧小副本）：值与共享 [Palette]（`paletteFor`）逐项一致，
 * 共享侧 `paletteFor` 为 internal、壳够不到，这里按 scheme 直给同一 16 色。
 * 若后续壳侧还有 UI 要用色，再考虑把共享调色板公开。
 */
internal fun wifiDialogPalette(scheme: String): Palette = when (scheme) {
    "night" -> Palette(Color(0xFF1C1C1E), Color(0xFFE8E8E8), Color(0xFF2C2C2E), Color(0xFF2A2A2C),
        Color(0xFF262628), Color(0xFF232326), Color(0xFF343438), Color(0xFF8A8A8A), Color(0xFF909090), Color(0xFF666666),
        Color(0xFF4A4A4E), Color(0xFF3A3A3E), Color(0xFF2C2C2E), Color(0xFF2A2018), Color(0xFFD9A94F), Color(0xFF8A5F1F))
    else -> Palette(Color(0xFFFAF8F4), Color(0xFF2B2B2B), Color(0xFFECE9E2), Color(0xFFF0EDE6),
        Color(0xFFEFECE4), Color(0xFFFFFFFF), Color(0xFFE3DFD5), Color(0xFF999999), Color(0xFF888888), Color(0xFFBBBBBB),
        Color(0xFFC9C4BA), Color(0xFFE7E3D9), Color(0xFFF0EDE6), Color(0xFFFAF3E6), Color(0xFF8A5F1F), Color(0xFFC8A15A))
}

/**
 * WIFI font import dialog (F4c: moved verbatim from retired `FontManagerPanel`):
 * while open, starts a temporary HTTP server on the device (LAN address);
 * opening that address in a computer browser lets you choose font files to upload.
 * On upload it does a private copy + parse into the DB. No scrim, with a persistent ✕
 * close, matching the reader panel's style.
 */
@Composable
internal fun WifiImportDialog(
    fontRepository: FontRepository,
    p: Palette,
    onDismiss: () -> Unit,
    onImported: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addr by remember { mutableStateOf<String?>(null) }
    var startFailed by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf(false) }

    // S35：上传服务由 common/net 的 Ktor 实现提供；暂存目录仍用壳 cacheDir，落盘后走既有 importFile 入库。
    val server = remember {
        val uploadDir = File(context.cacheDir, "wifi_fonts").apply { mkdirs() }.absolutePath
        FontUploadServer(uploadDir) { _, path ->
            scope.launch {
                fontRepository.importFile(File(path))
                imported = true
                onImported()
            }
        }
    }
    DisposableEffect(Unit) {
        val a = server.start()
        if (a == null) startFailed = true else addr = a
        onDispose { server.stop() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.width(340.dp).background(p.bg),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = p.bg),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "WIFI 导入字体",
                        color = p.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "✕",
                        color = p.text,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(4.dp).clickable(onClick = onDismiss),
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                when {
                    startFailed -> Text(
                        text = "启动服务失败：请确认平板已连接到 Wi-Fi，然后重试。",
                        color = DeleteRed,
                        fontSize = 14.sp,
                    )
                    addr == null -> Text("正在启动服务器…", color = p.muted, fontSize = 14.sp)
                    else -> {
                        Text("平板与电脑需在同一个局域网内，在电脑浏览器打开下面地址：", color = p.text, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = p.borderSoft),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = "http://${addr!!}",
                                    color = p.text,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("长按地址可复制。打开后点选 .ttf / .otf / .ttc 文件即可上传。", color = p.muted, fontSize = 12.sp)
                        if (imported) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("已收到上传并导入（可继续上传或关闭）", color = Color(0xFF1A7F37), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
