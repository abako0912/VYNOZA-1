package com.vynoza.app.ui.templates

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vynoza.app.core.templates.CloneArrangement
import com.vynoza.app.core.templates.ExposureBlendMode
import com.vynoza.app.core.templates.MemoriesFrameStyle
import com.vynoza.app.core.templates.MemoriesTransitionStyle
import com.vynoza.app.core.templates.PortraitFraming
import com.vynoza.app.core.templates.TemplateParameters
import com.vynoza.app.core.templates.VynozaTemplateId
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import com.vynoza.app.ui.theme.VynozaVioletContainer
import kotlin.math.roundToInt

@Composable
fun TemplateParameterControls(
  templateId: VynozaTemplateId,
  params: TemplateParameters,
  enabled: Boolean,
  onUpdate: ((TemplateParameters) -> TemplateParameters) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = VynozaSurfaceElevated,
    border = BorderStroke(1.dp, VynozaBorderSubtle),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      when (templateId) {
        VynozaTemplateId.CLONE_ECHO -> {
          ChipGroupLabel("Layout Arrangement")
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            CloneArrangement.entries.forEach { option ->
              StudioFilterChip(
                label = option.label,
                selected = params.cloneArrangement == option,
                enabled = enabled,
                tag = "param_clone_arr_${option.name.lowercase()}",
                onClick = { onUpdate { it.copy(cloneArrangement = option) } },
              )
            }
          }

          ChipGroupLabel("Clone Copies")
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 3, 4, 5).forEach { count ->
              StudioFilterChip(
                label = "${count}x",
                selected = params.cloneCount == count,
                enabled = enabled,
                tag = "param_clone_count_$count",
                onClick = { onUpdate { it.copy(cloneCount = count) } },
              )
            }
          }

          StudioSlider(
            label = "Echo Spacing",
            value = params.cloneSpacing,
            valueRange = 0.10f..1.0f,
            readout = "${(params.cloneSpacing * 100).roundToInt()}%",
            enabled = enabled,
            tag = "param_clone_spacing",
            onValueChange = { v -> onUpdate { it.copy(cloneSpacing = v) } },
          )
          StudioSlider(
            label = "Echo Opacity",
            value = params.cloneOpacity,
            valueRange = 0.15f..0.95f,
            readout = "${(params.cloneOpacity * 100).roundToInt()}%",
            enabled = enabled,
            tag = "param_clone_opacity",
            onValueChange = { v -> onUpdate { it.copy(cloneOpacity = v) } },
          )
          StudioSwitchRow(
            label = "Violet Aura Echo Tint",
            checked = params.cloneEchoTint,
            enabled = enabled,
            tag = "param_clone_tint",
            onCheckedChange = { checked -> onUpdate { it.copy(cloneEchoTint = checked) } },
          )
        }

        VynozaTemplateId.CINEMATIC_PORTRAIT -> {
          ChipGroupLabel("Portrait Framing")
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            PortraitFraming.entries.forEach { framing ->
              StudioFilterChip(
                label = framing.label,
                selected = params.portraitFraming == framing,
                enabled = enabled,
                tag = "param_portrait_frame_${framing.name.lowercase()}",
                onClick = { onUpdate { it.copy(portraitFraming = framing) } },
              )
            }
          }

          StudioSlider(
            label = "Contrast",
            value = params.contrast,
            valueRange = 0.70f..1.80f,
            readout = "${(params.contrast * 100).roundToInt()}%",
            enabled = enabled,
            tag = "param_cinematic_contrast",
            onValueChange = { v -> onUpdate { it.copy(contrast = v) } },
          )
          StudioSlider(
            label = "Saturation",
            value = params.saturation,
            valueRange = 0.30f..1.70f,
            readout = "${(params.saturation * 100).roundToInt()}%",
            enabled = enabled,
            tag = "param_cinematic_saturation",
            onValueChange = { v -> onUpdate { it.copy(saturation = v) } },
          )
          StudioSlider(
            label = "Shadows Lift",
            value = params.shadows,
            valueRange = -0.50f..0.60f,
            readout = formatSignedPercent(params.shadows),
            enabled = enabled,
            tag = "param_cinematic_shadows",
            onValueChange = { v -> onUpdate { it.copy(shadows = v) } },
          )
          StudioSlider(
            label = "Highlights Recovery",
            value = params.highlights,
            valueRange = -0.60f..0.50f,
            readout = formatSignedPercent(params.highlights),
            enabled = enabled,
            tag = "param_cinematic_highlights",
            onValueChange = { v -> onUpdate { it.copy(highlights = v) } },
          )
          StudioSlider(
            label = "Video Smooth Zoom",
            value = params.smoothZoom,
            valueRange = 0.0f..0.35f,
            readout = "+${(params.smoothZoom * 100).roundToInt()}%",
            enabled = enabled,
            tag = "param_cinematic_zoom",
            onValueChange = { v -> onUpdate { it.copy(smoothZoom = v) } },
          )
        }

        VynozaTemplateId.MOTION_BLUR -> {
          StudioSlider(
            label = "Blur Intensity",
            value = params.blurIntensity,
            valueRange = 0.08f..1.0f,
            readout = "${(params.blurIntensity * 100).roundToInt()}%",
            enabled = enabled,
            tag = "param_motion_intensity",
            onValueChange = { v -> onUpdate { it.copy(blurIntensity = v) } },
          )
          StudioSlider(
            label = "Direction Angle",
            value = params.blurAngleDegrees,
            valueRange = 0f..360f,
            readout = "${params.blurAngleDegrees.roundToInt()}°",
            enabled = enabled,
            tag = "param_motion_angle",
            onValueChange = { v -> onUpdate { it.copy(blurAngleDegrees = v) } },
          )
          StudioSlider(
            label = "Ghost Trail",
            value = params.ghostTrail,
            valueRange = 0.0f..1.0f,
            readout = "${(params.ghostTrail * 100).roundToInt()}%",
            enabled = enabled,
            tag = "param_motion_ghost",
            onValueChange = { v -> onUpdate { it.copy(ghostTrail = v) } },
          )
        }

        VynozaTemplateId.MULTIPLE_EXPOSURE -> {
          ChipGroupLabel("Blend Mode")
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            ExposureBlendMode.entries.forEach { mode ->
              StudioFilterChip(
                label = mode.label,
                selected = params.exposureBlendMode == mode,
                enabled = enabled,
                tag = "param_exposure_blend_${mode.name.lowercase()}",
                onClick = { onUpdate { it.copy(exposureBlendMode = mode) } },
              )
            }
          }
          StudioSlider(
            label = "Layer Opacity",
            value = params.exposureOpacity,
            valueRange = 0.15f..0.90f,
            readout = "${(params.exposureOpacity * 100).roundToInt()}%",
            enabled = enabled,
            tag = "param_exposure_opacity",
            onValueChange = { v -> onUpdate { it.copy(exposureOpacity = v) } },
          )
          StudioSlider(
            label = "Composition Offset",
            value = params.exposureOffset,
            valueRange = 0.05f..0.85f,
            readout = "${(params.exposureOffset * 100).roundToInt()}%",
            enabled = enabled,
            tag = "param_exposure_offset",
            onValueChange = { v -> onUpdate { it.copy(exposureOffset = v) } },
          )
          StudioSlider(
            label = "2nd Exposure Scale",
            value = params.exposureScale,
            valueRange = 0.85f..1.40f,
            readout = "${(params.exposureScale * 100).roundToInt()}%",
            enabled = enabled,
            tag = "param_exposure_scale",
            onValueChange = { v -> onUpdate { it.copy(exposureScale = v) } },
          )
          StudioSwitchRow(
            label = "Mirror Secondary Layer",
            checked = params.exposureMirrorSecond,
            enabled = enabled,
            tag = "param_exposure_mirror",
            onCheckedChange = { checked -> onUpdate { it.copy(exposureMirrorSecond = checked) } },
          )
        }

        VynozaTemplateId.MEMORIES -> {
          ChipGroupLabel("Frame Layout")
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            MemoriesFrameStyle.entries.forEach { style ->
              StudioFilterChip(
                label = style.label,
                selected = params.memoriesFrameStyle == style,
                enabled = enabled,
                tag = "param_memories_frame_${style.name.lowercase()}",
                onClick = { onUpdate { it.copy(memoriesFrameStyle = style) } },
              )
            }
          }

          ChipGroupLabel("Video Transition")
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            MemoriesTransitionStyle.entries.forEach { transition ->
              StudioFilterChip(
                label = transition.label,
                selected = params.memoriesTransition == transition,
                enabled = enabled,
                tag = "param_memories_transition_${transition.name.lowercase()}",
                onClick = { onUpdate { it.copy(memoriesTransition = transition) } },
              )
            }
          }

          OutlinedTextField(
            value = params.memoriesTitle,
            onValueChange = { txt -> onUpdate { it.copy(memoriesTitle = txt.take(36)) } },
            enabled = enabled,
            label = { Text("Memory Title") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedContainerColor = VynozaSurfaceDeep,
              unfocusedContainerColor = VynozaSurfaceDeep,
              focusedBorderColor = VynozaPurplePrimary,
              unfocusedBorderColor = VynozaBorderSubtle,
            ),
            modifier = Modifier.fillMaxWidth().testTag("param_memories_title_input"),
          )

          OutlinedTextField(
            value = params.memoriesDateText,
            onValueChange = { txt -> onUpdate { it.copy(memoriesDateText = txt.take(26)) } },
            enabled = enabled,
            label = { Text("Date / Location Stamp") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedContainerColor = VynozaSurfaceDeep,
              unfocusedContainerColor = VynozaSurfaceDeep,
              focusedBorderColor = VynozaPurplePrimary,
              unfocusedBorderColor = VynozaBorderSubtle,
            ),
            modifier = Modifier.fillMaxWidth().testTag("param_memories_date_input"),
          )

          StudioSlider(
            label = "Nostalgic Warmth",
            value = params.memoriesWarmth,
            valueRange = 0.0f..1.0f,
            readout = "${(params.memoriesWarmth * 100).roundToInt()}%",
            enabled = enabled,
            tag = "param_memories_warmth",
            onValueChange = { v -> onUpdate { it.copy(memoriesWarmth = v) } },
          )
        }
      }
    }
  }
}

