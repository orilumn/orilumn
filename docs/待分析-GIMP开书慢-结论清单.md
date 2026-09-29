# 待分析清单：开书 16–22s 的实证结论

> 采集时间：2026-09-29。数据来源：平板落盘日志 `日志_20260929.txt` / `日志_20260929.1.txt`
>（`run-as orilumn.reader cat files/logs/…`，设备 vivo PA2353）。
> 本文件只**记录已查实的结论与未决项**，不做方案；方案另起。
>
> **本文件被订正过两次**（§2.1）。所有订正都用 ~~删除线~~ 保留原判，不静默改写——
> 错判本身是有用的信息：两次都栽在"给热路径加探针"上。

## 0. 样本

| 项 | GIMP（主样本） | Rust（健康对照，22:11 起换用） |
| --- | --- | --- |
| 文件 | `book_1790674291641.epub`（51.8MB，29 章） | `Rust 程序设计语言 - Steve Klabnik Carol Nichols.epub`（1.9MB，25 章） |
| 落位章 | ch18「第14章 工具」2579 块 / 66624 字 / 151 页 | ch3–ch4，10–13 块 / 48–165 元素 |
| 结构 | `OEBPS/styles/style.css` 21768 B / 211 规则 | `OEBPS/Styles/stylesheet.css` 3896 B / 72 规则（13 后代） |
| **最大嵌套深度** | **624** | **33**（实测被塑块深度 2–3） |
| 设备 | vivo PA2353，8 核 / 7.7GB，`heapgrowthlimit=256m` | 同 |

换书理由：GIMP ch18 是**病态样本**（624 层嵌套），拿它当基准会把"书本身病态"和"引擎慢"搅在一起。
Rust 用来分辨这两者——它在同一套代码上快 4 倍，说明引擎不是均匀地慢，而是被某类输入拖垮。

## 1. 已定论

### 1.1 磁盘表命中路径曾是负优化

`path=WIN` 比 `path=TEMP` 慢一个数量级（18.7–22.7s vs 3.8–11.0s）。表已在磁盘上，命中却比重排还慢，
说明问题不在"要不要排"，而在"命中路径排了什么"：整章全量 2579 块 = 11.93s，而只塑形 21 块的
下一开书 = 17.50s。排得少的反而更慢 → 命中路径里有与块数无关的开销（见 §2.1）。

### 1.2 根因一：开书时 B2/B1 抢 `layoutMutex`

`TabletReaderHost.open()` 里 `requestWholeBookRelayout()` 排在 `locateStart()` **之前**，29 章 B2
全量活塞进池；`startAnchorStream` 尾部又 `dispatchB1` + `requestWholeBookRelayout(force = true)`
一次。锚页排版期间两个槽都被整章活占住，而 `ensureMarkup`（解析）与 `ensureChapterLayout`（排版）
共用同一个全局 `layoutMutex`。日志佐证：`b2:16` 堵 `layoutMutex` **15.9s**。

违反总则第 1 条（目标页第一优先）。**已修**（开书闸门，`openAnchorGate` / `releaseOpenGate`）。

### 1.3 根因二：轻路径逐块懒解析，`styleCache` 每次 `prepareLight` 重置

- `styleCache` / `inlineMaps` 是 `LightPrepare` 私有（`BoxChapterLayouter.kt` 内），
  `ensurePageRangeShaped` 每次冷翻页重建 `prepare` → 缓存全丢；只有 `unitShapeCache` 跨页保留。
- 后台预排的形状命中率为 **0**：`l2hits=0/17`——预排塑出来的形状一个都没被复用。

~~轻路径 278ms/块 vs 重路径 5.3–5.6ms/块（差 ~50×）~~ —— **这条已订正**：278ms/77ms/255ms 是
**最坏页**的数，不是典型值。实测每块塑形成本分布（同书同章）：ch68 5ms/块、ch0 2ms/块、
ch1 117ms/块、ch18 page7 77ms/块、ch18 page8 **255ms/块**，全样本中位 105ms/块。
**同一本书同一章 page7 vs page8 差 3.3 倍**——所以不存在一个"轻路径每块成本"可优化。

### 1.4 CSS 级联深度假设：已否证

