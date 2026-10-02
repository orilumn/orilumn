# Project Status

> Keeps a running log of significant milestones for the Orilumn reader engine. Supersedes
> everything marked done; each section reflects a completed stage.

## 2026-10-02 — 换自建断行引擎后代码块里的 Unicode 特殊字符不显示 / 显示乱码（取面缺保底）

**Issue.** 真机《Rust 程序设计语言》代码块里的特殊字符「要么不显示、要么显示乱码」，
且**是换成自建断行引擎之后才出现的**。

**不是断行问题。** 拿真书 `07.xhtml` 的 cargo 树（`├──` `└──` `│` 制表符 + `U+00A0` NBSP）过完整管线，
`skia` 与 `inhouse` 两个变体的**断行区间逐行完全相同**（`0..7 / 9..22 / 24..37 / 39..45 / 47..60 /
62..86 / 88..104 / 106..120`），换行保留、制表符与 NBSP 一个不少。落在**渲染层·字体解析**上。

**根因：换实现时把「系统级逐字形回退」弄丢了。**

- 旧的 `SkiaParagraphBreaker` 经 `SkParagraphFactory.defaultCollection()`
  （= `FontCollection().setDefaultFontManager(systemFonts())`）整形，**族栈覆盖不到的码本它照样画得出来**。
- 自建断行引擎把落墨换成 S5 逐字 `drawString` 后，取面只剩 `SkiaRunMeasurer.faceForCp` 一条路，
  而它**只在 CSS 族栈里找覆盖面**，找不到就交 `.notdef` ⇒ 豆腐块。

真书 `stylesheet.css:127` 的 `pre` 族栈是 `"Fira Code","Hack Nerd Font Mono","Roboto Mono",monospace`
—— 全是拉丁等宽族。实测（JVM 量宽器，fs=16，`tag=pre`）：

| 码本 | 族栈解出的面 | glyph | 结果 |
|---|---|---|---|
| `U+2500 ─` `U+2502 │` `U+251C ├` `U+2514 └` | Menlo | 2236/2238/2264/2256 | 正常 |
| `U+00A0` NBSP | Menlo | 98 | 正常 |
| `U+4E2D 中` | Menlo | **0** | **豆腐块** |
| `U+FF21 Ａ` | Menlo | **0** | **豆腐块** |
| `U+0432 в` | Menlo | 872 | 正常 |

`─│├└` 恰好在等宽面里有字形、`中` 与 `Ａ` 没有 ⇒ **「有的显示、有的不显示」**，与真机症状一致。

顺带量到一个**潜伏崩溃**：族栈里若一个可匹配的族都没有（如书只写了一个没装的字体名），
`measure` 的 `notdefWidth(fonts[0])` 会 `ArrayIndexOutOfBoundsException`。

**Resolution.** 在**渲染层·字体解析**补一张「通用面表」（`SkiaRunMeasurer.universalTypefaces`）：

1. 先按 `SkParagraphFactory.genericFallbackFamilyNames()` 的候选顺序取面（mono 时 `monospace`
   候选提前 ⇒ 代码块里的 CJK 落到等宽 CJK 面而非无衬线面）；
2. 再把系统里**其余**已装族按 `FontMgr.getFamilyName` 顺序补齐 —— 真·保底：**只要任何已装字体
   有该字形就找得到**；
3. 排除 `Last Resort`（它对任何码本都「有字形」，但画出来就是一块诊断豆腐块）。

**关键设计：保底表**不进热循环**。** 族栈面表恒只含 CSS 族栈的面（热路径逐值不变）；
`measure` 只在 `nPending > 0`（族栈**真的一个都不覆盖**）时另走 `universalPass` 扫保底表，
`faceForCp` 同样在族栈全灭后才查。两侧走**同一张表、同一条规则**（第一个
`getUTF32Glyph(cp) != 0` 即采用）⇒ **量画同源，不会出现「量取到保底面、画还在用 notdef」**。

代价：建表一次性付 `已装族数` 次 `matchFamilyStyle` native 调用（约 370ms / 326 族），
按 **(mono, 字重, 斜体)** 缓存、存 `Typeface`（不是 `Font`），与字号和族栈都无关。

**两个性能坑（都是本轮实测踩到，不是理论担忧）**：

- **坑 1：缓存键里带字号**。第一版把 `sizePx.toRawBits()` 放进键 ⇒ 每个新 font-size 触发一次
  326 族全量扫描；真书几十个尺寸桶 ⇒ `:app:testDebugUnitTest` 里两个 15 秒预算的跨章调度探针
  **双双超时**（`CrossChapterPreflightProbeTest` / `WholeBookCanonicalQueueProbeTest`）。
  昂贵的 `matchFamilyStyle` 与字号无关 ⇒ 键里删掉字号，换字号后 0ms。
- **坑 2：把保底面并进 `measure` 主循环**。主循环每面付 2 次 native 调用（`getUTF32Glyphs` +
  `getWidths`），数百个保底面 ⇒ 每段几百次。实测**正文段**（`STSong` 首族本已覆盖、根本不需要
  保底）**0.017ms → 整书 5000 段 85 秒**，同样把那两个探针顶爆。⇒ 拆成 `universalPass`，
  只在族栈全灭时才走。

附带修掉一个潜伏崩溃：族栈里一个可匹配的族都没有时，`measure` 的 `notdefWidth(fonts[0])` 会
`ArrayIndexOutOfBoundsException`（真书只写了一个没装的字体名即可触发）。

**锁**（`GlyphFallbackFaceTest`，5 把，每把单独变异验证过）：

| 锁 | 变异 | 结果 |
|---|---|---|
| 保底可达：族栈解不出、已装字体解得出的码本必须拿到有字形的面 | 摘掉两侧保底通道 | 红 |
| 量画同源：`advances` 的宽 == `faceForCp` 那张面的宽 | 同上 | 红（量=24.000 画=14.769，差 9.23px/字） |
| 不误伤：族栈能覆盖时仍取族栈那张面 | 让 `faceForCp` 先查保底表 | 红（拿到 Helvetica，栈是 Times New Roman） |
| 空栈不崩 | 回到修复前状态 | 红（`ArrayIndexOutOfBoundsException`） |
| 宿主字体集探针 | —— | 绿（326 族 / 9 个可用候选码本） |

锁 3 第一版是**假绿锁**：用等宽栈写的，而等宽场景下保底表队首（Menlo）与族栈解出的面**恰好是同一张
Typeface**，「在队首还是在队尾」根本测不出来 —— 变异验证当场抓出，改用衬线栈才拿到牙齿。
锁 1 也不能钉死 `中`：各宿主已装字体集不同，`中` 在某个宿主上可能已被族栈覆盖 ⇒ 静默空断言。
故锁 1 **在宿主上现挑**满足「族栈解不出 ∧ 已装字体解得出」的码本，挑不到就显式失败。
锁 4 的机制是「保底表保证面表恒非空」，不是那个越界守卫本身（守卫现在不可达，作为零成本的
防御保留）。

## 2026-10-02 — `pre` 代码块在书声明 `white-space: nowrap` 时连成一段并被裁（级联层降级）

**Issue.** 真机《Rust 程序设计语言》代码块全部连成一段、横向溢出页宽被裁。
不是引擎回归，也不是断行算法问题——**是书的 CSS 写错了，而我们的级联如实照做了**：

```css
/* book_1790865097552.epub / OEBPS/Styles/stylesheet.css:144 */
pre code { font-size: 0.8em; white-space: nowrap; }
```

`nowrap` 是**合法**值，所以声明真的生效。浏览器靠**横向滚动条**兜底不折行；
本项目是**页宽固定的分页阅读器**，没有横向滚动 ⇒ 不折行 = 溢出被裁 = **丢内容**。
且 `nowrap` 按 CSS 规范还会把源码换行**折叠成空格**，代码的换行结构被彻底抹掉。

此前 `480809c` 修过同一个症状，但那时书里写的是**拼错的 `nowarp`**（非法值 → 丢弃 → 继承
UA 的 `pre-wrap`）。2026-10-01 重新导入的书把笔误改成了合法的 `nowrap`，老修复就失效了。

**Resolution.** 在**级联结果**上降级：`StyleComputer.resolveWhiteSpace` —— 元素处于预格式化
语境（自身是 `pre`，或有 `pre` 祖先）时，把 `white-space` 的 `pre`/`nowrap` 降级成 `pre-wrap`。

- **为什么必须改在级联层而不是 `ua.css`**：`ua.css:32` 早就有 `pre { white-space: pre-wrap; }`
  （`InhouseParagraphBreaker` 的 KDoc 记着这次有意偏离浏览器），但 UA 是**最低优先级**、
  一定压不过书 ⇒ 要压过书只能在级联结果上改。
- **为什么只限 `pre` 子树**：`pre` 是作者显式声明的「格式敏感区」，在这里禁止折行与「保留格式」
  自相矛盾，降级没有语义代价。子树之外（正文里的短标签 `nowrap`）一律按浏览器语义放行、行为逐值不变。
- 一处改动即全链路生效：下游全部按 `WhiteSpace` 取值（`normalizeNode`/`finishLeaf`/`wraps`/
  `DrawLineBuilder.nowrap`/`BoxChapterLayouter`/`NormalFlowLayout`），无需各自打补丁。

**Locks (mutation-verified).** `PresentationAttrCascadeTest` 新增 4 把：`pre` 子树内合法
`nowrap` 降级 / `pre` 降级 / 声明挂在祖先时 `pre` 自身也降级 / **lazy `resolve` 与 `compute` 一致**
（两条级联通路必须同值，否则同一页代码块时折时不折）；另加 1 把负向锁钉住「`pre` 子树外不动」。
把降级改坏 → 4 把全红。端到端用真书 CSS + 真书 14.xhtml 最长 pre 块（241 字符一行）度量：
**13 行 / 换行保留**（改坏则 9 行 / 换行被折成空格）。
既有 3 条断言 `pre` 元素上 `white-space: pre` 得到 `PRE` 的锁按新语义更新为 `PRE_WRAP`
（其中 `T3 文本属性解析` 补了一条 `div` 上的对照断言，保证解析路径本身仍被覆盖）。
全量 `jvmTest` + `test`：tests=1268 failures=1（唯一红是既有的 `CrossChapterPreflightProbeTest`，Q12）。

