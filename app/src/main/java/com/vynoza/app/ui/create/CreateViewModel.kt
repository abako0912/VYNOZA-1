package com.vynoza.app.ui.create

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vynoza.app.data.repository.UserRepository
import com.vynoza.app.data.repository.VideoRepository
import com.vynoza.app.data.service.ClipSource
import com.vynoza.app.data.service.ClipValidationResult
import com.vynoza.app.data.service.LocalVideoClip
import com.vynoza.app.data.service.MediaStorageService
import com.vynoza.app.data.service.VideoUploadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PublishPostState {
  data object Idle : PublishPostState
  data class Uploading(val progress: Float?) : PublishPostState
  data object SavingPost : PublishPostState
  data class Success(val videoId: String, val playbackUrl: String) : PublishPostState
  data class Error(val message: String) : PublishPostState
}

private data class UploadedClipCache(
  val clipUri: String,
  val secureUrl: String,
  val publicId: String,
)

data class CreateUiState(
  val selectedClip: LocalVideoClip? = null,
  val validationError: String? = null,
  val caption: String = "",
  val visibility: String = "public",
  val publishState: PublishPostState = PublishPostState.Idle,
  val permissionDeniedNotice: Boolean = false,
) {
  val isPublishing: Boolean
    get() = publishState is PublishPostState.Uploading || publishState is PublishPostState.SavingPost
}

