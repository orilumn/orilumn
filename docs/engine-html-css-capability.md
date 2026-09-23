# Engine HTML / CSS Capability Audit (vs EPUB 2 & EPUB 3)

**Status:** post-P4 re-audit. P0 (parse/tags/attrs), P1 (style computation), P2 (fonts/stylesheets),
P3 (draw/generated content) and P4 (float/ruby/link-anchor) have all landed; this file now records
the **shipped** behavior. Authoritative delivery record: `docs/roadmap-epub2-3-full-support.md`
(§2/§3/§4 matrices, all green). Previous baseline (commit 734ad47) is superseded.

Legend: same as before — **epub2** = EPUB 2.0.1 (OPS) reliance; **epub3** = EPUB 3.x reliance;
**Parse** = survives `HtmlTreeConverter` as a semantic node; **Render/effect** = layout/draw behavior.
`—` = not specified / not applicable. Explicit non-goals live in the roadmap §0.

## 1. HTML elements

Zero de-shell for EPUB2/3 tags (P0-A; de-shell remains only for `script`/`style`/`head`-class
non-flow content). `display:none` hides any subtree; CSS `display:block` (+`list-item`/`flex`/`grid`/
`table*` block-ness) promotes inline elements to blocks (P1-A, dual-path locked).

| Element | epub2 | epub3 | Our parse | Our render/effect |
|---|---|---|---|---|
| `p` `div` `h1–h6` `blockquote` `pre` `section` `article` `aside` `header` `footer` `nav` `main` `hgroup` `details` `summary` | p/div/h/NQ | 全 | 块 | box 块（margin/行距/heading 缩放） |
| `ul` `ol` `li` | ✓ | ✓ | 块 | box 块 + 悬挂 marker（disc/circle/square；decimal/roman/alpha；`start/reversed/type`；`list-style` 简写） |
| `dl` `dt` `dd` | ✓ | ✓ | 块 | box 块（UA dd 缩进） |
| `address` `hr` | ✓ | ✓ | 块 | box 块 |
| `table` `thead` `tbody` `tfoot` `tr` `td` `th` `caption` `colgroup` `col` | ✓ | ✓ | 保留结构 | 2D 网格 + `colspan/rowspan` + 表格族 CSS（`border-collapse/spacing`、`caption-side`、`empty-cells`、`table-layout`）；`col` 仅列元数据 |
| `strong` `b` `em` `i` `u` `a` `code` `kbd` `samp` `tt` `span` `sub` `sup` `q` `small` `big` | 全 | 全 | 行内 | 行内 run（粗/斜/下划线/等宽/上下标字号+基线偏移/small·big 缩放）；`q` 生成引号；`a` 保留 `href`/`name` + 双端点按导航 |
| `s` `del` `ins` `abbr` `dfn` `cite` `var` `acronym` `mark` `time` `data` `bdi` `bdo` `wbr` | ✓ | ✓ | 行内 | 行内 span + UA 兜底（删除线/下划线/斜体/mark 高亮；`title` 保留；`wbr` 断行点） |
| `ruby` `rt` `rp` `rbc` `rtc` `rb` | ✓ | ✓ | 行内 | 叠排：`rp{display:none}`，`rbc/rtc/rb` 透明行内容器，`rt` 0.6em 居中压基字上方（行高预留注音高；内联源文透明占位，字符流不变） |
| `br` | ✓ | ✓ | 保留 | 换行（`clear` 属性进级联，块级 clear 越过悬浮） |
| `img` | ✓ | ✓ | 保留 | 可替换块（`width/height/srcset/sizes`；`align` → float；`float` 悬浮 + 紧随文本叶环绕，双路一致） |
| `figure` `figcaption` | EPUB2 无 | ✓ | 块 | box 块（独图提升为替换叶，不丢插图） |

## 2. HTML attributes

All retained (§3.1 + P0-B); HTML4 presentation attrs enter the cascade at low tier (15),
below author CSS (§3.2, all delivered).

| Attribute | Applies to | epub2 | epub3 | Our parse/use |
|---|---|---|---|---|
| `id` `class` `style` | 全局 | ✓ | ✓ | 保留；CSS 匹配/内联样式；`id` + `a name` 同为锚点 |
| `lang`/`xml:lang` `dir` | 全局 | ✓ | ✓ | 保留（`dir` 未消费，RTL 里程碑） |
| `epub:type` `role` `aria-*` `data-*` | 全局 | — | ✓ | 保留（结构语义） |
| `title` `cite` `datetime` `scope` `headers` `summary` | 各元素 | ✓ | ✓ | 保留（暂不渲染） |
| `align` | 块/表格 | ✓ | 弃 | → `text-align`（`img align` 另 → `float`） |
| `href` / `name` | `a` | ✓ | ✓ | 保留；点按导航闭环（页内锚 + 跨章） |
| `src` `alt` `width` `height` `srcset` `sizes` `border`(img) | `img` | ✓ | ✓ | width/height→布局；src→位图/背景复用；alt 备用 |
| `colspan` `rowspan` | `td/th` | ✓ | ✓ | 2D 表格网格 |
| `start` `reversed` `type` / `value`(li) | `ol`/`li` | ✓ | ✓ | 列表编号 |
| `border` `cellpadding` `cellspacing` | `table` | ✓ | 弃 | → `border`/`padding`/`border-spacing` |
| `bgcolor` / `background` | 块/表格 | ✓ | 弃 | → `background-color` / 低层级 `background-image` |
| `valign` / `nowrap` | `td/th` | ✓ | 弃 | → `vertical-align` / `white-space: nowrap` |
| `clear` | `br` | ✓ | 弃 | → `clear`（块级越过悬浮） |

