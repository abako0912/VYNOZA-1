package com.vynoza.app.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vynoza.app.core.model.UiState
import com.vynoza.app.core.model.UserPublicProfile
import com.vynoza.app.core.model.VideoComment
import com.vynoza.app.core.model.VideoPost
import com.vynoza.app.data.repository.UserRepository
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

enum class HomeFeedTab {
  FOR_YOU,
  FOLLOWING,
}

data class HomeFeedData(
  val videos: List<VideoPost> = emptyList(),
  val likedVideoIds: Set<String> = emptySet(),
  val savedVideoIds: Set<String> = emptySet(),
  val followedUserIds: Set<String> = emptySet(),
  val creatorProfiles: Map<String, UserPublicProfile> = emptyMap(),
  val currentUsername: String? = null,
)

class HomeViewModel(
  private val videoRepository: VynozaVideoSource,
  private val userRepository: VynozaUserSource,
  private val currentUserId: String,
) : ViewModel() {

  private val _selectedTab = MutableStateFlow(HomeFeedTab.FOR_YOU)
  val selectedTab: StateFlow<HomeFeedTab> = _selectedTab.asStateFlow()

  private val _likingVideoIds = MutableStateFlow<Set<String>>(emptySet())
  val likingVideoIds: StateFlow<Set<String>> = _likingVideoIds.asStateFlow()

  private val _savingVideoIds = MutableStateFlow<Set<String>>(emptySet())
  val savingVideoIds: StateFlow<Set<String>> = _savingVideoIds.asStateFlow()

  private val _followingUserIds = MutableStateFlow<Set<String>>(emptySet())
  val followingUserIds: StateFlow<Set<String>> = _followingUserIds.asStateFlow()

  private val _optimisticFollowOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())
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

  val feedState: StateFlow<UiState<HomeFeedData>> =
    combine(
      combine(
        videoRepository.observePublicFeed(),
        videoRepository.observeLikedVideoIds(currentUserId),
        videoRepository.observeSavedVideoIds(currentUserId),
        _optimisticSaveOverrides,
      ) { videos, likedVideoIds, savedVideoIds, saveOverrides ->
        val effectiveSaved = savedVideoIds.toMutableSet()
        saveOverrides.forEach { (id, saved) ->
          if (saved) effectiveSaved.add(id) else effectiveSaved.remove(id)
        }
        Triple(videos, likedVideoIds, effectiveSaved.toSet())
      },
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
      userRepository.observeDiscoverCreators(),
      _selectedTab,
    ) { (videos, likedVideoIds, savedVideoIds), followedIds, myProfile, creators, tab ->
      val filtered =
        when (tab) {
          HomeFeedTab.FOR_YOU -> videos
          HomeFeedTab.FOLLOWING -> videos.filter { it.authorId in followedIds }
        }
      UiState.Success(
        HomeFeedData(
          videos = filtered,
          likedVideoIds = likedVideoIds,
          savedVideoIds = savedVideoIds,
          followedUserIds = followedIds,
          creatorProfiles = creators.associateBy { it.userId },
          currentUsername = myProfile?.username,
        )
      ) as UiState<HomeFeedData>
    }
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing home feed", rootError)
        emit(UiState.Error(rootError.message ?: "Failed to load feed"))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading,
      )

  fun selectTab(tab: HomeFeedTab) {
    _selectedTab.value = tab
  }

  fun clearActionError() {
    _actionError.value = null
  }

  fun showActionError(message: String) {
    _actionError.value = message
  }

  fun toggleLike(
    video: VideoPost,
    currentlyLiked: Boolean,
    actorUsername: String? = null,
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
          actorUsername = actorUsername,
        ).onFailure { err ->
          _actionError.value = err.localizedMessage ?: "Unable to update like right now."
        }
      } finally {
        _likingVideoIds.value = _likingVideoIds.value - videoId
      }
    }
  }

  fun toggleFollowCreator(
    video: VideoPost,
    currentlyFollowing: Boolean,
    creatorProfile: UserPublicProfile? = null,
    actorUsername: String? = null,
  ) {
    val targetUserId = video.authorId
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
            userRepository.unfollowCreator(currentUserId = currentUserId, targetUserId = targetUserId)
          } else {
            val resolvedActorUsername =
              actorUsername
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.length in 3..30 }
                ?: UserRepository.deriveValidUsername(null, currentUserId)
            val target =
              creatorProfile
                ?: UserPublicProfile(
                  userId = targetUserId,
                  username = video.authorUsername.ifBlank { "vynoza_user" },
                  displayName = video.authorUsername.ifBlank { "Vynoza Creator" },
                )
            userRepository.followCreator(
              currentUserId = currentUserId,
              currentUsername = resolvedActorUsername,
              target = target,
            )
          }
        result.onFailure { err ->
          _actionError.value = err.localizedMessage ?: "Unable to update follow status right now."
        }
      } finally {
        _optimisticFollowOverrides.value = _optimisticFollowOverrides.value - targetUserId
        _followingUserIds.value = _followingUserIds.value - targetUserId
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

  fun openComments(videoId: String) = commentsDelegate.openComments(videoId)

  fun closeComments() = commentsDelegate.closeComments()

  fun retryLoadComments() = commentsDelegate.retryLoad()

  fun onCommentDraftChanged(text: String) = commentsDelegate.onDraftChanged(text)

  fun submitComment() = commentsDelegate.submitComment()

  fun deleteComment(comment: VideoComment) = commentsDelegate.deleteComment(comment)

  fun clearCommentError() = commentsDelegate.clearActionError()

  private companion object {
    const val TAG = "HomeVM"
    const val STOP_TIMEOUT_MILLIS = 5000L
  }
}
