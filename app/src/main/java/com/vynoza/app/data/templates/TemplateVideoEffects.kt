package com.vynoza.app.data.templates

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import com.vynoza.app.core.templates.CloneArrangement
import com.vynoza.app.core.templates.ExposureBlendMode
import com.vynoza.app.core.templates.MemoriesFrameStyle
import com.vynoza.app.core.templates.MemoriesTransitionStyle
import com.vynoza.app.core.templates.PortraitFraming
import com.vynoza.app.core.templates.TemplateParameters
import com.vynoza.app.core.templates.VynozaTemplateId
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(UnstableApi::class)
object TemplateVideoEffects {

  fun buildVideoEffects(
    templateId: VynozaTemplateId,
    params: TemplateParameters,
    isPhotoMotionExport: Boolean = false,
  ): List<Effect> {
    if (isPhotoMotionExport) {
      return when (templateId) {
        VynozaTemplateId.CLONE_ECHO,
        VynozaTemplateId.MULTIPLE_EXPOSURE -> listOf(
          MatrixTransformation { presentationTimeUs ->
            val t = ((presentationTimeUs / 1_000_000f) / 4.5f).coerceIn(0f, 1f)
            val scale = 1f + 0.08f * t
            Matrix().apply { setScale(scale, scale) }
          }
        )
        VynozaTemplateId.CINEMATIC_PORTRAIT -> {
          val zoom = max(0.14f, params.smoothZoom)
          listOf(
            MatrixTransformation { presentationTimeUs ->
              val t = ((presentationTimeUs / 1_000_000f) / 4.5f).coerceIn(0f, 1f)
              val scale = 1f + zoom * t
              Matrix().apply { setScale(scale, scale) }
            }
          )
        }
        VynozaTemplateId.MOTION_BLUR -> {
          val rad = Math.toRadians(params.blurAngleDegrees.toDouble())
          val dx = cos(rad).toFloat()
          val dy = sin(rad).toFloat()
          listOf(
            MatrixTransformation { presentationTimeUs ->
              val t = (presentationTimeUs / 1_000_000f)
              val drift = sin((t * 1.4f).toDouble()).toFloat() * 0.03f * params.blurIntensity
              Matrix().apply {
                setScale(1.06f, 1.06f)
                postTranslate(dx * drift, dy * drift)
              }
            }
          )
        }
        VynozaTemplateId.MEMORIES -> buildList {
          add(
            MatrixTransformation { presentationTimeUs ->
              val t = ((presentationTimeUs / 1_000_000f) / 4.5f).coerceIn(0f, 1f)
              val scale = 1f + 0.12f * t
              Matrix().apply { setScale(scale, scale) }
            }
          )
          add(MemoriesGradeGlEffect(params))
          add(OverlayEffect(listOf(buildMemoriesCaptionOverlay(params))))
        }
      }
    }

    return when (templateId) {
      VynozaTemplateId.CLONE_ECHO -> listOf(CloneEchoGlEffect(params))
      VynozaTemplateId.CINEMATIC_PORTRAIT -> buildList {
        val aspect =
          when (params.portraitFraming) {
            PortraitFraming.ORIGINAL -> null
            PortraitFraming.PORTRAIT_4_5 -> 4f / 5f
            PortraitFraming.CINEMA_9_16 -> 9f / 16f
          }
        if (aspect != null) {
          add(Presentation.createForAspectRatio(aspect, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
        }
        if (params.smoothZoom > 0.01f) {
          add(
            MatrixTransformation { presentationTimeUs ->
              val t = ((presentationTimeUs / 1_000_000f) / 4.5f).coerceIn(0f, 1f)
              val scale = 1f + params.smoothZoom * t
              Matrix().apply { setScale(scale, scale) }
            }
          )
        }
        add(CinematicPortraitGlEffect(params))
      }
      VynozaTemplateId.MOTION_BLUR -> listOf(MotionBlurGlEffect(params))
      VynozaTemplateId.MULTIPLE_EXPOSURE -> listOf(MultipleExposureGlEffect(params))
      VynozaTemplateId.MEMORIES -> buildList {
        if (params.memoriesTransition == MemoriesTransitionStyle.KEN_BURNS) {
          add(
            MatrixTransformation { presentationTimeUs ->
              val t = ((presentationTimeUs / 1_000_000f) / 4.5f).coerceIn(0f, 1f)
              val scale = 1f + 0.10f * t
              Matrix().apply { setScale(scale, scale) }
            }
          )
        }
        add(MemoriesGradeGlEffect(params))
        add(OverlayEffect(listOf(buildMemoriesCaptionOverlay(params))))
      }
    }
  }

  private fun buildMemoriesCaptionOverlay(params: TemplateParameters): BitmapOverlay {
    val w = 640
    val h = 136
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(Color.TRANSPARENT)

    val isLightPaper = params.memoriesFrameStyle == MemoriesFrameStyle.POLAROID
    val titlePaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isLightPaper) Color.rgb(34, 28, 42) else Color.rgb(246, 244, 251)
        textAlign = Paint.Align.CENTER
        textSize = 36f
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
      }
    val datePaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isLightPaper) Color.rgb(112, 98, 126) else Color.rgb(224, 170, 255)
        textAlign = Paint.Align.CENTER
        textSize = 22f
        letterSpacing = 0.14f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
      }
    val title = params.memoriesTitle.trim().ifBlank { "VYNOZA Memory" }.take(34)
    val date = params.memoriesDateText.trim().ifBlank { TemplateParameters.defaultDateStamp() }.uppercase()
    canvas.drawText(title, w / 2f, 56f, titlePaint)
    canvas.drawText(date, w / 2f, 102f, datePaint)

    val settings =
      StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, -0.84f)
        .setOverlayFrameAnchor(0f, 0f)
        .build()
    return BitmapOverlay.createStaticBitmapOverlay(bmp, settings)
  }
}

