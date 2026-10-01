# 未尽事宜 / Open Issues

- **`text-align: justify` 有实现但**静默失效**（2026-09-30 记，R1 排版时核出来的，独立于该 bug 已修）**：
  绘制侧 `LineWindowDrawer.paintText:198` 有 `appendTrailingNewline = (alignment == JUSTIFY)`，
  本意是行尾追加 `'\n'`、把本行伪造成「双行段的首行」以获得铺满间距。**但 Skia 的 `kJustify`
  只对软断行产生的行拉伸**，追加硬 `'\n'` 后本行成了硬换行结尾的行 → 永远不被拉伸。
  实测（真机字体栈 @44.4px）：中部行（自然宽 1509.60 / 版心 1600 与 1560）追加 `'\n'` 前后
  首行宽**逐值相同 1509.60**，均不拉伸；只有「在绘制时自己又折了一次行」的才被拉伸（1820.40 → 1600.0），
  而正常断行出来的行本不该在绘制时再折。故正常情况零效果，与真机截图一致（非首行右缘参差 1695–1725，
  非齐平的 1720）。
  **与 R1 无关**：追加 `'\n'` 前后首行宽相同，故 R1 那 88.4px 溢出**全部来自缩进右移**，
  且 `FirstLineIndentSingleLineOverflowTest` 量的 `lineMetrics[0].width` 恰等于生产绘制侧真实宽度，测试有效。

  > **2026-09-30 更新：可行性已测完，本条的诊断已被推翻并重写。**
  > 详见 `docs/自建断行引擎-测试计划.md` 的 T3 / T3b / T4 / T4b / T4c / T4d / T5 / T5b。
  >
  > **旧诊断「Skia 无『让已放得下的行也拉伸』的开关」是错的。**
  > `ParagraphStyle.textAlign = TextAlign.JUSTIFY` **可用且质量完美**：
  > 真实中文 326 个非末行，右缘误差 **100% < 0.5px，最大 −0.0002px**。
  > 机制（T4b 实测定性）：Skia 把 slack **均摊到字形 advance**，
  > `ΣΔadvance = +37.386px`（= slack）、`ΣΔ字缝 = −0.0002px` —— 等价于「CJK 逐字分摊」策略。
  > 末行不拉。行末尾随空格时 `rect.right` 超版心是 Skia 正确行为（空格不可见），测量须剔除。
  >
  > **真正的根因只有一条**：`appendTrailingNewline` 那个 `'\n'` **本身就是多余的、且有害的**。
  > 它把软断行行变成硬换行结尾行，`kJustify` 遂不生效。
  > 正确的最小修法是**删掉 `appendTrailingNewline`**，让行以软断行身份进 Paragraph。
  >
  > **但这条路有硬天花板（T4c 实测）**：Skia 的 `JUSTIFY` 只能服务于 **Skia 自己选的断点**。
  > 往文本注入 ZWSP `U+200B` **只能加断点、不能减**（Skia 按行宽优化择优，多给候选它仍选原来那个）；
  > 注入 WJ `U+2060` **无效且非零宽**（插 1 个 WJ 使首行 36 字 → 35 字）。
  > → **Skia 原生 JUSTIFY 与自建断点不可兼得，必须二选一**：
  > - **路线 S**（原称「路线 J」，改名避免与 `Justify` 混淆）：Skia 断行 + Skia JUSTIFY。
  >   R3 白拿；但 R4 停在 99.0038%、**R2 断词结构性不可行**。
  > - **路线 B**：自建断行 + 自建 Aligner + 逐字 `drawString` 落位。R1–R4 全达标。
  >
  > **✅ 2026-09-30 已定：路线 B 是唯一解。** 四条需求无妥协余地 → S 出局（R2 对 S 结构性不可能：
  > 画 `-` 必须自己知道断在哪，而 S 的断行是 Skia 的；R4 对 S 永远停在 99.0038%）。
  > S 下 `appendTrailingNewline` + `Alignment.JUSTIFY` 一并废弃。
  >
  > **性能已测完（补测 4 项中的第 1 项「章级/整书级 prepare」是唯一可能翻盘的数）**：
  > 章级 prepare 里**断行占 83%**；路线 B 完整模型（量宽 + 生产禁则断点 + 输出分配）
  > **K3 = 0.25x，比 Skia 基线快 4 倍**，章级倍率 **0.370x**、整书**负增量**，
  > 判据第 1/3/4 条（断行 ≤2x、单章 ≤1.5x、整书 ≤1s）**全部达标且余量大**。
  > 此前 T5 报的「断行 4.46x」是 **941 字合成大段的最坏情况高估** ——
  > 真实 leaf 平均仅 32.8 字，`build+layout` 固定开销占比高，逐字相对占比低。
  > 采信前过了三道闸门：量宽正确性（误差 ≤0.000072%）、抗噪 min-of-15、行数公平性（525/539 = 0.974）。
  > **唯一仍不达标的是绘制 2.10x**（判据 ≤1.2x），而 prepare 阶段不绘制，两者不冲突。
  > → **不触发 Rust 的重新评估条件**（"性能不达标且优化手段用尽"）。
  > 详见 `docs/自建断行引擎-测试计划.md` T5e；剩余 3 项见该文档 T5「待补测」。

- **Q1 — 长串（超长不可断单元）症状用户无法复现（2026-09-30 记）**：
  真机曾见长串行溢出/参差，但用户侧无法稳定复现，缺可复现样本。
  自建断行引擎**必须不比 Skia 差** —— T1 等价性（99.0038%）已隐含覆盖该场景，
  但**不等价于「长串更好」**。本轮不作为 R3 的判据，仅留作悬念；
  若日后拿到可复现样本，需单独立项对比 Skia 与自建实现。

