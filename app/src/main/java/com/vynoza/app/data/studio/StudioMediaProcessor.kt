package com.vynoza.app.data.studio

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
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.media.ExifInterface
import com.vynoza.app.core.studio.EditorMediaClip
import com.vynoza.app.core.studio.EditorMediaKind
import com.vynoza.app.core.studio.StudioEffectCatalog
import com.vynoza.app.core.studio.TextStudioAlignment
import com.vynoza.app.core.studio.TextStudioBackground
import com.vynoza.app.data.templates.TemplateMediaStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object StudioMediaProcessor {
  private const val MIN_PHOTO_FREE_BYTES = 15L * 1024L * 1024L
  private const val MIN_VIDEO_FREE_BYTES = 40L * 1024L * 1024L

  fun checkStorageForRecording(context: Context): Result<Unit> =
    checkFreeBytes(context, MIN_VIDEO_FREE_BYTES, "recording video")

  fun checkStorageForPhoto(context: Context): Result<Unit> =
    checkFreeBytes(context, MIN_PHOTO_FREE_BYTES, "capturing photo")

  private fun checkFreeBytes(context: Context, minBytes: Long, actionLabel: String): Result<Unit> {
    val available =
      try {
        val stat = StatFs(context.filesDir.absolutePath)
        if (stat.totalBytes <= 0L) null else stat.availableBytes
      } catch (_: Exception) {
        null
      }
    return if (available != null && available < minBytes) {
      Result.failure(
        IOException(
          "Insufficient storage (${available / (1024 * 1024)} MB free). " +
            "At least ${minBytes / (1024 * 1024)} MB free is needed for $actionLabel."
        )
      )
    } else {
      Result.success(Unit)
    }
  }

  suspend fun inspectGalleryUris(context: Context, uris: List<Uri>): List<EditorMediaClip> =
    withContext(Dispatchers.IO) {
      uris.mapNotNull { uri ->
        val inspected = TemplateMediaStorage.inspectSelectedMedia(context, uri) ?: return@mapNotNull null
        val kind =
          when (inspected.kind) {
            com.vynoza.app.core.templates.TemplateMediaKind.PHOTO -> EditorMediaKind.PHOTO
            com.vynoza.app.core.templates.TemplateMediaKind.VIDEO -> EditorMediaKind.VIDEO
          }
        val durationMs =
          if (kind == EditorMediaKind.VIDEO) {
            probeVideoDurationMs(context, uri) ?: 5_000L
          } else {
            EditorMediaClip.DEFAULT_PHOTO_CLIP_DURATION_MS
          }
        EditorMediaClip(
          uriString = uri.toString(),
          kind = kind,
          durationMs = durationMs,
          trimStartMs = 0L,
          trimEndMs = durationMs,
          displayName = inspected.displayName,
        )
      }
    }

  fun probeVideoDurationMs(context: Context, uri: Uri): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(context, uri)
      retriever
        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
    } catch (_: Exception) {
      null
    } finally {
      runCatching { retriever.release() }
    }
  }

  fun colorMatrixForEffect(effectPresetId: String): ColorMatrix? {
    return when (effectPresetId) {
      StudioEffectCatalog.EFFECT_NONE_ID -> null
      "cinematic_noir" ->
        ColorMatrix().apply {
          setSaturation(0f)
          val c = 1.24f
          val t = (-0.5f * c + 0.5f) * 255f
          postConcat(
            ColorMatrix(
              floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f,
              )
            )
          )
        }
      "golden_hour" ->
        ColorMatrix(
          floatArrayOf(
            1.14f, 0.04f, 0f, 0f, 14f,
            0f, 1.04f, 0f, 0f, 6f,
            0f, 0f, 0.84f, 0f, -8f,
            0f, 0f, 0f, 1f, 0f,
          )
        )
      "violet_aura" ->
        ColorMatrix(
          floatArrayOf(
            1.08f, 0f, 0.08f, 0f, 8f,
            0f, 0.94f, 0.04f, 0f, -4f,
            0.08f, 0f, 1.18f, 0f, 16f,
            0f, 0f, 0f, 1f, 0f,
          )
        )
      "cyber_prism" ->
        ColorMatrix(
          floatArrayOf(
            1.18f, 0f, 0.12f, 0f, -6f,
            0f, 1.05f, 0.10f, 0f, -4f,
            0.12f, 0.06f, 1.24f, 0f, 12f,
            0f, 0f, 0f, 1f, 0f,
          )
        )
      "vhs_memories" ->
        ColorMatrix(
          floatArrayOf(
            1.06f, 0.06f, 0f, 0f, 10f,
            0.02f, 0.98f, 0f, 0f, 6f,
            0f, 0.04f, 0.82f, 0f, 10f,
            0f, 0f, 0f, 1f, 0f,
          )
        )
      "polaroid_fade" ->
        ColorMatrix(
          floatArrayOf(
            0.96f, 0.04f, 0.02f, 0f, 16f,
            0.02f, 0.96f, 0.02f, 0f, 14f,
            0f, 0.04f, 0.92f, 0f, 18f,
            0f, 0f, 0f, 1f, 0f,
          )
        )
      "clone_echo",
      "motion_streak",
      "double_exposure" ->
        ColorMatrix().apply { setSaturation(1.16f) }
      else -> null
    }
  }

  suspend fun applyEffectToCapturedPhoto(
    sourceFile: File,
    effectPresetId: String,
    outputFile: File,
  ): Result<File> =
    withContext(Dispatchers.IO) {
      runCatching {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) {
          throw IOException("Captured photo file is missing.")
        }
        if (effectPresetId == StudioEffectCatalog.EFFECT_NONE_ID) {
          if (sourceFile.absolutePath != outputFile.absolutePath) {
            sourceFile.copyTo(outputFile, overwrite = true)
          }
          return@runCatching outputFile
        }

        val rawBitmap =
          BitmapFactory.decodeFile(sourceFile.absolutePath)
            ?: throw IOException("Unable to decode captured photo.")
        val oriented = applyExifOrientation(rawBitmap, sourceFile)
        val resultBmp = Bitmap.createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBmp)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        colorMatrixForEffect(effectPresetId)?.let { cm ->
          paint.colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(oriented, 0f, 0f, paint)

        // Add subtle stylized echo/double layer when those presets are selected on photos
        if (effectPresetId == "clone_echo" || effectPresetId == "double_exposure") {
          val overlayPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
              alpha = 96
              colorMatrixForEffect(effectPresetId)?.let { colorFilter = ColorMatrixColorFilter(it) }
            }
          canvas.drawBitmap(oriented, oriented.width * 0.055f, -oriented.height * 0.015f, overlayPaint)
        }

        FileOutputStream(outputFile).use { out ->
          resultBmp.compress(Bitmap.CompressFormat.JPEG, 94, out)
        }
        if (oriented !== rawBitmap) oriented.recycle()
        rawBitmap.recycle()
        resultBmp.recycle()
        outputFile
      }
    }

  suspend fun renderTextStoryToJpeg(
    context: Context,
    text: String,
    background: TextStudioBackground,
    alignment: TextStudioAlignment,
    fontScale: Float,
    outputFile: File,
  ): Result<File> =
    withContext(Dispatchers.IO) {
      runCatching {
        checkStorageForPhoto(context).getOrThrow()
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "Enter some text before creating your Text post." }

        val width = 1080
        val height = 1920
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader =
              LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                background.startColorArgb.toInt(),
                background.endColorArgb.toInt(),
                Shader.TileMode.CLAMP,
              )
          }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val paddingPx = 112
        val textPaint =
          TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = background.textColorArgb.toInt()
            textSize = 68f * fontScale.coerceIn(0.75f, 1.55f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(12f, 0f, 4f, Color.argb(65, 0, 0, 0))
          }
        val layoutAlign =
          when (alignment) {
            TextStudioAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextStudioAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextStudioAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
          }

        val textLayout =
          StaticLayout.Builder
            .obtain(cleanText, 0, cleanText.length, textPaint, width - paddingPx * 2)
            .setAlignment(layoutAlign)
            .setLineSpacing(12f, 1.12f)
            .build()

        canvas.save()
        val topY = ((height - textLayout.height) * 0.48f).coerceAtLeast(140f)
        canvas.translate(paddingPx.toFloat(), topY)
        textLayout.draw(canvas)
        canvas.restore()

        val watermarkPaint =
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(175, Color.red(background.textColorArgb.toInt()), Color.green(background.textColorArgb.toInt()), Color.blue(background.textColorArgb.toInt()))
            textSize = 28f
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.14f
          }
        canvas.drawText("VYNOZA", width / 2f, height - 110f, watermarkPaint)

        FileOutputStream(outputFile).use { out ->
          bmp.compress(Bitmap.CompressFormat.JPEG, 94, out)
        }
        bmp.recycle()
        outputFile
      }
    }

  private fun applyExifOrientation(bitmap: Bitmap, file: File): Bitmap {
    val orientation =
      runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
          ExifInterface.TAG_ORIENTATION,
          ExifInterface.ORIENTATION_NORMAL,
        )
      }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
      else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
  }
}
