package com.vynoza.app.ui.create

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vynoza.app.R
import com.vynoza.app.data.service.ClipSource
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaSuccess
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import com.vynoza.app.ui.theme.VynozaVioletContainer
import java.io.File

@Composable
fun CreateScreen(
  viewModel: CreateViewModel,
  onNavigateToHome: () -> Unit = {},
  onOpenTemplatesStudio: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

  // Zero-permission Google Play compliant gallery video picker
  val galleryPickerLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
      if (uri != null) {
        viewModel.onClipAcquired(context, uri, ClipSource.GALLERY_PICKER)
      }
    }

  val videoCaptureLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.CaptureVideo(),
    ) { success ->
      val captured = pendingCaptureUri
      if (success && captured != null) {
        viewModel.onClipAcquired(context, captured, ClipSource.CAMERA_CAPTURE)
      }
    }

  val cameraPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
      val cameraGranted = grants[Manifest.permission.CAMERA] == true
      val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true
      if (cameraGranted && audioGranted) {
        val outputUri = createTempVideoUri(context)
        pendingCaptureUri = outputUri
        videoCaptureLauncher.launch(outputUri)
      } else {
        viewModel.onPermissionDenied()
      }
    }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(VynozaObsidian)
      .statusBarsPadding()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 20.dp, vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = stringResource(R.string.create_title),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
      )
      Text(
        text = stringResource(R.string.create_subtitle),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      CreateActionCard(
        icon = Icons.Rounded.Videocam,
        title = stringResource(R.string.create_record_camera),
        description = "Launch the native camera recorder using runtime camera & audio permissions.",
        isPrimary = true,
        enabled = !uiState.isPublishing,
        buttonTag = "create_record_button",
        onClick = {
          val hasCamera =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
              PackageManager.PERMISSION_GRANTED
          val hasAudio =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
              PackageManager.PERMISSION_GRANTED
          if (hasCamera && hasAudio) {
            val outputUri = createTempVideoUri(context)
            pendingCaptureUri = outputUri
            videoCaptureLauncher.launch(outputUri)
          } else {
            cameraPermissionLauncher.launch(
              arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
          }
        },
      )

      CreateActionCard(
        icon = Icons.Rounded.PhotoLibrary,
        title = stringResource(R.string.create_pick_gallery),
        description = "Choose a video privately using the zero-permission Android Photo Picker.",
        isPrimary = false,
        enabled = !uiState.isPublishing,
        buttonTag = "create_gallery_button",
        onClick = {
          galleryPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
          )
        },
      )

      CreateActionCard(
        icon = Icons.Rounded.AutoAwesome,
        title = stringResource(R.string.templates_studio_entry_title),
        description = stringResource(R.string.templates_studio_entry_subtitle),
        isPrimary = false,
        enabled = !uiState.isPublishing,
        buttonTag = "create_templates_studio_button",
        onClick = onOpenTemplatesStudio,
      )

      if (uiState.permissionDeniedNotice) {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
        ) {
          Text(
            text = stringResource(R.string.create_permission_rationale),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
          )
        }
      }

      val clip = uiState.selectedClip
      if (clip != null) {
        Surface(
          shape = RoundedCornerShape(20.dp),
          color = VynozaSurfaceElevated,
          border = BorderStroke(1.dp, VynozaPurplePrimary.copy(alpha = 0.6f)),
          modifier = Modifier.fillMaxWidth().testTag("selected_clip_card"),
        ) {
          Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector =
                  if (uiState.validationError == null) Icons.Rounded.CheckCircle
                  else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint =
                  if (uiState.validationError == null) VynozaSuccess
                  else MaterialTheme.colorScheme.error,
              )
              Text(
                text =
                  if (uiState.validationError == null) "Local Clip Ready"
                  else "Clip Validation Issue",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(
                text = "File: ${clip.displayName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              clip.sizeBytes?.let { bytes ->
                Text(
                  text = "Size: ${formatByteSize(bytes)}",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              val sourceLabel =
                when (clip.source) {
                  ClipSource.CAMERA_CAPTURE -> "Camera Recording"
                  ClipSource.GALLERY_PICKER -> "Android Photo Picker"
                  ClipSource.TEMPLATE_STUDIO -> "Templates Studio Export"
                }
              Text(
                text = "Source: $sourceLabel",
                style = MaterialTheme.typography.labelLarge,
                color = VynozaLavenderSoft,
              )
            }

            if (uiState.validationError != null) {
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
              ) {
                Text(
                  text = uiState.validationError.orEmpty(),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  modifier = Modifier.padding(12.dp),
                )
              }
            } else {
              OutlinedTextField(
                value = uiState.caption,
                onValueChange = viewModel::onCaptionChanged,
                enabled = !uiState.isPublishing && uiState.publishState !is PublishPostState.Success,
                label = { Text("Caption") },
                placeholder = { Text(stringResource(R.string.create_caption_hint)) },
                supportingText = {
                  Text("${uiState.caption.length}/300")
                },
                maxLines = 3,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedContainerColor = VynozaSurfaceDeep,
                  unfocusedContainerColor = VynozaSurfaceDeep,
                  focusedBorderColor = VynozaPurplePrimary,
                  unfocusedBorderColor = VynozaBorderSubtle,
                ),
                modifier = Modifier.fillMaxWidth().testTag("create_caption_input"),
              )

              Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                VisibilityChip(
                  label = stringResource(R.string.create_visibility_public),
                  selected = uiState.visibility == "public",
                  enabled = !uiState.isPublishing && uiState.publishState !is PublishPostState.Success,
                  onClick = { viewModel.onVisibilitySelected("public") },
                  modifier = Modifier.testTag("create_visibility_public"),
                )
                VisibilityChip(
                  label = stringResource(R.string.create_visibility_private),
                  selected = uiState.visibility == "private",
                  enabled = !uiState.isPublishing && uiState.publishState !is PublishPostState.Success,
                  onClick = { viewModel.onVisibilitySelected("private") },
                  modifier = Modifier.testTag("create_visibility_private"),
                )
              }
            }

            when (val publishState = uiState.publishState) {
              is PublishPostState.Uploading -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  val progress = publishState.progress
                  if (progress != null) {
                    LinearProgressIndicator(
                      progress = { progress },
                      color = VynozaPurplePrimary,
                      trackColor = VynozaSurfaceDeep,
                      modifier = Modifier.fillMaxWidth().testTag("create_upload_progress"),
                    )
                    Text(
                      text = "Uploading video to Cloudinary... ${(progress * 100).toInt()}%",
                      style = MaterialTheme.typography.labelMedium,
                      color = VynozaLavenderSoft,
                    )
                  } else {
                    LinearProgressIndicator(
                      color = VynozaPurplePrimary,
                      trackColor = VynozaSurfaceDeep,
                      modifier = Modifier.fillMaxWidth().testTag("create_upload_progress"),
                    )
                    Text(
                      text = "Uploading video to Cloudinary...",
                      style = MaterialTheme.typography.labelMedium,
                      color = VynozaLavenderSoft,
                    )
                  }
                }
              }

              PublishPostState.SavingPost -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  LinearProgressIndicator(
                    color = VynozaPurplePrimary,
                    trackColor = VynozaSurfaceDeep,
                    modifier = Modifier.fillMaxWidth(),
                  )
                  Text(
                    text = "Saving post metadata to Cloud Firestore...",
                    style = MaterialTheme.typography.labelMedium,
                    color = VynozaLavenderSoft,
                  )
                }
              }

              is PublishPostState.Error -> {
                Surface(
                  shape = RoundedCornerShape(12.dp),
                  color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                  border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                  modifier = Modifier.fillMaxWidth().testTag("create_error_banner"),
                ) {
                  Text(
                    text = publishState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                  )
                }
              }

              is PublishPostState.Success -> {
                Surface(
                  shape = RoundedCornerShape(12.dp),
                  color = VynozaSuccess.copy(alpha = 0.14f),
                  border = BorderStroke(1.dp, VynozaSuccess.copy(alpha = 0.5f)),
                  modifier = Modifier.fillMaxWidth().testTag("create_success_banner"),
                ) {
                  Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Text(
                      text = "Video uploaded and published to VYNOZA!",
                      style = MaterialTheme.typography.titleSmall,
                      color = VynozaSuccess,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                      Button(
                        onClick = onNavigateToHome,
                        colors = ButtonDefaults.buttonColors(containerColor = VynozaPurplePrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("create_watch_feed_button"),
                      ) {
                        Text("Watch in Feed")
                      }
                      OutlinedButton(
                        onClick = viewModel::clearSelection,
                        shape = RoundedCornerShape(10.dp),
                      ) {
                        Text("Post Another")
                      }
                    }
                  }
                }
              }

              PublishPostState.Idle -> Unit
            }

            if (uiState.publishState !is PublishPostState.Success) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                if (uiState.validationError == null) {
                  Button(
                    onClick = { viewModel.publishSelectedClip(context) },
                    enabled = !uiState.isPublishing,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                      containerColor = VynozaPurplePrimary,
                      contentColor = Color.White,
                    ),
                    modifier = Modifier.weight(1f).height(48.dp).testTag("create_publish_button"),
                  ) {
                    if (uiState.isPublishing) {
                      CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                      )
                    } else {
                      Text(stringResource(R.string.create_publish_cta))
                    }
                  }
                }

                TextButton(
                  onClick = viewModel::clearSelection,
                  enabled = !uiState.isPublishing,
                ) {
                  Text("Clear")
                }
              }
            }
          }
        }
      }

      Surface(
        shape = RoundedCornerShape(18.dp),
        color = VynozaSurfaceElevated.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, VynozaBorderSubtle),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.padding(16.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.Top,
        ) {
          Box(
            modifier = Modifier
              .size(38.dp)
              .background(VynozaVioletContainer, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Rounded.CloudUpload,
              contentDescription = null,
              tint = VynozaLavenderSoft,
              modifier = Modifier.size(20.dp),
            )
          }
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              text = stringResource(R.string.create_storage_notice_title),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = stringResource(R.string.create_storage_notice_body),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text =
                "Cloud: ${com.vynoza.app.data.service.CloudinaryMediaStorageService.CLOUDINARY_CLOUD_NAME}" +
                  " • Preset: ${com.vynoza.app.data.service.CloudinaryMediaStorageService.CLOUDINARY_UPLOAD_PRESET}" +
                  " • Folder: ${com.vynoza.app.data.service.CloudinaryMediaStorageService.CLOUDINARY_UPLOAD_FOLDER}",
              style = MaterialTheme.typography.labelMedium,
              color = VynozaLavenderSoft,
              modifier = Modifier.testTag("create_cloudinary_target"),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun VisibilityChip(
  label: String,
  selected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
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
    modifier = modifier,
  )
}

