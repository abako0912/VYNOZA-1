package com.vynoza.app.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.vynoza.app.ui.components.VynozaVideoPlayer
import com.vynoza.app.ui.components.VynozaWordmark
import com.vynoza.app.ui.components.launchVideoShareSheet
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaRoseAccent
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import com.vynoza.app.ui.theme.VynozaWarning

@Composable
fun HomeScreen(
  viewModel: HomeViewModel,
  onNavigateToCreate: () -> Unit,
  onOpenCreatorProfile: (String) -> Unit = {},
  isScreenVisible: Boolean = true,
  targetVideoId: String? = null,
  onTargetVideoConsumed: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val feedState by viewModel.feedState.collectAsStateWithLifecycle()
  val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
  val likingVideoIds by viewModel.likingVideoIds.collectAsStateWithLifecycle()
  val savingVideoIds by viewModel.savingVideoIds.collectAsStateWithLifecycle()
  val followingUserIds by viewModel.followingUserIds.collectAsStateWithLifecycle()
  val actionError by viewModel.actionError.collectAsStateWithLifecycle()
  val commentsState by viewModel.commentsState.collectAsStateWithLifecycle()
  val shareUnavailableMsg = stringResource(R.string.share_unavailable)

  val activeCommentVideo =
    (feedState as? UiState.Success)?.data?.videos?.firstOrNull {
      it.id == commentsState.activeVideoId
    }

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

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(VynozaObsidian)
  ) {
    when (val state = feedState) {
      UiState.Loading -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(color = VynozaPurplePrimary)
        }
      }

      is UiState.Error -> {
        Box(
          modifier = Modifier.fillMaxSize().padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          VynozaErrorCard(
            message = state.message,
            onRetry = { viewModel.selectTab(selectedTab) },
          )
        }
      }

      is UiState.Success -> {
        val data = state.data
        val videos = data.videos
        if (videos.isEmpty()) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
          ) {
            VynozaEmptyStateCard(
              icon = Icons.Rounded.Videocam,
              title = stringResource(R.string.home_empty_title),
              subtitle = stringResource(R.string.home_empty_subtitle),
              actionLabel = stringResource(R.string.home_empty_cta),
              onActionClick = onNavigateToCreate,
            )
          }
        } else {
          val pagerState = rememberPagerState(pageCount = { videos.size })

          LaunchedEffect(targetVideoId, videos) {
            if (!targetVideoId.isNullOrBlank()) {
              val index = videos.indexOfFirst { it.id == targetVideoId }
              if (index >= 0) {
                pagerState.scrollToPage(index)
                onTargetVideoConsumed()
              }
            }
          }

          VerticalPager(
            state = pagerState,
            key = { index -> videos[index].id },
            modifier = Modifier.fillMaxSize().testTag("home_vertical_feed"),
          ) { page ->
            val post = videos[page]
            val creatorProfile = data.creatorProfiles[post.authorId]
            val isOwnVideo = post.authorId == viewModel.actorUserId
            val isFollowingCreator = post.authorId in data.followedUserIds
            val isFollowInFlight = post.authorId in followingUserIds
            val isLiked = post.id in data.likedVideoIds
            val isLikeInFlight = post.id in likingVideoIds
            val isSaved = post.id in data.savedVideoIds
            val isSaveInFlight = post.id in savingVideoIds

            VideoFeedCard(
              post = post,
              creatorProfile = creatorProfile,
              isActive =
                isScreenVisible &&
                  pagerState.currentPage == page &&
                  commentsState.activeVideoId == null,
              isOwnVideo = isOwnVideo,
              isFollowingCreator = isFollowingCreator,
              isFollowInFlight = isFollowInFlight,
              onToggleFollow = {
                viewModel.toggleFollowCreator(
                  video = post,
                  currentlyFollowing = isFollowingCreator,
                  creatorProfile = creatorProfile,
                  actorUsername = data.currentUsername,
                )
              },
              isLiked = isLiked,
              isLikeInFlight = isLikeInFlight,
              onToggleLike = {
                viewModel.toggleLike(
                  video = post,
                  currentlyLiked = isLiked,
                  actorUsername = data.currentUsername,
                )
              },
              onOpenComments = { viewModel.openComments(post.id) },
              isSaved = isSaved,
              isSaveInFlight = isSaveInFlight,
              onToggleSave = {
                viewModel.toggleSave(video = post, currentlySaved = isSaved)
              },
              onShare = {
                if (!launchVideoShareSheet(context, post)) {
                  viewModel.showActionError(shareUnavailableMsg)
                }
              },
              onOpenCreatorProfile = {
                if (post.authorId.isNotBlank()) {
                  onOpenCreatorProfile(post.authorId)
                }
              },
            )
          }
        }
      }
    }

    // Top overlay bar with brand wordmark & For You / Following tabs
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        VynozaWordmark()

        Surface(
          shape = RoundedCornerShape(20.dp),
          color = VynozaSurfaceElevated.copy(alpha = 0.85f),
          border = BorderStroke(1.dp, VynozaBorderSubtle),
        ) {
          Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            FeedTabPill(
              label = stringResource(R.string.home_tab_for_you),
              selected = selectedTab == HomeFeedTab.FOR_YOU,
              onClick = { viewModel.selectTab(HomeFeedTab.FOR_YOU) },
              modifier = Modifier.testTag("home_tab_for_you"),
            )
            FeedTabPill(
              label = stringResource(R.string.home_tab_following),
              selected = selectedTab == HomeFeedTab.FOLLOWING,
              onClick = { viewModel.selectTab(HomeFeedTab.FOLLOWING) },
              modifier = Modifier.testTag("home_tab_following"),
            )
          }
        }
      }

      if (actionError != null) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(end = 72.dp)
              .testTag("home_like_error_banner"),
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
    }
  }
}

