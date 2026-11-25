package com.vynoza.app.ui.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vynoza.app.R
import com.vynoza.app.core.update.AppReleaseInfo
import com.vynoza.app.core.update.AppUpdateUiState
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
fun AppUpdateDialogHost(
  uiState: AppUpdateUiState,
  isDialogVisible: Boolean,
  currentVersionName: String,
  currentVersionCode: Int,
  onUpdateNow: (AppReleaseInfo) -> Unit,
  onCancelDownload: () -> Unit,
  onDismiss: () -> Unit,
  onAllowUnknownSources: () -> Unit,
  onOpenInstaller: () -> Unit,
  onRetryCheck: () -> Unit,
) {
  if (!isDialogVisible) return

  val isBusy =
    uiState is AppUpdateUiState.Checking ||
      uiState is AppUpdateUiState.Downloading ||
      uiState is AppUpdateUiState.Verifying

  AlertDialog(
    onDismissRequest = { if (!isBusy) onDismiss() },
    containerColor = VynozaSurfaceElevated,
    modifier = Modifier.testTag("app_update_dialog"),
    icon = {
      Box(
        modifier =
          Modifier
            .size(48.dp)
            .background(VynozaVioletContainer, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector =
            when (uiState) {
              is AppUpdateUiState.ReadyToInstall -> Icons.Rounded.InstallMobile
              is AppUpdateUiState.NeedsInstallPermission -> Icons.Rounded.Security
              is AppUpdateUiState.UpToDate -> Icons.Rounded.CheckCircle
              else -> Icons.Rounded.SystemUpdate
            },
          contentDescription = null,
          tint =
            if (uiState is AppUpdateUiState.ReadyToInstall || uiState is AppUpdateUiState.UpToDate) {
              VynozaSuccess
            } else {
              VynozaLavenderSoft
            },
        )
      }
    },
    title = {
      Text(
        text =
          when (uiState) {
            is AppUpdateUiState.Checking -> stringResource(R.string.update_checking)
            is AppUpdateUiState.UpToDate -> "VYNOZA is Up to Date"
            is AppUpdateUiState.NeedsInstallPermission ->
              stringResource(R.string.update_permission_title)
            is AppUpdateUiState.ReadyToInstall -> "Ready to Install"
            is AppUpdateUiState.Error -> "Update Notice"
            else -> stringResource(R.string.update_available_title)
          },
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        when (uiState) {
          AppUpdateUiState.Idle,
          AppUpdateUiState.Checking -> {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              CircularProgressIndicator(
                color = VynozaPurplePrimary,
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
              )
              Text(
                text = "Checking GitHub Releases for newer VYNOZA builds...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          is AppUpdateUiState.UpToDate -> {
            Text(
              text =
                uiState.statusMessage
                  ?: "Installed: v${uiState.currentVersionName} (Build ${uiState.currentVersionCode}).",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          is AppUpdateUiState.UpdateAvailable -> {
            ReleaseSummaryBlock(
              release = uiState.release,
              currentVersionName = currentVersionName,
              currentVersionCode = currentVersionCode,
            )
          }

          is AppUpdateUiState.Downloading -> {
            ReleaseVersionBadges(
              release = uiState.release,
              currentVersionName = currentVersionName,
              currentVersionCode = currentVersionCode,
            )
            val downloadedMb = formatMb(uiState.downloadedBytes)
            val totalMb = uiState.totalBytes?.let { formatMb(it) }
            val pctText = uiState.progress?.let { "${(it * 100).toInt()}%" } ?: "..."
            Text(
              text =
                if (totalMb != null) {
                  "Downloading APK: $downloadedMb / $totalMb ($pctText)"
                } else {
                  "Downloading APK: $downloadedMb..."
                },
              style = MaterialTheme.typography.labelLarge,
              color = VynozaLavenderSoft,
            )
            if (uiState.progress != null) {
              LinearProgressIndicator(
                progress = { uiState.progress },
                color = VynozaPurplePrimary,
                trackColor = VynozaSurfaceDeep,
                modifier = Modifier.fillMaxWidth().testTag("update_download_progress"),
              )
            } else {
              LinearProgressIndicator(
                color = VynozaPurplePrimary,
                trackColor = VynozaSurfaceDeep,
                modifier = Modifier.fillMaxWidth().testTag("update_download_progress"),
              )
            }
          }

          is AppUpdateUiState.Verifying -> {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              CircularProgressIndicator(
                color = VynozaPurplePrimary,
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
              )
              Text(
                text = stringResource(R.string.update_verifying),
                style = MaterialTheme.typography.bodyMedium,
                color = VynozaLavenderSoft,
              )
            }
          }

          is AppUpdateUiState.NeedsInstallPermission -> {
            Text(
              text = stringResource(R.string.update_permission_body),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          is AppUpdateUiState.ReadyToInstall -> {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.VerifiedUser,
                contentDescription = null,
                tint = VynozaSuccess,
                modifier = Modifier.size(20.dp),
              )
              Text(
                text = "${uiState.release.formattedVersionLabel} verified.",
                style = MaterialTheme.typography.labelLarge,
                color = VynozaSuccess,
              )
            }
            Text(
              text = stringResource(R.string.update_data_safe_note),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          is AppUpdateUiState.Error -> {
            Text(
              text = uiState.message,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
      }
    },
    confirmButton = {
      when (uiState) {
        is AppUpdateUiState.UpdateAvailable -> {
          Button(
            onClick = { onUpdateNow(uiState.release) },
            colors = ButtonDefaults.buttonColors(containerColor = VynozaPurplePrimary),
            modifier = Modifier.height(48.dp).testTag("update_now_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Download,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(stringResource(R.string.update_now), color = Color.White)
          }
        }

        is AppUpdateUiState.Downloading -> {
          TextButton(
            onClick = onCancelDownload,
            modifier = Modifier.height(48.dp).testTag("update_cancel_download_button"),
          ) {
            Text(stringResource(R.string.update_cancel_download), color = VynozaRoseAccent)
          }
        }

        is AppUpdateUiState.NeedsInstallPermission -> {
          Button(
            onClick = onAllowUnknownSources,
            colors = ButtonDefaults.buttonColors(containerColor = VynozaPurplePrimary),
            modifier = Modifier.height(48.dp).testTag("update_allow_installs_button"),
          ) {
            Text(stringResource(R.string.update_allow_installs), color = Color.White)
          }
        }

        is AppUpdateUiState.ReadyToInstall -> {
          Button(
            onClick = onOpenInstaller,
            colors = ButtonDefaults.buttonColors(containerColor = VynozaPurplePrimary),
            modifier = Modifier.height(48.dp).testTag("update_open_installer_button"),
          ) {
            Text(stringResource(R.string.update_install_now), color = Color.White)
          }
        }

        is AppUpdateUiState.Error -> {
          val retryRelease = uiState.release
          Button(
            onClick = {
              if (retryRelease != null) onUpdateNow(retryRelease) else onRetryCheck()
            },
            colors = ButtonDefaults.buttonColors(containerColor = VynozaPurplePrimary),
            modifier = Modifier.height(48.dp).testTag("update_retry_button"),
          ) {
            Text(
              text =
                if (retryRelease != null) stringResource(R.string.update_retry)
                else stringResource(R.string.retry),
              color = Color.White,
            )
          }
        }

        else -> {
          TextButton(onClick = onDismiss, enabled = !isBusy) {
            Text("OK")
          }
        }
      }
    },
    dismissButton = {
      if (
        uiState is AppUpdateUiState.UpdateAvailable ||
          uiState is AppUpdateUiState.NeedsInstallPermission ||
          uiState is AppUpdateUiState.ReadyToInstall ||
          uiState is AppUpdateUiState.Error
      ) {
        TextButton(
          onClick = onDismiss,
          modifier = Modifier.height(48.dp).testTag("update_later_button"),
        ) {
          Text(stringResource(R.string.update_later))
        }
      }
    },
  )
}

@Composable
private fun ReleaseSummaryBlock(
  release: AppReleaseInfo,
  currentVersionName: String,
  currentVersionCode: Int,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    ReleaseVersionBadges(
      release = release,
      currentVersionName = currentVersionName,
      currentVersionCode = currentVersionCode,
    )

    Surface(
      shape = RoundedCornerShape(14.dp),
      color = VynozaSurfaceDeep,
      border = BorderStroke(1.dp, VynozaBorderSubtle),
      modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp),
    ) {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = release.title,
          style = MaterialTheme.typography.labelLarge,
          color = VynozaLavenderSoft,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = release.releaseNotes,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    Text(
      text = stringResource(R.string.update_data_safe_note),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun ReleaseVersionBadges(
  release: AppReleaseInfo,
  currentVersionName: String,
  currentVersionCode: Int,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      shape = RoundedCornerShape(999.dp),
      color = VynozaVioletContainer,
    ) {
      Text(
        text = release.formattedVersionLabel,
        style = MaterialTheme.typography.labelMedium,
        color = VynozaLavenderSoft,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
      )
    }

    release.apkSizeBytes?.let { sizeBytes ->
      Surface(
        shape = RoundedCornerShape(999.dp),
        color = VynozaObsidian,
        border = BorderStroke(1.dp, VynozaBorderSubtle),
      ) {
        Text(
          text = formatMb(sizeBytes),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
    }
  }
  Text(
    text = "Installed: v$currentVersionName (Build $currentVersionCode)",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
fun ProfileAppUpdateCard(
  currentVersionName: String,
  currentVersionCode: Int,
  updateState: AppUpdateUiState,
  onCheckForUpdates: () -> Unit,
  onOpenUpdateDialog: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val availableRelease = (updateState as? AppUpdateUiState.UpdateAvailable)?.release
  val isChecking = updateState is AppUpdateUiState.Checking
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = VynozaSurfaceElevated,
    border =
      BorderStroke(
        1.dp,
        if (availableRelease != null) VynozaPurplePrimary else VynozaBorderSubtle,
      ),
    modifier = modifier.fillMaxWidth().testTag("profile_update_card"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier
            .size(40.dp)
            .background(VynozaVioletContainer, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.SystemUpdate,
          contentDescription = null,
          tint = VynozaLavenderSoft,
          modifier = Modifier.size(20.dp),
        )
      }

      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text =
            if (availableRelease != null) {
              "Update Available: ${availableRelease.formattedVersionLabel}"
            } else {
              stringResource(R.string.update_section_title)
            },
          style = MaterialTheme.typography.titleSmall,
          color =
            if (availableRelease != null) VynozaLavenderSoft
            else MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text =
            when (updateState) {
              is AppUpdateUiState.Checking -> stringResource(R.string.update_checking)
              is AppUpdateUiState.UpToDate ->
                updateState.statusMessage ?: stringResource(R.string.update_up_to_date)
              is AppUpdateUiState.UpdateAvailable -> "Tap Update to download from GitHub Releases"
              is AppUpdateUiState.Downloading -> "Downloading update..."
              is AppUpdateUiState.ReadyToInstall -> "Ready to install update"
              else -> "Installed: v$currentVersionName (Build $currentVersionCode)"
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (availableRelease != null) {
        Button(
          onClick = onOpenUpdateDialog,
          colors = ButtonDefaults.buttonColors(containerColor = VynozaPurplePrimary),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.height(48.dp).testTag("profile_open_update_button"),
        ) {
          Text(stringResource(R.string.update_now), color = Color.White)
        }
      } else {
        OutlinedButton(
          onClick = onCheckForUpdates,
          enabled = !isChecking,
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, VynozaBorderSubtle),
          modifier = Modifier.height(48.dp).testTag("profile_check_updates_button"),
        ) {
          if (isChecking) {
            CircularProgressIndicator(
              color = VynozaPurplePrimary,
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
            )
          } else {
            Icon(
              imageVector = Icons.Rounded.Refresh,
              contentDescription = null,
              tint = VynozaLavenderSoft,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text("Check", color = VynozaLavenderSoft)
          }
        }
      }
    }
  }
}

private fun formatMb(bytes: Long): String {
  val mb = bytes.toDouble() / (1024.0 * 1024.0)
  return String.format(java.util.Locale.US, "%.1f MB", mb)
}
