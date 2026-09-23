
我记得昨天讨论过，换行引擎自己用 c++ 或 rust 来写的可能性
回顾：Rust / C++ 自研换行引擎这条路线
核心定位：把「断词、换行、分页、文本度量」这一整块，下沉到独立原生库；KMP common 只做调用、模型传递；SkParagraph 只负责最后的字形整形 + 绘制，不再承担断行决策。
和纯 SkParagraph 方案的本质差别：
纯 SkParagraph：Skia 内部一次性完成【断词→换行→度量→布局】，你只能调参数，断行规则黑盒，英文自动断字需要外挂词典；
自研换行引擎（Rust/C++）：你自己完全掌控断行逻辑，输出每行的字符区间、行宽、剩余空白，再把拆分好的每行文本丢给 SkParagraph 做字形整形 + 度量 + 绘制。
两种语言选型对比：Rust vs C++
Rust（更推荐，对你的场景）
✅ 优势
内存安全，天然规避内存泄漏、野指针；阅读器长期翻页、大量段落反复排版，这一点很关键。
成熟的文本库生态：unicode-linebreak（Unicode 断行规则 UAX#14）、unicode-bidi、hyphenation 库（带预编译英文音节词典，直接做自动断字插入 U+00AD）。
绑定到 KMP：用 uniffi 生成 Kotlin 绑定，一套原生库编译到 Android (arm64)、Desktop、iOS；common Kotlin 直接调用。
可以独立做 benchmark、单元测试，脱离 Android/Compose 环境。
⚠️ 代价
学习成本：如果你不熟悉 Rust。
跨平台编译链路：需要配置 NDK / Apple SDK / 桌面目标；CI 编译脚本要维护。
边界：Rust 层只负责断行逻辑，不做字形渲染；字形度量（每个字符的实际宽度）仍然需要向 Skia 查询。
关键点：Rust 换行引擎不能凭空知道字符宽度。两种集成方式：
Pull 模式（推荐）：Rust 定义接口 measureTextRun(text, style) -> width；这个度量函数由 Kotlin+Skiko 调用 Skia 实现，回调给 Rust。Rust 拿到字形宽度，执行换行算法。
Push 模式：Kotlin 把文本 + 样式送入 Rust，Rust 内部回调 Skiko 获取字符宽度。
C++
✅ 优势
和 Skia 本身是同语言，集成最顺滑；Skia API 直接调用，没有 FFI 中间层。
可以直接复用 Skia 的 SkShaper 字形整形、字体度量，不需要跨语言回调。
NDK / CMake 成熟，Android 原生开发最传统方案。
⚠️ 代价
内存管理完全手动，容易内存泄漏、use-after-free；阅读器长时间阅读，内存 bug 会非常折磨。
没有开箱即用的 hyphen 词典库，需要集成 libhyphen，自己封装。
样板代码更多，单元测试成本更高。
架构（KMP + CMP + Rust 换行引擎 + SkParagraph 绘制）
plaintext
orilumns-common(Kotlin)
├─ epub解析、文档模型、书签、设置
├─ 【FFI绑定层】调用 Rust 换行引擎
├─ 回调接口：TextMeasureCallback（调用SkParagraph/SkShaper获取文本run宽度）
└─ CMP UI（手势、翻页、目录）

rust-linebreaker（独立native库，uniffi导出接口）
├─ UAX#14 Unicode断行规则
├─ 英文hyphen自动断字（hyphenation crate，插入U+00AD）
├─ 两端对齐分配空白计算（中文字符分散拉伸，英文单词间距拉伸）
├─ 分页逻辑：按页面高度切分行序列，输出Page模型
└─ 输出：每行文本区间、样式、行宽、空白分配参数

platform层
├─ Android：编译rust库为.so，Skiko获取字形度量
├─ Desktop：编译为native dylib/dll，Skiko
└─ iOS：编译为static lib，Skiko