曾怀疑 `StyleComputer.resolve`（`css/StyleComputer.kt`）缓存命中也走 O(depth)、
`Cascade.winningDeclarations` 遍历全部选择器、`Selector.matchesChain` 的 `DESCENDANT` 分支扫全量祖先，
是 624 层嵌套的代价。**已否证**：R20 埋点把 `shape` 段拆成 CSS 级联与 Skia 断行后，Rust 锚页
`sStyles=1–4ms` / `sSkia=24–28ms`（10 块 / 48 元素 / 深度 3），GIMP 锚页 `sStyles=32–43ms` /
`sSkia=48–53ms`（13 块 / 165 元素）。CSS 级联在任何一本上都不是瓶颈，这条线索可以放下了。

## 2. §2.1 原结论，两次订正

### 2.1 `pgBackfill` 3.5–11.6s —— ~~**已定性：GC 停顿，不是这段代码慢**~~ **最终结论：是实参求值，真活**

按代码看，`pgBackfill` 对应的 `backfillBlockRanges` 只是 1 次 `PageSlice` 拷贝 + 每页 2 次二分，
亚毫秒级，但真机稳定测到 8.5–11.8s。**两次错判都栽在探针上**：

**错判 ①（"GC 停顿"）**：在 `pgBackfill` 段前后各采一次 `Runtime` 堆，得
`pgHeap=63->52MB free=1->20MB`，据此判"free 只在 GC 里被补满，所以 9s 是 ART 在回收"。
**探针本身就是那次 GC**：ART 的 `Runtime_nativeFree` 实现是
`CollectGarbage(clear_soft_references = true)` **之后**才返回可用字节数。单次 ≈660ms，
两次 ≈1.3s——恰好等于它所在那一段的墙钟。堆信息真实，但"墙钟里有 GC"是探针自己加的。
（`java.lang.management` 在 Android app classloader 里也不存在，实机 `NoClassDefFoundError`。）

**错判 ②（"线程在算"）**：改用 `Debug.threadCpuTimeNanos()` 取线程 CPU 时间，得
`pgCpu=1226ms` vs 墙钟 `pg=1232ms`，看着像真在算。**该调用同样比被测对象贵**：走 VMDebug 落
`/proc/self/task/<tid>/stat`，vivo PA2353 实测**进程内首次 ≈1.2s、之后每次 ≈27ms**。

**最终定位**：把边界再拆一层即见分晓。

```
pg-edge args=1251ms call1=0ms call2=0ms starts=172 blocks=172 chars=20016
pg-edge args=17ms   call1=0ms call2=0ms starts=8   blocks=8   chars=587
```

函数本体 0ms（且是进程内首次调用，类加载/JIT 也在窗口内），**1251ms 全在"求实参"**，
且随章规模线性（20016 字 → 1251ms；587 字 → 17ms，约 7.3ms/块）。

**真凶**：`BoxChapterLayouter.kt` 的 `LightPrepare.totalChars` 写成了计算属性——

```kotlin
val totalChars: Int get() = markupLeaves.sumOf { NormalFlowLayout.styledCharAdvance(
    it, { e -> styleComputer().resolve(e, styleCache) }, lightClassify(), hidden, genOf).toInt() }
```

即"对全章每个块重跑一遍 CSS 级联 + 文本前进宽度测量"。而它恰好是
`incrementalLayoutForPage` 里 `backfillBlockRanges` 的**实参**，于是**每次开书重算一遍全章**。
GIMP 的 `pgBackfill=9705ms` 就是这个（2579 块 × 3.8ms/块 ≈ 9.8s）。

**已修**：`globalCharStarts` 本就是同一批 `styledCharAdvance` 的前缀和（`computeStructure` 与重路径
`ChapterPrepareResult` 同一式），故 `sum == starts.last() + 末叶 advance`——172 次求和变 1 次，约 7ms。
`by lazy` 顺带让重复访问免费；`starts.size != n` 时退回原口径兜底。

> 方法论教训（已写进 `backfillBlockRanges` 注释）：**给热路径加探针前，先量探针自身**。
> 本次两个探针的自身成本都高于被测对象一两个数量级，且都表现为"被测代码在变慢"。

### 2.2 断点续（checkpoint resume）

`TaskScheduler` 只有 `cancel()`，**没有 suspend/resume**。

- `b2ChapterTask` 在**块间**有 checkpoint，放弃延迟 ≈5ms——够及时，但整趟 16–28s 进度全丢，
  抢占后必须从块 0 重来。
- `ensureMarkup` 在 `layoutMutex` 内**不可中断**（锁等待可取消，进了临界区就不能），
  这是 §1.2 里 15.9s 堵锁的直接原因之一。

