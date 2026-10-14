# 更新日志

本文件记录 Orilumn 每个版本的**用户可见变更**（新增 / 变更 / 修复 / 移除）。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

> 本 CHANGELOG 自 **0.4.3** 起记录：0.4.2 及更早的版本没有条目，
> 需要追溯请查 git 提交历史。
>
> 发版纪律：`orilumn.versionName` 递增与 `app/build.gradle.kts` 的 `versionCode` +1 的那个
> bump 提交里，**必须同时更新本文件**（把「未发布」段落实化为 `<版本号> - <日期>`），
> tag 落在该提交上。

## [未发布]

### 新增

- Linux 发行产物新增 **RPM** 与**单文件 AppImage**（原先只有 deb）。打 tag 后 `Orilumn-<版本>-linux-x64.rpm`
  与 `Orilumn-<版本>-linux-x64.AppImage` 会和 deb 一起挂到 Release。
- 仓库新增本 CHANGELOG 文件。

### 变更

- 重写 README：改为跨平台口径（平台与实测情况、自研浏览器内核与自研断行引擎的能力与边界、
  下载安装、构建与测试），此前 README 仍写着「仅 Android、基于 StaticLayout」的旧状态。
