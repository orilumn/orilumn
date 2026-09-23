# F 系统字体统一方案（全平台可用系统字体 + 可隐藏）

> 母文档：`docs/架构分层与共享边界.md`（系统字体集合边界 D2）、`docs/font-engine-plan.md`
> （字体引擎）、`docs/平台一致性整改方案.md`（C 系列之后的字体系收敛）。
> 系列编号：**F**。C2 只收了阅读管线（单编排宿主），字体管理仍是平板独占：
> 桌面 `fontNames = []`、`onDemandFonts = { false }`（见 `ReaderView.kt:115`、
> `DesktopReaderHost.kt:70`），用户在桌面一个字体都选不了。
> 分支：`feat/system-fonts`（基点 `603de12`，`fix/pagination-continuity` 顶）。
> 做完回 `fix/pagination-continuity` 继续分页连续性。
> 本文件是**续接依据**：会话中断时按「当前状态 / 剩余步骤」可直接恢复，不必重新勘察。

---

## 0. 动因（一句话）

桌面放弃用户字体导入可以接受，但两端都该能用**系统字体**；`FontFace` 当年为防列表淹没
而排除系统字体（KDoc 原话），现在用"系统 + 导入同列、可隐藏"替代"排除"，当年顾虑即消除。

## 1. 勘察结论（事实，2026-09-22，勿重做）

- 渲染侧已就绪，零改动：`embeddedFontCollection` 是"系统 manager + 内嵌 provider"双层
  （`SkParagraphFactory.kt:57-74`），具名族原样保留、未安装按字形跳过
  （`resolveFamiliesFor:241-244`）；槽位存系统族名（如 `Songti SC`）两端同一代码自动命中。
  `systemFonts()` expect/actual 两端就位（`SystemFonts.kt` + android/jvm actual）。
- 枚举零新缝：skiko `FontMgr` 自带 `familiesCount`/`getFamilyName(i)`
  （已拆 `skiko-awt-0.144.6.jar` 验证签名），common 纯函数即可枚举，无需 expect/actual。
- 数据层已共享：`FontFace` + `font_faces` 表在 `:common`（S34b，Room 已删）；
  `LibraryDb` 有 `allFonts/upsertFonts/importFont/deleteFont(s)`；
  桌面 `DesktopShelfStore` 用同一 `LibraryDb` + 同一套迁移（`1-3.sqm`，当前 schema v4）；
  `FontParser`（导入解析+分类）已在 `:common`，可共享。
- 宿主侧现状（不对称的全部）：
  - 平板（`:app`，Android 专用）：`FontRepository`（Context + SAF/WiFi 导入 + `filesDir/fonts/`）+
    `AndroidReaderSettingsPanel` + `FontManagerPanel`（左滑删除 `FontSwipeRow`）+
    `ReaderActivity.syncSkiaPool/topUpSkiaFonts`（用户+书内同池，`onDemandFonts` 追装）。
  - 桌面：无 fonts 目录、无 repository、无导入 UI、无 topUp（`onDemandFonts = { false }`）；
    FileKit（跨平台文件选择）已是桌面依赖，可做桌面导入源。
- 最大重复是面板分两套：`:app/FontManagerPanel` vs shared-ui `ReaderSettingsPanel + fontNames`。
  收敛面板才是 F 的大头工作，枚举/表/库都是小手术。

## 2. 目标形态

字体列表 = 系统族（两端枚举）+ 导入族（平板导入，桌面只读展示）同列展示；
系统行左滑显示**隐藏**，导入行左滑显示**删除**；隐藏存 DB（本机维度）；
被隐藏/删除且正被槽位引用时回退"跟随原书"（删除已有此逻辑，复用）；
桌面本次不做导入（只读系统 + 隐藏），平板导入能力不变。

## 3. 步骤（一步一 commit，每步全绿才走下一步）

- **F1 系统枚举**：engine-skia common 加 `systemFontFamilies()` 纯函数
  （`systemFonts()` 上 `familiesCount/getFamilyName`，去重+排序+去空，失败回空列表）+
  jvmTest（非空 + 含 Helvetica + 有序无空名）。
  验收：`:engine-skia:jvmTest` 全绿。
