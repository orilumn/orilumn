package orilumn.reader.ui.reader.curl

/**
 * 卷曲片元着色器（**唯一定义处**，桌面 SkSL 与 Android AGSL 共用同一份源码）。
 *
 * 这是「逐像素逆向映射」：片元不移动几何，而是反查「这个屏幕像素来自纸面的哪一点」。
 * 好处是曲面上没有接缝、明暗是连续的，也不需要在别处再写一份多边形切片。
 *
 * ## 与平台无关的约定
 *
 * - 子着色器按**纹理像素坐标**采样（1:1，两端都不挂 local matrix）：`eval(q - u_rect.xy)`，
 *   于是「桌面 skia 的 localMatrix 方向」与「Android BitmapShader 的 localMatrix 方向」
 *   这对天生相反的约定根本不用碰——挂错矩阵不会编译失败，只会把整页采成左上角一个像素。
 * - uniform 全是 `float4`，顺序 = [CurlFrame.uniforms] 的打包顺序（见那边的注释）。
 * - 纹理不一定铺满整页：`u_rect` 给出它在页坐标里的位置与尺寸，片元自己换算 uv。
 *   页栅格是**版心**大小（外边距是屏幕底色），覆盖层栅格是整页大小——同一份着色器
 *   靠这一个矩形同时伺候两种纹理，不用为「铺满/不铺满」写两条管线。
 * - `u_page.z = 1` 表示这是**覆盖层那一遍**（旁注 + 页码）：只画纸面上看得见的那两块
 *   （正面页 / 露出页 / 卷面朝上的正面），纸背一律返回透明，让底下的页栅格透出来。
 *
 * ## 几何（照 duokan 里 harism page curl 的三维模型 + 旧 2D 版 `CurlView.drawBody` 的裁切）
 *
 * 一张**刚性**纸绕折痕卷过去：折痕处是半径 R 的半圆柱（纸卷），越过圆柱顶之后再平铺。
 * 纸卷落在**纸料那一侧**（也就是露出页这一侧），平铺段落在**另一侧**——这是实物翻页
 * 唯一自洽的摆法（纸从右半边掀起来，绕过折痕，落到左半边）。
 *
 * ```
 *   s = 片段到折痕的带符号距离（沿 n，n 指向露出页）
 *   R = 纸卷半径（0.10 页宽，且不超过 0.30·D）
 *   arc = πR = 纸卷吃掉的弧长
 *
 *   s ≥ 0   露出页侧：  [0, sOuter) 纸卷（卷面朝上的那一面），之后是露出页 + 落影
 *   s < 0   另一侧：    纸背平铺段，再往外是还没掀起的正面页
 * ```
 *
 * ## 纸的范围 = 页面矩形本身（**不要再用「纸料宽度」画带子**）
 *
 * 纸就是那一页：材质点落在页矩形里就有纸，落在外面就是「纸到头了」。
 * 每个屏幕像素都把自己的材质点算出来，再用页矩形判一次，于是
 *
 * - 纸背平铺段的**外缘就是那一页自己的边**（镜像过去），折痕两端自然收成纸的四个角，
 *   不需要另算「折痕到页边的垂直距离」这种逐像素量；旧版两处实现都是这么做的：
 *   harism 把整块页矩形摆进网格里再用扫描线裁，旧 2D 版把「露出区多边形」按折痕镜像。
 * - 逐像素算纸料宽度会在折痕两端张出楔形（实测报上来的「上下多余的折角」——折痕靠近
 *   页边那一段垂直方向与页边近乎平行，宽度在 100px 内从 400 掉到 1）。
 *   整帧取一个宽度又会把纸背切成直角裁断的等宽直条（第二次实测报上来的就是这形状）。
 *   按页矩形判存在，两种毛病都不存在：纸背的远边是**页边镜像**（斜的），两端是**页角**。
 * - D（折痕中点沿 n 到页边的距离，整帧一个值）只用来定纸卷半径，不再参与「纸有多宽」。
 *
 * 三件必须分开算的事：
 *
 * 1. **平铺段 1:1**：材质距离 `= arc + t`（t = 屏幕位移），映射是刚性的（镜像 + 平移），
 *    所以屏幕位移与材质位移 1:1。旧版把剩余纸料压进屏幕带宽（斜率 (D-arc)/(D-B) < 1），
 *    纸背被放大约 1.45 倍——这是必须修掉的错。
 * 2. **纸卷是投影**：材质 u 与屏幕位移的关系是 `t = R·sin(u/R)`，反查 `u = R·asin(t/R)`。
 *    半圆柱的上半段（u > πR/2）绕回来盖住下半段，所以能看见的是**纸背**：
 *    `u = πR - R·asin(t/R)`；纸料不够一个圆柱、上半段不存在时，露出来的是**卷面朝上的
 *    正面**（内容照常，只是被压扁）——所以那里覆盖层要照画。纸卷被纸边从中间截断时，
 *    上半段所在的那一块纸也没了，同样退回正面（再没有就露出页）。
 * 3. **明暗**：纸背 `rgb *= 0.5 + 0.5·|nz|`（harism 同款，不用光源模型）：
 *    卷顶朝上最亮，纸卷最外沿竖直时最暗（0.5），平铺段恒 1。
 *
 * 阴影两道（照 harism：都是「贴着接触线、线性淡出」的软带，不是高斯带）：
 * - 落影：纸卷投在露出页上，从纸卷外沿起、宽 0.8R，最深 0.45；
 * - 接触影：纸边压在正面页上的那一条，从纸边起、宽 0.35R，最深 0.32
 *   （harism 没有这一道，但我们的平铺段在正面页这侧，不补纸边就像贴上去的）。
 *   宽度用「材质点到页矩形的距离」量——平铺段的映射是刚性的，屏幕距离与材质距离相等，
 *   于是阴影自动贴着纸背的真实外缘（含纸角）走，而不是贴着折痕走。
 *
 * 纸背平铺段**不画**自身阴影：实测三道带子在屏幕上并排（正面页一条、纸背一条、
 * 露出页一条），中间那条把纸背压出一道莫名其妙的灰杠，删掉。
 */
