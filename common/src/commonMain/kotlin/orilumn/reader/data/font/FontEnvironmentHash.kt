package orilumn.reader.data.font

import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * **平台字体文件环境哈希**（数据层·共享；方案《字体发现与开书调度方案》§8-2 指纹加固）。
 *
 * 为什么需要它：族名指纹（`systemFontFamilyHash`）只看**族名集合**，检测不到「族名不变、
 * 文件被换」——例如同族名重打包（改 name 表、族名不变）、OEM/OTA 原地更新系统字体。
 * 本函数把各字体根目录下所有字体文件的「绝对路径 + 大小 + 最后修改时间」排序后 FNV-1a 成
 * 一个 Long，交给 `SystemFontSource.environmentHash` 与族名哈希合成指纹。
 *
 * 平台差异**只在根目录列表**里（Android `/system/fonts`；macOS `/System/Library/Fonts` 等），
 * 由各宿主传入；本函数只管「目录树 → 哈希」的确定性算法，两端同一段 common 代码。
 *
 * 取不到（目录不存在 / 无权限 / 全部读失败）回 `0L`：与纯族名哈希合成时等价于「无环境信息」，
 * 即**退回**族名指纹。只在**至少一个**根目录成功枚举时才返回非零——宁可多扫一次，不可漏扫。
 *
 * @param fs okio 文件系统（宿主传 `FileSystem.SYSTEM`；单测传临时目录的 `FileSystem.SYSTEM`）。
 * @param roots 字体根目录绝对路径列表；不存在/不可读的根跳过。
 */
fun fontFilesEnvironmentHash(fs: FileSystem, roots: List<String>): Long {
    val entries = ArrayList<String>()
    var anyListed = false
    for (root in roots) {
        val dir = root.toPath()
        val listed = runCatching {
            if (fs.metadataOrNull(dir)?.isDirectory != true) return@runCatching null
            // 绝对路径进条目 ⇒ 同名文件落在不同根目录也区分得开；不跟随符号链接（防环）。
            fs.listRecursively(dir).mapNotNull { p ->
                val m = fs.metadataOrNull(p) ?: return@mapNotNull null
                if (!m.isRegularFile) return@mapNotNull null
                "${p}|${m.size}|${m.lastModifiedAtMillis ?: 0L}"
            }.toList()
        }.getOrNull() ?: continue
        if (listed.isEmpty()) continue
        anyListed = true
        entries.addAll(listed)
    }
    if (!anyListed) return 0L
    entries.sort()
    // FNV-1a（与 `systemFontFamilyHash` 同一常量口径）。
    var h: Long = -0x61c8864680b583ebL
    for (e in entries) {
        h = h xor e.hashCode().toLong()
        h *= 1099511628211L
    }
    return h
}
