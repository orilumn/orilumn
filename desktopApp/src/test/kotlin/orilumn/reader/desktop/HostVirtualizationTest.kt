package orilumn.reader.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口底子选型（平台层·桌面壳）：只有「真机 + 支持透明 + 非软件渲染」才用圆角/投影。
 *
 * 圆角本身不贵，贵的是透明窗口的逐像素贴屏合成：虚拟机里没有 GPU，拉窗会慢到不可用。
 */
class HostVirtualizationTest {

    @Test
    fun `virtual machine vendors are recognized`() {
        assertTrue(looksVirtualized(listOf("QEMU")))
        assertTrue(looksVirtualized(listOf("VMware Virtual Platform")))
        assertTrue(looksVirtualized(listOf("VirtualBox")))
        assertTrue(looksVirtualized(listOf("innotek GmbH")))
        assertTrue(looksVirtualized(listOf("Microsoft Corporation", "Virtual Machine")))
        assertTrue(looksVirtualized(listOf("Parallels Software International Inc.")))
        assertTrue(looksVirtualized(listOf("hypervisor")))
    }

    @Test
    fun `bare metal vendors are not mistaken for a virtual machine`() {
        assertFalse(looksVirtualized(listOf("Apple Inc.", "MacBookPro12,1")))
        assertFalse(looksVirtualized(listOf("Dell Inc.", "XPS 13 9310")))
        assertFalse(looksVirtualized(listOf("ASUSTeK COMPUTER INC.", "System Product Name")))
        assertFalse(looksVirtualized(emptyList()))
    }

    @Test
    fun `rounded window is only for real hardware`() {
        assertTrue(roundedWindowEnabled(canTransparent = true, isMac = false, virtualized = false, softwareRendered = false, override = null))
        // 虚拟机 / 软件渲染：不透明直角
        assertFalse(roundedWindowEnabled(canTransparent = true, isMac = false, virtualized = true, softwareRendered = false, override = null))
        assertFalse(roundedWindowEnabled(canTransparent = true, isMac = false, virtualized = false, softwareRendered = true, override = null))
        // 平台不支持透明 / macOS 系统窗口：不走自绘圆角
        assertFalse(roundedWindowEnabled(canTransparent = false, isMac = false, virtualized = false, softwareRendered = false, override = null))
        assertFalse(roundedWindowEnabled(canTransparent = true, isMac = true, virtualized = false, softwareRendered = false, override = null))
    }

    @Test
    fun `env override forces either way`() {
        assertTrue(roundedWindowEnabled(canTransparent = true, isMac = false, virtualized = true, softwareRendered = true, override = "1"))
        assertFalse(roundedWindowEnabled(canTransparent = true, isMac = false, virtualized = false, softwareRendered = false, override = "0"))
        // 强制开启也不能绕过平台不支持透明
        assertFalse(roundedWindowEnabled(canTransparent = false, isMac = false, virtualized = false, softwareRendered = false, override = "1"))
    }

    @Test
    fun `software rendering is detected from mesa env`() {
        assertTrue(isSoftwareRenderedHost { name ->
            when (name) {
                "LIBGL_ALWAYS_SOFTWARE" -> "1"
                else -> null
            }
        })
        assertTrue(isSoftwareRenderedHost { name ->
            when (name) {
                "MESA_LOADER_DRIVER_OVERRIDE" -> "llvmpipe"
                else -> null
            }
        })
        assertFalse(isSoftwareRenderedHost { null })
    }
}
