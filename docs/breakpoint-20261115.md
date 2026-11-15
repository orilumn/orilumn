# 断点 2026-11-15 · 3D 卷曲（feat/curl3d）

## 一句话现状

卷曲改成了「**页栅格 + 覆盖层栅格，两张图各过一遍同一个卷曲着色器**」，纹理就是页槽
自己画到屏幕上的那两张图，不再有任何截图环节。代码已合入 `feat/curl3d`，桌面离线出图
自查通过，**等平板实测手感**（只有平板能看出来弯得对不对）。

## 真机证据（历史，不用再猜）

上一版走 `GraphicsLayer` 整页快照，平板日志 `Orilumn.Curl` 全是：

```
W 01:51:36.270 [Orilumn.Curl] shot GIVE-UP tries=30 current=null target=null
```

即 `GraphicsLayer.toImageBitmap()` 在这台机器上拿不到非空图（30 帧 × 多次翻页全空），
卷曲层于是整帧不画。滑动档正常——它从不过截图。**这条路已整体废弃**。

## 现在的架构（已实现）

1. **页栅格**（`PageRaster.kt`）不变：只装正文/插图/盒背景，版心大小，按页身份键复用、
   可后台预栅格。
2. **覆盖层栅格**（`PageOverlay.kt`）：旁注 + 页码，**整页大小、透明底**，按
   「旁注 + 页码 + 样式 + 尺寸 + 密度」键缓存（`PageOverlayLayer`）。
   为什么单独一层而不是烤进页栅格：批注布点异步且随字号变，烤进页栅格会让页缓存反复作废，
   后台预栅格也永远打不中（那边拿不到布点结果，那是一台 Compose 尺子）。
   绘制用 Compose 的 `DrawScope` + **同一台 `TextMeasurer`**（量与画同源），
   旧 `NoteBubbles` 的视觉逐项搬过来。
3. **交互只剩热区**：`NoteBubbleHitAreas`（透明 Box + clickable），几何与视觉共用同一份
   `NoteBubble`，不会「看得见点不着」。
4. **纹理交接**：`ReaderPageRenderer.drawLines` 返回刚画出的页栅格 `ImageBitmap`，
   `ReaderPageCanvas` 同一帧把「页栅格 + 覆盖层」经 `onImages` 发布到 `CurlPageImages`
   （Compose 状态，图晚到会自己重画一帧）。卷曲层排在两个槽之后读它 ⇒ 天然是刚画好的那张。
5. **着色器两遍**：`u_page.z = 1` 是覆盖层那一遍，纸背区返回透明（旁注印在纸正面，
   纸背上不该有），于是旁注/页码跟着纸一起弯。
6. **截面照 duokan 里的 harism page curl**（`samples/duokan/sources/fi/harism/curl/`）：
   纸是刚性的，折痕处一个半径 R 的半圆柱纸卷，越过卷顶再平铺。纸卷在**纸料那一侧**
   （露出页侧），平铺段在另一侧；平铺段 1:1 刚性镜像，纸背明暗 `0.5+0.5·|nz|`。
   阴影两道：纸卷投在露出页上的落影、平铺段压在正面页上的接触影（纸背上不画）。
   纸的**范围**按页矩形裁：每个像素算出材质点，落在页矩形里才有纸 ⇒ 纸背外缘是页边镜像、
   折痕两端是纸自己的角（与 harism 拿整块页矩形进网格、再用扫描线裁是同一件事，
   与旧 2D 版把露出区多边形整体镜像同形状）。不要改回「折痕到页边的距离」画等宽带子：
   逐像素算会在两端张出楔形，整帧取一个值又会把纸背切成直条裁断。
7. **纹理坐标 1:1**：`u_rect` 给纹理在页坐标里的位置与尺寸（页栅格是版心、覆盖层是整页），
   片元自己减原点；两端**都不挂 local matrix**——桌面 skia 与安卓 `BitmapShader` 的
   local matrix 方向天生相反，挂错不报错只画错。
8. 缺图时**页面保持不动**（不切滑动、不冻帧）；`Orilumn.Curl` 每次翻页留一条
   `draw dir=… p=… fold=(x,y)-(x,y) n=(x,y) shadow=… tex=… overlay=…` 或 `wait page …`，
   上机对账、离线复现同一帧都用它（`CurlDebugTest` 里已有几个按实测几何写死的用例）。
9. **卷筒长到纸边为止（判据落在卷筒轮廓那一圈，不是折痕那一圈）**：折痕是这一页的折痕，
   折痕端点附近纸料不足一个圆柱，纸卷必须停在纸边——折页页角处。判「有没有纸」要看
   **材质 u = πR/2（屏幕 s = R）那一圈**，不能看 `paperAt(foot + n·arc)`（u = arc 是卷过顶
   之后往回走的位置，两者差大半个弧长）：折痕端点附近正好卡在两者之间，轮廓那一圈还有纸、
   u = arc 已经出页，按 u = arc 判会把整条卷筒误判成「没纸」，露出页就从缺口里透出来——
   实测报上来的「两个锐角端各漏一个直角三角形」就是这两块缺口。
   卷筒本身不另设门槛：上半段（纸背）与下半段（卷面朝上的正面）各自用自己的材质点
   `paperAt` 判纸在不在，谁在谁上屏；屏幕投影宽度按「纸沿 n 还能走多远」直接算出
   `sOut = R·sin(min(纸长, πR/2)/R)`，纸不够就跟着收窄。
