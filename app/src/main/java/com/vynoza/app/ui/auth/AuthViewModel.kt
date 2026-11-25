package com.vynoza.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vynoza.app.data.service.AuthOperationResult
import com.vynoza.app.data.service.VynozaAuthService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode {
  SIGN_IN,
  CREATE_ACCOUNT,
  RESET_PASSWORD,
}

data class AuthUiState(
  val mode: AuthMode = AuthMode.SIGN_IN,
  val email: String = "",
  val password: String = "",
  val confirmPassword: String = "",
  val displayName: String = "",
  val isLoading: Boolean = false,
  val errorMessage: String? = null,
  val infoMessage: String? = null,
)

class AuthViewModel(
  private val authService: VynozaAuthService,
) : ViewModel() {

  private val _uiState = MutableStateFlow(AuthUiState())
  val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

  fun setMode(mode: AuthMode) {
    _uiState.update {
      it.copy(
        mode = mode,
        errorMessage = null,
        infoMessage = null,
      )
    }
  }

  fun onEmailChanged(email: String) {
    _uiState.update { it.copy(email = email, errorMessage = null, infoMessage = null) }
  }

  fun onPasswordChanged(password: String) {
    _uiState.update { it.copy(password = password, errorMessage = null) }
  }

  fun onConfirmPasswordChanged(confirmPassword: String) {
    _uiState.update { it.copy(confirmPassword = confirmPassword, errorMessage = null) }
  }

  fun onDisplayNameChanged(displayName: String) {
    _uiState.update { it.copy(displayName = displayName.take(50), errorMessage = null) }
  }

  fun submit(onAuthSuccess: () -> Unit = {}) {
    val state = _uiState.value
    if (state.isLoading) return

    val cleanEmail = state.email.trim()
    if (!EMAIL_REGEX.matches(cleanEmail)) {
      _uiState.update { it.copy(errorMessage = "Please enter a valid email address.") }
      return
    }

    when (state.mode) {
      AuthMode.SIGN_IN -> {
        if (state.password.length < 6) {
          _uiState.update {
            it.copy(errorMessage = "Password must be at least 6 characters.")
          }
          return
        }
        launchAuthAction {
          when (val result = authService.signInWithEmailAndPassword(cleanEmail, state.password)) {
            AuthOperationResult.Success -> {
              _uiState.update {
                it.copy(
                  isLoading = false,
                  password = "",
                  confirmPassword = "",
                  errorMessage = null,
                )
              }
              onAuthSuccess()
            }
            is AuthOperationResult.Error -> {
              _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
            }
          }
        }
      }

      AuthMode.CREATE_ACCOUNT -> {
        val cleanName = state.displayName.trim()
        if (cleanName.isEmpty()) {
          _uiState.update { it.copy(errorMessage = "Please enter a creator display name.") }
          return
        }
        if (state.password.length < 6) {
          _uiState.update {
            it.copy(errorMessage = "Password must be at least 6 characters.")
          }
          return
        }
        if (state.password != state.confirmPassword) {
          _uiState.update { it.copy(errorMessage = "Passwords do not match.") }
          return
        }
        launchAuthAction {
          when (
            val result =
              authService.createAccountWithEmailAndPassword(
                email = cleanEmail,
                password = state.password,
                displayName = cleanName,
              )
          ) {
            AuthOperationResult.Success -> {
              _uiState.update {
                it.copy(
                  isLoading = false,
                  mode = AuthMode.SIGN_IN,
                  password = "",
                  confirmPassword = "",
                  displayName = "",
                  errorMessage = null,
                )
              }
              onAuthSuccess()
            }
            is AuthOperationResult.Error -> {
              _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
            }
          }
        }
      }

      AuthMode.RESET_PASSWORD -> {
        launchAuthAction {
          when (val result = authService.sendPasswordResetEmail(cleanEmail)) {
            AuthOperationResult.Success -> {
              _uiState.update {
                it.copy(
                  isLoading = false,
                  infoMessage = "Password reset link sent to $cleanEmail. Check your inbox.",
                )
              }
            }
            is AuthOperationResult.Error -> {
              _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
            }
          }
        }
      }
    }
  }

  private fun launchAuthAction(block: suspend () -> Unit) {
    _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
    viewModelScope.launch { block() }
  }

  private companion object {
    val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
  }
}