- **Q2 — 断词（hyphenation）方案已选型、待定案（2026-09-30 记）**：
  skiko 的 paragraph 包**零 hyphenation API**（javap 扫 3 个类命中 0）→ 只能自建。
  已实测选定 **Knuth-Liang + 新版 `hyph-en-us`（11125 patterns / 106KB，纯数据，KMP 安全）**：
  与权威参考实现 pyphen 0.18.1 在 **370105 词**上做断点集差分 → **100.0000% 一致，多 0 缺 0**；
  Kotlin 侧 **0.0011ms/词**，相对 `build+layout` 0.32ms 可忽略。
  **落地前提已解除**（2026-09-30）：路线 B 已定为唯一解，本条不再是"被路线卡死"，
  而是 B 路实现里的一个待办子项（软连字符绘制 + 断点接入正文断行单源）。本轮只做 en-US。
  步骤见 `docs/自建断行引擎-实施方案.md` S7。

- **Q3 — R1 长串：F11（`LongStringBreaker`）/ F12（`overflow-wrap`·`word-break`）零消费（2026-09-30 记）**：
  两处都已实现但**全项目零消费**（grep 只命中自身单测与 CSS 级联）：
  - F11 `LongStringBreaker.breakOpportunities`（`common/text/preprocess/LongStringBreaker.kt:26`）
  - F12 `ComputedStyle.overflowWrap` / `.wordBreak`（`ComputedStyle.kt:311/313`，由 `StyleComputer` 正常解析）

  **决策：先不接线**，理由三条 ——
  (1) R1 症状**用户无法复现**（见 Q1），为一个不可复现的症状扩 `ParagraphBreaker` 接缝不划算；
  (2) CSS `overflow-wrap: normal` 的语义本就是「只有在否则会溢出时才在任意点断开」，
      即 R1 core，与 `break-word` 的区别只在**是否影响 min-content 尺寸**——
      而 `minContentWidth` 已独立走 `minContentSegments` 计算，不经过断行器；
  (3) 接线要改 `breakLines` 3 个重载 × 2 个调用点，**接缝扩大后 S3 之后再改更贵**。

  落地为「独立后续项」，与 S3 主链路解耦。详见 `docs/自建断行引擎-实施方案.md` §2.2(c)。

- **Q4 — 多语言：其他语言的断词与禁则（2026-09-30 记，本轮不做）**：
  本轮范围 = **中文实现 + 英文断词 + 中英文禁则**；其他语言**只留数据形状上的口子**，不实现、不扩接缝。
  - **英文断词不需要 `lang` 参数**：适用范围按 **script 判定**（只对纯 ASCII 拉丁字母 token 跑 K-L），
    中英混排的书里只有英文词被断，中文一个字不动 → `ParagraphBreaker.breakLines` **零扩张**。
  - **已知局限（不修，写进锁）**：法/德/西也是拉丁 script，会误用 en-us 断在非音节处；
    拉丁变音字母（`ß é è`）不在 ASCII 白名单内天然排除，损害面限于「无变音的西/德/法词」。
  - **口子留在数据形状上（不是接缝上）**：① 禁则表收成 `KinsokuRules` 值对象；
    ② 每语言的 `lefthyphenmin`/`righthyphenmin` 随词典走（en = 2/3，德法不是，**且这两个参数不在 pattern 数据里**）；
    ③ `Hyphenator.hyphenate(text, lang)` 的 `lang` 是形参不是全局读；
    ④ `BreakOpportunitySet` 收「断点增强器」入参，不硬编码调 en-us。
  - **书级语言目前是零**：EPUB `dc:language` 未解析、`ComputedStyle` 无 `lang` 字段、`:lang()` 不支持
    （`HtmlTreeConverter.kt:190-193` 把 `lang`/`xml:lang` 存进 attrs，但断在属性层）。
    `font_faces.lang` 是**字体表**的一列（跟着 `FontFace` 走），**语义未查证**，但它**不是「书的语言」**。
  - 详见 `docs/自建断行引擎-实施方案.md` §0.1 / §0.2 / S7。

- **Q5 — 含拉丁字母的行失去 kerning 与 fi/fl 连字（2026-09-30 记，本轮有意接受）**：
  路线 B 的管线是「逐码本量宽 → 逐字 `drawString` 落位」，`Font.getWidths(glyphs)` 是裸 cmap 查表、
  **不整形**，而 skParagraph 走 HarfBuzz（带 `liga` 与 kern 表）。故两者在拉丁字母上不等宽。
  - **量级（实测，T8.4）**：连字只在 CJK 面（`STSong` 的 `fi` **−9.00px** @100px、`Songti SC` −4.10、
    `PingFang SC` −2.10）；kern 只在拉丁面成规模（Times New Roman **59 对**最大 **−3.52**、
    Helvetica 56 对最大 −1.76、**Georgia 0 对**）。CJK 侧**完全干净**：兰亭序 84 字长串 Δ=+0.000000%。
  - **暴露面（实测，`books/` 11 本 epub 正文）**：3 本中文网文 ≈ 0 处/万字符；
    英文书 25–32 处/万字符（moby-dick 32.24、gutenberg-84 31.12、gutenberg-1342 25.52）。
  - **为什么本轮不修**：① skiko `Paragraph` 无任何字形/簇级位置查询（`javap` 确认），
    「整段整形一次 + 查每簇位置」这条路不存在；② kern 可以在 Aligner 里补
    （`w_j = adv_j + kern(cp_{j−1}, cp_j)`，两侧同表即自洽），但**连字补不了**（它改变字形个数），
    而连字恰是 CJK 面上更大的那个 —— 补一半要「每面 62×62=3844 次 Paragraph 测量建表」，
    覆盖小偏差、放过大偏差，投入产出不成立；③ 要真补必须连绘制侧的 `Paragraph` 通路一起改，超出本轮范围。
  - **为什么这是「自洽」而不是「缺陷」**：断行侧量 `Σ` 逐码本、S4 Aligner 的 `x_i = x_0 + Σ(w_j+delta_j)`
    用同一个 `advanceOf`、S5 逐字绘制 —— 三者同源即量画一致。全部整形缺口**都是负的**
    （15 个面 × 多组实测无一为正），所以任何「比量出的更窄」的绘制都不可能溢出版心。
  - **补的话怎么做（留档）**：在 `SkiaRunMeasurer` 里加一层「per-face kern 表」，
    按 (面, size) 懒建表（`para("ab") - adv(a) - adv(b)`，62×62 上界），
    让 `advanceOf(i)` 返回 `adv(cp_i) + kern(cp_{i−1}, cp_i)`。连字仍无解。
  - 详见 `docs/自建断行引擎-实施方案.md` §2.2(d) 与 `docs/自建断行引擎-测试计划.md` §T8.4。

