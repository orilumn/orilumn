package orilumn.reader.desktop

import okio.FileSystem
import orilumn.reader.data.font.fontFilesEnvironmentHash

/**
 * **桌面系统字体文件环境哈希**（用户层·桌面；方案《字体发现与开书调度方案》§8-2 指纹加固）。
 *
 * 族名指纹只看族名集合，检测不到「族名不变、字体文件被换」。这里列 macOS 的字体根目录，
 * 把「路径 + 大小 + mtime」交给共享 [fontFilesEnvironmentHash] 成哈希，喂
 * `SystemFontSource.environmentHash`。
 *
 * 非 macOS（Linux/Windows 桌面）这些目录不存在 ⇒ [hash] 回 `0L`，退回纯族名指纹（不误报）。
 * 平台路径是**桌面专有差异**，只在本文件出现（AGENTS.md 架构约束 2）。
 */
object DesktopFontEnvironment {

    /** macOS 系统 + 用户字体根目录（顺序无关：哈希前会排序）。 */
    fun roots(): List<String> {
        val home = System.getProperty("user.home").orEmpty()
        return buildList {
            add("/System/Library/Fonts")
            add("/System/Library/Fonts/Supplemental")
            add("/Library/Fonts")
            if (home.isNotEmpty()) add("$home/Library/Fonts")
        }
    }

    fun hash(): Long = fontFilesEnvironmentHash(FileSystem.SYSTEM, roots())
}
