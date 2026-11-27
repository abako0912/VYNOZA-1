package com.vynoza.app.ui.studio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Videocam
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.vynoza.app.R
import com.vynoza.app.core.studio.CameraCaptureOutput
import com.vynoza.app.core.studio.CameraFlashMode
import com.vynoza.app.core.studio.CameraTimerOption
import com.vynoza.app.core.studio.CaptureMode
import com.vynoza.app.core.studio.RecordingDurationOption
import com.vynoza.app.core.studio.StudioEffectCatalog
import com.vynoza.app.data.music.AudiusMusicRepository
import com.vynoza.app.data.music.ExoStudioAudioPreviewController
import com.vynoza.app.data.music.MediaExtractorWaveformSampler
import com.vynoza.app.data.music.StudioAudioPreviewController
import com.vynoza.app.data.studio.CameraXStudioController
import com.vynoza.app.data.studio.StudioCameraController
import com.vynoza.app.data.studio.StudioMediaProcessor
import com.vynoza.app.data.studio.StudioStoreProvider
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaErrorContainer
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaRoseAccent
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import com.vynoza.app.ui.theme.VynozaTextSecondary
import com.vynoza.app.ui.theme.VynozaVioletContainer

@Composable
fun CameraStudioRoute(
  onClose: () -> Unit,
  onCaptureCompleted: (CameraCaptureOutput) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val soundPreviewController = remember(context) { ExoStudioAudioPreviewController(context) }
  val cameraController = remember { CameraXStudioController() }
  val cameraViewModel = remember(cameraController, soundPreviewController) {
    CameraStudioViewModel(
      cameraController = cameraController,
      soundPreviewController = soundPreviewController,
    )
  }
  val musicViewModel = remember(context, soundPreviewController) {
    MusicStudioViewModel(
      musicRepository = AudiusMusicRepository(),
      localStore = StudioStoreProvider.get(context),
      previewController = soundPreviewController,
      waveformExtractor = MediaExtractorWaveformSampler(),
    )
  }

  DisposableEffect(soundPreviewController) {
    onDispose {
      soundPreviewController.release()
    }
  }

  CameraStudioScreen(
    viewModel = cameraViewModel,
    onClose = onClose,
    onCaptureCompleted = onCaptureCompleted,
    musicStudioContent = { onBack, onSelected ->
      MusicStudioScreen(
        viewModel = musicViewModel,
        onBack = onBack,
        onSoundSelected = onSelected,
      )
    },
    modifier = modifier,
  )
}

