package app.folio.notes.ui.glass

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * The GPU half of a glass surface: blur, then the material's colour treatment and the
 * Liquid Glass edge lens, as one [RenderEffect] on a quarter-resolution copy of the backdrop.
 *
 * - Android 13+: an AGSL shader does saturation, the luminance curve, the tint and the lens
 *   (content near the rim is sampled from further out, so it bends round the edge).
 * - Android 12: blur plus a colour matrix (the luminance curve linearised).
 * - Older: no effect; the surface falls back to a frosted tint (see [GlassSpec.fallbackFill]).
 */
internal object GlassShader {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private const val SRC = """
uniform shader content;
uniform float2 rectOrigin;
uniform float2 rectSize;
uniform float radius;
uniform float saturation;
uniform float brightness;
uniform float4 lum;
uniform float lumAmount;
uniform float lens;
uniform float lensWidth;
uniform float4 tint;

float sdRoundRect(float2 p, float2 h, float r) {
    float2 q = abs(p) - h + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 h = rectSize * 0.5;
    float2 p = coord - (rectOrigin + h);
    float r = min(radius, min(h.x, h.y));
    float2 at = coord;
    if (lens > 0.0) {
        float d = sdRoundRect(p, h, r);
        if (d > -lensWidth) {
            float e = 0.75;
            float2 n = float2(
                sdRoundRect(p + float2(e, 0.0), h, r) - sdRoundRect(p - float2(e, 0.0), h, r),
                sdRoundRect(p + float2(0.0, e), h, r) - sdRoundRect(p - float2(0.0, e), h, r));
            n = n / max(length(n), 0.0001);
            float t = clamp(1.0 + d / lensWidth, 0.0, 1.0);
            at = coord + n * (lens * t * t * t);
        }
    }
    half4 c = content.eval(at);
    half3 rgb = c.a > 0.001 ? c.rgb / c.a : half3(0.0);
    float l = dot(float3(rgb), float3(0.299, 0.587, 0.114));
    float3 s = mix(float3(l), float3(rgb), saturation);
    float t1 = clamp(l, 0.0, 1.0);
    float u = 1.0 - t1;
    float curve = u * u * u * lum.x + 3.0 * u * u * t1 * lum.y + 3.0 * u * t1 * t1 * lum.z + t1 * t1 * t1 * lum.w;
    float target = clamp(l + (curve - l) * lumAmount + brightness, 0.0, 1.0);
    float3 o = clamp(s + (target - l), 0.0, 1.0);
    o = mix(o, tint.rgb, tint.a);
    return half4(half3(o), 1.0);
}
"""

    /** Everything the effect depends on; equal params → the cached effect is reused. */
    data class Params(
        val blurPx: Float,
        val rectX: Float, val rectY: Float, val rectW: Float, val rectH: Float, val radius: Float,
        val saturation: Float, val brightness: Float, val lum: List<Float>, val lumAmount: Float,
        val lens: Float, val lensWidth: Float,
        val tintR: Float, val tintG: Float, val tintB: Float, val tintA: Float,
    )

    @RequiresApi(Build.VERSION_CODES.S)
    fun create(p: Params, shader: Any?): RenderEffect {
        val blur = if (p.blurPx > 0.5f) RenderEffect.createBlurEffect(p.blurPx, p.blurPx, Shader.TileMode.CLAMP) else null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader is RuntimeShader) return agsl(p, shader, blur)
        // Android 12: linear stand-in for the curve: out = b + k · saturate(in).
        val f0 = p.lum[0]; val f1 = p.lum[3]
        val k = 1f + p.lumAmount * ((f1 - f0) - 1f)
        val b = p.lumAmount * f0 + p.brightness
        val m = saturationMatrix(p.saturation).apply {
            postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, b * 255, 0f, k, 0f, 0f, b * 255, 0f, 0f, k, 0f, b * 255, 0f, 0f, 0f, 1f, 0f)))
        }
        val filter = ColorMatrixColorFilter(m)
        return if (blur != null) RenderEffect.createColorFilterEffect(filter, blur) else RenderEffect.createColorFilterEffect(filter)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun newShader(): Any = RuntimeShader(SRC)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun agsl(p: Params, s: RuntimeShader, blur: RenderEffect?): RenderEffect {
        s.setFloatUniform("rectOrigin", p.rectX, p.rectY)
        s.setFloatUniform("rectSize", p.rectW, p.rectH)
        s.setFloatUniform("radius", p.radius)
        s.setFloatUniform("saturation", p.saturation)
        s.setFloatUniform("brightness", p.brightness)
        s.setFloatUniform("lum", p.lum[0], p.lum[1], p.lum[2], p.lum[3])
        s.setFloatUniform("lumAmount", p.lumAmount)
        s.setFloatUniform("lens", p.lens)
        s.setFloatUniform("lensWidth", p.lensWidth.coerceAtLeast(0.5f))
        s.setFloatUniform("tint", p.tintR, p.tintG, p.tintB, p.tintA)
        val shaded = RenderEffect.createRuntimeShaderEffect(s, "content")
        return if (blur != null) RenderEffect.createChainEffect(shaded, blur) else shaded
    }

    /** Saturation around Rec. 601 luma (what the macOS matrices use). */
    private fun saturationMatrix(s: Float): ColorMatrix {
        val lr = 0.299f; val lg = 0.587f; val lb = 0.114f
        val sr = (1 - s) * lr; val sg = (1 - s) * lg; val sb = (1 - s) * lb
        return ColorMatrix(
            floatArrayOf(
                sr + s, sg, sb, 0f, 0f,
                sr, sg + s, sb, 0f, 0f,
                sr, sg, sb + s, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }
}
