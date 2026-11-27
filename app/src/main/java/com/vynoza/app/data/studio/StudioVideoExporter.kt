package com.vynoza.app.data.studio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.vynoza.app.core.studio.AudioMixSettings
import com.vynoza.app.core.studio.EditorMediaClip
import com.vynoza.app.core.studio.EditorMediaKind
import com.vynoza.app.core.studio.MusicTrack
import com.vynoza.app.core.studio.MusicTrimSelection
import com.vynoza.app.core.studio.StudioEffectCatalog
import com.vynoza.app.core.templates.TemplateParameters
import com.vynoza.app.data.music.AudiusLicenseEvaluator
import com.vynoza.app.data.music.AudiusMusicRepository
import com.vynoza.app.data.music.MusicLicenseDecision
import com.vynoza.app.data.templates.CinematicPortraitGlEffect
import com.vynoza.app.data.templates.CloneEchoGlEffect
import com.vynoza.app.data.templates.MotionBlurGlEffect
import com.vynoza.app.data.templates.MultipleExposureGlEffect
import com.vynoza.app.data.templates.TemplateMediaStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class StudioExportRequest(
  val clips: List<EditorMediaClip>,
  val effectPresetId: String = StudioEffectCatalog.EFFECT_NONE_ID,
  val selectedMusic: MusicTrimSelection? = null,
  val audioMix: AudioMixSettings = AudioMixSettings(),
  val outputPrefix: String = "vynoza_edit",
)

data class PlannedClipMix(
  val clipId: String,
  val kind: EditorMediaKind,
  val trimStartMs: Long,
  val trimEndMs: Long,
  val includeOriginalAudio: Boolean,
  val originalAudioVolume: Float,
)

data class StudioExportPlan(
  val clips: List<PlannedClipMix>,
  val totalVideoDurationMs: Long,
  val includeBackgroundMusic: Boolean,
  val musicStartMs: Long,
  val musicEndMs: Long,
  val musicSyncOffsetMs: Long,
  val loopMusic: Boolean,
  val musicVolume: Float,
  val licenseDecision: MusicLicenseDecision,
)

interface StudioVideoExportDriver {
  suspend fun exportComposition(
    context: Context,
    composition: Composition,
    outputFile: File,
    onProgress: (Float) -> Unit,
  ): Result<File>
}

interface TransientAudioFetcher {
  suspend fun downloadTransientAudio(
    context: Context,
    track: MusicTrack,
    targetFile: File,
  ): Result<File>
}

interface ClipMediaInspector {
  fun hasAudioTrack(context: Context, uri: Uri): Boolean
  fun verifyOutputMp4Valid(context: Context, outputFile: File): Boolean
}

@OptIn(UnstableApi::class)
class Media3StudioExportDriver : StudioVideoExportDriver {
  override suspend fun exportComposition(
    context: Context,
    composition: Composition,
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
                onProgress((progressHolder.progress / 100f).coerceIn(0.05f, 0.98f))
              }
              mainHandler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
            }
          }

        val listener =
          object : Transformer.Listener {
            override fun onCompleted(comp: Composition, exportResult: ExportResult) {
              if (finished) return
              finished = true
              mainHandler.removeCallbacks(pollRunnable)
              if (!outputFile.exists() || outputFile.length() <= 0L) {
                TemplateMediaStorage.safeDelete(outputFile)
                if (continuation.isActive) {
                  continuation.resume(
                    Result.failure(IOException("Export finished but produced an empty MP4 file."))
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
              comp: Composition,
              exportResult: ExportResult,
              exportException: ExportException,
            ) {
              if (finished) return
              finished = true
              mainHandler.removeCallbacks(pollRunnable)
              TemplateMediaStorage.safeDelete(outputFile)
              val rawMessage = exportException.localizedMessage?.takeIf { it.isNotBlank() }
              val friendly =
                when {
                  rawMessage?.contains("codec", ignoreCase = true) == true ->
                    "Device codec error while encoding (${exportException.errorCodeName}). Try trimming shorter or choosing Normal effect."
                  else -> rawMessage ?: "Video export failed (${exportException.errorCodeName})."
                }
              if (continuation.isActive) {
                continuation.resume(Result.failure(IOException(friendly, exportException)))
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

          onProgress(0.08f)
          transformer.start(composition, outputFile.absolutePath)
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
              Result.failure(IOException(e.localizedMessage ?: "Unable to start Media3 video export.", e))
            )
          }
        }
      }
    }

  private companion object {
    const val PROGRESS_POLL_INTERVAL_MS = 120L
  }
}