待设计：`ChapterUnit` 增加 canonical 进度字段，`fullLayout` 现有的块间 checkpoint 从"仅 abandon"
升级为"存进度 + 可续"。

### 2.3 B2 同样是"一次性全部 submit"

本次只把**邻页全章预排**（`scheduleWindowPrefill` 的 `pg:<章>:<页>` 序列）改成了滚动派发
（同时最多 2 个，跑完一个补一个）。`requestWholeBookRelayout` 仍一次性把 29 章 B2 全部 submit
（队列深度 29）。是否也改滚动，待邻页那版上机测过再定——两条队列优先级不同
（邻页第 2/3/4 档 vs B2 第 7 档），共用滚动窗口可能把 B2 饿死。

## 3. 修 `totalChars` 后的实测（2026-09-29 22:39，Rust 书，磁盘表命中）

| 项 | 修 `totalChars` 前 | 修后 |
| --- | --- | --- |
| 开书总时长 | 2.70s | **1.07s**（1.06 / 1.07 / 1.08） |
| 锚页 `pg` | 1252ms | **4–5ms** |
| `DISK-HIT` 排版段 | 2161ms | **505–550ms** |
| 锚页真实排版（`sStyles`+`sSkia`） | 88ms | **31ms** |
| 锚页塑形段 | 304ms | **109–128ms** |
| 暖机段 `warm` | 350ms | **已关**（A/B 证明净亏，见 §4.1） |

`:engine-skia:jvmTest` 全绿。

## 3b. 拆开 `shape` 段：又是两条全章扫描（2026-09-29 23:2x，Rust 书，锚页 ch7 page15 / blocks[125,130) / 5 块）

关掉暖机后 `openT=1297ms` 里锚页 `shape=584ms`，而真排版（`sStyles+sSkia`）只有 85ms，
**约 500ms 未归因**。把探针伸进 `LightPrepare.block(i)` 之后全部归因完毕——
`sBlk + sSkia + sStyles = shape`，一分不差。三轮（R28 定位 / R29 修 / R30 定位 / R31 修）：

| | R26 基线 | R29 textLength 差分 | R31 +float 标志 |
| --- | --- | --- | --- |
| `openT` | 1297ms | 1178ms | **964ms** |
| `DISK-HIT shape` | 775ms | 656ms | **431ms** |
| 锚页 `shape` | 584ms | 529ms | **260ms** |
| `sBlk`（`block(i)` 物化） | 522ms | 302ms | **22ms** |
| `sAdv`（`textLength` 的 advance） | 227ms | 0ms | 0ms |
| `sStyles` / `sSkia` | 3ms / 82ms | 140ms / 82ms | 155ms / 84ms |

### 3b.1 第一条：`block(i)` 的 `textLength` 在白算一次 advance

每物化一块就跑一次 `styledCharAdvance`（按块文本长度线性、每元素回调 `resolveStyle`），
锚页 5 块 227ms——**与 `totalChars` 把整章重算 9.7s 的是同一个函数**。

不能改成置 0：`rebuildLocalLines` 三处（`charEnd` / `runningChar` 累加 ×2）都读逐块 `textLength`，
那是轻路径行流的字符记账，置 0 会让 `charStart`/`charEnd` 全错（且是静默的，只表现为目录跳错位）。
改走 `globalCharStarts` 差分：`computeStructure` 用同一表达式逐叶求 advance 后交给
`accumulateCharStarts`，后者是**精确整数前缀和**（`starts[i]=running; running+=lengths[i]`，无舍入），
`styledCharAdvance` 返回 `Long`，故 `starts[i+1]-starts[i]` 与原 `.toInt()` **逐值精确相同**。
末块无后继，用已按同式证明过的 `totalChars - starts[n-1]`。

净省只有 78ms 而不是 227ms：原先 advance 里已顺手把级联缓存热了（`sStyles` 3→140ms 接住），
真正省下的是非级联那部分文本测量。`BlockTextLengthIdentityTest` 6 例（纯段落 / img+br /
表格 / 隐藏+三层嵌套 / 生成内容 / 单块章）逐块对照旧口径，全绿。

### 3b.2 第二条：`computeFloatLeads` 开头那次全章扫描（275ms，大头）