- **Q6 — 表格侧：min 侧闲置 + 自建侧无真测量（2026-10-01 记；S3 接线时已查清前半个前提）**：
  auto 分列的 min 侧只有一个消费点（`NormalFlowLayout.tableCellPref` → `ParagraphBreaker.minContentWidth`）。
  真机 A/B 查明：13 本语料 49 张表**全部**落在 `autoColumnWidths` 三段式的 `avail >= totalMax` 段，
  该段 `w[i] = pref[i]`，**min-content 一次都没被读过**。故 T2d/T2e 的禁则补表在现实版心下不可观测。
  - **量级**（实测，`docs/自建断行引擎-测试计划.md` §T2f）：格级 7 处变化 → 列级 **1** 列 → live **0 列**。
    表要进插值段需版心 ≲ 0.8× 字号量级；即使挤进去，49 张表里也只有 1 列可观测。
  - **对 S3 的影响**：自建断行器的 R1（长串兜底）价值**不来自 min-content 这条路**，
    表格这条线已整条从 S3 验收判据里划掉。
  - **【2026-10-01 新增，比上面更要紧】自建侧根本没有真 `preferredWidth`/`minContentWidth`**：
    `InhouseParagraphBreaker` 未覆写这两个方法，用的是接口默认桩 `段长 x fontSizePx`
    （接口 KDoc 自称「CJK 精确、拉丁偏宽、永不窄于实需」——**永不窄于实需在表格语境下恰恰是坏事**）。
    实测 fs=44.4 / `STSong,serif`：`preferredWidth` CJK 1.000（汉字 advance 恰好 1em，巧合）、
    Latin **2.49x**、URL **2.09x**、混排 **1.95x**；`minContentWidth` Latin 2.61x / URL 2.03x。
    ⇒ **表格侧的正确解锁顺序是「先补真测量，再谈可观测」**；仅让表格落进插值段（fixed 列宽 /
    指定列宽 / 更多列 / 更窄版心）**不足以**安全切换。此前把这条写成「满足其一即可」是错的，已订正。
  - **接线时踩到并已修的坑：重路径的表格度量并不走 `tableBreaker`**。
    重路径用的是 `NormalFlowLayout.buildTableRows` → `tableCellPref` 里**透传**下来的正文断行器
    （`NormalFlowLayout.kt:746`），所以正文一接线，重路径表格列宽就跟着变体走 —— 与轻路径分叉，
    且自建侧那个桩会让 Latin 列宽翻倍。已加 `heavyPathBreaker` 把**度量方法**单独钉回 Skia
    （断行仍走变体），并用 `TableBreakerStaysSkiaTest` 双向钉住（变体开关 + 变异验证）。
    原则一句话：**变体只该改变行，不该改变列。**
  - **顺带查清的两处缓存陷阱**（不是 bug，但会让人量到假结论）：
    ① `LayoutParamKey` 不含禁则表身份 ⇒ 改断行规则不改 `paramHash`；
    ② `app/build.gradle.kts:18` 的 `versionCode = 20` 是写死常量、非单调递增构建号，
    故 `PaginationCacheCodec` KDoc 里「换构建号即全量作废」在本工程从未真正发生。
    ⇒ ~~**S3 接线时必须 bump `LAYOUT_VERSION`**，否则线上老用户会静默复用 Skia 断点算出的旧表。~~
    **已消解（2026-10-01 S3 接线实测推翻）**：改为把断行器变体作为字段**进 `LayoutParamKey`**
    （`inhouseBreak`，默认读运行期开关），键即可精确区分两侧，`LAYOUT_VERSION` **不必 bump**。
    该字段用**变长喂入**（只在 true 时追加哨兵字节），于是 `inhouseBreak=false` 那一侧
    的 `paramHash` 与接线前**逐字节相同**（金标准 `908642712`，Python + Kotlin 参考实现 +
    生产 `Crc32` 三方独立算出同值）。原判断错在：以为「断行规则不属于排版参数」；
    实际上换断行器换断点换页切点，它**就是**一个排版参数，只是此前没人把它当成参数看待。

- **【2026-10-01 变更】断行器变体默认改成 on：自建断行器成为生产主路径，Skia 降级为回退阀**：
  - **动机**：替换 Skia 断行。个人项目、无既有用户，故「老用户缓存全量作废」这条成本归零。
  - **改动面**：只改 `AbSwitch` 里一处默认值（`DEFAULT_ON_SWITCHES = setOf("inhouseBreak")`）。
    `bodyParagraphBreaker` 与 `LayoutParamKey.fromProfile` **一行未动**——它们本来就读同一个开关，
    这正是当初收成单源工厂的收益：换默认只改一个常量，不可能出现「键按一侧算、断行按另一侧跑」。
  - **连带必须做的两件事**（不做会静默出错，均已补）：
    ① **`apply()` 补上「关」的方向**。原本具名开关只有「加」没有「减」（`=1` 才进集合）；
    默认 off 时「关」等于默认、不写就够用，默认 on 之后不补则 `ab="inhouseBreak=0"`
    会被**静默忽略** —— 回退阀当场变成**单向门**，而它存在的全部理由是「不用回滚安装包」。
    现改为三态表：`显式关 > 显式开 > 默认开`（`isOn` 里 `in off` 分支必须排在 `in on` 之前）。
    ② **测试夹具两侧都要写显式值**。`resetForTest()` 现在回的是**各自默认值**（= on），
    只靠 reset 取「Skia 那一臂」会拿到自建，于是 Parameterized 锁的 skia 臂
    静默退化成「自建跑两遍」（`DrawLineBuilderTest` 38 个用例实测仍 19+19 分两侧，
    靠的就是改成显式 `inhouseBreak=0/1`）。
  - **锁的语义整体翻转**（不是新增，是改守的东西）：
    `DefaultSideIsUnchangedTest` 原守「默认侧 = 接线前」，那条性质**整体消失**了
    （默认侧换引擎了）；改为守**回退侧**——`inhouseBreak=0` 那条路必须与接线前的裸
    `SkiaParagraphBreaker` 逐值相同。`LayoutParamKeyTest` 的金标准 `908642712`
    同样改为钉 `inhouseBreak=false` 那一腿，且构造点**显式写 `false`**：
    靠字段默认值的话，锁会跟着默认值一起漂，锁还在但守着的东西已经变了。
  - **未验证项（重要）**：三道闸门（行数公平性 ≥95%、总行数比值 ∈[0.95,1.05]、
    章级 prepare ≤1.5x）与真机 A/B **仍未实测**。当前「可上线」的依据是
    **1110 个单测全绿 + 回退侧逐值一致的锁 + 两侧表格列宽一致的锁**，不是闸门数据。
    真机一旦量到闸门不达标，先 `am start ... --es ab "inhouseBreak=0"` 退回。
  - **回退命令**：`adb shell am start -n orilumn.reader/.MainActivity --es ab "inhouseBreak=0"`