**Side effect（正面）。** 本轮顺带**在真机上验证了上一轮的自动指纹**：
只改了 `StyleComputer.kt` 一处，指纹自动从 `0x5cd01516` 变到 `0x4dc7c35a`（人没碰任何常量），
落盘日志 `pagination decode null (layout-version have=4dc7c35a/sha256:4dc7c35a8a11)`
⇒ 旧分页表被拒、全章重排，正是设计意图。

**Pending / to verify on device.** 代码块恢复按版心折行、换行结构保留、不再横向溢出被裁。

## 2026-10-01 — 分页缓存失效自动化：`LAYOUT_VERSION` 改为引擎源码指纹，人不再需要记得 bump

**Issue.** 分页磁盘表的 key 是 `LayoutParamKey.hash()`，只含**版面参数**、不含排版算法版本。
所以「改了断行/度量算法但版面参数没变」时旧表继续命中，读者看到的是**上一版算法的分页结果**——
本轮修好软连字符行尾不画 `-` 后，真机「看起来完全没生效」，就是因为忘了 bump `LAYOUT_VERSION`。
现成的 `appVersion` 兜底也救不了：`BuildConfig.VERSION_CODE`（写死 `= 20`）与桌面
`DISK_CACHE_VERSION`（`= 1`）都是**常量**，只在发版升级时触发，开发期一次都不触发。

**Resolution.** `LAYOUT_VERSION` 从手写常量（历史 18…31）改为**引擎源码指纹自动导出**：
根 `build.gradle.kts` 的 `generateLayoutGeometryStamp` 对已登记引擎模块（`common` + `engine-skia`）
所有主源集文件内容求 SHA-256，取前 4 字节生成 `LayoutGeometryStamp.VALUE`。
源码变 ⇒ 值变 ⇒ 旧表在唯一的 `decode` 关卡被拒 ⇒ 自动重排；源码没变 ⇒ 值不变 ⇒ 缓存跨构建存活。
指纹故意取粗（宁可多作废不可少作废，多作废的生产成本为 0，因为发版本来就被 `appVersion` 全量覆盖）。
另加 `:unregisteredEngineCode` 守卫：未登记模块里出现 `orilumn.reader.engine` 包下的 .kt 直接 **fail 构建**
（把「新增引擎模块忘了登记」从注释提醒升级成硬检查）。`decode` 的 miss 原因带 `DIGEST_PREFIX` 便于诊断。

**Locks (mutation-verified).** 内容改动 → 指纹变 / 还原 → 回到原值；未登记模块放引擎代码 → 守卫红；
删掉生成物后 `:app:assembleDebug` 仍通过（`:common` 全任务 dependsOn 指纹任务）。
顺带把 `PaginationCacheTest` 里写死 `layoutVersion = 0` 的几何关卡锁改成 `LAYOUT_VERSION + 1`
（不依赖「指纹恰好非 0」）。全量 `jvmTest` + `test`：tests=1031 failures=0 errors=0。

**Lessons.** ① Kotlin 块注释**可嵌套**——在 KDoc 里写 glob `src/*Main` 会让 `/*` 打开嵌套注释，
把整份构建脚本剩余部分吞掉，**脚本照样编译通过、构建照样成功、连故意写错都不报错**，
只表现为「任务找不到」。⇒ 构建脚本的说明一律用 `//` 行注释。
② 任务依赖不要按任务名过滤：KMP-AGP 的 android 目标编译任务叫 `compileAndroidMain`，
`startsWith("compile") && contains("Kotlin")` 会漏掉它。

## 2026-09-30 — 排版 R1：带 `text-indent` 的单行段「只差一两个字却不换行、尾部被裁」（LAYOUT_VERSION 30）

**Issue.** 一段本来只有一行的文字，在「版心宽 − 边距」恰好比它的整段宽少 1~2 个字时，不折行也不掉字，
而是尾部 1~2 个字被版心右缘切掉（读者看到「这段字被拦腰截断」）。真机参数 + 真书
（《每天都离现形更近一步》ch1，正文 44.4px、`p { text-align: justify; text-indent: 2em }`）复现：
版心 1288 时该段右缘 1376.43，**溢出 88.4px = 2 个字**。

**根因（排版层-下，`SkiaParagraphBreaker`）。** 断行侧用 SkParagraph 的 `TextIndent` 承担首行缩进，
而 `TextIndent` **只在首行真的折行时**扣减首行可用宽；「整段放得下一行」的快捷路径拿整段自然宽
直接比 layout 宽，**不扣 indent**。真机字体栈（`STSong, serif` @44.4px，26 字段 `nat=1287.60`，
`indent=88.80`）下「整段单行」区从 `ceil(nat)=1288` 起，对 indent 三个取值（0 / 44.4 / 88.8）
**完全相同**。于是窗口 `版心 − indent < 整段自然宽 ≤ 版心` 内断行侧判「放得下」不换行，绘制侧
（`LineWindowDrawer`，`paintX = textX + firstLineIndentPx`）仍把整行右移 indent → 右缘越出版心至多
indent。只在一行时发作：一旦折行，Skia 自己就把首行按 `版心 − indent` 排对了。

**修复。** `SkiaParagraphBreaker.breakLines` 抽出 `layoutOnce`（单次整形 + 区间归一化，配置同源），
命中窗口（单行 && `nat + indent > 版心`）时按首行真实可用宽 `版心 − indent` **整段重排**、
`firstLineIndentPx` 传 0 避免二次扣减。修后单行区从 `ceil(nat + indent)` 起——正是 CSS 语义。
整段都用窄宽仍与 CSS 等价：命中窗口时整段自然宽 ≤ 版心，贪心首行把窄宽填到「差一个字就溢出」，
剩余尾巴宽 ≤ indent + 一字，远窄于版心，故第 2 行起在两种宽下的断点必然相同。
重轻两路共用这一个断行器，一处修好两条路；`LAYOUT_VERSION 29→30` 让旧表作废自愈。

**机制证明（`FirstLineIndentSingleLineOverflowTest`，engine-skia jvmTest 6/6；修复前 2 条红）。**
① 命中窗口多字号扫描必须折行且首行不越版心（真机 88.4px / fs=16 时 32px 两档都复现）；
② 整段 + 缩进放得下时保持单行（不多折）；③ 无缩进路径逐值不变（不误伤）；
④ Latin × LEFT/JUSTIFY/CENTER 不溢出；⑤ 真书重路径全链路扫版心 1280~1640 × 两个字号，
用**绘制侧真实整形结果**（同配置同 `lineWidthPx`）断言「首行缩进 + 行宽 ≤ 版心」且不二次折行；
⑥ 重轻两路断行区间逐项相同。度量用 Skia 自己的 `lineMetrics[0].width`（行尾空白不计）而非
`maxIntrinsicWidth`（含行尾空格 advance，会虚报约一个空格宽的溢出）。

**教训（两处把我带沟里的探针错误，留档）。** ① 扫描上界必须远大于阈值：`nat+2` 恰好落在单行区内，
测出的「阈值 = nat」是扫描顶的假象；② 扫描起点必须向上取整：`nat.toInt()` 在自然宽为小数时落到
阈值下方，扫到的全是已折行的版心，单行失效区被整段跳过。两者都让回归在修复前后都绿。

**顺带排除一条伪 bug：开书「被调两次」是日志误读。** 10 次开书会话里 `onCreate` / `open ok` /
`open: chapters=` 各 10 次（1:1:1），`viewport changed` 0 次——`openBookEngine` 每次只调一次。那两行是同一次
开书的两个阶段（Activity 建容器 :271 → `push("open")` 触发 `controller.open()` :418），因
`ReaderActivity.kt:250` 把自己的 `TAG` 传进控制器导致两行同 tag 同前缀；`chapters=0` 则是
`chapterCount = book?.spine?.size ?: 0` 在 `book` 赋值前记的，**架构上恒为 0**。详见 TODO 对应条目。

**Pending.** 无代码待办。下一个排队 bug：目录跳转点击无响应。

## 2026-09-30 — 图盒比例修复：persist 加载路径补 bindChapterFor（LAYOUT_VERSION 29），真机三态闭环

**Issue.** 重开《摄影的艺术》ch44 persist 加载路径不调 `readChapter`（chapterHref 空），
`NormalFlowLayout.intrinsicSizeOf` 跳过图片二进制探测——传统/现代主题下 pos=100% 标签探不到内在比时
图盒被打成 `width/2` 高（795），真实复现：重开位置漂移（图页只显示一半图）、ch44 图盒比例错误。

**Fixes（排版层-上）。** `BookDocumentController` 15 处入口补 `bindChapterFor(unit)`（helper+绑点，
persist 与 read 双路径对照 :600-:689）；`PaginationCacheCodec.LAYOUT_VERSION 28→29` 保证旧表作废自愈。

**机制证明（引擎级回归，engine-skia jvmTest 6/6）。** 新增
`PhotoPersistFullWidthRegressionTest.kt` 钉死三态：传统+href 绑定=1590×1920（=历史正确）；
传统+空白 href=1590×795（=历史 bug 精确值）；原书设置=915×1105（设计行为，非 bug）。

**真机实证（平板落盘日志，口径见 `docs/调试日志与分页跟踪.md`）。** 原书设置（bug 基线）
PGAP `firstLineH=1105`；切传统模式触发 `whole-book relayout` paramHash 838300637→186714974；
删表→冷启动→persist 重排 ch44（bug 原始路径）PGAP `firstLineH=1920` ✓；再 force-stop 冷启动
persist 加载 ch44 仍 `1920` ✓（DISK-HIT 命中磁盘表）。1920 = 内容宽 1590 × 内在比 1.2077（915×1105）。

