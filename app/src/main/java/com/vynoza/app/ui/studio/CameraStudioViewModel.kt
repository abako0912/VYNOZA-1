package com.vynoza.app.ui.studio

import android.content.Context
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vynoza.app.core.studio.CameraCaptureOutput
import com.vynoza.app.core.studio.CameraFlashMode
import com.vynoza.app.core.studio.CameraLayoutOption
import com.vynoza.app.core.studio.CameraLensFacing
import com.vynoza.app.core.studio.CameraTimerOption
import com.vynoza.app.core.studio.CaptureMode
import com.vynoza.app.core.studio.EditorMediaClip
import com.vynoza.app.core.studio.EditorMediaKind
import com.vynoza.app.core.studio.MusicTrimSelection
import com.vynoza.app.core.studio.RecordingDurationOption
import com.vynoza.app.core.studio.StudioEffectCatalog
import com.vynoza.app.core.studio.StudioEffectCategory
import com.vynoza.app.core.studio.StudioEffectPreset
import com.vynoza.app.core.studio.TextStudioAlignment
import com.vynoza.app.core.studio.TextStudioBackground
import com.vynoza.app.data.music.StudioAudioPreviewController
import com.vynoza.app.data.studio.CameraRecordCallbackEvent
import com.vynoza.app.data.studio.StudioCameraController
import com.vynoza.app.data.studio.StudioFilePaths
import com.vynoza.app.data.studio.StudioMediaProcessor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class CameraStudioUiState(
  val captureMode: CaptureMode = CaptureMode.VIDEO,
  val lensFacing: CameraLensFacing = CameraLensFacing.BACK,
  val canSwitchCamera: Boolean = true,
  val durationOption: RecordingDurationOption = RecordingDurationOption.FIFTEEN_SECONDS,
  val flashMode: CameraFlashMode = CameraFlashMode.OFF,
  val isFlashSupported: Boolean = false,
  val timerOption: CameraTimerOption = CameraTimerOption.OFF,
  val countdownRemainingSec: Int? = null,
  val layoutOption: CameraLayoutOption = CameraLayoutOption.PORTRAIT_9_16,
  val selectedEffect: StudioEffectPreset = StudioEffectCatalog.findById(StudioEffectCatalog.EFFECT_NONE_ID),
  val isEffectsGalleryOpen: Boolean = false,
  val selectedEffectCategory: StudioEffectCategory = StudioEffectCategory.ALL,
  val selectedMusic: MusicTrimSelection? = null,
  val isMusicSheetOpen: Boolean = false,
  val hasCameraPermission: Boolean = false,
  val hasMicPermission: Boolean = false,
  val permissionRequestedOnce: Boolean = false,
  val isCameraBound: Boolean = false,
  val isRecording: Boolean = false,
  val recordingElapsedMs: Long = 0L,
  val isCapturingPhoto: Boolean = false,
  val isProcessingMedia: Boolean = false,
  val recordingNotice: String? = null,
  val errorMessage: String? = null,
  val textContent: String = "",
  val textBackground: TextStudioBackground = TextStudioBackground.VIOLET_NIGHT,
  val textAlignment: TextStudioAlignment = TextStudioAlignment.CENTER,
  val textFontScale: Float = 1.0f,
  val reviewOutput: CameraCaptureOutput? = null,
) {
  val recordingProgressFraction: Float
    get() = (recordingElapsedMs.toFloat() / durationOption.durationMs.toFloat()).coerceIn(0f, 1f)
}