@Composable
private fun FeedTabPill(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val bg = if (selected) VynozaPurplePrimary else VynozaSurfaceElevated.copy(alpha = 0f)
  val textColor =
    if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(16.dp))
      .background(bg)
      .clickable(onClick = onClick)
      .minimumInteractiveComponentSize()
      .padding(horizontal = 14.dp, vertical = 8.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color = textColor,
    )
  }
}

@Composable
private fun VideoFeedCard(
  post: VideoPost,
  creatorProfile: UserPublicProfile?,
  isActive: Boolean,
  isOwnVideo: Boolean,
  isFollowingCreator: Boolean,
  isFollowInFlight: Boolean,
  onToggleFollow: () -> Unit,
  isLiked: Boolean,
  isLikeInFlight: Boolean,
  onToggleLike: () -> Unit,
  onOpenComments: () -> Unit,
  isSaved: Boolean,
  isSaveInFlight: Boolean,
  onToggleSave: () -> Unit,
  onShare: () -> Unit,
  onOpenCreatorProfile: () -> Unit,
) {
  val playbackUrl = post.playbackUrl?.takeIf { it.isNotBlank() }
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          colors = listOf(VynozaSurfaceDeep, VynozaObsidian)
        )
      )
  ) {
    if (playbackUrl != null) {
      VynozaVideoPlayer(
        playbackUrl = playbackUrl,
        isActive = isActive,
        muteButtonTopPadding = 64.dp,
        modifier = Modifier.fillMaxSize(),
      )
      Box(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .height(220.dp)
          .background(
            Brush.verticalGradient(
              colors = listOf(
                VynozaObsidian.copy(alpha = 0f),
                VynozaObsidian.copy(alpha = 0.82f),
              )
            )
          )
      )
    } else {
      Column(
        modifier = Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Box(
          modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(VynozaPurplePrimary.copy(alpha = 0.2f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.VideoLibrary,
            contentDescription = "Video post",
            tint = VynozaLavenderSoft,
            modifier = Modifier.size(34.dp),
          )
        }
        Text(
          text = "Video URL unavailable",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    // Bottom creator caption + TikTok-inspired right-side vertical engagement rail
    Row(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.Bottom,
    ) {
      Column(
        modifier = Modifier.weight(1f).padding(end = 16.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = "@${post.authorUsername}",
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
          color = VynozaLavenderSoft,
          modifier =
            Modifier
              .clip(RoundedCornerShape(8.dp))
              .clickable(enabled = post.authorId.isNotBlank(), onClick = onOpenCreatorProfile)
              .minimumInteractiveComponentSize()
              .padding(vertical = 4.dp, horizontal = 2.dp)
              .testTag("home_creator_username_${post.id}"),
        )
        if (post.caption.isNotBlank()) {
          Text(
            text = post.caption,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
          )
        }
      }

      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        FeedCreatorFollowAvatar(
          videoId = post.id,
          creatorDisplayName = creatorProfile?.displayName ?: post.authorUsername,
          creatorUsername = post.authorUsername,
          avatarUrl = creatorProfile?.avatarUrl,
          isOwnVideo = isOwnVideo,
          isFollowing = isFollowingCreator,
          isInFlight = isFollowInFlight,
          onAvatarClick = onOpenCreatorProfile,
          onToggleFollow = onToggleFollow,
        )
        FeedLikeButton(
          videoId = post.id,
          likesCount = post.likesCount,
          isLiked = isLiked,
          isInFlight = isLikeInFlight,
          onClick = onToggleLike,
        )
        FeedCommentButton(
          videoId = post.id,
          commentsCount = post.commentsCount,
          onClick = onOpenComments,
        )
        FeedSaveButton(
          videoId = post.id,
          isSaved = isSaved,
          isInFlight = isSaveInFlight,
          onClick = onToggleSave,
        )
        FeedShareButton(
          videoId = post.id,
          sharesCount = post.sharesCount,
          onClick = onShare,
        )
      }
    }
  }
}

@Composable
private fun FeedCreatorFollowAvatar(
  videoId: String,
  creatorDisplayName: String,
  creatorUsername: String,
  avatarUrl: String?,
  isOwnVideo: Boolean,
  isFollowing: Boolean,
  isInFlight: Boolean,
  onAvatarClick: () -> Unit,
  onToggleFollow: () -> Unit,
) {
  val followContentDesc =
    if (isFollowing) {
      stringResource(R.string.quick_following_creator, creatorUsername)
    } else {
      stringResource(R.string.quick_follow_creator, creatorUsername)
    }

  Box(
    modifier =
      Modifier
        .padding(bottom = if (isOwnVideo) 2.dp else 4.dp)
        .size(width = 52.dp, height = if (isOwnVideo) 48.dp else 78.dp),
    contentAlignment = Alignment.TopCenter,
  ) {
    VynozaCreatorAvatar(
      displayName = creatorDisplayName,
      avatarUrl = avatarUrl,
      size = 48.dp,
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .clip(CircleShape)
          .clickable(onClick = onAvatarClick)
          .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
          .testTag("home_creator_avatar_$videoId"),
    )

    if (!isOwnVideo) {
      Box(
        modifier =
          Modifier
            .align(Alignment.BottomCenter)
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = !isInFlight, role = Role.Button, onClick = onToggleFollow)
            .semantics { contentDescription = followContentDesc }
            .testTag("home_follow_button_$videoId"),
        contentAlignment = Alignment.Center,
      ) {
        Box(
          modifier =
            Modifier
              .size(22.dp)
              .clip(CircleShape)
              .background(if (isFollowing) VynozaSurfaceElevated else VynozaRoseAccent)
              .border(
                width = 1.5.dp,
                color = if (isFollowing) VynozaLavenderSoft else Color.White,
                shape = CircleShape,
              ),
          contentAlignment = Alignment.Center,
        ) {
          if (isInFlight) {
            CircularProgressIndicator(
              modifier = Modifier.size(12.dp),
              strokeWidth = 1.5.dp,
              color = Color.White,
            )
          } else {
            Icon(
              imageVector = if (isFollowing) Icons.Rounded.Check else Icons.Rounded.Add,
              contentDescription = null,
              tint = if (isFollowing) VynozaLavenderSoft else Color.White,
              modifier = Modifier.size(15.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun FeedLikeButton(
  videoId: String,
  likesCount: Long,
  isLiked: Boolean,
  isInFlight: Boolean,
  onClick: () -> Unit,
) {
  val iconScale by
    animateFloatAsState(
      targetValue = if (isLiked) 1.12f else 1.0f,
      animationSpec =
        spring(
          dampingRatio = Spring.DampingRatioMediumBouncy,
          stiffness = Spring.StiffnessMedium,
        ),
      label = "like_heart_scale",
    )

  val likeActionLabel =
    when {
      isInFlight -> stringResource(R.string.updating_like)
      isLiked -> stringResource(R.string.unlike_video)
      else -> stringResource(R.string.like_video)
    }

  Box(
    modifier = Modifier.size(width = 56.dp, height = 60.dp),
    contentAlignment = Alignment.TopCenter,
  ) {
    Box(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .size(48.dp)
          .clip(CircleShape)
          .background(
            if (isLiked) VynozaRoseAccent.copy(alpha = 0.22f)
            else VynozaObsidian.copy(alpha = 0.45f)
          )
          .clickable(enabled = !isInFlight, role = Role.Button, onClick = onClick)
          .semantics { contentDescription = likeActionLabel }
          .testTag("home_like_button_$videoId"),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        contentDescription = null,
        tint =
          when {
            isLiked -> VynozaRoseAccent
            else -> MaterialTheme.colorScheme.onBackground
          },
        modifier = Modifier.size(26.dp).scale(iconScale),
      )
      if (isInFlight) {
        CircularProgressIndicator(
          modifier = Modifier.size(38.dp),
          strokeWidth = 2.dp,
          color = VynozaLavenderSoft,
        )
      }
    }
    Text(
      text = likesCount.toString(),
      style = MaterialTheme.typography.labelMedium,
      color = if (isLiked) VynozaRoseAccent else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.align(Alignment.BottomCenter).testTag("home_like_count_$videoId"),
    )
  }
}

@Composable
private fun FeedCommentButton(
  videoId: String,
  commentsCount: Long,
  onClick: () -> Unit,
) {
  val openCommentsLabel = stringResource(R.string.open_comments)
  Box(
    modifier = Modifier.size(width = 56.dp, height = 60.dp),
    contentAlignment = Alignment.TopCenter,
  ) {
    Box(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .size(48.dp)
          .clip(CircleShape)
          .background(VynozaObsidian.copy(alpha = 0.45f))
          .clickable(role = Role.Button, onClick = onClick)
          .semantics { contentDescription = openCommentsLabel }
          .testTag("home_comment_button_$videoId"),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Rounded.Comment,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.size(24.dp),
      )
    }
    Text(
      text = commentsCount.toString(),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.align(Alignment.BottomCenter).testTag("home_comment_count_$videoId"),
    )
  }
}

@Composable
private fun FeedSaveButton(
  videoId: String,
  isSaved: Boolean,
  isInFlight: Boolean,
  onClick: () -> Unit,
) {
  val saveActionLabel =
    when {
      isInFlight -> stringResource(R.string.updating_save)
      isSaved -> stringResource(R.string.unsave_video)
      else -> stringResource(R.string.save_video)
    }

  Box(
    modifier = Modifier.size(width = 56.dp, height = 60.dp),
    contentAlignment = Alignment.TopCenter,
  ) {
    Box(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .size(48.dp)
          .clip(CircleShape)
          .background(
            if (isSaved) VynozaWarning.copy(alpha = 0.22f)
            else VynozaObsidian.copy(alpha = 0.45f)
          )
          .clickable(enabled = !isInFlight, role = Role.Button, onClick = onClick)
          .semantics { contentDescription = saveActionLabel }
          .testTag("home_save_button_$videoId"),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
        contentDescription = null,
        tint = if (isSaved) VynozaWarning else MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.size(25.dp),
      )
      if (isInFlight) {
        CircularProgressIndicator(
          modifier = Modifier.size(38.dp),
          strokeWidth = 2.dp,
          color = VynozaWarning,
        )
      }
    }
    Text(
      text = stringResource(if (isSaved) R.string.saved_label else R.string.save_label),
      style = MaterialTheme.typography.labelMedium,
      color = if (isSaved) VynozaWarning else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.align(Alignment.BottomCenter).testTag("home_save_label_$videoId"),
    )
  }
}

@Composable
private fun FeedShareButton(
  videoId: String,
  sharesCount: Long,
  onClick: () -> Unit,
) {
  val shareLabel = stringResource(R.string.share_video)
  Box(
    modifier = Modifier.size(width = 56.dp, height = 60.dp),
    contentAlignment = Alignment.TopCenter,
  ) {
    Box(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .size(48.dp)
          .clip(CircleShape)
          .background(VynozaObsidian.copy(alpha = 0.45f))
          .clickable(role = Role.Button, onClick = onClick)
          .semantics { contentDescription = shareLabel }
          .testTag("home_share_button_$videoId"),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Rounded.Share,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.size(24.dp),
      )
    }
    Text(
      text =
        if (sharesCount > 0L) sharesCount.toString()
        else stringResource(R.string.share_label),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.align(Alignment.BottomCenter).testTag("home_share_label_$videoId"),
    )
  }
}
