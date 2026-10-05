# AGENTS.md

## 分页调度总则（最高依据，总则：一切为了让用户少等待，哪个有利用哪个）

1. 第一优先级是目标页（锚页）：开书、各种跳转、翻页三者未命中磁盘表，以及旋转、调参等改变版面的情况，立即同步排版渲染，同时确定锚页所在章使用临时表翻页（小章除外，小章全部使用磁盘表，不使用临时表）。一旦使用临时表，在本章内就始终使用临时表，直到翻回章开头时，若本章磁盘表已就绪，则立即切换到磁盘表（不使用临时表先显示，直接显示磁盘表对应页面），抛弃本章临时表；离开本章时，本章临时表立即废弃。未就绪分三种：版面未改变、且单页或小章已在排的，直接同步（不等）；版面未改变、还没排的，立即同步排锚页并切换，章内临时表作废重建，整个第二优先级重启；大章在途的不等，直接走临时路径。版面改变时（旋转、调参关面板时判定），废弃临时表加全书磁盘表，全部重来。
2. 第二优先级主要使用临时表（除了正负 1 页是另一章时，对应章使用磁盘表；小章全部使用磁盘表，不使用临时表）：调参关闭面板并判断有排版参数变化时，以及上述其他场景下，按距离锚页的页码偏离度（+1、-1、+2、-2……）优先顺序串行排全章；只有在正负 1 页是其他章时，把对应章全量预排磁盘表提升到第二优先级。正负值由最近翻页方向决定，与翻页方向一致的为正；正负 1 必须排在正负 2 前面（分页必须连续）。翻页方向本身默认为无（0），在跳转、旋转、调参时清零，在翻页时重新赋值。
3. 第三优先级是磁盘表：视相对锚页所在章的章偏移量（本章为 0）从小到大排优先级，正负值同样由翻页方向决定；除正负 1 页是相邻章的特殊情况外，其他所有章全量都在第三优先级；目标页在章第 2 页逼近章首时，本章全量提到第二优先级（交接点保无缝）。

## 项目架构约束

1. 不得违反项目层级架构，各层级职责边界严格区分：
    - 用户层：UI 样式、单行定制 CSS、样式主体、批注修订等
    - 排版层（上）：全量分页算法、增量分页算法、垂直滚动模式、竖排模式等
    - 排版层（下）：分层引擎基础设施
    - 渲染层：盒模型、样式层叠、分行控制、字体渲染、几何测量

2. 不得违反 KMP + CMP 跨平台共享模型：程序逻辑和基本组件跨平台共享，仅允许少量平台特殊适配代码。

## 样式层叠优先级（最高依据，读者层永远压过书）

**UI(间距/行距/密度) > 单元素 > 主题 > 书本原样 > UA；前三层合成一次覆盖，直接覆盖内核级联结果。**
（出自 `docs/KMP迁移-功能架构.md` §6 覆盖层；实现在 `common/.../css/Cascade.kt`。）

落地 tier（`Cascade.kt:48-54`）：书本侧 `author 20 / inline 30 / author-important 40 /
inline-important 41`，`UA 10 / UA-important 50`；读者三层**严格夹在 41 与 50 之间**，
`theme 42(important 46) < settings 43(47) < UI 44(48)`。

1. **先比 tier，再比特异度**（`Winner.beats`：`if (tier != cur.tier) return tier > cur.tier`）。
   ⇒ 读者层一条裸标签选择器（特异度 0,0,1）**压过书在同名元素上的任何声明**，含 `.class`
   与 `!important`。**把选择器写窄并不能让书赢**，特异度在这条链上根本轮不到比。
   想让书赢只能放低层（`ua.css` tier 10，作者层 20 压得过它）。
2. **只声明它声明的**：读者层只发读者真配了的声明，没配就不发 ⇒ 书原样生效
   （`ReaderUiSheet.fontRules` 的字重槽是范例）。反过来，**读者层一旦发了，就是覆盖书的承诺**；
   动手前先量该属性在书里的声明分布（按标签归属，别用只数规则条数的正则扫 DOM 代替）。
3. **不经内核级联事后改写**（决策 6 铁律「upper layers … without any post-hoc mutation」）：
   用户字重曾由 `SkParagraphFactory.anchoredWeight` 在级联跑完后私改 `fontWeight`，判架构违规
   并整体退役（Q19）。读者意图只能以**层叠声明**进入，不得在渲染层打补丁。
4. **层内仍走标准级联**：同层内特异度与源码序照旧生效。读者层选择器表另有收紧纪律
   （首行缩进只给 `p`，`li` 绝不套用）。纵边距 UI 层零声明——段间距即疏密百分比，
   随版式乘算，不进层叠。
5. **主题值分两类，别一律推给 UI 层**：
   - **读者可调的**（有滑块/可换字体，读者能配出第三种状态）⇒ 值存 `ReaderSettings`，由 **UI 层
     tier 44** 发声明。现状：`firstLineIndent` / `fontBody` 等。纵边距是例外：段间距即疏密
     百分比（`paragraphGap`），随版式乘算、不发声明（100 = 书/主题节奏，0 = 清零）。
   - **不可再调的基线预设**（这套主题就长这样，读者配不出别的状态）⇒ 就写主题层 asset
     （tier 42）：`modern.css` / `traditional.css`。现状：`body{font-family}`、
     `p{text-indent}`、`p,li{margin:1em 0}`、正文两端对齐 `p,div,li,blockquote,dd,td{text-align:justify}`。
     为这类值新增 `ReaderSettings` 字段是**错的**：主题按钮不写它就是死字段（写死值＝UI 层无条件覆盖），
     而老存档里没这个键 → 停在现代/传统主题的用户实际没生效，还得补 `schemaVersion` 迁移。
   - 三主题切换时，`withLayoutTheme` 只写「可调那一类」的值；原书设置（`useOriginalStyle`）
     **整张主题表不加载** ⇒ 主题层的一切声明都不参与，正文对齐/字体/缩进全由书作者的 CSS 决定。
