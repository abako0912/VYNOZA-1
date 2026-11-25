package com.vynoza.app.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vynoza.app.BuildConfig
import com.vynoza.app.R
import com.vynoza.app.core.model.UiState
import com.vynoza.app.core.model.UserPublicProfile
import com.vynoza.app.core.model.VideoPost
import com.vynoza.app.core.update.AppUpdateUiState
import com.vynoza.app.ui.components.CommentsBottomSheet
import com.vynoza.app.ui.components.VynozaCreatorAvatar
import com.vynoza.app.ui.components.VynozaEmptyStateCard
import com.vynoza.app.ui.components.VynozaErrorCard
import com.vynoza.app.ui.components.VynozaStatPill
import com.vynoza.app.ui.components.VynozaVideoPlayerDialog
import com.vynoza.app.ui.components.VynozaVideoThumbnailTile
import com.vynoza.app.ui.components.launchVideoShareSheet
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import com.vynoza.app.ui.theme.VynozaVioletContainer
import com.vynoza.app.ui.theme.VynozaWarning
import com.vynoza.app.ui.update.ProfileAppUpdateCard

@Composable
fun ProfileScreen(
  viewModel: ProfileViewModel,
  onSignOut: () -> Unit,
  onOpenCreatorProfile: (String) -> Unit = {},
  appUpdateState: AppUpdateUiState = AppUpdateUiState.Idle,
  currentAppVersionName: String = BuildConfig.VERSION_NAME,
  currentAppVersionCode: Int = BuildConfig.VERSION_CODE,
  onCheckForUpdates: () -> Unit = {},
  onOpenUpdateDialog: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val profileState by viewModel.profileState.collectAsStateWithLifecycle()
  val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
  val editStatus by viewModel.editStatus.collectAsStateWithLifecycle()
  val likingVideoIds by viewModel.likingVideoIds.collectAsStateWithLifecycle()
  val savingVideoIds by viewModel.savingVideoIds.collectAsStateWithLifecycle()
  val actionError by viewModel.actionError.collectAsStateWithLifecycle()
  val commentsState by viewModel.commentsState.collectAsStateWithLifecycle()
  var showEditDialog by remember { mutableStateOf(false) }
  var selectedVideoId by remember { mutableStateOf<String?>(null) }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(VynozaObsidian)
      .statusBarsPadding()
      .padding(horizontal = 20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 640.dp)
        .padding(top = 12.dp, bottom = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.profile_title),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
      )
      IconButton(
        onClick = onSignOut,
        modifier = Modifier.testTag("sign_out_button"),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.Logout,
          contentDescription = stringResource(R.string.profile_sign_out),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    when (val state = profileState) {
      UiState.Loading -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(color = VynozaPurplePrimary)
        }
      }

      is UiState.Error -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          VynozaErrorCard(
            message = state.message,
            onRetry = viewModel::retry,
          )
        }
      }

      is UiState.Success -> {
        val profile = state.data.profile
        val videos = state.data.videos
        val savedVideos = state.data.savedVideos
        val likedVideoIds = state.data.likedVideoIds
        val savedVideoIds = state.data.savedVideoIds
        val allAvailableVideos = videos + savedVideos
        val selectedVideo = allAvailableVideos.firstOrNull { it.id == selectedVideoId }

        if (actionError != null) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
            modifier =
              Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .padding(bottom = 10.dp)
                .testTag("profile_like_error_banner"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = actionError!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
              )
              IconButton(onClick = viewModel::clearActionError) {
                Icon(
                  imageVector = Icons.Rounded.Close,
                  contentDescription = stringResource(R.string.dismiss),
                  tint = MaterialTheme.colorScheme.onErrorContainer,
                )
              }
            }
          }
        }

        if (showEditDialog && profile != null) {
          EditProfileDialog(
            profile = profile,
            editStatus = editStatus,
            onDismiss = {
              viewModel.clearEditError()
              showEditDialog = false
            },
            onSave = { displayName, username, bio ->
              viewModel.saveProfile(displayName, username, bio) {
                showEditDialog = false
              }
            },
          )
        }

        val activeCommentVideo = allAvailableVideos.firstOrNull { it.id == commentsState.activeVideoId }
        if (commentsState.activeVideoId != null) {
          CommentsBottomSheet(
            videoCommentsCount =
              activeCommentVideo?.commentsCount ?: commentsState.comments.size.toLong(),
            currentUserId = viewModel.actorUserId,
            state = commentsState,
            onDismiss = viewModel::closeComments,
            onRetryLoad = viewModel::retryLoadComments,
            onDraftChanged = viewModel::onCommentDraftChanged,
            onSubmitComment = viewModel::submitComment,
            onDeleteComment = viewModel::deleteComment,
            onClearError = viewModel::clearCommentError,
            onOpenCreatorProfile = { authorId ->
              if (authorId.isNotBlank() && authorId != viewModel.actorUserId) {
                viewModel.closeComments()
                selectedVideoId = null
                onOpenCreatorProfile(authorId)
              }
            },
          )
        }

        if (selectedVideo != null && commentsState.activeVideoId == null) {
          val isLiked = selectedVideo.id in likedVideoIds
          val isSaved = selectedVideo.id in savedVideoIds
          VynozaVideoPlayerDialog(
            post = selectedVideo,
            onDismiss = { selectedVideoId = null },
            isLiked = isLiked,
            isLikeInFlight = selectedVideo.id in likingVideoIds,
            onToggleLike = {
              viewModel.toggleLike(
                video = selectedVideo,
                currentlyLiked = isLiked,
                currentUsername = profile?.username,
              )
            },
            onOpenComments = { viewModel.openComments(selectedVideo.id) },
            isSaved = isSaved,
            isSaveInFlight = selectedVideo.id in savingVideoIds,
            onToggleSave = { viewModel.toggleSave(video = selectedVideo, currentlySaved = isSaved) },
            onShare = { launchVideoShareSheet(context, selectedVideo) },
            onOpenCreatorProfile = { authorId ->
              if (authorId.isNotBlank() && authorId != viewModel.actorUserId) {
                onOpenCreatorProfile(authorId)
              }
            },
          )
        }

        LazyVerticalGrid(
          columns = GridCells.Fixed(3),
          modifier = Modifier.fillMaxSize().widthIn(max = 640.dp).testTag("profile_grid"),
          contentPadding = PaddingValues(bottom = 24.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          item(span = { GridItemSpan(maxLineSpan) }) {
            ProfileHeaderCard(
              profile = profile,
              followersCount = state.data.followersCount,
              followingCount = state.data.followingCount,
              videosCount = videos.size.toLong(),
              onEditClick = { showEditDialog = true },
              onSignOutClick = onSignOut,
            )
          }

          item(span = { GridItemSpan(maxLineSpan) }) {
            ProfileContentTabRow(
              selectedTab = selectedTab,
              videosCount = videos.size,
              savedCount = savedVideos.size,
              onSelectTab = viewModel::selectTab,
            )
          }

          when (selectedTab) {
            ProfileContentTab.VIDEOS -> {
              if (videos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                  Box(modifier = Modifier.padding(top = 12.dp)) {
                    VynozaEmptyStateCard(
                      icon = Icons.Rounded.Videocam,
                      title = stringResource(R.string.profile_empty_videos_title),
                      subtitle = stringResource(R.string.profile_empty_videos_subtitle),
                    )
                  }
                }
              } else {
                items(videos, key = { "my_${it.id}" }) { video ->
                  VynozaVideoThumbnailTile(
                    video = video,
                    isLiked = video.id in likedVideoIds,
                    onClick = {
                      if (!video.playbackUrl.isNullOrBlank()) {
                        selectedVideoId = video.id
                      }
                    },
                  )
                }
              }
            }

            ProfileContentTab.SAVED -> {
              if (savedVideos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                  Box(modifier = Modifier.padding(top = 12.dp)) {
                    VynozaEmptyStateCard(
                      icon = Icons.Rounded.Bookmark,
                      title = stringResource(R.string.profile_empty_saved_title),
                      subtitle = stringResource(R.string.profile_empty_saved_subtitle),
                    )
                  }
                }
              } else {
                items(savedVideos, key = { "saved_${it.id}" }) { video ->
                  ProfileSavedVideoTile(
                    video = video,
                    isRemoving = video.id in savingVideoIds,
                    onRemoveClick = {
                      viewModel.toggleSave(video = video, currentlySaved = true)
                    },
                    onClick = {
                      if (!video.playbackUrl.isNullOrBlank()) {
                        selectedVideoId = video.id
                      }
                    },
                  )
                }
              }
            }
          }

          item(span = { GridItemSpan(maxLineSpan) }) {
            ProfileAppUpdateCard(
              currentVersionName = currentAppVersionName,
              currentVersionCode = currentAppVersionCode,
              updateState = appUpdateState,
              onCheckForUpdates = onCheckForUpdates,
              onOpenUpdateDialog = onOpenUpdateDialog,
              modifier = Modifier.padding(top = 8.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ProfileHeaderCard(
  profile: UserPublicProfile?,
  followersCount: Long,
  followingCount: Long,
  videosCount: Long,
  onEditClick: () -> Unit,
  onSignOutClick: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(24.dp),
    color = VynozaSurfaceElevated,
    border = BorderStroke(1.dp, VynozaBorderSubtle),
    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      VynozaCreatorAvatar(
        displayName = profile?.displayName ?: "Vynoza",
        avatarUrl = profile?.avatarUrl,
        size = 72.dp,
      )
      Spacer(modifier = Modifier.height(10.dp))
      Text(
        text = profile?.displayName ?: "Setting up profile...",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "@${profile?.username ?: "vynoza"}",
        style = MaterialTheme.typography.bodyMedium,
        color = VynozaLavenderSoft,
      )
      val bioText = profile?.bio?.takeIf { it.isNotBlank() }
      if (bioText != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = bioText,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        VynozaStatPill(
          count = followersCount,
          label = stringResource(R.string.profile_followers),
          modifier = Modifier.testTag("profile_followers_count"),
        )
        VynozaStatPill(
          count = followingCount,
          label = stringResource(R.string.profile_following),
          modifier = Modifier.testTag("profile_following_count"),
        )
        VynozaStatPill(
          count = videosCount,
          label = stringResource(R.string.profile_videos),
          modifier = Modifier.testTag("profile_videos_count"),
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Button(
          onClick = onEditClick,
          enabled = profile != null,
          shape = RoundedCornerShape(14.dp),
          colors = ButtonDefaults.buttonColors(containerColor = VynozaPurplePrimary),
          modifier = Modifier.weight(1f).height(48.dp).testTag("edit_profile_button"),
        ) {
          Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.size(6.dp))
          Text(stringResource(R.string.profile_edit))
        }
        OutlinedButton(
          onClick = onSignOutClick,
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, VynozaBorderSubtle),
          modifier = Modifier.height(48.dp),
        ) {
          Text(stringResource(R.string.profile_sign_out))
        }
      }
    }
  }
}