class CameraStudioViewModel(
  private val cameraController: StudioCameraController,
  private val soundPreviewController: StudioAudioPreviewController? = null,
) : ViewModel() {

  private val _uiState = MutableStateFlow(CameraStudioUiState())
  val uiState: StateFlow<CameraStudioUiState> = _uiState.asStateFlow()

  private var countdownJob: Job? = null
  private var recordingTickerJob: Job? = null
  private var stopLimitTriggered = false

  fun syncPermissions(cameraGranted: Boolean, micGranted: Boolean, requested: Boolean) {
    _uiState.update { current ->
      val micDeniedNotice =
        if (cameraGranted && !micGranted && requested) {
          "Microphone access denied — videos will record without microphone audio."
        } else if (micGranted && current.recordingNotice?.contains("Microphone access denied") == true) {
          null
        } else {
          current.recordingNotice
        }
      current.copy(
        hasCameraPermission = cameraGranted,
        hasMicPermission = micGranted,
        permissionRequestedOnce = current.permissionRequestedOnce || requested,
        recordingNotice = micDeniedNotice,
        errorMessage = if (cameraGranted) null else current.errorMessage,
      )
    }
  }

  fun bindPreview(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
  ) {
    val state = _uiState.value
    if (!state.hasCameraPermission && state.captureMode != CaptureMode.TEXT) {
      return
    }
    cameraController.bindCamera(
      context = context,
      lifecycleOwner = lifecycleOwner,
      previewView = previewView,
      captureMode = state.captureMode,
      lensFacing = state.lensFacing,
      layoutOption = state.layoutOption,
      flashMode = state.flashMode,
    ) { result ->
      result.fold(
        onSuccess = { bindInfo ->
          _uiState.update { cur ->
            val adjustedFlash = if (bindInfo.hasFlashUnit) cur.flashMode else CameraFlashMode.OFF
            cur.copy(
              isCameraBound = cur.captureMode != CaptureMode.TEXT,
              isFlashSupported = bindInfo.hasFlashUnit,
              canSwitchCamera = bindInfo.hasFrontCamera && bindInfo.hasBackCamera,
              flashMode = adjustedFlash,
              errorMessage = null,
            )
          }
        },
        onFailure = { error ->
          _uiState.update {
            it.copy(
              isCameraBound = false,
              errorMessage = error.localizedMessage ?: "Unable to initialize device camera.",
            )
          }
        },
      )
    }
  }

  fun selectCaptureMode(mode: CaptureMode) {
    val cur = _uiState.value
    if (cur.isRecording || cur.isCapturingPhoto || cur.countdownRemainingSec != null) return
    val nextFlash =
      if (mode == CaptureMode.VIDEO && cur.flashMode == CameraFlashMode.AUTO) {
        CameraFlashMode.OFF
      } else {
        cur.flashMode
      }
    _uiState.update {
      it.copy(
        captureMode = mode,
        flashMode = nextFlash,
        errorMessage = null,
      )
    }
    if (mode == CaptureMode.TEXT) {
      cameraController.applyFlashMode(CameraFlashMode.OFF, mode)
    }
  }

  fun selectDurationOption(option: RecordingDurationOption) {
    val cur = _uiState.value
    if (cur.isRecording || cur.countdownRemainingSec != null) return
    _uiState.update { it.copy(durationOption = option) }
  }

  fun flipCamera() {
    val cur = _uiState.value
    if (cur.isRecording || cur.isCapturingPhoto || !cur.canSwitchCamera) return
    val nextLens =
      if (cur.lensFacing == CameraLensFacing.BACK) CameraLensFacing.FRONT else CameraLensFacing.BACK
    _uiState.update {
      it.copy(
        lensFacing = nextLens,
        flashMode = CameraFlashMode.OFF,
        errorMessage = null,
      )
    }
  }

  fun cycleFlashMode() {
    val cur = _uiState.value
    if (cur.captureMode == CaptureMode.TEXT) return
    if (!cur.isFlashSupported) {
      _uiState.update {
        it.copy(
          flashMode = CameraFlashMode.OFF,
          recordingNotice = "Flash is not available on the active camera.",
        )
      }
      return
    }
    val next =
      if (cur.captureMode == CaptureMode.VIDEO) {
        if (cur.flashMode == CameraFlashMode.ON) CameraFlashMode.OFF else CameraFlashMode.ON
      } else {
        when (cur.flashMode) {
          CameraFlashMode.OFF -> CameraFlashMode.ON
          CameraFlashMode.ON -> CameraFlashMode.AUTO
          CameraFlashMode.AUTO -> CameraFlashMode.OFF
        }
      }
    _uiState.update { it.copy(flashMode = next, recordingNotice = null) }
    cameraController.applyFlashMode(next, cur.captureMode)
  }

  fun cycleTimerOption() {
    if (_uiState.value.isRecording || _uiState.value.countdownRemainingSec != null) return
    val next =
      when (_uiState.value.timerOption) {
        CameraTimerOption.OFF -> CameraTimerOption.THREE_SECONDS
        CameraTimerOption.THREE_SECONDS -> CameraTimerOption.TEN_SECONDS
        CameraTimerOption.TEN_SECONDS -> CameraTimerOption.OFF
      }
    _uiState.update { it.copy(timerOption = next) }
  }

  fun selectTimerOption(option: CameraTimerOption) {
    if (_uiState.value.isRecording || _uiState.value.countdownRemainingSec != null) return
    _uiState.update { it.copy(timerOption = option) }
  }

  fun cycleLayoutOption() {
    if (_uiState.value.isRecording || _uiState.value.countdownRemainingSec != null) return
    val entries = CameraLayoutOption.entries
    val nextIndex = (entries.indexOf(_uiState.value.layoutOption) + 1) % entries.size
    _uiState.update { it.copy(layoutOption = entries[nextIndex]) }
  }

  fun selectLayoutOption(option: CameraLayoutOption) {
    if (_uiState.value.isRecording || _uiState.value.countdownRemainingSec != null) return
    _uiState.update { it.copy(layoutOption = option) }
  }

  fun selectEffect(preset: StudioEffectPreset, closeGallery: Boolean = false) {
    _uiState.update {
      it.copy(
        selectedEffect = preset,
        isEffectsGalleryOpen = if (closeGallery) false else it.isEffectsGalleryOpen,
      )
    }
  }

  fun openEffectsGallery() {
    if (_uiState.value.isRecording) return
    _uiState.update { it.copy(isEffectsGalleryOpen = true) }
  }

  fun closeEffectsGallery() {
    _uiState.update { it.copy(isEffectsGalleryOpen = false) }
  }

  fun selectEffectCategory(category: StudioEffectCategory) {
    _uiState.update { it.copy(selectedEffectCategory = category) }
  }

  fun openMusicStudio() {
    if (_uiState.value.isRecording) return
    _uiState.update { it.copy(isMusicSheetOpen = true) }
  }

  fun closeMusicStudio() {
    soundPreviewController?.pause()
    _uiState.update { it.copy(isMusicSheetOpen = false) }
  }

  fun onMusicSelected(selection: MusicTrimSelection) {
    soundPreviewController?.pause()
    _uiState.update {
      it.copy(
        selectedMusic = selection,
        isMusicSheetOpen = false,
      )
    }
  }

  fun clearSelectedMusic() {
    soundPreviewController?.pause()
    _uiState.update { it.copy(selectedMusic = null) }
  }

  fun updateTextContent(text: String) {
    _uiState.update { it.copy(textContent = text.take(280), errorMessage = null) }
  }

  fun selectTextBackground(background: TextStudioBackground) {
    _uiState.update { it.copy(textBackground = background) }
  }

  fun cycleTextAlignment() {
    val entries = TextStudioAlignment.entries
    val next = entries[(entries.indexOf(_uiState.value.textAlignment) + 1) % entries.size]
    _uiState.update { it.copy(textAlignment = next) }
  }

  fun updateTextFontScale(scale: Float) {
    _uiState.update { it.copy(textFontScale = scale.coerceIn(0.8f, 1.45f)) }
  }

  fun onShutterPressed(context: Context) {
    val cur = _uiState.value
    if (cur.isRecording) {
      stopRecording()
      return
    }
    if (cur.countdownRemainingSec != null) {
      cancelCountdown()
      return
    }
    if (cur.isCapturingPhoto || cur.isProcessingMedia) return

    if (cur.captureMode == CaptureMode.TEXT) {
      submitTextStory(context)
      return
    }

    if (!cur.hasCameraPermission) {
      _uiState.update { it.copy(errorMessage = "Camera permission is required before capturing.") }
      return
    }

    val storageCheck =
      if (cur.captureMode == CaptureMode.VIDEO) {
        StudioMediaProcessor.checkStorageForRecording(context)
      } else {
        StudioMediaProcessor.checkStorageForPhoto(context)
      }
    storageCheck.onFailure { err ->
      _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "Insufficient device storage.") }
      return
    }

    val timerSeconds = cur.timerOption.seconds
    if (timerSeconds > 0) {
      startCountdown(timerSeconds) {
        triggerCaptureNow(context)
      }
    } else {
      triggerCaptureNow(context)
    }
  }

  fun cancelCountdown() {
    countdownJob?.cancel()
    countdownJob = null
    _uiState.update { it.copy(countdownRemainingSec = null) }
  }

  private fun startCountdown(seconds: Int, onComplete: () -> Unit) {
    countdownJob?.cancel()
    countdownJob =
      viewModelScope.launch {
        for (remaining in seconds downTo 1) {
          _uiState.update { it.copy(countdownRemainingSec = remaining, errorMessage = null) }
          delay(1_000L)
        }
        _uiState.update { it.copy(countdownRemainingSec = null) }
        onComplete()
      }
  }

  private fun triggerCaptureNow(context: Context) {
    when (_uiState.value.captureMode) {
      CaptureMode.VIDEO -> startVideoRecording(context)
      CaptureMode.PHOTO -> capturePhoto(context)
      CaptureMode.TEXT -> submitTextStory(context)
    }
  }

  private fun startVideoRecording(context: Context) {
    val cur = _uiState.value
    StudioMediaProcessor.checkStorageForRecording(context).onFailure { err ->
      _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "Low storage.") }
      return
    }
    val outputFile = StudioFilePaths.createTempCameraFile(context, "vynoza_rec", "mp4")
    val maxDurationMs = cur.durationOption.durationMs
    stopLimitTriggered = false

    val startedOk =
      cameraController.startRecording(
        context = context,
        outputFile = outputFile,
        withAudio = cur.hasMicPermission,
      ) { event ->
        when (event) {
          is CameraRecordCallbackEvent.Started -> {
            _uiState.update {
              it.copy(
                isRecording = true,
                recordingElapsedMs = 0L,
                errorMessage = null,
              )
            }
            cur.selectedMusic?.let { trim ->
              soundPreviewController?.playPreview(
                track = trim.track,
                startMs = trim.clampedStartMs,
                endMs = trim.endMs,
              )
            }
            startRecordingTimerFallback(maxDurationMs)
          }

          is CameraRecordCallbackEvent.Progress -> {
            _uiState.update { it.copy(recordingElapsedMs = event.elapsedMs.coerceAtMost(maxDurationMs)) }
            if (event.elapsedMs >= maxDurationMs && !stopLimitTriggered) {
              stopLimitTriggered = true
              stopRecording(notice = "Reached ${cur.durationOption.label} recording limit.")
            }
          }

          is CameraRecordCallbackEvent.Finalized -> {
            recordingTickerJob?.cancel()
            recordingTickerJob = null
            soundPreviewController?.pause()
            if (event.errorMessage == null && event.outputFile != null && event.uri != null) {
              val probedMs =
                if (event.durationMs > 0L) {
                  event.durationMs
                } else {
                  StudioMediaProcessor.probeVideoDurationMs(context, event.uri) ?: 1_000L
                }
              val clip =
                EditorMediaClip(
                  uriString = event.uri.toString(),
                  kind = EditorMediaKind.VIDEO,
                  durationMs = probedMs.coerceAtLeast(500L),
                  trimStartMs = 0L,
                  trimEndMs = probedMs.coerceAtLeast(500L),
                  displayName = event.outputFile.name,
                )
              _uiState.update { state ->
                state.copy(
                  isRecording = false,
                  recordingElapsedMs = 0L,
                  reviewOutput =
                    CameraCaptureOutput(
                      clips = listOf(clip),
                      selectedMusic = state.selectedMusic,
                      effectPresetId = state.selectedEffect.id,
                    ),
                )
              }
            } else {
              _uiState.update { state ->
                state.copy(
                  isRecording = false,
                  recordingElapsedMs = 0L,
                  errorMessage = event.errorMessage ?: "Video recording could not be saved.",
                )
              }
            }
          }
        }
      }

    if (!startedOk) {
      _uiState.update {
        it.copy(
          isRecording = false,
          errorMessage = "Camera recorder is not ready yet. Please wait a moment and try again.",
        )
      }
    }
  }

  private fun startRecordingTimerFallback(maxDurationMs: Long) {
    recordingTickerJob?.cancel()
    recordingTickerJob =
      viewModelScope.launch {
        val startedWallMs = System.currentTimeMillis()
        while (isActive && _uiState.value.isRecording) {
          delay(200L)
          val wallElapsed = System.currentTimeMillis() - startedWallMs
          val curElapsed = maxOf(_uiState.value.recordingElapsedMs, wallElapsed)
          _uiState.update { it.copy(recordingElapsedMs = curElapsed.coerceAtMost(maxDurationMs)) }
          if (curElapsed >= maxDurationMs && !stopLimitTriggered) {
            stopLimitTriggered = true
            stopRecording(notice = "Reached ${_uiState.value.durationOption.label} recording limit.")
            break
          }
        }
      }
  }

  fun stopRecording(notice: String? = null) {
    recordingTickerJob?.cancel()
    recordingTickerJob = null
    soundPreviewController?.pause()
    if (_uiState.value.isRecording) {
      cameraController.stopRecording()
      if (notice != null) {
        _uiState.update { it.copy(recordingNotice = notice) }
      }
    }
  }

  private fun capturePhoto(context: Context) {
    StudioMediaProcessor.checkStorageForPhoto(context).onFailure { err ->
      _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "Low storage.") }
      return
    }
    val cur = _uiState.value
    val rawFile = StudioFilePaths.createTempCameraFile(context, "vynoza_raw_photo", "jpg")
    val processedFile = StudioFilePaths.createTempCameraFile(context, "vynoza_photo", "jpg")
    _uiState.update { it.copy(isCapturingPhoto = true, errorMessage = null) }

    cameraController.capturePhoto(
      context = context,
      outputFile = rawFile,
      flashMode = cur.flashMode,
    ) { result ->
      result.fold(
        onSuccess = { savedRaw ->
          viewModelScope.launch {
            val gradedResult =
              StudioMediaProcessor.applyEffectToCapturedPhoto(
                sourceFile = savedRaw,
                effectPresetId = cur.selectedEffect.id,
                outputFile = processedFile,
              )
            gradedResult.fold(
              onSuccess = { finalFile ->
                if (savedRaw.absolutePath != finalFile.absolutePath) {
                  runCatching { savedRaw.delete() }
                }
                val uri =
                  runCatching { StudioFilePaths.fileProviderUriFor(context, finalFile) }
                    .getOrElse { Uri.fromFile(finalFile) }
                val clip =
                  EditorMediaClip(
                    uriString = uri.toString(),
                    kind = EditorMediaKind.PHOTO,
                    durationMs = EditorMediaClip.DEFAULT_PHOTO_CLIP_DURATION_MS,
                    trimStartMs = 0L,
                    trimEndMs = EditorMediaClip.DEFAULT_PHOTO_CLIP_DURATION_MS,
                    displayName = finalFile.name,
                  )
                _uiState.update { state ->
                  state.copy(
                    isCapturingPhoto = false,
                    reviewOutput =
                      CameraCaptureOutput(
                        clips = listOf(clip),
                        selectedMusic = state.selectedMusic,
                        effectPresetId = state.selectedEffect.id,
                      ),
                  )
                }
              },
              onFailure = { err ->
                _uiState.update {
                  it.copy(
                    isCapturingPhoto = false,
                    errorMessage = err.localizedMessage ?: "Failed to apply photo filter.",
                  )
                }
              },
            )
          }
        },
        onFailure = { err ->
          _uiState.update {
            it.copy(
              isCapturingPhoto = false,
              errorMessage = err.localizedMessage ?: "Photo capture failed.",
            )
          }
        },
      )
    }
  }

  fun submitTextStory(context: Context) {
    val cur = _uiState.value
    if (cur.textContent.isBlank()) {
      _uiState.update { it.copy(errorMessage = "Enter text for your story card first.") }
      return
    }
    _uiState.update { it.copy(isProcessingMedia = true, errorMessage = null) }
    val outputFile = StudioFilePaths.createTempCameraFile(context, "vynoza_text", "jpg")
    viewModelScope.launch {
      val rendered =
        StudioMediaProcessor.renderTextStoryToJpeg(
          context = context,
          text = cur.textContent,
          background = cur.textBackground,
          alignment = cur.textAlignment,
          fontScale = cur.textFontScale,
          outputFile = outputFile,
        )
      rendered.fold(
        onSuccess = { file ->
          val uri =
            runCatching { StudioFilePaths.fileProviderUriFor(context, file) }
              .getOrElse { Uri.fromFile(file) }
          val clip =
            EditorMediaClip(
              uriString = uri.toString(),
              kind = EditorMediaKind.PHOTO,
              durationMs = 4_000L,
              trimStartMs = 0L,
              trimEndMs = 4_000L,
              displayName = file.name,
            )
          _uiState.update { state ->
            state.copy(
              isProcessingMedia = false,
              reviewOutput =
                CameraCaptureOutput(
                  clips = listOf(clip),
                  selectedMusic = state.selectedMusic,
                  effectPresetId = state.selectedEffect.id,
                ),
            )
          }
        },
        onFailure = { err ->
          _uiState.update {
            it.copy(
              isProcessingMedia = false,
              errorMessage = err.localizedMessage ?: "Could not render text story.",
            )
          }
        },
      )
    }
  }

  fun onGalleryMediaSelected(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return
    _uiState.update { it.copy(isProcessingMedia = true, errorMessage = null) }
    viewModelScope.launch {
      val clips = StudioMediaProcessor.inspectGalleryUris(context, uris)
      if (clips.isEmpty()) {
        _uiState.update {
          it.copy(
            isProcessingMedia = false,
            errorMessage = "Selected media could not be read. Please choose valid photos or videos.",
          )
        }
      } else {
        _uiState.update { state ->
          state.copy(
            isProcessingMedia = false,
            reviewOutput =
              CameraCaptureOutput(
                clips = clips,
                selectedMusic = state.selectedMusic,
                effectPresetId = state.selectedEffect.id,
              ),
          )
        }
      }
    }
  }

  fun retakeCapture() {
    _uiState.update { it.copy(reviewOutput = null, errorMessage = null) }
  }

  fun dismissError() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  fun dismissNotice() {
    _uiState.update { it.copy(recordingNotice = null) }
  }

  fun onAppLifecyclePause() {
    if (_uiState.value.countdownRemainingSec != null) {
      cancelCountdown()
      _uiState.update { it.copy(recordingNotice = "Countdown cancelled when leaving camera.") }
    }
    if (_uiState.value.isRecording) {
      stopRecording(notice = "Recording stopped because the app went to background.")
    } else {
      soundPreviewController?.pause()
    }
    cameraController.applyFlashMode(CameraFlashMode.OFF, _uiState.value.captureMode)
  }

  override fun onCleared() {
    countdownJob?.cancel()
    recordingTickerJob?.cancel()
    soundPreviewController?.pause()
    cameraController.release()
    super.onCleared()
  }
}
