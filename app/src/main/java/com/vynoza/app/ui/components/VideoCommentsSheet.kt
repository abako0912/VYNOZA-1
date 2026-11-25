package com.vynoza.app.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import com.vynoza.app.R
import com.vynoza.app.core.model.UiState
import com.vynoza.app.core.model.VideoComment
import com.vynoza.app.data.repository.VynozaVideoSource
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VideoCommentsUiState(
  val activeVideoId: String? = null,
  val draftText: String = "",
  val comments: List<VideoComment> = emptyList(),
  val isLoading: Boolean = false,
  val loadError: String? = null,
  val actionError: String? = null,
  val isSubmitting: Boolean = false,
  val deletingCommentIds: Set<String> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class VideoCommentsDelegate(
  private val scope: CoroutineScope,
  private val videoRepository: VynozaVideoSource,
  private val currentUserId: String,
) {
  private val _activeVideoId = MutableStateFlow<String?>(null)
  private val _refreshCounter = MutableStateFlow(0)
  private val _draftText = MutableStateFlow("")
  private val _isSubmitting = MutableStateFlow(false)
  private val _deletingCommentIds = MutableStateFlow<Set<String>>(emptySet())
  private val _actionError = MutableStateFlow<String?>(null)

  private val commentsStreamState: StateFlow<UiState<List<VideoComment>>> =
    combine(_activeVideoId, _refreshCounter) { videoId, _ -> videoId }
      .flatMapLatest { videoId ->
        if (videoId.isNullOrBlank()) {
          flowOf(UiState.Success(emptyList()))
        } else {
          videoRepository
            .observeVideoComments(videoId)
            .map<List<VideoComment>, UiState<List<VideoComment>>> { UiState.Success(it) }
            .onStart { emit(UiState.Loading) }
            .catch { error ->
              val firestoreError =
                generateSequence(error) { it.cause }
                  .filterIsInstance<FirebaseFirestoreException>()
                  .firstOrNull()
              if (firestoreError == null && error is CancellationException) throw error
              val rootError = firestoreError ?: error
              emit(UiState.Error(rootError.message ?: "Failed to load comments."))
            }
        }
      }
      .stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = UiState.Success(emptyList()),
      )

  val uiState: StateFlow<VideoCommentsUiState> =
    combine(
      _activeVideoId,
      _draftText,
      commentsStreamState,
      _actionError,
      combine(_isSubmitting, _deletingCommentIds) { submitting, deletingIds ->
        submitting to deletingIds
      },
    ) { activeVideoId, draftText, streamState, actionError, (isSubmitting, deletingIds) ->
      VideoCommentsUiState(
        activeVideoId = activeVideoId,
        draftText = draftText,
        comments = (streamState as? UiState.Success)?.data.orEmpty(),
        isLoading = activeVideoId != null && streamState is UiState.Loading,
        loadError = (streamState as? UiState.Error)?.message,
        actionError = actionError,
        isSubmitting = isSubmitting,
        deletingCommentIds = deletingIds,
      )
    }.stateIn(
      scope = scope,
      started = SharingStarted.WhileSubscribed(5000L),
      initialValue = VideoCommentsUiState(),
    )

  fun openComments(videoId: String) {
    if (videoId.isBlank()) return
    if (_activeVideoId.value != videoId) {
      _draftText.value = ""
      _actionError.value = null
      _activeVideoId.value = videoId
    }
  }

  fun closeComments() {
    _activeVideoId.value = null
    _draftText.value = ""
    _actionError.value = null
  }

  fun retryLoad() {
    _refreshCounter.value += 1
  }

  fun onDraftChanged(text: String) {
    _draftText.value = text.take(MAX_COMMENT_LENGTH)
    if (_actionError.value != null) {
      _actionError.value = null
    }
  }

  fun clearActionError() {
    _actionError.value = null
  }

  fun submitComment() {
    val videoId = _activeVideoId.value ?: return
    if (_isSubmitting.value) return
    val trimmed = _draftText.value.trim()
    if (trimmed.isEmpty()) {
      _actionError.value = "Comment cannot be empty."
      return
    }

    _isSubmitting.value = true
    _actionError.value = null
    scope.launch {
      try {
        videoRepository
          .addComment(userId = currentUserId, videoId = videoId, text = trimmed)
          .onSuccess { _draftText.value = "" }
          .onFailure { err ->
            _actionError.value = err.localizedMessage ?: "Unable to post comment right now."
          }
      } finally {
        _isSubmitting.value = false
      }
    }
  }

  fun deleteComment(comment: VideoComment) {
    if (comment.id.isBlank() || comment.id in _deletingCommentIds.value) return
    if (comment.authorId != currentUserId) {
      _actionError.value = "You can only delete your own comments."
      return
    }

    _deletingCommentIds.value = _deletingCommentIds.value + comment.id
    _actionError.value = null
    scope.launch {
      try {
        videoRepository
          .deleteComment(
            userId = currentUserId,
            videoId = comment.videoId,
            commentId = comment.id,
          )
          .onFailure { err ->
            _actionError.value = err.localizedMessage ?: "Unable to delete comment right now."
          }
      } finally {
        _deletingCommentIds.value = _deletingCommentIds.value - comment.id
      }
    }
  }

  private companion object {
    const val MAX_COMMENT_LENGTH = 500
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsBottomSheet(
  videoCommentsCount: Long,
  currentUserId: String,
  state: VideoCommentsUiState,
  onDismiss: () -> Unit,
  onRetryLoad: () -> Unit,
  onDraftChanged: (String) -> Unit,
  onSubmitComment: () -> Unit,
  onDeleteComment: (VideoComment) -> Unit,
  onClearError: () -> Unit,
  onOpenCreatorProfile: (String) -> Unit = {},
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val displayedCount =
    if (!state.isLoading && state.loadError == null && state.comments.size.toLong() > videoCommentsCount) {
      state.comments.size.toLong()
    } else {
      videoCommentsCount
    }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = VynozaSurfaceElevated,
    contentColor = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.testTag("comments_bottom_sheet"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding(),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.comments_header_format, displayedCount),
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.testTag("comments_sheet_header"),
        )
        IconButton(
          onClick = onDismiss,
          modifier = Modifier.size(48.dp).testTag("comments_sheet_close"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.close_comments),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      HorizontalDivider(color = VynozaBorderSubtle)

      if (state.actionError != null) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 8.dp)
              .testTag("comments_error_banner"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = state.actionError,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.weight(1f),
            )
            IconButton(
              onClick = onClearError,
              modifier = Modifier.size(40.dp).testTag("comments_error_dismiss"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.dismiss),
                tint = MaterialTheme.colorScheme.onErrorContainer,
              )
            }
          }
        }
      }

      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp, max = 380.dp)
            .padding(horizontal = 16.dp),
      ) {
        when {
          state.isLoading -> {
            CircularProgressIndicator(
              color = VynozaPurplePrimary,
              modifier =
                Modifier.align(Alignment.Center).testTag("comments_loading_indicator"),
            )
          }

          state.loadError != null -> {
            Box(
              modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
              contentAlignment = Alignment.Center,
            ) {
              VynozaErrorCard(
                message = state.loadError,
                onRetry = onRetryLoad,
              )
            }
          }

          state.comments.isEmpty() -> {
            Box(
              modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
              contentAlignment = Alignment.Center,
            ) {
              VynozaEmptyStateCard(
                icon = Icons.AutoMirrored.Rounded.Comment,
                title = stringResource(R.string.comments_empty_title),
                subtitle = stringResource(R.string.comments_empty_subtitle),
              )
            }
          }

          else -> {
            LazyColumn(
              modifier = Modifier.fillMaxWidth().testTag("comments_list"),
              contentPadding = PaddingValues(vertical = 12.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              items(state.comments, key = { it.id }) { comment ->
                CommentItemRow(
                  comment = comment,
                  isOwnedByCurrentUser = comment.authorId == currentUserId,
                  isDeleting = comment.id in state.deletingCommentIds,
                  onAuthorClick = {
                    if (comment.authorId.isNotBlank()) {
                      onOpenCreatorProfile(comment.authorId)
                    }
                  },
                  onDeleteClick = { onDeleteComment(comment) },
                )
              }
            }
          }
        }
      }

      HorizontalDivider(color = VynozaBorderSubtle)

      val canSend = state.draftText.trim().isNotEmpty() && !state.isSubmitting
      val sendLabel = stringResource(R.string.send_comment)
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedTextField(
          value = state.draftText,
          onValueChange = onDraftChanged,
          enabled = !state.isSubmitting,
          maxLines = 4,
          placeholder = {
            Text(
              text = stringResource(R.string.comment_input_hint),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
          shape = RoundedCornerShape(18.dp),
          colors =
            OutlinedTextFieldDefaults.colors(
              focusedContainerColor = VynozaSurfaceDeep,
              unfocusedContainerColor = VynozaSurfaceDeep,
              focusedBorderColor = VynozaPurplePrimary,
              unfocusedBorderColor = VynozaBorderSubtle,
            ),
          modifier = Modifier.weight(1f).testTag("comment_input"),
        )

        FilledIconButton(
          onClick = onSubmitComment,
          enabled = canSend,
          shape = CircleShape,
          colors =
            IconButtonDefaults.filledIconButtonColors(
              containerColor = VynozaPurplePrimary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
              disabledContainerColor = VynozaSurfaceDeep,
              disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
          modifier =
            Modifier
              .size(48.dp)
              .semantics { contentDescription = sendLabel }
              .testTag("comment_send_button"),
        ) {
          if (state.isSubmitting) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
              color = VynozaLavenderSoft,
            )
          } else {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.Send,
              contentDescription = null,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CommentItemRow(
  comment: VideoComment,
  isOwnedByCurrentUser: Boolean,
  isDeleting: Boolean,
  onAuthorClick: () -> Unit,
  onDeleteClick: () -> Unit,
) {
  val displayLabel = comment.authorDisplayName.ifBlank { comment.authorUsername }
  val timeText = formatCommentTimestamp(comment.createdAt)
  val deleteLabel = stringResource(R.string.delete_comment)
  val canOpenProfile = comment.authorId.isNotBlank()

  Row(
    modifier = Modifier.fillMaxWidth().testTag("comment_item_${comment.id}"),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    VynozaCreatorAvatar(
      displayName = displayLabel,
      avatarUrl = comment.authorAvatarUrl,
      size = 38.dp,
      modifier =
        Modifier
          .clip(CircleShape)
          .clickable(enabled = canOpenProfile, onClick = onAuthorClick)
          .testTag("comment_avatar_${comment.id}"),
    )
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = "@${comment.authorUsername}",
          style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
          color = VynozaLavenderSoft,
          modifier =
            Modifier
              .clip(RoundedCornerShape(6.dp))
              .clickable(enabled = canOpenProfile, onClick = onAuthorClick)
              .padding(vertical = 4.dp, horizontal = 2.dp)
              .testTag("comment_author_${comment.id}"),
        )
        Text(
          text = "• $timeText",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("comment_timestamp_${comment.id}"),
        )
      }
      Text(
        text = comment.text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }

    if (isOwnedByCurrentUser) {
      Spacer(modifier = Modifier.size(2.dp))
      IconButton(
        onClick = onDeleteClick,
        enabled = !isDeleting,
        modifier =
          Modifier
            .size(48.dp)
            .semantics { contentDescription = deleteLabel }
            .testTag("delete_comment_button_${comment.id}"),
      ) {
        if (isDeleting) {
          CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.error,
          )
        } else {
          Icon(
            imageVector = Icons.Rounded.DeleteOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

internal fun formatCommentTimestamp(timestamp: Timestamp?): String {
  if (timestamp == null) return "Just now"
  val timeMillis = timestamp.toDate().time
  val nowMillis = System.currentTimeMillis()
  val diffMillis = (nowMillis - timeMillis).coerceAtLeast(0L)
  if (diffMillis < DateUtils.MINUTE_IN_MILLIS) return "Just now"
  if (diffMillis < DateUtils.DAY_IN_MILLIS) {
    return DateUtils.getRelativeTimeSpanString(
      timeMillis,
      nowMillis,
      DateUtils.MINUTE_IN_MILLIS,
      DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
  }
  return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timeMillis))
}