- ~~开书被调两次~~ **误判，非 bug（2026-09-29 记 → 2026-09-30 查清）**：原以为 `open ok chapters=0`
  后 140ms–900ms 又一次 `open: chapters=N` 是 `openBookEngine` 并发重入。**查清：每次开书只调一次**，
  那两行是同一次开书的两个阶段，且 `chapters=0` 恒为 0。
  - **计数证据**（10 次开书会话，`日志_20260930.1.txt` + `日志_20260930.txt`）：`ReaderActivity onCreate`
    10 次、`open ok` 10 行、`open: chapters=` 10 行，三者严格 1:1:1；`viewport changed` **0** 次。
    真重入必然出现某会话两行 `open ok`，一次都没有。且 10 行 `chapters=` **全是 0**。
  - **两行的真实来源**（都显示 `[Orilumn.Reader]`，因 `ReaderActivity.kt:250` 把自己的 `TAG` 当第 4 个
    位置参数传进 `BookDocumentController`，覆盖了默认 `Orilumn.Engine`；两行又都以 "open" 开头，故像两次）：
    ① `ReaderActivity.kt:271` `open ok` = 引擎容器建好；② `BookDocumentController.kt:418` `open: chapters=N`
    = 书真正解析，走 `ReaderScreen.kt:155` `push("open")` → `TabletReaderHost.kt:68` → `controller.open()`。
  - **`chapters=0` 不是"解析出空书"**：`chapterCount` 是 `book?.spine?.size ?: 0`（`BookDocumentController.kt:382`），
    `book` 要到 `open()` 内部 :405 才赋值 → 记这行时书还没解析，**架构上恒为 0**。文案有误导性，代码无问题。
  - **`external BUSY-DROP` 不是重入证据**：是 `LaunchedEffect(externalPos)`（`ReaderScreen.kt:251`）撞上
    在途的 `push("open")`（.019 持锁到 .539 `open done`）——`AnchorFunnel` 设计内行为（不排队、记 BUSY-DROP），
    `open` 侧另有 300ms 重试兜底；开书时 `externalPos` 本就是 null，丢了不丢东西。
  - **重入口子理论存在、实测未开**：`LaunchedEffect(pxW, pxH)` 的 guard 读 `engine == null`，而 `engine` 是
    `mutableStateOf`（`ReaderActivity.kt:102`）、赋值在 `withContext(IO)` 之后；IO 窗口最长 1.65s
    （12:32:33.486 → 12:32:35.137）内 `engine` 确为 null，若 `pxW/pxH` 变化会重入。但 `viewport changed` 为 0、
    `open ok` 数 = 会话数，**该窗口从未被触发**。若日后真要防，加 in-flight marker 即可（当前无证据支持改动）。

- **重路径级联约 1s/书待优化（2026-09-29 记，JVM 实测；拿设备数据再定做不做）**：重 `prepare` 全书
  ~8s 中级联（匹配+求值）占 ~1s（1.8ms/章），盒几何+塑形占 ~7s（归位正确，不动）。1s 不在翻页/跳转
  热路径（那两条走轻路径），出现位置：(1) 小章前台（开书/目录跳转/关面板重排的 SMALL-FULL，
  单章 100–300ms 含塑形）；(2) B2 整书后台（分摊，不阻塞）；(3) 同参数二次命中 `prepareResult`
  缓存，零成本。候选：规则索引（匹配加速，不动语义）、作者侧烘焙（`ComputedStyle` 血统拆分，
  大改，P4 已取消过一次）。**动工门槛**：先上设备量小章跳转/关面板两条真机耗时，1s 中有多大
  比例落在用户等待线上，再定做哪个；落盘口径见 `docs/调试日志与分页跟踪.md`（`relayout … SMALL-FULL`
  的 `fullLayout=` 分段）。

- **轻重双路等价实现收敛（2026-09-29 记，排版稳定后处理）**：轻 `computeStructure` 与重 `prepare` 各自贴各自的数据表示（`MarkupElement` vs `LayoutBox`），三处“同义双实现”并存：`hidden` 判定（懒 `resolveHidden` vs 表查 `displayNone`）、叶枚举与 charStarts（`styledCharAdvance` vs 盒 `textLength`，P1-2 称同式，`IncrementalReplayEquivalenceProbeTest` 锁等价）、属主映射（轻 map vs 重盒 flag）。phase-1 生成内容已收敛到 `genPhase1` 单源（`fix/scheduling`）。彻底统一须先统一表示层（`ComputedStyle` 按属性血统拆分：作者/UA 侧烘焙 + reader 侧 overlay），动级联表示层，单列大项；收敛前任何改动必须双路同改 + 等价测试。**已撞出的具体实例见下条「容器 float 在轻路径不生效」**（轻 `computeFloatLeads` 只对叶注册 float、重路径递归能处理容器 float）。

