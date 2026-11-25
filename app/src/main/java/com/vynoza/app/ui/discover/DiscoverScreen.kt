package com.vynoza.app.ui.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vynoza.app.R
import com.vynoza.app.core.model.UiState
import com.vynoza.app.core.model.UserPublicProfile
import com.vynoza.app.core.model.VideoPost
import com.vynoza.app.ui.components.CommentsBottomSheet
import com.vynoza.app.ui.components.VynozaCreatorAvatar
import com.vynoza.app.ui.components.VynozaEmptyStateCard
import com.vynoza.app.ui.components.VynozaErrorCard
import com.vynoza.app.ui.components.VynozaVideoPlayerDialog
import com.vynoza.app.ui.components.launchVideoShareSheet
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaRoseAccent
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import com.vynoza.app.ui.theme.VynozaVioletContainer

@Composable
fun DiscoverScreen(
  viewModel: DiscoverViewModel,
  onOpenCreatorProfile: (String) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val discoverState by viewModel.discoverState.collectAsStateWithLifecycle()
  val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
  val selectedFilter by viewModel.filter.collectAsStateWithLifecycle()
  val actionError by viewModel.actionError.collectAsStateWithLifecycle()
  val likingVideoIds by viewModel.likingVideoIds.collectAsStateWithLifecycle()
  val savingVideoIds by viewModel.savingVideoIds.collectAsStateWithLifecycle()
  val followingUserIds by viewModel.followingUserIds.collectAsStateWithLifecycle()
  val commentsState by viewModel.commentsState.collectAsStateWithLifecycle()
  var selectedVideoId by remember { mutableStateOf<String?>(null) }

  val successData = (discoverState as? UiState.Success)?.data
  val selectedVideo = successData?.videos?.firstOrNull { it.id == selectedVideoId }
  val activeCommentVideo = successData?.videos?.firstOrNull { it.id == commentsState.activeVideoId }

  if (commentsState.activeVideoId != null) {
    CommentsBottomSheet(
      videoCommentsCount = activeCommentVideo?.commentsCount ?: commentsState.comments.size.toLong(),
      currentUserId = viewModel.actorUserId,
      state = commentsState,
      onDismiss = viewModel::closeComments,
      onRetryLoad = viewModel::retryLoadComments,
      onDraftChanged = viewModel::onCommentDraftChanged,
      onSubmitComment = viewModel::submitComment,
      onDeleteComment = viewModel::deleteComment,
      onClearError = viewModel::clearCommentError,
      onOpenCreatorProfile = { authorId ->
        if (authorId.isNotBlank()) {
          viewModel.closeComments()
          onOpenCreatorProfile(authorId)
        }
      },
    )
  }

  if (selectedVideo != null && commentsState.activeVideoId == null) {
    val isLiked = selectedVideo.id in successData.likedVideoIds
    val isSaved = selectedVideo.id in successData.savedVideoIds
    VynozaVideoPlayerDialog(
      post = selectedVideo,
      onDismiss = { selectedVideoId = null },
      isLiked = isLiked,
      isLikeInFlight = selectedVideo.id in likingVideoIds,
      onToggleLike = {
        viewModel.toggleLike(
          video = selectedVideo,
          currentlyLiked = isLiked,
          currentUsername = successData.currentUsername,
        )
      },
      onOpenComments = { viewModel.openComments(selectedVideo.id) },
      isSaved = isSaved,
      isSaveInFlight = selectedVideo.id in savingVideoIds,
      onToggleSave = { viewModel.toggleSave(video = selectedVideo, currentlySaved = isSaved) },
      onShare = { launchVideoShareSheet(context, selectedVideo) },
      onOpenCreatorProfile = onOpenCreatorProfile,
    )
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(VynozaObsidian)
      .statusBarsPadding()
      .padding(horizontal = 20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
      Spacer(modifier = Modifier.height(16.dp))
      Text(
        text = stringResource(R.string.discover_title),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
      )
      Spacer(modifier = Modifier.height(14.dp))

      OutlinedTextField(
        value = searchQuery,
        onValueChange = viewModel::updateQuery,
        singleLine = true,
        placeholder = {
          Text(
            text = stringResource(R.string.discover_search_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        },
        leadingIcon = {
          Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = VynozaLavenderSoft,
          )
        },
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(onClick = { viewModel.updateQuery("") }) {
              Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Clear search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        },
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
          focusedContainerColor = VynozaSurfaceElevated,
          unfocusedContainerColor = VynozaSurfaceDeep,
          focusedBorderColor = VynozaPurplePrimary,
          unfocusedBorderColor = VynozaBorderSubtle,
        ),
        modifier = Modifier.fillMaxWidth().testTag("discover_search_input"),
      )

      Spacer(modifier = Modifier.height(12.dp))

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DiscoverFilterChip(
          label = stringResource(R.string.discover_filter_all),
          selected = selectedFilter == DiscoverFilter.ALL,
          onClick = { viewModel.selectFilter(DiscoverFilter.ALL) },
        )
        DiscoverFilterChip(
          label = stringResource(R.string.discover_filter_creators),
          selected = selectedFilter == DiscoverFilter.CREATORS,
          onClick = { viewModel.selectFilter(DiscoverFilter.CREATORS) },
        )
        DiscoverFilterChip(
          label = stringResource(R.string.discover_filter_videos),
          selected = selectedFilter == DiscoverFilter.VIDEOS,
          onClick = { viewModel.selectFilter(DiscoverFilter.VIDEOS) },
        )
      }

      if (actionError != null) {
        Spacer(modifier = Modifier.height(10.dp))
        VynozaErrorCard(
          message = actionError!!,
          onRetry = viewModel::clearActionError,
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    when (val state = discoverState) {
      UiState.Loading -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(color = VynozaPurplePrimary)
        }
      }

      is UiState.Error -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          VynozaErrorCard(
            message = state.message,
            onRetry = { viewModel.selectFilter(selectedFilter) },
          )
        }
      }

      is UiState.Success -> {
        val data = state.data
        if (data.creators.isEmpty() && data.videos.isEmpty()) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            VynozaEmptyStateCard(
              icon = Icons.Rounded.Explore,
              title = stringResource(R.string.discover_empty_title),
              subtitle = stringResource(R.string.discover_empty_subtitle),
            )
          }
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 640.dp).testTag("discover_results_list"),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            if (data.creators.isNotEmpty()) {
              item {
                Text(
                  text = stringResource(R.string.discover_filter_creators),
                  style = MaterialTheme.typography.titleMedium,
                  color = VynozaLavenderSoft,
                  modifier = Modifier.padding(top = 4.dp),
                )
              }
              items(data.creators, key = { "creator_${it.userId}" }) { creator ->
                val isFollowing = creator.userId in data.followedUserIds
                val isFollowInFlight = creator.userId in followingUserIds
                CreatorResultRow(
                  creator = creator,
                  isFollowing = isFollowing,
                  isFollowInFlight = isFollowInFlight,
                  onToggleFollow = {
                    viewModel.toggleFollow(creator, isFollowing, data.currentUsername)
                  },
                  onOpenProfile = {
                    if (creator.userId.isNotBlank()) {
                      onOpenCreatorProfile(creator.userId)
                    }
                  },
                )
              }
            }

            if (data.videos.isNotEmpty()) {
              item {
                Text(
                  text = stringResource(R.string.discover_filter_videos),
                  style = MaterialTheme.typography.titleMedium,
                  color = VynozaLavenderSoft,
                  modifier = Modifier.padding(top = 8.dp),
                )
              }
              items(data.videos, key = { "video_${it.id}" }) { video ->
                val isLiked = video.id in data.likedVideoIds
                val isLikeInFlight = video.id in likingVideoIds
                VideoResultCard(
                  video = video,
                  isLiked = isLiked,
                  isLikeInFlight = isLikeInFlight,
                  onToggleLike = {
                    viewModel.toggleLike(
                      video = video,
                      currentlyLiked = isLiked,
                      currentUsername = data.currentUsername,
                    )
                  },
                  onOpenComments = { viewModel.openComments(video.id) },
                  onOpenCreatorProfile = {
                    if (video.authorId.isNotBlank()) {
                      onOpenCreatorProfile(video.authorId)
                    }
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
      }
    }
  }
}