@Composable
fun CameraStudioScreen(
  viewModel: CameraStudioViewModel,
  onClose: () -> Unit,
  onCaptureCompleted: (CameraCaptureOutput) -> Unit,
  musicStudioContent: @Composable (onBack: () -> Unit, onSelected: (com.vynoza.app.core.studio.MusicTrimSelection) -> Unit) -> Unit = { _, _ -> },
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  val permissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
      val cam = grants[Manifest.permission.CAMERA] == true || hasPermission(context, Manifest.permission.CAMERA)
      val mic = grants[Manifest.permission.RECORD_AUDIO] == true || hasPermission(context, Manifest.permission.RECORD_AUDIO)
      viewModel.syncPermissions(cameraGranted = cam, micGranted = mic, requested = true)
    }

  val galleryPickerLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10),
    ) { uris ->
      viewModel.onGalleryMediaSelected(context, uris)
    }

  val previewView = remember(context) {
    PreviewView(context).apply {
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      scaleType = PreviewView.ScaleType.FILL_CENTER
    }
  }

  LaunchedEffect(Unit) {
    val cam = hasPermission(context, Manifest.permission.CAMERA)
    val mic = hasPermission(context, Manifest.permission.RECORD_AUDIO)
    viewModel.syncPermissions(cameraGranted = cam, micGranted = mic, requested = false)
    if (!cam) {
      permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
  }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE -> viewModel.onAppLifecyclePause()
        Lifecycle.Event.ON_RESUME -> {
          val cam = hasPermission(context, Manifest.permission.CAMERA)
          val mic = hasPermission(context, Manifest.permission.RECORD_AUDIO)
          viewModel.syncPermissions(cameraGranted = cam, micGranted = mic, requested = false)
        }
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  LaunchedEffect(
    uiState.hasCameraPermission,
    uiState.captureMode,
    uiState.lensFacing,
    uiState.layoutOption,
  ) {
    if (uiState.hasCameraPermission || uiState.captureMode == CaptureMode.TEXT) {
      viewModel.bindPreview(context, lifecycleOwner, previewView)
    }
  }

  LaunchedEffect(uiState.selectedEffect.id, previewView) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val cm = StudioMediaProcessor.colorMatrixForEffect(uiState.selectedEffect.id)
      val renderEffect = cm?.let { RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(it)) }
      previewView.setRenderEffect(renderEffect)
    }
  }

  BackHandler {
    when {
      uiState.isMusicSheetOpen -> viewModel.closeMusicStudio()
      uiState.isEffectsGalleryOpen -> viewModel.closeEffectsGallery()
      uiState.reviewOutput != null -> viewModel.retakeCapture()
      uiState.countdownRemainingSec != null -> viewModel.cancelCountdown()
      uiState.isRecording -> viewModel.stopRecording()
      else -> onClose()
    }
  }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(VynozaObsidian)
        .testTag("camera_studio_screen"),
  ) {
    // Viewport Canvas
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      if (uiState.captureMode == CaptureMode.TEXT) {
        TextCaptureCanvas(
          textContent = uiState.textContent,
          background = uiState.textBackground,
          alignment = uiState.textAlignment,
          fontScale = uiState.textFontScale,
          onTextChange = viewModel::updateTextContent,
          onSelectBackground = viewModel::selectTextBackground,
          onCycleAlignment = viewModel::cycleTextAlignment,
          onFontScaleChange = viewModel::updateTextFontScale,
          onCreateTextCard = { viewModel.submitTextStory(context) },
          modifier =
            Modifier
              .widthIn(max = 620.dp)
              .aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
              .clip(RoundedCornerShape(24.dp)),
        )
      } else if (uiState.hasCameraPermission) {
        Box(
          modifier =
            Modifier
              .widthIn(max = 620.dp)
              .aspectRatio(uiState.layoutOption.aspectRatio, matchHeightConstraintsFirst = true)
              .clip(RoundedCornerShape(24.dp))
              .background(Color.Black)
              .testTag("camera_preview_viewport"),
        ) {
          AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
          )

          // Visual atmospheric overlay for non-trivial effects across all Android API levels
          if (uiState.selectedEffect.id != StudioEffectCatalog.EFFECT_NONE_ID) {
            Box(
              modifier =
                Modifier
                  .fillMaxSize()
                  .background(Color(uiState.selectedEffect.accentColorArgb).copy(alpha = 0.08f))
                  .testTag("camera_active_effect_overlay"),
            )
          }

          CameraGridAndSplitOverlay(layoutOption = uiState.layoutOption)
        }
      } else {
        CameraPermissionRationaleCard(
          onRequestPermissions = {
            permissionLauncher.launch(
              arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            )
          },
          onOpenSettings = { openAppSettings(context) },
          onPickFromGallery = {
            galleryPickerLauncher.launch(
              PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )
          },
          onSwitchToText = { viewModel.selectCaptureMode(CaptureMode.TEXT) },
          modifier = Modifier.padding(horizontal = 24.dp),
        )
      }
    }

    // Safe-area foreground Column (Top Bar + Middle Rail + Bottom Dock) prevents overlaps on any screen height
    Column(
      modifier =
        Modifier
          .align(Alignment.Center)
          .fillMaxSize()
          .widthIn(max = 720.dp)
          .statusBarsPadding()
          .navigationBarsPadding(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Top Control Bar
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Surface(
            onClick = {
              if (uiState.isRecording) viewModel.stopRecording() else onClose()
            },
            shape = CircleShape,
            color = VynozaObsidian.copy(alpha = 0.62f),
            modifier = Modifier.size(48.dp).testTag("camera_close_button"),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.camera_studio_close),
                tint = Color.White,
              )
            }
          }

          // Add Sound Pill
          Surface(
            onClick = { if (!uiState.isRecording) viewModel.openMusicStudio() },
            shape = RoundedCornerShape(99.dp),
            color = VynozaObsidian.copy(alpha = 0.72f),
            border =
              BorderStroke(
                1.dp,
                if (uiState.selectedMusic != null) VynozaPurplePrimary else VynozaBorderSubtle,
              ),
            modifier = Modifier.height(48.dp).widthIn(max = 220.dp).testTag("camera_add_sound_button"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = VynozaLavenderSoft,
                modifier = Modifier.size(18.dp),
              )
              Text(
                text = uiState.selectedMusic?.track?.title ?: stringResource(R.string.camera_studio_add_sound),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              if (uiState.selectedMusic != null && !uiState.isRecording) {
                IconButton(
                  onClick = viewModel::clearSelectedMusic,
                  modifier = Modifier.size(24.dp).testTag("camera_clear_sound_button"),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove sound",
                    tint = VynozaTextSecondary,
                    modifier = Modifier.size(15.dp),
                  )
                }
              }
            }
          }

          // Layout Pill Label
          Surface(
            shape = RoundedCornerShape(99.dp),
            color = VynozaObsidian.copy(alpha = 0.62f),
            modifier = Modifier.testTag("camera_layout_badge"),
          ) {
            Text(
              text = uiState.layoutOption.label,
              style = MaterialTheme.typography.labelSmall,
              color = VynozaLavenderSoft,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
          }
        }

        if (uiState.isRecording) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Box(
                  modifier = Modifier.size(10.dp).clip(CircleShape).background(VynozaRoseAccent),
                )
                Text(
                  text = "REC",
                  style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                  color = VynozaRoseAccent,
                )
              }
              Text(
                text = "${formatMmSs(uiState.recordingElapsedMs)} / ${formatMmSs(uiState.durationOption.durationMs)}",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier.testTag("camera_recording_timer"),
              )
            }
            LinearProgressIndicator(
              progress = { uiState.recordingProgressFraction },
              color = VynozaRoseAccent,
              trackColor = Color.White.copy(alpha = 0.24f),
              modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(99.dp)),
            )
          }
        }

        val statusBanner = uiState.errorMessage ?: uiState.recordingNotice
        if (statusBanner != null) {
          val isError = uiState.errorMessage != null
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (isError) VynozaErrorContainer else VynozaSurfaceElevated.copy(alpha = 0.94f),
            border =
              BorderStroke(
                1.dp,
                if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.6f) else VynozaBorderSubtle,
              ),
            modifier =
              Modifier
                .fillMaxWidth()
                .testTag(if (isError) "camera_error_banner" else "camera_notice_banner"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = statusBanner,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else Color.White,
                modifier = Modifier.weight(1f),
              )
              TextButton(
                onClick = { if (isError) viewModel.dismissError() else viewModel.dismissNotice() },
              ) {
                Text(stringResource(R.string.dismiss))
              }
            }
          }
        }
      }

      // Middle flexible region: houses responsive Right Tool Rail without clipping on compact screens
      BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        if (uiState.captureMode != CaptureMode.TEXT && uiState.hasCameraPermission) {
          val railScrollState = rememberScrollState()
          val railScope = rememberCoroutineScope()
          val useCompactTwoColumnRail = maxHeight < 270.dp

          Surface(
            shape = RoundedCornerShape(22.dp),
            color = VynozaObsidian.copy(alpha = 0.66f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            modifier =
              Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp)
                .testTag("camera_tools_rail"),
          ) {
            Column(
              modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              if (railScrollState.canScrollBackward) {
                Surface(
                  onClick = {
                    railScope.launch {
                      railScrollState.animateScrollTo((railScrollState.value - 160).coerceAtLeast(0))
                    }
                  },
                  color = Color.Transparent,
                  modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 28.dp).testTag("camera_rail_scroll_up"),
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Icon(
                      imageVector = Icons.Rounded.KeyboardArrowUp,
                      contentDescription = "Scroll camera tools up",
                      tint = VynozaLavenderSoft,
                      modifier = Modifier.size(18.dp),
                    )
                  }
                }
              }

              Box(modifier = Modifier.weight(1f, fill = false)) {
                if (useCompactTwoColumnRail) {
                  Row(
                    modifier = Modifier.verticalScroll(railScrollState),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Top,
                  ) {
                    Column(
                      horizontalAlignment = Alignment.CenterHorizontally,
                      verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                      CameraRailIconButton(
                        icon = Icons.Rounded.Cameraswitch,
                        label = "Flip",
                        contentDescription = stringResource(R.string.camera_studio_flip),
                        enabled = uiState.canSwitchCamera && !uiState.isRecording,
                        onClick = viewModel::flipCamera,
                        testTag = "camera_flip_button",
                      )
                      CameraRailIconButton(
                        icon =
                          when (uiState.flashMode) {
                            CameraFlashMode.OFF -> Icons.Rounded.FlashOff
                            CameraFlashMode.ON -> Icons.Rounded.FlashOn
                            CameraFlashMode.AUTO -> Icons.Rounded.FlashAuto
                          },
                        label = if (uiState.isFlashSupported) uiState.flashMode.label else "No Flash",
                        contentDescription = stringResource(R.string.camera_studio_flash),
                        enabled = !uiState.isRecording,
                        isActive = uiState.flashMode != CameraFlashMode.OFF,
                        onClick = viewModel::cycleFlashMode,
                        testTag = "camera_flash_button",
                      )
                      CameraRailIconButton(
                        icon = Icons.Rounded.Timer,
                        label = if (uiState.timerOption == CameraTimerOption.OFF) "Timer" else uiState.timerOption.label,
                        contentDescription = stringResource(R.string.camera_studio_timer),
                        enabled = !uiState.isRecording,
                        isActive = uiState.timerOption != CameraTimerOption.OFF,
                        onClick = viewModel::cycleTimerOption,
                        testTag = "camera_timer_button",
                      )
                    }
                    Column(
                      horizontalAlignment = Alignment.CenterHorizontally,
                      verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                      CameraRailIconButton(
                        icon = Icons.Rounded.GridOn,
                        label = "Layout",
                        contentDescription = stringResource(R.string.camera_studio_layout),
                        enabled = !uiState.isRecording,
                        isActive = uiState.layoutOption != com.vynoza.app.core.studio.CameraLayoutOption.PORTRAIT_9_16,
                        onClick = viewModel::cycleLayoutOption,
                        testTag = "camera_layout_button",
                      )
                      CameraRailIconButton(
                        icon = Icons.Rounded.AutoAwesome,
                        label = "FX",
                        contentDescription = stringResource(R.string.camera_studio_effects),
                        enabled = !uiState.isRecording,
                        isActive = uiState.selectedEffect.id != StudioEffectCatalog.EFFECT_NONE_ID,
                        onClick = viewModel::openEffectsGallery,
                        testTag = "camera_effects_rail_button",
                      )
                    }
                  }
                } else {
                  Column(
                    modifier = Modifier.verticalScroll(railScrollState),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    CameraRailIconButton(
                      icon = Icons.Rounded.Cameraswitch,
                      label = "Flip",
                      contentDescription = stringResource(R.string.camera_studio_flip),
                      enabled = uiState.canSwitchCamera && !uiState.isRecording,
                      onClick = viewModel::flipCamera,
                      testTag = "camera_flip_button",
                    )
                    CameraRailIconButton(
                      icon =
                        when (uiState.flashMode) {
                          CameraFlashMode.OFF -> Icons.Rounded.FlashOff
                          CameraFlashMode.ON -> Icons.Rounded.FlashOn
                          CameraFlashMode.AUTO -> Icons.Rounded.FlashAuto
                        },
                      label = if (uiState.isFlashSupported) uiState.flashMode.label else "No Flash",
                      contentDescription = stringResource(R.string.camera_studio_flash),
                      enabled = !uiState.isRecording,
                      isActive = uiState.flashMode != CameraFlashMode.OFF,
                      onClick = viewModel::cycleFlashMode,
                      testTag = "camera_flash_button",
                    )
                    CameraRailIconButton(
                      icon = Icons.Rounded.Timer,
                      label = if (uiState.timerOption == CameraTimerOption.OFF) "Timer" else uiState.timerOption.label,
                      contentDescription = stringResource(R.string.camera_studio_timer),
                      enabled = !uiState.isRecording,
                      isActive = uiState.timerOption != CameraTimerOption.OFF,
                      onClick = viewModel::cycleTimerOption,
                      testTag = "camera_timer_button",
                    )
                    CameraRailIconButton(
                      icon = Icons.Rounded.GridOn,
                      label = "Layout",
                      contentDescription = stringResource(R.string.camera_studio_layout),
                      enabled = !uiState.isRecording,
                      isActive = uiState.layoutOption != com.vynoza.app.core.studio.CameraLayoutOption.PORTRAIT_9_16,
                      onClick = viewModel::cycleLayoutOption,
                      testTag = "camera_layout_button",
                    )
                    CameraRailIconButton(
                      icon = Icons.Rounded.AutoAwesome,
                      label = "FX",
                      contentDescription = stringResource(R.string.camera_studio_effects),
                      enabled = !uiState.isRecording,
                      isActive = uiState.selectedEffect.id != StudioEffectCatalog.EFFECT_NONE_ID,
                      onClick = viewModel::openEffectsGallery,
                      testTag = "camera_effects_rail_button",
                    )
                  }
                }
              }

              if (railScrollState.canScrollForward) {
                Surface(
                  onClick = {
                    railScope.launch {
                      railScrollState.animateScrollTo(
                        (railScrollState.value + 160).coerceAtMost(railScrollState.maxValue),
                      )
                    }
                  },
                  color = Color.Transparent,
                  modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 28.dp).testTag("camera_rail_scroll_down"),
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Icon(
                      imageVector = Icons.Rounded.KeyboardArrowDown,
                      contentDescription = "Scroll camera tools down",
                      tint = VynozaLavenderSoft,
                      modifier = Modifier.size(18.dp),
                    )
                  }
                }
              }
            }
          }
        }
      }

      // Bottom Dock: Durations, Effects Carousel, Gallery + Shutter, Capture Modes
      if (uiState.hasCameraPermission || uiState.captureMode == CaptureMode.TEXT) {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(bottom = 14.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          AnimatedVisibility(visible = uiState.captureMode != CaptureMode.TEXT && !uiState.isRecording) {
            CameraEffectsCarousel(
              selectedEffect = uiState.selectedEffect,
              onSelectEffect = { preset -> viewModel.selectEffect(preset) },
              onOpenGallery = viewModel::openEffectsGallery,
            )
          }

          if (uiState.captureMode == CaptureMode.VIDEO && !uiState.isRecording) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.testTag("camera_duration_selector"),
            ) {
              RecordingDurationOption.entries.forEach { option ->
                val selected = option == uiState.durationOption
                Surface(
                  onClick = { viewModel.selectDurationOption(option) },
                  shape = RoundedCornerShape(99.dp),
                  color = if (selected) VynozaPurplePrimary else VynozaObsidian.copy(alpha = 0.68f),
                  border = BorderStroke(1.dp, if (selected) VynozaLavenderSoft else VynozaBorderSubtle),
                  modifier = Modifier.testTag("camera_duration_${option.label}"),
                ) {
                  Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                  )
                }
              }
            }
          }

          // Main Shutter + Gallery Row
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
          ) {
            // Gallery Picker (Zero storage permissions)
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Surface(
                onClick = {
                  if (!uiState.isRecording) {
                    galleryPickerLauncher.launch(
                      PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                    )
                  }
                },
                shape = RoundedCornerShape(14.dp),
                color = VynozaObsidian.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, VynozaBorderSubtle),
                modifier = Modifier.size(50.dp).testTag("camera_gallery_button"),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.Rounded.PhotoLibrary,
                    contentDescription = stringResource(R.string.camera_studio_gallery),
                    tint = Color.White,
                  )
                }
              }
              Text(
                text = stringResource(R.string.camera_studio_gallery),
                style = MaterialTheme.typography.labelSmall,
                color = VynozaTextSecondary,
              )
            }

            // Shutter / Record Button
            val isBusy = uiState.isCapturingPhoto || uiState.isProcessingMedia
            Box(
              modifier =
                Modifier
                  .size(78.dp)
                  .clip(CircleShape)
                  .border(
                    width = 4.dp,
                    color = if (uiState.isRecording) VynozaRoseAccent else Color.White,
                    shape = CircleShape,
                  )
                  .padding(6.dp)
                  .clip(if (uiState.isRecording) RoundedCornerShape(14.dp) else CircleShape)
                  .background(
                    when {
                      uiState.isRecording -> VynozaRoseAccent
                      uiState.captureMode == CaptureMode.VIDEO -> VynozaRoseAccent
                      uiState.captureMode == CaptureMode.PHOTO -> Color.White
                      else -> VynozaPurplePrimary
                    }
                  )
                  .clickable(enabled = !isBusy) { viewModel.onShutterPressed(context) }
                  .testTag("camera_shutter_button"),
              contentAlignment = Alignment.Center,
            ) {
              if (isBusy) {
                CircularProgressIndicator(
                  color = VynozaPurplePrimary,
                  strokeWidth = 2.5.dp,
                  modifier = Modifier.size(28.dp),
                )
              }
            }

            // Effects Drawer Quick Button
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Surface(
                onClick = { if (!uiState.isRecording) viewModel.openEffectsGallery() },
                shape = RoundedCornerShape(14.dp),
                color = VynozaObsidian.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, VynozaBorderSubtle),
                modifier = Modifier.size(50.dp).testTag("camera_effects_dock_button"),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = stringResource(R.string.camera_studio_effects),
                    tint = VynozaLavenderSoft,
                  )
                }
              }
              Text(
                text = uiState.selectedEffect.name,
                style = MaterialTheme.typography.labelSmall,
                color = VynozaTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 68.dp),
              )
            }
          }

          // Capture Modes: Video / Photo / Text
          Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.testTag("camera_mode_selector"),
          ) {
            CaptureMode.entries.forEach { mode ->
              val selected = mode == uiState.captureMode
              Surface(
                onClick = { viewModel.selectCaptureMode(mode) },
                shape = RoundedCornerShape(99.dp),
                color = if (selected) VynozaVioletContainer else Color.Transparent,
                border = if (selected) BorderStroke(1.dp, VynozaPurplePrimary) else null,
                modifier = Modifier.height(40.dp).testTag("camera_mode_${mode.name.lowercase()}"),
              ) {
                Box(
                  contentAlignment = Alignment.Center,
                  modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                  Text(
                    text = mode.label,
                    style = MaterialTheme.typography.labelLarge.copy(
                      fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = if (selected) Color.White else VynozaTextSecondary,
                  )
                }
              }
            }
          }
        }
      }
    }

    // Countdown Overlay
    val remainingSec = uiState.countdownRemainingSec
    if (remainingSec != null) {
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(VynozaObsidian.copy(alpha = 0.56f))
            .testTag("camera_countdown_overlay"),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Text(
            text = remainingSec.toString(),
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 86.sp, fontWeight = FontWeight.ExtraBold),
            color = Color.White,
            modifier = Modifier.testTag("camera_countdown_number"),
          )
          OutlinedButton(
            onClick = viewModel::cancelCountdown,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
            modifier = Modifier.height(48.dp).testTag("camera_countdown_cancel"),
          ) {
            Text("Cancel Timer", color = Color.White)
          }
        }
      }
    }

    // Effects Gallery Sheet
    if (uiState.isEffectsGalleryOpen) {
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(VynozaObsidian.copy(alpha = 0.55f))
            .clickable(onClick = viewModel::closeEffectsGallery),
      )
      CameraEffectsGallerySheet(
        selectedEffect = uiState.selectedEffect,
        selectedCategory = uiState.selectedEffectCategory,
        onSelectCategory = viewModel::selectEffectCategory,
        onSelectEffect = { preset -> viewModel.selectEffect(preset, closeGallery = true) },
        onDismiss = viewModel::closeEffectsGallery,
        modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 720.dp),
      )
    }

    // Clip Playback & Photo Review Overlay
    val review = uiState.reviewOutput
    if (review != null) {
      CameraCapturedReviewOverlay(
        output = review,
        onRetake = viewModel::retakeCapture,
        onUseClip = onCaptureCompleted,
      )
    }

    // Music Studio Overlay
    if (uiState.isMusicSheetOpen) {
      Box(modifier = Modifier.fillMaxSize().testTag("camera_music_overlay")) {
        musicStudioContent(
          viewModel::closeMusicStudio,
          viewModel::onMusicSelected,
        )
      }
    }
  }
}