- **容器 float 在轻路径不生效（2026-09-29 记，R26 查 `anyFloat` 时撞出；既有缺口，非本轮引入）**：
  `<div style="float:left"><p>文字</p></div>` 在轻路径（临时表/增量）上环绕不生效，要等磁盘表就绪才正确。
  **取证**：新写 `LightFloatLeadTest`（6 例）时撞到；把 R26 的 `anyFloat` 改动（`07f6666` 之前）取回来重跑同一组测试，
  **6 例中同样 5 例过、同样 1 例挂** —— 与本轮改动无关，是原本就有的缺口。
  **根因**：`enumerateBlockLeaves` 只把**叶**放进 `leaves`，`<div>` 自己不是叶（它有块级子节点），
  叶表里根本没有这个 float；`computeFloatLeads` 的前向透传只对叶注册 float，祖先链只 `preClear(clearSide)`、
  不注册祖先自身的 float。重路径的递归能处理（`P6aFloatTest` 走的正是重路径），**故两路不一致**——
  属文首「轻重双路等价实现收敛」的同类实例。
  **与 `anyFloat` 无关**：标志的检测口径（`computeStructure` 里非 `#text` 叶取自身样式、`#text` 叶取父级）
  与原 `blockStyleFor` 扫描在非 `#text` 叶上是**同一个表达式**、在 `#text` 叶上都取父级，两者等价；
  它既没制造也没掩盖该缺口。改动前后 5 例同样通过，即 R26 对有 float 的书**行为中性**。
  **当前状态**：`LightFloatLeadTest` 以 `KNOWN GAP` 命名**锁定现状**（断言两表全 null），
  补上容器 float 时该测试会失败并提醒更新。
  **待办**（**行为变更，不在性能这轮范围**）：让前向透传在祖先链上注册 float（而非只 `preClear`），
  须同时确认与重路径递归同式 + 磁盘表/临时表两路等价，并 bump `LAYOUT_VERSION`。

- **桌面真背光后续（2026-09-27，macOS 先行落地）**：macOS DDC/CI 已通（`desktopApp …/brightness/`：`DisplayBrightness` 接口 + `DdcPackets` + `MacDisplayBrightness` JNA，真机读写闭环；>0 下发硬件150ms防抖、≤0 纯遮罩、跟随系统不碰硬件；滑块按探测切量程 -50~100 / -50~0）。**Win/Linux 空实现位**：Windows 接 Dxva2（`GetPhysicalMonitors`→`SetMonitorBrightness`，JNA）、Linux 接 ddcutil（/dev/i2c，需 i2c 组权限），同接口各自实现；显示器插拔重探（当前启动探一次）后续补。

- **项目更名 orilumn → orilumn（✅ 已办，2026-09-27）**：包名 `orilumn.reader` 全量、数据根 `~/.orilumn/`，条目退役（原改名清单删除）。

- **目录面板标题高亮定位**：目标是"仅高亮当前页内的标题、不在页内的不亮"（高亮下边框已去掉）。当前页内标题 id 集合逻辑已正确（有单测），但具体书籍上章内子标题仍常高亮不到——疑似 TOC 条目的 fragment 与正文标题元素 id 不一致，无法建立"目录项 ↔ 页内 char"的命中。**推迟到排版稳定后再处理**。

- **阅读定位不稳定（整改 D）**：**根因已在 C1-2 收编，契约落码，仅剩设备实测确认**。原症状："未翻页重开"会位移（保存当前页 locator.charStart，重开定位同 char 可能落到不同页），两个根因：(1) 大章临时表 vs 磁盘表 char 定位不一致；(2) 临时转正 `onBackgroundCanonicalReady` 与 `scheduleSave` 竞态存下旧临时页 char。
  - **已落地（2026-09-19，C1-2/C1-3）**：char 语义统一为 **canonical 磁盘表权威**——`onSaveProgress` 读 displayed slice 落盘（2026-09-26 修正：存档不再先 `finalizeOnLeave`，存档不是离开，杀活 temp 会话会锁死大章；finalize 只属于离开路径）；`ensurePageRangeShaped` 的 seam 诊断转正为 canary（新窗口贴合旧窗口时共享边界必连续，[BookDocumentController.kt:494](app/src/main/java/com/orilumns/engine/BookDocumentController.kt)），temp→canonical 交接再加 handoff canary（`:1225`），回归会大声失败而非静默漂移。
  - **剩余待办**：设备实测脚本（记保存 char → 重开 restore char 及落页）**未见留痕**，需跑一次确认位移消失；顺带核对"章内翻页是否真走全量路线"（此前怀疑全量路线从未实行）。

- **增量分页的三条线程调度优先级（整改 F）**：**契约已落码（C1-2），不再是"缺契约"状态**。前台 temp shaping / 后台 `canonicalDispatcher` / 后台 `tempPrefillJob` 的 F>A>B1>B2>P 优先级契约已写入 [BookDocumentController.kt:66](app/src/main/java/com/orilumns/engine/BookDocumentController.kt) KDoc 并与桌面同契约（[DesktopReaderHost.kt:80](desktopApp/src/main/kotlin/com/orilumn/desktop/DesktopReaderHost.kt)）；dispatcher 可注入（`injectedCanonicalDispatcher`，默认私有单线程 executor，前台翻页永不与整章塑形争共享池）；canonical 阶段采样前台活动用既有 `ensureActive` checkpoint 让位。
  - **剩余待办**：桌面端后台 canonical/整书预排**暂未启用**（单章懒加载已覆盖桌面打开模式）——启用时须复用同一分发器划分，不另起炉灶。

- **表格 auto 列宽：算法与 intrinsic 输入均已与浏览器同解**：`TableGridModel.autoColumnLayout`
  的表用宽/分配规则已改成浏览器同解——三段式（`avail≥MAX` 不撑满、`avail≤MIN` 溢出、
  之间按 `(max−min)` 线性插值；CSS 2.2 §17.5.2.2 骨架，中间段规则由 Chrome 实测反推并单测固化），
  列 min/max 也已计单元格 `padding+border`（`cellPref`）。**输入层也已真测**：max-content 走
  断行器「不限宽塑形取最宽行」（`preferredWidth`）、min-content 走「最长不可断段」逐段真测取最大
  （`minContentSegments` 纯函数＋`minContentWidth`，Chrome `width: min-content` 68 串样本标定），
  经 `NormalFlowLayout.tableCellPref` 单源喂重/轻两路（含行内 face 段、`white-space: nowrap` 即
  min=max、空文本留边）。`internallinks` 表1･1 真书端到端（`InternallinksTableWidthTest`）：
  321/321/230/287/430 vs Chrome 306/313/253/282/434，各列 ±10% 内（余差来自系统字库不同，
  同输入同算法已锁定）。待办：单元格指定 `width`（§17.5.2.2 step 1）、列/colgroup `width`
  （step 2/4）尚未实现；`table-layout: fixed` 现为各列均分，而规范是按首行单元格定列宽
  （§17.5.2.1）。