**Pending / to verify on device.** 无（真机闭环）。分支 `fix/photo-relayout-height` 待合 main。

## 2026-09-30 — release v0.2.1 (versionCode 19)

Merged `fix/scheduling` (27 commits) into `main` and shipped as **v0.2.1**: pagination persistence
(P0–P3), scheduling fixes (open 落位章同步独占), media 门收窄, and the R26–R29 open-book performance
work below. Version bumped `0.2.0 → 0.2.1` (android `versionCode 18 → 19`, `archivesName`
`orilumn-0.2.1`, desktopApp `packageVersion` synced); annotated tag `v0.2.1` pushed.

## 2026-09-29/30 — 开书性能实证整改（R26–R29）：totalChars 恒等式、两条全章扫描、暖机净亏关闭、cEdge −25%

**Scope.** GIMP 开书慢（旧包中位 15.1s，曾误报 21.4s——那是跨会话基线，作废）的实证整改收口。
四条定论全部来自平板落盘日志（口径见 `docs/调试日志与分页跟踪.md`），不靠读代码猜——此前累计
五次"读代码看出的浪费"（级联深度 / 通用字体兜底 / 重复查表 / 预编译 / 手工扫描）全被实测否证。
完整实证与原始表格：`docs/待分析-GIMP开书慢-结论清单.md` §3–§3b.5 / §4.1。

- **`totalChars` 计算属性（开书 `pg` 1252 → 4ms）** —— 根因：`totalChars` 写成计算属性，
  每次访问整章重算（真书 558 章量级 ≈ 9.7s）。改 `by lazy` 后开书总时长 2.70s → **1.07s**，
  锚页 `pg` 4–5ms，`DISK-HIT` 排版段 2161 → 505–550ms。
- **`block(i)` 的 `textLength` 白算一次 advance（227ms）** —— 每物化一块重跑
  `styledCharAdvance`（与 `totalChars` 同一个函数）。改走 `globalCharStarts` 差分：精确整数前缀和，
  与原口径逐值相同（`BlockTextLengthIdentityTest` 6 例锁死）。净省 78ms——省下的是非级联那部分
  文本测量（级联缓存热度转移 `sStyles` 3 → 140ms 是记账搬家，不是新增开销）。
- **`computeFloatLeads` 开头那次全章扫描（275ms）** —— `floatLeads` 的 `by lazy` 为回答
  "这章有没有 float" 遍历全章每个叶子的级联（Rust ch7 = 169 叶）。改在 `computeStructure` 那一趟
  顺手产 `anyFloat` 标志（口径与 `blockStyleFor` 逐值一致，含 `#text` 叶取父级），随叶表持久化
  （codec `VERSION` 2→3、`STRUCTURE_VERSION` 1→2，旧 bin 解码失败自动重算自愈）。
- **锚页 `shape` 584 → 260ms** —— 上面两条合计：`DISK-HIT shape` 775 → 431ms，
  `sBlk` 522 → 22ms；`openT` 1297 → 964ms。锚页真排版仍只占 `sStyles+sSkia ≈ 240ms`，
  其余在 `asm shape` 窗口外（prepareLight/结构缓存/表载入 ~170ms）未拆。
- **开书暖机 A/B 定论：净亏，已关** —— 曾断言 350ms 暖机是一次性固定成本；两条反证推倒：
  同进程内 ch4 vs ch0 暖机差 11 倍（346~419ms vs 30~37ms，R22–R25 逐次复现）、固定成本模型解出
  负单价（`F+3p=350`、`F+13p=304` → `p=−4.6ms/块`）。A/B（同锚页 3×3 交叉）：预热把锚页
  Skia 断行 85 → 45ms，但 3 块代价 367ms > 锚页 5 块本身 262ms，端到端 **+50ms**。
  `OPEN_WARMUP_BLOCKS` 置 **0**；日后只试更便宜的组合（1 块/换块），别直接开 3。
- **R28 · 测量基建（单装机运行期 A/B）** —— `AbSwitch` 从 engine-skia 移到 common（包名不变；
  `StyleComputer` 在 common，engine-skia 单向依赖 common，反向引用不成立）；`expected_describe()`
  归一化；`tools/ab_probe.py` / `ab_detail.py` / `ab_families.py`。修掉三个把数据带偏的坑：
  asm 配对错位、DISK-HIT 后台预排全 0 行混入、`warm=` 缺席即 0。
- **R29 · `cBuild` 三层拆分，`eCol`+`eSty` 命中（`cEdge` −25%）** —— `cBuild` → `cEdge`/`cFont`/
  其余；`cEdge` → 边六家族（`eBox`/`eWid`/`eCol`/`eSty`/`eRad`/`eBrd`）。前两层三条假设全否证：
  重复查表仅 14 处（8 槽 16 次哈希现算砍不下）、`Pattern.compile` 现场编译预编译臂零差（34/34ms）、
  `Pattern.split` 换手工字符扫描反而慢 8ms（8/8 全分离，已删）。命中：`parseBorderColors`/
  `parseBorderStyles` 是唯二无条件 `listOf(4)` + 4 次 `"border-$side-color"` 拼新字符串查表的函数，
  且实测 `eBrd=59/52`——多数元素只声明一两边。改显式四槽 + 4 侧 × 3 类 key 提为 companion 预建常量。
  **R39 交叉 8+8 实测**：`eCol` 5 → 1ms、`eSty` 8 → 5ms、`cEdge` 37.5 → 28ms（−25%）、
  `cBuild` 57.5 → 51ms、`openT` 731 → 708ms（−3.1%）；固化后 4 跑复核 `cEdge` 24~28ms。
  `regexHoist` 开关保留（默认关 = 原样，供换书/换锚点再量）。
- **顺带暴露的既有缺口（非本轮引入）** —— 容器 `float` 在轻路径不生效（`<div style="float:left">`
  的祖先 float 未注册、叶表里没有它）；`LightFloatLeadTest` 以 `KNOWN GAP` 命名锁定现状（断言全 null），
  真修复（前向透传注册祖先 float）是行为变更，单独立项。`anyFloat` 标志与缺口无关（07f6666 回退对照证明）。

**Verification.** 811 例 JVM 测试全绿（clean 后重跑）；0 崩溃；页数/区间无漂移（与基线逐值相同）；
设备装 R40 复核 `cEdge` 24~28ms。GIMP 按 7.3ms/块推测 `totalChars` 一项即 ~9.7s → 开书应掉到 ~5s 附近，
未实测（换书是为了脱离病态样本，不是 GIMP 不该修）。

## 2026-09-29 — 分页持久化 P1–P3 与调度收口（磁盘表零文本 IO）

**Scope.** `docs/分页计算集中化方案.md` 的 P0–P3 在 `fix/scheduling` 全部落地：磁盘分页表、章结构
（树+sheets）与派生产物持久化到应用私有存储，重开书免 epub 文本 IO。真书 557/557 验收通过。

- **磁盘分页表缀构建号 + 开书清旧表** —— 升级即失效（旧 `LAYOUT_VERSION` 之外的又一道守卫）。
- **P1 整章文件持久化** —— `_sheets.bin`（全书去重）+ `<ch>.bin` 存后处理树 + sheet hash 表；
  open 免 epub 文本。导入 `BuildStats(558/558/0/0)`；打开 45 章 `loaded from persist`、
  `readChapter` 零次、翻页正常。半路修过真 bug：`flowChildren` 合成匿名叶游离（parent 挂了但不在
  children 里）致整书 extract 全挂，现按"树成员走路径、合成节点走属主+文本重建"双轨（`LeafRef`）。
- **P2 打开时小项** —— spine map book 级 memo（`spineIndexCache`）、`demandCache` 按 css 指纹 memo、
  `linkRangeCache` 章级按 `paramHash` 归位；`anchorCharStart` 的 fragment 搜索与所查表同生同死、
  hoist 即 unsound，保持 per-jump（P2 "不 memo 项的真实理由"已记）。
- **P3 重路径吃现货** —— `prepare` 复用已持久化的 `genStrings`（绑定 gen 字符串，只在 CSS 无变化时
  有效，`PersistedStructureRelinkTest` 锁）。
- **`shapeGeometry` 一次分段五家共享** —— 6 遍子树分段 → 1，span 大书结构性受益。
- **media 门收窄到真视口条件** —— 裸 `screen` 不再挡住持久化（GIMP 29/29）；`@media` 仍门住读写两侧。
- **调度** —— open 落位章同步独占（prewarm/B2 跳过，删双端死调用）；`pg` 三段计时 + double-open TODO。
- **P4 取消（2026-09-29 实测推翻前提）** —— 重 `prepare` 8s/书中 cascade 仅占 ~1s（1.8ms/章），
  `layoutBoxes` 内含 skia 逐块断行占 ~7s，塑形归位正确不可移；bake 只省 10%，复杂度不值。
  塑形成本转 P5（advance 共享）。轻路径 `floatLeads` 全章扫描残留的 `sStyles` 见上一条目。

**Verification.** `:engine-skia:jvmTest` 全绿；真机 557/557 页无漂移。`genPhase1` 双路等价
（heavy/light 同一来源）由 `PersistedStructureRelinkTest` 等锁定。

## 2026-09-15/19 — KMP+CMP migration (S01–S35) + C-series platform convergence: whole project closed

**Scope.** The full Kotlin Multiplatform migration (`docs/KMP迁移-分步计划.md` S01–S35, atomic
one-commit-per-step) landed 2026-09-15/16, then the C-series orchestration convergence
(`docs/C1-实施步骤.md`, C1-0…C1-3) closed on 2026-09-19. Per `docs/平台一致性整改方案.md` §7 the
mother doc's rows **A/B/D/E/Q1/C are all closed**: one style source, one shaper/breaker, one font
pool, one image decode, one pagination table/store, one orchestration host shared by tablet and
desktop. Both long-deferred TODO items (阅读定位 D / 三线程优先级 F) were consumed in the same pass.

