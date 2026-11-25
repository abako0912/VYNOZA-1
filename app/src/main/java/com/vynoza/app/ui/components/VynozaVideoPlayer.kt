package com.vynoza.app.ui.components

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vynoza.app.core.model.VideoPost
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaRoseAccent
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import com.vynoza.app.ui.theme.VynozaWarning

@OptIn(UnstableApi::class)
@Composable
fun VynozaVideoPlayer(
  playbackUrl: String,
  isActive: Boolean,
  modifier: Modifier = Modifier,
  muteButtonTopPadding: Dp = 12.dp,
  resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  var isMuted by remember { mutableStateOf(false) }
  var userPaused by remember { mutableStateOf(false) }
  var isBuffering by remember { mutableStateOf(true) }
  var playbackError by remember { mutableStateOf<String?>(null) }
  var isLifecycleResumed by remember { mutableStateOf(true) }

  val exoPlayer =
    remember(playbackUrl) {
      ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        setMediaItem(MediaItem.fromUri(playbackUrl))
        prepare()
      }
    }

  DisposableEffect(exoPlayer) {
    val listener =
      object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
          isBuffering = playbackState == Player.STATE_BUFFERING
          if (playbackState == Player.STATE_READY) {
            playbackError = null
          }
        }

        override fun onPlayerError(error: PlaybackException) {
          isBuffering = false
          playbackError = error.localizedMessage ?: "Unable to play this video right now."
        }
      }
    exoPlayer.addListener(listener)
    onDispose {
      exoPlayer.removeListener(listener)
      exoPlayer.release()
    }
  }

  DisposableEffect(lifecycleOwner, exoPlayer) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> isLifecycleResumed = true
        Lifecycle.Event.ON_PAUSE,
        Lifecycle.Event.ON_STOP -> isLifecycleResumed = false
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  LaunchedEffect(exoPlayer, isActive, userPaused, isLifecycleResumed, playbackError) {
    val shouldPlay = isActive && !userPaused && isLifecycleResumed && playbackError == null
    exoPlayer.playWhenReady = shouldPlay
    if (!shouldPlay) {
      exoPlayer.pause()
    }
  }

  LaunchedEffect(exoPlayer, isMuted) {
    exoPlayer.volume = if (isMuted) 0f else 1f
  }

  Box(
    modifier =
      modifier
        .background(VynozaObsidian)
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = {
            if (playbackError == null) {
              userPaused = !userPaused
            }
          },
        ),
  ) {
    AndroidView(
      factory = { ctx ->
        PlayerView(ctx).apply {
          useController = false
          this.resizeMode = resizeMode
          layoutParams =
            ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
          player = exoPlayer
        }
      },
      update = { view ->
        if (view.player !== exoPlayer) {
          view.player = exoPlayer
        }
        if (view.resizeMode != resizeMode) {
          view.resizeMode = resizeMode
        }
      },
      modifier = Modifier.fillMaxSize(),
    )

    if (isBuffering && playbackError == null) {
      CircularProgressIndicator(
        color = VynozaPurplePrimary,
        modifier = Modifier.align(Alignment.Center).size(36.dp),
      )
    }

    if (userPaused && !isBuffering && playbackError == null) {
      Box(
        modifier =
          Modifier
            .align(Alignment.Center)
            .size(64.dp)
            .clip(CircleShape)
            .background(VynozaObsidian.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.PlayArrow,
          contentDescription = "Resume video",
          tint = Color.White,
          modifier = Modifier.size(36.dp),
        )
      }
    }

    if (playbackError != null) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = VynozaSurfaceElevated.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            text = playbackError ?: "Playback failed",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
          )
          OutlinedButton(
            onClick = {
              playbackError = null
              isBuffering = true
              exoPlayer.prepare()
              exoPlayer.playWhenReady = isActive && !userPaused
            }
          ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(6.dp))
            Text("Retry")
          }
        }
      }
    }

    IconButton(
      onClick = { isMuted = !isMuted },
      modifier =
        Modifier
          .align(Alignment.TopEnd)
          .padding(top = muteButtonTopPadding, end = 16.dp)
          .size(48.dp)
          .clip(CircleShape)
          .background(VynozaObsidian.copy(alpha = 0.55f))
          .testTag("video_mute_toggle"),
    ) {
      Icon(
        imageVector =
          if (isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
        contentDescription = if (isMuted) "Unmute video" else "Mute video",
        tint = Color.White,
      )
    }
  }
}