class OkHttpTransientAudiusAudioFetcher(
  private val apiKey: String? = AudiusMusicRepository.resolveConfiguredApiKey(),
  private val httpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .followRedirects(true)
      .build(),
) : TransientAudioFetcher {
  override suspend fun downloadTransientAudio(
    context: Context,
    track: MusicTrack,
    targetFile: File,
  ): Result<File> =
    withContext(Dispatchers.IO) {
      runCatching {
        val builder =
          Request.Builder()
            .url(track.streamUrl)
            .header("User-Agent", "VYNOZA-Android")
            .get()
        if (!apiKey.isNullOrBlank()) {
          builder.header("x-api-key", apiKey)
        }
        httpClient.newCall(builder.build()).execute().use { response ->
          if (!response.isSuccessful) {
            throw IOException("Unable to fetch licensed Audius audio stream (HTTP ${response.code}).")
          }
          val body = response.body ?: throw IOException("Empty Audius audio stream response.")
          targetFile.parentFile?.mkdirs()
          body.byteStream().use { input ->
            FileOutputStream(targetFile).use { output ->
              input.copyTo(output)
            }
          }
        }
        if (!targetFile.exists() || targetFile.length() <= 0L) {
          throw IOException("Downloaded Audius audio buffer is empty.")
        }
        targetFile
      }
    }
}

object DefaultClipMediaInspector : ClipMediaInspector {
  override fun hasAudioTrack(context: Context, uri: Uri): Boolean {
    val extractor = MediaExtractor()
    return try {
      if (uri.scheme == "file" && uri.path != null) {
        extractor.setDataSource(uri.path!!)
      } else {
        extractor.setDataSource(context, uri, null)
      }
      (0 until extractor.trackCount).any { idx ->
        val mime = extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME).orEmpty()
        mime.startsWith("audio/", ignoreCase = true)
      }
    } catch (_: Exception) {
      // Fall back to keeping audio attempt on content URIs if extractor probing fails
      true
    } finally {
      runCatching { extractor.release() }
    }
  }

  override fun verifyOutputMp4Valid(context: Context, outputFile: File): Boolean {
    if (!outputFile.exists() || outputFile.length() <= 0L) return false
    val duration = StudioMediaProcessor.probeVideoDurationMs(context, Uri.fromFile(outputFile))
    return duration != null && duration > 0L
  }
}

