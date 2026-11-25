package com.vynoza.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vynoza.app.R
import com.vynoza.app.core.model.VideoPost
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurpleBright
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaRoseAccent
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import com.vynoza.app.ui.theme.VynozaSurfaceHigh
import com.vynoza.app.ui.theme.VynozaVioletContainer

@Composable
fun VynozaWordmark(modifier: Modifier = Modifier) {
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Box(
      modifier = Modifier
        .size(12.dp)
        .clip(CircleShape)
        .background(
          Brush.linearGradient(
            colors = listOf(VynozaPurplePrimary, VynozaLavenderSoft)
          )
        )
    )
    Text(
      text = "VYNOZA",
      style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.onBackground,
    )
  }
}

@Composable
fun VynozaCreatorAvatar(
  displayName: String,
  avatarUrl: String?,
  size: Dp = 48.dp,
  modifier: Modifier = Modifier,
) {
  val initial = displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "V"
  Box(
    modifier = modifier
      .size(size)
      .clip(CircleShape)
      .background(
        Brush.linearGradient(
          colors = listOf(VynozaSurfaceHigh, VynozaPurplePrimary.copy(alpha = 0.45f))
        )
      )
      .border(1.5.dp, VynozaPurpleBright.copy(alpha = 0.55f), CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    if (!avatarUrl.isNullOrBlank()) {
      AsyncImage(
        model = avatarUrl,
        contentDescription = "$displayName avatar",
        contentScale = ContentScale.Crop,
        modifier = Modifier.matchParentSize(),
      )
    } else {
      Text(
        text = initial,
        style = MaterialTheme.typography.titleMedium,
        color = VynozaLavenderSoft,
      )
    }
  }
}

@Composable
fun VynozaEmptyStateCard(
  icon: ImageVector,
  title: String,
  subtitle: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onActionClick: (() -> Unit)? = null,
) {
  val glowColor = VynozaPurplePrimary.copy(alpha = 0.18f)
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .widthIn(max = 520.dp)
      .drawBehind {
        drawCircle(
          brush = Brush.radialGradient(
            colors = listOf(glowColor, Color.Transparent),
            center = Offset(size.width * 0.5f, size.height * 0.25f),
            radius = size.minDimension * 0.75f,
          )
        )
      },
    shape = RoundedCornerShape(24.dp),
    color = VynozaSurfaceElevated.copy(alpha = 0.92f),
    border = BorderStroke(1.dp, VynozaBorderSubtle),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
        modifier = Modifier
          .size(60.dp)
          .clip(CircleShape)
          .background(VynozaPurplePrimary.copy(alpha = 0.18f))
          .border(1.dp, VynozaPurpleBright.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = VynozaLavenderSoft,
          modifier = Modifier.size(28.dp),
        )
      }
      Spacer(modifier = Modifier.height(16.dp))
      Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      if (actionLabel != null && onActionClick != null) {
        Spacer(modifier = Modifier.height(20.dp))
        Button(
          onClick = onActionClick,
          colors = ButtonDefaults.buttonColors(
            containerColor = VynozaPurplePrimary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
          shape = RoundedCornerShape(14.dp),
        ) {
          Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
        }
      }
    }
  }
}

@Composable
fun VynozaErrorCard(
  message: String,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth().widthIn(max = 520.dp),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        textAlign = TextAlign.Center,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
      )
      OutlinedButton(
        onClick = onRetry,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
      ) {
        Icon(
          imageVector = Icons.Rounded.Refresh,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(text = stringResource(R.string.retry))
      }
    }
  }
}

@Composable
fun VynozaStatPill(
  count: Long,
  label: String,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.semantics(mergeDescendants = true) {}.padding(horizontal = 8.dp, vertical = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = count.toString(),
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
fun VynozaVideoThumbnailTile(
  video: VideoPost,
  isLiked: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  tileTag: String = "profile_video_tile_${video.id}",
  commentCountTag: String = "profile_tile_comment_count_${video.id}",
  likeCountTag: String = "profile_tile_like_count_${video.id}",
  viewsCountTag: String = "profile_tile_views_count_${video.id}",
) {
  val hasPlayback = !video.playbackUrl.isNullOrBlank()
  val thumbnailUrl = remember(video.playbackUrl) { resolveCloudinaryThumbnailUrl(video.playbackUrl) }

  Surface(
    shape = RoundedCornerShape(14.dp),
    color = VynozaSurfaceDeep,
    border = BorderStroke(1.dp, VynozaBorderSubtle),
    modifier =
      modifier
        .fillMaxWidth()
        .aspectRatio(9f / 14f)
        .clickable(enabled = hasPlayback, onClick = onClick)
        .testTag(tileTag),
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      Box(
        modifier =
          Modifier
            .align(Alignment.Center)
            .size(40.dp)
            .background(VynozaVioletContainer, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.VideoLibrary,
          contentDescription = video.caption.ifBlank { "Video thumbnail" },
          tint = VynozaLavenderSoft,
        )
      }

      if (thumbnailUrl != null) {
        AsyncImage(
          model = thumbnailUrl,
          contentDescription = video.caption.ifBlank { "Video thumbnail" },
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
      }

      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                colors =
                  listOf(
                    VynozaObsidian.copy(alpha = 0.55f),
                    Color.Transparent,
                    VynozaObsidian.copy(alpha = 0.78f),
                  )
              )
            )
      )

      Row(
        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.Comment,
          contentDescription = null,
          tint = Color.White.copy(alpha = 0.9f),
          modifier = Modifier.size(13.dp),
        )
        Text(
          text = video.commentsCount.toString(),
          style = MaterialTheme.typography.labelSmall,
          color = Color.White.copy(alpha = 0.9f),
          modifier = Modifier.testTag(commentCountTag),
        )
      }

      Row(
        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Icon(
          imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
          contentDescription = null,
          tint = if (isLiked) VynozaRoseAccent else Color.White.copy(alpha = 0.9f),
          modifier = Modifier.size(14.dp),
        )
        Text(
          text = video.likesCount.toString(),
          style = MaterialTheme.typography.labelSmall,
          color = if (isLiked) VynozaRoseAccent else Color.White.copy(alpha = 0.9f),
          modifier = Modifier.testTag(likeCountTag),
        )
      }

      Column(
        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        video.viewsCount?.let { views ->
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.PlayArrow,
              contentDescription = null,
              tint = Color.White,
              modifier = Modifier.size(14.dp),
            )
            Text(
              text = formatCompactCount(views),
              style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
              color = Color.White,
              modifier = Modifier.testTag(viewsCountTag),
            )
          }
        }
        Text(
          text = video.caption.ifBlank { video.status },
          style = MaterialTheme.typography.labelSmall,
          color = Color.White.copy(alpha = 0.9f),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