@OptIn(UnstableApi::class)
@Composable
fun VynozaVideoPlayerDialog(
  post: VideoPost,
  onDismiss: () -> Unit,
  isLiked: Boolean = false,
  isLikeInFlight: Boolean = false,
  onToggleLike: (() -> Unit)? = null,
  onOpenComments: (() -> Unit)? = null,
  isSaved: Boolean = false,
  isSaveInFlight: Boolean = false,
  onToggleSave: (() -> Unit)? = null,
  onShare: (() -> Unit)? = null,
  onOpenCreatorProfile: ((String) -> Unit)? = null,
) {
  val url = post.playbackUrl?.takeIf { it.isNotBlank() } ?: return
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    BoxWithConstraints(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      contentAlignment = Alignment.Center,
    ) {
      val maxVideoHeight = (maxHeight * 0.52f).coerceIn(180.dp, 460.dp)
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = VynozaSurfaceElevated,
        border = BorderStroke(1.dp, VynozaBorderSubtle),
      ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "@${post.authorUsername}",
              style = MaterialTheme.typography.titleMedium,
              color = VynozaLavenderSoft,
              modifier =
                Modifier
                  .clip(RoundedCornerShape(8.dp))
                  .clickable(enabled = onOpenCreatorProfile != null && post.authorId.isNotBlank()) {
                    onDismiss()
                    onOpenCreatorProfile?.invoke(post.authorId)
                  }
                  .padding(vertical = 4.dp, horizontal = 4.dp)
                  .testTag("player_dialog_author_${post.id}"),
            )
            IconButton(onClick = onDismiss) {
              Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close video player",
                tint = MaterialTheme.colorScheme.onSurface,
              )
            }
          }

          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .heightIn(max = maxVideoHeight)
                .aspectRatio(9f / 14f, matchHeightConstraintsFirst = true)
                .background(VynozaObsidian),
          ) {
            VynozaVideoPlayer(
              playbackUrl = url,
              isActive = true,
              muteButtonTopPadding = 12.dp,
              resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
              modifier = Modifier.fillMaxSize(),
            )
          }

        if (post.caption.isNotBlank() || onToggleLike != null || onOpenComments != null || onToggleSave != null || onShare != null) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text(
              text = post.caption.ifBlank { "Published clip" },
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (onToggleLike != null) {
                Surface(
                  onClick = onToggleLike,
                  enabled = !isLikeInFlight,
                  shape = RoundedCornerShape(16.dp),
                  color =
                    if (isLiked) VynozaRoseAccent.copy(alpha = 0.16f) else VynozaSurfaceDeep,
                  border =
                    BorderStroke(
                      1.dp,
                      if (isLiked) VynozaRoseAccent.copy(alpha = 0.65f) else VynozaBorderSubtle,
                    ),
                  modifier = Modifier.testTag("player_dialog_like_button_${post.id}"),
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    if (isLikeInFlight) {
                      CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = VynozaRoseAccent,
                      )
                    } else {
                      Icon(
                        imageVector =
                          if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (isLiked) "Unlike video" else "Like video",
                        tint = if (isLiked) VynozaRoseAccent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                      )
                    }
                    Text(
                      text = post.likesCount.toString(),
                      style = MaterialTheme.typography.labelLarge,
                      color = MaterialTheme.colorScheme.onSurface,
                      modifier = Modifier.testTag("player_dialog_like_count_${post.id}"),
                    )
                  }
                }
              }
              if (onOpenComments != null) {
                Surface(
                  onClick = onOpenComments,
                  shape = RoundedCornerShape(16.dp),
                  color = VynozaSurfaceDeep,
                  border = BorderStroke(1.dp, VynozaBorderSubtle),
                  modifier = Modifier.testTag("player_dialog_comment_button_${post.id}"),
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Icon(
                      imageVector = Icons.AutoMirrored.Rounded.Comment,
                      contentDescription = "Open comments",
                      tint = MaterialTheme.colorScheme.onSurface,
                      modifier = Modifier.size(20.dp),
                    )
                    Text(
                      text = post.commentsCount.toString(),
                      style = MaterialTheme.typography.labelLarge,
                      color = MaterialTheme.colorScheme.onSurface,
                      modifier = Modifier.testTag("player_dialog_comment_count_${post.id}"),
                    )
                  }
                }
              }
              if (onToggleSave != null) {
                Surface(
                  onClick = onToggleSave,
                  enabled = !isSaveInFlight,
                  shape = RoundedCornerShape(16.dp),
                  color =
                    if (isSaved) VynozaWarning.copy(alpha = 0.16f) else VynozaSurfaceDeep,
                  border =
                    BorderStroke(
                      1.dp,
                      if (isSaved) VynozaWarning.copy(alpha = 0.65f) else VynozaBorderSubtle,
                    ),
                  modifier = Modifier.testTag("player_dialog_save_button_${post.id}"),
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    if (isSaveInFlight) {
                      CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = VynozaWarning,
                      )
                    } else {
                      Icon(
                        imageVector =
                          if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = if (isSaved) "Remove saved video" else "Save video",
                        tint = if (isSaved) VynozaWarning else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                      )
                    }
                    Text(
                      text = if (isSaved) "Saved" else "Save",
                      style = MaterialTheme.typography.labelLarge,
                      color = MaterialTheme.colorScheme.onSurface,
                    )
                  }
                }
              }
              if (onShare != null) {
                Surface(
                  onClick = onShare,
                  shape = RoundedCornerShape(16.dp),
                  color = VynozaSurfaceDeep,
                  border = BorderStroke(1.dp, VynozaBorderSubtle),
                  modifier = Modifier.testTag("player_dialog_share_button_${post.id}"),
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Icon(
                      imageVector = Icons.Rounded.Share,
                      contentDescription = "Share video",
                      tint = MaterialTheme.colorScheme.onSurface,
                      modifier = Modifier.size(20.dp),
                    )
                    Text(
                      text = "Share",
                      style = MaterialTheme.typography.labelLarge,
                      color = MaterialTheme.colorScheme.onSurface,
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
}