6. **单元素层（settings，tier 43/47）尚未实现**：`BoxChapterLayouter.settingsSheet` 恒为 `null`
   且无任何调用方传值。排序里保留它的位置，但**当前不存在这一层**，不要按「单元素能覆盖」
   来设计方案。

## 附加约束（可选，可直接删掉）
- 新增代码、重构、方案设计时，先校验是否突破上述层级边界；若必须跨层，提前说明理由
- 平台差异代码集中存放，不要散落在共享业务/排版/渲染核心代码中
- 输出方案、代码、注释时，明确标注代码所属层级

## 校验规则

任何代码修改、方案设计，若违反上面两条架构约束，或违反「样式层叠优先级」，禁止直接生成实现代码，先指出架构冲突点，并给出合规调整思路。

涉及样式/级联的改动，额外必答三问：
1. 这条声明进的是哪一层、哪个 tier？该 tier 相对书本侧（author 20 / inline-important 41）是赢是输？
2. 它会盖掉书在同名元素上的什么声明？**量过没有**（按标签归属统计，不是数规则条数）？
3. 读者没配这个值时，是不是应该干脆不发声明？

## 发版与 tag

1. **tag 必须与 `gradle.properties` 的 `orilumn.versionName` 完全一致**（带 `v` 前缀的那份）。
   `.github/workflows/build-release.yml` 的 `Verify tag matches versionName` 是硬门禁：
   不一致直接 `exit 1`。**只改 tag 名不打版本，Release workflow 必红。**
2. **打 tag 前先提一个 bump 提交**：`orilumn.versionName` 递增到与 tag 相同的值，
   `app/build.gradle.kts` 的 `versionCode` 同步 `+1`，且 tag 落在该 bump 提交上。
   `versionCode` 的用途见该文件注释：分页失效的实际保障是 `common` 的 `LAYOUT_VERSION`
   引擎源码指纹，它只是「发版时把旧分页表全量作废一次」的兜底（生产额外成本 0）。
3. **`orilumn.versionName` 是版本号唯一来源**（`app` 与 `desktopApp` 共读），它同时决定
   `versionName` / `archivesBaseName`（APK 文件名）/ 桌面 `packageVersion` —— **只改一处即全动**。
   历史踩坑：`versionName` 升到 0.3.0 而 `archivesName` 还在 0.2.2，产出「包内容新号、
   文件名旧号」的半吊子 APK 且无人报错。**全仓不留硬编码版本号**（`grep -rn "0\.3\.0"`
   命中处只应剩注释里的历史事故记述）。
4. **推 tag 是不可逆的公开副作用**：会触发签名 Release workflow（构建 APK + 建 Release）。
   推之前本地先模拟门禁，并确认版本联动真的生效：

   ```bash
   TAG_VER="0.3.1"; CODE_VER=$(sed -n 's/^orilumn.versionName=//p' gradle.properties | head -1 | tr -d '[:space:]"')
   [ "$TAG_VER" = "$CODE_VER" ] && echo "门禁通过" || echo "门禁会红"
   ./gradlew :app:assembleDebug && ls app/build/outputs/apk/debug/*.apk   # 文件名应随 versionName 变
   ```
5. **装机验证前必须 `assembleDebug`**：只跑 `jvmTest` 不会重建 APK，此时 push 的是旧产物。
   本轮真被这个坑到——拿旧产物当 A/B 基线，把**正确**的实现测成「反斜」，差点把符号改回去。
   **任何截图/像素 A/B 对照，两个输入都必须当次构建，且以 `shasum -a256` 确认为不同版本。**

## 平板日志（无线 adb，vivo PA2353）

- 详见 `docs/调试日志与分页跟踪.md`；下面是每次必走的摘要。
- 连接：端口每次会变，优先 mDNS 自查（不用看平板）：先 `dns-sd -L "adb-400D91019H00000-rg2R7j" _adb-tls-connect._tcp local` 取端口（输出 `Android.local.:<端口>`），再 `adb connect 172.16.0.203:<端口>`；mDNS 无响应才去平板无线调试界面抄；双条目时命令一律带 `-s`。
- 定论只看落盘：`adb -s <addr> shell run-as orilumn.reader cat files/logs/日志_YYYYMMDD.txt`，配 `grep -E "Orilumn.FLIP|PGAP|PGL|DISK-HIT"`。logcat 会被 OEM 限流丢行，只做实时 tail。
- 禁止拉取 logcat（`logcat -d` 等）：系统级日志含其他应用隐私，只看 APP 私有目录落盘文件。
- 先看文件时间戳：无新日志 = 新包没跑起来，先 `am force-stop orilumn.reader` 再 `am start -n orilumn.reader/.MainActivity`。
- `Orilumn.DBGPAGE` / `Orilumn.View` 已退役（R1 后零调用）；当页区间看 `FLIP` 的 `slice`。