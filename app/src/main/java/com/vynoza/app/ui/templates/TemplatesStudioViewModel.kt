package com.vynoza.app.ui.templates

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vynoza.app.R
import com.vynoza.app.core.templates.ExportTargetAction
import com.vynoza.app.core.templates.SelectedTemplateMedia
import com.vynoza.app.core.templates.TemplateExportState
import com.vynoza.app.core.templates.TemplateMediaKind
import com.vynoza.app.core.templates.TemplateParameters
import com.vynoza.app.core.templates.VynozaTemplateCatalog
import com.vynoza.app.core.templates.VynozaTemplateId
import com.vynoza.app.core.templates.VynozaTemplateSpec
import com.vynoza.app.data.service.CloudinaryMediaStorageService
import com.vynoza.app.data.templates.PhotoTemplateProcessor
import com.vynoza.app.data.templates.TemplateMediaStorage
import com.vynoza.app.data.templates.VideoTemplateProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class TemplatesStudioUiState(
  val galleryThumbnails: Map<VynozaTemplateId, Bitmap> = emptyMap(),
  val selectedTemplate: VynozaTemplateSpec = VynozaTemplateCatalog.templates.first(),
  val parameters: TemplateParameters = TemplateParameters.defaultsFor(VynozaTemplateId.CLONE_ECHO),
  val selectedMedia: SelectedTemplateMedia? = null,
  val originalPhotoBitmap: Bitmap? = null,
  val renderedPhotoPreview: Bitmap? = null,
  val isRenderingPreview: Boolean = false,
  val showOriginalComparison: Boolean = false,
  val mediaLoadError: String? = null,
  val exportState: TemplateExportState = TemplateExportState.Idle,
) {
  val isExporting: Boolean
    get() = exportState is TemplateExportState.Processing

  val isUsingSampleMedia: Boolean
    get() = selectedMedia == null
}

