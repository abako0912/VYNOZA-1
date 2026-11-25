package com.vynoza.app.data.templates

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.vynoza.app.core.templates.TemplateParameters
import com.vynoza.app.core.templates.VynozaTemplateId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume

interface TemplateVideoExportDriver {
  suspend fun transformToMp4(
    context: Context,
    editedMediaItem: EditedMediaItem,
    outputFile: File,
    onProgress: (Float) -> Unit,
  ): Result<File>
}

@OptIn(UnstableApi::class)
class Media3TransformerExportDriver : TemplateVideoExportDriver {
  override suspend fun transformToMp4(
    context: Context,
    editedMediaItem: EditedMediaItem,
    outputFile: File,
    onProgress: (Float) -> Unit,
  ): Result<File> =
    withContext(Dispatchers.Main.immediate) {
      suspendCancellableCoroutine { continuation ->
        val mainHandler = Handler(Looper.getMainLooper())
        val progressHolder = ProgressHolder()
        var finished = false
        var transformerRef: Transformer? = null

        val pollRunnable =
          object : Runnable {
            override fun run() {
              if (finished) return
              val t = transformerRef ?: return
              val state = t.getProgress(progressHolder)
              if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                onProgress((progressHolder.progress / 100f).coerceIn(0f, 0.99f))
              }
              mainHandler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
            }
          }

        val listener =
          object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
              if (finished) return
              finished = true
              mainHandler.removeCallbacks(pollRunnable)
              if (!outputFile.exists() || outputFile.length() <= 0L) {
                TemplateMediaStorage.safeDelete(outputFile)
                if (continuation.isActive) {
                  continuation.resume(
                    Result.failure(IOException("Video export completed without valid output data."))
                  )
                }
                return
              }
              onProgress(1f)
              if (continuation.isActive) {
                continuation.resume(Result.success(outputFile))
              }
            }

            override fun onError(
              composition: Composition,
              exportResult: ExportResult,
              exportException: ExportException,
            ) {
              if (finished) return
              finished = true
              mainHandler.removeCallbacks(pollRunnable)
              TemplateMediaStorage.safeDelete(outputFile)
              val message =
                exportException.localizedMessage?.takeIf { it.isNotBlank() }
                  ?: "Video rendering failed (${exportException.errorCodeName})."
              if (continuation.isActive) {
                continuation.resume(Result.failure(IOException(message, exportException)))
              }
            }
          }

        try {
          val transformer =
            Transformer.Builder(context.applicationContext)
              .setLooper(Looper.getMainLooper())
              .addListener(listener)
              .build()
          transformerRef = transformer

          continuation.invokeOnCancellation {
            finished = true
            mainHandler.removeCallbacks(pollRunnable)
            mainHandler.post {
              runCatching { transformer.cancel() }
              TemplateMediaStorage.safeDelete(outputFile)
            }
          }

          onProgress(0.02f)
          transformer.start(editedMediaItem, outputFile.absolutePath)
          mainHandler.postDelayed(pollRunnable, PROGRESS_POLL_INTERVAL_MS)
        } catch (e: CancellationException) {
          finished = true
          mainHandler.removeCallbacks(pollRunnable)
          TemplateMediaStorage.safeDelete(outputFile)
          throw e
        } catch (e: Exception) {
          finished = true
          mainHandler.removeCallbacks(pollRunnable)
          TemplateMediaStorage.safeDelete(outputFile)
          if (continuation.isActive) {
            continuation.resume(
              Result.failure(
                IOException(e.localizedMessage ?: "Unable to start Media3 video export.", e)
              )
            )
          }
        }
      }
    }

  private companion object {
    const val PROGRESS_POLL_INTERVAL_MS = 140L
  }
}