- **表格单元格边框仍是合成 1px 单框**：`TableCellLines.emitCell` 无条件画 1px 实线框，**颜色已随
  CSS `border-color`**（未声明回退 `currentColor`，见 `borderArgbOf`），但仍未按 CSS
  `border-width`/`border-style` 门控，也表达不了四侧异宽/异色/虚线。浏览器在 `border-width:0`
  或 `border-style:none` 时完全不画——此差异待后续处理。

- OpenGL 翻页动画

- **系统字体中文名方案B（✅ 已在 JVM 端先行落地，`fix/font-panel-review`）**：中文名链现为
  「name 表直读（方案B）优先 → CoreText（方案A）补缺 → 族名本身回退」（公共字典已删——
  字体太多靠字典穷举不现实，是什么显示什么）。方案B 不依赖 CoreText/系统语言：
  `FontParser.familyNamesOf` 纯字节解析 OpenType name 表（按拉丁族名尾缀匹配变体语言——
  TC→zh-TW、HK→zh-HK、MO→zh-MO 优先，其余含 SC/无尾缀→zh-CN 优先；回退任意含 CJK 记录；
  **不筛简繁，name 表里是什么就显示什么**——日文假名照显示），桌面 `NameTableChineseNames`
  扫系统目录建「拉丁族名 → 中文族名」映射（RandomAccessFile 只读表目录 + name 表区间，不整读
  大 TTC，进程内缓存一次）。本机 326 族中 name 表命中 61 族，关键族全中（LXGW/Sarasa/
  Songti SC/Hiragino GB/思源黑宋/屏阅初夏明朝体；STHeiti 走 CoreText 兜底）。**将来断行引擎
  改 Rust 重写时**，同逻辑搬进引擎侧（原规划），JVM 端实现保持。切换前以 name 表直读 +
  CoreText 维持双链。

- 批注/修订模块，以及修订后的 epub 导出、批注导出

