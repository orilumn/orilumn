package orilumn.reader.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.Collections

/**
 * S35 FontUploadServer 单测：回环 + 临时端口上的真实 GET/POST + 文件名清洗纯函数。
 *
 * 不依赖局域网（`bindAndStart("127.0.0.1", 0)` 由系统分配端口），android/桌面双壳语义一致。
 */
class FontUploadServerTest {

    private fun tempDir(): String =
        Files.createTempDirectory("wifi_test").toFile().absolutePath

    private fun withServer(
        dir: String = tempDir(),
        uploads: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf()),
        block: (FontUploadServer, Int) -> Unit,
    ) {
        val server = FontUploadServer(dir) { name, path -> uploads.add(name to path) }
        val port = server.bindAndStart("127.0.0.1", 0)
        try {
            block(server, port)
        } finally {
            server.stop()
        }
    }

    private fun get(port: Int, path: String): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        return try {
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            code to body
        } finally {
            c.disconnect()
        }
    }

    private fun post(port: Int, name: String?, bytes: ByteArray): Pair<Int, String> {
        val query = if (name == null) "" else "?name=" + java.net.URLEncoder.encode(name, "UTF-8")
        val c = URL("http://127.0.0.1:$port/upload$query").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setRequestProperty("Content-Length", bytes.size.toString())
        c.outputStream.use { it.write(bytes) }
        return try {
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            code to body
        } finally {
            c.disconnect()
        }
    }

    @Test
    fun `GET 根路径返回上传页`() {
        withServer { _, port ->
            val (code, body) = get(port, "/")
            assertEquals(200, code)
            assertTrue(body.contains("向平板导入字体"))
            assertTrue(body.contains("/upload?name="))
        }
    }

    @Test
    fun `POST 上传落盘并回调清洗后文件名`() {
        val dir = tempDir()
        val uploads = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        withServer(dir, uploads) { _, port ->
            val payload = ByteArray(3000) { (it % 251).toByte() }
            val (code, _) = post(port, "My Font.ttf", payload)
            assertEquals(200, code)
            // 空格被清洗掉（与旧版同口径）。
            assertEquals(1, uploads.size)
            assertEquals("MyFont.ttf", uploads.single().first)
            val saved = java.io.File(uploads.single().second)
            assertTrue(saved.exists())
            assertArrayEquals(payload, saved.readBytes())
        }
    }

    @Test
    fun `空包上传400且无回调`() {
        val uploads = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        withServer(uploads = uploads) { _, port ->
            val (code, _) = post(port, "a.ttf", ByteArray(0))
            assertEquals(400, code)
            assertTrue(uploads.isEmpty())
        }
    }

    @Test
    fun `无名上传顺延不覆盖`() {
        val uploads = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        withServer(uploads = uploads) { _, port ->
            assertEquals(200, post(port, null, byteArrayOf(1)).first)
            assertEquals(200, post(port, null, byteArrayOf(2)).first)
            assertEquals(listOf("font_upload.ttf", "font_upload-1.ttf"), uploads.map { it.first })
        }
    }

    @Test
    fun `未知路径404`() {
        withServer { _, port ->
            assertEquals(404, get(port, "/nope").first)
        }
    }

    @Test
    fun `文件名清洗`() {
        // 路径穿越/非法字符剔除（与旧版同口径）。
        assertEquals("....etcpasswd", FontUploadServer.sanitizeFileName("../../etc/passwd"))
        assertEquals("abc-_.ttf", FontUploadServer.sanitizeFileName("abc-_.ttf"))
        assertEquals("", FontUploadServer.sanitizeFileName("   "))
        // 截断 64。
        assertEquals(64, FontUploadServer.sanitizeFileName("a".repeat(100)).length)
    }
}
