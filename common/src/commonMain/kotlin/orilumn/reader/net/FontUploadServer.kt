package orilumn.reader.net

import orilumn.reader.io.Logger
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.isEmpty
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

/**
 * S35：WIFI 字体上传服务——全项目唯一的 HTTP server（旧 `:app` 手写 `ServerSocket`
 * 版 `WifiFontServer` 的 Ktor 重写，`common/net` 通用层）。
 *
 * - 传输走 Ktor CIO 引擎（commonMain 可编：android/jvm 双目标 + 未来 iOS 直接复用同一实现；
 *   手写 `java.net` 在 commonMain 不可用，这正是 S35 指定 Ktor 的原因）。
 * - 落盘走 okio `FileSystem.SYSTEM`（与 S30/AppRoot 同一 I/O 口径），目录由各壳注入
 *   （android=`cacheDir/wifi_fonts`，桌面/iOS 壳各自临时目录）。
 * - 协议与旧版一一对应，电脑侧页面/上传方式不变：
 *   - GET `/` → 中文上传页（多选文件 + fetch 逐个 POST）。
 *   - POST `/upload?name=xx` → 请求体即字体 raw bytes（无 multipart 解析），写入临时文件后
 *     回调 [onUpload]（name=清洗后文件名，absolutePath=落盘绝对路径），调用方做私有拷贝+解析入库。
 *
 * 上传回调在 CIO 事件循环线程触发；涉 UI 状态的调用方自行切回主线程/scope
 *（android 侧 `WifiImportDialog` 经 `scope.launch` 接入）。
 */
