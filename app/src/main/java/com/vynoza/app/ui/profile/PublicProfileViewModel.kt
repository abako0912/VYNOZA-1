package com.vynoza.app.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestoreException
import com.vynoza.app.core.model.UiState
import com.vynoza.app.core.model.UserPublicProfile
import com.vynoza.app.core.model.VideoComment
import com.vynoza.app.core.model.VideoPost
import com.vynoza.app.data.repository.UserRepository
import com.vynoza.app.data.repository.VynozaUserSource
import com.vynoza.app.data.repository.VynozaVideoSource
import com.vynoza.app.ui.components.VideoCommentsDelegate
import com.vynoza.app.ui.components.VideoCommentsUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PublicProfileUiData(
  val profile: UserPublicProfile,
  val followersCount: Long = 0L,
  val videos: List<VideoPost> = emptyList(),
  val publicVideosCount: Long = 0L,
  val isFollowing: Boolean = false,
  val isOwnProfile: Boolean = false,
  val likedVideoIds: Set<String> = emptySet(),
  val savedVideoIds: Set<String> = emptySet(),
  val currentUsername: String? = null,
  val canLoadMore: Boolean = false,
  val isLoadingMore: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PublicProfileViewModel(
  private val userRepository: VynozaUserSource,
  private val videoRepository: VynozaVideoSource,
  val targetUserId: String,
  private val currentUserId: String,
) : ViewModel() {

  private val _videoLimit = MutableStateFlow(PAGE_SIZE)
  private val _isLoadingMore = MutableStateFlow(false)
  private val _refreshCounter = MutableStateFlow(0)

  private val _isFollowInFlight = MutableStateFlow(false)
  val isFollowInFlight: StateFlow<Boolean> = _isFollowInFlight.asStateFlow()

  private val _optimisticFollowOverride = MutableStateFlow<Boolean?>(null)

  private val _likingVideoIds = MutableStateFlow<Set<String>>(emptySet())
  val likingVideoIds: StateFlow<Set<String>> = _likingVideoIds.asStateFlow()

  private val _savingVideoIds = MutableStateFlow<Set<String>>(emptySet())
  val savingVideoIds: StateFlow<Set<String>> = _savingVideoIds.asStateFlow()

  private val _optimisticSaveOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())

  private val _actionError = MutableStateFlow<String?>(null)
  val actionError: StateFlow<String?> = _actionError.asStateFlow()

  val actorUserId: String
    get() = currentUserId

  private val commentsDelegate =
    VideoCommentsDelegate(
      scope = viewModelScope,
      videoRepository = videoRepository,
      currentUserId = currentUserId,
    )
  val commentsState: StateFlow<VideoCommentsUiState> = commentsDelegate.uiState

  private val paginatedPublicVideos =
    combine(_videoLimit, _refreshCounter) { limit, _ -> limit }
      .flatMapLatest { limit ->
        videoRepository
          .observeUserPublicVideos(userId = targetUserId, limit = limit)
          .map { videos ->
            _isLoadingMore.value = false
            videos to limit
          }
      }

  private val observedProfileAndFollowers =
    _refreshCounter.flatMapLatest {
      combine(
        userRepository.observePublicProfile(targetUserId),
        userRepository.observeFollowers(targetUserId),
      ) { profile, followers ->
        profile to followers
      }
    }

  val publicProfileState: StateFlow<UiState<PublicProfileUiData>> =
    combine(
      combine(
        observedProfileAndFollowers,
        paginatedPublicVideos,
        _isLoadingMore,
      ) { profileAndFollowers, videosAndLimit, isLoadingMore ->
        Triple(profileAndFollowers, videosAndLimit, isLoadingMore)
      },
      combine(
        userRepository.observeFollowing(currentUserId),
        _optimisticFollowOverride,
        userRepository.observePublicProfile(currentUserId),
      ) { followingEdges, followOverride, myProfile ->
        Triple(followingEdges, followOverride, myProfile)
      },
      combine(
        videoRepository.observeLikedVideoIds(currentUserId),
        videoRepository.observeSavedVideoIds(currentUserId),
        _optimisticSaveOverrides,
      ) { likedIds, savedIds, saveOverrides ->
        val effectiveSaved = savedIds.toMutableSet()
        saveOverrides.forEach { (id, saved) ->
          if (saved) effectiveSaved.add(id) else effectiveSaved.remove(id)
        }
        likedIds to effectiveSaved.toSet()
      },
    ) { (profileAndFollowers, videosAndLimit, isLoadingMore), (followingEdges, followOverride, myProfile), (likedIds, savedIds) ->
      val (profile, followers) = profileAndFollowers
      if (profile == null) {
        UiState.Error("This creator profile is unavailable.")
      } else {
        val (videos, activeLimit) = videosAndLimit
        val isOwnProfile = targetUserId == currentUserId
        val persistedFollowing = followingEdges.any { it.targetUserId == targetUserId }
        val effectiveFollowing = if (isOwnProfile) false else (followOverride ?: persistedFollowing)
        val canLoadMore = videos.size.toLong() >= activeLimit && activeLimit < MAX_VIDEO_LIMIT
        val publicVideosCount =
          if (!canLoadMore) {
            videos.size.toLong()
          } else {
            profile.videosCount.coerceAtLeast(videos.size.toLong())
          }
        val effectiveFollowersCount = maxOf(profile.followersCount, followers.size.toLong())

        UiState.Success(
          PublicProfileUiData(
            profile = profile,
            followersCount = effectiveFollowersCount,
            videos = videos,
            publicVideosCount = publicVideosCount,
            isFollowing = effectiveFollowing,
            isOwnProfile = isOwnProfile,
            likedVideoIds = likedIds,
            savedVideoIds = savedIds,
            currentUsername = myProfile?.username,
            canLoadMore = canLoadMore,
            isLoadingMore = isLoadingMore,
          )
        )
      }
    }
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing public profile $targetUserId", rootError)
        emit(UiState.Error(rootError.message ?: "Failed to load creator profile."))
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

  fun loadMoreVideos() {
    val currentData = (publicProfileState.value as? UiState.Success)?.data ?: return
    if (!currentData.canLoadMore || _isLoadingMore.value) return
    val nextLimit = (_videoLimit.value + PAGE_SIZE).coerceAtMost(MAX_VIDEO_LIMIT)
    if (nextLimit <= _videoLimit.value) return
    _isLoadingMore.value = true
    _videoLimit.value = nextLimit
  }

  fun toggleFollow() {
    val currentData = (publicProfileState.value as? UiState.Success)?.data ?: return
    if (currentData.isOwnProfile || _isFollowInFlight.value) return
    val currentlyFollowing = currentData.isFollowing
    val desiredFollowing = !currentlyFollowing

    _isFollowInFlight.value = true
    _optimisticFollowOverride.value = desiredFollowing
    _actionError.value = null

    viewModelScope.launch {
      try {
        val result =
          if (currentlyFollowing) {
            userRepository.unfollowCreator(
              currentUserId = currentUserId,
              targetUserId = currentData.profile.userId,
            )
          } else {
            val resolvedActorUsername =
              currentData.currentUsername
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.length in 3..30 }
                ?: UserRepository.deriveValidUsername(null, currentUserId)
            userRepository.followCreator(
              currentUserId = currentUserId,
              currentUsername = resolvedActorUsername,
              target = currentData.profile,
            )
          }
        result.onFailure { err ->
          _actionError.value = err.localizedMessage ?: "Unable to update follow status right now."
        }
      } finally {
        _optimisticFollowOverride.value = null
        _isFollowInFlight.value = false
      }
    }
  }

  fun toggleLike(
    video: VideoPost,
    currentlyLiked: Boolean,
  ) {
    val videoId = video.id
    if (videoId.isBlank() || videoId in _likingVideoIds.value) return
    val actorUsername = (publicProfileState.value as? UiState.Success)?.data?.currentUsername
    _likingVideoIds.value = _likingVideoIds.value + videoId
    _actionError.value = null

    viewModelScope.launch {
      try {
        videoRepository.toggleVideoLike(
          userId = currentUserId,
          videoId = videoId,
          expectedCurrentlyLiked = currentlyLiked,
          actorUsername = actorUsername,
        ).onFailure { err ->
          _actionError.value = err.localizedMessage ?: "Unable to update like right now."
        }
      } finally {
        _likingVideoIds.value = _likingVideoIds.value - videoId
      }
    }
  }

  fun toggleSave(
    video: VideoPost,
    currentlySaved: Boolean,
  ) {
    val videoId = video.id
    if (videoId.isBlank() || videoId in _savingVideoIds.value) return
    val desiredSaved = !currentlySaved
    _savingVideoIds.value = _savingVideoIds.value + videoId
    _optimisticSaveOverrides.value = _optimisticSaveOverrides.value + (videoId to desiredSaved)
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
        _optimisticSaveOverrides.value = _optimisticSaveOverrides.value - videoId
        _savingVideoIds.value = _savingVideoIds.value - videoId
      }
    }
  }

  fun clearActionError() {
    _actionError.value = null
  }

  fun showActionError(message: String) {
    _actionError.value = message
  }

  fun openComments(videoId: String) = commentsDelegate.openComments(videoId)

  fun closeComments() = commentsDelegate.closeComments()

  fun retryLoadComments() = commentsDelegate.retryLoad()

  fun onCommentDraftChanged(text: String) = commentsDelegate.onDraftChanged(text)

  fun submitComment() = commentsDelegate.submitComment()

  fun deleteComment(comment: VideoComment) = commentsDelegate.deleteComment(comment)

  fun clearCommentError() = commentsDelegate.clearActionError()

  companion object {
    const val PAGE_SIZE = 24L
    const val MAX_VIDEO_LIMIT = 120L
    private const val TAG = "PublicProfileVM"
    private const val STOP_TIMEOUT_MILLIS = 5000L
  }
}
