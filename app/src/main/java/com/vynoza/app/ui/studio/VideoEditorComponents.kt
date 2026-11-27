package com.vynoza.app.ui.studio

import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.vynoza.app.R
import com.vynoza.app.core.studio.CreateStudioDraft
import com.vynoza.app.core.studio.DraftPrivacy
import com.vynoza.app.core.studio.EditorMediaClip
import com.vynoza.app.core.studio.EditorMediaKind
import com.vynoza.app.core.studio.StudioEffectCatalog
import com.vynoza.app.data.studio.StudioMediaProcessor
import com.vynoza.app.ui.theme.VynozaWarning
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaRoseAccent
import com.vynoza.app.ui.theme.VynozaSuccess
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import com.vynoza.app.ui.theme.VynozaSurfaceHigh
import com.vynoza.app.ui.theme.VynozaTextMuted
import com.vynoza.app.ui.theme.VynozaTextSecondary
import com.vynoza.app.ui.theme.VynozaVioletContainer
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun VideoEditorViewport(
  uiState: VideoEditorUiState,
  onTogglePlayPause: () -> Unit,
  onToggleExportedPreview: (Boolean) -> Unit,
  onPreviewProgressTick: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val activeClip = uiState.selectedClip
  val exported = uiState.exportState as? VideoEditorExportState.Exported
  val showExported = uiState.isPreviewingExported && exported != null
  val activeUriString = if (showExported) exported.uriString else activeClip?.uriString
  val isVideoSource = showExported || activeClip?.kind == EditorMediaKind.VIDEO
  var playerError by remember(activeUriString) { mutableStateOf<String?>(null) }
  var totalClipPlayerMs by remember(activeUriString) { mutableLongStateOf(activeClip?.effectiveDurationMs ?: 1_000L) }

  val clipStartOffsetMs =
    remember(uiState.clips, uiState.selectedClipIndex, showExported) {
      if (showExported) {
        0L
      } else {
        uiState.clips.take(uiState.selectedClipIndex).sumOf { it.effectiveDurationMs }
      }
    }

  val exoPlayer =
    remember(activeUriString, isVideoSource) {
      if (isVideoSource && !activeUriString.isNullOrBlank()) {
        runCatching {
          ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(Uri.parse(activeUriString)))
            prepare()
          }
        }.getOrNull()
      } else {
        null
      }
    }

  LaunchedEffect(exoPlayer, uiState.isPreviewPlaying, showExported, uiState.audioMix.originalAudioVolume) {
    val player = exoPlayer ?: return@LaunchedEffect
    player.volume = if (showExported) 1f else uiState.audioMix.originalAudioVolume.coerceIn(0f, 1f)
    player.playWhenReady = uiState.isPreviewPlaying
  }

  LaunchedEffect(exoPlayer, activeClip?.trimStartMs, activeClip?.trimEndMs, showExported, uiState.isPreviewPlaying) {
    val player = exoPlayer ?: return@LaunchedEffect
    if (!showExported && activeClip != null && activeClip.kind == EditorMediaKind.VIDEO) {
      val pos = player.currentPosition
      if (pos < activeClip.trimStartMs || pos >= activeClip.trimEndMs) {
        player.seekTo(activeClip.trimStartMs)
      }
    }
    while (true) {
      if (uiState.isPreviewPlaying) {
        if (!showExported && activeClip != null && activeClip.kind == EditorMediaKind.VIDEO) {
          val rawPos = player.currentPosition
          if (rawPos >= activeClip.trimEndMs || rawPos < activeClip.trimStartMs) {
            player.seekTo(activeClip.trimStartMs)
            onPreviewProgressTick(clipStartOffsetMs)
          } else {
            onPreviewProgressTick(clipStartOffsetMs + (rawPos - activeClip.trimStartMs))
          }
          totalClipPlayerMs = activeClip.effectiveDurationMs
        } else {
          val dur = player.duration.takeIf { it > 0L } ?: uiState.totalDurationMs.coerceAtLeast(500L)
          totalClipPlayerMs = dur
          onPreviewProgressTick(player.currentPosition.coerceAtLeast(0L))
        }
      }
      delay(160L)
    }
  }

  LaunchedEffect(isVideoSource, activeClip?.id, uiState.isPreviewPlaying, showExported) {
    if (!isVideoSource && activeClip != null && !showExported) {
      val clipDur = activeClip.effectiveDurationMs
      totalClipPlayerMs = clipDur
      var localMs = 0L
      while (true) {
        if (uiState.isPreviewPlaying) {
          onPreviewProgressTick(clipStartOffsetMs + localMs)
          delay(200L)
          localMs = (localMs + 200L) % clipDur.coerceAtLeast(1_000L)
        } else {
          delay(200L)
        }
      }
    }
  }

  DisposableEffect(exoPlayer) {
    val listener =
      object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
          playerError = error.localizedMessage ?: "Unable to preview video clip."
        }
      }
    exoPlayer?.addListener(listener)
    onDispose {
      exoPlayer?.removeListener(listener)
      exoPlayer?.release()
    }
  }

  val effectMatrix =
    remember(uiState.effectPresetId, showExported) {
      if (showExported) null else StudioMediaProcessor.colorMatrixForEffect(uiState.effectPresetId)
    }

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(204.dp)
          .clip(RoundedCornerShape(20.dp))
          .background(VynozaSurfaceDeep)
          .clickable { onTogglePlayPause() }
          .testTag("editor_preview_viewport"),
      contentAlignment = Alignment.Center,
    ) {
      if (isVideoSource && exoPlayer != null && playerError == null) {
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
            if (view.player !== exoPlayer) view.player = exoPlayer
          },
          modifier = Modifier.fillMaxSize().testTag("editor_video_player"),
        )
      } else if (activeClip != null) {
        AsyncImage(
          model = Uri.parse(activeClip.uriString),
          contentDescription = activeClip.displayName,
          contentScale = ContentScale.Fit,
          colorFilter = effectMatrix?.let { ColorFilter.colorMatrix(ColorMatrix(it.array)) },
          modifier = Modifier.fillMaxSize().testTag("editor_image_preview"),
        )
      }

      if (playerError != null) {
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = VynozaObsidian.copy(alpha = 0.85f),
          modifier = Modifier.align(Alignment.Center).padding(16.dp),
        ) {
          Text(
            text = playerError.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = VynozaRoseAccent,
            modifier = Modifier.padding(10.dp),
          )
        }
      }

      // Top Left: Preview Source Badge / Toggle
      Row(
        modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(99.dp),
          color = VynozaObsidian.copy(alpha = 0.72f),
        ) {
          Text(
            text = if (showExported) "Exported MP4" else StudioEffectCatalog.findById(uiState.effectPresetId).name,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (showExported) VynozaSuccess else VynozaLavenderSoft,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
        if (exported != null) {
          Surface(
            onClick = { onToggleExportedPreview(!showExported) },
            shape = RoundedCornerShape(99.dp),
            color = VynozaVioletContainer,
            modifier = Modifier.testTag("editor_preview_mode_toggle"),
          ) {
            Text(
              text = if (showExported) "Edit Timeline" else "Preview MP4",
              style = MaterialTheme.typography.labelSmall,
              color = Color.White,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }
        }
      }

      // Bottom Left: Time badge
      Surface(
        shape = RoundedCornerShape(99.dp),
        color = VynozaObsidian.copy(alpha = 0.72f),
        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
      ) {
        Text(
          text = "${formatEditorMs(uiState.previewPositionMs)} / ${formatEditorMs(uiState.totalDurationMs)}",
          style = MaterialTheme.typography.labelSmall,
          color = Color.White,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
      }

      // Bottom Right: Play/Pause button (48dp touch target)
      Surface(
        onClick = onTogglePlayPause,
        shape = CircleShape,
        color = VynozaObsidian.copy(alpha = 0.74f),
        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(48.dp).testTag("editor_play_toggle"),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = if (uiState.isPreviewPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (uiState.isPreviewPlaying) "Pause preview" else "Play preview",
            tint = Color.White,
          )
        }
      }
    }

    // Interactive Seek Bar for Active Video Clip or Exported MP4
    if (isVideoSource && exoPlayer != null) {
      val minSeek = if (showExported || activeClip == null) 0f else activeClip.trimStartMs.toFloat()
      val maxSeek =
        if (showExported || activeClip == null) {
          totalClipPlayerMs.coerceAtLeast(500L).toFloat()
        } else {
          activeClip.trimEndMs.coerceAtLeast(activeClip.trimStartMs + 500L).toFloat()
        }
      val currentSeek =
        (if (showExported) uiState.previewPositionMs else (activeClip?.trimStartMs ?: 0L) + (uiState.previewPositionMs - clipStartOffsetMs).coerceAtLeast(0L))
          .toFloat()
          .coerceIn(minSeek, maxSeek)

      Slider(
        value = currentSeek,
        onValueChange = { pos ->
          exoPlayer.seekTo(pos.toLong())
          onPreviewProgressTick(
            if (showExported) pos.toLong()
            else clipStartOffsetMs + (pos.toLong() - (activeClip?.trimStartMs ?: 0L)).coerceAtLeast(0L)
          )
        },
        valueRange = minSeek..maxSeek,
        colors =
          SliderDefaults.colors(
            thumbColor = VynozaLavenderSoft,
            activeTrackColor = VynozaPurplePrimary,
            inactiveTrackColor = VynozaSurfaceHigh,
          ),
        modifier = Modifier.fillMaxWidth().testTag("editor_seek_slider"),
      )
    }
  }
}