`block(i)` 里 `floatLeads[i]` 触发 `floatData` 的 `by lazy`，其第一件事是
**遍历全章每个叶子的 `blockStyleFor`**，只为回答"这章有没有 float"（无则直接返回全 null 表）。
Rust ch7 = 169 个叶子的级联，实测 **275ms**，`sBlkF≈sBlk` 证明只第一次付。
与 `totalChars` 同一个病：全章扫描被懒初始化推进了开书关键路径。

修法同 `totalChars`：`computeStructure` 那一趟**已经把每个叶子的样式都解析过了**，
标志位几乎白送。口径必须与 `blockStyleFor` 一致（`#text` 叶取父级样式），
否则"文本叶在 float 容器内"的章会被误判成无 float。`float` 与字号/行高无关，
且结构持久化读写两侧都被 `!hasMediaRules` 门住（`@media` 翻不了它），
故随 `leaves`/`globalCharStarts` 一起持久化（codec `VERSION` 2→3、`STRUCTURE_VERSION` 1→2，
旧 bin 解码失败自动重算，日志里 17 条 `decode null (version)` 即一次性自愈）。

### 3b.3 `sStyles` 那 1.1ms/元素：不是选择器慢，是"建对象"慢

把探针伸进渲染层（`common` 的 `CascadeProbe`，四笔：选择器匹配 / 内联属性解析 /
建 `ComputedStyle` / 通用字体兜底的二次级联）。锚页 ch7 page14 / 10 块 / 108 元素，3 次：

| | 值 | 占比 |
| --- | --- | --- |
| `sStyles`（级联总账） | 109 / 111 / 115ms | |
| ├ `cBuild` 建 `ComputedStyle` | 78 / 93 / 89ms | **~75%** |
| ├ `cMatch` 选择器匹配 | 44 / 39 / 41ms | ~35% |
| ├ `cParse` 内联 `style=""` 解析 | 1 / 0 / 0ms | ~0 |
| └ `c2nd` 字体兜底二次级联 | **0ms** | 0 |

（`cMatch+cBuild` 略大于 `sStyles`：它覆盖整个 shape 窗口内**所有** `resolve`，
含 `block(i)` 的 `blockStyleFor`（`sBsty=22ms`），不只 `inlineStyles` 一家。）

**"1.1ms 花在选择器匹配"这个假设不成立。** 大头是 `computeStyle`：153 行代码，
`ComputedStyle` 有 **72 个字段**，逐字段各自解析长度/颜色/字体栈/间距——
87ms ÷ 108 元素 ≈ **0.8ms/元素**，就是构造一个 72 字段的值对象。
书里只有 1 个样式表、几十条选择器，匹配本身只占约 1/3。

顺带两个 **0 值**：`c2nd=0`（"通用字体名兜底把级联重跑一遍"那条路径未触发，
Rust 书 CSS 仅 1 个表、3 条 `font-family`，全是带名字的栈）、`cParse≈0`（该书无内联 `style`）。

> ⚠️ **这两个 0 是本书属性，不是通用结论，切勿据此认为该路径不需要。**
> `c2nd ≠ 0` 意味着该章命中了级联翻倍的字体兜底路径；`cParse ≠ 0` 意味着逐元素解析内联
> `style=""`。换一本书（尤其 CSS 里直接写 `font-family: serif` 的手工 EPUB）时，
> 这两个字段必须重新看。代码路径一直在，探针也一直在，只是本书没踩到。

### 3b.4 错判订正（三次）

**① `openT` 964ms → 715ms 不是收益。** R32 只加探针、没改任何行为，理论上只会变慢；
且那三次开书的续读位置自己漂了一页（page15 / 5 块 → page14 / 10 块，**工作量更大却更快**）。
这 250ms 归因不了，大概率是设备状态与连续冷开次数的方差。**不得计入任何收益账。**
可比的锚点数据只有 §3b 表格里的三项（`DISK-HIT shape` / 锚页 `shape` / `sBlk`）。

**② 探针的保真度瑕疵（已知，未修）。** `CascadeProbe.sink` 是全局可变量，挂在 shape 窗口上。
塑形全程在 `layoutMutex` 内单线程串行（既有代码的断言），但若真有第二个 layouter 实例
同时 `resolve`，它的时间会**混进本笔账**——不崩，只是数不准。要长期使用需按线程隔离。

