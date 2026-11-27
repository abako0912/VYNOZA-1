package com.vynoza.app.data.studio

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Rational
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.vynoza.app.core.studio.CameraFlashMode
import com.vynoza.app.core.studio.CameraLayoutOption
import com.vynoza.app.core.studio.CameraLensFacing
import com.vynoza.app.core.studio.CaptureMode
import java.io.File

data class CameraBindResult(
  val hasFlashUnit: Boolean,
  val hasFrontCamera: Boolean,
  val hasBackCamera: Boolean,
)

sealed interface CameraRecordCallbackEvent {
  data object Started : CameraRecordCallbackEvent
  data class Progress(val elapsedMs: Long) : CameraRecordCallbackEvent
  data class Finalized(
    val outputFile: File?,
    val uri: Uri?,
    val durationMs: Long,
    val errorMessage: String? = null,
  ) : CameraRecordCallbackEvent
}

interface StudioCameraController {
  fun bindCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    captureMode: CaptureMode,
    lensFacing: CameraLensFacing,
    layoutOption: CameraLayoutOption,
    flashMode: CameraFlashMode,
    onBound: (Result<CameraBindResult>) -> Unit,
  )

  fun applyFlashMode(flashMode: CameraFlashMode, captureMode: CaptureMode)

  fun startRecording(
    context: Context,
    outputFile: File,
    withAudio: Boolean,
    onEvent: (CameraRecordCallbackEvent) -> Unit,
  ): Boolean

  fun stopRecording()

  fun capturePhoto(
    context: Context,
    outputFile: File,
    flashMode: CameraFlashMode,
    onComplete: (Result<File>) -> Unit,
  )

  fun release()
}

class CameraXStudioController : StudioCameraController {
  private var cameraProvider: ProcessCameraProvider? = null
  private var boundCamera: Camera? = null
  private var videoCapture: VideoCapture<Recorder>? = null
  private var imageCapture: ImageCapture? = null
  private var activeRecording: Recording? = null

