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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.vynoza.app.core.studio.CameraCaptureOutput
import com.vynoza.app.core.studio.CameraLayoutOption
import com.vynoza.app.core.studio.EditorMediaKind
import com.vynoza.app.core.studio.StudioEffectCatalog
import com.vynoza.app.core.studio.StudioEffectCategory
import com.vynoza.app.core.studio.StudioEffectPreset
import com.vynoza.app.core.studio.TextStudioAlignment
import com.vynoza.app.core.studio.TextStudioBackground
import com.vynoza.app.data.studio.StudioMediaProcessor
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaRoseAccent
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import com.vynoza.app.ui.theme.VynozaSurfaceHigh
import com.vynoza.app.ui.theme.VynozaTextMuted
import com.vynoza.app.ui.theme.VynozaTextSecondary
import com.vynoza.app.ui.theme.VynozaVioletContainer

@Composable
fun CameraGridAndSplitOverlay(
  layoutOption: CameraLayoutOption,
  modifier: Modifier = Modifier,
) {
  if (!layoutOption.showRuleOfThirds && !layoutOption.isSplitGuide) return

  Canvas(modifier = modifier.fillMaxSize()) {
    val strokeColor = Color.White.copy(alpha = 0.42f)
    val strokePx = 1.5.dp.toPx()

    if (layoutOption.showRuleOfThirds) {
      val thirdW = size.width / 3f
      val thirdH = size.height / 3f
      for (i in 1..2) {
        drawLine(
          color = strokeColor,
          start = Offset(thirdW * i, 0f),
          end = Offset(thirdW * i, size.height),
          strokeWidth = strokePx,
        )
        drawLine(
          color = strokeColor,
          start = Offset(0f, thirdH * i),
          end = Offset(size.width, thirdH * i),
          strokeWidth = strokePx,
        )
      }
    }

    if (layoutOption.isSplitGuide) {
      val midY = size.height / 2f
      drawLine(
        color = Color(0xFFE0AAFF).copy(alpha = 0.85f),
        start = Offset(0f, midY),
        end = Offset(size.width, midY),
        strokeWidth = 2.dp.toPx(),
      )
    }
  }
}

@Composable
fun CameraEffectsCarousel(
  selectedEffect: StudioEffectPreset,
  onSelectEffect: (StudioEffectPreset) -> Unit,
  onOpenGallery: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val carouselItems = remember { StudioEffectCatalog.carouselPresets }
  LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier.fillMaxWidth().testTag("camera_effects_carousel"),
  ) {
    item(key = "open_effects_gallery") {
      Surface(
        onClick = onOpenGallery,
        shape = RoundedCornerShape(16.dp),
        color = VynozaSurfaceHigh.copy(alpha = 0.90f),
        border = BorderStroke(1.dp, VynozaPurplePrimary.copy(alpha = 0.7f)),
        modifier = Modifier.height(48.dp).testTag("camera_open_effects_gallery"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = stringResource(R.string.camera_studio_effects),
            tint = VynozaLavenderSoft,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = "All FX",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = VynozaLavenderSoft,
          )
        }
      }
    }

    items(carouselItems, key = { it.id }) { preset ->
      val isSelected = preset.id == selectedEffect.id
      val accent = Color(preset.accentColorArgb)
      Surface(
        onClick = { onSelectEffect(preset) },
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) VynozaVioletContainer else VynozaObsidian.copy(alpha = 0.68f),
        border =
          BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) accent else VynozaBorderSubtle,
          ),
        modifier = Modifier.height(48.dp).testTag("camera_effect_chip_${preset.id}"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Box(
            modifier =
              Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(
                  Brush.linearGradient(
                    colors = listOf(accent, accent.copy(alpha = 0.45f)),
                  )
                ),
          )
          Text(
            text = preset.name,
            style = MaterialTheme.typography.labelMedium.copy(
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            ),
            color = if (isSelected) Color.White else VynozaTextSecondary,
            maxLines = 1,
          )
        }
      }
    }
  }
}