@Composable
private fun CreateActionCard(
  icon: ImageVector,
  title: String,
  description: String,
  isPrimary: Boolean,
  enabled: Boolean,
  buttonTag: String,
  onClick: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(22.dp),
    color = VynozaSurfaceElevated,
    border = BorderStroke(1.dp, VynozaBorderSubtle),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Box(
          modifier = Modifier
            .size(48.dp)
            .background(VynozaVioletContainer, RoundedCornerShape(14.dp)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint = VynozaLavenderSoft,
            modifier = Modifier.size(24.dp),
          )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (isPrimary) {
        Button(
          onClick = onClick,
          enabled = enabled,
          shape = RoundedCornerShape(14.dp),
          colors = ButtonDefaults.buttonColors(containerColor = VynozaPurplePrimary),
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag(buttonTag),
        ) {
          Text(title, style = MaterialTheme.typography.labelLarge)
        }
      } else {
        OutlinedButton(
          onClick = onClick,
          enabled = enabled,
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, VynozaPurplePrimary),
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag(buttonTag),
        ) {
          Text(title, style = MaterialTheme.typography.labelLarge, color = VynozaLavenderSoft)
        }
      }
    }
  }
}

private fun createTempVideoUri(context: Context): Uri {
  val dir = File(context.cacheDir, "videos").apply { mkdirs() }
  val videoFile = File(dir, "vynoza_${System.currentTimeMillis()}.mp4")
  return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", videoFile)
}

private fun formatByteSize(bytes: Long): String {
  if (bytes < 1024L) return "$bytes B"
  val kb = bytes / 1024.0
  if (kb < 1024.0) return String.format("%.1f KB", kb)
  val mb = kb / 1024.0
  return String.format("%.1f MB", mb)
}
