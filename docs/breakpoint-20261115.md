# 断点 2026-11-15 · 3D 卷曲（feat/curl3d）

## 一句话现状

平板上一翻页**完全没有动画**：卷曲层的两张整页快照在真机上截出来是**空图**，30 帧都拿不到，
卷曲层于是整帧不画（这是当时的保护：截不到图就不画，不黑屏）。滑动档不受影响。

## 真机证据（不用再猜）

平板日志 `Orilumn.Curl` 全是：

```
W 01:51:36.270 [Orilumn.Curl] shot GIVE-UP tries=30 current=null target=null
```

即 `GraphicsLayer.toImageBitmap()` 这条路在这台机器上拿不到非空图（30 帧 × 多次翻页全空）。
滑动档正常，恰恰证明不了它——滑动层从不过截图，页面本身是页栅格。

## 这一轮已经改好的（未提交）

1. **倾斜接上了**：手势位移的上下分量进了状态机（`curlTilt`），以前折痕永远竖着。
2. **向前翻的露出方向修正**：法向改为恒指纸的右缘（与旧版 `CurlView.drawBody` 同布局），
   两个方向只换「折痕扫向」和「两张图谁是谁」。以前向前翻时纸卷落在折痕右侧、露出页跑到左边。
   冒烟测试同步改过，4 条全绿。
3. 快照改成「截到非空图才算数」+ 逐帧重试 + 诊断日志（就是上面那条 GIVE-UP 的来源）。

未提交：`curl/` 三端目录、`ReaderScreen.kt`、`FlipController.kt`、`jvmTest/.../curl/`。

## 下一刀（方向已拍板，直接做）

**页码 / 旁注烤进页栅格，卷曲直接吃页栅格，彻底不用 GraphicsLayer。**

落法（明天照这个走，别重新论证）：

1. **先把页栅格扩到整屏**。这是关键前置，容易踩：现在 `ReaderPageCanvas` 传给渲染器的
   `contentRect*` 是**版心**（左右上下的页边距都在栅格外），而页码画在下边距、旁注画在左右留白，
   **两者都在栅格外**，所以不扩栅格的话「烤进去」烤不到。
   时扩法是把 `contentRectLeft/Top/Right/Bottom` 传成 `0, 0, pxWidth, pxHeight`；
   `drawPageContent` 里的 `xOff = contentLeft - contentRectLeft`、`yOff = contentRectTop`
   会自动把行/图摆回原来的绝对位置（帧坐标本来就是屏幕坐标），不用改换算。
   代价：整页位图变大（多出四条边距），栅格键变一次，缓存整体失效一次。
2. 栅格绘制侧加两笔（`drawPageContent` 之后）：旁注、页码。旁注的绘制要照 `NoteBubbles` 的
   视觉 1:1 搬（圆角框 3dp、底色 16%、描边 50%、字号/行高/左右内缩、小椭圆三点、引线箭头）；
   文字用 skia 段落（`maxLines` + `ellipsis`），字体由 `noteFontFamilyOf` 拆出的
   （族名 / 文件路径 / 字重）解析：有路径 `Typeface.makeFromFile`，否则系统匹配。
   页码是单行右对齐，落在下边距带里居中（算法见 `PageNumberLabel` 的注释）。
3. 槽里那两个覆盖层改成**不可见的命中层**（旁注还要能点开），像素由栅格出。
4. 纹理交接：`ReaderPageRenderer.drawLines` 返回刚画出的 `ImageBitmap`，
   `ReaderPageCanvas` 用**普通 holder**（不是 Compose 状态）往外发；卷曲层在同一个 draw 相位
   排在两个槽之后读它 → 天然是「刚画好的整页」。两张图就绪才画，缺图时页面不动（等图）。
5. 删掉卷曲层里的 `rememberGraphicsLayer` / `toImageBitmap` / 截空图重试那一整套。

## 省时间的既有事实

- 平板：`adb -s adb-400D91019H00000-rg2R7j._adb-tls-connect._tcp`（API 34，1840×2800）；
  启动 `monkey -p orilumn.reader -c android.intent.category.LAUNCHER 1`。
- 定论只看落盘：`run-as orilumn.reader cat files/logs/日志_YYYYMMDD.txt`，配 `grep Orilumn.Curl`。
- 卷曲几何/着色器已在 `curl/CurlFrame.kt`（折痕、倾斜、uniform 打包）与 `curl/CurlShader.kt`
  （唯一一份 SkSL，桌面/Android 共用），两端后端 `CurlDraw.jvm.kt` / `CurlDraw.android.kt`。
- **有现成的离线自查手段**：`shared-ui/src/jvmTest/.../curl/` 里的冒烟测试能直接渲染 PNG
  （`build/curl-*.png`），画完用图像查看器确认，不用装到平板上看。
  旁注/页码的 skia 绘制也照这个办法先出图对齐，再上机。
- 手感权威：旧版 `orilum.bak/app/src/main/java/com/orilum/ui/reader/curl/CurlView.kt`
  （折痕 `w*(1-p)` / `w*p`、倾斜 0.9 页宽、阴影带 5% 页宽、纸背 100/255）。

## 明天第一步

先把栅格扩到整屏 + 旁注/页码画进去，用 jvmTest 出一张 PNG 对齐视觉；
再换卷曲纹理来源；最后上机看一次。