@Composable
private fun CameraRailIconButton(
  icon: ImageVector,
  label: String,
  contentDescription: String,
  enabled: Boolean,
  isActive: Boolean = false,
  onClick: () -> Unit,
  testTag: String,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier =
      Modifier
        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        .clip(RoundedCornerShape(14.dp))
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 4.dp)
        .testTag(testTag),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint =
        when {
          !enabled -> Color.White.copy(alpha = 0.35f)
          isActive -> VynozaLavenderSoft
          else -> Color.White
        },
      modifier = Modifier.size(20.dp),
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
      color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
      maxLines = 1,
    )
  }
}

@Composable
private fun CameraPermissionRationaleCard(
  onRequestPermissions: () -> Unit,
  onOpenSettings: () -> Unit,
  onPickFromGallery: () -> Unit,
  onSwitchToText: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = RoundedCornerShape(24.dp),
    color = VynozaSurfaceElevated,
    border = BorderStroke(1.dp, VynozaBorderSubtle),
    modifier = modifier.fillMaxWidth().widthIn(max = 460.dp).testTag("camera_permission_card"),
  ) {
    Column(
      modifier = Modifier.verticalScroll(rememberScrollState()).padding(22.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = VynozaVioletContainer,
        modifier = Modifier.size(56.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Rounded.Videocam,
            contentDescription = null,
            tint = VynozaLavenderSoft,
            modifier = Modifier.size(28.dp),
          )
        }
      }
      Text(
        text = stringResource(R.string.camera_studio_permission_title),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = Color.White,
      )
      Text(
        text = stringResource(R.string.camera_studio_permission_body),
        style = MaterialTheme.typography.bodySmall,
        color = VynozaTextSecondary,
      )
      Button(
        onClick = onRequestPermissions,
        colors = ButtonDefaults.buttonColors(containerColor = VynozaPurplePrimary, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("camera_grant_permission_button"),
      ) {
        Text(stringResource(R.string.camera_studio_grant_permission))
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = onOpenSettings,
          modifier = Modifier.weight(1f).height(48.dp).testTag("camera_open_settings_button"),
        ) {
          Text(stringResource(R.string.camera_studio_open_settings), maxLines = 1)
        }
        OutlinedButton(
          onClick = onPickFromGallery,
          modifier = Modifier.weight(1f).height(48.dp).testTag("camera_permission_gallery_button"),
        ) {
          Text(stringResource(R.string.camera_studio_gallery), maxLines = 1)
        }
      }
      TextButton(
        onClick = onSwitchToText,
        modifier = Modifier.testTag("camera_permission_text_button"),
      ) {
        Text("Or create a Text post without camera", color = VynozaLavenderSoft)
      }
    }
  }
}

private fun hasPermission(context: Context, permission: String): Boolean =
  ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
  val intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.fromParts("package", context.packageName, null)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  runCatching { context.startActivity(intent) }
}

private fun formatMmSs(ms: Long): String {
  val totalSeconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return "%02d:%02d".format(minutes, seconds)
}