@OptIn(UnstableApi::class)
class StudioVideoExporter(
  private val exportDriver: StudioVideoExportDriver = Media3StudioExportDriver(),
  private val audioFetcher: TransientAudioFetcher = OkHttpTransientAudiusAudioFetcher(),
  private val mediaInspector: ClipMediaInspector = DefaultClipMediaInspector,
) {

  fun buildExportPlan(
    request: StudioExportRequest,
    clipHasAudioProvider: (EditorMediaClip) -> Boolean,
  ): Result<StudioExportPlan> =
    runCatching {
      require(request.clips.isNotEmpty()) { "Select or record at least one clip before exporting." }
      val mix = request.audioMix.normalized()

      val plannedClips =
        request.clips.map { clip ->
          when (clip.kind) {
            EditorMediaKind.PHOTO -> {
              val dur = clip.durationMs.coerceIn(1_000L, 15_000L)
              PlannedClipMix(
                clipId = clip.id,
                kind = EditorMediaKind.PHOTO,
                trimStartMs = 0L,
                trimEndMs = dur,
                includeOriginalAudio = false,
                originalAudioVolume = 0f,
              )
            }

            EditorMediaKind.VIDEO -> {
              val safeStart = clip.trimStartMs.coerceIn(0L, (clip.durationMs - 500L).coerceAtLeast(0L))
              val safeEnd = clip.trimEndMs.coerceIn((safeStart + 500L).coerceAtMost(clip.durationMs), clip.durationMs)
              val sourceHasAudio = clipHasAudioProvider(clip)
              val keepOriginalAudio = sourceHasAudio && mix.originalAudioVolume > 0.001f
              PlannedClipMix(
                clipId = clip.id,
                kind = EditorMediaKind.VIDEO,
                trimStartMs = safeStart,
                trimEndMs = safeEnd,
                includeOriginalAudio = keepOriginalAudio,
                originalAudioVolume = if (keepOriginalAudio) mix.originalAudioVolume else 0f,
              )
            }
          }
        }

      val totalDurationMs =
        plannedClips.sumOf { (it.trimEndMs - it.trimStartMs).coerceAtLeast(500L) }

      val selectedMusic = request.selectedMusic
      val licenseDecision = AudiusLicenseEvaluator.evaluate(selectedMusic?.track)
      val wantsMusic = selectedMusic != null && mix.musicVolume > 0.001f

      if (wantsMusic && !licenseDecision.canEmbed) {
        throw IllegalStateException(
          "Cannot embed \"${selectedMusic.track.title}\": ${licenseDecision.explanation}"
        )
      }

      val includeMusic = wantsMusic && licenseDecision.canEmbed && mix.syncOffsetMs < totalDurationMs
      val musicStartMs = selectedMusic?.clampedStartMs ?: 0L
      val rawMusicEndMs = selectedMusic?.endMs ?: 0L
      val musicWindowMs = (rawMusicEndMs - musicStartMs).coerceAtLeast(500L)
      val remainingAfterOffsetMs = (totalDurationMs - mix.syncOffsetMs).coerceAtLeast(500L)
      val loopMusic = includeMusic && remainingAfterOffsetMs > musicWindowMs
      val effectiveMusicEndMs =
        if (!includeMusic) {
          0L
        } else if (loopMusic) {
          rawMusicEndMs
        } else {
          minOf(rawMusicEndMs, musicStartMs + remainingAfterOffsetMs)
        }

      StudioExportPlan(
        clips = plannedClips,
        totalVideoDurationMs = totalDurationMs,
        includeBackgroundMusic = includeMusic,
        musicStartMs = musicStartMs,
        musicEndMs = effectiveMusicEndMs,
        musicSyncOffsetMs = mix.syncOffsetMs,
        loopMusic = loopMusic,
        musicVolume = mix.musicVolume,
        licenseDecision = licenseDecision,
      )
    }

  suspend fun exportEditedVideo(
    context: Context,
    request: StudioExportRequest,
    onProgress: (Float) -> Unit = {},
  ): Result<File> {
    var transientAudioFile: File? = null
    var outputFile: File? = null
    return try {
      val estimatedInputBytes =
        withContext(Dispatchers.IO) {
          request.clips.sumOf { clip ->
            runCatching {
              context.contentResolver.openAssetFileDescriptor(Uri.parse(clip.uriString), "r")?.use {
                it.length.coerceAtLeast(0L)
              } ?: 0L
            }.getOrDefault(0L)
          }.takeIf { it > 0L }
        }
      TemplateMediaStorage.ensureEnoughStorageForVideo(context, estimatedInputBytes).getOrThrow()

      withContext(Dispatchers.IO) {
        request.clips.forEach { clip ->
          if (!StudioDraftMediaHelper.isUriReadable(context, clip.uriString)) {
            throw IOException("Media file \"${clip.displayName}\" is missing or cannot be opened.")
          }
        }
      }

      val plan =
        withContext(Dispatchers.IO) {
          buildExportPlan(request) { clip ->
            mediaInspector.hasAudioTrack(context, Uri.parse(clip.uriString))
          }.getOrThrow()
        }

      onProgress(0.03f)
      val musicFileUri: Uri? =
        if (plan.includeBackgroundMusic && request.selectedMusic != null) {
          val tempAudio =
            StudioFilePaths.createTempCameraFile(context, "audius_mix_tmp", "mp3")
          transientAudioFile = tempAudio
          audioFetcher.downloadTransientAudio(context, request.selectedMusic.track, tempAudio).getOrThrow()
          Uri.fromFile(tempAudio)
        } else {
          null
        }

      onProgress(0.07f)
      val composition = buildMedia3Composition(request, plan, musicFileUri)
      outputFile =
        StudioFilePaths.createPersistentStudioFile(context, request.outputPrefix, "mp4")

      val exportResult =
        exportDriver.exportComposition(context, composition, outputFile) { p ->
          onProgress((0.07f + p * 0.93f).coerceIn(0.07f, 1f))
        }

      exportResult.onSuccess { exported ->
        val valid =
          withContext(Dispatchers.IO) {
            mediaInspector.verifyOutputMp4Valid(context, exported)
          }
        if (!valid) {
          TemplateMediaStorage.safeDelete(exported)
          return Result.failure(IOException("Exported MP4 file is unreadable or has zero duration."))
        }
      }.onFailure {
        TemplateMediaStorage.safeDelete(outputFile)
      }

      exportResult
    } catch (e: CancellationException) {
      TemplateMediaStorage.safeDelete(outputFile)
      throw e
    } catch (e: Exception) {
      TemplateMediaStorage.safeDelete(outputFile)
      Result.failure(e)
    } finally {
      // Never retain downloaded Audius audio files beyond active mixing
      TemplateMediaStorage.safeDelete(transientAudioFile)
    }
  }

  internal fun buildMedia3Composition(
    request: StudioExportRequest,
    plan: StudioExportPlan,
    transientMusicUri: Uri?,
  ): Composition {
    val videoEffects = buildStudioVideoEffects(request.effectPresetId)

    val editedVideoItems =
      request.clips.zip(plan.clips).map { (clip, planned) ->
        val uri = Uri.parse(clip.uriString)
        val audioProcessors: List<AudioProcessor> =
          if (planned.includeOriginalAudio && planned.originalAudioVolume < 0.999f) {
            listOf(VolumeAudioProcessor(planned.originalAudioVolume))
          } else {
            emptyList()
          }

        when (clip.kind) {
          EditorMediaKind.PHOTO -> {
            val mediaItem =
              MediaItem.Builder()
                .setUri(uri)
                .setImageDurationMs(planned.trimEndMs)
                .build()
            EditedMediaItem.Builder(mediaItem)
              .setFrameRate(30)
              .setRemoveAudio(true)
              .setEffects(Effects(emptyList(), videoEffects))
              .build()
          }

          EditorMediaKind.VIDEO -> {
            val mediaItem =
              MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                  MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(planned.trimStartMs)
                    .setEndPositionMs(planned.trimEndMs)
                    .build()
                )
                .build()
            val itemBuilder =
              EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(audioProcessors, videoEffects))
            if (!planned.includeOriginalAudio) {
              itemBuilder.setRemoveAudio(true)
            }
            itemBuilder.build()
          }
        }
      }

    val anyOriginalAudioKept = plan.clips.any { it.includeOriginalAudio }
    val primarySequence =
      if (anyOriginalAudioKept) {
        EditedMediaItemSequence.withAudioAndVideoFrom(editedVideoItems)
      } else {
        EditedMediaItemSequence.withVideoFrom(editedVideoItems)
      }

    if (plan.includeBackgroundMusic && transientMusicUri != null) {
      fun buildMusicItem(startMs: Long, endMs: Long): EditedMediaItem {
        val clipped =
          MediaItem.Builder()
            .setUri(transientMusicUri)
            .setClippingConfiguration(
              MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build()
            )
            .build()
        val processors: List<AudioProcessor> =
          if (plan.musicVolume < 0.999f) {
            listOf(VolumeAudioProcessor(plan.musicVolume))
          } else {
            emptyList()
          }
        return EditedMediaItem.Builder(clipped)
          .setRemoveVideo(true)
          .setEffects(Effects(processors, emptyList()))
          .build()
      }

      val musicSequenceBuilder =
        EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
      if (plan.musicSyncOffsetMs > 0L) {
        musicSequenceBuilder.addGap(plan.musicSyncOffsetMs * 1_000L)
      }

      if (plan.loopMusic) {
        if (plan.musicSyncOffsetMs == 0L) {
          musicSequenceBuilder.addItem(buildMusicItem(plan.musicStartMs, plan.musicEndMs))
          musicSequenceBuilder.setIsLooping(true)
        } else {
          // Avoid setIsLooping(true) when an initial delay gap exists, otherwise Media3 repeats the gap on each loop.
          val windowMs = (plan.musicEndMs - plan.musicStartMs).coerceAtLeast(500L)
          val remainingMs = (plan.totalVideoDurationMs - plan.musicSyncOffsetMs).coerceAtLeast(500L)
          val fullLoops = (remainingMs / windowMs).toInt()
          val tailMs = remainingMs % windowMs
          repeat(fullLoops) {
            musicSequenceBuilder.addItem(buildMusicItem(plan.musicStartMs, plan.musicEndMs))
          }
          if (tailMs > 0L) {
            musicSequenceBuilder.addItem(buildMusicItem(plan.musicStartMs, plan.musicStartMs + tailMs))
          }
        }
      } else {
        musicSequenceBuilder.addItem(buildMusicItem(plan.musicStartMs, plan.musicEndMs))
      }

      return Composition.Builder(primarySequence, musicSequenceBuilder.build()).build()
    }

    return Composition.Builder(primarySequence).build()
  }

  private fun buildStudioVideoEffects(effectPresetId: String): List<Effect> {
    val baseScaling = Presentation.createForShortSide(720)
    val styleEffects: List<Effect> =
      when (effectPresetId) {
        StudioEffectCatalog.EFFECT_NONE_ID -> emptyList()
        "cinematic_noir" ->
          listOf(StudioColorMatrixGlEffect(saturation = 0f, contrast = 1.24f, rBias = 0f, gBias = 0f, bBias = 0f))
        "violet_aura" ->
          listOf(StudioColorMatrixGlEffect(saturation = 1.12f, contrast = 1.06f, rBias = 0.04f, gBias = -0.02f, bBias = 0.09f))
        "golden_hour" ->
          listOf(StudioColorMatrixGlEffect(saturation = 1.12f, contrast = 1.04f, rBias = 0.07f, gBias = 0.03f, bBias = -0.05f))
        "cyber_prism" ->
          listOf(StudioColorMatrixGlEffect(saturation = 1.25f, contrast = 1.14f, rBias = 0.03f, gBias = -0.01f, bBias = 0.08f))
        "vhs_memories" ->
          listOf(StudioColorMatrixGlEffect(saturation = 0.88f, contrast = 0.96f, rBias = 0.05f, gBias = 0.03f, bBias = -0.03f))
        "polaroid_fade" ->
          listOf(StudioColorMatrixGlEffect(saturation = 0.90f, contrast = 0.90f, rBias = 0.06f, gBias = 0.05f, bBias = 0.07f))
        "clone_echo" -> listOf(CloneEchoGlEffect(TemplateParameters()))
        "motion_streak" -> listOf(MotionBlurGlEffect(TemplateParameters()))
        "double_exposure" -> listOf(MultipleExposureGlEffect(TemplateParameters()))
        else -> listOf(CinematicPortraitGlEffect(TemplateParameters()))
      }
    return listOf(baseScaling) + styleEffects
  }
}