- **Module shape (final)** — `:common` (KMP: androidTarget+jvm; engine/css, paging, layout,
  laying, html, epub, settings, font, text + io/log expect/actual), `:engine-skia`
  (SkParagraph shaping, `SkiaParagraphBreaker` + `BookEngineSwitch` dual-engine switch,
  `LineWindowDrawer`), `:shared-ui` (CMP shelf/reader/settings+TOC, S26–S29), `:desktopApp`
  (JVM shell hosting `App()`, S32), `:app` (slimmed to a thin host, S31).
- **JVM-only deps replaced** — jsoup→fleeksoft Ksoup (S10–S12), `javax.xml`→self-built mini XML
  DOM with XXE guard (S13–S16), `org.json`→kotlinx.serialization (S17–S18),
  `java.util.zip`→okio (S15), Room→SQLDelight v4 with shared KMP shelf db (S33/S34b),
  `WifiFontServer`→Ktor in `common/net` (S35), `util/FileLogger`→common `Logger` (S31-cleanup).
- **C1-0 dual-source unification** — incremental/temp shaping now runs on Skia single-source;
  the Android `StaticLayout` shaping primitive is retired (remaining mentions are historical
  comments only; geometry is plain data on `ParagraphShape`).
- **C1-1 pagination single-source** — table + codec + okio `FileSystem` store into `common`;
  same bytes/filename/`LAYOUT_VERSION` invalidation for both hosts; `PaginationCacheTest` 7 cases.
- **C1-2 orchestration into common** — `TempNavigation`/`FragmentAnchors` pure logic, window
  state machine, save-order contract, and the **F>A>B1>B2>P priority contract with injectable
  dispatchers** (`BookDocumentController` KDoc `:66`, `injectedCanonicalDispatcher`); desktop
  consumes the identical contract. TODO D: `onSaveProgress` finalizes the temp table before
  persisting (canonical char authority) + seam/handoff canaries; TODO F: contract written, no
  longer "missing".
- **C1-3 desktop same host** — `DesktopReaderHost` write-through on the shared `PaginationCacheStore`
  (same `LayoutParamKey`, hit reports disk page count), background canonical/whole-book prefill
  **not yet enabled** on desktop (single-chapter lazy load covers the open mode); enabling it must
  reuse the same dispatcher split.
- **Verification** — `:app:testDebugUnitTest` 122/122 + `assembleDebug`, `:common:jvmTest` 347,
  `:engine-skia:jvmTest`, `:shared-ui:jvmTest`, `:desktopApp:test` 4/4 all green.
- **Deferred / next** — phase **L (iOS)** not started (no `iosApp`; needs iosMain actuals for
  mDNS/paths/fonts); device re-verification of TODO D (save-char vs restore-char) still unrecorded.

## 2026-09-18 — Layout Priority & Concurrency P-series (P1–P14): all 14 landed + P9 corrective fix

**Scope.** The responsiveness/priority/concurrency roadmap
(`docs/layout-priority-and-responsiveness-improvements.md` P1–P14, design spec
`docs/layout-priority-and-concurrency-design.md`) completed in one day: priority contract F>A>B1>B2>P,
unified miss flow (U0–U9), bounded contiguous sliding window ±1 with discard-and-restart, unified
promotion-on-leave, and the 4–5 core concurrency layout. All items verified by probes T1–T8 and both
regression suites (`:common:jvmTest`, `:app:testDebugUnitTest`).

- **P1 birth-window atomization (R1)** — the anchor page is shaped into locals, then the commit
  (bindInProgress + fill window + setCurrentTempPage) is atomic under `tempStateLock`; flips `await`
  the birth signal and re-read state. Probe T1 `TempBirthWaitRegressionTest`.
- **P2 whole-book epoch (R4)** — `prepareRelayout` bumps `layoutEpoch`; B1 carries it and skips stale
  segments; the params-settled point re-dispatches B2 with the current hash. Probe T3.
- **P3 per-chapter canonical slots (R3)** — `canonicalJobs[chapter]`: same-chapter new params cancel,
  cross-chapter queue-not-kill, so a just-tuned chapter's table always lands. Probe T4.
- **P6 jump gate (jump responsiveness)** — single-slot `JumpGate`: cancel/dedup/epoch-guard + gold
  top-edge load line; a seek-toc-burst converges on exactly the last landing. Probe T2.
- **P7 timely abandon** — `fullLayout` gains a per-block `checkpoint` (foreground default no-op);
  B1/B2 pass `ctx.ensureActive()`, cancellations surface as `CancellationException`, never a truncated
  table; the large-chapter anchor shape is skipped when the epoch went stale. Probes T5 + P13's.
- **P11 unified promotion on leave** — all three jump paths + flip-leave finalize the old chapter's
  temp session (`finalizeOnLeave`), closing the "re-entry lands on the old anchor" residue. Probe T8.
- **P10 bounded sliding window** — temp pagination is a contiguous ±1 window with far-end eviction and
  `WinUnit.{Fwd,Bwd,Pair}` torn-pair atomicity; window-outside = discard-and-restart; exposed two latent
  bugs (`locateTempPosition` (blockStart,charStart) key; `stepTempPrefill` neighbor liveness/trim).
  Probe T7 `WindowSlideDiscardProbeTest`.
- **P14 cache lifecycle + LRU** — `PaginationCacheStore` trims each book's directory to 32
  least-recently-used tables; `paramHash` completeness verified (useOriginalStyle + userCssHash slot).
- **P12 scan ordering** — B2 follows reading direction × distance (`orderRemainingChapters`).
- **P4/P5 preflight** — neighbor chapters parse + light-prepare off the flip thread; open-book first
  screen becomes a promote. Probes `CrossChapterPreflightProbeTest`/`OpenBookPreflightProbeTest`.
- **P13 chunked canonical (U6k)** — `fullLayoutChunked` slices shaping across a low-priority chunk pool,
  equal to sequential canonical by construction. Probe T6 `ChunkedCanonicalEquivalenceProbeTest`.
- **P8 head lift (R2)** — near-head anchors (≤ `HEAD_START_BLOCK_LIMIT`=100) re-anchor at the true
  chapter head so temp and canonical share the same head source. Probe `HeadLiftProbeTest`.
- **P9 R5 corrective fix (landed same day)** — temp sessions now shape from a
  **frozen** `ip.profileSnapshot` captured at `startAnchorStream`, never the live controller
  `profile`; a flip racing a typography tune can no longer flash one mixed-param frame. (Documented in
  the plan as "documented, fix deferred"; implemented once Phases 1/2/3 were live.)

**Full table below** (2026-09-14) covers the list-marker `liOf` generalization; the detailed per-item
logs live in `docs/incremental-vs-full-layout-investigation.md` ("Implementation Log").

## 2026-09-14 — List markers attach to `<li><p>…</p></li>` (first leaf per item only)

**Issue.** Books that wrap every list-item body in a `<p>` (Manning/calibre exports — the Kotlin in
Action book here is the concrete case: 405 of 476 `<li>` are `<li><p class="list">…</p></li>`)
rendered with no bullets and no hanging indent: only per-chapter summary lists, whose `<li>` holds
direct text, looked right. The book CSS is valid; a browser puts the marker on the `display:list-item`
`<li>` regardless of its children.

**Root cause.** `ListMarkers.liOf` only attributed two leaf shapes as marker carriers — a `li` text
leaf, or a nested `<li>`'s leading anonymous `#text` leaf. In `<li><p>…</p></li>` the layout leaf is
the `<p>` (a block child), so `liOf(p)` returned null, `listMarkerFor` yielded null, and
`ParagraphShape.drawListMarker` was a no-op: no bullet, no hanging gutter, just the raw `ul` padding.

**Fix.**
- **`liOf` generalizes** to the nearest `li` on the leaf's ancestor chain (itself included), so a
  `<p>` (or any block child) inside a `<li>` now belongs to that item.
- **New `ListMarkers.firstCarrierSet`**: for each `<li>` in a document-ordered leaf list, exactly the
  **first** leaf carries the marker. `<li><p>a</p><p>b</p></li>` draws one bullet (on `a`); the second
  `<p>` renders as a plain paragraph, matching CSS where the bullet belongs to the item box, not to
  each nested block. Nested `<ul>` leaves claim their own inner items, so inner lists keep their
  bullets.
- **Both paths gate on the carrier set**: heavy (`fullLayout` → `firstCarrierLeaves(prepare.leaves)`)
  and light (`LightPrepare.firstCarrierLeaves`, lazy over `markupLeaves`) pass it into `listMarkerFor`,
  which now returns null for non-carrier leaves. This also fixes a latent mis-attribution where stray
  trailing inline text after a `<p>` in an item would have claimed the bullet.

**Tests / build.** `ListMarkersTest` +3 cases (`liOf` ancestor walk, first-leaf-per-li gating,
nested-list carriers); full `:app:testDebugUnitTest` green; `compileDebugKotlin` green. Marker
placement on device pending (uikit).

## 2026-09-13 — Shaper: drop the trailing phantom blank line in pre/code blocks

**Issue.** The tablet's Rust book (`book_47`) ships a *different* stylesheet than the repo's
`rust_cn.epub`: `pre{margin:0.5rem 0; padding:0.5rem}` (a plain **leaf** — no `code{display:block}`),
`.filename{display:block; padding-top…; background}`, and `.filename + pre{margin-top:0}`. Because `pre`
is a leaf there, the container-margin fix (above) does not touch it — yet every code block rendered with a
large empty background **below** the last line ("大片空白"), confirmed by pixel measurement (~one line-height
≈ 53–67px) and by an on-device `PREGEOM` probe. Root cause: every `<pre><code>…\n</code></pre>` ends in a
trailing `\n`, and the *draw/light* shaper (`ParagraphShapes` → `StaticLayout`) emitted it as a final
**empty line** (`lineStart == lineEnd`, full line-height). The heavy measure shaper
(`StaticLayoutBreaker`) already skips such lines, so drawing/backgrounds were a line-height taller than
geometry.