@Composable
private fun DiscoverFilterChip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(label) },
    colors = FilterChipDefaults.filterChipColors(
      selectedContainerColor = VynozaVioletContainer,
      selectedLabelColor = VynozaLavenderSoft,
      containerColor = VynozaSurfaceDeep,
      labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ),
    border = FilterChipDefaults.filterChipBorder(
      enabled = true,
      selected = selected,
      borderColor = VynozaBorderSubtle,
      selectedBorderColor = VynozaPurplePrimary,
    ),
  )
}

@Composable
private fun CreatorResultRow(
  creator: UserPublicProfile,
  isFollowing: Boolean,
  isFollowInFlight: Boolean,
  onToggleFollow: () -> Unit,
  onOpenProfile: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = VynozaSurfaceElevated,
    border = BorderStroke(1.dp, VynozaBorderSubtle),
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(18.dp))
        .clickable(enabled = creator.userId.isNotBlank(), onClick = onOpenProfile)
        .testTag("discover_creator_card_${creator.userId}"),
  ) {
    Row(
      modifier = Modifier.padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      VynozaCreatorAvatar(
        displayName = creator.displayName,
        avatarUrl = creator.avatarUrl,
        size = 48.dp,
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = creator.displayName,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "@${creator.username}",
          style = MaterialTheme.typography.bodyMedium,
          color = VynozaLavenderSoft,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (creator.bio.isNotBlank()) {
          Text(
            text = creator.bio,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      if (isFollowing) {
        OutlinedButton(
          onClick = onToggleFollow,
          enabled = !isFollowInFlight,
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, VynozaPurplePrimary),
          modifier = Modifier.testTag("discover_follow_button_${creator.userId}"),
        ) {
          Text(stringResource(R.string.following_state), color = VynozaLavenderSoft)
        }
      } else {
        Button(
          onClick = onToggleFollow,
          enabled = !isFollowInFlight,
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = VynozaPurplePrimary),
          modifier = Modifier.testTag("discover_follow_button_${creator.userId}"),
        ) {
          Text(stringResource(R.string.follow))
        }
      }
    }
  }
}