**③ 拿"本书没走到"当"该路径不需要"。** R26 改 `anyFloat` 时只在 **无 float 的** Rust ch7
上验证过，却动了 **有 float 的** 那条代码路径。查证：仓库 5 个 float 测试**全部走重路径**，
轻路径的 `computeFloatLeads` / `floatLeads` / `anyFloat` **零测试引用**。
已补 `LightFloatLeadTest`（6 例，覆盖叶 float / 章末 float / 无 float / 容器 float），
并做了**改动前后对照**：把 R26 的 `anyFloat` 改动（07f6666 之前）取回来重跑，
6 例中同样 5 例过、同样 1 例挂——**证实我的改动对 float 书行为中性**，
同时顺带暴露一个**既有缺口**（见 §3b.6）。

### 3b.5 `computeStyle` 内部：三层拆分，三条假设否证，一条命中（R29）

锚页 `shape` 的 `cBuild` 已拆到叶子并修掉一处。拆分过程本身比结论更值得记——
**前两层的三条假设全被实测否证**，第三层才命中。

```
cBuild 50ms
├─ cEdge  28ms   ← 命中在这里面
│   ├─ eSty 10ms  ┐
│   ├─ eCol  7ms  ┘ 61%：唯二无条件 listOf(4) + 4 次字符串拼接查表
│   ├─ eWid  4ms
│   ├─ eBox  3ms  （padding；margin 计入 cEdge 未单列）
│   └─ eRad  1ms
├─ cFont  1ms    ← 不值钱，不碰
└─ 其余   21ms
```

**否证一：重复查表。** `w["..."]` 重复出现的 key 只有 14 处，
按每元素算远不到 87ms。改完省不下钱。（顺带：12 处同一 `key` 的重复查表
已经合并过，`font-family` 三连现在共用一次 `parseFontFamilyList`。）

**否证二：`Pattern.compile` 现场编译。** 13 处 `split(Regex("\\s+"))` 每次
现场 `new Regex` 走 `Pattern.compile`，同一模式被编译上千次——**代码长相上
明摆着的浪费**。做 `AbSwitch.regexHoist` 臂直接量（预编译常量 vs 原样）：

```
              原样      预编译     差
   cEdge      34ms      34ms      0        ← 零差别
   cBuild     50ms      54ms      +4       ← 方向还偏反（噪声内）
```

**否证三：切分开销本身。** 既然预编译没收益，那就把 `Pattern.split` 整个
换成手工字符扫描（按 CSS 空白集 `[ \t\n\r\f]`）。同装机交叉 8+8、锚点一致：

```
   cEdge    35.5ms（手工扫描） vs 28ms（Pattern.split）    ← 慢 8ms，8/8 全分离
```

手工扫描**更慢**，已删。所以 `cEdge` 那 34ms 根本不在切分上，在
**边家族的实际解析工作**里——这才有第三层。

**命中：`eCol + eSty`。** 这两个函数是边家族里唯二**无条件
`listOf("top","right","bottom","left")` 新建列表、且 4 次
`"border-$side-color"` 拼**新字符串**再查表**的（哈希现算、8 槽共 16 次），
而绝大多数元素只声明了一两个边（实测 `eBrd=59/52` 元素有 border，
但不是四边齐）。改显式四槽 + 4 侧 × 3 类 key 提为 companion 预建常量。

**R39 实测**（单装机运行期交叉 8+8、锚点一致 12@21972、两臂
`eBrd=59`/`sEls=52` 逐值相同）：

| | 原样 | 改后 | 差 |
|---|---|---|---|
| `eCol` | 5.0ms | 1.0ms | −4.0ms |
| `eSty` | 8.0ms | 5.0ms | −3.0ms |
| **`cEdge`** | **37.5ms** | **28.0ms** | **−9.5ms（−25%）** |
| `cBuild` | 57.5ms | 51.0ms | −6.5ms |
| `openT` | 731ms | 708ms | −22ms（−3.1%） |

固化后 4 跑复核 `cEdge` 24~28ms，落在改后区间。

`regexHoist` 开关**保留**（默认关 = 原样行为）。它与上面结论不冲突：
否证的是"预编译能省时间"，保留它是为了换书/换锚点时能再量一次
（本书 CSS 简写少，不代表别的书少）。

**教训**：`cEdge` 里 72 字段的"逐字段解析"这个描述**不足以指导改动**。
必须拆到家族级才知道该动哪两个函数。而拆的代价是三次否证——
**读代码看出的"明显浪费"在这台设备上已错三次**（级联深度 / 通用字体兜底 /
重复查表），本轮又错两次（预编译 / 手工扫描）。

