package com.vynoza.app.ui.studio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vynoza.app.R
import com.vynoza.app.core.studio.CreateStudioDraft
import com.vynoza.app.data.music.AudiusMusicRepository
import com.vynoza.app.data.music.ExoStudioAudioPreviewController
import com.vynoza.app.data.music.MediaExtractorWaveformSampler
import com.vynoza.app.data.studio.StudioStoreProvider
import com.vynoza.app.data.studio.StudioVideoExporter
import com.vynoza.app.ui.create.CreateViewModel
import com.vynoza.app.ui.create.PublishPostState
import com.vynoza.app.ui.theme.VynozaWarning
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaSuccess
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import com.vynoza.app.ui.theme.VynozaTextSecondary
import com.vynoza.app.ui.theme.VynozaVioletContainer

@Composable
fun VideoEditorRoute(
  initialDraft: CreateStudioDraft,
  createViewModel: CreateViewModel,
  onBack: () -> Unit,
  onNavigateToHome: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val localStore = remember(context) { StudioStoreProvider.get(context) }
  val audioPreviewController = remember(context) { ExoStudioAudioPreviewController(context) }
  val videoExporter = remember { StudioVideoExporter() }

  val editorViewModel =
    remember(initialDraft.id, createViewModel) {
      VideoEditorViewModel(
        localStore = localStore,
        videoExporter = videoExporter,
        audioPreviewController = audioPreviewController,
        createViewModel = createViewModel,
        initialDraft = initialDraft,
      )
    }

  val musicViewModel =
    remember(context, audioPreviewController) {
      MusicStudioViewModel(
        musicRepository = AudiusMusicRepository(),
        localStore = localStore,
        previewController = audioPreviewController,
        waveformExtractor = MediaExtractorWaveformSampler(),
      )
    }

  DisposableEffect(audioPreviewController) {
    onDispose { audioPreviewController.release() }
  }

  val createState by createViewModel.uiState.collectAsStateWithLifecycle()
  val editorUiState by editorViewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(initialDraft.id) {
    if (!createState.isPublishing && createState.publishState !is PublishPostState.Idle) {
      createViewModel.clearSelection()
    }
  }

  LaunchedEffect(createState.publishState, editorUiState.hasStartedPublishInSession) {
    if (editorUiState.hasStartedPublishInSession && createState.publishState is PublishPostState.Success) {
      editorViewModel.deleteSavedDraftAfterPublish()
    }
  }

  val sessionPublishState =
    if (editorUiState.hasStartedPublishInSession || createState.isPublishing) {
      createState.publishState
    } else {
      PublishPostState.Idle
    }

  VideoEditorScreen(
    viewModel = editorViewModel,
    publishState = sessionPublishState,
    onBack = onBack,
    onNavigateToHome = onNavigateToHome,
    musicStudioContent = { onCloseSheet, onSelected ->
      MusicStudioScreen(
        viewModel = musicViewModel,
        onBack = onCloseSheet,
        onSoundSelected = onSelected,
      )
    },
    modifier = modifier,
  )
}