**Fix.** `ParagraphShape` now trims lines **after the last non-empty one** (`trimmedLineCount`). Kept lines
are always a contiguous prefix `0..last`, so `getLineTop/Start/End(k)` still index the same StaticLayout —
no char loss; blank lines *between* code are preserved. Bumps `LAYOUT_VERSION` (10→11) so stale pagination
tables re-flow with the corrected geometry. Compiles; geometry suites green. On-device pixel confirmation
was partially blocked by a pre-existing `ReaderActivity.onContainerTouch` crash under programmatic input,
so the tablet should be re-checked by hand.

## 2026-09-13 — Box model: container margins + border/padding now follow CSS in vertical flow

**Issue.** In the rust book ch1, `blockquote`/`.note` boxes and `pre` code blocks carried a visible
`background-color`. Two stacked defects made every non-text block's vertical layout deviate from CSS:
(1) `pre > code { display:block }` turns `pre` into a **container** box, and the container branch of
`NormalFlowLayout.emit` never consumed its **own margin** (an "entered top-aligned" shortcut), so blocks
sat flush against the paragraph above; (2) containers also never advanced the cursor by their **own top
border+padding** (nor past their bottom border+padding), so a container's background box was derived as
`firstChildTop − edgesTop`, extending *above* the preceding block's content bottom and painting over the
text above, while a following sibling was pulled up into the container's padded bottom. Together they
read as "negative margin-top / overlap" plus a "large bottom blank" and generally scrambled margins.
Non-container leaves already consumed their own edges, so plain `p`/labels were unaffected — only
containers (`.note` with `<p>`, `pre` with a `display:block` `code`) showed it.

**Fix (single-source, both layout paths — CSS box model).**
- `NormalFlowLayout.emit` container branch now mirrors a leaf: it consumes its **own collapsed top margin**
  (`collapse(margin.top, prevBottomMargin)`), advances the cursor by `border.top + padding.top`, lays out
  children, then extends the cursor past `border.bottom + padding.bottom` and returns its **own bottom
  margin** for the next sibling to collapse with. Margins, borders and padding all now participate in the
  flow exactly as CSS specifies.
- `NormalFlowLayout.consecutiveLeafAdvance` (the light/incremental gap) reproduces that full gap: the LCA
  sibling margin collapse + top edges/margins of every container on the next-leaf path + bottom edges of
  every container on the prev-leaf path, so light spacing mirrors heavy byte-for-byte (held by
  `BoxSharedGeometryTest`).

**Tests / build.** Added `preContainerPaddingDoesNotOverlapPrecedingText` and
`followingSiblingDoesNotInvadeContainerBottomPadding`; the old `nextInsideContainerDropsPrecedingMargin`
was corrected to `nextInsideContainerCollapsesPrecedingMargin` (CSS collapse → 40). Geometry suites
(BoxShared/BoxLayoutMargin/BoxLayoutFlow/BoxNested) green; `:app:assembleDebug` + `installDebug` to tablet.

**Verified on device.** Rust book ch1: `pre` blocks and `.note` boxes now render with a clean CSS margin
gap to the text above, **no overlap**, and symmetric healthy padding (residual, minor bottom-heavy inside
code blocks traces to a trailing `\n` in the EPUB's `<code>` creating one extra line — a known, separate
minor quirk, not the reported bug).

> Heads-up: the 10 pre-existing test failures on `HEAD` (27522a3) were stale expectations after the
> color parser switched to `#AARRGGBB`; the engine was already correct. Updated CascadeTest /
> BoxNestedLayoutTest color assertions to `#AARRGGBB`; they now pass. The remaining
> `BoxPathConsistencyTest.img is a replaceable block` asserts img-as-block, but the engine deliberately
> treats `<img>` as default-inline — a separate stale expectation, deferred.

## 2026-09-13 — Incremental/light path: cache typography-independent structure (prepareLight ~900ms → ~3ms)

**Issue.** Tuning a typography slider (line spacing / font size / letter spacing) forced the whole *light*
prepare to re-run every time: device profile of `prepareLight` on the rust book ch4 showed
`enumerate=755ms + charStarts=131ms + styleEngine(CSS parse)=28ms ≈ 914ms`, and during a slider drag
`prepareLight` re-fires many times/sec. All of it is typography-invariant (depends only on DOM + CSS +
`display` classification), so it was pure re-computation.

**Fix (two layers).**
- **Block structure cache** (`ChapterStructureCache` on `ChapterUnit`): memoizes the light-path leaf set
  (`enumerateBlockLeaves`) + `globalCharStarts`, keyed by `cssBundle` + `useOriginalStyle`. Survival
  of `invalidateLayout` (which fires on every param change) is deliberate — the key self-validates only
  against structural inputs.
- **Parsed CSS cache**: the parsed author stylesheets (`LightCssParser` output) are stored on the same
  cache; `styleComputerFor` accepts pre-parsed sheets and the light path skips re-tokenizing CSS. The heavy
  `prepare()` path is unchanged (still parses when none are supplied).

**Result (measured on device).** `prepareLight` cache-hit: original ~914ms → Phase-1 (~40ms, leaves/charStarts
cached) → Phase-2 (`3–12ms`, parsed CSS reused). Rendering verified with no regression.

**Design doc.** `docs/incremental-layout-plan.md` §1–§5 (micro-profile + knob-invalidation table + two-layer
cache). Commits `0d75b0c` (BlockSkeleton) `9b5847a` (parsed CSS).

**Not yet done.** The interim *correctness* fix (blockquote/container backgrounds lost in the incremental
path because `PartialDrawableLayout` gets leaf boxes only) is documented in the plan §4.3 as pending a
decision; the two `drawPageSlice` implementations remain duplicated.

## 2026-09-12 — Browser-consistent list hanging: text at contentLeft, marker hangs left

`list-style-position: outside` now matches browsers: the item text starts exactly at `contentLeft`
(= the `ul/ol` `padding-left`), and the marker hangs in the gutter to its left (drawn at a negative
frame x) instead of pushing the text by marker-width. So `ul,ol{padding-left:2em}` yields text
indented 2em (≈2 CJK chars), nested `ul/ol` each add their padding via `contentLeft` accumulation
(2em per level). `inside` keeps the marker inline (first line indented, wrapped lines return to
contentLeft). Both draw paths widen their leaf clip (`listMarkerClipLeft`) so the hanging marker is
not clipped. Emphasizes the engine principle: box model + cascade mirror a normal browser; EPUB-only
defaults belong in an upper layer.

**Tests / build.** Pure-JVM suites green; `:app:installDebug` to tablet.

**Pending to verify on device.** `ul{padding-left:2em}` → text at 2em, marker hanging left; nested
lists indent 2em per level.

## 2026-09-12 — Box model: consume block-level horizontal offsets; list markers honored

**Issue.** `contentLeft` was never accumulated from block horizontal edges (padding/margin/border-left)
and text was drawn at x=0, so book CSS like `ul,ol{padding-left:2rem}` had no effect and list markers
sat tight against the text.

**Fix.**
- **Horizontal accumulation (single-source).** New `NormalFlowLayout.descendContentLeft` mirrors
  `descendContentWidth`: each box's border-box left = parent's + ancestor left border/padding + own
  left margin. `buildBoxTree` threads `childLeft` top-down; light `LightPrepare.block(i)` uses the same
  descent; `tableRowLayoutFor` bakes the row left into cell x. Only `contentLeft`/draw-x change —
  `contentWidth`, line breaking and `globalCharStarts` are untouched (char authority preserved).
- **Render at contentLeft.** Both `BoxDrawableLayout.drawLeaf` and `PartialDrawableLayout.drawLeaf`
  (incl. replaceable/img) x-translate by `contentLeft + own left edges`; page reading margin applied
  outside.
- **List refinements.** Sizeable vector-drawn bullets (disc/circle/square) instead of font glyphs
  (fixes "bullet looks like a period"); marker→text gutter = marker width + gap (0.6em) with marker at
  the item content start; li-level `list-style-type`/`list-style-position` override; `ol start/reversed`
  attrs preserved. Nested indentation is now fully CSS-driven via contentLeft.

**Tests / build.** Pure-JVM `BoxLayoutMarginTest`/`BoxNestedLayoutTest` assert `contentLeft` accumulates
(ul padding → li at that x; plain p stays 0) and existing suites stay green; `:app:installDebug` to tablet.

**Pending to verify on device.** Confirm `ul{padding-left}` now shifts whole lists right, markers gap
from text, nested lists indent per level, and table/images honor padding.

## 2026-09-12 — List markers: EPUB-standard ul/ol rendering

**Issue.** `ul`/`ol`/`li` were laid out as plain blocks with no markers, so lists rendered as
indistinguishable body paragraphs. Needs: bullets, ordered numbering, `start`/`reversed`, `inside`/
`outside`, per-level nesting reset, and list items must NOT receive the body first-line indent.