### 3b.5.1 剩下的

`DISK-HIT shape` 里约 170ms 在 `asm shape` 窗口之外
（prepareLight / 结构缓存 / 表载入），未拆。
`cBuild` 的"其余 21ms"未拆。

### 3b.6 顺带查出的既有缺口：容器 float 在轻路径不生效（非本轮引入）

查 `anyFloat` 时用 `LightFloatLeadTest` 撞出来的，**与 R26 无关**（已用 07f6666 回退对照证明）。

**现象**：`<div style="float:left"><p>文字</p></div>` 在轻路径（临时表/增量）上环绕不生效，
要到磁盘表就绪才正确。

**根因**：`enumerateBlockLeaves` 只把**叶**放进 `leaves`，`<div>` 自己不是叶（它有块级子节点），
叶表里根本没有这个 float。`computeFloatLeads` 的前向透传只对叶注册 float，
祖先链只 `preClear(clearSide)`、**不注册祖先自身的 float**。重路径的递归能处理
（`P6aFloatTest` 走的就是重路径），所以两路行为不一致。

**与 `anyFloat` 标志的关系**：无。标志的检测口径与原 `blockStyleFor` 扫描在非 `#text` 叶上
是同一个表达式、在 `#text` 叶上都取父级，两者等价——所以标志既没制造也没掩盖这个缺口。

**处理**：`LightFloatLeadTest` 里以 `KNOWN GAP` 命名**锁定现状**（断言全 null），
一旦有人补上容器 float，这条会失败并提醒更新。真正的修复（让前向透传注册祖先 float）
是行为变更，**不在本轮性能工作范围内**，留作独立条目。

## 4. 未决项

### 4.1 暖机段：定论已下，实测净亏，已关

**先订正一条错判。** 曾断言暖机那 350ms 是 class-load / JIT / 字体段落初始化这类**一次性固定成本**，
依据是"ch4 锚页 8 块 1106ms vs 暖后 ch0 同 8 块 81ms"。这条**不成立**，两条反证：

1. **同一进程内暖机耗时差 11 倍。** 一次开书里先落 ch4 再落 ch0，暖机代码路径、块数（都是 3 块）、
   进程冷热都相同，耗时却是 ch4 `warm=346~419ms` 对 ch0 `warm=30~37ms`（R22–R25 逐次复现）。
   固定成本不会随章节内容变 11 倍。
2. **固定成本模型解出负单价。** 同一章同一调用内 `warm(3 块)=350ms`、`anchor(13 块)=304ms`。
   设 `F+3p=350`、`F+13p=304`，解得 `p=-4.6ms/块`。模型与数据矛盾。

**再做 A/B 定论（R26 vs R27，同一锚页 ch7 page15 / blocks[125,130) / 5 块，各 3 次冷开书）：**

| | warmB=0 | warmB=3 | 差 |
| --- | --- | --- | --- |
| 开书 `openT` | 1311 / 1278 / 1302（均 **1297**） | 1359 / 1326 / 1357（均 **1347**） | **+50ms 变慢** |
| 锚页 `DISK-HIT shape` | 767 / 764 / 794（均 775） | 820 / 816 / 837（均 824） | +49ms |
| 锚页 `asm shape` | 579 / 575 / 597（均 **584**） | 259 / 254 / 272（均 **262**） | −322ms |
| 预热 `warm` | 0 | 366 / 366 / 369（均 367） | +367ms |
| 锚页真排版 `sStyles+sSkia` | 85ms | **45ms** | −40ms |

**预热确实暖到了东西**——锚页 Skia 断行 85ms → 45ms。**但代价远超收益**：3 块 367ms，
而锚页 5 块才 262ms。净 +45ms，端到端 +50ms。`OPEN_WARMUP_BLOCKS` 已置 **0**。

那条 350ms 是 ch4/ch7 前 3 块（章标题 h1 + 首段）自身的真实排版开销，不是"暖机"。
若日后要试更便宜的暖机（只暖 1 块、或换更便宜的块），从这里下手，**别直接开 3**。

### 4.2 GIMP 未复测

按 7.3ms/块 推，2579 块光 `totalChars` 一项就是 ~9.7s，GIMP 开书应能从 15.1s 掉到 5s 附近。
**未实测**——换书是为了脱离病态样本，不是因为 GIMP 不该修。

### 4.3 两次跨会话基线不可比

