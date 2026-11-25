package com.vynoza.app.data.service

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.auth.userProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

sealed interface AuthOperationResult {
  data object Success : AuthOperationResult
  data class Error(val message: String) : AuthOperationResult
}

interface VynozaAuthService {
  val currentUser: FirebaseUser?
  fun authStateFlow(): Flow<FirebaseUser?>
  suspend fun signInWithEmailAndPassword(email: String, password: String): AuthOperationResult
  suspend fun createAccountWithEmailAndPassword(
    email: String,
    password: String,
    displayName: String,
  ): AuthOperationResult
  suspend fun sendPasswordResetEmail(email: String): AuthOperationResult
  fun signOut()
  fun consumePendingDisplayName(): String? = null
}

class FirebaseVynozaAuthService(
  private val auth: FirebaseAuth = Firebase.auth,
) : VynozaAuthService {

  @Volatile private var pendingDisplayName: String? = null

  override val currentUser: FirebaseUser?
    get() = auth.currentUser

  override fun authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
      trySend(firebaseAuth.currentUser)
    }
    auth.addAuthStateListener(listener)
    awaitClose { auth.removeAuthStateListener(listener) }
  }

  override suspend fun signInWithEmailAndPassword(
    email: String,
    password: String,
  ): AuthOperationResult {
    return try {
      pendingDisplayName = null
      auth.signInWithEmailAndPassword(email.trim(), password).await()
      AuthOperationResult.Success
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Email sign-in failed", e)
      AuthOperationResult.Error(mapFirebaseAuthError(e, "Sign in failed"))
    }
  }

  override suspend fun createAccountWithEmailAndPassword(
    email: String,
    password: String,
    displayName: String,
  ): AuthOperationResult {
    val cleanName = displayName.trim()
    return try {
      pendingDisplayName = cleanName.takeIf { it.isNotEmpty() }
      val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
      val user = result.user
      if (user != null && cleanName.isNotEmpty()) {
        val profileUpdates = userProfileChangeRequest { this.displayName = cleanName }
        user.updateProfile(profileUpdates).await()
      }
      AuthOperationResult.Success
    } catch (e: CancellationException) {
      pendingDisplayName = null
      throw e
    } catch (e: Exception) {
      pendingDisplayName = null
      Log.w(TAG, "Account creation failed", e)
      AuthOperationResult.Error(mapFirebaseAuthError(e, "Account creation failed"))
    }
  }

  override suspend fun sendPasswordResetEmail(email: String): AuthOperationResult {
    return try {
      auth.sendPasswordResetEmail(email.trim()).await()
      AuthOperationResult.Success
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Password reset failed", e)
      AuthOperationResult.Error(mapFirebaseAuthError(e, "Failed to send password reset email"))
    }
  }

  override fun signOut() {
    pendingDisplayName = null
    auth.signOut()
  }

  override fun consumePendingDisplayName(): String? {
    val value = pendingDisplayName
    pendingDisplayName = null
    return value
  }

  private fun mapFirebaseAuthError(exception: Exception, fallback: String): String {
    if (exception is FirebaseAuthException) {
      return when (exception.errorCode) {
        "ERROR_OPERATION_NOT_ALLOWED" ->
          "Email/Password authentication is not enabled yet in Firebase Console (Authentication -> Sign-in method)."
        "ERROR_INVALID_EMAIL" -> "Please enter a valid email address."
        "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL" ->
          "Incorrect email or password."
        "ERROR_USER_NOT_FOUND" -> "No account exists for that email address."
        "ERROR_EMAIL_ALREADY_IN_USE" -> "An account with this email already exists."
        "ERROR_WEAK_PASSWORD" -> "Password must be at least 6 characters long."
        "ERROR_USER_DISABLED" -> "This account has been disabled."
        "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Please wait a moment and try again."
        else -> exception.localizedMessage ?: fallback
      }
    }
    return exception.localizedMessage ?: fallback
  }

  private companion object {
    const val TAG = "VynozaAuth"
  }
}
