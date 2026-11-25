package com.vynoza.app.ui.templates

import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vynoza.app.core.templates.TemplateParameters
import com.vynoza.app.core.templates.VynozaTemplateId
import com.vynoza.app.data.templates.TemplateVideoEffects
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaSurfaceElevated

@OptIn(UnstableApi::class)
@Composable
fun TemplateVideoPreviewPlayer(
  videoUri: Uri,
  templateId: VynozaTemplateId,
  params: TemplateParameters,
  showOriginal: Boolean,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var isPaused by remember { mutableStateOf(false) }
  var isBuffering by remember { mutableStateOf(true) }
  var errorText by remember { mutableStateOf<String?>(null) }

  val exoPlayer =
    remember(videoUri) {
      ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        volume = 1f
        setMediaItem(MediaItem.fromUri(videoUri))
      }
    }

  LaunchedEffect(exoPlayer, templateId, params, showOriginal) {
    try {
      val effects =
        if (showOriginal) emptyList()
        else TemplateVideoEffects.buildVideoEffects(templateId, params, isPhotoMotionExport = false)
      exoPlayer.setVideoEffects(effects)
      exoPlayer.prepare()
      exoPlayer.playWhenReady = !isPaused
      errorText = null
    } catch (e: Exception) {
      errorText = e.localizedMessage ?: "Live GL effect preview is unavailable on this device."
    }
  }

  LaunchedEffect(exoPlayer, isPaused) {
    exoPlayer.playWhenReady = !isPaused
  }

  DisposableEffect(exoPlayer) {
    val listener =
      object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
          isBuffering = playbackState == Player.STATE_BUFFERING
          if (playbackState == Player.STATE_READY) {
            errorText = null
          }
        }

        override fun onPlayerError(error: PlaybackException) {
          isBuffering = false
          errorText = error.localizedMessage ?: "Unable to preview this video with GL effects."
        }
      }
    exoPlayer.addListener(listener)
    onDispose {
      exoPlayer.removeListener(listener)
      exoPlayer.release()
    }
  }

  Box(
    modifier =
      modifier
        .background(VynozaObsidian)
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = { isPaused = !isPaused },
        )
        .testTag("templates_video_preview"),
  ) {
    AndroidView(
      factory = { ctx ->
        PlayerView(ctx).apply {
          useController = false
          resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
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
      },
      modifier = Modifier.fillMaxSize(),
    )

    if (isBuffering && errorText == null) {
      CircularProgressIndicator(
        color = VynozaPurplePrimary,
        modifier = Modifier.align(Alignment.Center).size(36.dp),
      )
    }

    if (isPaused && !isBuffering && errorText == null) {
      Box(
        modifier =
          Modifier
            .align(Alignment.Center)
            .size(60.dp)
            .clip(CircleShape)
            .background(VynozaObsidian.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.PlayArrow,
          contentDescription = "Resume video preview",
          tint = Color.White,
          modifier = Modifier.size(34.dp),
        )
      }
    }

    val currentError = errorText
    if (currentError != null) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = VynozaSurfaceElevated.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        modifier = Modifier.align(Alignment.Center).padding(20.dp),
      ) {
        Column(
          modifier = Modifier.padding(14.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = currentError,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
          )
        }
      }
    }
  }
}
