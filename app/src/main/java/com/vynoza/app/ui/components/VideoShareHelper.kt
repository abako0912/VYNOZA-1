package com.vynoza.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.vynoza.app.R
import com.vynoza.app.core.model.UserPublicProfile
import com.vynoza.app.core.model.VideoPost

fun resolveShareableVideoUrl(post: VideoPost): String? {
  val directUrl =
    post.playbackUrl
      ?.trim()
      ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
  if (directUrl != null) return directUrl
  val cleanId = post.id.trim()
  return if (cleanId.isNotEmpty()) "vynoza://video/$cleanId" else null
}

fun buildVideoShareText(post: VideoPost): String? {
  val shareUrl = resolveShareableVideoUrl(post) ?: return null
  val handle = post.authorUsername.trim().ifBlank { "vynoza" }
  val cleanCaption = post.caption.trim()
  return buildString {
    append("Watch @$handle's video on VYNOZA")
    if (cleanCaption.isNotEmpty()) {
      append(": \"$cleanCaption\"")
    }
    append("\n")
    append(shareUrl)
  }
}

fun buildVideoShareSendIntent(post: VideoPost): Intent? {
  val shareText = buildVideoShareText(post) ?: return null
  val handle = post.authorUsername.trim().ifBlank { "vynoza" }
  return Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_SUBJECT, "@$handle on VYNOZA")
    putExtra(Intent.EXTRA_TEXT, shareText)
  }
}

fun launchVideoShareSheet(context: Context, post: VideoPost): Boolean {
  val sendIntent = buildVideoShareSendIntent(post) ?: return false
  val chooser =
    Intent.createChooser(sendIntent, context.getString(R.string.share_video_chooser_title)).apply {
      if (context !is Activity) {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    }
  return runCatching {
    context.startActivity(chooser)
  }.isSuccess
}

fun resolveShareableProfileUrl(profile: UserPublicProfile): String? {
  val cleanId = profile.userId.trim()
  return if (cleanId.isNotEmpty()) "vynoza://user/$cleanId" else null
}

fun buildProfileShareText(profile: UserPublicProfile): String? {
  val shareUrl = resolveShareableProfileUrl(profile) ?: return null
  val handle = profile.username.trim().ifBlank { "vynoza" }
  val displayName = profile.displayName.trim().ifBlank { "@$handle" }
  return "Check out $displayName (@$handle) on VYNOZA\n$shareUrl"
}

fun buildProfileShareSendIntent(profile: UserPublicProfile): Intent? {
  val shareText = buildProfileShareText(profile) ?: return null
  val handle = profile.username.trim().ifBlank { "vynoza" }
  return Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_SUBJECT, "@$handle on VYNOZA")
    putExtra(Intent.EXTRA_TEXT, shareText)
  }
}

fun launchProfileShareSheet(context: Context, profile: UserPublicProfile): Boolean {
  val sendIntent = buildProfileShareSendIntent(profile) ?: return false
  val chooser =
    Intent.createChooser(sendIntent, context.getString(R.string.share_profile_chooser_title)).apply {
      if (context !is Activity) {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    }
  return runCatching {
    context.startActivity(chooser)
  }.isSuccess
}

fun resolveCloudinaryThumbnailUrl(playbackUrl: String?): String? {
  val cleanUrl =
    playbackUrl
      ?.trim()
      ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
      ?: return null
  val marker = "/video/upload/"
  val markerIdx = cleanUrl.indexOf(marker)
  if (markerIdx < 0) return null

  val queryStart = cleanUrl.indexOfAny(charArrayOf('?', '#'))
  val basePath = if (queryStart >= 0) cleanUrl.substring(0, queryStart) else cleanUrl
  val prefix = basePath.substring(0, markerIdx + marker.length)
  val suffix = basePath.substring(markerIdx + marker.length)
  if (suffix.isBlank()) return null

  val transformedSuffix =
    if (suffix.startsWith("so_") || suffix.startsWith("w_")) suffix
    else "so_0,w_480,c_fill,q_auto/$suffix"

  val lower = transformedSuffix.lowercase()
  val imagePath =
    when {
      lower.endsWith(".mp4") -> transformedSuffix.dropLast(4) + ".jpg"
      lower.endsWith(".mov") -> transformedSuffix.dropLast(4) + ".jpg"
      lower.endsWith(".m4v") -> transformedSuffix.dropLast(4) + ".jpg"
      lower.endsWith(".3gp") -> transformedSuffix.dropLast(4) + ".jpg"
      lower.endsWith(".webm") -> transformedSuffix.dropLast(5) + ".jpg"
      lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") -> transformedSuffix
      else -> "$transformedSuffix.jpg"
    }
  return "$prefix$imagePath"
}

fun formatCompactCount(count: Long): String {
  val safe = count.coerceAtLeast(0L)
  return when {
    safe < 1_000L -> safe.toString()
    safe < 1_000_000L -> {
      val tenths = (safe * 10L) / 1_000L
      if (tenths % 10L == 0L) "${tenths / 10L}K" else "${tenths / 10L}.${tenths % 10L}K"
    }
    else -> {
      val tenths = (safe * 10L) / 1_000_000L
      if (tenths % 10L == 0L) "${tenths / 10L}M" else "${tenths / 10L}.${tenths % 10L}M"
    }
  }
}