class TemplatesStudioViewModel(
  private val videoTemplateProcessor: VideoTemplateProcessor = VideoTemplateProcessor(),
) : ViewModel() {

  private val _uiState = MutableStateFlow(TemplatesStudioUiState())
  val uiState: StateFlow<TemplatesStudioUiState> = _uiState.asStateFlow()

  private var sampleSourceBitmap: Bitmap? = null
  private var sampleTempUri: Uri? = null
  private var previewJob: Job? = null
  private var exportJob: Job? = null
  private var thumbnailsLoaded = false

  fun initializeStudio(context: Context) {
    val appCtx = context.applicationContext
    if (!thumbnailsLoaded) {
      thumbnailsLoaded = true
      loadGalleryThumbnails(appCtx)
    }
    if (sampleSourceBitmap == null) {
      viewModelScope.launch {
        val decoded =
          PhotoTemplateProcessor.decodeDrawableSample(
            appCtx,
            R.drawable.img_template_sample_portrait,
            PhotoTemplateProcessor.PREVIEW_MAX_DIMENSION,
          )
        decoded.onSuccess { bmp ->
          sampleSourceBitmap = bmp
          if (_uiState.value.selectedMedia == null) {
            _uiState.update { it.copy(originalPhotoBitmap = bmp) }
            schedulePhotoPreviewRender(bmp)
          }
        }
      }
    }
  }

  private fun loadGalleryThumbnails(appContext: Context) {
    viewModelScope.launch {
      val sampleThumb =
        PhotoTemplateProcessor.decodeDrawableSample(
          appContext,
          R.drawable.img_template_sample_portrait,
          PhotoTemplateProcessor.GALLERY_THUMB_MAX_DIMENSION,
        ).getOrNull() ?: return@launch

      val map = LinkedHashMap<VynozaTemplateId, Bitmap>()
      withContext(Dispatchers.Default) {
        VynozaTemplateCatalog.templates.forEach { spec ->
          val rendered =
            PhotoTemplateProcessor.applyTemplate(
              sampleThumb,
              spec.id,
              spec.defaultParameters,
            )
          map[spec.id] = rendered
        }
      }
      _uiState.update { it.copy(galleryThumbnails = map) }
    }
  }

  fun selectTemplate(context: Context, templateId: VynozaTemplateId) {
    val spec = VynozaTemplateCatalog.findById(templateId)
    val defaults = TemplateParameters.defaultsFor(templateId)
    _uiState.update {
      it.copy(
        selectedTemplate = spec,
        parameters = defaults,
        showOriginalComparison = false,
        mediaLoadError = null,
        exportState = TemplateExportState.Idle,
      )
    }
    initializeStudio(context)
    val media = _uiState.value.selectedMedia
    if (media == null || media.kind == TemplateMediaKind.PHOTO) {
      val source = _uiState.value.originalPhotoBitmap ?: sampleSourceBitmap
      if (source != null) {
        schedulePhotoPreviewRender(source)
      }
    }
  }

  fun onMediaPicked(context: Context, uri: Uri) {
    if (_uiState.value.isExporting) return
    val appCtx = context.applicationContext
    val inspected = TemplateMediaStorage.inspectSelectedMedia(appCtx, uri)
    if (inspected == null) {
      _uiState.update {
        it.copy(
          mediaLoadError =
            "Unsupported media file. Please choose a valid photo (JPG/PNG/WebP) or video (MP4/MOV/WebM)."
        )
      }
      return
    }

    if (inspected.kind == TemplateMediaKind.VIDEO) {
      previewJob?.cancel()
      _uiState.update {
        it.copy(
          selectedMedia = inspected,
          originalPhotoBitmap = null,
          renderedPhotoPreview = null,
          isRenderingPreview = false,
          showOriginalComparison = false,
          mediaLoadError = null,
          exportState = TemplateExportState.Idle,
        )
      }
    } else {
      previewJob?.cancel()
      _uiState.update {
        it.copy(
          selectedMedia = inspected,
          isRenderingPreview = true,
          showOriginalComparison = false,
          mediaLoadError = null,
          exportState = TemplateExportState.Idle,
        )
      }
      viewModelScope.launch {
        val result =
          PhotoTemplateProcessor.decodeSampledBitmap(
            appCtx,
            uri,
            PhotoTemplateProcessor.PREVIEW_MAX_DIMENSION,
          )
        result
          .onSuccess { bmp ->
            _uiState.update { it.copy(originalPhotoBitmap = bmp) }
            schedulePhotoPreviewRender(bmp)
          }
          .onFailure { error ->
            _uiState.update {
              it.copy(
                selectedMedia = null,
                originalPhotoBitmap = sampleSourceBitmap,
                isRenderingPreview = false,
                mediaLoadError = error.localizedMessage ?: "Failed to decode selected photo.",
              )
            }
            sampleSourceBitmap?.let { schedulePhotoPreviewRender(it) }
          }
      }
    }
  }

  fun clearSelectedMedia() {
    if (_uiState.value.isExporting) return
    val fallback = sampleSourceBitmap
    _uiState.update {
      it.copy(
        selectedMedia = null,
        originalPhotoBitmap = fallback,
        showOriginalComparison = false,
        mediaLoadError = null,
        exportState = TemplateExportState.Idle,
      )
    }
    if (fallback != null) {
      schedulePhotoPreviewRender(fallback)
    }
  }

  fun updateParameters(transform: (TemplateParameters) -> TemplateParameters) {
    if (_uiState.value.isExporting) return
    _uiState.update { current ->
      current.copy(
        parameters = transform(current.parameters),
        exportState =
          if (current.exportState is TemplateExportState.Error) TemplateExportState.Idle
          else current.exportState,
      )
    }
    val currentMedia = _uiState.value.selectedMedia
    if (currentMedia == null || currentMedia.kind == TemplateMediaKind.PHOTO) {
      val source = _uiState.value.originalPhotoBitmap ?: sampleSourceBitmap
      if (source != null) {
        schedulePhotoPreviewRender(source, debounceMs = 45L)
      }
    }
  }

  fun resetParameters() {
    if (_uiState.value.isExporting) return
    val id = _uiState.value.selectedTemplate.id
    updateParameters { TemplateParameters.defaultsFor(id) }
  }

  fun toggleCompareOriginal(forceValue: Boolean? = null) {
    _uiState.update { current ->
      current.copy(showOriginalComparison = forceValue ?: !current.showOriginalComparison)
    }
  }

  fun saveEditedPhotoToDevice(context: Context) {
    if (_uiState.value.isExporting) return
    val appCtx = context.applicationContext
    val snapshot = _uiState.value

    exportJob =
      viewModelScope.launch {
        try {
          val sourceUri = resolveActivePhotoUri(appCtx, snapshot)
          if (sourceUri == null) {
            _uiState.update {
              it.copy(
                exportState =
                  TemplateExportState.Error("Photo export requires a selected photo or loaded sample.")
              )
            }
            return@launch
          }
          _uiState.update {
            it.copy(
              exportState =
                TemplateExportState.Processing(
                  action = ExportTargetAction.SAVE_PHOTO_TO_DEVICE,
                  progress = 0.05f,
                  statusLabel = "Applying ${snapshot.selectedTemplate.title} pixels...",
                )
            )
          }
          val renderedFile =
            PhotoTemplateProcessor.renderAndSavePhoto(
              context = appCtx,
              sourceUri = sourceUri,
              templateId = snapshot.selectedTemplate.id,
              params = snapshot.parameters,
            ) { progress ->
              _uiState.update { current ->
                if (current.exportState is TemplateExportState.Processing) {
                  current.copy(
                    exportState =
                      TemplateExportState.Processing(
                        action = ExportTargetAction.SAVE_PHOTO_TO_DEVICE,
                        progress = progress * 0.85f,
                        statusLabel = "Rendering high-res photo (${(progress * 85).toInt()}%)...",
                      )
                  )
                } else current
              }
            }.getOrThrow()

          val galleryUri =
            TemplateMediaStorage.saveJpegToGallery(
              appCtx,
              renderedFile,
              "vynoza_${snapshot.selectedTemplate.id.routeArg}",
            ).getOrThrow()

          _uiState.update {
            it.copy(
              exportState =
                TemplateExportState.SavedPhoto(
                  filePath = renderedFile.absolutePath,
                  contentUri = galleryUri,
                )
            )
          }
        } catch (e: CancellationException) {
          _uiState.update { it.copy(exportState = TemplateExportState.Idle) }
          throw e
        } catch (e: Exception) {
          _uiState.update {
            it.copy(
              exportState =
                TemplateExportState.Error(e.localizedMessage ?: "Failed to save edited photo.")
            )
          }
        }
      }
  }

  fun exportAndSaveVideoToDevice(context: Context) {
    startVideoExport(context, ExportTargetAction.SAVE_VIDEO_TO_DEVICE, onReadyForPublish = null)
  }

  fun exportVideoForVynozaPublish(context: Context, onReadyForPublish: (Uri) -> Unit) {
    startVideoExport(
      context = context,
      action = ExportTargetAction.PREPARE_VIDEO_FOR_PUBLISH,
      onReadyForPublish = onReadyForPublish,
    )
  }

  private fun startVideoExport(
    context: Context,
    action: ExportTargetAction,
    onReadyForPublish: ((Uri) -> Unit)?,
  ) {
    if (_uiState.value.isExporting) return
    val appCtx = context.applicationContext
    val snapshot = _uiState.value

    exportJob =
      viewModelScope.launch {
        try {
          _uiState.update {
            it.copy(
              exportState =
                TemplateExportState.Processing(
                  action = action,
                  progress = 0.02f,
                  statusLabel = "Preparing ${snapshot.selectedTemplate.title} video render...",
                )
            )
          }

          val media = snapshot.selectedMedia
          val exportResult =
            if (media != null && media.kind == TemplateMediaKind.VIDEO) {
              videoTemplateProcessor.exportVideoWithTemplate(
                context = appCtx,
                sourceUri = media.uri,
                estimatedSizeBytes = media.sizeBytes,
                templateId = snapshot.selectedTemplate.id,
                params = snapshot.parameters,
              ) { p ->
                updateExportProgress(action, p, "Rendering MP4 video")
              }
            } else {
              val photoUri =
                resolveActivePhotoUri(appCtx, snapshot)
                  ?: return@launch _uiState.update {
                    it.copy(
                      exportState = TemplateExportState.Error("Select a photo or video first.")
                    )
                  }
              videoTemplateProcessor.exportPhotoAsMotionVideo(
                context = appCtx,
                sourcePhotoUri = photoUri,
                templateId = snapshot.selectedTemplate.id,
                params = snapshot.parameters,
              ) { p ->
                updateExportProgress(action, p, "Encoding template motion clip")
              }
            }

          val mp4File = exportResult.getOrThrow()
          if (
            action == ExportTargetAction.PREPARE_VIDEO_FOR_PUBLISH &&
              mp4File.length() > CloudinaryMediaStorageService.MAX_VIDEO_SIZE_BYTES
          ) {
            _uiState.update {
              it.copy(
                exportState =
                  TemplateExportState.Error(
                    "Exported video exceeds VYNOZA's 100 MB upload limit. Try a shorter clip."
                  )
              )
            }
            return@launch
          }

          val providerUri = TemplateMediaStorage.fileProviderUriFor(appCtx, mp4File)
          var savedToGallery = false
          if (action == ExportTargetAction.SAVE_VIDEO_TO_DEVICE) {
            TemplateMediaStorage.saveMp4ToGallery(
              appCtx,
              mp4File,
              "vynoza_${snapshot.selectedTemplate.id.routeArg}",
            ).getOrThrow()
            savedToGallery = true
          }

          _uiState.update {
            it.copy(
              exportState =
                TemplateExportState.ExportedVideo(
                  filePath = mp4File.absolutePath,
                  fileUri = providerUri,
                  savedToGallery = savedToGallery,
                  readyForPublish = action == ExportTargetAction.PREPARE_VIDEO_FOR_PUBLISH,
                )
            )
          }
          onReadyForPublish?.invoke(providerUri)
        } catch (e: CancellationException) {
          _uiState.update { it.copy(exportState = TemplateExportState.Idle) }
          throw e
        } catch (e: Exception) {
          _uiState.update {
            it.copy(
              exportState =
                TemplateExportState.Error(
                  e.localizedMessage ?: "Video export failed on this device."
                )
            )
          }
        }
      }
  }

  fun cancelExport() {
    exportJob?.cancel()
    exportJob = null
    _uiState.update { it.copy(exportState = TemplateExportState.Idle) }
  }

  fun dismissExportNotice() {
    if (_uiState.value.isExporting) return
    _uiState.update { it.copy(exportState = TemplateExportState.Idle) }
  }

  private fun updateExportProgress(action: ExportTargetAction, progress: Float, prefix: String) {
    val clamped = progress.coerceIn(0f, 1f)
    _uiState.update { current ->
      if (current.exportState is TemplateExportState.Processing) {
        current.copy(
          exportState =
            TemplateExportState.Processing(
              action = action,
              progress = clamped,
              statusLabel = "$prefix... ${(clamped * 100).toInt()}%",
            )
        )
      } else current
    }
  }

  private fun schedulePhotoPreviewRender(source: Bitmap, debounceMs: Long = 0L) {
    previewJob?.cancel()
    val templateId = _uiState.value.selectedTemplate.id
    val params = _uiState.value.parameters
    previewJob =
      viewModelScope.launch {
        if (debounceMs > 0L) delay(debounceMs)
        _uiState.update { it.copy(isRenderingPreview = true) }
        try {
          val rendered = PhotoTemplateProcessor.renderPreview(source, templateId, params)
          _uiState.update { it.copy(renderedPhotoPreview = rendered, isRenderingPreview = false) }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          _uiState.update {
            it.copy(
              isRenderingPreview = false,
              mediaLoadError = e.localizedMessage ?: "Failed to render template preview.",
            )
          }
        }
      }
  }

  private suspend fun resolveActivePhotoUri(
    appCtx: Context,
    snapshot: TemplatesStudioUiState,
  ): Uri? {
    val selected = snapshot.selectedMedia
    if (selected != null && selected.kind == TemplateMediaKind.PHOTO) {
      return selected.uri
    }
    sampleTempUri?.let { return it }
    val sample =
      sampleSourceBitmap
        ?: PhotoTemplateProcessor.decodeDrawableSample(
          appCtx,
          R.drawable.img_template_sample_portrait,
          PhotoTemplateProcessor.EXPORT_MAX_DIMENSION,
        ).getOrNull()
        ?: return null
    sampleSourceBitmap = sample
    val file =
      withContext(Dispatchers.IO) {
        val out = File(appCtx.cacheDir, "templates/sample_portrait_source.jpg").apply {
          parentFile?.mkdirs()
        }
        FileOutputStream(out).use { stream ->
          sample.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        }
        out
      }
    return Uri.fromFile(file).also { sampleTempUri = it }
  }
}