  override fun bindCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    captureMode: CaptureMode,
    lensFacing: CameraLensFacing,
    layoutOption: CameraLayoutOption,
    flashMode: CameraFlashMode,
    onBound: (Result<CameraBindResult>) -> Unit,
  ) {
    if (captureMode == CaptureMode.TEXT) {
      stopRecording()
      cameraProvider?.unbindAll()
      boundCamera = null
      onBound(Result.success(CameraBindResult(hasFlashUnit = false, hasFrontCamera = true, hasBackCamera = true)))
      return
    }

    val mainExecutor = ContextCompat.getMainExecutor(context)
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener(
      {
        try {
          val provider = providerFuture.get()
          cameraProvider = provider

          val hasBack = runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
          val hasFront = runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
          if (!hasBack && !hasFront) {
            onBound(Result.failure(IllegalStateException("No camera hardware detected on this device.")))
            return@addListener
          }

          val targetSelector =
            when {
              lensFacing == CameraLensFacing.FRONT && hasFront -> CameraSelector.DEFAULT_FRONT_CAMERA
              lensFacing == CameraLensFacing.BACK && hasBack -> CameraSelector.DEFAULT_BACK_CAMERA
              hasBack -> CameraSelector.DEFAULT_BACK_CAMERA
              else -> CameraSelector.DEFAULT_FRONT_CAMERA
            }

          val aspectRatioInt =
            when (layoutOption) {
              CameraLayoutOption.FEED_4_5,
              CameraLayoutOption.SQUARE_1_1 -> AspectRatio.RATIO_4_3
              else -> AspectRatio.RATIO_16_9
            }

          val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
          val preview =
            Preview.Builder()
              .setTargetAspectRatio(aspectRatioInt)
              .setTargetRotation(rotation)
              .build()
              .also { it.surfaceProvider = previewView.surfaceProvider }

          val rational =
            when (layoutOption) {
              CameraLayoutOption.SQUARE_1_1 -> Rational(1, 1)
              CameraLayoutOption.FEED_4_5 -> Rational(4, 5)
              else -> Rational(9, 16)
            }
          val viewPort = ViewPort.Builder(rational, rotation).build()

          val groupBuilder = UseCaseGroup.Builder().setViewPort(viewPort).addUseCase(preview)

          if (captureMode == CaptureMode.VIDEO) {
            val recorder =
              Recorder.Builder()
                .setQualitySelector(
                  QualitySelector.from(
                    Quality.HD,
                    androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                  )
                )
                .build()
            val videoCap =
              VideoCapture.Builder(recorder)
                .setTargetRotation(rotation)
                .build()
            videoCapture = videoCap
            imageCapture = null
            groupBuilder.addUseCase(videoCap)
          } else {
            val imgCap =
              ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(aspectRatioInt)
                .setTargetRotation(rotation)
                .setFlashMode(toImageCaptureFlashMode(flashMode))
                .build()
            imageCapture = imgCap
            videoCapture = null
            groupBuilder.addUseCase(imgCap)
          }

          provider.unbindAll()
          val camera = provider.bindToLifecycle(lifecycleOwner, targetSelector, groupBuilder.build())
          boundCamera = camera
          val hasFlash = camera.cameraInfo.hasFlashUnit()
          applyFlashModeInternal(camera, flashMode, captureMode, hasFlash)

          onBound(
            Result.success(
              CameraBindResult(
                hasFlashUnit = hasFlash,
                hasFrontCamera = hasFront,
                hasBackCamera = hasBack,
              )
            )
          )
        } catch (e: Exception) {
          onBound(Result.failure(e))
        }
      },
      mainExecutor,
    )
  }

  override fun applyFlashMode(flashMode: CameraFlashMode, captureMode: CaptureMode) {
    val camera = boundCamera ?: return
    applyFlashModeInternal(camera, flashMode, captureMode, camera.cameraInfo.hasFlashUnit())
  }

  private fun applyFlashModeInternal(
    camera: Camera,
    flashMode: CameraFlashMode,
    captureMode: CaptureMode,
    hasFlash: Boolean,
  ) {
    if (!hasFlash) {
      runCatching { camera.cameraControl.enableTorch(false) }
      return
    }
    if (captureMode == CaptureMode.VIDEO) {
      camera.cameraControl.enableTorch(flashMode == CameraFlashMode.ON)
    } else {
      runCatching { camera.cameraControl.enableTorch(false) }
      imageCapture?.flashMode = toImageCaptureFlashMode(flashMode)
    }
  }

  private fun toImageCaptureFlashMode(flashMode: CameraFlashMode): Int =
    when (flashMode) {
      CameraFlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
      CameraFlashMode.ON -> ImageCapture.FLASH_MODE_ON
      CameraFlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
    }

  @SuppressLint("MissingPermission")
  override fun startRecording(
    context: Context,
    outputFile: File,
    withAudio: Boolean,
    onEvent: (CameraRecordCallbackEvent) -> Unit,
  ): Boolean {
    val videoCap = videoCapture ?: return false
    if (activeRecording != null) return false

    val outputOptions = FileOutputOptions.Builder(outputFile).build()
    var pending = videoCap.output.prepareRecording(context, outputOptions)
    if (withAudio) {
      pending = pending.withAudioEnabled()
    }

    val mainExecutor = ContextCompat.getMainExecutor(context)
    activeRecording =
      pending.start(mainExecutor) { event ->
        when (event) {
          is VideoRecordEvent.Start -> onEvent(CameraRecordCallbackEvent.Started)
          is VideoRecordEvent.Status -> {
            val elapsedMs = event.recordingStats.recordedDurationNanos / 1_000_000L
            onEvent(CameraRecordCallbackEvent.Progress(elapsedMs))
          }
          is VideoRecordEvent.Finalize -> {
            activeRecording = null
            val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
            if (!event.hasError() && outputFile.exists() && outputFile.length() > 0L) {
              val uri =
                runCatching { StudioFilePaths.fileProviderUriFor(context, outputFile) }
                  .getOrElse { Uri.fromFile(outputFile) }
              onEvent(
                CameraRecordCallbackEvent.Finalized(
                  outputFile = outputFile,
                  uri = uri,
                  durationMs = durationMs,
                  errorMessage = null,
                )
              )
            } else {
              runCatching { if (outputFile.exists()) outputFile.delete() }
              onEvent(
                CameraRecordCallbackEvent.Finalized(
                  outputFile = null,
                  uri = null,
                  durationMs = durationMs,
                  errorMessage = "Video recording failed or was interrupted (${event.cause?.localizedMessage ?: "error ${event.error}"}).",
                )
              )
            }
          }
        }
      }
    return true
  }

  override fun stopRecording() {
    activeRecording?.stop()
    activeRecording = null
  }

  override fun capturePhoto(
    context: Context,
    outputFile: File,
    flashMode: CameraFlashMode,
    onComplete: (Result<File>) -> Unit,
  ) {
    val imgCap = imageCapture
    if (imgCap == null) {
      onComplete(Result.failure(IllegalStateException("Camera photo capture is not ready.")))
      return
    }
    imgCap.flashMode = toImageCaptureFlashMode(flashMode)
    val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
    val mainExecutor = ContextCompat.getMainExecutor(context)
    imgCap.takePicture(
      outputOptions,
      mainExecutor,
      object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
          if (outputFile.exists() && outputFile.length() > 0L) {
            onComplete(Result.success(outputFile))
          } else {
            onComplete(Result.failure(IllegalStateException("Captured photo file is empty.")))
          }
        }

        override fun onError(exception: ImageCaptureException) {
          runCatching { if (outputFile.exists()) outputFile.delete() }
          onComplete(Result.failure(exception))
        }
      },
    )
  }

  override fun release() {
    stopRecording()
    boundCamera?.let { cam ->
      runCatching { cam.cameraControl.enableTorch(false) }
    }
    cameraProvider?.unbindAll()
    boundCamera = null
    videoCapture = null
    imageCapture = null
  }
}
