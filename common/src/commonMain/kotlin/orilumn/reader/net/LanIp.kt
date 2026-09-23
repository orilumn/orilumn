package orilumn.reader.net

/**
 * 本机局域网 IPv4（无可用非回环网卡时返回 null）。
 *
 * S35 自旧 `:app` `WifiFontServer.localIpv4` 提取：`jvmMain actual` 一套实现同时服务
 * android 与桌面（沿 S21/S30 的 `androidMain.dependsOn(jvmMain)` 接缝范式），iOS 壳落地时补
 * `iosMain actual`（getifaddrs），`commonMain` 调用方（[FontUploadServer]）无需改动。
 */
expect fun lanIpv4(): String?
