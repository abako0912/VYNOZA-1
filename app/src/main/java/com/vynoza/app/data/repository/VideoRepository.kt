package com.vynoza.app.data.repository

import android.content.Context
import com.vynoza.app.R
import com.vynoza.app.core.error.OperationType
import com.vynoza.app.core.error.handleFirestoreError
import com.vynoza.app.core.model.VideoComment
import com.vynoza.app.core.model.VideoPost
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

interface VynozaVideoSource {
  fun observePublicFeed(): Flow<List<VideoPost>>
  fun observeUserVideos(userId: String): Flow<List<VideoPost>>
  fun observeUserPublicVideos(userId: String, limit: Long = 24L): Flow<List<VideoPost>>
  fun observeLikedVideoIds(userId: String): Flow<Set<String>>
  suspend fun toggleVideoLike(
    userId: String,
    videoId: String,
    expectedCurrentlyLiked: Boolean? = null,
    actorUsername: String? = null,
  ): Result<Boolean>
  fun observeVideoComments(videoId: String): Flow<List<VideoComment>>
  suspend fun addComment(
    userId: String,
    videoId: String,
    text: String,
  ): Result<VideoComment>
  suspend fun deleteComment(
    userId: String,
    videoId: String,
    commentId: String,
  ): Result<Unit>
  fun observeSavedVideoIds(userId: String): Flow<List<String>>
  suspend fun toggleVideoSave(
    userId: String,
    videoId: String,
    expectedCurrentlySaved: Boolean? = null,
  ): Result<Boolean>
  suspend fun getVideoById(videoId: String): Result<VideoPost?>
}