@OptIn(UnstableApi::class)
private class StudioColorMatrixGlEffect(
  private val saturation: Float,
  private val contrast: Float,
  private val rBias: Float,
  private val gBias: Float,
  private val bBias: Float,
) : GlEffect {
  override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
    return object : BaseGlShaderProgram(false, 1) {
      private val glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)

      override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

      override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        glProgram.setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        glProgram.setFloatUniform("uSaturation", saturation)
        glProgram.setFloatUniform("uContrast", contrast)
        glProgram.setFloatUniform("uBiasR", rBias)
        glProgram.setFloatUniform("uBiasG", gBias)
        glProgram.setFloatUniform("uBiasB", bBias)
        glProgram.bindAttributesAndUniforms()
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
      }

      override fun release() {
        super.release()
        glProgram.delete()
      }
    }
  }

  private companion object {
    const val VERTEX_SHADER = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""
    const val FRAGMENT_SHADER = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uSaturation;
uniform float uContrast;
uniform float uBiasR;
uniform float uBiasG;
uniform float uBiasB;
varying vec2 vTexSamplingCoord;

void main() {
  vec4 base = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 c = (base.rgb - 0.5) * uContrast + 0.5;
  float luma = dot(c, vec3(0.299, 0.587, 0.114));
  c = vec3(luma) + (c - vec3(luma)) * uSaturation;
  c += vec3(uBiasR, uBiasG, uBiasB);
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), base.a);
}
"""
  }
}
