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
 * ## 几何（与 [CurlFrame] 同一套，照 duokan 里 harism page curl 的实体模型）
 *
 * 一张**刚性**纸绕折痕卷过去：折痕处是半径 R 的半圆柱（纸卷），越过圆柱顶之后再平铺。
 * 纸卷落在**纸料那一侧**（也就是露出页这一侧），平铺段落在**另一侧**——这是实物翻页
 * 唯一自洽的摆法（纸从右半边掀起来，绕过折痕，落到左半边）。
 *
 * ```
 *   s = 片段到折痕的带符号距离（沿 n，n 指向露出页）
 *   D = 折痕点沿 n 到页边界的距离（逐段算，倾斜时自然收进页角）= 被翻纸料的宽度
 *   R = 纸卷半径（0.10 页宽，且不超过 0.30·D ⇒ 弧长 πR ≤ 0.94·D）
 *   arc = πR = 纸卷吃掉的弧长，flatLen = D - arc = 平铺段的长度
 *
 *   s ≥ 0   露出页侧：  [0, sOuter) 纸卷（卷面朝上的那一面），之后是露出页 + 落影
 *   s < 0   另一侧：    [0, flatLen] 纸背平铺（1:1 刚性镜像），再往左是正面页
 * ```
 *
 * 三件必须分开算的事：
 *
 * 1. **平铺段 1:1**：`dm = arc + t`（t = 屏幕位移）。旧版把剩余纸料压进屏幕带宽
 *    （斜率 (D-arc)/(D-B) < 1），纸背被放大约 1.45 倍——这是必须修掉的错。
 * 2. **纸卷是投影**：材质 u 与屏幕位移的关系是 `t = R·sin(u/R)`，反查 `u = R·asin(t/R)`。
 *    半圆柱的上半段（u > πR/2）绕回来盖住下半段，所以能看见的是**纸背**：
 *    `u = πR - R·asin(t/R)`，法向 z 分量 `nz = √(1-(t/R)²)`。
 *    纸料不足一个圆柱（D < πR/2）时上半段根本不存在，露出来的是**卷面朝上的正面**
 *    （内容照常，只是被压扁）——所以那里覆盖层要照画。
 * 3. **明暗**：纸背 `rgb *= 0.5 + 0.5·|nz|`（harism 同款，不用光源模型）：
 *    卷顶朝上最亮，纸卷最外沿竖直时最暗（0.5），平铺段恒 1。
 *
 * 阴影两道（照 harism：都是「贴着接触线、线性淡出」的软带，不是高斯带）：
 * - 落影：纸卷投在露出页上，从纸卷外沿起、宽 0.8R，最深 0.45；
 * - 接触影：平铺段压在正面页上的那一条，从纸边起、宽 0.35R，最深 0.32
 *   （harism 没有这一道，但我们的平铺段在正面页这侧，不补纸边就像贴上去的）。
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

    float2 d = p - top;
    float xi = dot(d, crDir);
    float s = dot(d, nrm);
    float2 c = top + crDir * xi;

    // 露出侧到页边界的距离（逐段）：x/y 四个边界取最先撞上的那个。
    float D = 1.0e6;
    if (nrm.x > 0.0001) { D = min(D, (page.x - c.x) / nrm.x); }
    if (nrm.x < -0.0001) { D = min(D, -c.x / nrm.x); }
    if (nrm.y > 0.0001) { D = min(D, (page.y - c.y) / nrm.y); }
    if (nrm.y < -0.0001) { D = min(D, -c.y / nrm.y); }
    D = clamp(D, 1.0, page.x * 2.0);

    // 纸卷半径：上限 0.10 页宽（视觉上的卷边粗细），同时保证 πR ≤ 0.94·D
    // （纸料不够一个圆柱时卷小一点，而不是让弧长超过纸料宽度）。
    float R = max(min(0.10 * page.x, 0.30 * D), 1.0);
    float arc = PI * R;                     // 纸卷吃掉的弧长
    float flatLen = max(D - arc, 0.0);      // 越过卷顶之后平铺的长度
    float umax = min(D, arc);
    // 纸卷的屏幕投影：t = R·sin(u/R)，u ≤ πR/2 时随 u 增大，之后折回来。
    float sOuter = R * sin(min(umax / R, 0.5 * PI));

    float t = -s;                           // 屏幕位移：折痕 → 被翻的那一侧
    float2 uv = texOf(p);
    half3 paper = u_paper.rgb;
    half4 col;

    if (s >= 0.0) {
        // ── 露出页这一侧 ────────────────────────────────────────────────
        if (s < sOuter) {
            // 纸卷。同一屏幕位置对应两个材质点：下半段（卷面朝上的正面）与
            // 上半段（绕回来盖住它的纸背）。上半段存在（uBack ≤ D）时看到的是纸背。
            float a = asin(clamp(s / R, 0.0, 1.0));
            float uBack = arc - R * a;
            bool backFace = uBack <= D;
            float dm = backFace ? uBack : R * a;
            float2 uvs = texOf(p + nrm * (t + dm));
            half4 content = outsideTex(uvs) ? half4(paper, 1.0) : u_front.eval(uvs);
            if (!backFace) {
                // 纸还没翻过竖直：看到的就是被压扁的正面页，旁注/页码照画。
                col = content;
            } else {
                // 纸背：纸色打底 + 镜像内容低透明度（旧版 100/255 ≈ 0.39），
                // 明暗只看法向 z（harism：卷顶最亮，最外沿竖直时 0.5）。
                if (overlay) { return half4(0.0); }
                float nz = sqrt(max(1.0 - (s / R) * (s / R), 0.0));
                half3 base = mix(paper, content.rgb, 0.39);
                col = half4(half3(base * (0.5 + 0.5 * nz)), 1.0);
            }
        } else {
            // 露出页 + 纸卷投下来的落影（从纸卷外沿起，宽 0.8R，线性淡出）。
            half4 bg = outsideTex(uv) ? half4(paper, 1.0) : u_back.eval(uv);
            float q = clamp((s - sOuter) / (0.8 * R), 0.0, 1.0);
            float k = 1.0 - q;
            col = half4(bg.rgb * (1.0 - 0.45 * shadow * k * k), bg.a);
        }
    } else if (t <= flatLen) {
        // ── 纸背平铺段：刚性 1:1 镜像（dm = arc + t，斜率 1）────────────────
        if (overlay) { return half4(0.0); }
        float dm = arc + t;
        float2 uvs = texOf(p + nrm * (t + dm));
        half4 content = outsideTex(uvs) ? half4(paper, 1.0) : u_front.eval(uvs);
        half3 base = mix(paper, content.rgb, 0.39);
        // 平铺段法向恒朝上（nz = 1），不画自身阴影——见文件头「纸背平铺段不画自身阴影」。
        col = half4(base, 1.0);
    } else {
        // ── 平铺的正面页 + 纸边压在页面上的接触影 ──────────────────────────
        half4 fg = outsideTex(uv) ? half4(paper, 1.0) : u_front.eval(uv);
        float q = clamp((t - flatLen) / max(0.35 * R, 1.0), 0.0, 1.0);
        float k = 1.0 - q;
        float contact = (flatLen > 1.0) ? 0.32 * shadow * k * k : 0.0;
        col = half4(fg.rgb * (1.0 - contact), fg.a);
    }
    return col;
}
"""
