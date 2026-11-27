package com.vynoza.app.ui.studio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vynoza.app.R
import com.vynoza.app.core.studio.MusicTrimSelection
import com.vynoza.app.data.music.AudioPreviewPlaybackState
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaErrorContainer
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurpleBright
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaRoseAccent
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import com.vynoza.app.ui.theme.VynozaSurfaceHigh
import com.vynoza.app.ui.theme.VynozaTextMuted
import com.vynoza.app.ui.theme.VynozaTextSecondary
import com.vynoza.app.ui.theme.VynozaVioletContainer
import kotlin.math.roundToLong

@Composable
fun MusicWaveformTrimmerSheet(
  selection: MusicTrimSelection,
  playback: AudioPreviewPlaybackState,
  isSamplingWaveform: Boolean,
  isWaveformRealDecoded: Boolean,
  isVerifyingSelection: Boolean,
  errorMessage: String?,
  onTogglePlayback: () -> Unit,
  onStartChanged: (Long) -> Unit,
  onDurationChanged: (Long) -> Unit,
  onSeekPreview: (Long) -> Unit,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val track = selection.track
  val totalMs = track.durationMs.coerceAtLeast(1_000L)
  val startMs = selection.clampedStartMs
  val endMs = selection.endMs
  val clipDurationMs = (endMs - startMs).coerceAtLeast(1_000L)
  val minDurationMs = minOf(MusicTrimSelection.MIN_TRIM_DURATION_MS, totalMs)
  val maxDurationMs = minOf(MusicTrimSelection.MAX_TRIM_DURATION_MS, totalMs).coerceAtLeast(minDurationMs + 1_000L)
  val maxStartMs = (totalMs - minDurationMs).coerceAtLeast(1_000L)
  val isPlayingThisTrack = playback.activeTrackId == track.id && playback.isPlaying

  Surface(
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    color = VynozaSurfaceElevated,
    border = BorderStroke(1.dp, VynozaBorderSubtle),
    modifier = modifier.fillMaxWidth().testTag("music_trimmer_sheet"),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp, vertical = 18.dp)
          .navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // Header Row
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = VynozaSurfaceHigh,
          modifier = Modifier.size(52.dp),
        ) {
          if (!track.artworkLargeUrl.isNullOrBlank()) {
            AsyncImage(
              model = track.artworkLargeUrl,
              contentDescription = "${track.title} artwork",
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          } else {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = VynozaLavenderSoft,
              )
            }
          }
        }

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = track.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = track.attributionSummary,
            style = MaterialTheme.typography.bodySmall,
            color = VynozaTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        IconButton(
          onClick = onDismiss,
          modifier = Modifier.size(48.dp).testTag("music_trim_close_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.dismiss),
            tint = VynozaTextSecondary,
          )
        }
      }

      // Licensing & Attribution Notice
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = VynozaSurfaceDeep,
        border = BorderStroke(1.dp, VynozaBorderSubtle),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.VerifiedUser,
            contentDescription = null,
            tint = VynozaLavenderSoft,
            modifier = Modifier.size(16.dp),
          )
          Text(
            text =
              if (isWaveformRealDecoded) {
                "Audius stream • ${track.license} • Waveform sampled from audio"
              } else {
                "Audius stream • ${track.license} • Streamed on demand, never saved offline"
              },
            style = MaterialTheme.typography.labelSmall,
            color = VynozaTextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      if (isSamplingWaveform) {
        LinearProgressIndicator(
          color = VynozaPurplePrimary,
          trackColor = VynozaSurfaceDeep,
          modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(99.dp)),
        )
      }

      // Interactive Waveform Scrubber
      Surface(
        shape = RoundedCornerShape(18.dp),
        color = VynozaObsidian,
        border = BorderStroke(1.dp, VynozaBorderSubtle),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          val bars =
            selection.waveformAmplitudes.ifEmpty {
              List(48) { idx -> 0.25f + 0.55f * ((idx * 7 % 11) / 10f) }
            }

          Canvas(
            modifier =
              Modifier
                .fillMaxWidth()
                .height(84.dp)
                .testTag("music_waveform_canvas")
                .pointerInput(track.id, totalMs, clipDurationMs) {
                  detectTapGestures { offset ->
                    val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val tappedMs = (fraction * totalMs).roundToLong()
                    if (tappedMs in startMs..endMs) {
                      onSeekPreview(tappedMs)
                    } else {
                      val centeredStart = (tappedMs - clipDurationMs / 2L).coerceIn(0L, (totalMs - clipDurationMs).coerceAtLeast(0L))
                      onStartChanged(centeredStart)
                    }
                  }
                }
                .pointerInput(track.id, totalMs, clipDurationMs) {
                  detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    val currentStart = selection.clampedStartMs
                    val deltaMs = ((dragAmount / size.width.toFloat()) * totalMs).roundToLong()
                    val maxAllowedStart = (totalMs - clipDurationMs).coerceAtLeast(0L)
                    onStartChanged((currentStart + deltaMs).coerceIn(0L, maxAllowedStart))
                  }
                },
          ) {
            val count = bars.size.coerceAtLeast(1)
            val slotWidth = size.width / count
            val barWidth = (slotWidth * 0.62f).coerceAtLeast(2.dp.toPx())
            val startFrac = (startMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
            val endFrac = (endMs.toFloat() / totalMs.toFloat()).coerceIn(startFrac, 1f)

            // Active selection band
            val bandLeft = startFrac * size.width
            val bandRight = endFrac * size.width
            drawRoundRect(
              color = VynozaPurplePrimary.copy(alpha = 0.16f),
              topLeft = Offset(bandLeft, 0f),
              size = Size((bandRight - bandLeft).coerceAtLeast(4f), size.height),
              cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
            )

            bars.forEachIndexed { index, amp ->
              val barFrac = (index + 0.5f) / count
              val inSelection = barFrac in startFrac..endFrac
              val barHeight = (size.height * amp.coerceIn(0.15f, 1f)).coerceAtLeast(8.dp.toPx())
              val top = (size.height - barHeight) * 0.5f
              val left = index * slotWidth + (slotWidth - barWidth) * 0.5f
              drawRoundRect(
                color = if (inSelection) VynozaLavenderSoft else VynozaTextMuted.copy(alpha = 0.42f),
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth * 0.5f, barWidth * 0.5f),
              )
            }

            // Start & End trim boundary handles
            drawRect(
              color = VynozaPurpleBright,
              topLeft = Offset((bandLeft - 1.5.dp.toPx()).coerceAtLeast(0f), 0f),
              size = Size(3.dp.toPx(), size.height),
            )
            drawRect(
              color = VynozaPurpleBright,
              topLeft = Offset((bandRight - 1.5.dp.toPx()).coerceAtMost(size.width - 3.dp.toPx()), 0f),
              size = Size(3.dp.toPx(), size.height),
            )

            // Live playhead indicator
            if (playback.activeTrackId == track.id) {
              val playFrac = (playback.positionMs.toFloat() / totalMs.toFloat()).coerceIn(startFrac, endFrac)
              val playX = playFrac * size.width
              drawRect(
                color = VynozaRoseAccent,
                topLeft = Offset((playX - 1.dp.toPx()).coerceIn(0f, size.width - 2.dp.toPx()), 0f),
                size = Size(2.dp.toPx(), size.height),
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Start ${formatDurationMs(startMs)} • End ${formatDurationMs(endMs)}",
              style = MaterialTheme.typography.labelMedium,
              color = VynozaLavenderSoft,
              modifier = Modifier.testTag("music_trim_timecode_label"),
            )
            Text(
              text = "Clip ${clipDurationMs / 1000L}s / ${formatDurationMs(totalMs)}",
              style = MaterialTheme.typography.labelSmall,
              color = VynozaTextSecondary,
            )
          }
        }
      }

      // Quick Clip Duration Pills
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Clip Length:",
          style = MaterialTheme.typography.labelMedium,
          color = VynozaTextSecondary,
        )
        listOf(15_000L, 30_000L, 60_000L).forEach { presetMs ->
          if (presetMs <= totalMs || presetMs == 15_000L) {
            val targetMs = minOf(presetMs, totalMs)
            val selected = kotlin.math.abs(clipDurationMs - targetMs) < 1_000L
            FilterChip(
              selected = selected,
              onClick = { onDurationChanged(targetMs) },
              label = { Text("${targetMs / 1000L}s") },
              colors =
                FilterChipDefaults.filterChipColors(
                  selectedContainerColor = VynozaVioletContainer,
                  selectedLabelColor = VynozaLavenderSoft,
                  containerColor = VynozaSurfaceDeep,
                  labelColor = VynozaTextSecondary,
                ),
              modifier = Modifier.testTag("music_trim_preset_${presetMs / 1000L}s"),
            )
          }
        }
      }

      // Precision Start & Length Sliders
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = "Start Position (${formatDurationMs(startMs)})",
          style = MaterialTheme.typography.labelSmall,
          color = VynozaTextSecondary,
        )
        Slider(
          value = startMs.toFloat().coerceIn(0f, maxStartMs.toFloat()),
          onValueChange = { onStartChanged(it.roundToLong()) },
          valueRange = 0f..maxStartMs.toFloat(),
          colors =
            SliderDefaults.colors(
              thumbColor = VynozaLavenderSoft,
              activeTrackColor = VynozaPurplePrimary,
              inactiveTrackColor = VynozaSurfaceDeep,
            ),
          modifier = Modifier.fillMaxWidth().testTag("music_trim_start_slider"),
        )

        Text(
          text = "Trim Duration (${clipDurationMs / 1000L}s)",
          style = MaterialTheme.typography.labelSmall,
          color = VynozaTextSecondary,
        )
        Slider(
          value = clipDurationMs.toFloat().coerceIn(minDurationMs.toFloat(), maxDurationMs.toFloat()),
          onValueChange = { onDurationChanged(it.roundToLong()) },
          valueRange = minDurationMs.toFloat()..maxDurationMs.toFloat(),
          colors =
            SliderDefaults.colors(
              thumbColor = VynozaLavenderSoft,
              activeTrackColor = VynozaPurpleBright,
              inactiveTrackColor = VynozaSurfaceDeep,
            ),
          modifier = Modifier.fillMaxWidth().testTag("music_trim_duration_slider"),
        )
      }

      if (errorMessage != null) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = VynozaErrorContainer,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
          modifier = Modifier.fillMaxWidth().testTag("music_trim_error_banner"),
        ) {
          Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
          )
        }
      }

      // Action Bar: Loop Preview + Confirm Use Sound
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          onClick = onTogglePlayback,
          shape = CircleShape,
          color = VynozaSurfaceHigh,
          border = BorderStroke(1.dp, VynozaBorderSubtle),
          modifier = Modifier.size(50.dp).testTag("music_trim_play_button"),
        ) {
          Box(contentAlignment = Alignment.Center) {
            if (playback.activeTrackId == track.id && playback.isBuffering) {
              CircularProgressIndicator(
                color = VynozaLavenderSoft,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
              )
            } else {
              Icon(
                imageVector = if (isPlayingThisTrack) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlayingThisTrack) "Pause trimmed sound" else "Preview trimmed sound",
                tint = Color.White,
              )
            }
          }
        }

        Button(
          onClick = onConfirm,
          enabled = !isVerifyingSelection,
          colors =
            ButtonDefaults.buttonColors(
              containerColor = VynozaPurplePrimary,
              contentColor = Color.White,
            ),
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.weight(1f).height(50.dp).testTag("music_confirm_trim_button"),
        ) {
          if (isVerifyingSelection) {
            CircularProgressIndicator(
              color = Color.White,
              strokeWidth = 2.dp,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Verifying license...")
          } else {
            Icon(
              imageVector = Icons.Rounded.Check,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.music_studio_use_sound))
          }
        }
      }
    }
  }
}

internal fun formatDurationMs(durationMs: Long): String {
  val totalSec = (durationMs.coerceAtLeast(0L) / 1000L)
  val mins = totalSec / 60L
  val secs = totalSec % 60L
  return "%d:%02d".format(mins, secs)
}