@Composable
private fun VideoResultCard(
  video: VideoPost,
  isLiked: Boolean,
  isLikeInFlight: Boolean,
  onToggleLike: () -> Unit,
  onOpenComments: () -> Unit,
  onOpenCreatorProfile: () -> Unit,
  onClick: () -> Unit,
) {
  val hasPlayback = !video.playbackUrl.isNullOrBlank()
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = VynozaSurfaceElevated,
    border = BorderStroke(1.dp, VynozaBorderSubtle),
    modifier = Modifier.fillMaxWidth().clickable(enabled = hasPlayback, onClick = onClick),
  ) {
    Row(
      modifier = Modifier.padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Box(
        modifier = Modifier
          .size(56.dp)
          .background(VynozaVioletContainer, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.Videocam,
          contentDescription = null,
          tint = VynozaLavenderSoft,
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "@${video.authorUsername}",
          style = MaterialTheme.typography.labelLarge,
          color = VynozaLavenderSoft,
          modifier =
            Modifier
              .clip(RoundedCornerShape(6.dp))
              .clickable(enabled = video.authorId.isNotBlank(), onClick = onOpenCreatorProfile)
              .padding(vertical = 4.dp, horizontal = 2.dp)
              .testTag("discover_video_author_${video.id}"),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = video.caption.ifBlank { "Untitled clip" },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }

      val openCommentsLabel = stringResource(R.string.open_comments)
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        IconButton(
          onClick = onOpenComments,
          modifier =
            Modifier
              .size(48.dp)
              .semantics { contentDescription = openCommentsLabel }
              .testTag("discover_comment_button_${video.id}"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Rounded.Comment,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          text = video.commentsCount.toString(),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("discover_comment_count_${video.id}"),
        )
      }

      val likeActionLabel =
        when {
          isLikeInFlight -> stringResource(R.string.updating_like)
          isLiked -> stringResource(R.string.unlike_video)
          else -> stringResource(R.string.like_video)
        }
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        IconButton(
          onClick = onToggleLike,
          enabled = !isLikeInFlight,
          modifier =
            Modifier
              .size(48.dp)
              .semantics { contentDescription = likeActionLabel }
              .testTag("discover_like_button_${video.id}"),
        ) {
          if (isLikeInFlight) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
              color = VynozaRoseAccent,
            )
          } else {
            Icon(
              imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
              contentDescription = null,
              tint =
                if (isLiked) VynozaRoseAccent
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Text(
          text = video.likesCount.toString(),
          style = MaterialTheme.typography.labelSmall,
          color = if (isLiked) VynozaRoseAccent else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("discover_like_count_${video.id}"),
        )
      }
    }
  }
}
