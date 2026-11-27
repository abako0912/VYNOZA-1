package com.vynoza.app.ui.studio

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vynoza.app.core.studio.AudioMixSettings
import com.vynoza.app.core.studio.CameraCaptureOutput
import com.vynoza.app.core.studio.CreateStudioDraft
import com.vynoza.app.core.studio.DraftPrivacy
import com.vynoza.app.core.studio.EditorMediaClip
import com.vynoza.app.core.studio.EditorMediaKind
import com.vynoza.app.core.studio.MusicTrimSelection
import com.vynoza.app.core.studio.StudioEffectCatalog
import com.vynoza.app.data.music.AudiusLicenseEvaluator
import com.vynoza.app.data.music.MusicLicenseDecision
import com.vynoza.app.data.music.StudioAudioPreviewController
import com.vynoza.app.data.service.ClipSource
import com.vynoza.app.data.studio.CreateStudioLocalStore
import com.vynoza.app.data.studio.StudioDraftMediaHelper
import com.vynoza.app.data.studio.StudioExportRequest
import com.vynoza.app.data.studio.StudioFilePaths
import com.vynoza.app.data.studio.StudioMediaProcessor
import com.vynoza.app.data.studio.StudioVideoExporter
import com.vynoza.app.ui.create.CreateViewModel
import com.vynoza.app.ui.create.PublishPostState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface VideoEditorExportState {
  data object Idle : VideoEditorExportState
  data class Exporting(val progress: Float) : VideoEditorExportState
  data class Exported(val file: File, val uriString: String) : VideoEditorExportState
  data class Error(val message: String) : VideoEditorExportState
}

enum class VideoEditorTab(val label: String) {
  EDIT_AND_AUDIO("Edit & Audio"),
  COVER_AND_PUBLISH("Cover & Publish"),
}

data class VideoEditorUiState(
  val draftId: String,
  val createdAtMs: Long,
  val title: String = "",
  val templateId: String? = null,
  val clips: List<EditorMediaClip> = emptyList(),
  val selectedClipIndex: Int = 0,
  val missingClipIds: Set<String> = emptySet(),
  val effectPresetId: String = StudioEffectCatalog.EFFECT_NONE_ID,
  val selectedMusic: MusicTrimSelection? = null,
  val musicLicenseDecision: MusicLicenseDecision = AudiusLicenseEvaluator.evaluate(null),
  val audioMix: AudioMixSettings = AudioMixSettings(),
  val coverFrameUriString: String? = null,
  val coverTimestampMs: Long = 0L,
  val isExtractingCover: Boolean = false,
  val caption: String = "",
  val hashtags: List<String> = emptyList(),
  val mentions: List<String> = emptyList(),
  val privacy: DraftPrivacy = DraftPrivacy.PUBLIC,
  val activeTab: VideoEditorTab = VideoEditorTab.EDIT_AND_AUDIO,
  val isMusicSheetOpen: Boolean = false,
  val isPreviewPlaying: Boolean = true,
  val isPreviewingExported: Boolean = false,
  val previewPositionMs: Long = 0L,
  val exportState: VideoEditorExportState = VideoEditorExportState.Idle,
  val isSavingDraft: Boolean = false,
  val draftStatusMessage: String? = null,
  val errorMessage: String? = null,
  val isAddingMedia: Boolean = false,
  val hasStartedPublishInSession: Boolean = false,
) {
  val selectedClip: EditorMediaClip?
    get() = clips.getOrNull(selectedClipIndex.coerceIn(0, (clips.size - 1).coerceAtLeast(0)))

  val totalDurationMs: Long
    get() = clips.sumOf { it.effectiveDurationMs }

  val hasMusicLicenseConflict: Boolean
    get() = selectedMusic != null && audioMix.musicVolume > 0.001f && !musicLicenseDecision.canEmbed

  val composedCaption: String
    get() =
      CreateStudioDraft(
        id = draftId,
        caption = caption,
        hashtags = hashtags,
        mentions = mentions,
      ).buildPublishCaption()

  val exportedFile: File?
    get() = (exportState as? VideoEditorExportState.Exported)?.file
}