绘制阶段：
Rust输出每一行的文本+样式+对齐参数 → 送入SkParagraph逐行构建段落 → Skia绘制
这条路线解决了哪些 SkParagraph 的痛点
完全掌控断行规则：UAX#14 你可以微调，EPUB 特殊换行规则、CJK / 英文混合排版自己说了算，不再被 SkParagraph 的WordBreak枚举限制。
英文自动音节断字：Rust hyphenation crate 自带多语言词典，自动在长单词内部插入 U+00AD；之后 SkParagraph 识别软连字符并渲染-。
两端对齐精细化控制：
SkParagraph 的kJustify是黑盒。你可以自己实现：
中文：剩余空白均匀分配到汉字之间
英文：空白分配到单词之间
混合段落：自定义混合策略
这一点是你当前 StaticLayout 最大痛点，自研换行引擎可以做到书籍级排版，比原生 SkParagraph justify 更精细。
分页逻辑和渲染解耦。换行结果是纯数据结构，可以序列化缓存；不需要保留任何 Skia native 对象来保存分页布局。缓存成本极低。
性能对比：自研换行引擎 vs 直接 SkParagraph
分层看：
断行算法本身：Rust/C++ 实现 UAX#14 极快，远快于 Kotlin 实现。
瓶颈转移到【字形度量】：换行循环里，需要反复调用 Skia 获取文本 run 宽度。跨 FFI 来回调用会有开销。
优化方案：批量度量文本 run，减少 FFI 调用次数；缓存同样式文本的字形宽度。
绘制环节不变：最后还是 SkParagraph/Skia 绘制，和纯 SkParagraph 方案绘制性能一致。
⚠️ 新增开销：FFI 边界。频繁在 Kotlin ↔ Rust 来回调用会产生成本。设计上要尽量粗粒度 API：一次性送入段落文本，一次性返回全部行信息，而不是逐字符调用。
工作量与风险（重点）
优点
排版规则完全自主，不再被 Skia 文本引擎的限制绑架；后续可以加竖排、标点悬挂、复杂 CSS 排版。
跨平台输出完全一致的分页结果，因为换行决策逻辑在同一套原生库，Skia 只负责绘制。
硬成本
实现完整 UAX#14 断行规则。unicode-linebreak可以直接用，不用从零写，但要适配 EPUB 的特殊规则。
文本度量回调的适配：处理不同字体、BIDI 双向文本、复杂字形（比如阿拉伯文）。
两端对齐空白分配算法，区分 CJK / 西文。
搭建跨平台编译流水线（Rust + uniffi 多目标编译）。
BIDI 双向文本处理（unicode-bidi crate），如果你的 EPUB 包含阿拉伯 / 希伯来文本。
取舍对比（三条路线横向总结）
路线 A：纯 SkParagraph
✅ 工作量最小；开箱 justify；
❌ 断行规则黑盒；英文自动断字要外挂词典；对齐策略不可微调。
路线 B：KMP + Rust/C++ 自研换行引擎 + Skia 绘制（当前讨论）
✅ 完全可控换行、对齐、hyphen 断字；分页结果全平台一致；可精细实现书籍排版；布局结果可序列化缓存。
❌ 工作量最高；需要维护原生库、FFI、跨平台编译；调试链路更长。
路线 C：原有 Android StaticLayout
✅ 测量快；系统文本选择；
❌ 中文两端对齐残废；仅 Android；分页溢出 bug。
推荐实施顺序（如果走 Rust 路线）
原型阶段：Rust 写最小 demo，用unicode-linebreak+hyphenation，输入一段中英文混排文本，输出断行位置。先不接入 Skia，单元测试验证断行和 hyphen 效果。
增加两端对齐空白分配逻辑，输出每行的间隙参数。
用 uniffi 绑定 Kotlin，实现粗粒度接口：layoutParagraph(text, style, availableWidth) -> List<LineInfo>。
在 Kotlin 侧实现 TextMeasure 回调，调用 SkParagraph/SkShaper 获取文本 run 宽度，传给 Rust。
接入 CMP+Skiko，把 Rust 输出的 LineInfo 送入 Skia 绘制。
集成到 Orilumns 阅读器，实现分页、缓存。
关键问题确认
你的 EPUB 是否包含 BIDI 双向文本（阿拉伯、希伯来）？
你对两端对齐的精细度要求：是否需要严格区分 CJK 字符间隙 / 西文单词间隙，而不是 SkParagraph 统一的 justify 策略？
是否打算后续支持竖排排版？（自研换行引擎更容易扩展竖排）
如果你想，我可以先写最小 Rust 原型（UAX#14 + hyphen 断字）的代码框架。
我可能还需要统一在相邻中英文字符间移除多余空格，插入统一空白
需求定性
这个属于文本预处理层，放在换行引擎之前做最合适：
EPUB 里经常出现：中文和英文 / 数字之间多出来的半角空格 ；排版规范要求：中英混排时，在汉字与西文之间插入固定宽度的空白（通常 1/4em 或 1/8em），同时删掉原有的多余空格。
注意：这一步是字符流层面的预处理，不是换行 / 两端对齐的空白分配，二者不能混淆。
区分两层空白（非常关键，不要混在一起）
静态排版空白（你现在要做）
在中英交界：
你好 hello → 删除中间普通空格，插入固定 em 空白（U+2000/U+2004 等）
这是文本预处理，在断行之前执行，属于内容层面的字符注入。
一旦插入，后续换行引擎会把这个空白当成普通字符参与宽度计算。
两端对齐动态拉伸空白（之前讨论的 justify）
是一行放不下时，动态分配剩余行宽，在单词 / 汉字之间拉伸间隙。
👉 必须放在断行完成之后，不能和上面静态空白混在一起。
错误做法：把中英间隙交给两端对齐算法去动态生成。会导致间隙大小随每行拉伸而变化，不符合中文排版规范。
处理规则（标准中文电子书排版规则）
规则集
识别边界：CJK 字符（汉字、日文、韩文） ↔ 西文（ASCII 字母、数字）
边界两侧连续的普通半角空格 U+0020 全部删除
在 CJK ↔ 西文 的交界处，插入固定宽度空白字符
常用：U+2004 1/3em，U+2005 1/4em（最常用，书籍排版）
也可以不用 Unicode 空白字符，而是在 style run 里标记一个间隙（推荐方案，下面讲）
例外：
标点紧邻英文不插入（hello，世界 逗号是标点，不是西文字母）
已经存在软连字符 U+00AD、零宽字符不处理
代码、URL、<pre> 块内关闭这个自动插入逻辑
两种实现方案二选一
方案 1：字符流注入空白字符（简单，但有缺陷）
预处理文本字符串，直接插入 U+2005 字符。
✅ 优点：最简单，unicode-linebreak、hyphen 词典、SkParagraph 全部把它当成普通字符，宽度由字体自动决定。
❌ 缺点：
插入真实字符，会影响断行；这个空白位置会被当成可换行点，你不希望中英之间这个 1/4em 空白处随意断行。
文本选区、复制时，这个空白字符会被复制出去，导出文本会多出不可见空白，原文被篡改。
方案 2：样式 Run 标记间隙（推荐，适合你的 Rust 换行引擎架构）
不修改原始文本字符串，不插入真实 Unicode 字符。
在文本解析阶段，把文本切为 TextRun，相邻两个 run 如果是 CJK ↔ 西文，就在两个 run 之间附加一个inline 间隙标记（inline_gap: EmUnit）。
rust
运行
struct TextRun {
    text: String,
    style: Style,
    /// 本run结束后，是否附加固定inline间隙（仅用于CJK<->西文交界）
    trailing_gap: Option<f32>, // em单位，例如0.25em
}
原始文本保留原样，复制 / 选中文本不会多出奇怪空白。
换行度量阶段：计算 run 总宽度时，额外加上 trailing_gap 的宽度。
断行规则：这个 gap不是可换行断点，不会在这里换行。
绘制阶段：SkParagraph 分段构建，在两个 run 之间，手动绘制一小段空白。
这个方案完美规避方案 1 的所有缺陷，是专业排版引擎（如 pagedjs、vivliostyle）的做法。
嵌入到你整体流水线的位置（Rust 换行引擎架构）
plaintext
EPUB/HTML解析 → 构建AnnotatedTextRun列表
    ↓
