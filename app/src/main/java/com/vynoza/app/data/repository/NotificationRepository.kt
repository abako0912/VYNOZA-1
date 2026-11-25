package com.vynoza.app.data.repository

import android.content.Context
import com.vynoza.app.R
import com.vynoza.app.core.error.OperationType
import com.vynoza.app.core.error.handleFirestoreError
import com.vynoza.app.core.model.VynozaNotification
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
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

interface VynozaNotificationSource {
  fun observeNotifications(userId: String): Flow<List<VynozaNotification>>
  suspend fun markAsRead(userId: String, notificationId: String): Result<Unit>
}

class NotificationRepository(
  private val db: FirebaseFirestore,
  private val auth: FirebaseAuth = Firebase.auth,
) : VynozaNotificationSource {
  constructor(context: Context) : this(
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    )
  )

  private fun requireUserId(): String =
    auth.currentUser?.uid
      ?: throw IllegalStateException("User must be signed in before accessing notifications.")

  override fun observeNotifications(userId: String): Flow<List<VynozaNotification>> = flow {
    val uid = requireUserId()
    check(uid == userId) { "Cannot observe another user's notifications." }
    val path = "users/$userId/notifications"
    emitAll(
      db.collection("users")
        .document(userId)
        .collection("notifications")
        .snapshots()
        .map { snapshot ->
          snapshot
            .toObjects(
              VynozaNotification::class.java,
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

  override suspend fun markAsRead(userId: String, notificationId: String): Result<Unit> {
    val path = "users/$userId/notifications/$notificationId"
    return try {
      val uid = requireUserId()
      check(uid == userId) { "Cannot update another user's notification." }
      require(notificationId.isNotBlank()) { "Notification ID must not be blank." }
      db.collection("users")
        .document(uid)
        .collection("notifications")
        .document(notificationId)
        .update(mapOf("read" to true))
        .await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, path)
      Result.failure(e)
    }
  }
}