private const val STANDARD_VERTEX_SHADER = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

@OptIn(UnstableApi::class)
private abstract class VynozaShaderProgram(
  fragmentShader: String,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1) {
  protected val glProgram = GlProgram(STANDARD_VERTEX_SHADER, fragmentShader)

  override fun configure(inputWidth: Int, inputHeight: Int): Size {
    return Size(inputWidth, inputHeight)
  }

  override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
    glProgram.use()
    glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
    glProgram.setBufferAttribute(
      "aFramePosition",
      GlUtil.getNormalizedCoordinateBounds(),
      GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
    )
    bindCustomUniforms(presentationTimeUs)
    glProgram.bindAttributesAndUniforms()
    GLES20.glClearColor(0f, 0f, 0f, 1f)
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GlUtil.checkGlError()
  }

  protected abstract fun bindCustomUniforms(presentationTimeUs: Long)

  override fun release() {
    super.release()
    glProgram.delete()
  }
}

@OptIn(UnstableApi::class)
class CloneEchoGlEffect(private val params: TemplateParameters) : GlEffect {
  override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
    return object : VynozaShaderProgram(FRAGMENT_SHADER) {
      override fun bindCustomUniforms(presentationTimeUs: Long) {
        val tSec = presentationTimeUs / 1_000_000f
        glProgram.setFloatUniform("uSpacing", params.cloneSpacing.coerceIn(0.08f, 1.0f) * 0.24f)
        glProgram.setFloatUniform("uEchoOpacity", params.cloneOpacity.coerceIn(0.15f, 0.95f))
        glProgram.setFloatUniform("uCloneCount", params.cloneCount.coerceIn(2, 5).toFloat())
        glProgram.setFloatUniform("uTime", tSec)
        glProgram.setFloatUniform("uUseTint", if (params.cloneEchoTint) 1f else 0f)
        val arrMode =
          when (params.cloneArrangement) {
            CloneArrangement.HORIZONTAL_FAN -> 0f
            CloneArrangement.DIAGONAL_ECHO -> 1f
            CloneArrangement.ARC_HALO -> 2f
          }
        glProgram.setFloatUniform("uArrangement", arrMode)
      }
    }
  }

  private companion object {
    const val FRAGMENT_SHADER = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uSpacing;
uniform float uEchoOpacity;
uniform float uCloneCount;
uniform float uTime;
uniform float uUseTint;
uniform float uArrangement;
varying vec2 vTexSamplingCoord;

vec3 sampleOffset(vec2 offset, float scale) {
  vec2 uv = (vTexSamplingCoord - 0.5) / scale + 0.5 - offset;
  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return vec3(0.0);
  return texture2D(uTexSampler, uv).rgb;
}

void main() {
  vec4 base = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 acc = base.rgb * 0.78;
  float weightSum = 0.78;
  float pulse = 1.0 + 0.12 * sin(uTime * 2.5);

  for (int i = 1; i <= 4; i++) {
    float fi = float(i);
    float active = step(fi, uCloneCount - 0.5);
    float side = mix(-1.0, 1.0, step(0.5, mod(fi, 2.0)));
    float tier = floor((fi + 1.0) * 0.5);
    vec2 off = vec2(side * tier * uSpacing * pulse, tier * uSpacing * 0.16);
    if (uArrangement > 0.5 && uArrangement < 1.5) {
      off = vec2(-tier * uSpacing * 0.85 * pulse, -tier * uSpacing * 0.55);
    } else if (uArrangement >= 1.5) {
      float ang = (fi - 1.5) * 0.65;
      off = vec2(sin(ang) * uSpacing * 1.3 * pulse, -abs(cos(ang) - 1.0) * uSpacing);
    }
    float sc = max(0.72, 0.94 - tier * 0.06);
    vec3 echo = sampleOffset(off, sc);
    vec3 tinted = echo * mix(vec3(0.92, 0.80, 1.24), vec3(1.12, 0.82, 1.08), step(0.5, mod(fi, 2.0)));
    echo = mix(echo, tinted, step(0.5, uUseTint));
    float w = active * uEchoOpacity * (0.65 - fi * 0.10);
    acc += echo * w;
    weightSum += w;
  }
  gl_FragColor = vec4(clamp(acc / max(weightSum, 0.01), 0.0, 1.0), base.a);
}
"""
  }
}

@OptIn(UnstableApi::class)
class CinematicPortraitGlEffect(private val params: TemplateParameters) : GlEffect {
  override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
    return object : VynozaShaderProgram(FRAGMENT_SHADER) {
      override fun bindCustomUniforms(presentationTimeUs: Long) {
        glProgram.setFloatUniform("uContrast", params.contrast.coerceIn(0.6f, 2.0f))
        glProgram.setFloatUniform("uSaturation", params.saturation.coerceIn(0.2f, 1.9f))
        glProgram.setFloatUniform("uShadows", params.shadows.coerceIn(-0.6f, 0.6f))
        glProgram.setFloatUniform("uHighlights", params.highlights.coerceIn(-0.6f, 0.6f))
      }
    }
  }

  private companion object {
    const val FRAGMENT_SHADER = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uContrast;
uniform float uSaturation;
uniform float uShadows;
uniform float uHighlights;
varying vec2 vTexSamplingCoord;

void main() {
  vec4 base = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 c = base.rgb;
  float luma = dot(c, vec3(0.299, 0.587, 0.114));
  float sW = clamp((0.50 - luma) * 2.0, 0.0, 1.0);
  float hW = clamp((luma - 0.50) * 2.0, 0.0, 1.0);
  c += (uShadows * 0.28 * sW + uHighlights * 0.28 * hW);
  c.r += hW * 0.045 - sW * 0.020;
  c.g += hW * 0.015;
  c.b += sW * 0.040 - hW * 0.025;
  c = (c - 0.5) * uContrast + 0.5;
  float l2 = dot(c, vec3(0.299, 0.587, 0.114));
  c = vec3(l2) + (c - vec3(l2)) * uSaturation;
  vec2 d = vTexSamplingCoord - vec2(0.5, 0.45);
  float vig = clamp(1.0 - dot(d, d) * 0.68, 0.62, 1.0);
  gl_FragColor = vec4(clamp(c * vig, 0.0, 1.0), base.a);
}
"""
  }
}