早期记录的"21.4s → 14.2s"作废：21.4s 是跨会话基线。同会话真实对照为旧包
19.4 / 22.5 / 12.8 / 22.1s vs 新包 15.0–16.0s（中位 15.1s），新包更快且方差小一个数量级
（极差 1.0s vs 9.6s）。

### 4.4 既有测试失败（非本次引入）

`CrossChapterPreflightProbeTest > a flip landing prewarms both neighbors … FAILED`
（`awaitPrepared(0)` 15s 超时）。在 `a86e8ee` 之前即失败。（最近一次 `:engine-skia:jvmTest`
未复现，需确认该测试属于哪个模块。）

## 5. 诊断口径约定

- 端到端用 `tap-flip start → tap-flip done`、开书用 `open:` → `jump: open ->`。
- 后台每页成本用 `win-prefill` / `asm` 的 `shape=`，但**必须配 `sStyles` / `sSkia` 一起看**：
  `shape` 里含暖机、含 `listMarkerFor` / `floatLeadAt` 等未拆项，只有 `sStyles`+`sSkia` 是真排版。
- **不要再拿 `pgBackfill` / `pg` 当代码耗时**——它量的是"求实参 + 函数本体"，实参里曾藏着
  全章重算（§2.1）。`bf copy=/calc=` 那对计时（0ms）是这条路径的护栏。
- 每块塑形成本必须按块数归一后看，且**必须同书同章比**（§1.3 的订正）。
- **`asm` 行的配对方向**：`asm` 出现在 `jump: open` **之前**（DISK-HIT → asm → 落位）。
  解析时把 `asm` 归给**其后第一个**落位行；归给上一个会得出"某一臂全 0"的假象
  （R29 踩过，纯配对错位，不是变体效应）。
- **只取 `sBlocks>0` 的 `asm`**：DISK-HIT 命中后后台还会发一批 `sBlocks=0` 的
  预排 `asm`，各字段全 0，混进来中位数就废了（R29 踩过）。
- 家族级对比（`eBox`/`eWid`/`eCol`/`eSty`/`eRad`）用 `tools/ab_families.py`；
  端到端与三段配对用 `tools/ab_probe.py` / `tools/ab_detail.py`。口径不同、校验不同，
  别混进同一个脚本。
- `AbSwitch` 具名开关的**期望自报值**要过 `ab_probe.py` 的 `expected_describe()`
  归一化（`slowRegex=1` → `describe()` 报 `slowRegex`）。直接拿命令行参数去比
  会把**已生效**的变体误判成"未生效"、白扔一批样本（R29 踩过）。
- `ab=` 串里 `warm=N` 可以**缺席**（`describe()` 只在非 0 时列出；纯具名开关
  就是这种形状）。解析时缺席即 0，别直接 `search(...).group(1)`（R29 踩过，直接崩）。

## 6. 改动落点与层级

| 文件 | 改动 | 层级 |
| --- | --- | --- |
| `BookDocumentController.kt` | 开书闸门、滚动派发、`OPEN_WARMUP_BLOCKS` 暖机、`openT` 基线 | 排版层（上）分页调度 |
| `BoxChapterLayouter.kt` | `LightPrepare.totalChars` 恒等式 + `by lazy`、`backfillBlockRanges` 的 `bf` 护栏、`ShapeProbe`（`sStyles`/`sSkia`/`sDepth` + R29 的 `cEdge`/`cFont`/`e*`）、锚页暖机 | 排版层（下）分层引擎基础设施 |
| `css/StyleComputer.kt` | §1.4 曾判"不必改"（当时量的是级联深度）；R29 改的是 `parseBorderColors`/`parseBorderStyles` 的四槽 key 查表（§3b.5） | 渲染层 · 样式级联 |
| `css/CascadeProbe.kt` | 诊断探针（`sink`/`splitSink`/`familySink`） | 渲染层 · 样式级联（给自己装仪表） |
| `engine/AbSwitch.kt` | 运行期 A/B 开关（`warmupBlocks` + 具名 flags）+ `controlMs()` 控制量探针。R29 从 engine-skia 移到 common（包名不变）——`StyleComputer` 在 common，而 engine-skia 单向依赖 common，反向引用不成立 | 引擎层 · 测量设施 |

未跨层。`css/*` 的改动全在"样式级联"这一职责本体里（层级表里它本就属渲染层）；
探针置位方在排版层（下），但置位方只负责在测量窗口内挂/卸 sink，不改排版行为。
