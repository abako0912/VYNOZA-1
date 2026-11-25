package com.vynoza.app.data.repository

import android.content.Context
import com.vynoza.app.R
import com.vynoza.app.core.error.OperationType
import com.vynoza.app.core.error.handleFirestoreError
import com.vynoza.app.core.model.FollowEdge
import com.vynoza.app.core.model.FollowerEdge
import com.vynoza.app.core.model.UserPublicProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

interface VynozaUserSource {
  fun observePublicProfile(userId: String): Flow<UserPublicProfile?>
  fun observeDiscoverCreators(): Flow<List<UserPublicProfile>>
  fun observeFollowing(userId: String): Flow<List<FollowEdge>>
  fun observeFollowers(userId: String): Flow<List<FollowerEdge>>
  suspend fun updatePublicProfile(
    userId: String,
    displayName: String,
    username: String,
    bio: String,
  ): Result<Unit>
  suspend fun followCreator(
    currentUserId: String,
    currentUsername: String,
    target: UserPublicProfile,
  ): Result<Unit>
  suspend fun unfollowCreator(currentUserId: String, targetUserId: String): Result<Unit>
}

class UserRepository(
  private val db: FirebaseFirestore,
  private val auth: FirebaseAuth = Firebase.auth,
) : VynozaUserSource {
  constructor(context: Context) : this(
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    )
  )

  private fun requireUserId(): String =
    auth.currentUser?.uid
      ?: throw IllegalStateException("User must be signed in before accessing Firestore.")

  suspend fun ensureUserInitialized(
    userId: String,
    email: String?,
    displayName: String?,
    avatarUrl: String?,
  ): Result<Unit> {
    return try {
      val uid = requireUserId()
      check(uid == userId) { "Authenticated user mismatch during initialization." }

      val privateRef = db.collection("users").document(uid)
      val privateSnap = privateRef.get().await()
      if (!privateSnap.exists()) {
        val privatePayload =
          mapOf(
            "userId" to uid,
            "email" to email?.trim()?.takeIf { it.length in 3..254 },
            "accountTier" to "standard",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
          ).filterValues { it != null }
        privateRef.set(privatePayload).await()
      }

      val publicRef = db.collection("users_public").document(uid)
      val publicSnap = publicRef.get().await()
      if (!publicSnap.exists()) {
        val cleanDisplayName =
          displayName?.trim()?.takeIf { it.isNotEmpty() }?.take(50) ?: "Vynoza Creator"
        val cleanUsername = deriveValidUsername(displayName, uid)
        val publicPayload =
          mapOf(
            "userId" to uid,
            "username" to cleanUsername,
            "displayName" to cleanDisplayName,
            "bio" to "",
            "avatarUrl" to avatarUrl?.trim()?.takeIf { it.isNotEmpty() && it.length <= 500 },
            "discoverable" to true,
            "followersCount" to 0L,
            "followingCount" to 0L,
            "videosCount" to 0L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
          ).filterValues { it != null }
        publicRef.set(publicPayload).await()
      }
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, "users_public/$userId")
      Result.failure(e)
    }
  }

  override fun observePublicProfile(userId: String): Flow<UserPublicProfile?> = flow {
    require(userId.isNotBlank()) { "User ID must not be blank." }
    val path = "users_public/$userId"
    emitAll(
      db.collection("users_public")
        .document(userId)
        .snapshots()
        .map { snapshot ->
          if (snapshot.exists()) {
            snapshot.toObject(
              UserPublicProfile::class.java,
              DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            )
          } else {
            null
          }
        }
        .catch { error ->
          logAndRethrowIfNeeded(error, OperationType.GET, path, userId)
          throw error
        }
    )
  }

  override fun observeDiscoverCreators(): Flow<List<UserPublicProfile>> = flow {
    val uid = requireUserId()
    val path = "users_public"
    emitAll(
      db.collection("users_public")
        .whereEqualTo("discoverable", true)
        .limit(30)
        .snapshots()
        .map { snapshot ->
          snapshot.toObjects(
            UserPublicProfile::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
          )
        }
        .catch { error ->
          logAndRethrowIfNeeded(error, OperationType.LIST, path, uid)
          throw error
        }
    )
  }

  override fun observeFollowing(userId: String): Flow<List<FollowEdge>> = flow {
    val uid = requireUserId()
    check(uid == userId) { "Cannot observe another user's private following list." }
    val path = "users/$userId/following"
    emitAll(
      db.collection("users")
        .document(userId)
        .collection("following")
        .snapshots()
        .map { snapshot ->
          snapshot.toObjects(
            FollowEdge::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
          )
        }
        .catch { error ->
          logAndRethrowIfNeeded(error, OperationType.LIST, path, uid)
          throw error
        }
    )
  }

  override fun observeFollowers(userId: String): Flow<List<FollowerEdge>> = flow {
    val uid = requireUserId()
    require(userId.isNotBlank()) { "User ID must not be blank." }
    val path = "users/$userId/followers"
    emitAll(
      db.collection("users")
        .document(userId)
        .collection("followers")
        .snapshots()
        .map { snapshot ->
          snapshot.toObjects(
            FollowerEdge::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
          )
        }
        .catch { error ->
          logAndRethrowIfNeeded(error, OperationType.LIST, path, uid)
          throw error
        }
    )
  }

  override suspend fun updatePublicProfile(
    userId: String,
    displayName: String,
    username: String,
    bio: String,
  ): Result<Unit> {
    val path = "users_public/$userId"
    return try {
      val uid = requireUserId()
      check(uid == userId) { "Cannot update another user's profile." }

      val trimmedDisplay = displayName.trim()
      val normalizedUsername = username.trim().lowercase()
      val trimmedBio = bio.trim()

      require(trimmedDisplay.length in 1..50) {
        "Display name must be between 1 and 50 characters."
      }
      require(normalizedUsername.length in 3..30 && USERNAME_REGEX.matches(normalizedUsername)) {
        "Username must be 3-30 lowercase letters, numbers, underscores, or dots."
      }
      require(trimmedBio.length <= 200) {
        "Bio must be 200 characters or fewer."
      }

      db.collection("users_public")
        .document(uid)
        .update(
          mapOf(
            "displayName" to trimmedDisplay,
            "username" to normalizedUsername,
            "bio" to trimmedBio,
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        )
        .await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, path)
      Result.failure(e)
    }
  }

  override suspend fun followCreator(
    currentUserId: String,
    currentUsername: String,
    target: UserPublicProfile,
  ): Result<Unit> {
    val path = "users/$currentUserId/following/${target.userId}"
    return try {
      val uid = requireUserId()
      check(uid == currentUserId) { "Authenticated user mismatch." }
      require(target.userId.isNotBlank() && target.userId != uid) {
        "Invalid follow target."
      }

      val followingEdgeRef =
        db.collection("users").document(uid).collection("following").document(target.userId)
      val followerEdgeRef =
        db.collection("users").document(target.userId).collection("followers").document(uid)
      val selfProfileRef = db.collection("users_public").document(uid)
      val targetProfileRef = db.collection("users_public").document(target.userId)

      val resolvedActorUsername =
        currentUsername
          .trim()
          .lowercase()
          .takeIf { it.length in 3..30 && USERNAME_REGEX.matches(it) }
          ?: deriveValidUsername(auth.currentUser?.displayName, uid)

      val didFollow =
        db.runTransaction { tx ->
          // Firestore transactions require all reads before any writes.
          val followingEdgeSnap = tx.get(followingEdgeRef)
          val followerEdgeSnap = tx.get(followerEdgeRef)
          val selfSnap = tx.get(selfProfileRef)
          val targetSnap = tx.get(targetProfileRef)

          check(targetSnap.exists()) { "Creator profile was not found." }
          val alreadyFollowing = followingEdgeSnap.exists()
          val alreadyHasFollowerRecord = followerEdgeSnap.exists()
          if (alreadyFollowing && alreadyHasFollowerRecord) {
            return@runTransaction false
          }

          if (!alreadyFollowing) {
            val resolvedTargetUsername =
              targetSnap.getString("username")?.takeIf { it.length in 3..30 }
                ?: target.username.trim().lowercase().takeIf { it.length in 3..30 }
                ?: deriveValidUsername(target.displayName, target.userId)

            tx.set(
              followingEdgeRef,
              mapOf(
                "followerId" to uid,
                "targetUserId" to target.userId,
                "targetUsername" to resolvedTargetUsername,
                "createdAt" to FieldValue.serverTimestamp(),
              ),
            )
            if (selfSnap.exists()) {
              val currentFollowing = (selfSnap.getLong("followingCount") ?: 0L).coerceAtLeast(0L)
              tx.update(
                selfProfileRef,
                mapOf(
                  "followingCount" to (currentFollowing + 1L),
                  "updatedAt" to FieldValue.serverTimestamp(),
                ),
              )
            }
          }

          if (!alreadyHasFollowerRecord) {
            val resolvedFollowerUsername =
              selfSnap
                .takeIf { it.exists() }
                ?.getString("username")
                ?.takeIf { it.length in 3..30 && USERNAME_REGEX.matches(it) }
                ?: resolvedActorUsername

            tx.set(
              followerEdgeRef,
              mapOf(
                "followerId" to uid,
                "targetUserId" to target.userId,
                "followerUsername" to resolvedFollowerUsername,
                "createdAt" to FieldValue.serverTimestamp(),
              ),
            )
            val currentFollowers = (targetSnap.getLong("followersCount") ?: 0L).coerceAtLeast(0L)
            tx.update(
              targetProfileRef,
              mapOf(
                "followersCount" to (currentFollowers + 1L),
                "updatedAt" to FieldValue.serverTimestamp(),
              ),
            )
          }
          !alreadyFollowing
        }.await()

      if (didFollow) {
        try {
          db.collection("users")
            .document(target.userId)
            .collection("notifications")
            .document()
            .set(
              mapOf(
                "recipientId" to target.userId,
                "actorId" to uid,
                "actorUsername" to resolvedActorUsername,
                "type" to "follow",
                "message" to "@$resolvedActorUsername started following you",
                "read" to false,
                "createdAt" to FieldValue.serverTimestamp(),
              )
            )
            .await()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          // Non-fatal: the atomic follow transaction already succeeded.
        }
      }
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, path)
      Result.failure(e)
    }
  }

  override suspend fun unfollowCreator(currentUserId: String, targetUserId: String): Result<Unit> {
    val path = "users/$currentUserId/following/$targetUserId"
    return try {
      val uid = requireUserId()
      check(uid == currentUserId) { "Authenticated user mismatch." }
      require(targetUserId.isNotBlank() && targetUserId != uid) {
        "Invalid unfollow target."
      }
      val followingEdgeRef =
        db.collection("users").document(uid).collection("following").document(targetUserId)
      val followerEdgeRef =
        db.collection("users").document(targetUserId).collection("followers").document(uid)
      val selfProfileRef = db.collection("users_public").document(uid)
      val targetProfileRef = db.collection("users_public").document(targetUserId)

      db.runTransaction { tx ->
        // Firestore transactions require all reads before any writes.
        val followingEdgeSnap = tx.get(followingEdgeRef)
        val followerEdgeSnap = tx.get(followerEdgeRef)
        val selfSnap = tx.get(selfProfileRef)
        val targetSnap = tx.get(targetProfileRef)

        val hasFollowing = followingEdgeSnap.exists()
        val hasFollower = followerEdgeSnap.exists()
        if (!hasFollowing && !hasFollower) {
          return@runTransaction
        }

        if (hasFollowing) {
          tx.delete(followingEdgeRef)
          if (selfSnap.exists()) {
            val currentFollowing = (selfSnap.getLong("followingCount") ?: 0L).coerceAtLeast(0L)
            if (currentFollowing > 0L) {
              tx.update(
                selfProfileRef,
                mapOf(
                  "followingCount" to (currentFollowing - 1L),
                  "updatedAt" to FieldValue.serverTimestamp(),
                ),
              )
            }
          }
        }
        if (hasFollower) {
          tx.delete(followerEdgeRef)
        }
        if ((hasFollower || hasFollowing) && targetSnap.exists()) {
          val currentFollowers = (targetSnap.getLong("followersCount") ?: 0L).coerceAtLeast(0L)
          if (currentFollowers > 0L) {
            tx.update(
              targetProfileRef,
              mapOf(
                "followersCount" to (currentFollowers - 1L),
                "updatedAt" to FieldValue.serverTimestamp(),
              ),
            )
          }
        }
      }.await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, path)
      Result.failure(e)
    }
  }

  private fun logAndRethrowIfNeeded(
    error: Throwable,
    operationType: OperationType,
    path: String,
    expectedUid: String,
  ) {
    val firestoreError =
      generateSequence(error) { it.cause }
        .filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
    if (firestoreError == null && error is CancellationException) throw error
    val exceptionToLog = firestoreError ?: (error as? Exception)
    if (exceptionToLog != null && auth.currentUser?.uid == expectedUid) {
      handleFirestoreError(exceptionToLog, operationType, path)
    }
  }

  companion object {
    private val USERNAME_REGEX = Regex("^[a-z0-9_.]+$")

    internal fun deriveValidUsername(displayName: String?, uid: String): String {
      val base =
        displayName
          ?.lowercase()
          ?.replace(Regex("[^a-z0-9_.]"), "")
          ?.take(20)
          .orEmpty()
      val suffix = uid.lowercase().replace(Regex("[^a-z0-9]"), "").take(6)
      val candidate =
        if (base.length >= 3) {
          "${base}_$suffix".take(30)
        } else {
          "vynoza_${suffix.padEnd(4, '0')}".take(30)
        }
      return candidate
    }
  }
}