@OptIn(UnstableApi::class)
class MotionBlurGlEffect(private val params: TemplateParameters) : GlEffect {
  override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
    return object : VynozaShaderProgram(FRAGMENT_SHADER) {
      override fun bindCustomUniforms(presentationTimeUs: Long) {
        val rad = Math.toRadians(params.blurAngleDegrees.toDouble())
        glProgram.setFloatUniform("uDirX", cos(rad).toFloat())
        glProgram.setFloatUniform("uDirY", sin(rad).toFloat())
        glProgram.setFloatUniform("uIntensity", params.blurIntensity.coerceIn(0.05f, 1.0f))
        glProgram.setFloatUniform("uGhostTrail", params.ghostTrail.coerceIn(0f, 1f))
      }
    }
  }

  private companion object {
    const val FRAGMENT_SHADER = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uDirX;
uniform float uDirY;
uniform float uIntensity;
uniform float uGhostTrail;
varying vec2 vTexSamplingCoord;

void main() {
  vec2 dir = vec2(uDirX, uDirY);
  float reach = 0.065 * uIntensity;
  vec3 acc = vec3(0.0);
  float wSum = 0.0;
  for (int t = -5; t <= 5; t++) {
    float frac = float(t) / 5.0;
    float w = 1.15 - abs(frac) * 0.55;
    vec2 uv = clamp(vTexSamplingCoord + dir * frac * reach, 0.0, 1.0);
    acc += texture2D(uTexSampler, uv).rgb * w;
    wSum += w;
  }
  vec3 blurred = acc / wSum;
  float gShift = 0.075 * (0.4 + uGhostTrail * 0.8);
  vec3 g1 = texture2D(uTexSampler, clamp(vTexSamplingCoord - dir * gShift, 0.0, 1.0)).rgb;
  vec3 g2 = texture2D(uTexSampler, clamp(vTexSamplingCoord - dir * gShift * 0.5, 0.0, 1.0)).rgb;
  vec3 finalRgb = mix(blurred, (g1 + g2) * 0.5, clamp(uGhostTrail * 0.42, 0.0, 0.55));
  gl_FragColor = vec4(clamp(finalRgb, 0.0, 1.0), 1.0);
}
"""
  }
}

@OptIn(UnstableApi::class)
class MultipleExposureGlEffect(private val params: TemplateParameters) : GlEffect {
  override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
    return object : VynozaShaderProgram(FRAGMENT_SHADER) {
      override fun bindCustomUniforms(presentationTimeUs: Long) {
        val tSec = presentationTimeUs / 1_000_000f
        glProgram.setFloatUniform("uOpacity", params.exposureOpacity.coerceIn(0.15f, 0.95f))
        glProgram.setFloatUniform("uOffset", params.exposureOffset.coerceIn(0.02f, 0.9f))
        glProgram.setFloatUniform("uScale", params.exposureScale.coerceIn(0.8f, 1.45f))
        glProgram.setFloatUniform("uMirror", if (params.exposureMirrorSecond) 1f else 0f)
        glProgram.setFloatUniform("uTime", tSec)
        val mode =
          when (params.exposureBlendMode) {
            ExposureBlendMode.SCREEN -> 0f
            ExposureBlendMode.LIGHTEN -> 1f
            ExposureBlendMode.OVERLAY -> 2f
            ExposureBlendMode.SOFT_ADD -> 3f
          }
        glProgram.setFloatUniform("uBlendMode", mode)
      }
    }
  }

  private companion object {
    const val FRAGMENT_SHADER = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uOpacity;
uniform float uOffset;
uniform float uScale;
uniform float uMirror;
uniform float uTime;
uniform float uBlendMode;
varying vec2 vTexSamplingCoord;

void main() {
  vec4 base = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 a = base.rgb * vec3(0.84, 0.82, 0.90);
  float drift = sin(uTime * 1.6) * 0.025;
  vec2 uv2 = (vTexSamplingCoord - 0.5) / uScale;
  if (uMirror > 0.5) uv2.x = -uv2.x;
  uv2 += 0.5 - vec2(uOffset * 0.14 + drift, -uOffset * 0.06);
  vec3 b = vec3(0.0);
  if (uv2.x >= 0.0 && uv2.x <= 1.0 && uv2.y >= 0.0 && uv2.y <= 1.0) {
    b = texture2D(uTexSampler, uv2).rgb * vec3(1.08, 0.90, 1.15);
  }
  vec3 blended;
  if (uBlendMode < 0.5) {
    blended = 1.0 - (1.0 - a) * (1.0 - b);
  } else if (uBlendMode < 1.5) {
    blended = max(a, b);
  } else if (uBlendMode < 2.5) {
    vec3 lo = 2.0 * a * b;
    vec3 hi = 1.0 - 2.0 * (1.0 - a) * (1.0 - b);
    blended = mix(lo, hi, step(0.5, a));
  } else {
    blended = clamp(a + b * 0.72, 0.0, 1.0);
  }
  gl_FragColor = vec4(mix(a, blended, uOpacity), base.a);
}
"""
  }
}

