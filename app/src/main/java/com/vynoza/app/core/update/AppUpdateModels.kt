package com.vynoza.app.core.update

import java.io.File

data class AppReleaseInfo(
  val versionCode: Int?,
  val versionName: String,
  val tagName: String,
  val title: String,
  val releaseNotes: String,
  val apkDownloadUrl: String,
  val apkFileName: String,
  val apkSizeBytes: Long? = null,
  val sha256: String? = null,
  val publishedAt: String? = null,
  val htmlUrl: String? = null,
) {
  val formattedVersionLabel: String
    get() =
      if (versionCode != null) {
        "v$versionName (Build $versionCode)"
      } else {
        "v$versionName"
      }
}

sealed interface AppUpdateCheckResult {
  data class UpToDate(
    val currentVersionName: String,
    val currentVersionCode: Int,
    val statusMessage: String? = null,
  ) : AppUpdateCheckResult

  data class UpdateAvailable(val release: AppReleaseInfo) : AppUpdateCheckResult

  data class Error(val message: String) : AppUpdateCheckResult
}

sealed interface AppUpdateUiState {
  data object Idle : AppUpdateUiState

  data object Checking : AppUpdateUiState

  data class UpToDate(
    val currentVersionName: String,
    val currentVersionCode: Int,
    val statusMessage: String? = null,
  ) : AppUpdateUiState

  data class UpdateAvailable(val release: AppReleaseInfo) : AppUpdateUiState

  data class Downloading(
    val release: AppReleaseInfo,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val progress: Float?,
  ) : AppUpdateUiState

  data class Verifying(val release: AppReleaseInfo) : AppUpdateUiState

  data class NeedsInstallPermission(
    val release: AppReleaseInfo,
    val apkFile: File,
  ) : AppUpdateUiState

  data class ReadyToInstall(
    val release: AppReleaseInfo,
    val apkFile: File,
  ) : AppUpdateUiState

  data class Error(
    val message: String,
    val release: AppReleaseInfo? = null,
  ) : AppUpdateUiState
}

object AppVersionComparator {
  fun isUpdateNewer(
    remote: AppReleaseInfo,
    currentVersionCode: Int,
    currentVersionName: String,
  ): Boolean {
    val remoteCode = remote.versionCode
    if (remoteCode != null) {
      return remoteCode > currentVersionCode
    }
    return compareSemanticVersions(remote.versionName, currentVersionName) > 0
  }

  fun compareSemanticVersions(a: String, b: String): Int {
    val partsA = parseNumericParts(a)
    val partsB = parseNumericParts(b)
    val count = maxOf(partsA.size, partsB.size)
    for (i in 0 until count) {
      val va = partsA.getOrElse(i) { 0 }
      val vb = partsB.getOrElse(i) { 0 }
      if (va != vb) return va.compareTo(vb)
    }
    return 0
  }

  private fun parseNumericParts(raw: String): List<Int> {
    val cleaned =
      raw
        .trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('+')
        .substringBefore('-')
    return cleaned
      .split('.')
      .mapNotNull { segment ->
        val digits = segment.takeWhile { it.isDigit() }
        digits.toIntOrNull()
      }
  }
}
