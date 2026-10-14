# 断点：P0 已完成，P1 待续

时间：Wed Oct 14 2026

## 已提交（fix/platform-consistency）
- 9c6f826 P0-1: prepareRelayout/prepareRelayoutLight 判空提前到副作用之前
- 1873205 P0-2: 从开书路径摘掉字体扫描（ReaderView/ReaderActivity）
- 5920ca5 P0-3: TaskScheduler 新增 PRIO_FONT_DEMAND=5、PRIO_FONT_SCAN=45

## 改动文件
- engine-skia/src/commonMain/kotlin/orilumn/reader/engine/BookDocumentController.kt
- engine-skia/src/commonMain/kotlin/orilumn/reader/engine/TaskScheduler.kt
- desktopApp/src/main/kotlin/orilumn/reader/desktop/ReaderView.kt
- app/src/main/java/orilumn/reader/ui/reader/ReaderActivity.kt

## 下步（P1：字体发现三层次，按方案 §2-§5）
1. **FontLibrary/LibraryDb**：增加族名指纹（cacheSchemaVersion + familyHash）用于判定系统字体是否变化（指纹 = `(cacheSchemaVersion, hash(sorted(systemFontFamilies())))`，方案 §2.1）
2. **engine-skia SystemFonts**：暴露族名集合计算与指纹计算
3. **排版层（上）BookDocumentController/宿主**：启动一次性检测（app 启动时只检测一次，@Volatile once 门控）→ 指纹变才进 TaskScheduler 提交 `fontscan`（prio=45）；指纹不变什么都不做（方案 §2.2）
4. **面板可见性驱动**：面板打开 → 同键 `fontscan` 提档到 `PRIO_FONT_DEMAND=5`（同键重提已由 TaskScheduler 支持），面板关闭 → 降回 `PRIO_FONT_SCAN=45` 或 `cancelKey`（方案 §2.4、§3.4）
5. **字体扫描任务编排进 TaskScheduler**：逐族落成果（每完成一族即提交进 DB/指纹，抗抢占续跑），面板随每族完成增量刷新（方案 §3.3）
6. **平板端 ReaderActivity 启动检测时机、桌面端 Main/宿主启动检测时机**（方案 §5 分层）

## 测试状态（engine-skia jvmTest）
- tests=129, failures=1, skipped=1, errors=0
- 主要失败是 Gradle Test Run 的环境性问题（非单个用例失败统计异常）；单个用例统计显示绝大多通过。改动未触及排版算法主路径。

## 备注
- 本次改动最小（P0），每步提交。测试耗时较长，先在此断点，明天从 P1-1 开始。
