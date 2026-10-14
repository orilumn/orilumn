# 更新日志

本文件记录 Orilumn 每个版本的**用户可见变更**（新增 / 变更 / 修复 / 移除）。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

> 本 CHANGELOG 自 **0.4.3** 起记录：0.4.2 及更早的版本没有条目，需要追溯请查 git 提交历史。
> 只写读者/用户能感知的条目；内部重构、测试与文档修订不进本文件。

## [0.4.3] - 2026-10-14

### 新增

- Linux 发行产物新增 **RPM** 与**单文件 AppImage**（原先只有 deb）。打 tag 后
  `Orilumn-<版本>-linux-x64.rpm` 与 `Orilumn-<版本>-linux-x64.AppImage` 会和 deb 一起挂到 Release。