10. **落影按纸面本体截断（照 harism `CurlMesh`）**：落影/自身影的顶点就是纸面网格顶点
    复制一份、只沿法向偏移，偏移量 `0.8R·sqrt(smoothstep(z/R))`——卷边越矮影越窄，
    折痕两端 z → 0、影宽也 → 0，所以端头是**跟着卷筒收尖**的，不是一刀切出的方角。
    影的内缘永远等于卷筒轮廓 `sOut`（纸到哪里影到哪里）。**不补**「折痕到页矩形距离」
    当带宽（那是一条平行于折痕、一路拉到页边的灰带，就是实测报上来的「折痕两头各一大块
    阴影」），**也不做**到端角的二维距离圆斑（端头会张出一块圆形阴影）。

## 离线自查（不用装平板）

```bash
./gradlew :shared-ui:jvmTest --tests "orilumn.reader.ui.reader.curl.*" \
                             --tests "orilumn.reader.ui.reader.PageOverlayPreviewTest"
```

- `build/overlay-preview.png`：旁注框/引线/小图标/页码的视觉；
- `build/curl-composite.png`：两遍合成的整帧（正面页有旁注、纸背没有、露出页是目标页的）。

## 上机第一件事

1. 设置面板把「翻页动画模式」切到**卷曲**（外部改 `reader.json` 不生效）。
2. 前后各翻几页，看：纸面有没有弧度、纸背是不是镜像内容、旁注/页码有没有跟着弯、
   跟手是否顺、松手落位有没有跳。
3. 卡住就看落盘日志：

```bash
adb -s <addr> shell "run-as orilumn.reader cat files/logs/日志_YYYYMMDD.txt" | grep Orilumn.Curl
```

## 省时间的既有事实

- 平板：`adb -s adb-400D91019H00000-rg2R7j._adb-tls-connect._tcp`（API 34，1840×2800）；
  端口会变，先 `dns-sd -L "adb-400D91019H00000-rg2R7j" _adb-tls-connect._tcp local`，
  再 `adb connect 172.16.0.203:<端口>`；启动 `monkey -p orilumn.reader -c android.intent.category.LAUNCHER 1`。
- 手感权威：旧版 `orilum.bak/app/src/main/java/com/orilum/ui/reader/curl/CurlView.kt`
  （折痕 `w*(1-p)` / `w*p`、倾斜 0.9 页宽、纸背 100/255）；截面与阴影参考
  `samples/duokan/sources/fi/harism/curl/`（`CurlMesh.curl` 的圆柱映射与两道阴影）。
- 页位图池 100MB（版心一张约 15MB）：卷曲跨帧持有当前页与目标页两张，池紧张时有被
  淘汰回收的风险（回收后 `asAndroidBitmap` 抛错 ⇒ 当帧画不出卷曲，页面不动）。

---

## 2026-11-15 转向：3D 卷曲整条废掉，改回 2D 折页

**结论**：`docs/翻页动画设计.md` §15 的逐像素 3D 卷曲**已废弃**，现行实现是 §16 的折页
（多边形裁切 + 镜像 + 渐变影）。本节只记「为什么转」，细节看 §16。

### 症状（真机，非推断）

1. 卷筒漏到正面页上；
2. 卷筒被纸深压扁（想改「按纸深算粗细」更糟：卷筒几乎看不见）；
3. 松手末段跳变。

### 根因

圆柱投影**不是单射**：纸卷近侧、远侧、纸背平铺段投在同一条屏幕带上，着色器只有「到折痕的
带符号距离」可用，没法判断某个像素属于哪个面。这是坐标系选错了，不是参数没调好——
继续在上头加约束只会越描越歪。

### 转向后的做法

照旧版 `orilum.bak` 的 `CurlView.drawBody` 抄：三块区域是屏幕空间多边形，边界就是折痕与页边。
顺带删掉整条平台缝（`CurlDraw` 的 expect/actual、SkSL、`curlFlipSupported`）——
折页是纯 Compose 画布，全平台同一份代码，设置里也不再隐藏「卷曲」。

保留的资产：页栅格 + 覆盖层的发布通路（`CurlPageImages`、`PageOverlay`、`ReaderPageCanvas.onImages`）。

### 已验（本轮）

- `./gradlew :shared-ui:jvmTest`：607 条全绿（新折页 18 条：几何 10 + 渲染 7 + 出图 1）；
- `:app:assembleDebug` 装机（`shasum -a 256` 确认新包），两个方向各拖一次、松手提交/回退各一次，
  截图为证；落盘日志 `Orilumn.Curl draw dir=… p=… fold=(…)‑(…) lean=… fade=…`。

### 上机复核要点（改了折页之后）

1. 滑/卷两档切换：切到卷曲后前后各翻几页，折痕有斜度、纸背是镜像、露出页从另一侧露出来；
2. 慢拖到 90% 再松手：末段折痕扶正、影淡出，撤层不跳；
3. 反方向拖回（回退）：折痕收回、纸背缩回右缘；
4. 日志里 `fade` 在结算头尾应当降到 0，`lean` 跟着变小。
