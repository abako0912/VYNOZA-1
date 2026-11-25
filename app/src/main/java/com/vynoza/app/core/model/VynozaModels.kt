package com.vynoza.app.core.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

sealed interface UiState<out T> {
  data object Loading : UiState<Nothing>
  data class Success<T>(val data: T) : UiState<T>
  data class Error(val message: String) : UiState<Nothing>
}

@Keep
@IgnoreExtraProperties
data class UserPrivateAccount(
  @DocumentId val id: String = "",
  val userId: String = "",
  val email: String? = null,
  val accountTier: String = "standard",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class UserPublicProfile(
  @DocumentId val id: String = "",
  val userId: String = "",
  val username: String = "",
  val displayName: String = "",
  val bio: String = "",
  val avatarUrl: String? = null,
  val discoverable: Boolean = true,
  val followersCount: Long = 0L,
  val followingCount: Long = 0L,
  val videosCount: Long = 0L,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class VideoPost(
  @DocumentId val id: String = "",
  val authorId: String = "",
  val authorUsername: String = "",
  val caption: String = "",
  val visibility: String = "public",
  val status: String = "ready",
  val storagePath: String? = null,
  val playbackUrl: String? = null,
  val likesCount: Long = 0L,
  val commentsCount: Long = 0L,
  val sharesCount: Long = 0L,
  val viewsCount: Long? = null,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

enum class NotificationType(val wireValue: String) {
  LIKE("like"),
  COMMENT("comment"),
  FOLLOW("follow"),
  SYSTEM("system");

  companion object {
    fun fromWire(raw: String?): NotificationType =
      entries.firstOrNull { it.wireValue == raw } ?: SYSTEM
  }
}

@Keep
@IgnoreExtraProperties
data class VynozaNotification(
  @DocumentId val id: String = "",
  val recipientId: String = "",
  val actorId: String = "",
  val actorUsername: String = "",
  val type: String = NotificationType.SYSTEM.wireValue,
  val targetVideoId: String? = null,
  val message: String = "",
  val read: Boolean = false,
  @ServerTimestamp val createdAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class FollowEdge(
  @DocumentId val id: String = "",
  val followerId: String = "",
  val targetUserId: String = "",
  val targetUsername: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class FollowerEdge(
  @DocumentId val id: String = "",
  val followerId: String = "",
  val targetUserId: String = "",
  val followerUsername: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class VideoLike(
  @DocumentId val id: String = "",
  val videoId: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class VideoComment(
  @DocumentId val id: String = "",
  val videoId: String = "",
  val authorId: String = "",
  val authorUsername: String = "",
  val authorDisplayName: String = "",
  val authorAvatarUrl: String? = null,
  val text: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class SavedVideo(
  @DocumentId val id: String = "",
  val videoId: String = "",
  val userId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
)