- **字体管理面板余项（F 系列收尾，2026-09-23 列表；除 6 外均已闭环）**：字重枚举 + 中文名
  方案A 已合入（`feat(F-F5)`），以下是目验/追问后发现的不算完结的事项：
  1. **霞鹜文楷仍显示英文名（已闭环，闭环方式变更）**：初版经字典补 LXGW/Sarasa/方正等
     开源中文族段（`feat/font-panel-remaining`）。**`fix/font-panel-review` 起字典已整删**
     （字体太多靠字典穷举不现实）——改由 name 表直读（方案B）覆盖：`NameTableChineseNames`
     现命中 61 族，LXGW 霞鹜文楷系（霞鹜文楷/等宽/GB/圆角）全由 name 表 zh-CN 直出；Sarasa
     更纱系、Fandol 方正系、Noto/Source Han 思源系同样由 name 表直出；真没中文记录的族
     （Hei/Kai 等 Apple 老式族）展示层回退族名本身（Hei→"Hei"），用户已确认该口径。
  2. **字体名与字重观感重叠**：预览行按真实字形渲染族名，粗/黑体系名称本身带字重，
     与副标题「语种 · 字重表」互相干扰，观感上「名」与「重」叠在一起。
     待办：评估预览改中性字重/统一字号的取舍（损失「所见即所得」语义），或调整
     名称行与字重行的排版/间距；定方案再动，不仓促改。
     ✅ **已办（`feat/font-panel-remaining`）**：保留所见即所得（真实字形度量已驱动行高，
     无框重叠）；名/重间隙按实际字形高度折算——`subtitleGapPx(nameH, 4dp, 10dp) =
     nameH*0.2 钳 [4,10]dp`，粗黑全方字形更高 → 气口更大，副标题不贴死名字底。
     单测 `FontPanelRowsTest.subtitleGapScalesWithGlyphHeightAndClamps`。
     ↳ **桌面目验追补（`fix/font-panel-review`，2026-09-23）**：一版按 `getRectsForRange
     (TIGHT)` 收紧间隙（0.25 钳 [6,12]dp）——但**它测不准**：326 族全量真栅格核出
     4 族墨迹真超出度量行高（Zapfino +32px、jpfont-nds +68px、SignPainter +3px、
     BM Hanna 11yrs Old +2px），且其中 2 族 skia rects 直接报等于行高（假阴性）——
     花体/手写/长尾字体墨迹远超字体度量，按度量行高画就会把字重副标题压穿。
     **修法改为栅格墨迹真值**：`FontPreviewText` 把要画的同一 paragraph 栅格到临时
     位图、逐行扫描（`inkBoxOfParagraph`，进程内按 族+展示名+字号 缓存），Canvas
     高度 = `max(度量行高, 真墨迹底)`——墨永不溢出名字行盒。新单测
     `FontInkMeasureTest`（已知溢墨族 ≥ 阈值 + 常规族不溢 + 确定性/缓存命中）。
     间隙仍 `nameH*0.25 钳 [6,12]dp` 由行模型驱动。
      ↳ **第二轮目验（2026-09-23）**：0.25/[6,12] 于高字形行（Zapfino 名行≈67dp → 收
      到 12dp）观感仍偏大 → 比率降 **0.15、钳 [6,8]dp**（常规行仍 6dp 兜底，高字形行
      只给到 8dp，名/重聚拢成一体；行盒 = 真墨迹，间距不缩放也不会叠墨）。
      ↳ **同展示名合族（2026-09-23）**：Source Han Sans/Serif VF+区域静态版（都落
      「思源黑体/宋体」，同字重面数并列时取 SC 先于 VF）此前各占一行、观感像字体重复
      → `buildFontRows` 改按展示名合并为一行，规范族 = 面数最多、并列取族名字典序小者，
      行首排规范族、槽位旧值（VF）仍能命中合并行。单测
      `FontPanelRowsTest.sourceHanVfMergesIntoRegionalStaticRow`。
      ↳ **Apple 老式族 Hei/Kai 修正（2026-09-23，用户 taxonomy 复核）**：Hei/Kai 是
      Apple 可下载的 Classic Mac 遗留字体（90 年代 Ikarus 简体字形，字体册默认需下载），
      **≠ Heiti SC（黑体-简）/ Kaiti SC（楷体-简）**，也非 Windows 拷贝——两套不同字面
      不能靠展示名合族。字典原区分名：Hei→苹果老黑体、Kai→苹果老楷体（**`fix/font-panel-review`
      起字典已删**，Hei/Kai 的 name 表仅存 Apple 远古 'Hei'/'Kai' 记录、无中文名 → 展示回退
      族名本身 "Hei"/"Kai"，用户已接受此口径）；同时明确中易
      （北京中易中标 ZhongYi）四兄弟：SimSun/NSimSun→新宋体、SimHei→黑体、SimKai/KaiTi→
      楷体、SimFang/FangSong→仿宋（Sim=简体，Sun=宋/Hei=黑/Kai=楷/Fang=仿）。
      单测 `FontPanelRowsTest.{appleLegacyHeiStaysDistinctFromHeitiSc,
      appleLegacyKaiStaysDistinctFromKaitiSc}` + `FontLibraryTest` 对应断言。
  3. **名称/字重溢出折行**：长族名（更纱终端书呆黑体-简、凌慧体-简）或多字重表
     （PingFang 6 项）会顶出/裁切在行末。待办：字体名行先不动；至少让字重行可折行
     （B0 层折行 + 行高），名称行另行评估。
     ✅ **已办（`feat/font-panel-remaining`）**：字重副标题去 `maxLines=1`（B0 层折行，
     `lineHeight=16sp` 与 12sp 字号匹配），行高 `IntrinsicSize.Min` 随折行撑开；名称行
     维持单行（名称行折行/裁切仍留待评估）。
  4. **安卓「跟随原书」放回列表首行**：`AndroidReaderSettingsPanel` 现把「跟随原书」做
     成独立选项行，与桌面版面不一致。待办：作为字体列表首行候选（选中态首行），
     数据源与桌面共用同一行形态。
     ⚠️ **旧“已办”结论错误（2026-09-24 目验推翻）**：此前记为过时条目（称 F4c 后已是
     列表首行），但平板上「跟随原书」仍被单独拎在面板顶部，不在字体列表内。待重查
     安卓入口装配（`AndroidReaderSettingsPanel.TextPage` 自持列表 vs 桌面行形态）。
     ✅ **已办（设置面板收敛，2026-09-24）**：`AndroidReaderSettingsPanel` 已删，平板改调共享 `ReaderSettingsPanel`，下钻与桌面同一 `buildFontRows`，首行恒为跟随原书；旧面板不复存在，无第二套列表可把跟随原书拎出。待真机复验一次确认。
  5. **桌面 PgDn/PgUp 翻页焦点不跟随（bug）**：字体列表翻页后焦点行原地不动，箭头
     导航仍从旧焦点行起步，与翻页所见错位。待办：翻页时同步重算焦点行（页首可见行/
     保持相对偏移），与 ScrollState 保持一致。
     ✅ **已办（`feat/font-panel-remaining`）**：滚动跟随——`snapshotFlow{isScrollInProgress}`
     只在滚动静止后评估：高亮（activeIdx）行已被滚出视口即重算到首个可见可焦点行
     （FollowOriginal/Import/Entry，跳过分区头），箭头导航与所见一致；键盘
     `ensureListVisible` 自滚落点行恒可见，不误改；安卓无键盘 activeIdx 恒 null，不动作。
  6. **「既然可查中文名，DB 还有何用」（记录）**：DB 仍是唯一权源——(a) 隐藏态/
     字重行/导入行是多端持久状态，本地化名只是 `displayName` 列的一层缓存；
     (b) 渲染期不逐次走 JNA 桥（CoreText 只在同步校验时刻枚举喂 `syncSystemFaces` 一次，
     面板查询全在内存）；(c) 平板/英文系统无 CoreText，展示层无本地化名回退族名本身，
     两端口径一致；(d) 取字形/槽位/逻辑键永远用 ASCII 族名，「展示名 vs 逻辑键」分离正是靠
     DB 列承载。方案B 已落地（JVM 端 name 表直读，见上），由链路上游填 `displayName`，
     DB 角色不变。
   7. **入口行仍显示英文原族名（2026-09-23 桌面目验，`fix/font-panel-review` 已办）**：
      文字页正文/标题/代码三行直接显示槽位原族名。`TextPage` 加 `fontDisplayByFamily`
      （`fontEntries` 预建族→展示名表，缺席回退族名本身 + CSS 通用族标签）。
      安卓入口（`AndroidReaderSettingsPanel.TextPage`）同症，但外层面无 entries
      （列表由 TextFont 子页自持），提升状态机改动面大，留待安卓对齐时一并做。
   8. **管理行右侧贴边（2026-09-23 桌面目验，`fix/font-panel-review` 已办）**：
      `FontManageRow` 内容区只有左 16dp，右侧用满贴窗边。内容区改
      `horizontal=16dp`，选中金点去尾 16dp（行内缩已给）。

- 根据用户选择内容，弹出选择器列表供选择，并允许用户指定css样式和属性

- 完善传统和现代样式主题，并允许用户使用自定义样式主题

