package orilumn.reader.ui.reader.curl

/**
 * 卷曲片元着色器（**唯一定义处**，桌面 SkSL 与 Android AGSL 共用同一份源码）。
 *
 * 这是「逐像素逆向映射」：片元不移动几何，而是反查「这个屏幕像素来自纸面的哪一点」。
 * 好处是曲面上没有接缝、明暗是连续的，也不需要在别处再写一份多边形切片。
 *
 * ## 与平台无关的约定
 *
 * - 子着色器一律用**归一化 uv**（0..1）采样：桌面 `Image.makeShader` 挂 `scale(1/w, 1/h)`
 *   的 localMatrix，Android `BitmapShader` 挂同一个矩阵。两端因此共用 `eval(uv)`，
 *   不存在「一边像素坐标一边归一化」的分支。
 * - uniform 只有四个 `float4`，顺序 = [CurlFrame.uniforms] 的打包顺序（见那边的注释）。
 *
 * ## 几何（与 [CurlFrame] 同一套）
 *
 * ```
 *   s = 片段到折痕的带符号距离（沿 n，n 指向露出页）
 *   D = 折痕点沿 n 到页边界的距离（逐段算，倾斜时自然收进页角）
 *   s ≥ 0      → 露出页（目标页纹理）
 *   -D ≤ s < 0 → 纸卷：靠折痕一段是圆弧（弦长 B = 0.35·D），外侧整段平铺
 *                屏幕距离 t = -s，φ = asin(t/B) ∈ [0, π/2]，材质距离 dm = B·φ
 *                背面采样 = 被翻那页在折痕另一侧 dm 处的内容（镜像，旧版语义）
 *   s < -D     → 平铺正面页
 * ```
 *
 * 为什么不是「整段半圆柱」：半圆柱两端的切线与屏幕垂直，采样率在那里趋于 0，
 * 正文会被压成一片糊；而且圆弧的弧长（πD/2）比它能占的屏幕宽度（D）长，
 * 塞不进去。所以圆弧只给折痕那一小段（真正的卷边），外侧按 1:1 平铺——
 * 与旧版手感一致，只是折缝从「一条线」变成「一段会亮的圆角」。
 *
 * 法向 `N = -cos(φ)·n + sin(φ)·ẑ`：折痕处水平（背光、暗），φ = π/2 时朝上（最亮），
 * 外侧平铺段沿用 φ = π/2 的同一条法向——明暗在接缝处连续，不需要额外的阴影多边形。
 */
internal const val CURL_SKSL: String = """
uniform float4 u_page;    // xy = 页宽/页高(px)
uniform float4 u_geom1;   // xy = 折痕上端点, zw = 折痕方向单位向量
uniform float4 u_geom2;   // xy = 法向单位向量(指向露出页), z = 阴影强度
uniform float4 u_paper;   // rgb = 纸背底色
uniform shader u_front;   // 被翻的那一页（正面纹理）
uniform shader u_back;    // 露出的那一页

half4 main(float2 p) {
    float2 page = u_page.xy;
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

    float2 uv = p / page;
    half4 col;

    if (s >= 0.0) {
        // 露出页 + 折缝投影（高斯带，σ = 5% 页宽，继承旧版 SHADOW_BAND）。
        half4 bg = u_back.eval(uv);
        float sigma = 0.05 * page.x;
        float sh = shadow * exp(-(s * s) / (sigma * sigma));
        col = half4(bg.rgb * (1.0 - 0.55 * sh), bg.a);
    } else {
        float t = -s;
        if (t >= D) {
            // 平铺的正面页 + 纸卷压在页面上的接触阴影。
            half4 fg = u_front.eval(uv);
            float sigma2 = 0.03 * page.x;
            float dd = t - D;
            float sh2 = shadow * exp(-(dd * dd) / (sigma2 * sigma2));
            col = half4(fg.rgb * (1.0 - 0.35 * sh2), fg.a);
        } else {
            // 纸卷：靠折痕一段是四分之一圆弧（弦长 B），φ = asin(t/B)；外侧平铺。
            // 纸不可拉伸：圆弧吃掉的材料比它的屏幕弦长多（弧长 πB/2 > B），
            // 所以外侧平铺段要按剩余材料均摊（k ≤ 1），否则采样会被顶出页面、整片发白。
            float B = max(D * 0.35, 1.0);
            float phi = asin(clamp(t / B, 0.0, 1.0));
            float arc = B * 1.5707963;
            float dm = (t < B) ? B * phi : arc + (t - B) * (D - arc) / max(D - B, 1.0);
            float2 pBack = p + nrm * (t + dm);
            half4 content = u_front.eval(pBack / page);

            // 纸背 = 纸色打底 + 镜像内容低透明度（旧版 100/255 ≈ 0.39）。
            half3 paper = u_paper.rgb;
            half3 base = mix(paper, content.rgb, 0.39);

            // 圆弧段：折痕处切线竖直（法向水平，背光偏暗）→ φ = π/2 顶点朝上（最亮）；
            // 平铺段统一用 φ = π/2 的法向，接缝处明暗连续。
            float3 N = float3(-nrm * cos(phi), sin(phi));
            float3 L = normalize(float3(0.25, -0.45, 1.0));
            float diff = max(0.0, dot(N, L));
            float3 H = normalize(L + float3(0.0, 0.0, 1.0));
            float spec = pow(max(0.0, dot(N, H)), 24.0) * 0.07;
            float shade = 0.68 + 0.32 * diff;
            col = half4(half3(base * shade + spec), 1.0);
        }
    }
    return col;
}
"""