@Composable
private fun ProfileContentTabRow(
  selectedTab: ProfileContentTab,
  videosCount: Int,
  savedCount: Int,
  onSelectTab: (ProfileContentTab) -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = VynozaSurfaceDeep,
    border = BorderStroke(1.dp, VynozaBorderSubtle),
    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(4.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      ProfileContentTabItem(
        label = "${stringResource(R.string.profile_tab_videos)} ($videosCount)",
        selected = selectedTab == ProfileContentTab.VIDEOS,
        onClick = { onSelectTab(ProfileContentTab.VIDEOS) },
        modifier = Modifier.weight(1f).testTag("profile_tab_videos"),
      )
      ProfileContentTabItem(
        label = "${stringResource(R.string.profile_tab_saved)} ($savedCount)",
        selected = selectedTab == ProfileContentTab.SAVED,
        onClick = { onSelectTab(ProfileContentTab.SAVED) },
        modifier = Modifier.weight(1f).testTag("profile_tab_saved"),
      )
    }
  }
}

@Composable
private fun ProfileContentTabItem(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(12.dp),
    color = if (selected) VynozaPurplePrimary else VynozaSurfaceDeep,
    modifier = modifier.height(48.dp),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge.copy(
          fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        ),
        color =
          if (selected) MaterialTheme.colorScheme.onPrimary
          else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ProfileSavedVideoTile(
  video: VideoPost,
  isRemoving: Boolean,
  onRemoveClick: () -> Unit,
  onClick: () -> Unit,
) {
  val hasPlayback = !video.playbackUrl.isNullOrBlank()
  val removeLabel = stringResource(R.string.unsave_video)
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = VynozaSurfaceDeep,
    border = BorderStroke(1.dp, VynozaBorderSubtle),
    modifier =
      Modifier
        .fillMaxWidth()
        .aspectRatio(9f / 14f)
        .clickable(enabled = hasPlayback, onClick = onClick)
        .testTag("profile_saved_tile_${video.id}"),
  ) {
    Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
      IconButton(
        onClick = onRemoveClick,
        enabled = !isRemoving,
        modifier =
          Modifier
            .align(Alignment.TopEnd)
            .size(36.dp)
            .clip(CircleShape)
            .background(VynozaObsidian.copy(alpha = 0.65f))
            .semantics { contentDescription = removeLabel }
            .testTag("profile_remove_saved_${video.id}"),
      ) {
        if (isRemoving) {
          CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = VynozaWarning,
          )
        } else {
          Icon(
            imageVector = Icons.Rounded.Bookmark,
            contentDescription = null,
            tint = VynozaWarning,
            modifier = Modifier.size(18.dp),
          )
        }
      }

      Box(
        modifier = Modifier
          .align(Alignment.Center)
          .size(40.dp)
          .background(VynozaVioletContainer, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.VideoLibrary,
          contentDescription = video.caption,
          tint = VynozaLavenderSoft,
        )
      }

      Column(modifier = Modifier.align(Alignment.BottomStart)) {
        Text(
          text = "@${video.authorUsername}",
          style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
          color = VynozaLavenderSoft,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = video.caption.ifBlank { "Saved clip" },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun EditProfileDialog(
  profile: UserPublicProfile,
  editStatus: EditProfileStatus,
  onDismiss: () -> Unit,
  onSave: (displayName: String, username: String, bio: String) -> Unit,
) {
  var displayName by remember(profile) { mutableStateOf(profile.displayName) }
  var username by remember(profile) { mutableStateOf(profile.username) }
  var bio by remember(profile) { mutableStateOf(profile.bio) }

  AlertDialog(
    onDismissRequest = { if (!editStatus.isSaving) onDismiss() },
    containerColor = VynozaSurfaceElevated,
    title = { Text(stringResource(R.string.profile_edit)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = displayName,
          onValueChange = { displayName = it.take(50) },
          label = { Text("Display Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("edit_display_name_input"),
        )
        OutlinedTextField(
          value = username,
          onValueChange = { username = it.lowercase().take(30) },
          label = { Text("Username") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("edit_username_input"),
        )
        OutlinedTextField(
          value = bio,
          onValueChange = { bio = it.take(200) },
          label = { Text("Bio") },
          maxLines = 3,
          modifier = Modifier.fillMaxWidth().testTag("edit_bio_input"),
        )
        if (editStatus.errorMessage != null) {
          Text(
            text = editStatus.errorMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onSave(displayName, username, bio) },
        enabled = !editStatus.isSaving,
        colors = ButtonDefaults.buttonColors(containerColor = VynozaPurplePrimary),
        modifier = Modifier.testTag("save_profile_button"),
      ) {
        if (editStatus.isSaving) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
          Text(stringResource(R.string.profile_save))
        }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, enabled = !editStatus.isSaving) {
        Text(stringResource(R.string.profile_cancel))
      }
    },
  )
}