【预处理阶段（Rust）】
    扫描相邻TextRun，检测CJK ↔ 西文边界
    删除边界之间多余U+0020空格
    设置trailing_gap标记（0.25em），**不修改原始文本**
    ↓
【断行引擎：unicode-linebreak + hyphen自动插入U+00AD】
    ↓
【布局：计算每行宽度，两端对齐间隙分配（动态）】
    ↓
输出 LineInfo（每行包含run列表+trailing_gap标记 + justify间隙参数）
    ↓
送入SkParagraph / SkShaper绘制，在run间隙绘制固定空白
字符分类库（Rust）
直接使用 unicode-general-category + unicode-script 判断：
CJK：Script::Han / Hiragana / Katakana / Hangul
西文：Latin、数字
区分标点，单独处理。
边界坑点
连续多个中英交替：A中文B中文C，A↔中文、中文↔B、B↔中文、中文↔C，每处交界都要加间隙。
中英之间原有多个空格：hello 世界 → 全部删除，只保留一个固定间隙。
西文标点：hello.世界，.是西文标点，属于西文 run 末尾，和汉字之间也要加间隙。世界,hello，中文标点则不加。
字体缩放：间隙是 em 单位，自动跟随字号缩放。
和两端对齐的交互
这个 trailing_gap 是固定宽度，不参与 justify 拉伸。
当一行做两端对齐拉伸时：
固定中英间隙保持 0.25em 不变；
拉伸只发生在：汉字之间、英文单词之间，不拉伸这个中英间隔。
这是书籍排版标准行为。
备选权衡
如果你想快速验证原型，先用方案 1（插入 U+2005）快速跑通视觉效果；
等验证完成，再迁移到方案 2 的 TextRun+trailing_gap 正式版本。
如果你需要，我可以：
写 Rust 的 TextRun 结构 + CJK / 西文边界扫描代码；
或者定义 KMP 侧对应的 AnnotatedRun 模型，和 Rust FFI 对接。
kmp+cmp+SkParagraph 方案成熟度
KMP + CMP + SkParagraph 成熟度评估
分层看：KMP/CMP UI 外壳已经生产就绪；Skia/SkParagraph 底层文本能力稳定，但 Skiko 封装 + 电子书专业排版场景属于 “可用但非开箱生产级”，需要自己补齐上层排版逻辑。
1. 各组件成熟度拆分
✅ KMP（Kotlin Multiplatform）
稳定，生产可用
业务逻辑、模型、解析器、协程、序列化等 common 层基础设施成熟，大量商业 App 落地。
你把 EPUB 解析 / 书签 / 阅读状态抽到 common，这部分完全没有风险。
✅ CMP（Compose Multiplatform，UI 外壳）
Android / Desktop 稳定，生产就绪；iOS 稳定
JetBrains 官方标记生产可用，大量产品使用 CMP 做跨平台 UI、手势、设置面板、目录界面。
普通 Composable 组件、手势、动画、Lazy 滚动都成熟。
注意：CMP 高层 Text 组件不要用来做你的分页正文，你只用 CMP 作为 UI 容器，正文画布自己用 Skiko 绘制。
✅ Skia + SkParagraph（C++ 底层）
成熟、工业级，Chrome/Flutter 都在用
SkParagraph 本身是 Skia 内置专业文本排版模块，kJustify 中文两端对齐、多语言混排、BIDI、首行缩进、行高都原生支持，这正是你替换 StaticLayout 的核心诉求。
文本度量、字形整形、字体集合、软连字符 U+00AD 渲染能力完备。
局限：没有内置英文自动音节断字词典，hyphen 需要自己外挂；断行规则是黑盒，不能精细控制 UAX#14 细节。
⚠️ Skiko（JetBrains 的 Kotlin 绑定 Skia，关键风险点）
API 稳定，但属于底层图形绑定，不是专门给电子书做的
Android：JNI 桥，需要手动管理 Native 对象生命周期（Paragraph、FontCollection、Typeface必须.close()），内存泄漏是高频踩坑点。
Desktop：Skiko 体验最好，JNI 开销几乎不存在，字体加载逻辑和 Android 不一样，字体 fallback 是最常见坑（中文方框 tofu）。
API 层面：SkParagraph API 本身稳定，版本升级时 Skiko 会跟随 Skia 大版本迭代，偶尔有行为变更。
生态：没有成熟的电子书排版库基于 SkParagraph，没有现成分页、图文混排、选区、长按选词组件，全部需要自研。
2. 针对阅读器 Orilumns 场景的成熟度判定
适合直接落地的能力
中英混排、中文两端对齐（核心痛点解决）
跨平台一致文本渲染：Android/Desktop 同一份文本 + 同字体，渲染结果一致
自定义画布绘制、翻页动画、背景、文字颜色、阴影、首行缩进、段间距
多字体、内嵌字体加载
需要你自己从零实现（不属于 SkParagraph 自带能力）
分页逻辑：SkParagraph 只做单段落布局，不会自动按页面高度切分多页。
中英间隙处理（你刚提的：删除多余空格、插入固定 1/4em 间隙）：SkParagraph 没有这个排版规则，需要在送入 ParagraphBuilder 之前，做 TextRun 预处理（推荐用 run+trailing_gap，不插入真实字符）。
英文自动音节断字：SkParagraph 只识别 U+00AD，词典和插入逻辑要自己写（Rust/libhyphen）。
文本选区、点击命中、长按复制：SkParagraph 不提供系统原生文本选择，需要遍历 LineInfo + 字形位置，自己实现点击命中、选区绘制、文本提取。
复杂 CSS 盒模型、浮动图文、表格、ruby 注音：SkParagraph 只负责段落文本，布局盒子、图片排版需要上层自己实现。
标点悬挂、挤压标点等中文出版级排版微调：SkParagraph 可调参数有限，精细控制要上层接管换行。
3. 风险清单（工程层面）
字体跨平台一致性：Android 系统字体、Desktop 系统字体名称 / 字形不一样。必须统一字体集合策略，优先打包内嵌字体，否则同一本书在 PC 和手机上字宽不一致，分页错位。
Native 内存管理：忘记 close Paragraph 对象会持续涨内存，长时间阅读会 OOM。这是 Skiko 最常见的生产 bug。
版本升级成本：CMP 升级时 Skia 版本同步升级，偶尔会改变文本度量细微行为，需要回归排版基准用例。
Android 包体积：引入 Skiko 原生库，APK 增大十几 MB。
性能：段落测量 CPU 开销高于 StaticLayout，必须后台预分页 + 缓存 LineInfo，不能主线实时排版。
4. 两条路线成熟度横向对比
纯 KMP+CMP+SkParagraph（不做 Rust/C++ 自研换行）
✅ 成熟度中等，原型验证快，工作量更小
❌ 断行规则黑盒；英文自动断字需要外挂词典；中英间隙、对齐策略无法做到出版级精细控制。适合快速验证两端对齐效果。
KMP+CMP + Rust 自研换行引擎 + SkParagraph 仅绘制
✅ 排版规则完全自主，中英间隙、hyphen、UAX#14、两端对齐算法完全可控，跨平台分页 100% 一致
❌ 成熟度低，上层排版逻辑全部自研，工作量大，需要维护 Rust 库、FFI、跨平台编译流水线；适合长期迭代、追求专业书籍排版。
5. 推荐落地策略
先做原型验证，不要一次性全量迁移
搭建最小 common Demo：Skiko + SkParagraph，验证中文两端对齐、中英混排、U+00AD 软连字符渲染；重点测试 Android 和 Desktop 分页是否一致。
验证字体加载、内存释放、段落测量耗时 benchmark。
原型确认合格后，再决定是否继续叠加 Rust 换行引擎。
这个项目涉及多层跨平台渲染、文本排版和 FFI 集成，信息密度很高，工作任务模式可以帮你把技术选型、风险清单和模块接口整理成结构化工程方案，要不要用它继续？
Rust 自研换行引擎 + SkParagraph 仅绘制，两者会有偏差吗？
核心结论
只要你度量接口实现正确，分页、换行位置可以做到完全无偏差；但字形层面仍存在极小的潜在偏差源，需要提前约束。
架构本质区分：
Rust 换行引擎只负责布局决策（在哪里断行、每行包含哪些文本 Run、计算每行的可用宽度、分配两端对齐间隙、中英间隙）；
SkParagraph 只做字形整形 + 绘制，不再参与断行判断。
只要 Rust 拿到的字符宽度，和后续 SkParagraph 渲染时实际使用的字符宽度完全一致，换行位置就不会错位。
偏差来源分类
1. 【致命偏差：文本度量不一致】最容易踩坑
Rust 做换行计算时，需要调用回调拿到 TextRun 的宽度。
理想：Rust 度量得到的宽度 = SkParagraph 最终渲染出来的同一段文本宽度。
产生偏差的场景：
度量和渲染阶段字体集合不一样（字体 fallback 链不同，选用了不同字体）；
字号、字间距、文字缩放参数不一致；
SkShaper 字形整形（复杂连字、阿拉伯文 BIDI、字体 OpenType 特性）在度量时没开启，渲染时开启；
度量时只简单累加单个 glyph 宽度，忽略字偶距（kerning）、连字。
解决办法：
度量回调复用完全相同的 FontCollection、字体参数、shaper 配置。度量直接调用 SkShaper / SkParagraph 的布局接口获取真实 Run 宽度，不要自己手写字符宽度表。
不要在 Rust 内部硬编码字符宽度；Rust 只接收 Skia 给出的真实度量结果。
2. 【可忽略：浮点精度误差】
宽度都是浮点数，反复累加会有微小浮点误差，累积到长段落会出现 1px 以内的偏差。
处理方案：
统一使用相同精度（Float32）；
行宽比较时增加极小 epsilon 阈值；
分页高度计算统一基线，避免上下行基线累积偏移。
3. 【软连字符 U+00AD 行为】
Rust 插入 U+00AD 作为可选断点，Rust 的换行算法要识别这个字符作为合法断行点；
SkParagraph 渲染时识别 U+00AD，在断行位置画出-，但是 SkParagraph 不能再自行触发额外断行。
⚠️ 关键约束：送入 SkParagraph 的每一行文本，必须是已经固定好的单行文本，不能把整段丢给 SkParagraph 让它再自动换行。
也就是：SkParagraph 每行只构建单行段落，availableWidth 设为一个足够大的值，禁止 SkParagraph 内部自动折行。
这一点是消除偏差最核心的工程规则。
kotlin
// 正确：单行文本，宽度给超大值，SkParagraph只整形绘制，不再换行
val singleLinePara = ParagraphBuilder()
    .pushStyle(style)
    .addText(lineText)
    .build()