class CreateViewModel(
  private val mediaStorageService: MediaStorageService,
  private val currentUserId: String,
  private val postPublisher: suspend (
    caption: String,
    visibility: String,
    secureUrl: String,
    publicId: String,
  ) -> Result<String> = { _, _, _, _ ->
    Result.failure(IllegalStateException("Video repository is not configured."))
  },
) : ViewModel() {

  constructor(
    mediaStorageService: MediaStorageService,
    videoRepository: VideoRepository,
    userRepository: UserRepository,
    currentUserId: String,
  ) : this(
    mediaStorageService = mediaStorageService,
    currentUserId = currentUserId,
    postPublisher = { caption, visibility, secureUrl, publicId ->
      videoRepository.publishVideoPost(
        userId = currentUserId,
        caption = caption,
        visibility = visibility,
        secureUrl = secureUrl,
        publicId = publicId,
        userRepository = userRepository,
      )
    },
  )

  private val _uiState = MutableStateFlow(CreateUiState())
  val uiState: StateFlow<CreateUiState> = _uiState.asStateFlow()

  private var uploadedClipCache: UploadedClipCache? = null

  fun onClipAcquired(context: Context, uri: Uri, source: ClipSource) {
    if (_uiState.value.isPublishing) return
    val clip = mediaStorageService.inspectLocalClip(context, uri, source)
    val validation = mediaStorageService.validateClipForUpload(context, clip)
    val validationError = (validation as? ClipValidationResult.Invalid)?.reason
    uploadedClipCache = null

    _uiState.value =
      CreateUiState(
        selectedClip = clip,
        validationError = validationError,
        caption = "",
        visibility = "public",
        publishState = PublishPostState.Idle,
        permissionDeniedNotice = false,
      )
  }

  fun onCaptionChanged(caption: String) {
    if (_uiState.value.isPublishing) return
    _uiState.update {
      it.copy(
        caption = caption.take(MAX_CAPTION_LENGTH),
        publishState = if (it.publishState is PublishPostState.Error) PublishPostState.Idle else it.publishState,
      )
    }
  }

  fun onVisibilitySelected(visibility: String) {
    if (_uiState.value.isPublishing) return
    val normalized = if (visibility == "private") "private" else "public"
    _uiState.update { it.copy(visibility = normalized) }
  }

  fun prepareEditedClipForPublish(
    context: Context,
    uri: Uri,
    source: ClipSource,
    caption: String,
    visibility: String,
    autoPublish: Boolean = true,
  ) {
    if (_uiState.value.isPublishing) return
    val clip = mediaStorageService.inspectLocalClip(context, uri, source)
    val validation = mediaStorageService.validateClipForUpload(context, clip)
    val validationError = (validation as? ClipValidationResult.Invalid)?.reason
    val normalizedVisibility = if (visibility == "private") "private" else "public"
    uploadedClipCache = null

    _uiState.value =
      CreateUiState(
        selectedClip = clip,
        validationError = validationError,
        caption = caption.take(MAX_CAPTION_LENGTH),
        visibility = normalizedVisibility,
        publishState = PublishPostState.Idle,
        permissionDeniedNotice = false,
      )
    if (autoPublish && validationError == null) {
      publishSelectedClip(context)
    }
  }

  fun publishSelectedClip(context: Context) {
    val snapshot = _uiState.value
    if (snapshot.isPublishing) return

    val clip = snapshot.selectedClip ?: run {
      _uiState.update {
        it.copy(publishState = PublishPostState.Error("Please record or select a video first."))
      }
      return
    }

    val validation = mediaStorageService.validateClipForUpload(context, clip)
    if (validation is ClipValidationResult.Invalid) {
      _uiState.update {
        it.copy(
          validationError = validation.reason,
          publishState = PublishPostState.Error(validation.reason),
        )
      }
      return
    }

    val cleanCaption = snapshot.caption.trim()
    if (cleanCaption.length > MAX_CAPTION_LENGTH) {
      _uiState.update {
        it.copy(
          publishState = PublishPostState.Error("Caption must be 300 characters or fewer.")
        )
      }
      return
    }

    viewModelScope.launch {
      try {
        val clipKey = clip.uri.toString()
        val cached = uploadedClipCache?.takeIf { it.clipUri == clipKey }

        val (secureUrl, publicId) =
          if (cached != null) {
            cached.secureUrl to cached.publicId
          } else {
            _uiState.update { it.copy(publishState = PublishPostState.Uploading(progress = 0f)) }
            when (
              val uploadResult =
                mediaStorageService.uploadVideo(context.applicationContext, clip) { progress ->
                  _uiState.update { current ->
                    if (current.publishState is PublishPostState.Uploading) {
                      current.copy(publishState = PublishPostState.Uploading(progress))
                    } else {
                      current
                    }
                  }
                }
            ) {
              is VideoUploadResult.Success -> {
                uploadedClipCache =
                  UploadedClipCache(
                    clipUri = clipKey,
                    secureUrl = uploadResult.secureUrl,
                    publicId = uploadResult.publicId,
                  )
                uploadResult.secureUrl to uploadResult.publicId
              }
              is VideoUploadResult.Error -> {
                _uiState.update {
                  it.copy(publishState = PublishPostState.Error(uploadResult.message))
                }
                return@launch
              }
            }
          }

        _uiState.update { it.copy(publishState = PublishPostState.SavingPost) }
        val saveResult =
          postPublisher(cleanCaption, snapshot.visibility, secureUrl, publicId)

        saveResult
          .onSuccess { videoId ->
            uploadedClipCache = null
            _uiState.update {
              it.copy(
                publishState = PublishPostState.Success(videoId = videoId, playbackUrl = secureUrl)
              )
            }
          }
          .onFailure { error ->
            _uiState.update {
              it.copy(
                publishState =
                  PublishPostState.Error(
                    error.localizedMessage ?: "Video uploaded, but saving the post to Firestore failed. Tap Publish Post to retry."
                  )
              )
            }
          }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            publishState =
              PublishPostState.Error(
                e.localizedMessage ?: "Failed to publish video post."
              )
          )
        }
      }
    }
  }

  fun onPermissionDenied() {
    _uiState.update { it.copy(permissionDeniedNotice = true) }
  }

  fun clearSelection() {
    if (_uiState.value.isPublishing) return
    uploadedClipCache = null
    _uiState.value = CreateUiState()
  }

  private companion object {
    const val MAX_CAPTION_LENGTH = 300
  }
}
