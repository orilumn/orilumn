package orilumn.reader.ui.reader.curl

/**
 * 卷曲网格的**片元着色器**（排版层上 → 用户层，两端同一份源码）。
 *
 * 一段三角形网格只承载三件事：屏幕位置、纹理坐标（**页坐标**，px）、画序。剩下的全在这里：
 *
 * 1. **采样**：页栅格放在版心（`texOrigin` 偏移），覆盖层（旁注 / 页码）是整页大小。
 *    两者按 `overlay.a` 合成 —— 旁注是印在纸上的浮层，跟着纸一起卷。
 * 2. **明暗**：圆柱法线 `N = (sinθ, 0, cosθ)`，θ 由「到折痕的弧长 / 半径」得到。
 *    观察者在正上方，所以正对观察者（θ = 0 与 θ = π）最亮、侧对着（θ = π/2）最暗；
 *    再加一道窄高光压在卷顶，纸才有「圆」的味道（moon+ `NewCurl3D.java:683` 同源思路）。
 * 3. **纸背**：θ > π/2 之后看到的是同一张纸的背面，压到 0.39 并混入纸色。
 *
 * SkSL 与 AGSL 语法在这几个特性上一致（`uniform shader` / `eval` / `half4 main(float2)`），
 * 所以桌面与安卓共用这一份字符串，只有最后 `drawVertices` 那一跳分端。
 */
internal const val CURL_MESH_SHADER: String = """
uniform shader pageTex;
uniform shader overlayTex;
uniform float foldTx;
uniform float foldTy;
uniform float foldNx;
uniform float foldNy;
uniform float radius;
uniform float surface;
uniform float texOx;
uniform float texOy;
uniform float paperR;
uniform float paperG;
uniform float paperB;

half4 main(float2 p) {
    // 到折痕的弧长：纹理坐标就是材料坐标，所以 s 直接量得到。
    // 平铺段（surface 2）的纹理坐标被镜射回了折痕另一侧，弧长要补回半圈。
    float s = (p.x - foldTx) * foldNx + (p.y - foldTy) * foldNy;
    if (surface > 1.5) {
        s = s + 3.14159265 * radius;
    }
    float th = clamp(s / max(radius, 0.001), 0.0, 3.14159265);

    half4 pg = pageTex.eval(p - float2(texOx, texOy));
    half4 ov = overlayTex.eval(p);
    // 覆盖层是预乘的，先还原再按 alpha 盖上去，否则半透明旁注会整块发灰。
    half3 ovc = ov.a > 0.004 ? ov.rgb / ov.a : half3(0.0, 0.0, 0.0);
    half3 base = mix(pg.rgb, ovc, ov.a);

    // 圆柱法线：N = (sinθ, 0, cosθ)；背面（卷的内侧）法线反号。
    // 光从观察者左上前方来，正对光最亮、侧过去最暗 —— 卷顶那一圈因此压出一道暗带，
    // 纸才有厚度。加一点窄高光压在卷顶，避免整段读成一条灰带。
    float nx = sin(th);
    float nz = cos(th);
    if (surface > 0.5) { nx = -nx; nz = -nz; }
    float diff = max(nx * (-0.35) + nz * 0.90, 0.0);
    float sheen = pow(sin(th), 10.0) * 0.18;
    half shade = half(0.60 + 0.40 * diff + sheen);

    half3 ink = base * shade;
    half3 paper = half3(half(paperR), half(paperG), half(paperB));
    // 正面不压淡；背面（卷的内侧 / 平铺回来的纸背）压到 0.39。
    half k = surface > 0.5 ? 0.39 : 1.0;
    return half4(mix(paper, ink, k), 1.0);
}
"""