class VideoRepository(
  private val db: FirebaseFirestore,
  private val auth: FirebaseAuth = Firebase.auth,
) : VynozaVideoSource {
  constructor(context: Context) : this(
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    )
  )

  private fun requireUserId(): String =
    auth.currentUser?.uid
      ?: throw IllegalStateException("User must be signed in before accessing videos.")

  override fun observePublicFeed(): Flow<List<VideoPost>> = flow {
    val uid = requireUserId()
    val path = "videos"
    emitAll(
      db.collection("videos")
        .whereEqualTo("visibility", "public")
        .limit(30)
        .snapshots()
        .map { snapshot ->
          snapshot
            .toObjects(
              VideoPost::class.java,
              DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            )
            .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  override fun observeUserVideos(userId: String): Flow<List<VideoPost>> = flow {
    val uid = requireUserId()
    val path = "videos"
    emitAll(
      db.collection("videos")
        .whereEqualTo("authorId", userId)
        .limit(30)
        .snapshots()
        .map { snapshot ->
          snapshot
            .toObjects(
              VideoPost::class.java,
              DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            )
            .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  override fun observeUserPublicVideos(userId: String, limit: Long): Flow<List<VideoPost>> = flow {
    val uid = requireUserId()
    require(userId.isNotBlank()) { "User ID must not be blank." }
    val safeLimit = limit.coerceIn(1L, 120L)
    val path = "videos"
    emitAll(
      db.collection("videos")
        .whereEqualTo("authorId", userId)
        .whereEqualTo("visibility", "public")
        .limit(safeLimit)
        .snapshots()
        .map { snapshot ->
          snapshot
            .toObjects(
              VideoPost::class.java,
              DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            )
            .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  override fun observeLikedVideoIds(userId: String): Flow<Set<String>> = flow {
    val uid = requireUserId()
    check(uid == userId) { "Cannot observe another user's liked videos." }
    val path = "users/$userId/liked_videos"
    emitAll(
      db.collection("users")
        .document(userId)
        .collection("liked_videos")
        .snapshots()
        .map { snapshot ->
          snapshot.documents.mapTo(mutableSetOf()) { doc ->
            doc.getString("videoId")?.takeIf { it.isNotBlank() } ?: doc.id
          }
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  private data class LikeTransactionOutcome(
    val isLiked: Boolean,
    val authorId: String,
    val changed: Boolean,
  )

  override suspend fun toggleVideoLike(
    userId: String,
    videoId: String,
    expectedCurrentlyLiked: Boolean?,
    actorUsername: String?,
  ): Result<Boolean> {
    val path = "videos/$videoId/likes/$userId"
    return try {
      val uid = requireUserId()
      check(uid == userId) { "Authenticated user mismatch while updating like." }
      require(videoId.length in 1..128 && VALID_ID_REGEX.matches(videoId)) {
        "Invalid video ID."
      }

      val videoRef = db.collection("videos").document(videoId)
      val videoLikeRef = videoRef.collection("likes").document(uid)
      val userLikedRef = db.collection("users").document(uid).collection("liked_videos").document(videoId)

      val outcome =
        db.runTransaction { tx ->
          // Firestore transactions require all reads before any writes.
          val videoSnap = tx.get(videoRef)
          val videoLikeSnap = tx.get(videoLikeRef)
          val userLikedSnap = tx.get(userLikedRef)

          check(videoSnap.exists()) { "Video post was not found." }
          val authorId = videoSnap.getString("authorId").orEmpty()
          val currentLikes = (videoSnap.getLong("likesCount") ?: 0L).coerceAtLeast(0L)
          val currentlyLiked = videoLikeSnap.exists()
          val desiredLiked = expectedCurrentlyLiked?.let { !it } ?: !currentlyLiked

          if (desiredLiked == currentlyLiked) {
            if (desiredLiked && !userLikedSnap.exists()) {
              tx.set(
                userLikedRef,
                mapOf(
                  "videoId" to videoId,
                  "userId" to uid,
                  "createdAt" to FieldValue.serverTimestamp(),
                ),
              )
            } else if (!desiredLiked && userLikedSnap.exists()) {
              tx.delete(userLikedRef)
            }
            return@runTransaction LikeTransactionOutcome(
              isLiked = desiredLiked,
              authorId = authorId,
              changed = false,
            )
          }

          if (desiredLiked) {
            val likePayload =
              mapOf(
                "videoId" to videoId,
                "userId" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
              )
            tx.set(videoLikeRef, likePayload)
            tx.set(userLikedRef, likePayload)
            tx.update(
              videoRef,
              mapOf(
                "likesCount" to (currentLikes + 1L),
                "updatedAt" to FieldValue.serverTimestamp(),
              ),
            )
          } else {
            check(currentLikes > 0L) { "Cannot decrement likes count below zero." }
            tx.delete(videoLikeRef)
            if (userLikedSnap.exists()) {
              tx.delete(userLikedRef)
            }
            tx.update(
              videoRef,
              mapOf(
                "likesCount" to (currentLikes - 1L),
                "updatedAt" to FieldValue.serverTimestamp(),
              ),
            )
          }

          LikeTransactionOutcome(
            isLiked = desiredLiked,
            authorId = authorId,
            changed = true,
          )
        }.await()

      if (outcome.changed && outcome.isLiked && outcome.authorId.isNotBlank() && outcome.authorId != uid) {
        try {
          val resolvedUsername =
            actorUsername
              ?.trim()
              ?.lowercase()
              ?.takeIf { it.length in 3..30 && USERNAME_REGEX.matches(it) }
              ?: db.collection("users_public")
                .document(uid)
                .get()
                .await()
                .getString("username")
                ?.takeIf { it.length in 3..30 && USERNAME_REGEX.matches(it) }
              ?: UserRepository.deriveValidUsername(auth.currentUser?.displayName, uid)

          db.collection("users")
            .document(outcome.authorId)
            .collection("notifications")
            .document()
            .set(
              mapOf(
                "recipientId" to outcome.authorId,
                "actorId" to uid,
                "actorUsername" to resolvedUsername,
                "type" to "like",
                "targetVideoId" to videoId,
                "message" to "@$resolvedUsername liked your video",
                "read" to false,
                "createdAt" to FieldValue.serverTimestamp(),
              )
            )
            .await()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          // Non-fatal: the atomic like transaction already succeeded.
        }
      }

      Result.success(outcome.isLiked)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, path)
      Result.failure(e)
    }
  }

  override fun observeVideoComments(videoId: String): Flow<List<VideoComment>> = flow {
    val uid = requireUserId()
    require(videoId.length in 1..128 && VALID_ID_REGEX.matches(videoId)) {
      "Invalid video ID."
    }
    val path = "videos/$videoId/comments"
    emitAll(
      db.collection("videos")
        .document(videoId)
        .collection("comments")
        .orderBy("createdAt", Query.Direction.DESCENDING)
        .limit(100)
        .snapshots()
        .map { snapshot ->
          snapshot
            .toObjects(
              VideoComment::class.java,
              DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            )
            .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  override suspend fun addComment(
    userId: String,
    videoId: String,
    text: String,
  ): Result<VideoComment> {
    val commentId = "cmt_${UUID.randomUUID().toString().replace("-", "").take(24)}"
    val path = "videos/$videoId/comments/$commentId"
    return try {
      val uid = requireUserId()
      check(uid == userId) { "Authenticated user mismatch while posting comment." }
      require(videoId.length in 1..128 && VALID_ID_REGEX.matches(videoId)) {
        "Invalid video ID."
      }
      val cleanText = text.trim()
      require(cleanText.isNotEmpty()) { "Comment cannot be empty." }
      require(cleanText.length <= MAX_COMMENT_LENGTH) {
        "Comment must be $MAX_COMMENT_LENGTH characters or fewer."
      }

      val profileSnap = db.collection("users_public").document(uid).get().await()
      val authorUsername =
        profileSnap
          .getString("username")
          ?.trim()
          ?.lowercase()
          ?.takeIf { it.length in 3..30 && USERNAME_REGEX.matches(it) }
          ?: UserRepository.deriveValidUsername(auth.currentUser?.displayName, uid)
      val authorDisplayName =
        profileSnap
          .getString("displayName")
          ?.trim()
          ?.takeIf { it.isNotEmpty() }
          ?.take(50)
          ?: auth.currentUser?.displayName?.trim()?.takeIf { it.isNotEmpty() }?.take(50)
          ?: authorUsername
      val authorAvatarUrl =
        (profileSnap.getString("avatarUrl") ?: auth.currentUser?.photoUrl?.toString())
          ?.trim()
          ?.takeIf { it.isNotEmpty() && it.length <= 500 }

      val videoRef = db.collection("videos").document(videoId)
      val commentRef = videoRef.collection("comments").document(commentId)

      val videoAuthorId =
        db.runTransaction { tx ->
          // Firestore transactions require all reads before any writes.
          val videoSnap = tx.get(videoRef)
          check(videoSnap.exists()) { "Video post was not found." }
          val authorId = videoSnap.getString("authorId").orEmpty()
          val currentComments = (videoSnap.getLong("commentsCount") ?: 0L).coerceAtLeast(0L)

          val commentPayload =
            mapOf(
              "videoId" to videoId,
              "authorId" to uid,
              "authorUsername" to authorUsername,
              "authorDisplayName" to authorDisplayName,
              "authorAvatarUrl" to authorAvatarUrl,
              "text" to cleanText,
              "createdAt" to FieldValue.serverTimestamp(),
            ).filterValues { it != null }

          tx.set(commentRef, commentPayload)
          tx.update(
            videoRef,
            mapOf(
              "commentsCount" to (currentComments + 1L),
              "lastCommentId" to commentId,
              "updatedAt" to FieldValue.serverTimestamp(),
            ),
          )
          authorId
        }.await()

      if (videoAuthorId.isNotBlank() && videoAuthorId != uid) {
        try {
          db.collection("users")
            .document(videoAuthorId)
            .collection("notifications")
            .document()
            .set(
              mapOf(
                "recipientId" to videoAuthorId,
                "actorId" to uid,
                "actorUsername" to authorUsername,
                "type" to "comment",
                "targetVideoId" to videoId,
                "message" to "@$authorUsername commented: ${cleanText.take(80)}".take(240),
                "read" to false,
                "createdAt" to FieldValue.serverTimestamp(),
              )
            )
            .await()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          // Non-fatal: the atomic comment transaction already succeeded.
        }
      }

      Result.success(
        VideoComment(
          id = commentId,
          videoId = videoId,
          authorId = uid,
          authorUsername = authorUsername,
          authorDisplayName = authorDisplayName,
          authorAvatarUrl = authorAvatarUrl,
          text = cleanText,
          createdAt = Timestamp.now(),
        )
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, path)
      Result.failure(e)
    }
  }

  override suspend fun deleteComment(
    userId: String,
    videoId: String,
    commentId: String,
  ): Result<Unit> {
    val path = "videos/$videoId/comments/$commentId"
    return try {
      val uid = requireUserId()
      check(uid == userId) { "Authenticated user mismatch while deleting comment." }
      require(videoId.length in 1..128 && VALID_ID_REGEX.matches(videoId)) {
        "Invalid video ID."
      }
      require(commentId.length in 1..128 && VALID_ID_REGEX.matches(commentId)) {
        "Invalid comment ID."
      }

      val videoRef = db.collection("videos").document(videoId)
      val commentRef = videoRef.collection("comments").document(commentId)

      db.runTransaction { tx ->
        // Firestore transactions require all reads before any writes.
        val videoSnap = tx.get(videoRef)
        val commentSnap = tx.get(commentRef)

        check(videoSnap.exists()) { "Video post was not found." }
        check(commentSnap.exists()) { "Comment was not found." }
        check(commentSnap.getString("authorId") == uid) {
          "You can only delete your own comments."
        }
        val currentComments = (videoSnap.getLong("commentsCount") ?: 0L).coerceAtLeast(0L)
        check(currentComments > 0L) { "Cannot decrement comments count below zero." }

        tx.delete(commentRef)
        tx.update(
          videoRef,
          mapOf(
            "commentsCount" to (currentComments - 1L),
            "lastCommentId" to commentId,
            "updatedAt" to FieldValue.serverTimestamp(),
          ),
        )
      }.await()

      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, path)
      Result.failure(e)
    }
  }

  override fun observeSavedVideoIds(userId: String): Flow<List<String>> = flow {
    val uid = requireUserId()
    check(uid == userId) { "Cannot observe another user's saved videos." }
    val path = "users/$userId/saved_videos"
    emitAll(
      db.collection("users")
        .document(userId)
        .collection("saved_videos")
        .snapshots()
        .map { snapshot ->
          snapshot.documents
            .sortedByDescending { doc ->
              doc.getTimestamp(
                "createdAt",
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
              ) ?: Timestamp(0, 0)
            }
            .map { doc ->
              doc.getString("videoId")?.takeIf { it.isNotBlank() } ?: doc.id
            }
            .distinct()
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  override suspend fun toggleVideoSave(
    userId: String,
    videoId: String,
    expectedCurrentlySaved: Boolean?,
  ): Result<Boolean> {
    val path = "users/$userId/saved_videos/$videoId"
    return try {
      val uid = requireUserId()
      check(uid == userId) { "Authenticated user mismatch while updating saved videos." }
      require(videoId.length in 1..128 && VALID_ID_REGEX.matches(videoId)) {
        "Invalid video ID."
      }

      val savedRef = db.collection("users").document(uid).collection("saved_videos").document(videoId)
      val videoRef = db.collection("videos").document(videoId)

      val nowSaved =
        db.runTransaction { tx ->
          // Firestore transactions require all reads before any writes.
          val savedSnap = tx.get(savedRef)
          val videoSnap = tx.get(videoRef)

          val currentlySaved = savedSnap.exists()
          val desiredSaved = expectedCurrentlySaved?.let { !it } ?: !currentlySaved

          if (desiredSaved == currentlySaved) {
            return@runTransaction desiredSaved
          }

          if (desiredSaved) {
            check(videoSnap.exists()) { "Video post was not found." }
            tx.set(
              savedRef,
              mapOf(
                "videoId" to videoId,
                "userId" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
              ),
            )
          } else {
            tx.delete(savedRef)
          }
          desiredSaved
        }.await()

      Result.success(nowSaved)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, path)
      Result.failure(e)
    }
  }

  override suspend fun getVideoById(videoId: String): Result<VideoPost?> {
    val path = "videos/$videoId"
    return try {
      requireUserId()
      require(videoId.length in 1..128 && VALID_ID_REGEX.matches(videoId)) {
        "Invalid video ID."
      }
      val snap = db.collection("videos").document(videoId).get().await()
      val post =
        if (snap.exists()) {
          snap.toObject(
            VideoPost::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
          )
        } else {
          null
        }
      Result.success(post)
    } catch (e: CancellationException) {
      throw e
    } catch (e: FirebaseFirestoreException) {
      if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ||
        e.code == FirebaseFirestoreException.Code.NOT_FOUND
      ) {
        Result.success(null)
      } else {
        handleFirestoreError(e, OperationType.GET, path)
        Result.failure(e)
      }
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, path)
      Result.failure(e)
    }
  }

  suspend fun publishVideoPost(
    userId: String,
    caption: String,
    visibility: String,
    secureUrl: String,
    publicId: String,
    userRepository: UserRepository? = null,
  ): Result<String> {
    val videoId = "vid_${UUID.randomUUID().toString().replace("-", "").take(24)}"
    val path = "videos/$videoId"
    return try {
      val uid = requireUserId()
      check(uid == userId) { "Authenticated user mismatch while publishing video." }

      val cleanCaption = caption.trim()
      val cleanVisibility = visibility.trim().lowercase()
      val cleanUrl = secureUrl.trim()
      val cleanPublicId = publicId.trim()

      require(cleanCaption.length <= 300) { "Caption must be 300 characters or fewer." }
      require(cleanVisibility == "public" || cleanVisibility == "private") {
        "Visibility must be public or private."
      }
      require(cleanUrl.startsWith("https://") && cleanUrl.length in 1..500) {
        "Invalid Cloudinary secure_url."
      }
      require(cleanPublicId.isNotEmpty() && cleanPublicId.length <= 500) {
        "Invalid Cloudinary public_id."
      }

      val profileRef = db.collection("users_public").document(uid)
      var profileSnap = profileRef.get().await()
      if (!profileSnap.exists() && userRepository != null) {
        val currentUser = auth.currentUser
        userRepository.ensureUserInitialized(
          userId = uid,
          email = currentUser?.email,
          displayName = currentUser?.displayName,
          avatarUrl = currentUser?.photoUrl?.toString(),
        ).getOrThrow()
        profileSnap = profileRef.get().await()
      }
      check(profileSnap.exists()) { "Creator profile is not initialized yet." }

      val authorUsername =
        profileSnap.getString("username")?.takeIf { it.length in 3..30 }
          ?: UserRepository.deriveValidUsername(auth.currentUser?.displayName, uid)

      val videoRef = db.collection("videos").document(videoId)
      val videoPayload =
        mapOf(
          "authorId" to uid,
          "authorUsername" to authorUsername,
          "caption" to cleanCaption,
          "visibility" to cleanVisibility,
          "status" to "ready",
          "storagePath" to cleanPublicId,
          "playbackUrl" to cleanUrl,
          "likesCount" to 0L,
          "commentsCount" to 0L,
          "sharesCount" to 0L,
          "createdAt" to FieldValue.serverTimestamp(),
          "updatedAt" to FieldValue.serverTimestamp(),
        )

      val batch = db.batch()
      batch.set(videoRef, videoPayload)
      batch.update(
        profileRef,
        mapOf(
          "videosCount" to FieldValue.increment(1L),
          "updatedAt" to FieldValue.serverTimestamp(),
        ),
      )
      batch.commit().await()
      Result.success(videoId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, path)
      Result.failure(e)
    }
  }

  suspend fun deleteVideoPost(userId: String, videoId: String): Result<Unit> {
    val path = "videos/$videoId"
    return try {
      val uid = requireUserId()
      check(uid == userId) { "Cannot delete another creator's video." }
      require(videoId.isNotBlank()) { "Video ID must not be blank." }
      db.collection("videos").document(videoId).delete().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, path)
      Result.failure(e)
    }
  }

  private companion object {
    const val MAX_COMMENT_LENGTH = 500
    val VALID_ID_REGEX = Regex("^[a-zA-Z0-9_\\-]+$")
    val USERNAME_REGEX = Regex("^[a-z0-9_.]+$")
  }
}
