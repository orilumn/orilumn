package orilumn.reader.desktop

import okio.FileSystem
import orilumn.reader.data.font.fontFilesEnvironmentHash

/**
 * **桌面系统字体文件环境哈希**（用户层·桌面；方案《字体发现与开书调度方案》§8-2 指纹加固）。
 *
 * 族名指纹只看族名集合，检测不到「族名不变、字体文件被换」。这里列 macOS 与 Linux 的字体根目录，
 * 把「路径 + 大小 + mtime」交给共享 [fontFilesEnvironmentHash] 成哈希，喂
 * `SystemFontSource.environmentHash`。
 *
 * 不存在的根目录被跳过；一个都没有（Windows 桌面）⇒ [hash] 回 `0L`，退回纯族名指纹（不误报）。
 * 平台路径是**桌面专有差异**，只在本文件出现（AGENTS.md 架构约束 2）。
 */
object DesktopFontEnvironment {

    /** macOS 系统 + 用户字体根目录；Linux 系统 + 用户字体根目录（顺序无关：哈希前会排序）。 */
    fun roots(): List<String> {
        val home = System.getProperty("user.home").orEmpty()
        return buildList {
            add("/System/Library/Fonts")
            add("/System/Library/Fonts/Supplemental")
            add("/Library/Fonts")
            if (home.isNotEmpty()) add("$home/Library/Fonts")
            // Linux（fontconfig 的标准目录；不在表里的自定义目录仅影响指纹灵敏度，不影响枚举）
            add("/usr/share/fonts")
            add("/usr/local/share/fonts")
            if (home.isNotEmpty()) {
                add("$home/.local/share/fonts")
                add("$home/.fonts")
            }
        }
    }

    fun hash(): Long = fontFilesEnvironmentHash(FileSystem.SYSTEM, roots())
}