@Composable
private fun ChipGroupLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = VynozaLavenderSoft,
  )
}

@Composable
private fun StudioFilterChip(
  label: String,
  selected: Boolean,
  enabled: Boolean,
  tag: String,
  onClick: () -> Unit,
) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    enabled = enabled,
    label = { Text(label) },
    colors = FilterChipDefaults.filterChipColors(
      selectedContainerColor = VynozaVioletContainer,
      selectedLabelColor = VynozaLavenderSoft,
      containerColor = VynozaSurfaceDeep,
      labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ),
    border = FilterChipDefaults.filterChipBorder(
      enabled = enabled,
      selected = selected,
      borderColor = VynozaBorderSubtle,
      selectedBorderColor = VynozaPurplePrimary,
    ),
    modifier = Modifier.testTag(tag),
  )
}

@Composable
private fun StudioSlider(
  label: String,
  value: Float,
  valueRange: ClosedFloatingPointRange<Float>,
  readout: String,
  enabled: Boolean,
  tag: String,
  onValueChange: (Float) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = readout,
        style = MaterialTheme.typography.labelMedium,
        color = VynozaLavenderSoft,
      )
    }
    Slider(
      value = value.coerceIn(valueRange.start, valueRange.endInclusive),
      onValueChange = onValueChange,
      valueRange = valueRange,
      enabled = enabled,
      colors = SliderDefaults.colors(
        thumbColor = VynozaLavenderSoft,
        activeTrackColor = VynozaPurplePrimary,
        inactiveTrackColor = VynozaSurfaceDeep,
      ),
      modifier = Modifier.fillMaxWidth().testTag(tag),
    )
  }
}

@Composable
private fun StudioSwitchRow(
  label: String,
  checked: Boolean,
  enabled: Boolean,
  tag: String,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      enabled = enabled,
      colors = SwitchDefaults.colors(
        checkedThumbColor = VynozaLavenderSoft,
        checkedTrackColor = VynozaPurplePrimary,
      ),
      modifier = Modifier.testTag(tag),
    )
  }
}

private fun formatSignedPercent(value: Float): String {
  val pct = (value * 100).roundToInt()
  return if (pct >= 0) "+$pct%" else "$pct%"
}