@OptIn(UnstableApi::class)
class MemoriesGradeGlEffect(private val params: TemplateParameters) : GlEffect {
  override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
    return object : VynozaShaderProgram(FRAGMENT_SHADER) {
      override fun bindCustomUniforms(presentationTimeUs: Long) {
        val tSec = presentationTimeUs / 1_000_000f
        glProgram.setFloatUniform("uWarmth", params.memoriesWarmth.coerceIn(0f, 1f))
        val fade =
          if (params.memoriesTransition == MemoriesTransitionStyle.FADE_GLOW) {
            min(1f, tSec / 0.9f)
          } else {
            1f
          }
        glProgram.setFloatUniform("uFade", fade)
        val warmSweep =
          if (params.memoriesTransition == MemoriesTransitionStyle.WARM_REVEAL) {
            max(0f, 1f - tSec / 1.4f) * 0.28f
          } else {
            0f
          }
        glProgram.setFloatUniform("uWarmSweep", warmSweep)
        val styleId =
          when (params.memoriesFrameStyle) {
            MemoriesFrameStyle.POLAROID -> 0f
            MemoriesFrameStyle.FILM_STRIP -> 1f
            MemoriesFrameStyle.SCRAPBOOK -> 2f
          }
        glProgram.setFloatUniform("uFrameStyle", styleId)
      }
    }
  }

  private companion object {
    const val FRAGMENT_SHADER = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uWarmth;
uniform float uFade;
uniform float uWarmSweep;
uniform float uFrameStyle;
varying vec2 vTexSamplingCoord;

void main() {
  vec4 base = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 c = base.rgb;
  float luma = dot(c, vec3(0.299, 0.587, 0.114));
  float sat = 0.92 - uWarmth * 0.14;
  c = vec3(luma) + (c - vec3(luma)) * sat;
  c *= vec3(1.0 + uWarmth * 0.10, 1.0 + uWarmth * 0.04, 1.0 - uWarmth * 0.10);
  c = c * uFade + vec3(1.0, 0.78, 0.45) * uWarmSweep;

  float x = vTexSamplingCoord.x;
  float y = vTexSamplingCoord.y;
  bool inBorder = (x < 0.06 || x > 0.94 || y > 0.945 || y < 0.19);
  if (inBorder) {
    if (uFrameStyle < 0.5) {
      c = vec3(0.965, 0.945, 0.910);
    } else if (uFrameStyle < 1.5) {
      c = vec3(0.063, 0.055, 0.086);
      float py = mod(y * 10.0, 1.0);
      if ((x > 0.015 && x < 0.045 || x > 0.955 && x < 0.985) && py > 0.30 && py < 0.70) {
        c = vec3(0.88, 0.82, 0.96);
      }
    } else {
      c = vec3(0.14, 0.09, 0.22);
      if (x > 0.054 && x < 0.946 && y < 0.950 && y > 0.185) {
        c = vec3(0.88, 0.67, 1.0);
      }
    }
  }
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), base.a);
}
"""
  }
}
