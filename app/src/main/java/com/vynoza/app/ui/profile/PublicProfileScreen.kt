package com.vynoza.app.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vynoza.app.R
import com.vynoza.app.core.model.UiState
import com.vynoza.app.ui.components.CommentsBottomSheet
import com.vynoza.app.ui.components.VynozaCreatorAvatar
import com.vynoza.app.ui.components.VynozaEmptyStateCard
import com.vynoza.app.ui.components.VynozaErrorCard
import com.vynoza.app.ui.components.VynozaStatPill
import com.vynoza.app.ui.components.VynozaVideoPlayerDialog
import com.vynoza.app.ui.components.VynozaVideoThumbnailTile
import com.vynoza.app.ui.components.launchProfileShareSheet
import com.vynoza.app.ui.components.launchVideoShareSheet
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun PublicProfileScreen(
  viewModel: PublicProfileViewModel,
  onBack: () -> Unit,
  onOpenCreatorProfile: (String) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val profileState by viewModel.publicProfileState.collectAsStateWithLifecycle()
  val isFollowInFlight by viewModel.isFollowInFlight.collectAsStateWithLifecycle()
  val likingVideoIds by viewModel.likingVideoIds.collectAsStateWithLifecycle()
  val savingVideoIds by viewModel.savingVideoIds.collectAsStateWithLifecycle()
  val actionError by viewModel.actionError.collectAsStateWithLifecycle()
  val commentsState by viewModel.commentsState.collectAsStateWithLifecycle()
  var selectedVideoId by remember { mutableStateOf<String?>(null) }

  val shareProfileUnavailableMsg = stringResource(R.string.share_profile_unavailable)
  val shareVideoUnavailableMsg = stringResource(R.string.share_unavailable)
  val successData = (profileState as? UiState.Success)?.data
  val selectedVideo = successData?.videos?.firstOrNull { it.id == selectedVideoId }

  BackHandler(
    enabled = commentsState.activeVideoId == null && selectedVideo == null,
    onBack = onBack,
  )

  Column(
    modifier =
      modifier
        .fillMaxSize()
        .background(VynozaObsidian)
        .statusBarsPadding()
        .padding(horizontal = 20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .widthIn(max = 640.dp)
          .padding(top = 8.dp, bottom = 8.dp)
          .testTag("public_profile_top_bar"),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(
        onClick = onBack,
        modifier = Modifier.size(48.dp).testTag("public_profile_back_button"),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
          contentDescription = stringResource(R.string.public_profile_back),
          tint = MaterialTheme.colorScheme.onBackground,
        )
      }

      Text(
        text = successData?.profile?.let { "@${it.username}" }.orEmpty(),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("public_profile_top_handle"),
        textAlign = TextAlign.Center,
      )

      val currentProfile = successData?.profile
      if (currentProfile != null) {
        IconButton(
          onClick = {
            if (!launchProfileShareSheet(context, currentProfile)) {
              viewModel.showActionError(shareProfileUnavailableMsg)
            }
          },
          modifier = Modifier.size(48.dp).testTag("public_profile_share_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Share,
            contentDescription = stringResource(R.string.share_profile),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        Spacer(modifier = Modifier.size(48.dp))
      }
    }

    when (val state = profileState) {
      UiState.Loading -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(
            color = VynozaPurplePrimary,
            modifier = Modifier.testTag("public_profile_loading"),
          )
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
        val data = state.data
        val profile = data.profile
        val videos = data.videos
        val gridState = rememberLazyGridState()

        LaunchedEffect(gridState, data.canLoadMore, data.isLoadingMore, videos.size) {
          snapshotFlow {
            val layoutInfo = gridState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to layoutInfo.totalItemsCount
          }
            .map { (lastVisible, totalItems) ->
              totalItems > 0 && lastVisible >= totalItems - 3
            }
            .distinctUntilChanged()
            .collect { nearEnd ->
              if (nearEnd && data.canLoadMore && !data.isLoadingMore) {
                viewModel.loadMoreVideos()
              }
            }
        }

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
                .testTag("public_profile_error_banner"),
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

        val activeCommentVideo = videos.firstOrNull { it.id == commentsState.activeVideoId }
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
              if (authorId.isNotBlank() && authorId != viewModel.targetUserId) {
                viewModel.closeComments()
                selectedVideoId = null
                onOpenCreatorProfile(authorId)
              }
            },
          )
        }

        if (selectedVideo != null && commentsState.activeVideoId == null) {
          val isLiked = selectedVideo.id in data.likedVideoIds
          val isSaved = selectedVideo.id in data.savedVideoIds
          VynozaVideoPlayerDialog(
            post = selectedVideo,
            onDismiss = { selectedVideoId = null },
            isLiked = isLiked,
            isLikeInFlight = selectedVideo.id in likingVideoIds,
            onToggleLike = { viewModel.toggleLike(video = selectedVideo, currentlyLiked = isLiked) },
            onOpenComments = { viewModel.openComments(selectedVideo.id) },
            isSaved = isSaved,
            isSaveInFlight = selectedVideo.id in savingVideoIds,
            onToggleSave = { viewModel.toggleSave(video = selectedVideo, currentlySaved = isSaved) },
            onShare = {
              if (!launchVideoShareSheet(context, selectedVideo)) {
                viewModel.showActionError(shareVideoUnavailableMsg)
              }
            },
          )
        }

        LazyVerticalGrid(
          state = gridState,
          columns = GridCells.Fixed(3),
          modifier =
            Modifier
              .fillMaxSize()
              .widthIn(max = 640.dp)
              .testTag("public_profile_grid"),
          contentPadding = PaddingValues(bottom = 24.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          item(span = { GridItemSpan(maxLineSpan) }) {
            PublicProfileHeaderCard(
              data = data,
              isFollowInFlight = isFollowInFlight,
              onToggleFollow = viewModel::toggleFollow,
              onShareProfile = {
                if (!launchProfileShareSheet(context, profile)) {
                  viewModel.showActionError(shareProfileUnavailableMsg)
                }
              },
            )
          }

          item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
              text = stringResource(R.string.public_profile_videos_section),
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
              color = VynozaLavenderSoft,
              modifier = Modifier.padding(vertical = 6.dp),
            )
          }

          if (videos.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
              Box(modifier = Modifier.padding(top = 8.dp).testTag("public_profile_empty_videos")) {
                VynozaEmptyStateCard(
                  icon = Icons.Rounded.Videocam,
                  title = stringResource(R.string.public_profile_empty_videos_title),
                  subtitle =
                    stringResource(
                      R.string.public_profile_empty_videos_subtitle,
                      profile.username,
                    ),
                )
              }
            }
          } else {
            items(videos, key = { "pub_${it.id}" }) { video ->
              VynozaVideoThumbnailTile(
                video = video,
                isLiked = video.id in data.likedVideoIds,
                onClick = {
                  if (!video.playbackUrl.isNullOrBlank()) {
                    selectedVideoId = video.id
                  }
                },
                tileTag = "public_profile_video_tile_${video.id}",
                commentCountTag = "public_profile_tile_comment_count_${video.id}",
                likeCountTag = "public_profile_tile_like_count_${video.id}",
                viewsCountTag = "public_profile_tile_views_count_${video.id}",
              )
            }
          }

          if (data.canLoadMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
              Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                contentAlignment = Alignment.Center,
              ) {
                OutlinedButton(
                  onClick = viewModel::loadMoreVideos,
                  enabled = !data.isLoadingMore,
                  shape = RoundedCornerShape(14.dp),
                  border = BorderStroke(1.dp, VynozaPurplePrimary.copy(alpha = 0.7f)),
                  modifier = Modifier.height(48.dp).testTag("public_profile_load_more_button"),
                ) {
                  if (data.isLoadingMore) {
                    CircularProgressIndicator(
                      modifier = Modifier.size(18.dp),
                      strokeWidth = 2.dp,
                      color = VynozaLavenderSoft,
                    )
                  } else {
                    Text(
                      text = stringResource(R.string.public_profile_load_more),
                      color = VynozaLavenderSoft,
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PublicProfileHeaderCard(
  data: PublicProfileUiData,
  isFollowInFlight: Boolean,
  onToggleFollow: () -> Unit,
  onShareProfile: () -> Unit,
) {
  val profile = data.profile
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
        displayName = profile.displayName,
        avatarUrl = profile.avatarUrl,
        size = 76.dp,
        modifier = Modifier.testTag("public_profile_avatar"),
      )
      Spacer(modifier = Modifier.height(10.dp))
      Text(
        text = profile.displayName,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("public_profile_display_name"),
      )
      Text(
        text = "@${profile.username}",
        style = MaterialTheme.typography.bodyMedium,
        color = VynozaLavenderSoft,
        modifier = Modifier.testTag("public_profile_username"),
      )
      val bioText = profile.bio.takeIf { it.isNotBlank() }
      if (bioText != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = bioText,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.testTag("public_profile_bio"),
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        VynozaStatPill(
          count = data.followersCount,
          label = stringResource(R.string.profile_followers),
          modifier = Modifier.testTag("public_profile_followers_count"),
        )
        VynozaStatPill(
          count = profile.followingCount,
          label = stringResource(R.string.profile_following),
          modifier = Modifier.testTag("public_profile_following_count"),
        )
        VynozaStatPill(
          count = data.publicVideosCount,
          label = stringResource(R.string.profile_videos),
          modifier = Modifier.testTag("public_profile_videos_count"),
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (!data.isOwnProfile) {
          if (data.isFollowing) {
            OutlinedButton(
              onClick = onToggleFollow,
              enabled = !isFollowInFlight,
              shape = RoundedCornerShape(14.dp),
              border = BorderStroke(1.dp, VynozaPurplePrimary),
              modifier = Modifier.weight(1f).height(48.dp).testTag("public_profile_follow_button"),
            ) {
              Text(
                text = stringResource(R.string.following_state),
                color = VynozaLavenderSoft,
              )
            }
          } else {
            Button(
              onClick = onToggleFollow,
              enabled = !isFollowInFlight,
              shape = RoundedCornerShape(14.dp),
              colors = ButtonDefaults.buttonColors(containerColor = VynozaPurplePrimary),
              modifier = Modifier.weight(1f).height(48.dp).testTag("public_profile_follow_button"),
            ) {
              Text(text = stringResource(R.string.follow))
            }
          }
        }

        OutlinedButton(
          onClick = onShareProfile,
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, VynozaBorderSubtle),
          modifier =
            Modifier
              .let { if (data.isOwnProfile) it.fillMaxWidth() else it }
              .height(48.dp)
              .testTag("public_profile_share_action"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Share,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = VynozaLavenderSoft,
          )
          Spacer(modifier = Modifier.size(6.dp))
          Text(
            text = stringResource(R.string.share_label),
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    }
  }
}
