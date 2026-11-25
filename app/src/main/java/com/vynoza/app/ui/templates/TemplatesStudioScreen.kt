package com.vynoza.app.ui.templates

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.PermMedia
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vynoza.app.R
import com.vynoza.app.core.templates.SelectedTemplateMedia
import com.vynoza.app.core.templates.TemplateExportState
import com.vynoza.app.core.templates.TemplateMediaKind
import com.vynoza.app.core.templates.VynozaTemplateCatalog
import com.vynoza.app.core.templates.VynozaTemplateId
import com.vynoza.app.core.templates.VynozaTemplateSpec
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaRoseAccent
import com.vynoza.app.ui.theme.VynozaSuccess
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import com.vynoza.app.ui.theme.VynozaVioletContainer

@Composable
fun TemplatesStudioScreen(
  viewModel: TemplatesStudioViewModel,
  onBackToCreate: () -> Unit,
  onExportedVideoReadyForPublish: (Uri) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  var isEditingTemplate by rememberSaveable { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    viewModel.initializeStudio(context)
  }

  // Zero-permission Photo Picker supporting both images and videos
  val mediaPickerLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
      if (uri != null) {
        viewModel.onMediaPicked(context, uri)
      }
    }

  fun launchMediaPicker() {
    mediaPickerLauncher.launch(
      PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
    )
  }

  BackHandler(enabled = !uiState.isExporting) {
    if (isEditingTemplate) {
      isEditingTemplate = false
    } else {
      onBackToCreate()
    }
  }

  Column(
    modifier =
      modifier
        .fillMaxSize()
        .background(VynozaObsidian)
        .statusBarsPadding(),
  ) {
    TemplatesStudioTopBar(
      title =
        if (isEditingTemplate) uiState.selectedTemplate.title
        else stringResource(R.string.templates_studio_title),
      subtitle =
        if (isEditingTemplate) uiState.selectedTemplate.badge
        else "CapCut-inspired on-device studio",
      canNavigateBack = !uiState.isExporting,
      onBack = {
        if (isEditingTemplate) {
          isEditingTemplate = false
        } else {
          onBackToCreate()
        }
      },
      onReset = if (isEditingTemplate) viewModel::resetParameters else null,
      resetEnabled = !uiState.isExporting,
    )

    if (!isEditingTemplate) {
      TemplateGalleryContent(
        uiState = uiState,
        onPickMedia = ::launchMediaPicker,
        onClearMedia = viewModel::clearSelectedMedia,
        onSelectTemplate = { id ->
          viewModel.selectTemplate(context, id)
          isEditingTemplate = true
        },
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      TemplateEditorContent(
        uiState = uiState,
        onPickMedia = ::launchMediaPicker,
        onClearMedia = viewModel::clearSelectedMedia,
        onCompareToggle = { viewModel.toggleCompareOriginal() },
        onUpdateParams = viewModel::updateParameters,
        onSavePhoto = { viewModel.saveEditedPhotoToDevice(context) },
        onSaveVideo = { viewModel.exportAndSaveVideoToDevice(context) },
        onPublishVideo = {
          viewModel.exportVideoForVynozaPublish(context, onExportedVideoReadyForPublish)
        },
        onCancelExport = viewModel::cancelExport,
        onDismissExportNotice = viewModel::dismissExportNotice,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

@Composable
private fun TemplatesStudioTopBar(
  title: String,
  subtitle: String,
  canNavigateBack: Boolean,
  onBack: () -> Unit,
  onReset: (() -> Unit)?,
  resetEnabled: Boolean,
) {
  Surface(
    color = VynozaSurfaceDeep,
    border = BorderStroke(1.dp, VynozaBorderSubtle.copy(alpha = 0.55f)),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      IconButton(
        onClick = onBack,
        enabled = canNavigateBack,
        modifier = Modifier.size(48.dp).testTag("templates_back_button"),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
          contentDescription = stringResource(R.string.templates_studio_back),
          tint = MaterialTheme.colorScheme.onSurface,
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.labelMedium,
          color = VynozaLavenderSoft,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (onReset != null) {
        TextButton(
          onClick = onReset,
          enabled = resetEnabled,
          modifier = Modifier.testTag("templates_reset_params_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = null,
            tint = VynozaLavenderSoft,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.size(4.dp))
          Text("Reset", color = VynozaLavenderSoft)
        }
      }
    }
  }
}

@Composable
private fun TemplateGalleryContent(
  uiState: TemplatesStudioUiState,
  onPickMedia: () -> Unit,
  onClearMedia: () -> Unit,
  onSelectTemplate: (VynozaTemplateId) -> Unit,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.TopCenter) {
    val columns = if (maxWidth >= 680.dp) 3 else 2
    LazyVerticalGrid(
      columns = GridCells.Fixed(columns),
      contentPadding = PaddingValues(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize().widthIn(max = 920.dp).testTag("templates_gallery_grid"),
    ) {
      item(span = { GridItemSpan(columns) }) {
        MediaSourceBanner(
          selectedMedia = uiState.selectedMedia,
          errorMessage = uiState.mediaLoadError,
          enabled = !uiState.isExporting,
          onPickMedia = onPickMedia,
          onClearMedia = onClearMedia,
        )
      }

      items(
        items = VynozaTemplateCatalog.templates,
        key = { it.id.routeArg },
      ) { spec ->
        val thumb = uiState.galleryThumbnails[spec.id]
        TemplateGalleryCard(
          spec = spec,
          previewThumbnail = thumb,
          onClick = { onSelectTemplate(spec.id) },
        )
      }
    }
  }
}

@Composable
private fun MediaSourceBanner(
  selectedMedia: SelectedTemplateMedia?,
  errorMessage: String?,
  enabled: Boolean,
  onPickMedia: () -> Unit,
  onClearMedia: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = VynozaSurfaceElevated,
    border = BorderStroke(1.dp, VynozaPurplePrimary.copy(alpha = 0.5f)),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Box(
          modifier =
            Modifier
              .size(44.dp)
              .background(VynozaVioletContainer, RoundedCornerShape(12.dp)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.PermMedia,
            contentDescription = null,
            tint = VynozaLavenderSoft,
          )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text =
              if (selectedMedia != null) selectedMedia.displayName
              else "Live Sample Portrait Loaded",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text =
              when (selectedMedia?.kind) {
                TemplateMediaKind.PHOTO -> "Your photo is ready — select any template below."
                TemplateMediaKind.VIDEO -> "Your video is ready — previews use real Media3 GL shaders."
                null -> "Pick a photo or video from your phone, or preview with our studio portrait."
              },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (selectedMedia != null) {
          IconButton(
            onClick = onClearMedia,
            enabled = enabled,
            modifier = Modifier.size(48.dp).testTag("templates_clear_media_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Close,
              contentDescription = "Clear selected media",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      if (errorMessage != null) {
        Text(
          text = errorMessage,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }

      Button(
        onClick = onPickMedia,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = VynozaPurplePrimary),
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("templates_pick_media_button"),
      ) {
        Icon(Icons.Rounded.PermMedia, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.size(8.dp))
        Text(
          text =
            if (selectedMedia == null) stringResource(R.string.templates_studio_choose_media)
            else "Change Photo or Video",
        )
      }
    }
  }
}

@Composable
private fun TemplateGalleryCard(
  spec: VynozaTemplateSpec,
  previewThumbnail: android.graphics.Bitmap?,
  onClick: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = VynozaSurfaceElevated,
    border = BorderStroke(1.dp, VynozaBorderSubtle),
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(20.dp))
        .clickable(onClick = onClick)
        .testTag("template_card_${spec.id.routeArg}"),
  ) {
    Column {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .background(VynozaSurfaceDeep),
      ) {
        if (previewThumbnail != null) {
          Image(
            bitmap = previewThumbnail.asImageBitmap(),
            contentDescription = "${spec.title} live preview",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          CircularProgressIndicator(
            color = VynozaPurplePrimary,
            modifier = Modifier.align(Alignment.Center).size(28.dp),
          )
        }

        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .background(
                Brush.verticalGradient(
                  colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                  startY = 120f,
                )
              )
        )

        Surface(
          shape = RoundedCornerShape(999.dp),
          color = VynozaVioletContainer.copy(alpha = 0.9f),
          modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
        ) {
          Text(
            text = spec.badge,
            style = MaterialTheme.typography.labelSmall,
            color = VynozaLavenderSoft,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }

        Surface(
          shape = RoundedCornerShape(999.dp),
          color = VynozaObsidian.copy(alpha = 0.72f),
          border = BorderStroke(1.dp, VynozaBorderSubtle),
          modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
        ) {
          Text(
            text = spec.mediaSupport.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }

        Column(
          modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Text(
            text = spec.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
          )
          Text(
            text = spec.shortDescription,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.82f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.templates_studio_use_template),
          style = MaterialTheme.typography.labelLarge,
          color = VynozaLavenderSoft,
        )
        Icon(
          imageVector = Icons.Rounded.AutoAwesome,
          contentDescription = null,
          tint = VynozaPurplePrimary,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

@Composable
private fun TemplateEditorContent(
  uiState: TemplatesStudioUiState,
  onPickMedia: () -> Unit,
  onClearMedia: () -> Unit,
  onCompareToggle: () -> Unit,
  onUpdateParams: ((com.vynoza.app.core.templates.TemplateParameters) -> com.vynoza.app.core.templates.TemplateParameters) -> Unit,
  onSavePhoto: () -> Unit,
  onSaveVideo: () -> Unit,
  onPublishVideo: () -> Unit,
  onCancelExport: () -> Unit,
  onDismissExportNotice: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val selectedMedia = uiState.selectedMedia
  val isVideoMode = selectedMedia?.kind == TemplateMediaKind.VIDEO

  Column(
    modifier =
      modifier
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 14.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // Live Preview Stage
      Surface(
        shape = RoundedCornerShape(22.dp),
        color = VynozaSurfaceDeep,
        border = BorderStroke(1.dp, VynozaBorderSubtle),
        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
      ) {
        Box(modifier = Modifier.fillMaxSize()) {
          if (selectedMedia?.kind == TemplateMediaKind.VIDEO) {
            TemplateVideoPreviewPlayer(
              videoUri = selectedMedia.uri,
              templateId = uiState.selectedTemplate.id,
              params = uiState.parameters,
              showOriginal = uiState.showOriginalComparison,
              modifier = Modifier.fillMaxSize(),
            )
          } else {
            val previewBmp =
              if (uiState.showOriginalComparison) {
                uiState.originalPhotoBitmap ?: uiState.renderedPhotoPreview
              } else {
                uiState.renderedPhotoPreview ?: uiState.originalPhotoBitmap
              }
            if (previewBmp != null) {
              Image(
                bitmap = previewBmp.asImageBitmap(),
                contentDescription = "${uiState.selectedTemplate.title} edited result",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().testTag("templates_photo_preview"),
              )
            } else {
              CircularProgressIndicator(
                color = VynozaPurplePrimary,
                modifier = Modifier.align(Alignment.Center),
              )
            }

            if (uiState.isRenderingPreview) {
              Surface(
                shape = RoundedCornerShape(999.dp),
                color = VynozaObsidian.copy(alpha = 0.72f),
                modifier = Modifier.align(Alignment.Center),
              ) {
                CircularProgressIndicator(
                  color = VynozaLavenderSoft,
                  strokeWidth = 2.dp,
                  modifier = Modifier.padding(10.dp).size(22.dp),
                )
              }
            }
          }

          // Top status pills
          Surface(
            shape = RoundedCornerShape(999.dp),
            color = VynozaObsidian.copy(alpha = 0.76f),
            border = BorderStroke(1.dp, VynozaBorderSubtle),
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
          ) {
            Text(
              text =
                when {
                  uiState.showOriginalComparison -> "Original"
                  selectedMedia == null -> stringResource(R.string.templates_studio_preview_sample)
                  else -> stringResource(R.string.templates_studio_media_selected)
                },
              style = MaterialTheme.typography.labelMedium,
              color = VynozaLavenderSoft,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
          }

          Surface(
            onClick = onCompareToggle,
            shape = RoundedCornerShape(999.dp),
            color =
              if (uiState.showOriginalComparison) VynozaPurplePrimary
              else VynozaObsidian.copy(alpha = 0.78f),
            border = BorderStroke(1.dp, VynozaPurplePrimary.copy(alpha = 0.7f)),
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).testTag("templates_compare_toggle"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Rounded.Compare,
                contentDescription = stringResource(R.string.templates_studio_compare),
                tint = Color.White,
                modifier = Modifier.size(16.dp),
              )
              Text(
                text = if (uiState.showOriginalComparison) "After" else "Original",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
              )
            }
          }
        }
      }

      // Media picker row inside editor
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedButton(
          onClick = onPickMedia,
          enabled = !uiState.isExporting,
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, VynozaPurplePrimary),
          modifier = Modifier.weight(1f).height(48.dp).testTag("templates_editor_pick_media"),
        ) {
          Icon(
            Icons.Rounded.PermMedia,
            contentDescription = null,
            tint = VynozaLavenderSoft,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.size(6.dp))
          Text(
            text =
              if (selectedMedia == null) stringResource(R.string.templates_studio_choose_media)
              else "Change Media",
            color = VynozaLavenderSoft,
          )
        }

        if (selectedMedia != null) {
          TextButton(
            onClick = onClearMedia,
            enabled = !uiState.isExporting,
            modifier = Modifier.height(48.dp),
          ) {
            Text("Use Sample")
          }
        }
      }

      // Honest capability & engine note
      Text(
        text =
          if (isVideoMode) uiState.selectedTemplate.videoEffectNote
          else uiState.selectedTemplate.photoEffectNote,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      // Live Template Parameters
      TemplateParameterControls(
        templateId = uiState.selectedTemplate.id,
        params = uiState.parameters,
        enabled = !uiState.isExporting,
        onUpdate = onUpdateParams,
      )

      // Export Status Card
      ExportStatusBanner(
        exportState = uiState.exportState,
        onCancelExport = onCancelExport,
        onDismiss = onDismissExportNotice,
      )

      // Save & Publish Action Buttons
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedButton(
          onClick = if (isVideoMode) onSaveVideo else onSavePhoto,
          enabled = !uiState.isExporting,
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, VynozaBorderSubtle),
          modifier =
            Modifier
              .weight(1f)
              .height(50.dp)
              .testTag(if (isVideoMode) "templates_save_video_button" else "templates_save_photo_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Download,
            contentDescription = null,
            tint = VynozaLavenderSoft,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.size(6.dp))
          Text(
            text =
              if (isVideoMode) stringResource(R.string.templates_studio_save_video)
              else stringResource(R.string.templates_studio_save_photo),
            color = MaterialTheme.colorScheme.onSurface,
          )
        }

        Button(
          onClick = onPublishVideo,
          enabled = !uiState.isExporting,
          shape = RoundedCornerShape(14.dp),
          colors = ButtonDefaults.buttonColors(containerColor = VynozaPurplePrimary),
          modifier = Modifier.weight(1f).height(50.dp).testTag("templates_publish_video_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.MovieCreation,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.size(6.dp))
          Text(
            text = stringResource(R.string.templates_studio_publish_feed),
            color = Color.White,
            maxLines = 1,
          )
        }
      }

      if (!isVideoMode) {
        Text(
          text = "Publishing a photo edit automatically encodes it into a 4.5s animated MP4 clip for VYNOZA's video feed.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

@Composable
private fun ExportStatusBanner(
  exportState: TemplateExportState,
  onCancelExport: () -> Unit,
  onDismiss: () -> Unit,
) {
  when (exportState) {
    TemplateExportState.Idle -> Unit

    is TemplateExportState.Processing -> {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = VynozaSurfaceElevated,
        border = BorderStroke(1.dp, VynozaPurplePrimary.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().testTag("templates_export_progress_card"),
      ) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = exportState.statusLabel,
              style = MaterialTheme.typography.labelLarge,
              color = VynozaLavenderSoft,
            )
            TextButton(
              onClick = onCancelExport,
              modifier = Modifier.testTag("templates_cancel_export_button"),
            ) {
              Text(stringResource(R.string.templates_studio_cancel_export), color = VynozaRoseAccent)
            }
          }
          LinearProgressIndicator(
            progress = { exportState.progress },
            color = VynozaPurplePrimary,
            trackColor = VynozaSurfaceDeep,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }

    is TemplateExportState.SavedPhoto -> {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = VynozaSuccess.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, VynozaSuccess.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth().testTag("templates_saved_photo_banner"),
      ) {
        Row(
          modifier = Modifier.padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = VynozaSuccess)
          Text(
            text = "Edited photo saved to Pictures/VYNOZA.",
            style = MaterialTheme.typography.bodyMedium,
            color = VynozaSuccess,
            modifier = Modifier.weight(1f),
          )
          TextButton(onClick = onDismiss) { Text("OK") }
        }
      }
    }

    is TemplateExportState.ExportedVideo -> {
      if (exportState.savedToGallery) {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = VynozaSuccess.copy(alpha = 0.14f),
          border = BorderStroke(1.dp, VynozaSuccess.copy(alpha = 0.55f)),
          modifier = Modifier.fillMaxWidth().testTag("templates_saved_video_banner"),
        ) {
          Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = VynozaSuccess)
            Text(
              text = "Rendered MP4 saved to Movies/VYNOZA.",
              style = MaterialTheme.typography.bodyMedium,
              color = VynozaSuccess,
              modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("OK") }
          }
        }
      }
    }

    is TemplateExportState.Error -> {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().testTag("templates_error_banner"),
      ) {
        Row(
          modifier = Modifier.padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            text = exportState.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
          )
          TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
      }
    }
  }
}