## 3. CSS properties

Computed layer holds every property below with old-behavior defaults (§6 inv.3); consumption
points are noted. "parsed only" = computed, draw/layout consumption pending or out of scope.

| Property | epub2 | epub3 | Our: parsed? | Our: effect? |
|---|---|---|---|---|
| `font-size`（px/em/rem/%/unitless） | ✓ | ✓ | yes | **yes**（行内各自字号） |
| `font-family`（含栈）`font-weight` `font-style` | ✓ | ✓ | yes | **yes**（回退 + monospace） |
| `font-variant` (`small-caps`) / `font-stretch` | — | ✓ | yes | small-caps 合成 **yes**；stretch 计算值 |
| `line-height` | ✓ | ✓ | yes | **yes** |
| `color` / `background-color` | ✓ | ✓ | yes | **yes**（主题继承 + 显式 run） |
| `text-decoration` | ✓ | ✓ | yes | **yes**（underline/line-through） |
| `text-indent` / `text-align` | ✓ | ✓ | yes | **yes**（含 JUSTIFY） |
| `letter-spacing`（作者） / `word-spacing` | ✓ | ✓ | yes | **yes** |
| `text-transform` | ✓ | ✓ | yes | **yes**（字符流级，大小写 1:1） |
| `white-space` | ✓ | ✓ | yes | **yes**（断行单源 + 制表符展开） |
| `vertical-align`（sub/super/middle/top/bottom） | ✓ | ✓ | yes | **yes**（行内基线偏移 run） |
| `overflow-wrap` / `word-break` | — | ✓ | yes | **yes**（断行） |
| `margin`/`padding`（含逻辑属性） | ✓ | ✓ | yes | **yes**（盒几何 + collapse） |
| `border-width/style/color`（四边 + currentColor）/`border-radius` | ✓ | ✓ | yes | **yes**（几何 + 分段/圆角绘制） |
| `box-shadow` / `text-shadow` / `text-emphasis`（-epub- 前缀）/ `opacity` | — | ✓ | yes | **yes**（绘制层） |
| `background`（色）/`background-image`+`repeat`+`position` | ✓ | ✓ | yes | **yes**（平铺几何 + 双端绘制；无 `background-size`） |
| `display`（block/none/list-item/table*） | ✓ | ✓ | yes | **yes**（块判定 + 整子树隐藏） |
| `width`/`height`/`max-*`/`min-*`（px 与 % 分离）/`box-sizing` | ✓ | ✓ | yes | **yes**（布局期解 %） |
| `list-style-type`/`position` + `list-style` 简写 | ✓ | ✓ | yes | **yes** |
| `break-inside/after/before` + `page-break-*` 别名 | ✓ | ✓ | yes | **yes**（BreakRule） |
| `border-collapse`/`border-spacing`/`caption-side`/`empty-cells`/`table-layout` | ✓ | ✓ | yes | **yes**（2D 消费） |
| `content`/`counter-reset`/`counter-increment`/`quotes` + `::before/::after` | — | ✓ | yes | **yes**（字符流级；无 `counter-set`） |
| `@font-face`（ttf/otf/woff/woff2 + IDPF 去混淆）/`@import`/`@media`（全视口） | — | ✓ | yes | **yes**（先行入池，双端） |
| `float` / `clear` | ✓ | ✓ | yes | **yes**：文本/img 悬浮 + 双侧跨度并排 + 跨容器环绕 + 真 clear + 窗口 carry-in + 右悬浮绘制（双路一致） |
| `visibility` | ✓ | ✓ | yes | parsed only（隐藏消费后续） |
| `overflow`（祖先裁剪） | ✓ | ✓ | yes | parsed only |
| `position`（relative 标记） | ✓ | ✓ | yes | parsed only（absolute/fixed 不做） |
| `direction`/`unicode-bidi` | ✓ | ✓ | yes | 隔离 parsed only（RTL 流后续） |
| `flex`/`grid` 真实布局 | — | ✓ | n/a | NO（按 block 降级，§0 例外） |
| `list-style-image` / `@page` / 垂直书写 / 脚本媒体表单 | ✓ | ✓ | n/a | NO（§0 例外） |

## 4. UA baseline (`common/.../resources/css/ua.css`, frozen by P5-a)

Shipped defaults beyond raw browser UA (paginated-reader extensions): `ul/ol` 2em
padding-left（分页裁切沟槽补偿）, `img{max-width:100%}`（无横滚约束）, `q::before/after`
引号补丁, `sub/sup` 字号+位移, `rp{display:none}` + `rt` 0.6em（叠排字号/行高同源）+ `rbc/rtc/rb/ruby` 透明行内, 标题/代码/强调兜底.
`__LINK_COLOR__` is the only theme token, substituted per profile at load.

## 5. Known v1 limitations (accepted, healing paths documented)

1. 悬浮：窄列退回块式（保守取空隙，不重叠）。
2. 注音：latin 注音按 0.6em/char 居中，偏宽 ≤0.4em/char 为已文档化容差；水平溢出允许（与浏览器同式，不挪邻字）；断行宽按内联全文保守预留（只宽不窄）。
3. 链接：点按命中经字形反查（tight 字形级）；链接优先于三区，跳转后 500ms 三区防抖（手抖连点不翻走）；真机已验（internallinks 注脚跨章跳转，2026-09-21）。
4. 其余见 §3 表 parsed-only 行与 §0 例外清单。

_This inventory is now the shipped record; each row above is covered by unit/probe tests
except the device-pending items in §5 (P5-c)._
