package com.vynoza.app.ui.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vynoza.app.BuildConfig
import com.vynoza.app.core.update.AppReleaseInfo
import com.vynoza.app.core.update.AppUpdateCheckResult
import com.vynoza.app.core.update.AppUpdateUiState
import com.vynoza.app.data.update.AndroidApkUpdateInstaller
import com.vynoza.app.data.update.ApkInstallerPort
import com.vynoza.app.data.update.AppUpdateSource
import com.vynoza.app.data.update.GitHubAppUpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppUpdateViewModel(
  private val updateRepository: AppUpdateSource = GitHubAppUpdateRepository(),
  private val apkInstaller: ApkInstallerPort = AndroidApkUpdateInstaller(),
  val currentVersionCode: Int = BuildConfig.VERSION_CODE,
  val currentVersionName: String = BuildConfig.VERSION_NAME,
) : ViewModel() {

  private val _uiState = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
  val uiState: StateFlow<AppUpdateUiState> = _uiState.asStateFlow()

  private val _isDialogVisible = MutableStateFlow(false)
  val isDialogVisible: StateFlow<Boolean> = _isDialogVisible.asStateFlow()

  private var checkJob: Job? = null
  private var downloadJob: Job? = null

  fun checkForUpdates(context: Context, force: Boolean = false) {
    val active = _uiState.value
    if (
      active is AppUpdateUiState.Checking ||
        active is AppUpdateUiState.Downloading ||
        active is AppUpdateUiState.Verifying
    ) {
      return
    }
    val appCtx = context.applicationContext
    checkJob?.cancel()
    checkJob =
      viewModelScope.launch {
        if (force) {
          _uiState.value = AppUpdateUiState.Checking
        }
        when (
          val result =
            updateRepository.checkForLatestUpdate(
              context = appCtx,
              currentVersionCode = currentVersionCode,
              currentVersionName = currentVersionName,
              force = force,
            )
        ) {
          is AppUpdateCheckResult.UpdateAvailable -> {
            _uiState.value = AppUpdateUiState.UpdateAvailable(result.release)
            val shouldAutoPrompt = force || !updateRepository.isVersionDismissed(appCtx, result.release)
            if (shouldAutoPrompt) {
              _isDialogVisible.value = true
            }
          }

          is AppUpdateCheckResult.UpToDate -> {
            _uiState.value =
              if (force) {
                AppUpdateUiState.UpToDate(
                  currentVersionName = result.currentVersionName,
                  currentVersionCode = result.currentVersionCode,
                  statusMessage = result.statusMessage,
                )
              } else {
                AppUpdateUiState.Idle
              }
          }

          is AppUpdateCheckResult.Error -> {
            _uiState.value =
              if (force) {
                AppUpdateUiState.Error(message = result.message, release = null)
              } else {
                AppUpdateUiState.Idle
              }
            if (force) {
              _isDialogVisible.value = true
            }
          }
        }
      }
  }

  fun openUpdateDialog() {
    if (_uiState.value !is AppUpdateUiState.Idle) {
      _isDialogVisible.value = true
    }
  }

  fun dismissUpdateDialog(context: Context) {
    val current = _uiState.value
    if (current is AppUpdateUiState.Downloading || current is AppUpdateUiState.Verifying) {
      return
    }
    if (current is AppUpdateUiState.UpdateAvailable) {
      updateRepository.dismissVersion(context.applicationContext, current.release)
    } else if (current is AppUpdateUiState.Error && current.release == null) {
      _uiState.value = AppUpdateUiState.Idle
    }
    _isDialogVisible.value = false
  }

  fun startDownloadAndInstall(context: Context, releaseOverride: AppReleaseInfo? = null) {
    val targetRelease =
      releaseOverride
        ?: when (val state = _uiState.value) {
          is AppUpdateUiState.UpdateAvailable -> state.release
          is AppUpdateUiState.Error -> state.release
          else -> null
        }
        ?: return

    if (
      _uiState.value is AppUpdateUiState.Downloading ||
        _uiState.value is AppUpdateUiState.Verifying
    ) {
      return
    }

    val appCtx = context.applicationContext
    _isDialogVisible.value = true
    updateRepository.clearDismissedVersion(appCtx)

    downloadJob =
      viewModelScope.launch {
        _uiState.value =
          AppUpdateUiState.Downloading(
            release = targetRelease,
            downloadedBytes = 0L,
            totalBytes = targetRelease.apkSizeBytes,
            progress = 0f,
          )
        try {
          val downloadResult =
            apkInstaller.downloadAndVerifyApk(
              context = appCtx,
              release = targetRelease,
              currentVersionCode = currentVersionCode,
            ) { downloaded, total, progress ->
              val isDone = progress != null && progress >= 0.999f
              _uiState.value =
                if (isDone) {
                  AppUpdateUiState.Verifying(targetRelease)
                } else {
                  AppUpdateUiState.Downloading(
                    release = targetRelease,
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    progress = progress,
                  )
                }
            }

          downloadResult
            .onSuccess { apkFile ->
              if (apkInstaller.canRequestPackageInstalls(appCtx)) {
                _uiState.value = AppUpdateUiState.ReadyToInstall(targetRelease, apkFile)
                apkInstaller.launchPackageInstaller(appCtx, apkFile).onFailure { err ->
                  _uiState.value =
                    AppUpdateUiState.Error(
                      message = err.localizedMessage ?: "Unable to open Android package installer.",
                      release = targetRelease,
                    )
                }
              } else {
                _uiState.value = AppUpdateUiState.NeedsInstallPermission(targetRelease, apkFile)
              }
            }
            .onFailure { err ->
              _uiState.value =
                AppUpdateUiState.Error(
                  message = err.localizedMessage ?: "Failed to download or verify VYNOZA update.",
                  release = targetRelease,
                )
            }
        } catch (e: CancellationException) {
          _uiState.value = AppUpdateUiState.UpdateAvailable(targetRelease)
          throw e
        }
      }
  }

  fun cancelDownload() {
    val active = _uiState.value
    downloadJob?.cancel()
    downloadJob = null
    if (active is AppUpdateUiState.Downloading) {
      _uiState.value = AppUpdateUiState.UpdateAvailable(active.release)
    } else if (active is AppUpdateUiState.Verifying) {
      _uiState.value = AppUpdateUiState.UpdateAvailable(active.release)
    }
  }

  fun requestInstallPermission(context: Context) {
    val appCtx = context.applicationContext
    apkInstaller.launchUnknownSourcesSettings(appCtx).onFailure { err ->
      val currentRelease = (_uiState.value as? AppUpdateUiState.NeedsInstallPermission)?.release
      _uiState.value =
        AppUpdateUiState.Error(
          message = err.localizedMessage ?: "Unable to open Android install permission settings.",
          release = currentRelease,
        )
    }
  }

  fun onAppResumed(context: Context) {
    val state = _uiState.value
    if (state is AppUpdateUiState.NeedsInstallPermission) {
      val appCtx = context.applicationContext
      if (apkInstaller.canRequestPackageInstalls(appCtx)) {
        _uiState.value = AppUpdateUiState.ReadyToInstall(state.release, state.apkFile)
        apkInstaller.launchPackageInstaller(appCtx, state.apkFile).onFailure { err ->
          _uiState.value =
            AppUpdateUiState.Error(
              message = err.localizedMessage ?: "Unable to open Android package installer.",
              release = state.release,
            )
        }
      }
    }
  }

  fun launchInstaller(context: Context) {
    val state = _uiState.value
    if (state is AppUpdateUiState.ReadyToInstall) {
      val appCtx = context.applicationContext
      if (!apkInstaller.canRequestPackageInstalls(appCtx)) {
        _uiState.value = AppUpdateUiState.NeedsInstallPermission(state.release, state.apkFile)
        return
      }
      apkInstaller.launchPackageInstaller(appCtx, state.apkFile).onFailure { err ->
        _uiState.value =
          AppUpdateUiState.Error(
            message = err.localizedMessage ?: "Unable to open Android package installer.",
            release = state.release,
          )
      }
    }
  }
}
