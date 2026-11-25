package com.vynoza.app.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vynoza.app.core.model.UiState
import com.vynoza.app.core.model.UserPublicProfile
import com.vynoza.app.core.model.VideoComment
import com.vynoza.app.core.model.VideoPost
import com.vynoza.app.data.repository.VynozaUserSource
import com.vynoza.app.data.repository.VynozaVideoSource
import com.vynoza.app.ui.components.VideoCommentsDelegate
import com.vynoza.app.ui.components.VideoCommentsUiState
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ProfileContentTab {
  VIDEOS,
  SAVED,
}

data class ProfileUiData(
  val profile: UserPublicProfile? = null,
  val followersCount: Long = 0L,
  val followingCount: Long = 0L,
  val videos: List<VideoPost> = emptyList(),
  val savedVideos: List<VideoPost> = emptyList(),
  val likedVideoIds: Set<String> = emptySet(),
  val savedVideoIds: Set<String> = emptySet(),
)

data class EditProfileStatus(
  val isSaving: Boolean = false,
  val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModel(
  private val userRepository: VynozaUserSource,
  private val videoRepository: VynozaVideoSource,
  private val currentUserId: String,
) : ViewModel() {

  private val _editStatus = MutableStateFlow(EditProfileStatus())
  val editStatus: StateFlow<EditProfileStatus> = _editStatus.asStateFlow()

  private val _selectedTab = MutableStateFlow(ProfileContentTab.VIDEOS)
  val selectedTab: StateFlow<ProfileContentTab> = _selectedTab.asStateFlow()

  private val _likingVideoIds = MutableStateFlow<Set<String>>(emptySet())
  val likingVideoIds: StateFlow<Set<String>> = _likingVideoIds.asStateFlow()

  private val _savingVideoIds = MutableStateFlow<Set<String>>(emptySet())
  val savingVideoIds: StateFlow<Set<String>> = _savingVideoIds.asStateFlow()

  private val _actionError = MutableStateFlow<String?>(null)
  val actionError: StateFlow<String?> = _actionError.asStateFlow()

  private val extraSavedVideos = MutableStateFlow<Map<String, VideoPost>>(emptyMap())
  private val attemptedExtraLookups = mutableSetOf<String>()
  private val _refreshCounter = MutableStateFlow(0)

  val actorUserId: String
    get() = currentUserId

  private val commentsDelegate =
    VideoCommentsDelegate(
      scope = viewModelScope,
      videoRepository = videoRepository,
      currentUserId = currentUserId,
    )
  val commentsState: StateFlow<VideoCommentsUiState> = commentsDelegate.uiState

  val profileState: StateFlow<UiState<ProfileUiData>> =
    _refreshCounter
      .flatMapLatest {
        combine(
          combine(
            userRepository.observePublicProfile(currentUserId),
            userRepository.observeFollowers(currentUserId),
            userRepository.observeFollowing(currentUserId),
          ) { profile, followers, following ->
            Triple(profile, followers, following)
          },
          videoRepository.observeUserVideos(currentUserId),
          videoRepository.observePublicFeed(),
          videoRepository.observeLikedVideoIds(currentUserId),
          combine(videoRepository.observeSavedVideoIds(currentUserId), extraSavedVideos) { ids, extras ->
            ids to extras
          },
        ) { (profile, followers, following), videos, publicVideos, likedVideoIds, (savedVideoIds, extras) ->
          val knownById = LinkedHashMap<String, VideoPost>()
          publicVideos.forEach { knownById[it.id] = it }
          videos.forEach { knownById[it.id] = it }
          extras.forEach { (id, post) -> if (id !in knownById) knownById[id] = post }

          val missingIds = savedVideoIds.filter { it !in knownById && it !in attemptedExtraLookups }
          if (missingIds.isNotEmpty()) {
            fetchMissingSavedVideos(missingIds)
          }

          val resolvedSavedVideos = savedVideoIds.mapNotNull { knownById[it] }
          val effectiveFollowersCount = maxOf(profile?.followersCount ?: 0L, followers.size.toLong())
          val effectiveFollowingCount = maxOf(profile?.followingCount ?: 0L, following.size.toLong())
          UiState.Success(
            ProfileUiData(
              profile = profile,
              followersCount = effectiveFollowersCount,
              followingCount = effectiveFollowingCount,
              videos = videos,
              savedVideos = resolvedSavedVideos,
              likedVideoIds = likedVideoIds,
              savedVideoIds = savedVideoIds.toSet(),
            )
          ) as UiState<ProfileUiData>
        }
      }
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing profile", rootError)
        emit(UiState.Error(rootError.message ?: "Failed to load profile"))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading,
      )

  fun retry() {
    _actionError.value = null
    _refreshCounter.value += 1
  }

  fun saveProfile(
    displayName: String,
    username: String,
    bio: String,
    onSuccess: () -> Unit,
  ) {
    viewModelScope.launch {
      _editStatus.value = EditProfileStatus(isSaving = true)
      val result = userRepository.updatePublicProfile(currentUserId, displayName, username, bio)
      result
        .onSuccess {
          _editStatus.value = EditProfileStatus(isSaving = false)
          onSuccess()
        }
        .onFailure { err ->
          _editStatus.value =
            EditProfileStatus(
              isSaving = false,
              errorMessage = err.localizedMessage ?: "Failed to update profile",
            )
        }
    }
  }

  fun toggleLike(
    video: VideoPost,
    currentlyLiked: Boolean,
    currentUsername: String? = null,
  ) {
    val videoId = video.id
    if (videoId.isBlank() || videoId in _likingVideoIds.value) return
    _likingVideoIds.value = _likingVideoIds.value + videoId
    _actionError.value = null

    viewModelScope.launch {
      try {
        videoRepository.toggleVideoLike(
          userId = currentUserId,
          videoId = videoId,
          expectedCurrentlyLiked = currentlyLiked,
          actorUsername = currentUsername,
        ).onFailure { err ->
          _actionError.value = err.localizedMessage ?: "Unable to update like right now."
        }
      } finally {
        _likingVideoIds.value = _likingVideoIds.value - videoId
      }
    }
  }

  fun clearActionError() {
    _actionError.value = null
  }

  fun clearEditError() {
    _editStatus.value = _editStatus.value.copy(errorMessage = null)
  }

  fun selectTab(tab: ProfileContentTab) {
    _selectedTab.value = tab
  }

  fun toggleSave(
    video: VideoPost,
    currentlySaved: Boolean,
  ) {
    val videoId = video.id
    if (videoId.isBlank() || videoId in _savingVideoIds.value) return
    _savingVideoIds.value = _savingVideoIds.value + videoId
    _actionError.value = null

    viewModelScope.launch {
      try {
        videoRepository.toggleVideoSave(
          userId = currentUserId,
          videoId = videoId,
          expectedCurrentlySaved = currentlySaved,
        ).onFailure { err ->
          _actionError.value = err.localizedMessage ?: "Unable to update saved videos right now."
        }
      } finally {
        _savingVideoIds.value = _savingVideoIds.value - videoId
      }
    }
  }

  private fun fetchMissingSavedVideos(videoIds: List<String>) {
    attemptedExtraLookups.addAll(videoIds)
    viewModelScope.launch {
      val fetched = mutableMapOf<String, VideoPost>()
      for (id in videoIds) {
        videoRepository.getVideoById(id).onSuccess { post ->
          if (post != null) {
            fetched[id] = post
          }
        }
      }
      if (fetched.isNotEmpty()) {
        extraSavedVideos.value = extraSavedVideos.value + fetched
      }
    }
  }

  fun openComments(videoId: String) = commentsDelegate.openComments(videoId)

  fun closeComments() = commentsDelegate.closeComments()

  fun retryLoadComments() = commentsDelegate.retryLoad()

  fun onCommentDraftChanged(text: String) = commentsDelegate.onDraftChanged(text)

  fun submitComment() = commentsDelegate.submitComment()

  fun deleteComment(comment: VideoComment) = commentsDelegate.deleteComment(comment)

  fun clearCommentError() = commentsDelegate.clearActionError()

  private companion object {
    const val TAG = "ProfileVM"
    const val STOP_TIMEOUT_MILLIS = 5000L
  }
}