class FontUploadServer(
    /** 上传暂存目录（绝对路径，不存在则启动时创建）。 */
    private val uploadDirPath: String,
    /** 固定上传端口：同一局域网内每次打开地址不变，电脑侧无需重新输入（沿旧版约定）。 */
    private val port: Int = PORT,
    /** 上传落盘后回调（name=清洗后文件名，absolutePath=落盘绝对路径）。 */
    private val onUpload: (name: String, absolutePath: String) -> Unit,
) {
    private val tag = "Orilumn.Wifi"
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /**
     * 生产入口：绑定 `0.0.0.0:[port]`，返回电脑侧可开的 `"ip:port"`；
     * 无局域网 IPv4 或端口被占时返回 null（调用方显示"启动服务失败"，与旧版语义一致）。
     */
    fun start(): String? {
        val ip = lanIpv4()
        if (ip == null) {
            Logger.w(tag, "no lan ipv4")
            return null
        }
        val bound = runCatching { bindAndStart("0.0.0.0", port) }.getOrNull()
        if (bound == null) {
            Logger.w(tag, "bind port $port failed")
            return null
        }
        return "$ip:$bound"
    }

    /**
     * 绑定并启动（幂等：先停旧引擎），返回实际端口（`port=0` 时由系统分配）。
     * 生产走 [start]；单测用 `bindAndStart("127.0.0.1", 0)` 避开局域网依赖。
     */
    fun bindAndStart(host: String, port: Int): Int {
        runCatching { server?.stop(0, 500) }
        server = null
        val eng = embeddedServer(CIO, port = port, host = host) { module() }
        eng.start(wait = false)
        server = eng
        FileSystem.SYSTEM.createDirectories(uploadDirPath.toPath())
        if (port != 0) return port
        // port=0（单测）：等引擎落定后读系统分配的实际端口（3.x resolvedConnectors 为 suspend）。
        return runBlocking {
            repeat(100) {
                val actual = eng.engine.resolvedConnectors().firstOrNull()?.port ?: 0
                if (actual != 0) return@runBlocking actual
                delay(20)
            }
            port
        }
    }

    /** 停服 + 清空暂存目录（与旧版 `stop` 语义一致：对话框关闭即清理）。 */
    fun stop() {
        runCatching { server?.stop(0, 500) }
        server = null
        runCatching {
            val fs = FileSystem.SYSTEM
            fs.listOrNull(uploadDirPath.toPath())?.forEach { runCatching { fs.delete(it) } }
        }
    }

    private fun Application.module() {
        routing {
            get("/") {
                call.respondText(UPLOAD_PAGE, ContentType.Text.Html)
            }
            post("/upload") {
                // Ktor 已做 query URL 解码；此处只做文件名清洗（防路径穿越/非法字符）。
                val raw = call.request.queryParameters["name"] ?: ""
                val target = uniqueTarget(sanitizeFileName(raw))
                val size = runCatching { streamToFile(call.receiveChannel(), target.toString()) }.getOrDefault(-1)
                if (size <= 0) {
                    runCatching { FileSystem.SYSTEM.delete(target) }
                    call.respondText("上传失败，请重试", ContentType.Text.Html, HttpStatusCode.BadRequest)
                    return@post
                }
                val name = target.name
                Logger.i(tag, "uploaded $name (${size}B)")
                runCatching { onUpload(name, target.toString()) }
                call.respondText(okPage(name), ContentType.Text.Html)
            }
        }
    }

    /** 通道流式落盘（字体 TTC 可达 tens of MB，不全量进内存）；返回写入字节数，失败 -1。 */
    private suspend fun streamToFile(channel: ByteReadChannel, absolutePath: String): Long {
        var size = 0L
        FileSystem.SYSTEM.sink(absolutePath.toPath()).buffer().use { sink ->
            while (!channel.isClosedForRead) {
                val packet = channel.readRemaining(8192)
                if (packet.isEmpty) break
                val bytes = packet.readBytes()
                sink.write(bytes)
                size += bytes.size
            }
        }
        return size
    }

    /** 目标文件：清洗后名；重名/空名时顺延（commonMain 无时钟，用存在性探测代替旧版时间戳）。 */
    private fun uniqueTarget(sanitized: String): okio.Path {
        val fs = FileSystem.SYSTEM
        val base = sanitized.ifBlank { "font_upload.ttf" }
        val dir = uploadDirPath.toPath()
        fs.createDirectories(dir)
        var candidate = dir / base
        var n = 1
        while (fs.exists(candidate)) {
            val stem = base.substringBeforeLast('.', base)
            val ext = base.substringAfterLast('.', "")
            candidate = dir / "$stem-$n.$ext"
            n++
        }
        return candidate
    }

    companion object {
        /** 固定上传端口：同一局域网内每次打开地址不变，电脑侧无需重新输入（沿旧版约定）。 */
        const val PORT = 8080

        /**
         * 文件名清洗（纯函数，单测覆盖）：只留字母数字/`-_."，截断 64，
         * 与旧版 `WifiFontServer.handleUpload` 同一口径（Ktor 已先做 URL 解码）。
         */
        internal fun sanitizeFileName(raw: String): String =
            raw.filter { it.isLetterOrDigit() || it in "-_." }.take(64)

        private fun okPage(name: String): String {
            val safe = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            return "<!DOCTYPE html><html lang=\"zh\"><meta charset=\"utf-8\"><body>" +
                "<p style='color:#1a7f37'>$safe 已上传到平板并导入</p>" +
                "<p><a href='/'>继续上传</a></p></body></html>"
        }

        private val UPLOAD_PAGE = """
            <!DOCTYPE html><html lang="zh"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>向平板导入字体</title><style>
            body{font-family:system-ui,sans-serif;max-width:560px;margin:40px auto;padding:0 20px;color:#222}
            h1{font-size:20px}
            .drop{background:#2b2b2b;color:#fff;border:none;border-radius:10px;padding:16px 20px;text-align:center;font-size:16px;cursor:pointer}
            .drop:hover{background:#414141}
            .list{margin-top:20px}.item{display:flex;justify-content:space-between;padding:8px 0;border-bottom:1px solid #eee}
            .ok{color:#1a7f37}.err{color:#c0392b}
            </style></head><body>
            <h1>向平板导入字体</h1>
            <p>选择上传字体文件（.ttf / .otf / .ttc / .otc），可多选。</p>
            <div class="drop" id="drop">点击选择文件</div>
            <input type="file" id="file" multiple accept=".ttf,.otf,.ttc,.otc" style="display:none">
            <div class="list" id="list"></div>
            <script>
            const drop=document.getElementById('drop'),file=document.getElementById('file'),list=document.getElementById('list');
            drop.onclick=()=>file.click();
            file.onchange=async()=>{
              const files=[...file.files];
              for(const f of files){
                const row=document.createElement('div');row.className='item';
                row.innerHTML='<span>'+f.name+'</span><span class="ok">上传中…</span>';list.appendChild(row);
                const label=row.lastChild;
                try{
                  const r=await fetch('/upload?name='+encodeURIComponent(f.name),{method:'POST',body:f});
                  if(!r.ok) throw new Error('HTTP '+r.status);
                  label.className='ok';label.textContent='成功';
                }catch(e){label.className='err';label.textContent=('失败');}
              }
              file.value='';
            };
            </script></body></html>
        """.trimIndent()
    }
}