@OptIn(UnstableApi::class)
class VideoTemplateProcessor(
  private val exportDriver: TemplateVideoExportDriver = Media3TransformerExportDriver(),
) {

  suspend fun exportVideoWithTemplate(
    context: Context,
    sourceUri: Uri,
    estimatedSizeBytes: Long?,
    templateId: VynozaTemplateId,
    params: TemplateParameters,
    onProgress: (Float) -> Unit,
  ): Result<File> {
    var outputFile: File? = null
    return try {
      TemplateMediaStorage.ensureEnoughStorageForVideo(context, estimatedSizeBytes).getOrThrow()
      verifyReadableUri(context, sourceUri).getOrThrow()

      val effectsList = buildSafeVideoEffects(templateId, params, isPhotoMotionExport = false)
      val mediaItem = MediaItem.fromUri(sourceUri)
      val editedMediaItem =
        EditedMediaItem.Builder(mediaItem)
          .setEffects(Effects(/* audioProcessors= */ emptyList(), effectsList))
          .build()

      outputFile =
        TemplateMediaStorage.createTempOutputFile(context, "vynoza_${templateId.routeArg}", "mp4")
      exportDriver.transformToMp4(context, editedMediaItem, outputFile, onProgress)
    } catch (e: CancellationException) {
      TemplateMediaStorage.safeDelete(outputFile)
      throw e
    } catch (e: Exception) {
      TemplateMediaStorage.safeDelete(outputFile)
      Result.failure(e)
    }
  }

  suspend fun exportPhotoAsMotionVideo(
    context: Context,
    sourcePhotoUri: Uri,
    templateId: VynozaTemplateId,
    params: TemplateParameters,
    durationMs: Long = DEFAULT_PHOTO_VIDEO_DURATION_MS,
    onProgress: (Float) -> Unit,
  ): Result<File> {
    var tempImageFile: File? = null
    var outputVideoFile: File? = null
    return try {
      TemplateMediaStorage.ensureEnoughStorageForVideo(context, 12L * 1024L * 1024L).getOrThrow()
      onProgress(0.08f)

      // Pre-render the photo template (for Memories, keep the unframed warm source so OverlayEffect frames cleanly)
      val baseBitmap =
        PhotoTemplateProcessor.decodeSampledBitmap(context, sourcePhotoUri, 1080).getOrThrow()
      val stagedBitmap =
        try {
          if (templateId == VynozaTemplateId.MEMORIES) {
            baseBitmap
          } else {
            PhotoTemplateProcessor.applyTemplate(baseBitmap, templateId, params).also {
              if (it !== baseBitmap) baseBitmap.recycle()
            }
          }
        } catch (e: Exception) {
          if (!baseBitmap.isRecycled) baseBitmap.recycle()
          throw e
        }

      tempImageFile =
        TemplateMediaStorage.createTempOutputFile(context, "vynoza_stage_${templateId.routeArg}", "jpg")
      withContext(Dispatchers.IO) {
        try {
          FileOutputStream(tempImageFile).use { out ->
            if (!stagedBitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)) {
              throw IOException("Failed to prepare intermediate frame for video encoding.")
            }
          }
        } finally {
          if (!stagedBitmap.isRecycled) stagedBitmap.recycle()
        }
      }

      onProgress(0.22f)
      val imageMediaItem =
        MediaItem.Builder()
          .setUri(Uri.fromFile(tempImageFile))
          .setImageDurationMs(durationMs.coerceIn(2500L, 8000L))
          .build()

      val videoEffects =
        buildList<Effect> {
          // Ensure clean portrait 720p encoding target so hardware codecs across devices accept input
          add(Presentation.createForShortSide(720))
          addAll(TemplateVideoEffects.buildVideoEffects(templateId, params, isPhotoMotionExport = true))
        }

      val editedMediaItem =
        EditedMediaItem.Builder(imageMediaItem)
          .setFrameRate(30)
          .setEffects(Effects(emptyList(), videoEffects))
          .build()

      outputVideoFile =
        TemplateMediaStorage.createTempOutputFile(context, "vynoza_clip_${templateId.routeArg}", "mp4")

      exportDriver.transformToMp4(context, editedMediaItem, outputVideoFile) { p ->
        onProgress(0.22f + p * 0.78f)
      }
    } catch (e: CancellationException) {
      TemplateMediaStorage.safeDelete(outputVideoFile)
      throw e
    } catch (e: Exception) {
      TemplateMediaStorage.safeDelete(outputVideoFile)
      Result.failure(e)
    } finally {
      TemplateMediaStorage.safeDelete(tempImageFile)
    }
  }

  private fun buildSafeVideoEffects(
    templateId: VynozaTemplateId,
    params: TemplateParameters,
    isPhotoMotionExport: Boolean,
  ): List<Effect> {
    return buildList {
      // Cap short side at 720p so 4K phone videos encode reliably without hitting codec limits
      add(Presentation.createForShortSide(720))
      addAll(TemplateVideoEffects.buildVideoEffects(templateId, params, isPhotoMotionExport))
    }
  }

  private suspend fun verifyReadableUri(context: Context, uri: Uri): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
          if (stream.read() == -1) {
            throw IOException("The selected video file is empty.")
          }
        } ?: throw IOException("Unable to read the selected video file.")
        Unit
      }
    }

  private companion object {
    const val DEFAULT_PHOTO_VIDEO_DURATION_MS = 4500L
  }
}