- **F2 模型 + 表**：`FontFace` 加 `hidden: Boolean = false` + `SOURCE_SYSTEM = "system"`，
  改"仅用户导入"KDoc；`4.sqm`（v4→v5：`ALTER TABLE font_faces ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0`）；
  `LibraryDb` 加 `setFontHidden(id, hidden)`/（`visibleFonts()`？沿用 `allFonts` + 调用方过滤，
  见风险 3）+ `toFace` 跟进；common 单测（upsert/hide roundtrip，沿 `LibraryDbTest` 风格）。
  验收：`:common:jvmTest` 全绿。旧库迁移默认全可见，行为不变。
- **F3 共享库 `FontLibrary`（`:common`）**：系统枚举 + `allFonts` 合并成统一列表项
  （`sealed FontEntry { System(family) / Imported(face) }`，隐藏过滤，导入优先排序沿用现有
  Collator 口径）；`hide/unhide`（系统+导入通用）、`deleteFamily`（仅导入，复用既有语义）、
  `importBytes(bytes, name, fontsDir)`（解析+落盘+upsert，字节来源由平台注入）；
  `:app FontRepository` 退化成它的薄包装（SAF Uri/WiFi 读字节后调 `importBytes`，
  `filesDir/fonts` 当 `fontsDir` 传），API 不变。
  验收：`:common:jvmTest` + `:app:testDebugUnitTest` 全绿。
- **F4 面板收敛 + 桌面接线**：shared-ui 新增统一字体管理面板（系统区+导入区、
  左滑隐藏/删除、`FontSwipeRow` 复用思想跨模块重实现——`:app` 的行组件不可直接引用）；
  导入按钮只在平板显示（平台能力位注入）；桌面 `ReaderView` 的 `fontNames` 改喂库列表、
  `DesktopPaths` 加 `fonts/`（首版只读：隐藏可用，导入按钮隐藏）、`DesktopReaderHost`
  `onDemandFonts` 接共享 topUp（用户+书内同池，照抄 `syncSkiaPool` 去 Android 部分）；
  平板切新面板（SAF/WiFi 经能力位保留）。
  验收：`:desktopApp:test` + `:app:testDebugUnitTest` 全绿 + 桌面真机选到系统字体不断行错位。
- **F5 收尾**：本文档勾选，回 `fix/pagination-continuity`。

## 4. 非目标（本系列不碰）

- iOS（枚举/面板 actual 留后）。
- 桌面导入字体（本次只读系统 + 隐藏；FileKit 导入是后续加法，F3 已把 `importBytes` 铺好）。
- 跨端像素级一致（母文档能力边界：断行差只源自系统字体集合，不强制）。
- 改变现有平板导入/删除行为（只搬不改）。

## 5. 风险

1. Android 端 `familiesCount` 未经真机验证（skiko-android 的 Minikin manager 理论支持；
   F1 先合 JVM 侧，Android 真机验证放到 F4 平板切面板时；若不可用，fallback 是 Android
   `SystemFonts`（29+）/ `fonts.xml` 解析，届时才加 expect/actual，不提前）。
2. 系统族数量大（macOS 上百，Android 几十）：首版只展示 + 隐藏，不做搜索；若列表卡顿，
   F4 再加懒加载/分组（§2 不承诺搜索）。
3. `visibleFonts` 是否进 SQL：首版调用方过滤（`hidden` 量小）；若性能/口径有诉求再下沉。
4. 旧缓存：字体集合变化 → 分页/断行变化，宿主已有重排路径（`syncSkiaPool` 返回 changed 即重排，
   桌面 F4 照抄）；`font_faces` 加列不影响分页表 key（key 里无字体名，见 C 系列），无需清缓存。

## 当前状态

- [x] 勘察（§1，2026-09-22）
- [x] F1（系统枚举 + jvmTest）
- [x] F2（模型 + 表迁移 + LibraryDb + 单测）
- [x] F3（共享 FontLibrary + FontRepository 退化包装）
- [ ] F4（面板收敛 + 桌面接线 + 平板切换）
  - [x] F4a（共享 FontLibraryPanel + 真字形预览 + 拼音序缝 + 设置面板接线）
  - [x] F4b（桌面接线：字库 + topUp）
  - [x] F4c（平板切新面板 + 删旧面板）
- [x] F5（收尾，回分页分支：`8f1fe1a` 已 fast-forward 合入 `fix/pagination-continuity`）