@Composable
fun VideoEditorScreen(
  viewModel: VideoEditorViewModel,
  publishState: PublishPostState,
  onBack: () -> Unit,
  onNavigateToHome: () -> Unit,
  musicStudioContent: @Composable (onBack: () -> Unit, onSelected: (com.vynoza.app.core.studio.MusicTrimSelection) -> Unit) -> Unit = { _, _ -> },
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val isPublishing = publishState is PublishPostState.Uploading || publishState is PublishPostState.SavingPost
  val isExporting = uiState.exportState is VideoEditorExportState.Exporting
  val isBusy = isPublishing || isExporting

  val galleryPickerLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 8),
    ) { uris ->
      viewModel.addGalleryMedia(context, uris)
    }

  LaunchedEffect(Unit) {
    viewModel.validateExistingMedia(context)
  }

  DisposableEffect(lifecycleOwner) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE) {
          viewModel.pauseOnLifecycleExit()
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  BackHandler {
    when {
      uiState.isMusicSheetOpen -> viewModel.closeMusicStudio()
      isExporting -> viewModel.cancelExport()
      !isPublishing -> onBack()
    }
  }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(VynozaObsidian)
        .statusBarsPadding()
        .navigationBarsPadding()
        .testTag("video_editor_screen"),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .widthIn(max = 720.dp)
          .align(Alignment.Center)
          .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Top Bar
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          IconButton(
            onClick = { if (!isBusy) onBack() },
            enabled = !isBusy,
            modifier = Modifier.size(48.dp).testTag("editor_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = stringResource(R.string.video_editor_back),
              tint = Color.White,
            )
          }
          Text(
            text = stringResource(R.string.video_editor_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
          )
        }

        OutlinedButton(
          onClick = { viewModel.saveDraft(context) },
          enabled = !isBusy && !uiState.isSavingDraft && uiState.clips.isNotEmpty(),
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, VynozaBorderSubtle),
          modifier = Modifier.height(42.dp).testTag("editor_save_draft_button"),
        ) {
          Icon(Icons.Rounded.BookmarkBorder, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(stringResource(R.string.video_editor_save_draft))
        }
      }

      // Missing Media Warning Banner
      if (uiState.missingClipIds.isNotEmpty()) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = VynozaWarning.copy(alpha = 0.16f),
          border = BorderStroke(1.dp, VynozaWarning.copy(alpha = 0.6f)),
          modifier = Modifier.fillMaxWidth().testTag("editor_missing_media_banner"),
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = VynozaWarning)
            Text(
              text = "${uiState.missingClipIds.size} draft media file(s) are missing from storage.",
              style = MaterialTheme.typography.bodySmall,
              color = Color.White,
              modifier = Modifier.weight(1f),
            )
            TextButton(
              onClick = viewModel::removeMissingClips,
              modifier = Modifier.testTag("editor_remove_missing_button"),
            ) {
              Text("Remove", color = VynozaLavenderSoft)
            }
          }
        }
      }

      // Video / Photo Preview Player
      VideoEditorViewport(
        uiState = uiState,
        onTogglePlayPause = viewModel::togglePreviewPlayPause,
        onToggleExportedPreview = viewModel::setPreviewingExported,
        onPreviewProgressTick = viewModel::onPreviewProgressTick,
      )

      // Tab Switcher
      TabRow(
        selectedTabIndex = uiState.activeTab.ordinal,
        containerColor = VynozaSurfaceDeep,
        contentColor = VynozaLavenderSoft,
        indicator = { tabPositions ->
          TabRowDefaults.SecondaryIndicator(
            modifier = Modifier.tabIndicatorOffset(tabPositions[uiState.activeTab.ordinal]),
            color = VynozaPurplePrimary,
          )
        },
        modifier = Modifier.fillMaxWidth().testTag("editor_tab_row"),
      ) {
        VideoEditorTab.entries.forEach { tab ->
          Tab(
            selected = uiState.activeTab == tab,
            onClick = { viewModel.selectTab(tab) },
            text = { Text(tab.label, fontWeight = FontWeight.SemiBold) },
            modifier = Modifier.testTag("editor_tab_${tab.name.lowercase()}"),
          )
        }
      }

      // Scrollable Workspace Content
      Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (uiState.activeTab == VideoEditorTab.EDIT_AND_AUDIO) {
          VideoEditorClipAndTrimSection(
            uiState = uiState,
            onSelectClip = viewModel::selectClip,
            onAddGalleryMedia = {
              galleryPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
              )
            },
            onRemoveClip = viewModel::removeClip,
            onUpdateClipTrim = viewModel::updateClipTrim,
            onSelectEffect = viewModel::selectEffectPreset,
          )

          VideoEditorAudioMixSection(
            uiState = uiState,
            onOpenMusicPicker = viewModel::openMusicStudio,
            onRemoveMusic = viewModel::removeSelectedMusic,
            onOriginalVolumeChange = viewModel::updateOriginalAudioVolume,
            onMusicVolumeChange = viewModel::updateMusicVolume,
            onSyncOffsetChange = viewModel::updateMusicSyncOffset,
          )
        } else {
          VideoEditorCoverAndPublishSection(
            uiState = uiState,
            isBusy = isBusy,
            onUpdateCoverTimestamp = viewModel::updateCoverTimestamp,
            onCaptureCover = { viewModel.captureCoverFrame(context) },
            onUpdateCaption = viewModel::updateCaption,
            onAddHashtag = viewModel::addHashtag,
            onRemoveHashtag = viewModel::removeHashtag,
            onAddMention = viewModel::addMention,
            onRemoveMention = viewModel::removeMention,
            onSelectPrivacy = viewModel::selectPrivacy,
          )
        }

        // Export Status / Progress Card
        when (val exportState = uiState.exportState) {
          is VideoEditorExportState.Exporting -> {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = VynozaSurfaceElevated,
              border = BorderStroke(1.dp, VynozaPurplePrimary),
              modifier = Modifier.fillMaxWidth().testTag("editor_export_progress_card"),
            ) {
              Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = "Mixing audio & rendering MP4... ${(exportState.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = VynozaLavenderSoft,
                  )
                  TextButton(
                    onClick = viewModel::cancelExport,
                    modifier = Modifier.testTag("editor_cancel_export_button"),
                  ) {
                    Text(stringResource(R.string.video_editor_cancel_export))
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

          is VideoEditorExportState.Exported -> {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = VynozaSuccess.copy(alpha = 0.14f),
              border = BorderStroke(1.dp, VynozaSuccess.copy(alpha = 0.5f)),
              modifier = Modifier.fillMaxWidth().testTag("editor_export_ready_card"),
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = VynozaSuccess)
                Text(
                  text = "Mixed MP4 exported (${exportState.file.length() / 1024} KB). Ready to preview & publish.",
                  style = MaterialTheme.typography.bodySmall,
                  color = Color.White,
                )
              }
            }
          }

          is VideoEditorExportState.Error -> {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
              modifier = Modifier.fillMaxWidth().testTag("editor_export_error_card"),
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(
                  text = exportState.message,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
              }
            }
          }

          VideoEditorExportState.Idle -> Unit
        }

        // General Error or Draft Saved Banner
        val notice = uiState.errorMessage ?: uiState.draftStatusMessage
        if (!notice.isNullOrBlank()) {
          val isErr = uiState.errorMessage != null
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (isErr) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f) else VynozaVioletContainer,
            modifier = Modifier.fillMaxWidth().testTag("editor_status_banner"),
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier.weight(1f),
              )
              TextButton(onClick = viewModel::dismissMessages) {
                Text(stringResource(R.string.dismiss))
              }
            }
          }
        }

        // Cloudinary & Firestore Publish State
        when (publishState) {
          is PublishPostState.Uploading -> {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = VynozaSurfaceElevated,
              modifier = Modifier.fillMaxWidth().testTag("editor_publish_upload_progress"),
            ) {
              Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                  text =
                    publishState.progress?.let { "Uploading MP4 to Cloudinary... ${(it * 100).toInt()}%" }
                      ?: "Uploading MP4 to Cloudinary...",
                  style = MaterialTheme.typography.labelMedium,
                  color = VynozaLavenderSoft,
                )
                if (publishState.progress != null) {
                  LinearProgressIndicator(progress = { publishState.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                  LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
              }
            }
          }

          PublishPostState.SavingPost -> {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = VynozaSurfaceElevated,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                  text = "Saving video post to Cloud Firestore...",
                  style = MaterialTheme.typography.labelMedium,
                  color = VynozaLavenderSoft,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              }
            }
          }

          is PublishPostState.Error -> {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
              modifier = Modifier.fillMaxWidth().testTag("editor_publish_error_banner"),
            ) {
              Text(
                text = publishState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(12.dp),
              )
            }
          }

          is PublishPostState.Success -> {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = VynozaSuccess.copy(alpha = 0.16f),
              border = BorderStroke(1.dp, VynozaSuccess.copy(alpha = 0.5f)),
              modifier = Modifier.fillMaxWidth().testTag("editor_publish_success_banner"),
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  text = "Published to VYNOZA!",
                  style = MaterialTheme.typography.titleSmall,
                  color = VynozaSuccess,
                )
                Button(
                  onClick = onNavigateToHome,
                  colors = ButtonDefaults.buttonColors(containerColor = VynozaPurplePrimary),
                  modifier = Modifier.testTag("editor_watch_feed_button"),
                ) {
                  Text("Watch in Feed")
                }
              }
            }
          }

          PublishPostState.Idle -> Unit
        }
      }

      // Bottom Export & Publish Action Row
      if (publishState !is PublishPostState.Success) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedButton(
            onClick = { viewModel.exportEditedVideo(context, thenPublish = false) },
            enabled = !isBusy && uiState.clips.isNotEmpty() && !uiState.hasMusicLicenseConflict,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, VynozaPurplePrimary),
            modifier = Modifier.weight(1f).height(48.dp).testTag("editor_export_button"),
          ) {
            Icon(Icons.Rounded.MovieCreation, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.video_editor_export_mix), maxLines = 1)
          }

          Button(
            onClick = { viewModel.publishToVynoza(context) },
            enabled = !isBusy && uiState.clips.isNotEmpty() && !uiState.hasMusicLicenseConflict,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = VynozaPurplePrimary, contentColor = Color.White),
            modifier = Modifier.weight(1f).height(48.dp).testTag("editor_publish_button"),
          ) {
            if (isPublishing || isExporting) {
              CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
              Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(stringResource(R.string.video_editor_publish), maxLines = 1)
            }
          }
        }
      }
    }

    // Music Studio Picker Overlay
    if (uiState.isMusicSheetOpen) {
      Box(modifier = Modifier.fillMaxSize().testTag("editor_music_overlay")) {
        musicStudioContent(
          viewModel::closeMusicStudio,
          viewModel::onMusicSelected,
        )
      }
    }
  }
}