- **标准化遗留 L1（待办：parsed-only 补消费）**：`visibility/overflow/position:relative/direction/unicode-bidi/font-stretch` 目前只落 `ComputedStyle` 计算值（`ComputedStyle.kt` / `StyleComputer.kt`），laying/draw 零消费。后续补祖先裁剪、相对偏移、RTL 流、字形压缩消费，双路一致 + bump `LAYOUT_VERSION`。
- **标准化遗留 L2（待办：§0 例外收口）**：`flex/grid` 真实布局（现按 `block` 降级）、`list-style-image/@page/vertical writing/cursor`、脚本/表单/音视频/`canvas/iframe/object-embed`、固定版式（pre-paginated）。后续收口时先修范围定义与验收矩阵，按需逐项立项。
- **标准化遗留 L3（待办：近似实现转精确）**：`background-size` 恒 1:1、`background-image` 只取首层、`counter-set`/非 `li` 的 `list-item` 生成内容不做、窄列悬浮退块式、latin 注音偏宽容差。后续按需逐项闭环。
- **状态栏按键焦点导航（桌面键盘，待办）**：状态栏打开时左右键不再翻页（`ReaderScreen` 已放行不消费，见代码 TODO），后续补栏按键之间的焦点切换（左/右移焦、回车触发、Esc 回阅读面），与面板内 `PanelNav` 口径对齐。

- **阅读目验问题清单（2026-09-24，待办；其中 1-3 已于 `fix/legacy-patches` 闭环）**：
  1. ✅ 打开目录时，应定位到当前阅读位置对应的目录项，同时作为桌面侧键盘移动的起始位置。
     （共享 `resolveTocCurrentRow`：当页标题 id 命中取文档序最末子标题、无命中回退章首；
     打开滚动到该行 + 桌面 `nav.activeIdx` 落该行；桌面侧补 `currentFragments` 接线，
     此前桌面一直空集只能定位章首。）
  2. ✅ 目录默认折叠，仅自动展开当前章的目录；用户展开新章目录时，其余所有章目录折叠。
     （共享 `initialTocCollapsed`/`toggleTocCollapsed`：每次打开重置为仅当前顶层子树展开，
     顶层展开手风琴、嵌套切换只管自己；"章"= depth 0 顶层节点。）
  3. ✅ 目录折叠三角形太小。（点击区 22dp→32dp、字号 11sp→14sp，字形 Box 居中；双端同改。）
  4. ✅ 平板侧「跟随原书」已随设置面板收敛闭环（单共享实现，首行恒为跟随原书；待真机复验）。
  5. ✅ 分区字样已删，改为行首来源竖标（2026-09-27）：导入/系统行按展示名归并单列，行首竖标“系统/导入/隐藏”；跨来源同名各占一行。
  6. ✅ 按最终显示的名字排序（2026-09-27）：展示名归并排序（两组各自有序归并）。
  7. ✅ 字重选择已落地（2026-09-27）：`WeightPicker` 按族选档（多字重行首入口进档位页）；连续滑块未做，如需再立项。
  8. 阅读主题中的缃色改掉：与象牙白接近，且不是真正的缃色 #F0C239（该色不适合阅读）。
  9. ✅ macOS 真背光已通（2026-09-27）：DDC/CI 下发，滑块 -50~100（>0 硬件，≤0 遮罩）；无 DDC 显示器钳 -50~0。
  10. 预设管理面板未完整实现。
   14. ✅ 已验证可行并落地（2026-09-27，见 9）：macOS 经 IOKit I2C 发 DDC/CI；Win（Dxva2）/Linux（ddcutil）空实现位见文首“桌面真背光后续”。
  11. 封面等比例缩放改为封面拉伸全屏。
  12. 两页内容不连续、翻页乱跳等问题。
  - 章内 TEMP 连翻跳页（2026-09-27 修，待合）：前向塑形从水位改以后继原点为准（`tempResumeAfter`），
    追加前相接校验（`tempPagesTile` ±3），失配按需修复收敛（`repairTempForwardGap`），窗口裁剪后水位
    倒回新尾。真机验证（`日志_20260927.txt` 02:32，`TempBurstFlipProbeTest`+全套 206 绿）：30 页连翻
    单调无跳，全文件零 `fwd-gap`/`fwd-repair`/`origin-drift`。
  - 跨章来回跳（✅ 已办，2026-09-27）：UI 侧 `AnchorFunnel` 已合入，引擎侧 in-flight 同目标去重已合入；显示状态就是
    `ReaderScreen.openPos` 一个变量，原先每次点按起独立协程调 `host.adjacent`，回来**不分新旧无条件
    覆盖**（旧 `ReaderScreen.kt:177`），谁后完成谁赢。边界处连点 → N 个跨章落地并发（各 ~1s）按完成
    顺序贴 UI，旧目标覆盖新位置。
    证据：11.411 七个 `landed ch=16` 扎堆，11.809 三个过期 `landed ch=15` 把显示拽回，
    11.968/12.209/12.937 无点按自动跳。附带真 bug：每次点按按发出瞬间的旧页算源，慢翻页时
    10 次点按全从同一页出发（全落同一页），不是 10→11→…→20。
    立规矩：`openPos` 只接受锚页事件（翻页/跳转/开书/改参/旋转/外部落位/开链接），过期结果是已死的
    锚页事件，无权覆盖。
    落地情况：`AnchorFunnel`（shared-ui 用户层）——6 个写入点收口为唯一通道，
    规矩只有一条：try-lock，锁占用期间后到的点按直接放弃（`BUSY-DROP` 落盘可查），不排队、不等帧、
    不计时——这次实测锁占了约 1s（落地构建实际耗时），没有任何时间阈值；
    解锁后的新动作按最新位置重取源，10 次连点即 10→11→…→20 的链式推进。代价写在明处：锁占用期间
    的点按按了白按。`AnchorFunnelTest` 6 绿、shared-ui 全套 76 绿、app 编译过。
    引擎侧同目标在途去重已合入（重复构建不再互相覆盖）。
  - 存档 500ms 批处理（2026-09-27 记，**待复核**）：`markPositionChanged` 每次提交取消旧 `saveJob`、
    500ms 后写一次 Room（复刻 legacy scheduleSave）。连翻只存落定页，显示层零影响；漏斗落地后存的
    必是显示过的位置。复核点：500ms 值是否合适、杀进程丢进度窗口、与跳转/旋转等重排路径的竞态。
  13. 目录跳转偏差已修（2026-09-27：桌面透传 fragment + 引擎排版字符流锚点）；点击无响应（失败静默吞）待办。