class VideoEditorViewModel(
  private val localStore: CreateStudioLocalStore,
  private val videoExporter: StudioVideoExporter,
  private val audioPreviewController: StudioAudioPreviewController? = null,
  private val createViewModel: CreateViewModel? = null,
  initialDraft: CreateStudioDraft,
) : ViewModel() {

  constructor(
    localStore: CreateStudioLocalStore,
    videoExporter: StudioVideoExporter,
    audioPreviewController: StudioAudioPreviewController? = null,
    createViewModel: CreateViewModel? = null,
    captureOutput: CameraCaptureOutput,
  ) : this(
    localStore = localStore,
    videoExporter = videoExporter,
    audioPreviewController = audioPreviewController,
    createViewModel = createViewModel,
    initialDraft =
      CreateStudioDraft(
        clips = captureOutput.clips,
        selectedMusic = captureOutput.selectedMusic,
        effectPresetId = captureOutput.effectPresetId,
      ),
  )

  private val _uiState =
    MutableStateFlow(
      VideoEditorUiState(
        draftId = initialDraft.id,
        createdAtMs = initialDraft.createdAtMs,
        title = initialDraft.title,
        templateId = initialDraft.templateId,
        clips = initialDraft.clips,
        effectPresetId = initialDraft.effectPresetId,
        selectedMusic = initialDraft.selectedMusic,
        musicLicenseDecision = AudiusLicenseEvaluator.evaluate(initialDraft.selectedMusic?.track),
        audioMix = initialDraft.audioMix.normalized(),
        coverFrameUriString = initialDraft.coverFrameUriString,
        coverTimestampMs = initialDraft.coverTimestampMs,
        caption = initialDraft.caption,
        hashtags = initialDraft.hashtags,
        mentions = initialDraft.mentions,
        privacy = initialDraft.privacy,
      )
    )
  val uiState: StateFlow<VideoEditorUiState> = _uiState.asStateFlow()

  private var exportJob: Job? = null
  private var publishAfterExport = false
  private var wasPlayingMusicPreview = false

  fun validateExistingMedia(context: Context) {
    val snapshot = _uiState.value.toDraft()
    viewModelScope.launch {
      val inspection = StudioDraftMediaHelper.validateDraftMedia(context, snapshot)
      _uiState.update { cur ->
        cur.copy(
          missingClipIds = inspection.missingClipIds,
          coverFrameUriString = if (inspection.isCoverMissing) null else cur.coverFrameUriString,
        )
      }
    }
  }

  fun selectTab(tab: VideoEditorTab) {
    _uiState.update { it.copy(activeTab = tab) }
  }

  fun selectClip(index: Int) {
    _uiState.update { cur ->
      val clamped = index.coerceIn(0, (cur.clips.size - 1).coerceAtLeast(0))
      cur.copy(selectedClipIndex = clamped, previewPositionMs = 0L)
    }
  }

  fun addGalleryMedia(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return
    _uiState.update { it.copy(isAddingMedia = true, errorMessage = null) }
    viewModelScope.launch {
      val added = StudioMediaProcessor.inspectGalleryUris(context, uris)
      if (added.isEmpty()) {
        _uiState.update {
          it.copy(
            isAddingMedia = false,
            errorMessage = "Could not open the selected media item(s).",
          )
        }
      } else {
        _uiState.update { cur ->
          cur.copy(
            isAddingMedia = false,
            clips = cur.clips + added,
            exportState = VideoEditorExportState.Idle,
            isPreviewingExported = false,
          )
        }
      }
    }
  }

  fun removeClip(clipId: String) {
    _uiState.update { cur ->
      if (cur.clips.size <= 1 && clipId !in cur.missingClipIds) {
        return@update cur.copy(errorMessage = "Keep at least one clip in the editor.")
      }
      val nextClips = cur.clips.filterNot { it.id == clipId }
      val nextIdx = cur.selectedClipIndex.coerceIn(0, (nextClips.size - 1).coerceAtLeast(0))
      cur.copy(
        clips = nextClips,
        selectedClipIndex = nextIdx,
        missingClipIds = cur.missingClipIds - clipId,
        exportState = VideoEditorExportState.Idle,
        isPreviewingExported = false,
      )
    }
  }

  fun removeMissingClips() {
    _uiState.update { cur ->
      val remaining = cur.clips.filterNot { it.id in cur.missingClipIds }
      cur.copy(
        clips = remaining,
        selectedClipIndex = 0,
        missingClipIds = emptySet(),
        exportState = VideoEditorExportState.Idle,
        isPreviewingExported = false,
      )
    }
  }

  fun updateClipTrim(clipId: String, trimStartMs: Long, trimEndMs: Long) {
    _uiState.update { cur ->
      val updatedClips =
        cur.clips.map { clip ->
          if (clip.id != clipId) {
            clip
          } else if (clip.kind == EditorMediaKind.PHOTO) {
            val dur = trimEndMs.coerceIn(1_000L, 15_000L)
            clip.copy(durationMs = dur, trimStartMs = 0L, trimEndMs = dur)
          } else {
            val safeStart = trimStartMs.coerceIn(0L, (clip.durationMs - 500L).coerceAtLeast(0L))
            val safeEnd = trimEndMs.coerceIn((safeStart + 500L).coerceAtMost(clip.durationMs), clip.durationMs)
            clip.copy(trimStartMs = safeStart, trimEndMs = safeEnd)
          }
        }
      cur.copy(
        clips = updatedClips,
        exportState = VideoEditorExportState.Idle,
        isPreviewingExported = false,
      )
    }
  }

  fun updatePhotoClipDuration(clipId: String, durationMs: Long) {
    updateClipTrim(clipId, 0L, durationMs)
  }

  fun selectEffectPreset(effectPresetId: String) {
    _uiState.update { cur ->
      cur.copy(
        effectPresetId = effectPresetId,
        exportState = VideoEditorExportState.Idle,
        isPreviewingExported = false,
      )
    }
  }

  fun openMusicStudio() {
    pauseSyncedMusicPreview()
    audioPreviewController?.setVolume(1f)
    _uiState.update { it.copy(isPreviewPlaying = false, isMusicSheetOpen = true) }
  }

  fun closeMusicStudio() {
    audioPreviewController?.pause()
    audioPreviewController?.setVolume(_uiState.value.audioMix.musicVolume)
    _uiState.update { it.copy(isMusicSheetOpen = false) }
  }

  fun onMusicSelected(selection: MusicTrimSelection) {
    audioPreviewController?.pause()
    audioPreviewController?.setVolume(_uiState.value.audioMix.musicVolume)
    val decision = AudiusLicenseEvaluator.evaluate(selection.track)
    _uiState.update { cur ->
      cur.copy(
        selectedMusic = selection,
        musicLicenseDecision = decision,
        isMusicSheetOpen = false,
        exportState = VideoEditorExportState.Idle,
        isPreviewingExported = false,
        errorMessage = null,
      )
    }
  }

  fun removeSelectedMusic() {
    pauseSyncedMusicPreview()
    _uiState.update { cur ->
      cur.copy(
        selectedMusic = null,
        musicLicenseDecision = AudiusLicenseEvaluator.evaluate(null),
        audioMix = cur.audioMix.copy(syncOffsetMs = 0L),
        exportState = VideoEditorExportState.Idle,
        isPreviewingExported = false,
        errorMessage = null,
      )
    }
  }

  fun updateOriginalAudioVolume(volume: Float) {
    _uiState.update { cur ->
      cur.copy(
        audioMix = cur.audioMix.copy(originalAudioVolume = volume.coerceIn(0f, 1f)),
        exportState = VideoEditorExportState.Idle,
        isPreviewingExported = false,
      )
    }
  }

  fun updateMusicVolume(volume: Float) {
    val clamped = volume.coerceIn(0f, 1f)
    audioPreviewController?.setVolume(clamped)
    if (clamped <= 0.001f) {
      pauseSyncedMusicPreview()
    }
    _uiState.update { cur ->
      cur.copy(
        audioMix = cur.audioMix.copy(musicVolume = clamped),
        exportState = VideoEditorExportState.Idle,
        isPreviewingExported = false,
        errorMessage = null,
      )
    }
  }

  fun updateMusicSyncOffset(offsetMs: Long) {
    _uiState.update { cur ->
      val maxOffset = cur.totalDurationMs.coerceIn(0L, 60_000L)
      cur.copy(
        audioMix = cur.audioMix.copy(syncOffsetMs = offsetMs.coerceIn(0L, maxOffset)),
        exportState = VideoEditorExportState.Idle,
        isPreviewingExported = false,
      )
    }
  }

  fun setPreviewPlaying(playing: Boolean) {
    _uiState.update { it.copy(isPreviewPlaying = playing) }
    if (!playing) {
      pauseSyncedMusicPreview()
    }
  }

  fun togglePreviewPlayPause() {
    setPreviewPlaying(!_uiState.value.isPreviewPlaying)
  }

  fun setPreviewingExported(previewExported: Boolean) {
    if (previewExported && _uiState.value.exportState !is VideoEditorExportState.Exported) return
    if (previewExported) {
      pauseSyncedMusicPreview()
    }
    _uiState.update { it.copy(isPreviewingExported = previewExported, previewPositionMs = 0L) }
  }

  fun onPreviewProgressTick(globalPositionMs: Long) {
    val cur = _uiState.value
    _uiState.update { it.copy(previewPositionMs = globalPositionMs.coerceAtLeast(0L)) }

    if (cur.isPreviewingExported || !cur.isPreviewPlaying || cur.isMusicSheetOpen) {
      pauseSyncedMusicPreview()
      return
    }

    val music = cur.selectedMusic
    if (music == null || cur.audioMix.musicVolume <= 0.001f || !music.track.isStreamable) {
      pauseSyncedMusicPreview()
      return
    }

    val offsetMs = cur.audioMix.syncOffsetMs
    if (globalPositionMs < offsetMs) {
      pauseSyncedMusicPreview()
      return
    }

    val previewCtrl = audioPreviewController ?: return
    previewCtrl.setVolume(cur.audioMix.musicVolume)
    val targetAudioMs =
      music.clampedStartMs + ((globalPositionMs - offsetMs) % music.clampedDurationMs)
    val previewState = previewCtrl.state.value
    if (previewState.activeTrackId != music.track.id || !previewState.isPlaying) {
      previewCtrl.playPreview(
        track = music.track,
        startMs = music.clampedStartMs,
        endMs = music.endMs,
      )
      previewCtrl.seekTo(targetAudioMs)
      wasPlayingMusicPreview = true
    } else if (kotlin.math.abs(previewState.positionMs - targetAudioMs) > 650L) {
      previewCtrl.seekTo(targetAudioMs)
    }
  }

  private fun pauseSyncedMusicPreview() {
    if (wasPlayingMusicPreview) {
      audioPreviewController?.pause()
      wasPlayingMusicPreview = false
    } else {
      audioPreviewController?.pause()
    }
  }

  fun updateCaption(caption: String) {
    _uiState.update {
      it.copy(
        caption = caption.take(CreateStudioDraft.MAX_FIRESTORE_CAPTION_LENGTH),
        errorMessage = null,
      )
    }
  }

  fun addHashtag(raw: String) {
    val normalized = CreateStudioDraft.normalizeHashtag(raw) ?: return
    _uiState.update { cur ->
      if (cur.hashtags.any { it.equals(normalized, ignoreCase = true) }) {
        cur
      } else {
        cur.copy(hashtags = cur.hashtags + normalized)
      }
    }
  }

  fun removeHashtag(tag: String) {
    _uiState.update { cur ->
      cur.copy(hashtags = cur.hashtags.filterNot { it == tag })
    }
  }

  fun addMention(raw: String) {
    val normalized = CreateStudioDraft.normalizeMention(raw) ?: return
    _uiState.update { cur ->
      if (cur.mentions.any { it.equals(normalized, ignoreCase = true) }) {
        cur
      } else {
        cur.copy(mentions = cur.mentions + normalized)
      }
    }
  }

  fun removeMention(mention: String) {
    _uiState.update { cur ->
      cur.copy(mentions = cur.mentions.filterNot { it == mention })
    }
  }

  fun selectPrivacy(privacy: DraftPrivacy) {
    _uiState.update { it.copy(privacy = privacy) }
  }

  fun updateCoverTimestamp(timestampMs: Long) {
    _uiState.update { cur ->
      cur.copy(coverTimestampMs = timestampMs.coerceIn(0L, cur.totalDurationMs.coerceAtLeast(0L)))
    }
  }

  fun captureCoverFrame(context: Context) {
    val cur = _uiState.value
    if (cur.clips.isEmpty() || cur.isExtractingCover) return
    _uiState.update { it.copy(isExtractingCover = true, errorMessage = null) }
    viewModelScope.launch {
      val extracted =
        StudioDraftMediaHelper.extractCoverFrame(
          context = context,
          clips = cur.clips,
          coverTimestampMs = cur.coverTimestampMs,
          draftId = cur.draftId,
        )
      extracted.fold(
        onSuccess = { coverUri ->
          _uiState.update {
            it.copy(
              isExtractingCover = false,
              coverFrameUriString = coverUri.toString(),
              draftStatusMessage = "Cover frame captured.",
            )
          }
        },
        onFailure = { err ->
          _uiState.update {
            it.copy(
              isExtractingCover = false,
              errorMessage = err.localizedMessage ?: "Unable to extract cover frame.",
            )
          }
        },
      )
    }
  }

  fun exportEditedVideo(
    context: Context,
    thenPublish: Boolean = false,
  ) {
    val cur = _uiState.value
    if (cur.exportState is VideoEditorExportState.Exporting) return

    if (cur.clips.isEmpty()) {
      _uiState.update { it.copy(errorMessage = "Add at least one clip before exporting.") }
      return
    }
    if (cur.missingClipIds.isNotEmpty()) {
      _uiState.update {
        it.copy(errorMessage = "Remove or replace missing media clips before exporting.")
      }
      return
    }
    if (cur.hasMusicLicenseConflict) {
      _uiState.update {
        it.copy(
          errorMessage =
            cur.musicLicenseDecision.explanation +
              " Remove or mute the music track (0%) to export your video.",
        )
      }
      return
    }

    publishAfterExport = thenPublish
    pauseSyncedMusicPreview()
    _uiState.update {
      it.copy(
        isPreviewPlaying = false,
        exportState = VideoEditorExportState.Exporting(0f),
        errorMessage = null,
      )
    }

    exportJob?.cancel()
    exportJob =
      viewModelScope.launch {
        try {
          val request =
            StudioExportRequest(
              clips = cur.clips,
              effectPresetId = cur.effectPresetId,
              selectedMusic = cur.selectedMusic,
              audioMix = cur.audioMix,
            )
          val result =
            videoExporter.exportEditedVideo(context.applicationContext, request) { progress ->
              _uiState.update { state ->
                if (state.exportState is VideoEditorExportState.Exporting) {
                  state.copy(exportState = VideoEditorExportState.Exporting(progress))
                } else {
                  state
                }
              }
            }

          result.fold(
            onSuccess = { file ->
              val uri =
                runCatching { StudioFilePaths.fileProviderUriFor(context, file) }
                  .getOrElse { Uri.fromFile(file) }
              _uiState.update {
                it.copy(
                  exportState = VideoEditorExportState.Exported(file, uri.toString()),
                  isPreviewingExported = true,
                  isPreviewPlaying = true,
                  previewPositionMs = 0L,
                )
              }
              if (publishAfterExport) {
                publishAfterExport = false
                submitExportedToPipeline(context, uri)
              }
            },
            onFailure = { err ->
              publishAfterExport = false
              _uiState.update {
                it.copy(
                  exportState =
                    VideoEditorExportState.Error(
                      err.localizedMessage ?: "Failed to export edited video."
                    ),
                )
              }
            },
          )
        } catch (e: CancellationException) {
          publishAfterExport = false
          _uiState.update { it.copy(exportState = VideoEditorExportState.Idle) }
          throw e
        }
      }
  }

  fun cancelExport() {
    publishAfterExport = false
    exportJob?.cancel()
    exportJob = null
    _uiState.update {
      it.copy(
        exportState = VideoEditorExportState.Idle,
        isPreviewingExported = false,
      )
    }
  }

  fun saveDraft(context: Context) {
    val cur = _uiState.value
    if (cur.isSavingDraft) return
    _uiState.update { it.copy(isSavingDraft = true, draftStatusMessage = null, errorMessage = null) }

    viewModelScope.launch {
      val materialized = StudioDraftMediaHelper.materializeDraftMedia(context, cur.toDraft())
      val withCover =
        if (materialized.coverFrameUriString.isNullOrBlank() && materialized.clips.isNotEmpty()) {
          val extracted =
            StudioDraftMediaHelper.extractCoverFrame(
              context = context,
              clips = materialized.clips,
              coverTimestampMs = materialized.coverTimestampMs,
              draftId = materialized.id,
            ).getOrNull()
          if (extracted != null) materialized.copy(coverFrameUriString = extracted.toString()) else materialized
        } else {
          materialized
        }

      localStore.saveDraft(withCover).fold(
        onSuccess = { saved ->
          _uiState.update {
            it.copy(
              isSavingDraft = false,
              clips = saved.clips,
              coverFrameUriString = saved.coverFrameUriString,
              draftStatusMessage = "Draft saved locally to your studio.",
            )
          }
        },
        onFailure = { err ->
          _uiState.update {
            it.copy(
              isSavingDraft = false,
              errorMessage = err.localizedMessage ?: "Could not save draft.",
            )
          }
        },
      )
    }
  }

  fun publishToVynoza(context: Context) {
    val cur = _uiState.value
    if (cur.hasMusicLicenseConflict) {
      _uiState.update {
        it.copy(
          errorMessage =
            cur.musicLicenseDecision.explanation +
              " Remove or mute the track before publishing.",
        )
      }
      return
    }
    if (cur.composedCaption.length > CreateStudioDraft.MAX_FIRESTORE_CAPTION_LENGTH) {
      _uiState.update {
        it.copy(errorMessage = "Caption exceeds the 300-character Firestore limit.")
      }
      return
    }

    _uiState.update { it.copy(hasStartedPublishInSession = true) }
    val exported = cur.exportState as? VideoEditorExportState.Exported
    if (exported != null && exported.file.exists() && exported.file.length() > 0L) {
      submitExportedToPipeline(context, Uri.parse(exported.uriString))
    } else {
      exportEditedVideo(context, thenPublish = true)
    }
  }

  private fun submitExportedToPipeline(context: Context, exportedUri: Uri) {
    val cur = _uiState.value
    val targetCreateVm = createViewModel ?: run {
      _uiState.update {
        it.copy(errorMessage = "Publishing service is not connected.")
      }
      return
    }
    pauseSyncedMusicPreview()
    _uiState.update { it.copy(isPreviewPlaying = false, hasStartedPublishInSession = true, errorMessage = null) }
    targetCreateVm.prepareEditedClipForPublish(
      context = context,
      uri = exportedUri,
      source = if (cur.templateId != null) ClipSource.TEMPLATE_STUDIO else ClipSource.CAMERA_CAPTURE,
      caption = cur.composedCaption,
      visibility = cur.privacy.firestoreValue,
      autoPublish = true,
    )
  }

  fun deleteSavedDraftAfterPublish() {
    val draftId = _uiState.value.draftId
    viewModelScope.launch {
      localStore.deleteDraft(draftId)
    }
  }

  fun dismissMessages() {
    _uiState.update { it.copy(errorMessage = null, draftStatusMessage = null) }
  }

  fun pauseOnLifecycleExit() {
    pauseSyncedMusicPreview()
    _uiState.update { it.copy(isPreviewPlaying = false) }
  }

  override fun onCleared() {
    exportJob?.cancel()
    pauseSyncedMusicPreview()
    super.onCleared()
  }

  private fun VideoEditorUiState.toDraft(): CreateStudioDraft {
    val resolvedTitle =
      title.ifBlank {
        caption.trim().take(36).ifBlank {
          clips.firstOrNull()?.displayName ?: "VYNOZA Draft"
        }
      }
    return CreateStudioDraft(
      id = draftId,
      title = resolvedTitle,
      createdAtMs = createdAtMs,
      clips = clips,
      selectedMusic = selectedMusic,
      audioMix = audioMix.normalized(),
      effectPresetId = effectPresetId,
      templateId = templateId,
      coverFrameUriString = coverFrameUriString,
      coverTimestampMs = coverTimestampMs,
      caption = caption,
      hashtags = hashtags,
      mentions = mentions,
      privacy = privacy,
    )
  }
}