**Design (marker as overlay, char stream untouched).** Rather than prefixing marker text into the
character stream (which would disturb heavy/light `globalCharStarts`), markers are a visual overlay
drawn per `ParagraphShape` (the engine's `BulletSpan`): the `<li>` text reserves a gutter via a
`LeadingMarginSpan.Standard(first, rest)`, and the marker glyph is drawn in that gutter at the first
line's baseline. Char accounting (`visibleCharAdvance` / `textLength` / `globalCharStarts`) is
untouched, so pagination/selection/progress stay consistent.

- **`ListMarkers` (pure JVM)**: node attribution (`liOf`: `<li>` leaf, or a nested `<li>`'s leading
  anonymous `#text`), `list-style-type` → disc/circle/square + decimal/decimal-leading-zero/
  alpha/roman (default disc/decimal; `none` → no marker), `list-style-position` inside/outside,
  `start`/`reversed`, per-level `level`, bijective base-26 alpha, subtractive roman.
- **Two paths share** `shapeLeaf(listMarkerFor(...))`: heavy reads the whole-chapter style map, light
  the lazy resolver, so both see the same `ul/ol` list-style. Marker drawn in both `BoxDrawableLayout`
  and `PartialDrawableLayout`.
- **`HtmlTreeConverter`** now keeps `ol` `start`/`reversed`/`type` (boolean `reversed` via `hasAttr`).
- **`uiSheetFromProfile`**: first-line indent restricted to `p`; `li` keeps 段间距 but no text-indent.
- `ComputedStyle`/`StyleComputer` parse `list-style-type`/`list-style-position`.

**Tests / build.** New pure-JVM `ListMarkersTest` (attribution, numbering, formats, defaults) green
(Robolectric still unavailable in this env); `:app:compileDebugKotlin` green; `installDebug` OK.

**Pending to verify on device.** Bullet/ordered rendering, `start`/`reversed`, `inside`/`outside`,
nested indentation and renumbering, `list-style-type:none` (no marker/indent), and that `li` is no
longer first-line-indented.

## 2026-09-12 — Incremental / temporary pagination: line-based fill + stable window

**Issue.** Both the disk-incremental and anchor temporary pagination exposed two defects: (1) the page-break points were unstable while flipping (the same page's content changed between flips); and (2) with "whole block never torn" packing, a code block that did not fit was moved whole to the next page, leaving a large blank at the bottom of the page / a clipped bottom half-line.

**Fixes (S5 temp-table invariant relaxed: no longer "whole blocks never torn").**
- **Disk-incremental self-consistency**: `incrementalLayoutForPage` now tiles pages by `contentH` from its own `localLines` geometry (pages are continuous, neither leaking nor overflowing), instead of hard-applying the disk table's char boundaries; `ensurePageRangeShaped` records the window so flipping inside the window does not re-layout (stable page-break points).
- **Temp-table line fill**: `shapeTempPageForward` / `shapeAnchorPageForward` changed from whole-block to accumulating real per-line heights up to `contentH`, and may break lines inside a code block; added `ForwardedPage` and the line-level continuation cursor `forwardFromLine`. Only replaceable blocks (img / table rows) are kept whole.
- Keeping no mid-chapter disk-table switch (only at the chapter head / on leave) is preserved.

**Tests / build.** `:app:testDebugUnitTest` + `:app:assembleDebug` green; installed to the tablet.

**Pending to verify.** Forward whole-block tearing is resolved; backward is still whole-block, to be completed after forward is confirmed.

## 2026-09-12 — CSS `display:none` support (engine-level hide)

**Issue.** The engine only handled `display:block`; `display:none` was ignored, so `display:none` elements (e.g. a book's `<span class="boring">` "hide the boring code" spans) were still laid out and rendered, producing spurious blank lines / tall pages.

**Fix (single-source cascade, both layout paths).** `ComputedStyle` gains `displayNone`; `StyleComputer` sets it from `display:none` and adds `resolveHidden` (light path, mirrors heavy's `styleMap.displayNone`). `NormalFlowLayout` skips hidden subtrees in box building / text absorption / inline aggregation / leaf enumeration; `ParagraphShapes.emitText` skips them when rendering; the light path derives char starts via the shared `visibleCharAdvance` so heavy and light exclude display:none identically.

**Tests / build.** `:app:testDebugUnitTest` + `:app:assembleDebug` green; installed to the tablet.

**Pending / to verify on device.** Book `.boring` spans hidden; pages fill normally; no pagination drift between heavy and light.

## 2026-09-11 — Anonymous text leaf style fix at the engine layer (root cause)

**Issue.** In a heading like `<h1><span class="sec-num">Chapter 9 </span>Error Handling</h1>` the anonymous `#text` title (`Error Handling`) lost its block's **color and bold** — rendered black / thin — while the `sec-num` element leaf rendered correctly. Root cause was engine-level: an anonymous `#text` leaf is synthesized and has **no entry** in the styles map, so `emitText` never applied color/bold spans to it (element leaves have real style entries). This is the same mechanism failure behind both the color and the weight symptoms; earlier base-style alignment (`blockStyleFor`) only matched base styles, not the rendered inline spans.

**Fix (engine layer, shared by heavy+light).** In `ParagraphShapes.emitText`, an anonymous `#text` leaf falls back to the block's `rootStyle` (`styles[el] ?: rootStyle`), so it receives the same color/bold/spacing spans as any text run — correct HTML/CSS produces correct rendering, no paint-layer workaround (base-color / fake-bold hacks were tried and removed).

**Tests / build.** `:app:testDebugUnitTest` green; `:app:assembleDebug` OK; installed to the tablet.

**Pending / to verify on device.** Chapter big title now blue + bold; `sec-num` unchanged; no regression across body/heading/link styling.

## 2026-09-11 — Chapter-head page-flip dual render unification (head-flip double-render fix)

**Issue.** At a chapter's first page (e.g. after a TOC jump to a heading's `<h2><span class="sec-num">…</span> …</h2>`), a left page-flip showed two defects: (1) the page redrew **in place** instead of flipping / cross-chapter, and (2) the heading's weight/color changed because the heavy (`canonical`) and light (`temp`) renderings disagreed on the anonymous text leaf's style.

**Fixes.**
- **Single-source anonymous-leaf style (defect B).** The light path now resolves an anonymous `#text` leaf through its container block (`blockStyleFor`) and returns an empty inline-style map — matching the heavy path, which reuses the container style and holds no `styleMap` entry for synthesized leaves. Added a per-leaf `ComputedStyle` (fontSize/bold/color/textAlign/fontFamily/fontWeight) dual-path equality assertion to `BoxPathConsistencyTest`.
- **Chapter-head page-flip routing (defect A, S5).** `tempNav` no longer force-calls `bindCanonicalAndPage` to redraw the head page. At the chapter head, a backward flip is a boundary → the temp table is abandoned and the **disk (canonical) pagination is enabled on leave** (`finalizeTempOnLeave`). The mid-session canonical switch (`onBackgroundCanonicalReady`) was removed, so the current page is never re-rendered in place / can't flash while temp is live. Cross-chapter from the head now works on the first flip.

**Tests / build.** `BoxPathConsistencyTest` extended; `.gradlew :app:testDebugUnitTest` green; `:app:assembleDebug` OK; installed to the tablet.

**Pending / to verify on device.** Chapter-head left-flip now cross-chapters without in-place redraw or style jump; re-open after disk hit still lands on the same character; no regression across parameter relayout / cross-chapter jumps.

## 2026-09-11 — Dual-path single-sourcing (typesetting correctness, rectification A/B + 1.2)

**Issue.** The engine kept two layout paths — heavy `NormalFlowLayout` (canonical, writes the disk pagination table) and light `BoxChapterLayouter.LightPrepare` (lazy cascade, reads the table + whole-block temp pages). The same geometry was implemented twice in separate sessions; equivalences were not enough, and a divergence surfaced as a page's last line overflowing the content height (clipped bottom half).

**Fixes (single-source, light now calls heavy's shared functions).**
- **A · line-break width**: extracted `NormalFlowLayout.innerBreakWidth(style, borderBoxW)` and `descendContentWidth(el, rootW, edgesOf)`. `buildBoxTree` and light shaping both route through them; `LightPrepare.borderBoxWidth` deleted.
- **B · container margin collapsing**: added `NormalFlowLayout.consecutiveLeafAdvance(prev, next, styleOf)` that reproduces `emit()`'s tree-aware collapse (container bottom margins + the container/leaf-abutment rule). `rebuildLocalLines` now uses it instead of adjacent-leaf pairwise.
- **1.2 single-sourcing**: last-line height via `lineHeightPx` (shared by both rebuild paths); `globalCharStarts` via `NormalFlowLayout.accumulateCharStarts`; first-line top-align via `DrawableBookLayout.alignPageTop`.

**Tests.** Added `BoxSharedGeometryTest` locking the shared width/advance functions byte-for-byte to canonical emit geometry (nested containers, container margins, leaf→container abutting). Full `:app:testDebugUnitTest` is green.

**Pending / to verify on device.** Device regression (page last line no longer clipped; spacing matches pre-change; large-font / multi-margin chapters). The temporary `INCR-OVERFLOW` diagnostic log was already absent from the tree (nothing left to remove).
## 2026-10-02 — 真机「代码块特殊字符乱码」定案（提交 1120a97）

**Issue.** 真机报代码块里 Unicode 特殊字符「要么不显示、要么显示乱码」，用户明确「之前用 skia 是好的」，
且第一轮修完（补保底面表）**仍乱码**。

**两个根因，都是「平台附赠能力在换实现时丢掉了」的变体。**

1. **取码本有两份实现，落墨那份是错的。** 量宽侧合并代理对，落墨侧 `drawGlyphPass` 用 `text[i].code`
   ⇒ emoji 被拆成两个孤立代理（没有任何字体有它们的字形）；量宽侧还把孤立低位代理当独立码本去查字体，
   查不到 ⇒ 记 notdef 宽（实测 fs=24 时 15.21px）⇒ **行凭空宽出一个字位，后续字位全体右移**。
   平板日志实证：`无字体的码本 U+D83D` / `U+DC4D` 是一**对代理的上下半**。
   修法 = **取码本收成单源** `codePointAt`（`CodePointAt.kt`），量与画都必须用它。

2. **枚举表按族名建，严格窄于系统回退链。** 保底表遍历 63 个族名，**里面没有任何 emoji 族**，
   而 `/system/fonts/NotoColorEmoji.ttf` **存在**且 U+1F44D 有字形 ⇒「设备没字体」的结论是假的。
   旧 Skia 路径靠 `FontCollection` 的系统回退链（`defaultFallback`），那条链直接问宿主、不靠族名。
   修法 = 在保底表之后补最后一道 `systemFallbackFace`（同一出口、同一缓存，量画同源）。

**性能坑（本轮实测）.** `FontCollection.setDefaultFontManager(systemFonts())` 不是廉价构造，
而 `systemFallbackFace` 是按码本调用的 ⇒ 第一版每次新建，整书重排探针（15 秒预算）全量跑时超时。
改为整个进程只建一次。

**Tests.** 新增 `SurrogatePairLayoutTest`（5 把，MUT-E/MUT-F 逐把变异验证）、
`GlyphFallbackFaceTest` 锁 7/8（MUT-H 验证）。锁变异表：

| 锁 | 变异 | 结果 |
| --- | --- | --- |
| 代理对低位代理槽位必须零宽 | MUT-E 撤掉零宽 | RED `expected:<0.0> but was:<15.2109375>` |
| 落墨必须整对取码本 | MUT-F 退回 `text[i].code` | RED `落墨取到了孤立代理 ⇒ 代理对被拆开画了` |
| 量画同源（遍历候选码本） | MUT-H 量宽侧摘掉保底通道 | RED `U+0E01 量画必须同源（量=14.4000 画=14.2852）` |
| 枚举表够不到必须由系统链接住 | — | macOS 上 SKIP（枚举表够得到 ⇒ 空断言），牙齿在真机 |

**踩掉的假绿锁.** 第一版像素锁判据「左右不对称 ⇒ 不是豆腐块」实测恒绿：skiko 把孤立代理编码成 `?` 画出来，
`?` 有墨且不对称 ⇒ 前提不成立。**已删除**（教训 ⑫）。锁 7/8 的系统链牙齿在 macOS 上不存在
（枚举表覆盖全部候选码本 ⇒ 该通道是死代码），已在锁注释里写明。

**Verification.** 全量 `./gradlew jvmTest test` = 1281 条 / 1 红（唯一红是已登记的 Q12
`CrossChapterPreflightProbeTest`，与本次无关）。装机后真机确认「已经显示出来了」；
日志 0 异常、无孤立代理残留、`无字体的码本 U+1F44D` 不再出现。诊断用的族名转储与「可疑取面」探针**跑完即删**。

**同轮遗留的第二个探针也在这之后删了（2026-10-02）。** `logUniversalTableOnce`
（`SkiaRunMeasurer` 内的保底表诊断）的取证目的已达成，随探针删除，
其结论与「豆腐块判据连试四遍都不可用」的全过程落在
`自建断行引擎-测试计划.md` §教训 ⑯（**否定结论**）。平板实测：
保底表 `installed=59 faces=39`，15 个诊断码本 13 个可解，解不出的是 `U+0E01`（泰文）与 `U+1F600`（emoji），
分档 真字形 9 / 简单几何 2 / 空白 1 / **豆腐块 1 = `└` 假阳性** ⇒ **未检出任何真豆腐块**。
删它的直接原因是一次运行 **39 秒打出 641 行**（门是每实例的 `HashSet`，见 §教训 ⑰；该次运行日志总行 932 行，探针占 69%），
而它写在排版热路径上。`logUnresolvableCp` **保留** —— 「设备真的没装这个字体」对用户可行动。

**装机复核**：重装后同一场景 `Orilumn.FACE` **0 行**、异常 0、分页正常（`SPIKE`/`DISK`/`TEMP`/`TAP` 照常）。

**同处还记下一笔未销的缺陷**：`universalCache` 与被删的 `universalLogged` 一样是 `SkiaRunMeasurer` 的**实例字段**，
而该类在生产路径上是四处**默认实参** ⇒ 每个新实例都重扫全部 59 个已装族。按那 641 行折算，
**39 秒内至少新建 214 个实例** = 至少 214 次结果完全相同的全量重扫。正确作用域是进程级
（与 `systemFallbackCollection` 同模式）。**本次不动它** —— 收益未量化，且要确认「已装字体集」在运行期是否真不变
（热插字体/换字体配置会让进程级缓存变陈旧，而每实例缓存天然规避了这一点）。已在 `universalTypefaces` 的 KDoc 上标注。

## 2026-10-02 — 表格侧度量交给自建断行器（TODO Q6 销案）+ Q5（kerning）销案

**Q6 是什么.** 表格 auto 分列的**列宽**原本被刻意隔离在 Skia 侧，靠两处钉扎维持：
轻路径 `BoxChapterLayouter.tableBreaker` 恒 Skia，重路径 `engine-skia` 的 `heavyPathBreaker`
（`autoColumnMeasurePinnedToSkia`）把 `preferredWidth`/`minContentWidth` 单独钉回 Skia。
理由是自建侧 `preferredWidth` 只是接口默认桩 `段长 x fontSizePx`（实测 Latin/URL 高估 2.0–2.6 倍，
而列宽经 `autoColumnLayout` 的 `avail>=totalMax` 段 `w[i]=pref[i]` **直通**，无处夹取）。

**解法：一个方法 + 一个实例.**

- `InhouseParagraphBreaker` 补 `preferredWidth` 覆写 → `SkiaRunMeasurer.naturalWidth`
  （与断行**同一个 `advances` 单源**，只差「不施加版心宽、不找断点」）。
- `minContentWidth` **一个字都没写**：接口默认实现本身就是「按 `minContentSegments` 切段后
  逐段调 `preferredWidth` 取最大」，随上一条自动变真。
- 两处钉扎**一并删除**（`BodyParagraphBreaker.kt` 135→56 行），`bodyParagraphBreaker` 成为唯一断行器口。
- 新增 `BoxChapterLayouter.breakerFor`：重路径 `BoxLayouter` 与轻路径 `LightPrepare`（表格测宽）
  **共用同一个实例**，缓存键 `(profile, 变体)`。净 **+212 / −311**。

**实测两侧差**（fs=16 与 44.4，`STSong,serif`，同宿主对照）:

| 文本类别 | 差 |
| --- | --- |
| CJK / URL / code / 混排 / 无 kern 对的 Latin | **0.0000%** |
| `Office of the Future`、`AVATAR Wayfinding WA` | **+1.104% / +0.838%**（恒为一个 kern 对的像素量：1.44px@16、3.996px@44.4，比值 2.775 = 44.4/16） |
| min-content（Latin 37 字） | **0.0000%**（181.6404 逐值相等） |

方向**永不反向**（整形缺口全为负，见 Q5），符合 `preferredWidth` 契约「永不窄于实需」。
原台账里的「桩高估 Latin **2.49x**」—— 那 2.49 倍**全是桩的错**，真测量只差 ~1%。

**拆钉扎时抓到的两个真 bug（都不是预演出来的）.**

1. **两处钉扎本身是缺陷的温床。** 表格测宽在轻路径 `LightPrepare` 里消费，重路径压根不经过它 ⇒
   「维持重轻一致」的两处钉扎里，**改轻路径那一处永远测不到**：变异 MUT-I（把生产侧断行器来源
   改回 `SkiaParagraphBreaker`）连跑**两轮 BUILD SUCCESSFUL**，是假绿锁形态⑤（锁压根没碰那条路径）。
   ⇒ 解法不是加更严的锁，是**结构上收成单源**。⚠️ 这份锁的 KDoc 里**已经写着**这条错误，
   照抄一遍又踩 —— **写在文档里的教训不会自动生效，只有当场变异验证会**（新教训 ⑮）。
2. **`breakerFor` 缓存键漏了变体。** 只按 `profile` 缓存，而实例的**类**由 `AbSwitch.inhouseBreak()`
   决定，且 `BoxChapterLayouter` 寿命是**整本书**、跨多次排版参数变更 ⇒ 拨开关后仍按 profile 命中缓存、
   继续发旧变体 ⇒ **回退阀在缓存命中时静默失效**（且 `paramHash` 变了会重排、重排却拿到同一套断行器）。
   这是教训 ⑩ 的第三种翻法：**键的分量不只看「昂贵那步的输入」，还要看「产出对象的身份由谁决定」。**
   由新加的**轻路径**锁当场抓出 —— 绕过去测的锁连这个 bug 都看不见（两侧同样绕过，一样相等）。

**Tests.** `TableColumnWidthRealMeasureTest`（**正向**，6 把，取代 `TableBreakerStaysSkiaTest` 那 2 把
守卫锁 —— 后者 KDoc 本就写明「一旦有人补上真测量，本锁故意失败，届时改写成两侧都跑的正向锁」）。
真测量 4 把（真测量 / min-content 连带变真 / 无 kern 对文本两侧逐值相等 / 含 kern 对 Latin 偏宽不超 3%）
+ 接线 2 把（重路径 / 轻路径，**缺一不可**）。变异表（实测）:

| 变异 | 结果 |
| --- | --- |
| MUT-I 改 `breakerFor` | 锁 7 红（锁 5 绿——它不走 `BoxChapterLayouter`） |
| MUT-J 删 `preferredWidth` 覆写 | 锁 1/2/3/4 红 |
| MUT-K `naturalWidth` ×1.05 | 锁 1/2/3/4 红 |
| MUT-L `naturalWidth` ×0.95 | 锁 2/3/4 红（**锁 1 不红**——它不测方向） |

锁 5/7 在 MUT-J/K/L 下仍绿是**正确分工**（测接线不管测量）；MUT-I 只红锁 7 说明两把真在看两条不同的路。

**顺带作废一条原则.** 「变体只该改变行，不该改变列」**作废** —— 它当初是用「把列宽钉回另一套实现」
维持的，而**那两处钉扎正是重轻分叉的真正来源**；现在一致性由「重轻共用同一个实例」保证。

**Q5（kerning / 连字）销案.** `KerningClusterTable` **本身就是解法**，不是「自建侧没接管的缺口」：
kerning/连字信息只存在于字体 GPOS/GSUB，取它必须有 shaper，而 `SkiaRunMeasurer` 刻意
「逐码本量宽、不整形」是 S2 冻结契约 ⇒ 有意分工「**量宽不整形 + 落墨现算簇位**」。
判据不是「自建要接管整形」，而是「**取簇位必须同源**」（`GraftKerningOntoTest` 钉住）。
成本：`needsClusters` 先判 `isLatinish`，纯 CJK 行**不建 Paragraph**（零行为变化）。

**Verification.** 全量 `./gradlew jvmTest test` = **1285 条 / 1 红 / 1 跳过**
（唯一红仍是已登记的 Q12 `CrossChapterPreflightProbeTest`；唯一跳过是
`GlyphFallbackFaceTest` 锁 7，macOS 上枚举表够得到 ⇒ 空断言，牙齿在真机）。
⚠️ **未做真机验证**：Latin 表格列宽会宽约 1%（CJK 逐值不变），值得装机过一眼表格。

---

## 2026-10-02 — 真机报缺陷的排查轮：5 条确诊，0 条销案（Q15–Q19 新登记）

用户一次报来 4 条 + GIMP 表格 1 条。**全部走「实测钉死，不推测」**，探针跑完即删（结论先落纸）。
**本轮没有改动任何生产代码** —— 5 条都还卡在「要先定规格」那一步。

| 编号 | 症状 | 根因（层） | 状态 |
| --- | --- | --- | --- |
| Q15 | GIMP 手册表格「显示不出来」 | `StyledText.kt:107` 对块级子节点 `isBlock(c) -> Unit` ⇒ **`<td><p>…</p></td>` 整格文本为空**（排版层·上） | 确诊，**已修**（见「第二批」） |
| Q16 | `pre code` 空行全没了 | 断行器**两侧都丢**空行（自建 `greedy` 行首 `'\n'` 跳过 + Skia 侧 `if (e > s)`）⇒ **回退阀也修不好**（排版层·上） | 确诊，未修 |
| Q17 | 中英之间空白怎么处理的 | **什么都没做**；`CjkLatinSpacing` 是死代码；「盒边界」在这条链路上不存在（`<code>` 不是盒，只是 `FontRun` 区间） | 已回答 |
| Q18 | `wrap(断行)ping_add` 断行优先级 | **先压缩空白再断行**；断在字母中间是**英语音节断词**把 `wrapping` 断成 `wrap-`+`ping`，而 `_` 断点因「判据看叶块 tag」没注入（分行控制） | 确诊，未修 |
| Q19 | 标题看不出粗 + 选粗细对标题无效 | `anchoredWeight` 第一行 `if (italic \|\| weight != 400) return` ⇒ 对 h1–h6 的 700 **恒不触发**；且**全仓无合成粗体**（渲染层） | 确诊，未修 |
| **Q20** | **断词行尾没有连字符**（用户指「上次发现过、没彻底解决」） | `DrawLine` 有**三个**构造点，上次只修了 canonical 那一个：**增量路径 `BoxChapterLayouter:797` 与表格格 `TableCellLines:172` 都没传 `hyphenAtEnd`** ⇒ 取默认 `false` ⇒ `placement.hyphenWidth==0` ⇒ 落墨整块跳过 | 确诊，**已修**（见「第二批」） |

### Q15 的影响面是本轮最重的一条

GIMP 只是用户点名的书。扫平板书库 7 本有表格的书，`<td>` 内以块级标签开头的占比：

| 书 | 块级包裹 `td` / 总 `td` |
| --- | --- |
| `book_1790742729435`（Rust 程序设计 第2版） | 908 / 987 = **92%** |
| `book_1790742665434` | 1058 / 1170 = **90%** |
| `book_1790742662856`（GIMP 3.0 用户手册） | 591 / 1249 = 47% |
| `book_1790742718646` | 1 / 10 |
| 其余 3 本 | 0（安全） |

⇒ 设备书库合计 **2558 / 4309（59.4%）个单元格不可见**（GIMP 539 张表每张的正文格都丢）。
且这**与 Q6 无关**：探针用旧桩与新真测量各跑一遍
`tableCellPref` + `autoColumnLayout`，列宽 `[25, 32]` **逐值相同**（两条理由见 TODO Q15）。
**为什么既有测试没抓到**：`TableFamilyTest` 与本轮新加的 `TableColumnWidthRealMeasureTest` 用的
全是**裸文本单元格**，没有一个带 `<p>` ⇒ 合成形状把最常见的形态整个漏掉。

### 两处「探针自己写错判据」的假红（新教训 ⑱）

查 Q18 时同一段代码连栽两次，**两次都是探针的影子判据错、不是生产错**，而且两次方向相反：
① 用 `isBreakOpportunity`（**只有 ZH_EN**）判「非法断点」，而断行器实际喂 `sourcesFor(tag)`
（含**英语断词**）⇒ 误报 `非法断点=1`，差一步就把一条 KDoc 已写明规则的**正确行为**
记成「引擎非法硬切」并照着改生产代码；
② `(marked.filter { code(it) } - marked.toSet())` 值域恒为 ∅（集合运算写错）⇒ 漏看「`_` 断点
本该注入却没注入」这个**真正的缺陷**。
⇒ 教训：判据不是「写一条看起来相关的检查」，而是**复用生产那个函数**；
自己另写一份等价判定 = 制造一个可与生产分叉的影子规格。

### Verification

本轮零生产代码改动。删探针后全量 `./gradlew jvmTest test --offline --rerun-tasks --continue`
= **1285 条 / 1 红 / 1 跳过**，与本轮开始前逐项一致
（唯一红 = 已登记 Q12 `CrossChapterPreflightProbeTest`；唯一跳过 = `GlyphFallbackFaceTest` 锁 7）。

---

## 2026-10-02 — 真机报缺陷的排查轮·第二批：Q15 / Q20 修完

Q15（表格格内块级内容消失）与 Q20（断词行尾连字符）**同批落地**，因为它们共用
`TableCellLayout` 这一个数据结构；Q20③ 本来就与 Q15 同一个改动面。Q16/Q17/Q18/Q19 仍在 TODO 里。

### Q15：格内容按 CSS 2.1 §16.3 拆成块序列

规格：格内**块级**子节点各自成块、格的**直接内联内容**合成一个匿名块、逐块断行后纵向堆叠
（兄弟外边距折叠）。`TableCellLayout` 的单 `shape` ⇒ `blocks: List<TableCellBlock>`；
排版（`buildTableRows`）与塑形（`fillTableRowCells`）共调 `NormalFlowLayout.cellBlocks`，
两侧共用 `stackTableCellBlocks` 这**一处**定位式；`TableCellLines.emitCell/emitCellImages` 逐块发射。

**这条重构是被一条既有红锁逼出来的，不是自己发明的需求**：
`SkiaDrawLineWindowCoherenceTest.rowspanTableSharesSpanningCellHeightAcrossRows` 变红
（`5 yTop(rel) expected:<98> but was:<77>`），根因是轻路径把匿名 run 的 `<br>` 硬换行折成空格
（Kindle 跨行格 5 行掉成 3 行、格高 161→105、跨行分摊量消失）。

**三个坑（都先做错、被探针/红锁打回）**：

| # | 做错的版本 | 现象 | 定案 |
| --- | --- | --- | --- |
| ① | 匿名块造一个合成 `#text` 当 `el` | 塑形侧从**真实子树**重抽文本拿不到东西，`<br>` 被 `white-space:normal` 折成空格 ⇒ 5 行→3 行 | 匿名块的 `el` **就是所在容器**；区间靠 `TableCellBlock.outsideSiblings`（**原始**兄弟，按 `container.children` 身份）复原 |
| ② | 行内 `<img>` 独立成替换块 | 塑形侧对 `img` 根早退、塑出零行 | 行内 `<img>` **不拆块**（与 `flowChildren` 同式），`<td><img></td>` 与修复前逐字节同式 |
| ③ | 沿用旧 `appendInlineText` | GIMP 图标格 `<td rowspan="2"><img/></td>` **整格零块** | `appendInlineText` 必须与 `styledSegments` 的 `walk` **逐条同形**（`br`/`img` 都产字符）—— 它只是「这段 run 有没有内容」的判据 |

连带修掉：`isBlock` 分支在 5 处（`StyledText`/`ColorRuns`×2/`BaselineShifts`/`UnderlineRuns`）
**统一前移到 `c.isText` 之前**（匿名块要排掉 run 外的**文本**兄弟）；重路径 `rowChars` 对
`<td><p>…</p></td>` 得 0 的 char 基址漂移；嵌套图（`<p>字<span><img/></span>字</p>`）此前被静默丢字符。

### Q20：三个 `DrawLine` 构造点一次补齐（载体从 `LayoutBox` 改挂 `shape`）

②`buildPartialSkiaWindow` 原计划读 `leaf.hyphenAtEnd`，实测**读不到**：轻路径的叶是按
`ranges = emptyList()` 建的，断行只发生在塑形那一步 ⇒ 改挂 `ParagraphShapeRef.shapeLineHyphenAtEnd(k)`
（＋ `ShapedGeometry.lineHyphenAtEnd`，由 `shapeGeometry` 从 `broken.map { it.hyphenAtEnd }` 搬）。
③格内块两路都由 `fillTableRowCells` 塑形、形状即单源 ⇒ 也读 shape。① canonical 的 `DrawLineBuilder`
**不塑形**，仍读 `leaf.hyphenAtEnd` —— 与 `ranges` 的既有口径完全一样（canonical 读盒流、轻路径读 shape，
同断行器同宽、逐项一致）。

### Verification

- 锁：`common:TableCellBlocksTest` 16 把 ＋ 新增 `app:TableCellBlocksEndToEndTest` 5 把
  （走 `prepareLight`→`incrementalLayoutForPage`/`shapeTempPageForward`→`tableCellLines` 整条链路，
  语料含 GIMP 2×2 rowspan 真实表形）＋ 新增 `app:SkiaDrawLineWindowCoherenceTest.hyphenAtEndFlagIsCarriedByIncrementalAndTempWindows`。
- **逐把变异验证**（6 个变异全部应验）：M1 `shapeLineHyphenAtEnd→false`、M2 删 `appendInlineText` 的 img 分支、
  M3 `cellFlowItems` 不拆块、M4 匿名块 `el` 换回合成 `#text`（**复现出原始症状** `5 yTop(rel) 98→77`）、
  M5 `charAcc += 0`、M6 `stackTableCellBlocks` 不累计前块高度。
- 全量 `./gradlew :common:jvmTest :engine-skia:jvmTest :app:testDebugUnitTest --offline --continue`
  = **1208 条 / 1 红 / 1 跳过**，与本轮开始前逐项一致
  （唯一红 = Q12 `CrossChapterPreflightProbeTest`；唯一跳过 = `GlyphFallbackFaceTest` 锁 7）。