singleLinePara.layout(Float.MAX_VALUE) 
如果传给 SkParagraph 的是完整段落，并且设置实际页面宽度，SkParagraph 会再次执行它自己的断行逻辑，两套换行引擎打架，直接出现严重错位。
4. BIDI 双向文本（阿拉伯 / 希伯来）
Rust 使用unicode-bidi计算文本视觉顺序；SkParagraph 内部也会做 BIDI 重排。
风险：两套 BIDI 实现如果版本 / 规则有差异，Run 顺序不一致，宽度计算错位。
方案：BIDI 处理统一交给 Rust，Rust 输出视觉顺序的文本 Run 序列，Skia 不再二次 BIDI 重排。
5. 中英间隙 trailing_gap
Rust 计算行总宽度时，已经把trailing_gap固定间隙计入行宽；
绘制阶段，在 Run 之间手动绘制这个间隙，SkParagraph 文本本身不含这个间隙。
只要两边计算 gap 宽度公式一致，不会出现偏差。
验证策略：回归测试基线
构建自动化排版基准测试，消除偏差：
固定字体、字号、行高；
固定一段中英文混合 + 带 hyphen 长单词的样本文本；
Rust 输出每行字符区间、行宽；
Skia 渲染后，截图 + 像素测量；
对比：换行位置、每行文本、行总宽度，自动检测超出 epsilon 的差异。
对比纯 SkParagraph 方案
纯 SkParagraph：断行、度量、渲染全部一套引擎，天然无偏差，但断行逻辑黑盒；
Rust+SkParagraph 绘制：只要严格遵守 “Rust 全权决定断行，SkParagraph 只做单行整形绘制”，可以做到零换行偏差，代价是要严格管控度量接口和参数一致性。
最简架构约束（写代码时必须遵守）
SkParagraph 只用于单行，不做段落自动换行；
度量回调和渲染使用完全一致 FontCollection、OpenType 特性开关；
所有文本 Run、BIDI 顺序、trailing_gap、hyphen 标记全部由 Rust 输出；
缓存 LineInfo 是 Rust 输出的布局结果，不缓存 SkParagraph 原生对象。