@Composable
fun CameraEffectsGallerySheet(
  selectedEffect: StudioEffectPreset,
  selectedCategory: StudioEffectCategory,
  onSelectCategory: (StudioEffectCategory) -> Unit,
  onSelectEffect: (StudioEffectPreset) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val filteredPresets =
    remember(selectedCategory) { StudioEffectCatalog.presetsFor(selectedCategory) }

  Surface(
    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    color = VynozaSurfaceDeep,
    tonalElevation = 8.dp,
    border = BorderStroke(1.dp, VynozaBorderSubtle),
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(max = 520.dp)
        .navigationBarsPadding()
        .testTag("camera_effects_gallery_sheet"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column {
          Text(
            text = "Effects & Filters Gallery",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "Active: ${selectedEffect.name}",
            style = MaterialTheme.typography.labelSmall,
            color = VynozaLavenderSoft,
          )
        }
        IconButton(
          onClick = onDismiss,
          modifier = Modifier.size(48.dp).testTag("camera_effects_gallery_close"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Close effects gallery",
            tint = VynozaTextSecondary,
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        StudioEffectCategory.entries.forEach { category ->
          val active = category == selectedCategory
          FilterChip(
            selected = active,
            onClick = { onSelectCategory(category) },
            label = { Text(category.label) },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = VynozaVioletContainer,
                selectedLabelColor = VynozaLavenderSoft,
                containerColor = VynozaSurfaceElevated,
                labelColor = VynozaTextSecondary,
              ),
            modifier = Modifier.testTag("effects_category_${category.name.lowercase()}"),
          )
        }
      }

      LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
      ) {
        items(filteredPresets, key = { it.id }) { preset ->
          val isSelected = preset.id == selectedEffect.id
          val accent = Color(preset.accentColorArgb)
          Surface(
            onClick = { onSelectEffect(preset) },
            shape = RoundedCornerShape(16.dp),
            color = if (isSelected) VynozaSurfaceHigh else VynozaSurfaceElevated,
            border =
              BorderStroke(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) accent else VynozaBorderSubtle,
              ),
            modifier = Modifier.fillMaxWidth().testTag("effects_gallery_item_${preset.id}"),
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Box(
                  modifier =
                    Modifier
                      .size(26.dp)
                      .clip(CircleShape)
                      .background(Brush.linearGradient(listOf(accent, VynozaObsidian))),
                )
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = accent.copy(alpha = 0.22f),
                ) {
                  Text(
                    text = preset.badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  )
                }
              }
              Text(
                text = preset.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = preset.description,
                style = MaterialTheme.typography.bodySmall,
                color = VynozaTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun TextCaptureCanvas(
  textContent: String,
  background: TextStudioBackground,
  alignment: TextStudioAlignment,
  fontScale: Float,
  onTextChange: (String) -> Unit,
  onSelectBackground: (TextStudioBackground) -> Unit,
  onCycleAlignment: () -> Unit,
  onFontScaleChange: (Float) -> Unit,
  onCreateTextCard: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val textAlign =
    when (alignment) {
      TextStudioAlignment.CENTER -> TextAlign.Center
      TextStudioAlignment.LEFT -> TextAlign.Start
      TextStudioAlignment.RIGHT -> TextAlign.End
    }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(
          Brush.linearGradient(
            colors = listOf(Color(background.startColorArgb), Color(background.endColorArgb)),
          )
        )
        .padding(horizontal = 20.dp, vertical = 24.dp)
        .testTag("camera_text_mode_canvas"),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      OutlinedTextField(
        value = textContent,
        onValueChange = onTextChange,
        placeholder = {
          Text(
            text = "Share a thought, prompt, or story card...",
            color = Color(background.textColorArgb).copy(alpha = 0.55f),
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
          )
        },
        textStyle =
          MaterialTheme.typography.headlineSmall.copy(
            color = Color(background.textColorArgb),
            fontWeight = FontWeight.Bold,
            fontSize = (22f * fontScale).sp,
            textAlign = textAlign,
          ),
        colors =
          OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(background.textColorArgb).copy(alpha = 0.55f),
            unfocusedBorderColor = Color(background.textColorArgb).copy(alpha = 0.25f),
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
          ),
        maxLines = 6,
        modifier = Modifier.fillMaxWidth().testTag("camera_text_input"),
      )

      Spacer(modifier = Modifier.height(16.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          TextStudioBackground.entries.forEach { preset ->
            val selected = preset == background
            Box(
              modifier =
                Modifier
                  .size(34.dp)
                  .clip(CircleShape)
                  .background(
                    Brush.linearGradient(
                      colors = listOf(Color(preset.startColorArgb), Color(preset.endColorArgb)),
                    )
                  )
                  .clickable { onSelectBackground(preset) }
                  .testTag("camera_text_bg_${preset.name.lowercase()}"),
              contentAlignment = Alignment.Center,
            ) {
              if (selected) {
                Icon(
                  imageVector = Icons.Rounded.Check,
                  contentDescription = preset.label,
                  tint = Color(preset.textColorArgb),
                  modifier = Modifier.size(18.dp),
                )
              }
            }
          }
        }

        Surface(
          onClick = onCycleAlignment,
          shape = RoundedCornerShape(12.dp),
          color = VynozaObsidian.copy(alpha = 0.45f),
          modifier = Modifier.size(48.dp).testTag("camera_text_align_button"),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector =
                when (alignment) {
                  TextStudioAlignment.CENTER -> Icons.Rounded.FormatAlignCenter
                  TextStudioAlignment.LEFT -> Icons.AutoMirrored.Rounded.FormatAlignLeft
                  TextStudioAlignment.RIGHT -> Icons.AutoMirrored.Rounded.FormatAlignRight
                },
              contentDescription = "Text align: ${alignment.label}",
              tint = Color.White,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      Slider(
        value = fontScale,
        onValueChange = onFontScaleChange,
        valueRange = 0.8f..1.45f,
        colors =
          SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White.copy(alpha = 0.85f),
            inactiveTrackColor = Color.White.copy(alpha = 0.30f),
          ),
        modifier = Modifier.fillMaxWidth().testTag("camera_text_size_slider"),
      )

      Spacer(modifier = Modifier.height(10.dp))

      Button(
        onClick = onCreateTextCard,
        enabled = textContent.isNotBlank(),
        shape = RoundedCornerShape(16.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = VynozaPurplePrimary,
            contentColor = Color.White,
          ),
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("camera_text_create_button"),
      ) {
        Text("Render Text Story", fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

@OptIn(UnstableApi::class)
@Composable
fun CameraCapturedReviewOverlay(
  output: CameraCaptureOutput,
  onRetake: () -> Unit,
  onUseClip: (CameraCaptureOutput) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var activeClipIndex by remember(output) { mutableIntStateOf(0) }
  val activeClip = output.clips.getOrNull(activeClipIndex) ?: return
  var isVideoPlaying by remember(activeClip.id) { mutableStateOf(true) }
  var playerError by remember(activeClip.id) { mutableStateOf<String?>(null) }

  val exoPlayer =
    remember(activeClip.id, activeClip.uriString) {
      if (activeClip.kind == EditorMediaKind.VIDEO) {
        runCatching {
          ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(Uri.parse(activeClip.uriString)))
            prepare()
            playWhenReady = true
          }
        }.getOrNull()
      } else {
        null
      }
    }

  LaunchedEffect(exoPlayer, isVideoPlaying) {
    exoPlayer?.playWhenReady = isVideoPlaying
  }

  DisposableEffect(exoPlayer) {
    val listener =
      object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
          playerError = error.localizedMessage ?: "Unable to preview recorded clip."
        }
      }
    exoPlayer?.addListener(listener)
    onDispose {
      exoPlayer?.removeListener(listener)
      exoPlayer?.release()
    }
  }

  val effectMatrix =
    remember(output.effectPresetId) {
      StudioMediaProcessor.colorMatrixForEffect(output.effectPresetId)
    }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(VynozaObsidian)
        .navigationBarsPadding()
        .testTag("camera_review_overlay"),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .widthIn(max = 720.dp)
          .align(Alignment.Center)
          .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Top Summary Row
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text =
            if (output.clips.size > 1) {
              "Review Selection (${output.clips.size} clips)"
            } else if (activeClip.kind == EditorMediaKind.VIDEO) {
              "Review Recorded Clip"
            } else {
              "Review Captured Photo"
            },
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
          color = Color.White,
        )
        Surface(
          shape = RoundedCornerShape(99.dp),
          color = VynozaVioletContainer,
        ) {
          Text(
            text = StudioEffectCatalog.findById(output.effectPresetId).name,
            style = MaterialTheme.typography.labelSmall,
            color = VynozaLavenderSoft,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      }

      // Preview Viewport
      Box(
        modifier =
          Modifier
            .weight(1f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(VynozaSurfaceDeep)
            .clickable(enabled = activeClip.kind == EditorMediaKind.VIDEO) {
              isVideoPlaying = !isVideoPlaying
            },
        contentAlignment = Alignment.Center,
      ) {
        if (activeClip.kind == EditorMediaKind.VIDEO && exoPlayer != null && playerError == null) {
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
            modifier = Modifier.fillMaxSize().testTag("camera_review_video_player"),
          )
          Surface(
            onClick = { isVideoPlaying = !isVideoPlaying },
            shape = CircleShape,
            color = VynozaObsidian.copy(alpha = 0.65f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(48.dp).testTag("camera_review_play_toggle"),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = if (isVideoPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isVideoPlaying) "Pause preview" else "Play preview",
                tint = Color.White,
              )
            }
          }
        } else {
          AsyncImage(
            model = Uri.parse(activeClip.uriString),
            contentDescription = activeClip.displayName,
            contentScale = ContentScale.Fit,
            colorFilter = effectMatrix?.let { ColorFilter.colorMatrix(ColorMatrix(it.array)) },
            modifier = Modifier.fillMaxSize().testTag("camera_review_image_preview"),
          )
          if (playerError != null) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = VynozaObsidian.copy(alpha = 0.85f),
              modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            ) {
              Text(
                text = playerError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = VynozaRoseAccent,
                modifier = Modifier.padding(10.dp),
              )
            }
          }
        }
      }

      if (output.clips.size > 1) {
        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth().testTag("camera_review_clip_strip"),
        ) {
          items(output.clips.indices.toList()) { idx ->
            val clip = output.clips[idx]
            val selected = idx == activeClipIndex
            FilterChip(
              selected = selected,
              onClick = { activeClipIndex = idx },
              label = { Text("${idx + 1}. ${clip.kind.name.lowercase().replaceFirstChar { it.uppercase() }}") },
              colors =
                FilterChipDefaults.filterChipColors(
                  selectedContainerColor = VynozaVioletContainer,
                  selectedLabelColor = VynozaLavenderSoft,
                  containerColor = VynozaSurfaceElevated,
                  labelColor = VynozaTextSecondary,
                ),
            )
          }
        }
      }

      output.selectedMusic?.let { music ->
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = VynozaSurfaceElevated,
          border = BorderStroke(1.dp, VynozaBorderSubtle),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.MusicNote,
              contentDescription = null,
              tint = VynozaLavenderSoft,
              modifier = Modifier.size(18.dp),
            )
            Text(
              text = "${music.track.title} • ${music.track.artistName}",
              style = MaterialTheme.typography.bodySmall,
              color = Color.White,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      // Action Buttons
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        OutlinedButton(
          onClick = {
            exoPlayer?.pause()
            onRetake()
          },
          shape = RoundedCornerShape(16.dp),
          border = BorderStroke(1.dp, VynozaBorderSubtle),
          modifier = Modifier.weight(1f).height(50.dp).testTag("camera_review_retake_button"),
        ) {
          Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(stringResource(R.string.camera_studio_retake))
        }

        Button(
          onClick = {
            exoPlayer?.pause()
            onUseClip(output)
          },
          shape = RoundedCornerShape(16.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = VynozaPurplePrimary,
              contentColor = Color.White,
            ),
          modifier = Modifier.weight(1f).height(50.dp).testTag("camera_review_use_button"),
        ) {
          Icon(imageVector = Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(stringResource(R.string.camera_studio_use_clip), fontWeight = FontWeight.SemiBold)
        }
      }
    }
  }
}
