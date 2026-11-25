package com.vynoza.app.ui.notifications

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vynoza.app.core.model.NotificationType
import com.vynoza.app.core.model.UiState
import com.vynoza.app.core.model.VynozaNotification
import com.vynoza.app.data.repository.VynozaNotificationSource
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

enum class NotificationFilter(val label: String) {
  ALL("All"),
  LIKES("Likes"),
  COMMENTS("Comments"),
  FOLLOWS("Followers"),
}

class NotificationsViewModel(
  private val notificationRepository: VynozaNotificationSource,
  private val currentUserId: String,
) : ViewModel() {

  private val _selectedFilter = MutableStateFlow(NotificationFilter.ALL)
  val selectedFilter: StateFlow<NotificationFilter> = _selectedFilter.asStateFlow()

  val notificationsState: StateFlow<UiState<List<VynozaNotification>>> =
    combine(
      notificationRepository.observeNotifications(currentUserId),
      _selectedFilter,
    ) { items, filter ->
      val filtered =
        when (filter) {
          NotificationFilter.ALL -> items
          NotificationFilter.LIKES ->
            items.filter { NotificationType.fromWire(it.type) == NotificationType.LIKE }
          NotificationFilter.COMMENTS ->
            items.filter { NotificationType.fromWire(it.type) == NotificationType.COMMENT }
          NotificationFilter.FOLLOWS ->
            items.filter { NotificationType.fromWire(it.type) == NotificationType.FOLLOW }
        }
      UiState.Success(filtered) as UiState<List<VynozaNotification>>
    }
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing notifications", rootError)
        emit(UiState.Error(rootError.message ?: "Failed to load notifications"))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading,
      )

  fun selectFilter(filter: NotificationFilter) {
    _selectedFilter.value = filter
  }

  fun markAsRead(notificationId: String) {
    viewModelScope.launch {
      notificationRepository.markAsRead(currentUserId, notificationId)
    }
  }

  private companion object {
    const val TAG = "NotificationsVM"
    const val STOP_TIMEOUT_MILLIS = 5000L
  }
}