@Composable
fun VideoEditorClipAndTrimSection(
  uiState: VideoEditorUiState,
  onSelectClip: (Int) -> Unit,
  onAddGalleryMedia: () -> Unit,
  onRemoveClip: (String) -> Unit,
  onUpdateClipTrim: (String, Long, Long) -> Unit,
  onSelectEffect: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Timeline Strip
    Surface(
      shape = RoundedCornerShape(18.dp),
      color = VynozaSurfaceElevated,
      border = BorderStroke(1.dp, VynozaBorderSubtle),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = "Timeline (${uiState.clips.size} clips • ${formatEditorMs(uiState.totalDurationMs)})",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
          )
          OutlinedButton(
            onClick = onAddGalleryMedia,
            enabled = !uiState.isAddingMedia,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.height(36.dp).testTag("editor_add_media_button"),
          ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add Clip", style = MaterialTheme.typography.labelMedium)
          }
        }

        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth().testTag("editor_clip_strip"),
        ) {
          itemsIndexed(uiState.clips, key = { _, clip -> clip.id }) { idx, clip ->
            val selected = idx == uiState.selectedClipIndex
            val isMissing = clip.id in uiState.missingClipIds
            Surface(
              onClick = { onSelectClip(idx) },
              shape = RoundedCornerShape(12.dp),
              color =
                when {
                  isMissing -> VynozaRoseAccent.copy(alpha = 0.20f)
                  selected -> VynozaVioletContainer
                  else -> VynozaSurfaceDeep
                },
              border =
                BorderStroke(
                  1.dp,
                  when {
                    isMissing -> VynozaRoseAccent
                    selected -> VynozaPurplePrimary
                    else -> VynozaBorderSubtle
                  },
                ),
              modifier = Modifier.testTag("editor_clip_chip_$idx"),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Text(
                  text = "${idx + 1}. ${clip.displayName.take(12)} (${formatEditorMs(clip.effectiveDurationMs)})",
                  style = MaterialTheme.typography.labelMedium,
                  color = Color.White,
                )
                if (uiState.clips.size > 1 || isMissing) {
                  Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove ${clip.displayName}",
                    tint = VynozaTextSecondary,
                    modifier =
                      Modifier
                        .size(16.dp)
                        .clickable { onRemoveClip(clip.id) },
                  )
                }
              }
            }
          }
        }

        // Trim Active Clip
        val active = uiState.selectedClip
        if (active != null) {
          if (active.kind == EditorMediaKind.VIDEO) {
            val totalClipMs = active.durationMs.coerceAtLeast(1_000L).toFloat()
            val startF = active.trimStartMs.toFloat().coerceIn(0f, totalClipMs - 500f)
            val endF = active.trimEndMs.toFloat().coerceIn(startF + 500f, totalClipMs)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = "Trim Start: ${formatEditorMs(active.trimStartMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = VynozaLavenderSoft,
              )
              Text(
                text = "Duration: ${formatEditorMs(active.effectiveDurationMs)}",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
              )
              Text(
                text = "Trim End: ${formatEditorMs(active.trimEndMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = VynozaLavenderSoft,
              )
            }
            RangeSlider(
              value = startF..endF,
              onValueChange = { range ->
                val s = range.start.toLong()
                val e = maxOf(s + 500L, range.endInclusive.toLong())
                onUpdateClipTrim(active.id, s, e)
              },
              valueRange = 0f..totalClipMs,
              colors =
                SliderDefaults.colors(
                  thumbColor = Color.White,
                  activeTrackColor = VynozaPurplePrimary,
                  inactiveTrackColor = VynozaSurfaceDeep,
                ),
              modifier = Modifier.fillMaxWidth().testTag("editor_trim_range_slider"),
            )
          } else {
            Text(
              text = "Photo Clip Duration: ${formatEditorMs(active.effectiveDurationMs)}",
              style = MaterialTheme.typography.labelSmall,
              color = VynozaLavenderSoft,
            )
            Slider(
              value = active.effectiveDurationMs.toFloat().coerceIn(1_000f, 15_000f),
              onValueChange = { dur -> onUpdateClipTrim(active.id, 0L, dur.toLong()) },
              valueRange = 1_000f..15_000f,
              modifier = Modifier.fillMaxWidth().testTag("editor_photo_duration_slider"),
            )
          }
        }
      }
    }

    // Visual Filter / Effect Strip
    Surface(
      shape = RoundedCornerShape(18.dp),
      color = VynozaSurfaceElevated,
      border = BorderStroke(1.dp, VynozaBorderSubtle),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = VynozaLavenderSoft, modifier = Modifier.size(18.dp))
          Text(
            text = "Color Grade & Effect",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
          )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items(StudioEffectCatalog.presets, key = { it.id }) { preset ->
            val selected = preset.id == uiState.effectPresetId
            FilterChip(
              selected = selected,
              onClick = { onSelectEffect(preset.id) },
              label = { Text(preset.name) },
              colors =
                FilterChipDefaults.filterChipColors(
                  selectedContainerColor = VynozaVioletContainer,
                  selectedLabelColor = VynozaLavenderSoft,
                  containerColor = VynozaSurfaceDeep,
                  labelColor = VynozaTextSecondary,
                ),
              modifier = Modifier.testTag("editor_effect_${preset.id}"),
            )
          }
        }
      }
    }
  }
}

