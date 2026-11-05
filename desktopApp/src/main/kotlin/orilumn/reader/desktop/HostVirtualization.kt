package orilumn.reader.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 宿主是不是虚拟机 / 容器（平台层·桌面壳）。
 *
 * 判据尽量便宜且不易误判：
 * - Linux：先问 `systemd-detect-virt --quiet`（VM 与容器都算虚拟化，容器里一样没有 GPU 直通）；
 *   没有 systemd 的发行版退到 DMI 厂商串、CPU `hypervisor` 位与 `/sys/hypervisor/type`；
 * - Windows：看有没有装虚拟机客机驱动（VBoxGuest / vmmouse / vmhgfs 等），不拉起 WMI；
 * - macOS：`sysctl kern.hv_vmm_present`，虚拟机里为 1。
 *
 * 判不出证据时按**真机**处理（裸机 DMI 一般都有真实厂商串；相反，虚拟机几乎都会留痕）。
 */
internal fun isVirtualizedHost(): Boolean =
    systemdDetectsVirt() || looksVirtualized(virtualizationEvidence())

/**
 * 纯软件渲染的桌面（llvmpipe / Xvfb 等）。透明窗口在这种环境里同样要 CPU 逐像素贴屏，
 * 与虚拟机一个下场，故一并排除。
 */
internal fun isSoftwareRenderedHost(
    env: (String) -> String? = System::getenv,
): Boolean {
    fun on(name: String) = env(name)?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
    if (on("LIBGL_ALWAYS_SOFTWARE")) return true
    val driver = env("MESA_LOADER_DRIVER_OVERRIDE")?.trim()?.lowercase()
        ?: env("GALLIUM_DRIVER")?.trim()?.lowercase()
    return driver in setOf("llvmpipe", "softpipe", "swrast")
}

/**
 * 窗口底子选哪种（平台层·桌面壳，Win/Linux）：
 * - 逐像素透明 + 内容自绘圆角/投影：观感好，但贴屏要合成器逐像素混合，**没有硬件加速就慢到不可用**
 *   （虚拟机实测 0.2–2 秒一帧）；
 * - 不透明 + 直角：哪都能跑，代价只是四角与外圈投影。
 *
 * 故只在「平台支持透明 && 真机 && 不是软件渲染」时用圆角。环境变量 `ORILUMN_ROUNDED_WINDOW`
 * 可强制开关（`1`/`0`），方便在虚拟机里试观感、或在特殊宿主上退回直角。
 */
internal fun roundedWindowEnabled(
    canTransparent: Boolean,
    isMac: Boolean,
    virtualized: Boolean,
    softwareRendered: Boolean,
    override: String? = System.getenv("ORILUMN_ROUNDED_WINDOW"),
): Boolean {
    if (isMac || !canTransparent) return false
    when (override?.trim()?.lowercase()) {
        "1", "true", "on", "yes" -> return true
        "0", "false", "off", "no" -> return false
    }
    return !virtualized && !softwareRendered
}

/** 宿主留有虚拟化痕迹的关键字（大小写不敏感）。 */
private val VirtualizationMarkers = listOf(
    "vmware", "virtualbox", "vbox", "innotek", "qemu", "kvm", "xen", "hyper-v", "hyperv",
    "virtual machine", "parallels", "bochs", "bhyve", "openstack", "amazon ec2",
    "google compute", "digitalocean", "droplet", "alibaba", "tencent", "scaleway",
    "nutanix", "proxmox", "hv_vmm_present",
)

/** 把宿主特征串分类：命中任一虚拟化关键字即视为虚拟机（纯函数，便于测试）。 */
internal fun looksVirtualized(evidence: List<String>): Boolean =
    evidence.any { line -> VirtualizationMarkers.any { line.contains(it, ignoreCase = true) } }

/** `systemd-detect-virt --quiet`：退出码 0 = 在虚拟化环境里（VM 或容器）。 */
private fun systemdDetectsVirt(): Boolean = runCatching {
    val process = ProcessBuilder("systemd-detect-virt", "--quiet")
        .redirectErrorStream(true)
        .start()
    try {
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    } finally {
        process.inputStream.close()
    }
}.getOrDefault(false)

/** 采集宿主特征串（只读，不拉起重型命令）。 */
private fun virtualizationEvidence(): List<String> = buildList {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    when {
        os.contains("mac") -> sysctl("kern.hv_vmm_present")
            ?.takeIf { it.trim() == "1" }
            ?.let { add("hv_vmm_present") }

        os.contains("win") -> WindowsGuestDrivers.forEach { path ->
            if (runCatching { File(path).exists() }.getOrDefault(false)) add(path)
        }

        else -> {
            LinuxDmiFiles.forEach { path ->
                runCatching { File(path).readText() }.getOrNull()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add(it) }
            }
            runCatching {
                File("/proc/cpuinfo").useLines { lines ->
                    if (lines.any { it.startsWith("flags") && it.contains(" hypervisor") }) {
                        add("hypervisor")
                    }
                }
            }
            runCatching { File("/sys/hypervisor/type").readText() }.getOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { add(it) }
        }
    }
}

private fun sysctl(key: String): String? = runCatching {
    val process = ProcessBuilder("sysctl", "-n", key).redirectErrorStream(true).start()
    try {
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            process.inputStream.bufferedReader().use { it.readText() }.trim()
        }
    } finally {
        process.inputStream.close()
    }
}.getOrNull()

/** Linux：DMI 里能看出厂商/型号的几处。 */
private val LinuxDmiFiles = listOf(
    "/sys/class/dmi/id/sys_vendor",
    "/sys/class/dmi/id/product_name",
    "/sys/class/dmi/id/product_version",
    "/sys/class/dmi/id/board_vendor",
    "/sys/class/dmi/id/bios_vendor",
)

/** Windows：虚拟机客机驱动的常见落点。 */
private val WindowsGuestDrivers = listOf(
    "C:\\Windows\\System32\\drivers\\VBoxGuest.sys",
    "C:\\Windows\\System32\\drivers\\VBoxMouse.sys",
    "C:\\Windows\\System32\\drivers\\vmmouse.sys",
    "C:\\Windows\\System32\\drivers\\vmhgfs.sys",
    "C:\\Windows\\System32\\drivers\\vmci.sys",
    "C:\\Windows\\System32\\drivers\\qemu-ga.sys",
)
