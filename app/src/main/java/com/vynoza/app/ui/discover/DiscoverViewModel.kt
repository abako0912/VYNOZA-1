package com.vynoza.app.ui.discover

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DiscoverFilter {
  ALL,
  CREATORS,
  VIDEOS,
}

data class DiscoverData(
  val creators: List<UserPublicProfile> = emptyList(),
  val videos: List<VideoPost> = emptyList(),
  val followedUserIds: Set<String> = emptySet(),
  val likedVideoIds: Set<String> = emptySet(),
  val savedVideoIds: Set<String> = emptySet(),
  val currentUsername: String = "vynoza_user",
)

class DiscoverViewModel(
  private val userRepository: VynozaUserSource,
  private val videoRepository: VynozaVideoSource,
  private val currentUserId: String,
) : ViewModel() {

  private val _searchQuery = MutableStateFlow("")
  val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

  private val _filter = MutableStateFlow(DiscoverFilter.ALL)
  val filter: StateFlow<DiscoverFilter> = _filter.asStateFlow()

  private val _actionError = MutableStateFlow<String?>(null)
  val actionError: StateFlow<String?> = _actionError.asStateFlow()

  private val _likingVideoIds = MutableStateFlow<Set<String>>(emptySet())
  val likingVideoIds: StateFlow<Set<String>> = _likingVideoIds.asStateFlow()

  private val _savingVideoIds = MutableStateFlow<Set<String>>(emptySet())
  val savingVideoIds: StateFlow<Set<String>> = _savingVideoIds.asStateFlow()

  private val _followingUserIds = MutableStateFlow<Set<String>>(emptySet())
  val followingUserIds: StateFlow<Set<String>> = _followingUserIds.asStateFlow()

  private val _optimisticFollowOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())

  val actorUserId: String
    get() = currentUserId

  private val commentsDelegate =
    VideoCommentsDelegate(
      scope = viewModelScope,
      videoRepository = videoRepository,
      currentUserId = currentUserId,
    )
  val commentsState: StateFlow<VideoCommentsUiState> = commentsDelegate.uiState

  val discoverState: StateFlow<UiState<DiscoverData>> =
    combine(
      userRepository.observeDiscoverCreators(),
      videoRepository.observePublicFeed(),
      combine(
        userRepository.observeFollowing(currentUserId),
        _optimisticFollowOverrides,
      ) { followingEdges, followOverrides ->
        val effectiveFollowed = followingEdges.mapTo(mutableSetOf()) { it.targetUserId }
        followOverrides.forEach { (id, followed) ->
          if (followed) effectiveFollowed.add(id) else effectiveFollowed.remove(id)
        }
        effectiveFollowed.toSet()
      },
      userRepository.observePublicProfile(currentUserId),
      combine(
        videoRepository.observeLikedVideoIds(currentUserId),
        videoRepository.observeSavedVideoIds(currentUserId),
        _searchQuery,
        _filter,
      ) { likedIds, savedIds, q, f ->
        listOf(likedIds, savedIds.toSet(), q, f)
      },
    ) { creators, videos, effectiveFollowedIds, myProfile, combinedExtras ->
      @Suppress("UNCHECKED_CAST")
      val likedVideoIds = combinedExtras[0] as Set<String>
      @Suppress("UNCHECKED_CAST")
      val savedVideoIds = combinedExtras[1] as Set<String>
      val query = combinedExtras[2] as String
      val activeFilter = combinedExtras[3] as DiscoverFilter
      val normalizedQuery = query.trim().lowercase()
      val filteredCreators =
        if (activeFilter == DiscoverFilter.VIDEOS) {
          emptyList()
        } else {
          creators
            .filter { it.userId != currentUserId }
            .filter { creator ->
              normalizedQuery.isEmpty() ||
                creator.username.lowercase().contains(normalizedQuery) ||
                creator.displayName.lowercase().contains(normalizedQuery) ||
                creator.bio.lowercase().contains(normalizedQuery)
            }
        }

      val filteredVideos =
        if (activeFilter == DiscoverFilter.CREATORS) {
          emptyList()
        } else {
          videos.filter { video ->
            normalizedQuery.isEmpty() ||
              video.caption.lowercase().contains(normalizedQuery) ||
              video.authorUsername.lowercase().contains(normalizedQuery)
          }
        }

      UiState.Success(
        DiscoverData(
          creators = filteredCreators,
          videos = filteredVideos,
          followedUserIds = effectiveFollowedIds,
          likedVideoIds = likedVideoIds,
          savedVideoIds = savedVideoIds,
          currentUsername = myProfile?.username ?: "vynoza_user",
        )
      ) as UiState<DiscoverData>
    }
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing discover data", rootError)
        emit(UiState.Error(rootError.message ?: "Failed to load Discover"))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading,
      )

  fun updateQuery(query: String) {
    _searchQuery.value = query
  }

  fun selectFilter(filter: DiscoverFilter) {
    _filter.value = filter
  }

  fun clearActionError() {
    _actionError.value = null
  }

  fun toggleFollow(target: UserPublicProfile, currentlyFollowing: Boolean, currentUsername: String) {
    val targetUserId = target.userId
    if (targetUserId.isBlank() || targetUserId == currentUserId || targetUserId in _followingUserIds.value) {
      return
    }
    val desiredFollowing = !currentlyFollowing
    _followingUserIds.value = _followingUserIds.value + targetUserId
    _optimisticFollowOverrides.value = _optimisticFollowOverrides.value + (targetUserId to desiredFollowing)
    _actionError.value = null

    viewModelScope.launch {
      try {
        val result =
          if (currentlyFollowing) {
            userRepository.unfollowCreator(currentUserId, targetUserId)
          } else {
            userRepository.followCreator(currentUserId, currentUsername, target)
          }
        result.onFailure { err ->
          _actionError.value = err.localizedMessage ?: "Unable to update follow status"
        }
      } finally {
        _optimisticFollowOverrides.value = _optimisticFollowOverrides.value - targetUserId
        _followingUserIds.value = _followingUserIds.value - targetUserId
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
          _actionError.value = err.localizedMessage ?: "Unable to update saved video."
        }
      } finally {
        _savingVideoIds.value = _savingVideoIds.value - videoId
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
    const val TAG = "DiscoverVM"
    const val STOP_TIMEOUT_MILLIS = 5000L
  }
}
