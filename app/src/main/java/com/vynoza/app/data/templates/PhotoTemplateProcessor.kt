package com.vynoza.app.data.templates

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import androidx.annotation.DrawableRes
import com.vynoza.app.core.templates.CloneArrangement
import com.vynoza.app.core.templates.ExposureBlendMode
import com.vynoza.app.core.templates.MemoriesFrameStyle
import com.vynoza.app.core.templates.PortraitFraming
import com.vynoza.app.core.templates.TemplateParameters
import com.vynoza.app.core.templates.VynozaTemplateId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object PhotoTemplateProcessor {
  const val PREVIEW_MAX_DIMENSION = 960
  const val GALLERY_THUMB_MAX_DIMENSION = 360
  const val EXPORT_MAX_DIMENSION = 1920

  suspend fun decodeSampledBitmap(
    context: Context,
    uri: Uri,
    maxDimension: Int = PREVIEW_MAX_DIMENSION,
  ): Result<Bitmap> =
    withContext(Dispatchers.IO) {
      runCatching {
        val resolver = context.contentResolver
        val header = ByteArray(12)
        val headerRead =
          resolver.openInputStream(uri)?.use { stream ->
            stream.read(header)
          } ?: throw IOException("Unable to open the selected photo.")
        if (!hasValidImageSignature(header, headerRead)) {
          throw IOException("Selected file is not a supported image.")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream =
          resolver.openInputStream(uri)
            ?: throw IOException("Unable to open the selected photo.")
        boundsStream.use { stream ->
          BitmapFactory.decodeStream(stream, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
          throw IOException("Selected file is not a supported image.")
        }

        val exifOrientation =
          try {
            resolver.openInputStream(uri)?.use { stream ->
              ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
              )
            } ?: ExifInterface.ORIENTATION_NORMAL
          } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
          }

        val decodeOpts =
          BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
          }
        val decoded =
          resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
          } ?: throw IOException("Failed to decode image pixels.")

        val oriented = applyExifOrientation(decoded, exifOrientation)
        if (oriented !== decoded) decoded.recycle()
        scaleDownIfNeeded(oriented, maxDimension)
      }.recoverCatching { throwable ->
        if (throwable is CancellationException) throw throwable
        if (throwable is OutOfMemoryError) {
          throw IOException("Photo is too large for available memory. Try a smaller image.", throwable)
        }
        throw throwable
      }
    }

  suspend fun decodeDrawableSample(
    context: Context,
    @DrawableRes drawableResId: Int,
    maxDimension: Int = GALLERY_THUMB_MAX_DIMENSION,
  ): Result<Bitmap> =
    withContext(Dispatchers.Default) {
      runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, drawableResId, bounds)
        val decodeOpts =
          BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
          }
        val decoded =
          BitmapFactory.decodeResource(context.resources, drawableResId, decodeOpts)
            ?: throw IOException("Sample portrait drawable missing.")
        scaleDownIfNeeded(decoded, maxDimension)
      }
    }

  suspend fun renderPreview(
    source: Bitmap,
    templateId: VynozaTemplateId,
    params: TemplateParameters,
  ): Bitmap =
    withContext(Dispatchers.Default) {
      currentCoroutineContext().ensureActive()
      applyTemplate(source, templateId, params)
    }

  suspend fun renderAndSavePhoto(
    context: Context,
    sourceUri: Uri,
    templateId: VynozaTemplateId,
    params: TemplateParameters,
    onProgress: (Float) -> Unit,
  ): Result<File> =
    withContext(Dispatchers.Default) {
      var tempOutput: File? = null
      try {
        TemplateMediaStorage.ensureEnoughStorageForPhoto(context).getOrThrow()
        onProgress(0.15f)
        currentCoroutineContext().ensureActive()

        val sourceBitmap = decodeSampledBitmap(context, sourceUri, EXPORT_MAX_DIMENSION).getOrThrow()
        onProgress(0.42f)
        currentCoroutineContext().ensureActive()

        val rendered =
          try {
            applyTemplate(sourceBitmap, templateId, params)
          } finally {
            sourceBitmap.recycle()
          }

        onProgress(0.78f)
        currentCoroutineContext().ensureActive()

        tempOutput = TemplateMediaStorage.createTempOutputFile(context, "vynoza_${templateId.routeArg}", "jpg")
        try {
          FileOutputStream(tempOutput).use { out ->
            if (!rendered.compress(Bitmap.CompressFormat.JPEG, 94, out)) {
              throw IOException("Failed to encode rendered JPEG image.")
            }
          }
        } finally {
          rendered.recycle()
        }

        if (!tempOutput.exists() || tempOutput.length() <= 0L) {
          throw IOException("Rendered photo file is empty.")
        }
        onProgress(1f)
        Result.success(tempOutput)
      } catch (e: CancellationException) {
        TemplateMediaStorage.safeDelete(tempOutput)
        throw e
      } catch (e: OutOfMemoryError) {
        TemplateMediaStorage.safeDelete(tempOutput)
        Result.failure(IOException("Ran out of memory while exporting this photo.", e))
      } catch (e: Exception) {
        TemplateMediaStorage.safeDelete(tempOutput)
        Result.failure(e)
      }
    }

  fun applyTemplate(
    source: Bitmap,
    templateId: VynozaTemplateId,
    params: TemplateParameters,
  ): Bitmap {
    val safeSource =
      if (source.config == Bitmap.Config.ARGB_8888) source
      else source.copy(Bitmap.Config.ARGB_8888, false)
    return when (templateId) {
      VynozaTemplateId.CLONE_ECHO -> applyCloneAndEcho(safeSource, params)
      VynozaTemplateId.CINEMATIC_PORTRAIT -> applyCinematicPortrait(safeSource, params)
      VynozaTemplateId.MOTION_BLUR -> applyMotionBlur(safeSource, params)
      VynozaTemplateId.MULTIPLE_EXPOSURE -> applyMultipleExposure(safeSource, params)
      VynozaTemplateId.MEMORIES -> applyMemoriesFrame(safeSource, params)
    }
  }

  private fun applyCloneAndEcho(source: Bitmap, params: TemplateParameters): Bitmap {
    val w = source.width
    val h = source.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)

    // Draw slightly dimmed atmospheric background
    val bgPaint =
      Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter =
          ColorMatrixColorFilter(
            ColorMatrix().apply {
              setScale(0.76f, 0.74f, 0.84f, 1f)
            }
          )
      }
    canvas.drawBitmap(source, 0f, 0f, bgPaint)

    // Extract feathered subject cutout against border palette + edge/center salience
    val cutout = extractSubjectCutout(source)
    val count = params.cloneCount.coerceIn(2, 5)
    val spacingPx = w * 0.24f * params.cloneSpacing.coerceIn(0.08f, 1.0f)
    val baseAlpha = (params.cloneOpacity.coerceIn(0.15f, 0.95f) * 255).roundToInt()

    val echoOffsets = buildClonePlacements(count - 1, spacingPx, h * 0.08f, params.cloneArrangement)
    val echoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    try {
      echoOffsets.forEachIndexed { index, (dx, dy, scale) ->
        val layerFactor = 1f - (index.toFloat() / max(1, echoOffsets.size)) * 0.42f
        echoPaint.alpha = (baseAlpha * layerFactor).roundToInt().coerceIn(32, 245)
        echoPaint.colorFilter =
          if (params.cloneEchoTint) {
            val isEven = index % 2 == 0
            val rGain = if (isEven) 0.95f else 1.12f
            val gGain = 0.78f
            val bGain = if (isEven) 1.24f else 1.06f
            ColorMatrixColorFilter(ColorMatrix().apply { setScale(rGain, gGain, bGain, 1f) })
          } else {
            null
          }

        val matrix = Matrix().apply {
          postTranslate(-w / 2f, -h / 2f)
          postScale(scale, scale)
          postTranslate(w / 2f + dx, h / 2f + dy)
        }
        canvas.drawBitmap(cutout, matrix, echoPaint)
      }

      // Draw crisp primary subject on top
      val crispPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
      canvas.drawBitmap(cutout, 0f, 0f, crispPaint)
    } finally {
      cutout.recycle()
    }
    return out
  }

  private fun buildClonePlacements(
    echoCount: Int,
    spacingX: Float,
    spacingY: Float,
    arrangement: CloneArrangement,
  ): List<Triple<Float, Float, Float>> {
    val placements = mutableListOf<Triple<Float, Float, Float>>()
    for (i in 0 until echoCount) {
      val side = if (i % 2 == 0) -1f else 1f
      val tier = (i / 2) + 1
      val scale = (0.94f - tier * 0.06f).coerceAtLeast(0.72f)
      when (arrangement) {
        CloneArrangement.HORIZONTAL_FAN ->
          placements += Triple(side * tier * spacingX, tier * spacingY * 0.25f, scale)
        CloneArrangement.DIAGONAL_ECHO ->
          placements += Triple(-tier * spacingX * 0.85f, -tier * spacingY * 0.65f, scale)
        CloneArrangement.ARC_HALO -> {
          val angle = (i - (echoCount - 1) / 2f) * 0.65f
          placements += Triple(
            sin(angle.toDouble()).toFloat() * spacingX * 1.45f,
            -abs(cos(angle.toDouble()).toFloat() - 1f) * spacingY * 1.2f - tier * spacingY * 0.2f,
            scale,
          )
        }
      }
    }
    return placements
  }

  private fun extractSubjectCutout(source: Bitmap): Bitmap {
    val w = source.width
    val h = source.height
    val pixels = IntArray(w * h)
    source.getPixels(pixels, 0, w, 0, 0, w, h)

    // Estimate border backdrop color from top, left, and right rim pixels
    var sumR = 0L
    var sumG = 0L
    var sumB = 0L
    var sampleCount = 0L
    val step = max(1, min(w, h) / 48)
    for (x in 0 until w step step) {
      val top = pixels[x]
      sumR += Color.red(top)
      sumG += Color.green(top)
      sumB += Color.blue(top)
      sampleCount++
    }
    for (y in 0 until (h * 0.8f).toInt() step step) {
      val left = pixels[y * w]
      val right = pixels[y * w + (w - 1)]
      sumR += Color.red(left) + Color.red(right)
      sumG += Color.green(left) + Color.green(right)
      sumB += Color.blue(left) + Color.blue(right)
      sampleCount += 2
    }
    val bgR = (sumR / max(1L, sampleCount)).toInt()
    val bgG = (sumG / max(1L, sampleCount)).toInt()
    val bgB = (sumB / max(1L, sampleCount)).toInt()

    // Measure border color variance so noisy backgrounds smoothly transition to soft portrait oval
    var bgDiffAccum = 0f
    var diffCount = 0
    for (x in 0 until w step step * 2) {
      val c = pixels[x]
      bgDiffAccum += abs(Color.red(c) - bgR) + abs(Color.green(c) - bgG) + abs(Color.blue(c) - bgB)
      diffCount++
    }
    val avgBgNoise = (bgDiffAccum / max(1, diffCount)) / (255f * 3f)
    val useColorKey = avgBgNoise < 0.16f

    val outPixels = IntArray(w * h)
    val cx = w * 0.5f
    val cy = h * 0.48f
    val rx = w * 0.42f
    val ry = h * 0.48f

    for (y in 0 until h) {
      val dy = (y - cy) / ry
      val dySq = dy * dy
      for (x in 0 until w) {
        val idx = y * w + x
        val c = pixels[idx]
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)

        val dx = (x - cx) / rx
        val radial = sqrt((dx * dx + dySq).toDouble()).toFloat()
        val spatialFeather = (1f - ((radial - 0.52f) / 0.58f)).coerceIn(0f, 1f)

        val alphaFrac =
          if (useColorKey) {
            val dr = abs(r - bgR) / 255f
            val dg = abs(g - bgG) / 255f
            val db = abs(b - bgB) / 255f
            val dist = sqrt((dr * dr + dg * dg + db * db).toDouble()).toFloat()
            val colorMask = ((dist - 0.08f) / 0.18f).coerceIn(0f, 1f)
            // Keep lower body intact while rejecting flat perimeter backdrop
            max(colorMask, spatialFeather * colorMask * 1.35f).coerceIn(0f, 1f)
          } else {
            // Smooth feathered ellipse when background is heavily textured
            val smooth = spatialFeather * spatialFeather * (3f - 2f * spatialFeather)
            smooth.coerceIn(0f, 1f)
          }

        val alpha = (alphaFrac * Color.alpha(c)).roundToInt().coerceIn(0, 255)
        outPixels[idx] = Color.argb(alpha, r, g, b)
      }
    }

    val cutout = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    cutout.setPixels(outPixels, 0, w, 0, 0, w, h)
    return cutout
  }

  private fun applyCinematicPortrait(source: Bitmap, params: TemplateParameters): Bitmap {
    val cropped = cropToPortraitFraming(source, params.portraitFraming)
    val w = cropped.width
    val h = cropped.height
    val pixels = IntArray(w * h)
    cropped.getPixels(pixels, 0, w, 0, 0, w, h)
    if (cropped !== source) cropped.recycle()

    val contrast = params.contrast.coerceIn(0.6f, 2.0f)
    val saturation = params.saturation.coerceIn(0.2f, 1.9f)
    val shadowAdj = params.shadows.coerceIn(-0.6f, 0.6f)
    val highlightAdj = params.highlights.coerceIn(-0.6f, 0.6f)

    val cx = w * 0.5f
    val cy = h * 0.45f
    val invMaxDistSq = 1f / (cx * cx + cy * cy).coerceAtLeast(1f)

    for (y in 0 until h) {
      val dy = y - cy
      val dySq = dy * dy
      val rowOffset = y * w
      for (x in 0 until w) {
        val idx = rowOffset + x
        val c = pixels[idx]
        var r = Color.red(c) / 255f
        var g = Color.green(c) / 255f
        var b = Color.blue(c) / 255f

        val luma = 0.299f * r + 0.587f * g + 0.114f * b

        // Shadows & highlights sculpted curve
        val shadowWeight = ((0.50f - luma) * 2f).coerceIn(0f, 1f)
        val highlightWeight = ((luma - 0.50f) * 2f).coerceIn(0f, 1f)
        val toneShift = shadowAdj * 0.28f * shadowWeight + highlightAdj * 0.28f * highlightWeight
        r += toneShift
        g += toneShift
        b += toneShift

        // Subtle cinematic split-tone: cool slate shadows, warm golden highlights
        r += highlightWeight * 0.045f - shadowWeight * 0.020f
        g += highlightWeight * 0.015f
        b += shadowWeight * 0.040f - highlightWeight * 0.025f

        // Contrast around mid-tone 0.5
        r = (r - 0.5f) * contrast + 0.5f
        g = (g - 0.5f) * contrast + 0.5f
        b = (b - 0.5f) * contrast + 0.5f

        // Saturation preserve luminance
        val lumAfter = 0.299f * r + 0.587f * g + 0.114f * b
        r = lumAfter + (r - lumAfter) * saturation
        g = lumAfter + (g - lumAfter) * saturation
        b = lumAfter + (b - lumAfter) * saturation

        // Radial portrait depth vignette
        val dx = x - cx
        val distRatio = (dx * dx + dySq) * invMaxDistSq
        val vignette = (1f - distRatio * 0.34f).coerceIn(0.62f, 1f)

        val outR = (r * vignette * 255f).roundToInt().coerceIn(0, 255)
        val outG = (g * vignette * 255f).roundToInt().coerceIn(0, 255)
        val outB = (b * vignette * 255f).roundToInt().coerceIn(0, 255)
        pixels[idx] = Color.argb(Color.alpha(c), outR, outG, outB)
      }
    }

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
  }

  private fun cropToPortraitFraming(source: Bitmap, framing: PortraitFraming): Bitmap {
    val targetRatio =
      when (framing) {
        PortraitFraming.ORIGINAL -> return source
        PortraitFraming.PORTRAIT_4_5 -> 4f / 5f
        PortraitFraming.CINEMA_9_16 -> 9f / 16f
      }
    val srcW = source.width
    val srcH = source.height
    val currentRatio = srcW.toFloat() / srcH.toFloat()
    if (abs(currentRatio - targetRatio) < 0.015f) return source

    val cropW: Int
    val cropH: Int
    if (currentRatio > targetRatio) {
      cropH = srcH
      cropW = (srcH * targetRatio).roundToInt().coerceIn(1, srcW)
    } else {
      cropW = srcW
      cropH = (srcW / targetRatio).roundToInt().coerceIn(1, srcH)
    }
    val left = ((srcW - cropW) / 2).coerceAtLeast(0)
    val top = ((srcH - cropH) / 3).coerceAtLeast(0) // Favor upper portrait subject framing
    return Bitmap.createBitmap(source, left, top, cropW, cropH)
  }

  private fun applyMotionBlur(source: Bitmap, params: TemplateParameters): Bitmap {
    val w = source.width
    val h = source.height
    val srcPixels = IntArray(w * h)
    source.getPixels(srcPixels, 0, w, 0, 0, w, h)

    val intensity = params.blurIntensity.coerceIn(0.05f, 1.0f)
    val rad = Math.toRadians(params.blurAngleDegrees.toDouble())
    val dirX = cos(rad).toFloat()
    val dirY = sin(rad).toFloat()

    // Directional streak reach scales cleanly with image dimension & intensity
    val maxReachPx = max(6f, min(w, h) * 0.09f * intensity)
    val tapRadius = 5
    val blurredPixels = IntArray(w * h)

    for (y in 0 until h) {
      val rowOffset = y * w
      for (x in 0 until w) {
        var accA = 0f
        var accR = 0f
        var accG = 0f
        var accB = 0f
        var totalWeight = 0f

        for (t in -tapRadius..tapRadius) {
          val fraction = t.toFloat() / tapRadius.toFloat()
          val weight = 1.15f - abs(fraction) * 0.55f
          val sx = (x + dirX * fraction * maxReachPx).roundToInt().coerceIn(0, w - 1)
          val sy = (y + dirY * fraction * maxReachPx).roundToInt().coerceIn(0, h - 1)
          val sample = srcPixels[sy * w + sx]
          accA += Color.alpha(sample) * weight
          accR += Color.red(sample) * weight
          accG += Color.green(sample) * weight
          accB += Color.blue(sample) * weight
          totalWeight += weight
        }
        val a = (accA / totalWeight).roundToInt().coerceIn(0, 255)
        val r = (accR / totalWeight).roundToInt().coerceIn(0, 255)
        val g = (accG / totalWeight).roundToInt().coerceIn(0, 255)
        val b = (accB / totalWeight).roundToInt().coerceIn(0, 255)
        blurredPixels[rowOffset + x] = Color.argb(a, r, g, b)
      }
    }

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(blurredPixels, 0, w, 0, 0, w, h)

    // Layer directional ghost trails opposite the motion direction
    val ghostAmount = params.ghostTrail.coerceIn(0f, 1f)
    if (ghostAmount > 0.03f) {
      val canvas = Canvas(out)
      val ghostShift = min(w, h) * 0.08f * (0.4f + ghostAmount * 0.8f)
      val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

      val offsets = listOf(-1.0f, -0.5f, 0.45f)
      offsets.forEachIndexed { idx, factor ->
        val alphaFrac = ghostAmount * (0.36f - idx * 0.08f)
        ghostPaint.alpha = (alphaFrac * 255f).roundToInt().coerceIn(16, 160)
        canvas.drawBitmap(source, dirX * ghostShift * factor, dirY * ghostShift * factor, ghostPaint)
      }
    }
    return out
  }

  private fun applyMultipleExposure(source: Bitmap, params: TemplateParameters): Bitmap {
    val w = source.width
    val h = source.height
    val basePixels = IntArray(w * h)
    source.getPixels(basePixels, 0, w, 0, 0, w, h)

    val shiftX = w * 0.14f * params.exposureOffset.coerceIn(0.02f, 0.9f)
    val shiftY = h * 0.06f * params.exposureOffset.coerceIn(0.02f, 0.9f)
    val scale = params.exposureScale.coerceIn(0.8f, 1.45f)
    val opacity = params.exposureOpacity.coerceIn(0.15f, 0.95f)

    // Render 2nd transformed exposure onto its own bitmap so blend math is exact on every device & JVM
    val secondLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val secondCanvas = Canvas(secondLayer)
    val matrix2 = Matrix().apply {
      postTranslate(-w / 2f, -h / 2f)
      postScale(if (params.exposureMirrorSecond) -scale else scale, scale)
      postTranslate(w / 2f + shiftX, h / 2f - shiftY)
    }
    secondCanvas.drawBitmap(source, matrix2, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    val layerPixels = IntArray(w * h)
    secondLayer.getPixels(layerPixels, 0, w, 0, 0, w, h)
    secondLayer.recycle()

    val outPixels = IntArray(w * h)
    val mode = params.exposureBlendMode

    for (i in 0 until w * h) {
      val bc = basePixels[i]
      val lc = layerPixels[i]
      val la = Color.alpha(lc) / 255f

      val ar = (Color.red(bc) / 255f) * 0.84f
      val ag = (Color.green(bc) / 255f) * 0.82f
      val ab = (Color.blue(bc) / 255f) * 0.90f

      val br = ((Color.red(lc) / 255f) * 1.08f).coerceIn(0f, 1f) * la
      val bg = ((Color.green(lc) / 255f) * 0.90f).coerceIn(0f, 1f) * la
      val bb = ((Color.blue(lc) / 255f) * 1.15f).coerceIn(0f, 1f) * la

      val mr = blendChannel(ar, br, mode)
      val mg = blendChannel(ag, bg, mode)
      val mb = blendChannel(ab, bb, mode)

      val fr = ((ar + (mr - ar) * opacity) * 255f).roundToInt().coerceIn(0, 255)
      val fg = ((ag + (mg - ag) * opacity) * 255f).roundToInt().coerceIn(0, 255)
      val fb = ((ab + (mb - ab) * opacity) * 255f).roundToInt().coerceIn(0, 255)
      outPixels[i] = Color.argb(Color.alpha(bc), fr, fg, fb)
    }

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(outPixels, 0, w, 0, 0, w, h)
    return out
  }

  private fun blendChannel(a: Float, b: Float, mode: ExposureBlendMode): Float {
    return when (mode) {
      ExposureBlendMode.SCREEN -> 1f - (1f - a) * (1f - b)
      ExposureBlendMode.LIGHTEN -> max(a, b)
      ExposureBlendMode.OVERLAY -> {
        if (a < 0.5f) 2f * a * b else 1f - 2f * (1f - a) * (1f - b)
      }
      ExposureBlendMode.SOFT_ADD -> (a + b * 0.72f).coerceIn(0f, 1f)
    }
  }

  private fun applyMemoriesFrame(source: Bitmap, params: TemplateParameters): Bitmap {
    val w = source.width
    val h = source.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)

    val warmth = params.memoriesWarmth.coerceIn(0f, 1f)
    val warmMatrix =
      ColorMatrix().apply {
        setSaturation(0.92f - warmth * 0.14f)
        postConcat(
          ColorMatrix().apply {
            setScale(1f + warmth * 0.10f, 1f + warmth * 0.04f, 1f - warmth * 0.10f, 1f)
          }
        )
      }
    val photoPaint =
      Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(warmMatrix)
      }

    when (params.memoriesFrameStyle) {
      MemoriesFrameStyle.POLAROID -> {
        // Dark obsidian textured mat behind tilted/framed Polaroid card
        canvas.drawColor(Color.rgb(18, 15, 28))
        val padX = w * 0.08f
        val padTop = h * 0.06f
        val padBottom = h * 0.07f
        val cardRect = RectF(padX, padTop, w - padX, h - padBottom)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 0, 0, 0) }
        canvas.drawRoundRect(
          RectF(cardRect.left + 8f, cardRect.top + 10f, cardRect.right + 8f, cardRect.bottom + 12f),
          w * 0.018f,
          w * 0.018f,
          shadowPaint,
        )
        val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(246, 241, 232) }
        canvas.drawRoundRect(cardRect, w * 0.016f, w * 0.016f, paperPaint)

        val innerPad = cardRect.width() * 0.065f
        val photoRect =
          RectF(
            cardRect.left + innerPad,
            cardRect.top + innerPad,
            cardRect.right - innerPad,
            cardRect.bottom - cardRect.height() * 0.21f,
          )
        drawCenterCropped(canvas, source, photoRect, photoPaint)
        drawMemoriesTypography(
          canvas = canvas,
          title = params.memoriesTitle,
          dateText = params.memoriesDateText,
          centerX = cardRect.centerX(),
          titleY = photoRect.bottom + cardRect.height() * 0.088f,
          dateY = photoRect.bottom + cardRect.height() * 0.145f,
          widthRef = cardRect.width(),
          titleColor = Color.rgb(34, 28, 42),
          dateColor = Color.rgb(112, 98, 126),
        )
      }

      MemoriesFrameStyle.FILM_STRIP -> {
        canvas.drawColor(Color.rgb(14, 13, 19))
        val stripMarginX = w * 0.09f
        val photoRect = RectF(stripMarginX, h * 0.10f, w - stripMarginX, h * 0.78f)
        drawCenterCropped(canvas, source, photoRect, photoPaint)

        // Sprocket holes down both edges
        val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(225, 214, 245) }
        val holeW = w * 0.036f
        val holeH = h * 0.028f
        val count = 10
        val stepY = h.toFloat() / count
        for (i in 0 until count) {
          val cy = i * stepY + stepY * 0.5f
          canvas.drawRoundRect(
            RectF(w * 0.028f, cy - holeH / 2f, w * 0.028f + holeW, cy + holeH / 2f),
            4f,
            4f,
            holePaint,
          )
          canvas.drawRoundRect(
            RectF(w - w * 0.028f - holeW, cy - holeH / 2f, w - w * 0.028f, cy + holeH / 2f),
            4f,
            4f,
            holePaint,
          )
        }
        drawMemoriesTypography(
          canvas = canvas,
          title = params.memoriesTitle,
          dateText = "${params.memoriesDateText}  •  35MM",
          centerX = w / 2f,
          titleY = h * 0.86f,
          dateY = h * 0.92f,
          widthRef = w.toFloat(),
          titleColor = Color.rgb(245, 236, 214),
          dateColor = Color.rgb(212, 168, 245),
        )
      }

      MemoriesFrameStyle.SCRAPBOOK -> {
        val bgGrad =
          Paint().apply {
            shader =
              LinearGradient(
                0f,
                0f,
                w.toFloat(),
                h.toFloat(),
                intArrayOf(Color.rgb(42, 26, 66), Color.rgb(22, 18, 34), Color.rgb(54, 28, 58)),
                null,
                Shader.TileMode.CLAMP,
              )
          }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgGrad)

        val frameRect = RectF(w * 0.07f, h * 0.07f, w * 0.93f, h * 0.78f)
        val borderPaint =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(3f, w * 0.008f)
            color = Color.rgb(224, 170, 255)
          }
        drawCenterCropped(canvas, source, frameRect, photoPaint)
        canvas.drawRect(frameRect, borderPaint)

        // Subtle warm highlight bloom
        val glowPaint =
          Paint().apply {
            shader =
              RadialGradient(
                frameRect.left + frameRect.width() * 0.25f,
                frameRect.top + frameRect.height() * 0.2f,
                frameRect.width() * 0.7f,
                Color.argb((warmth * 75).roundToInt(), 255, 204, 153),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
              )
          }
        canvas.drawRect(frameRect, glowPaint)

        drawMemoriesTypography(
          canvas = canvas,
          title = params.memoriesTitle,
          dateText = params.memoriesDateText,
          centerX = w / 2f,
          titleY = h * 0.865f,
          dateY = h * 0.925f,
          widthRef = w.toFloat(),
          titleColor = Color.rgb(248, 244, 255),
          dateColor = Color.rgb(224, 170, 255),
        )
      }
    }
    return out
  }

  private fun drawCenterCropped(
    canvas: Canvas,
    source: Bitmap,
    dest: RectF,
    paint: Paint,
  ) {
    val srcRatio = source.width.toFloat() / source.height.toFloat()
    val dstRatio = dest.width() / dest.height()
    val srcRect: Rect =
      if (srcRatio > dstRatio) {
        val cropW = (source.height * dstRatio).roundToInt().coerceIn(1, source.width)
        val left = (source.width - cropW) / 2
        Rect(left, 0, left + cropW, source.height)
      } else {
        val cropH = (source.width / dstRatio).roundToInt().coerceIn(1, source.height)
        val top = (source.height - cropH) / 3
        Rect(0, top, source.width, top + cropH)
      }
    canvas.drawBitmap(source, srcRect, dest, paint)
  }

  private fun drawMemoriesTypography(
    canvas: Canvas,
    title: String,
    dateText: String,
    centerX: Float,
    titleY: Float,
    dateY: Float,
    widthRef: Float,
    titleColor: Int,
    dateColor: Int,
  ) {
    val cleanTitle = title.trim().ifBlank { "VYNOZA Memory" }.take(36)
    val cleanDate = dateText.trim().ifBlank { TemplateParameters.defaultDateStamp() }.take(28)

    val titlePaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = titleColor
        textAlign = Paint.Align.CENTER
        textSize = max(20f, widthRef * 0.056f)
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
      }
    val datePaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dateColor
        textAlign = Paint.Align.CENTER
        textSize = max(14f, widthRef * 0.034f)
        letterSpacing = 0.14f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
      }
    canvas.drawText(cleanTitle, centerX, titleY, titlePaint)
    canvas.drawText(cleanDate.uppercase(), centerX, dateY, datePaint)
  }

  private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
    var sample = 1
    while (max(width / sample, height / sample) > maxDim * 2) {
      sample *= 2
    }
    return sample
  }

  private fun scaleDownIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val maxEdge = max(bitmap.width, bitmap.height)
    if (maxEdge <= maxDimension) return bitmap
    val ratio = maxDimension.toFloat() / maxEdge.toFloat()
    val targetW = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
    val targetH = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
  }

  private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
  }

  private fun hasValidImageSignature(header: ByteArray, length: Int): Boolean {
    if (length < 2) return false
    // JPEG: FF D8 FF
    if (length >= 3 &&
      header[0] == 0xFF.toByte() &&
      header[1] == 0xD8.toByte() &&
      header[2] == 0xFF.toByte()
    ) {
      return true
    }
    // PNG: 89 50 4E 47
    if (length >= 4 &&
      header[0] == 0x89.toByte() &&
      header[1] == 0x50.toByte() &&
      header[2] == 0x4E.toByte() &&
      header[3] == 0x47.toByte()
    ) {
      return true
    }
    // GIF: GIF8
    if (length >= 4 &&
      header[0] == 'G'.code.toByte() &&
      header[1] == 'I'.code.toByte() &&
      header[2] == 'F'.code.toByte() &&
      header[3] == '8'.code.toByte()
    ) {
      return true
    }
    // WebP: RIFF....WEBP
    if (length >= 12 &&
      header[0] == 'R'.code.toByte() &&
      header[1] == 'I'.code.toByte() &&
      header[2] == 'F'.code.toByte() &&
      header[3] == 'F'.code.toByte() &&
      header[8] == 'W'.code.toByte() &&
      header[9] == 'E'.code.toByte() &&
      header[10] == 'B'.code.toByte() &&
      header[11] == 'P'.code.toByte()
    ) {
      return true
    }
    // BMP: BM
    if (header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) {
      return true
    }
    // ISO-BMFF (HEIF / HEIC / AVIF): ....ftyp
    if (length >= 8 &&
      header[4] == 'f'.code.toByte() &&
      header[5] == 't'.code.toByte() &&
      header[6] == 'y'.code.toByte() &&
      header[7] == 'p'.code.toByte()
    ) {
      return true
    }
    return false
  }
}