internal const val CURL_SKSL: String = """
uniform float4 u_page;    // xy = 页宽/页高(px), z = 1 表示覆盖层那一遍（纸背透明）
uniform float4 u_rect;    // xy = 纹理左上角在页坐标里的位置, zw = 纹理尺寸(px)
uniform float4 u_geom1;   // xy = 折痕上端点, zw = 折痕方向单位向量
uniform float4 u_geom2;   // xy = 法向单位向量(指向露出页), z = 阴影强度
uniform float4 u_paper;   // rgb = 纸背底色
uniform shader u_front;   // 被翻的那一页（正面纹理）
uniform shader u_back;    // 露出的那一页

const float PI = 3.14159265;

// 页坐标 → 纹理像素坐标（纹理 1:1 落位，不挂 local matrix，见文件头）。
float2 texOf(float2 q) {
    return q - u_rect.xy;
}

// 落在纹理外：版心外是屏幕边距，屏幕底色 = 纸色，用纸色兜住（否则 CLAMP 会把
// 版心边缘那一列像素拉成一条竖带）。
bool outsideTex(float2 q) {
    return q.x < 0.0 || q.y < 0.0 || q.x > u_rect.z || q.y > u_rect.w;
}

// 这一页的纸还在不在：材质点落在页矩形里（含边距）就有纸。
// 注意用的是页矩形而不是纹理矩形——纹理是版心，纸包含四周留白。
bool paperAt(float2 q, float2 page) {
    return q.x >= 0.0 && q.y >= 0.0 && q.x <= page.x && q.y <= page.y;
}

// 到页矩形的距离（矩形内部为 0）：接触影的宽度就按它量。
float distOutside(float2 q, float2 page) {
    float2 d = max(max(-q, q - page), float2(0.0, 0.0));
    return max(d.x, d.y);
}

half4 main(float2 p) {
    float2 page = u_page.xy;
    bool overlay = u_page.z > 0.5;
    if (p.x < 0.0 || p.y < 0.0 || p.x > page.x || p.y > page.y) {
        return half4(0.0);
    }
    float2 top = u_geom1.xy;
    float2 crDir = u_geom1.zw;
    float2 nrm = u_geom2.xy;
    float shadow = u_geom2.z;

    // s = 到折痕的带符号距离（沿 n）；foot = 折痕上与之同一点（沿折痕方向坐标相同）。
    float s = dot(p - top, nrm);
    float2 foot = p - nrm * s;

    // 纸料宽度参照值 D：整帧只算一次（取折痕与页面中线的交点），只用来定纸卷半径。
    // 折痕竖着（常态）就按中线 y 取，几乎横着时改看中线 x，免得除以接近 0 的分量。
    float xiMid = abs(crDir.y) > 0.05
        ? (0.5 * page.y - top.y) / crDir.y
        : (0.5 * page.x - top.x) / max(abs(crDir.x), 0.0001);
    float2 cMid = top + crDir * xiMid;
    float D = 1.0e6;
    if (nrm.x > 0.0001) { D = min(D, (page.x - cMid.x) / nrm.x); }
    if (nrm.x < -0.0001) { D = min(D, -cMid.x / nrm.x); }
    if (nrm.y > 0.0001) { D = min(D, (page.y - cMid.y) / nrm.y); }
    if (nrm.y < -0.0001) { D = min(D, -cMid.y / nrm.y); }
    D = clamp(D, 1.0, page.x * 2.0);

    // 纸卷半径：上限 0.10 页宽（视觉上的卷边粗细），同时保证 πR ≤ 0.94·D
    // （纸料不够一个圆柱时卷小一点，而不是让弧长超过纸料宽度）。
    float R = max(min(0.10 * page.x, 0.30 * D), 1.0);
    float arc = PI * R;
    // 纸卷的屏幕投影宽度：t = R·sin(u/R)，u ≤ πR/2 时随 u 增大，之后折回来。
    float uMax = min(D, arc);
    float sOuter = R * sin(min(uMax / R, 0.5 * PI));

    float2 uv = texOf(p);
    half3 paper = u_paper.rgb;

    if (s >= 0.0) {
        // ── 露出页这一侧：纸卷的投影，再往外是露出页 ──────────────────────
        if (s < sOuter) {
            float a = asin(clamp(s / R, 0.0, 1.0));
            // 同一屏幕位置对应两个材质点：下半段（卷面朝上的正面）与上半段（绕回来
            // 盖住它的纸背）。上半段的纸还在（材质点仍在页矩形里）时看到的是纸背。
            float2 qBack = foot + nrm * (arc - R * a);
            if (paperAt(qBack, page)) {
                if (overlay) { return half4(0.0); }
                float2 uvs = texOf(qBack);
                half4 content = outsideTex(uvs) ? half4(paper, 1.0) : u_front.eval(uvs);
                float nz = sqrt(max(1.0 - (s / R) * (s / R), 0.0));
                half3 base = mix(paper, content.rgb, 0.39);
                return half4(half3(base * (0.5 + 0.5 * nz)), 1.0);
            }
            // 纸还没绕到上半段：看到的是卷面朝上的正面页（被压扁），旁注/页码照画。
            float2 qFront = foot + nrm * (R * a);
            if (paperAt(qFront, page)) {
                float2 uvs = texOf(qFront);
                return outsideTex(uvs) ? half4(paper, 1.0) : u_front.eval(uvs);
            }
            // 连下半段都没有：纸在这里已经到头，下面接着走「露出页」。
        }
        // 露出页 + 纸卷投下来的落影（从纸卷外沿起，宽 0.8R，线性淡出）。
        half4 bg = outsideTex(uv) ? half4(paper, 1.0) : u_back.eval(uv);
        float k = (s >= sOuter) ? 1.0 - clamp((s - sOuter) / (0.8 * R), 0.0, 1.0) : 0.0;
        return half4(bg.rgb * (1.0 - 0.45 * shadow * k * k), bg.a);
    }

    // ── 折痕另一侧：纸背平铺段（刚性 1:1 镜像），再往外是还没掀起的正面页 ──
    float t = -s;
    float2 qBack = foot + nrm * (arc + t);
    if (paperAt(qBack, page)) {
        // 纸背：纸色打底 + 镜像内容低透明度（旧版 100/255 ≈ 0.39）。
        // 平铺段法向恒朝上（nz = 1），不画自身阴影——见文件头。
        if (overlay) { return half4(0.0); }
        float2 uvs = texOf(qBack);
        half4 content = outsideTex(uvs) ? half4(paper, 1.0) : u_front.eval(uvs);
        return half4(mix(paper, content.rgb, 0.39), 1.0);
    }
    // 纸到头了：露出下面还没掀起的正面页，纸边在它上面压一道接触影。
    half4 fg = outsideTex(uv) ? half4(paper, 1.0) : u_front.eval(uv);
    float q = clamp(distOutside(qBack, page) / max(0.35 * R, 1.0), 0.0, 1.0);
    float k = 1.0 - q;
    return half4(fg.rgb * (1.0 - 0.32 * shadow * k * k), fg.a);
}
"""