@Composable
fun VideoEditorAudioMixSection(
  uiState: VideoEditorUiState,
  onOpenMusicPicker: () -> Unit,
  onRemoveMusic: () -> Unit,
  onOriginalVolumeChange: (Float) -> Unit,
  onMusicVolumeChange: (Float) -> Unit,
  onSyncOffsetChange: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = VynozaSurfaceElevated,
    border = BorderStroke(1.dp, VynozaBorderSubtle),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = VynozaLavenderSoft, modifier = Modifier.size(20.dp))
          Text(
            text = "Audio & Music Mix",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (uiState.selectedMusic != null) {
            TextButton(
              onClick = onRemoveMusic,
              modifier = Modifier.testTag("editor_remove_music_button"),
            ) {
              Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text(stringResource(R.string.video_editor_remove_music))
            }
          }
          OutlinedButton(
            onClick = onOpenMusicPicker,
            border = BorderStroke(1.dp, VynozaPurplePrimary),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.height(40.dp).testTag("editor_music_picker_button"),
          ) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text =
                if (uiState.selectedMusic == null) stringResource(R.string.video_editor_add_music)
                else stringResource(R.string.video_editor_change_music),
              style = MaterialTheme.typography.labelMedium,
            )
          }
        }
      }

      // Selected Music Card + Waveform + License Guardrail
      val music = uiState.selectedMusic
      if (music != null) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = VynozaSurfaceDeep,
          border =
            BorderStroke(
              1.dp,
              if (uiState.musicLicenseDecision.canEmbed) VynozaPurplePrimary.copy(alpha = 0.5f)
              else VynozaWarning.copy(alpha = 0.7f),
            ),
          modifier = Modifier.fillMaxWidth().testTag("editor_selected_music_card"),
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(VynozaSurfaceHigh),
                contentAlignment = Alignment.Center,
              ) {
                if (!music.track.artworkUrl.isNullOrBlank()) {
                  AsyncImage(
                    model = music.track.artworkUrl,
                    contentDescription = music.track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                  )
                } else {
                  Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = VynozaLavenderSoft)
                }
              }
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = music.track.title,
                  style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                  color = Color.White,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = "${music.track.artistName} (@${music.track.artistHandle}) • ${formatEditorMs(music.clampedStartMs)}–${formatEditorMs(music.endMs)}",
                  style = MaterialTheme.typography.bodySmall,
                  color = VynozaTextSecondary,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }

            // Mini Waveform Bar Strip
            if (music.waveformAmplitudes.isNotEmpty()) {
              Canvas(
                modifier = Modifier.fillMaxWidth().height(26.dp).testTag("editor_music_mini_waveform"),
              ) {
                val bars = music.waveformAmplitudes.take(48)
                val step = size.width / bars.size.coerceAtLeast(1)
                val barW = (step * 0.6f).coerceAtLeast(2f)
                bars.forEachIndexed { idx, amp ->
                  val h = (amp.coerceIn(0.15f, 1f) * size.height)
                  val top = (size.height - h) / 2f
                  drawRoundRect(
                    color = VynozaLavenderSoft.copy(alpha = 0.85f),
                    topLeft = Offset(idx * step, top),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(4f, 4f),
                  )
                }
              }
            }

            // License & Attribution Banner
            val decision = uiState.musicLicenseDecision
            Surface(
              shape = RoundedCornerShape(10.dp),
              color =
                if (decision.canEmbed) VynozaSuccess.copy(alpha = 0.14f)
                else VynozaWarning.copy(alpha = 0.16f),
              border =
                BorderStroke(
                  1.dp,
                  if (decision.canEmbed) VynozaSuccess.copy(alpha = 0.45f)
                  else VynozaWarning.copy(alpha = 0.55f),
                ),
              modifier = Modifier.fillMaxWidth().testTag("editor_license_banner"),
            ) {
              Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Icon(
                    imageVector = if (decision.canEmbed) Icons.Rounded.VerifiedUser else Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = if (decision.canEmbed) VynozaSuccess else VynozaWarning,
                    modifier = Modifier.size(16.dp),
                  )
                  Text(
                    text = decision.badgeLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (decision.canEmbed) VynozaSuccess else VynozaWarning,
                  )
                }
                Text(
                  text = decision.explanation,
                  style = MaterialTheme.typography.bodySmall,
                  color = Color.White.copy(alpha = 0.90f),
                )
                if (decision.canEmbed && decision.attributionText.isNotBlank()) {
                  Text(
                    text = decision.attributionText,
                    style = MaterialTheme.typography.labelSmall,
                    color = VynozaLavenderSoft,
                  )
                } else if (!decision.canEmbed) {
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                      onClick = { onMusicVolumeChange(0f) },
                      modifier = Modifier.testTag("editor_mute_blocked_music_button"),
                    ) {
                      Text("Mute Music (0%)", color = VynozaLavenderSoft)
                    }
                    TextButton(
                      onClick = onRemoveMusic,
                      modifier = Modifier.testTag("editor_remove_blocked_music_button"),
                    ) {
                      Text("Remove Sound", color = VynozaRoseAccent)
                    }
                  }
                }
              }
            }
          }
        }
      }

      // Original Audio Volume Slider
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = VynozaTextSecondary, modifier = Modifier.size(16.dp))
            Text(
              text = stringResource(R.string.video_editor_original_volume),
              style = MaterialTheme.typography.bodySmall,
              color = Color.White,
            )
          }
          Text(
            text = "${(uiState.audioMix.originalAudioVolume * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = VynozaLavenderSoft,
          )
        }
        Slider(
          value = uiState.audioMix.originalAudioVolume,
          onValueChange = onOriginalVolumeChange,
          valueRange = 0f..1f,
          modifier = Modifier.fillMaxWidth().testTag("editor_original_volume_slider"),
        )
      }

      // Background Music Volume & Sync Delay Sliders
      if (music != null) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = stringResource(R.string.video_editor_music_volume),
              style = MaterialTheme.typography.bodySmall,
              color = Color.White,
            )
            Text(
              text = "${(uiState.audioMix.musicVolume * 100).toInt()}%",
              style = MaterialTheme.typography.labelMedium,
              color = VynozaLavenderSoft,
            )
          }
          Slider(
            value = uiState.audioMix.musicVolume,
            onValueChange = onMusicVolumeChange,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth().testTag("editor_music_volume_slider"),
          )
        }

        val maxSyncMs = uiState.totalDurationMs.coerceIn(1_000L, 60_000L)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = stringResource(R.string.video_editor_sync_offset),
              style = MaterialTheme.typography.bodySmall,
              color = Color.White,
            )
            Text(
              text = "+${formatEditorMs(uiState.audioMix.syncOffsetMs)}",
              style = MaterialTheme.typography.labelMedium,
              color = VynozaLavenderSoft,
            )
          }
          Slider(
            value = uiState.audioMix.syncOffsetMs.toFloat().coerceIn(0f, maxSyncMs.toFloat()),
            onValueChange = { onSyncOffsetChange(it.toLong()) },
            valueRange = 0f..maxSyncMs.toFloat(),
            modifier = Modifier.fillMaxWidth().testTag("editor_sync_offset_slider"),
          )
        }
      }
    }
  }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoEditorCoverAndPublishSection(
  uiState: VideoEditorUiState,
  isBusy: Boolean,
  onUpdateCoverTimestamp: (Long) -> Unit,
  onCaptureCover: () -> Unit,
  onUpdateCaption: (String) -> Unit,
  onAddHashtag: (String) -> Unit,
  onRemoveHashtag: (String) -> Unit,
  onAddMention: (String) -> Unit,
  onRemoveMention: (String) -> Unit,
  onSelectPrivacy: (DraftPrivacy) -> Unit,
  modifier: Modifier = Modifier,
) {
  var tagDraft by remember { mutableStateOf("") }
  var mentionDraft by remember { mutableStateOf("") }

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Cover Frame Picker
    Surface(
      shape = RoundedCornerShape(18.dp),
      color = VynozaSurfaceElevated,
      border = BorderStroke(1.dp, VynozaBorderSubtle),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = stringResource(R.string.video_editor_cover_section),
          style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
          color = Color.White,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Box(
            modifier =
              Modifier
                .size(width = 64.dp, height = 88.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(VynozaSurfaceDeep)
                .testTag("editor_cover_preview"),
            contentAlignment = Alignment.Center,
          ) {
            if (!uiState.coverFrameUriString.isNullOrBlank()) {
              AsyncImage(
                model = Uri.parse(uiState.coverFrameUriString),
                contentDescription = "Selected video cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
              )
            } else {
              Icon(Icons.Rounded.Image, contentDescription = null, tint = VynozaTextMuted)
            }
          }
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              text = "Frame time: ${formatEditorMs(uiState.coverTimestampMs)} / ${formatEditorMs(uiState.totalDurationMs)}",
              style = MaterialTheme.typography.bodySmall,
              color = VynozaTextSecondary,
            )
            Slider(
              value = uiState.coverTimestampMs.toFloat().coerceIn(0f, uiState.totalDurationMs.coerceAtLeast(500L).toFloat()),
              onValueChange = { onUpdateCoverTimestamp(it.toLong()) },
              valueRange = 0f..uiState.totalDurationMs.coerceAtLeast(500L).toFloat(),
              enabled = !isBusy,
              modifier = Modifier.fillMaxWidth().testTag("editor_cover_timestamp_slider"),
            )
            OutlinedButton(
              onClick = onCaptureCover,
              enabled = !isBusy && !uiState.isExtractingCover && uiState.clips.isNotEmpty(),
              border = BorderStroke(1.dp, VynozaPurplePrimary),
              modifier = Modifier.height(42.dp).testTag("editor_capture_cover_button"),
            ) {
              if (uiState.isExtractingCover) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
              } else {
                Text(stringResource(R.string.video_editor_capture_cover))
              }
            }
          }
        }
      }
    }

    // Caption, Hashtags, Mentions & Privacy
    Surface(
      shape = RoundedCornerShape(18.dp),
      color = VynozaSurfaceElevated,
      border = BorderStroke(1.dp, VynozaBorderSubtle),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        OutlinedTextField(
          value = uiState.caption,
          onValueChange = onUpdateCaption,
          enabled = !isBusy,
          label = { Text("Caption") },
          placeholder = { Text(stringResource(R.string.create_caption_hint)) },
          supportingText = {
            Text(
              text = "Final post caption: ${uiState.composedCaption.length}/${CreateStudioDraft.MAX_FIRESTORE_CAPTION_LENGTH}",
              color =
                if (uiState.composedCaption.length >= CreateStudioDraft.MAX_FIRESTORE_CAPTION_LENGTH) {
                  VynozaWarning
                } else {
                  VynozaTextSecondary
                },
            )
          },
          maxLines = 4,
          shape = RoundedCornerShape(14.dp),
          colors =
            OutlinedTextFieldDefaults.colors(
              focusedContainerColor = VynozaSurfaceDeep,
              unfocusedContainerColor = VynozaSurfaceDeep,
              focusedBorderColor = VynozaPurplePrimary,
              unfocusedBorderColor = VynozaBorderSubtle,
            ),
          modifier = Modifier.fillMaxWidth().testTag("editor_caption_input"),
        )

        // Hashtags
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
            value = tagDraft,
            onValueChange = { tagDraft = it },
            enabled = !isBusy,
            label = { Text("Add #hashtag") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).testTag("editor_hashtag_input"),
          )
          Button(
            onClick = {
              onAddHashtag(tagDraft)
              tagDraft = ""
            },
            enabled = !isBusy && tagDraft.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = VynozaVioletContainer),
            modifier = Modifier.height(48.dp).testTag("editor_add_hashtag_button"),
          ) {
            Text("Add #")
          }
        }
        if (uiState.hashtags.isNotEmpty()) {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            uiState.hashtags.forEach { tag ->
              InputChip(
                selected = true,
                onClick = { if (!isBusy) onRemoveHashtag(tag) },
                label = { Text(tag) },
                trailingIcon = {
                  Icon(Icons.Rounded.Close, contentDescription = "Remove $tag", modifier = Modifier.size(16.dp))
                },
              )
            }
          }
        }

        // Mentions
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
            value = mentionDraft,
            onValueChange = { mentionDraft = it },
            enabled = !isBusy,
            label = { Text("Add @mention") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).testTag("editor_mention_input"),
          )
          Button(
            onClick = {
              onAddMention(mentionDraft)
              mentionDraft = ""
            },
            enabled = !isBusy && mentionDraft.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = VynozaVioletContainer),
            modifier = Modifier.height(48.dp).testTag("editor_add_mention_button"),
          ) {
            Text("Add @")
          }
        }
        if (uiState.mentions.isNotEmpty()) {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            uiState.mentions.forEach { mention ->
              InputChip(
                selected = true,
                onClick = { if (!isBusy) onRemoveMention(mention) },
                label = { Text(mention) },
                trailingIcon = {
                  Icon(Icons.Rounded.Close, contentDescription = "Remove $mention", modifier = Modifier.size(16.dp))
                },
              )
            }
          }
        }

        // Privacy Selector
        Text(
          text = "Visibility",
          style = MaterialTheme.typography.labelLarge,
          color = Color.White,
        )
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          DraftPrivacy.entries.forEach { option ->
            val selected = option == uiState.privacy
            FilterChip(
              selected = selected,
              enabled = !isBusy,
              onClick = { onSelectPrivacy(option) },
              leadingIcon = {
                Icon(
                  imageVector = if (option == DraftPrivacy.PUBLIC) Icons.Rounded.Public else Icons.Rounded.Lock,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
              },
              label = { Text(option.label) },
              colors =
                FilterChipDefaults.filterChipColors(
                  selectedContainerColor = VynozaVioletContainer,
                  selectedLabelColor = VynozaLavenderSoft,
                  containerColor = VynozaSurfaceDeep,
                  labelColor = VynozaTextSecondary,
                ),
              modifier = Modifier.testTag("editor_privacy_${option.firestoreValue}"),
            )
          }
        }
      }
    }
  }
}

internal fun formatEditorMs(ms: Long): String {
  val totalSeconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return "%02d:%02d".format(minutes, seconds)
}
