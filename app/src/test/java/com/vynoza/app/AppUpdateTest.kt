package com.vynoza.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.vynoza.app.core.update.AppReleaseInfo
import com.vynoza.app.core.update.AppUpdateCheckResult
import com.vynoza.app.core.update.AppUpdateUiState
import com.vynoza.app.core.update.AppVersionComparator
import com.vynoza.app.data.update.AndroidApkUpdateInstaller
import com.vynoza.app.data.update.ApkInstallerPort
import com.vynoza.app.data.update.AppUpdateSource
import com.vynoza.app.data.update.GitHubAppUpdateRepository
import com.vynoza.app.ui.theme.VynozaTheme
import com.vynoza.app.ui.update.AppUpdateDialogHost
import com.vynoza.app.ui.update.AppUpdateViewModel
import com.vynoza.app.ui.update.ProfileAppUpdateCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppUpdateTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun `build config and manifest declare version 1_0_7 build 8 and install permission`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("com.vynoza.app", BuildConfig.APPLICATION_ID)
    assertEquals(9, BuildConfig.VERSION_CODE)
    assertEquals("1.0.8", BuildConfig.VERSION_NAME)
    assertEquals(
      "https://github.com/abako0912/VYNOZA-1",
      GitHubAppUpdateRepository.GITHUB_REPO_WEB_URL,
    )
    assertEquals(
      "https://api.github.com/repos/abako0912/VYNOZA-1/releases/latest",
      GitHubAppUpdateRepository.GITHUB_LATEST_RELEASE_URL,
    )

    val pkgInfo =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
          context.packageName,
          PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
      } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
      }
    val permissions = pkgInfo.requestedPermissions?.toList().orEmpty()
    assertTrue(
      "Missing REQUEST_INSTALL_PACKAGES permission",
      permissions.contains(android.Manifest.permission.REQUEST_INSTALL_PACKAGES),
    )
  }

  @Test
  fun `github release parser extracts versionCode from body, tag, and metadata json`() {
    val bodyReleaseJson =
      """
      {
        "tag_name": "v1.0.7",
        "name": "VYNOZA v1.0.7",
        "draft": false,
        "html_url": "https://github.com/abako0912/VYNOZA-1/releases/tag/v1.0.7",
        "published_at": "2026-11-25T10:00:00Z",
        "body": "versionCode: 8\nsha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n- Faster feed loading\n- Bug fixes",
        "assets": [
          {
            "name": "vynoza-v1.0.7.apk",
            "size": 29500000,
            "content_type": "application/vnd.android.package-archive",
            "browser_download_url": "https://github.com/abako0912/VYNOZA-1/releases/download/v1.0.7/vynoza-v1.0.7.apk"
          }
        ]
      }
      """.trimIndent()

    val parsedFromBody = GitHubAppUpdateRepository.parseLatestReleaseJson(bodyReleaseJson)
    assertNotNull(parsedFromBody)
    assertEquals(8, parsedFromBody!!.versionCode)
    assertEquals("1.0.7", parsedFromBody.versionName)
    assertEquals("vynoza-v1.0.7.apk", parsedFromBody.apkFileName)
    assertEquals(29500000L, parsedFromBody.apkSizeBytes)
    assertEquals(
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      parsedFromBody.sha256,
    )
    assertFalse(parsedFromBody.releaseNotes.contains("versionCode:"))
    assertTrue(parsedFromBody.releaseNotes.contains("Faster feed loading"))

    val tagReleaseJson =
      """
      {
        "tag_name": "v1.0.8+9",
        "name": "VYNOZA 1.0.8",
        "draft": false,
        "body": "Creator stability update",
        "assets": [
          {
            "name": "vynoza-update.json",
            "browser_download_url": "https://github.com/abako0912/VYNOZA-1/releases/download/v1.0.8%2B9/vynoza-update.json"
          },
          {
            "name": "app-release.apk",
            "size": 30100000,
            "browser_download_url": "https://github.com/abako0912/VYNOZA-1/releases/download/v1.0.8%2B9/app-release.apk"
          }
        ]
      }
      """.trimIndent()

    val parsedFromTag = GitHubAppUpdateRepository.parseLatestReleaseJson(tagReleaseJson)
    assertNotNull(parsedFromTag)
    assertEquals(9, parsedFromTag!!.versionCode)
    assertEquals("1.0.8", parsedFromTag.versionName)
    assertEquals(
      "https://github.com/abako0912/VYNOZA-1/releases/download/v1.0.8%2B9/vynoza-update.json",
      GitHubAppUpdateRepository.extractMetadataJsonAssetUrl(tagReleaseJson),
    )

    val metadataOverrideJson =
      """
      {
        "versionCode": 12,
        "versionName": "1.1.0",
        "releaseNotes": "Metadata release notes",
        "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      }
      """.trimIndent()
    val parsedWithMeta =
      GitHubAppUpdateRepository.parseLatestReleaseJson(tagReleaseJson, metadataOverrideJson)
    assertNotNull(parsedWithMeta)
    assertEquals(12, parsedWithMeta!!.versionCode)
    assertEquals("1.1.0", parsedWithMeta.versionName)
    assertEquals("Metadata release notes", parsedWithMeta.releaseNotes)
    assertEquals(
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      parsedWithMeta.sha256,
    )
  }

  @Test
  fun `github release parser rejects drafts, insecure urls, and releases without apk`() {
    val draftJson =
      """
      {
        "tag_name": "v1.0.9",
        "draft": true,
        "assets": [
          {
            "name": "vynoza.apk",
            "browser_download_url": "https://github.com/abako0912/VYNOZA-1/releases/download/v1.0.9/vynoza.apk"
          }
        ]
      }
      """.trimIndent()
    assertNull(GitHubAppUpdateRepository.parseLatestReleaseJson(draftJson))

    val httpOnlyJson =
      """
      {
        "tag_name": "v1.0.9",
        "draft": false,
        "assets": [
          {
            "name": "vynoza.apk",
            "browser_download_url": "http://example.com/vynoza.apk"
          }
        ]
      }
      """.trimIndent()
    assertNull(GitHubAppUpdateRepository.parseLatestReleaseJson(httpOnlyJson))

    val noApkJson =
      """
      {
        "tag_name": "v1.0.9",
        "draft": false,
        "assets": [
          {
            "name": "checksums.txt",
            "browser_download_url": "https://github.com/abako0912/VYNOZA-1/releases/download/v1.0.9/checksums.txt"
          }
        ]
      }
      """.trimIndent()
    assertNull(GitHubAppUpdateRepository.parseLatestReleaseJson(noApkJson))
  }

  @Test
  fun `version comparator prioritizes versionCode and falls back to semantic versionName`() {
    val newerCode = sampleRelease(versionCode = 8, versionName = "1.0.6")
    val sameCode = sampleRelease(versionCode = 7, versionName = "1.0.9")
    val olderCode = sampleRelease(versionCode = 6, versionName = "1.0.5")
    val semanticNewer = sampleRelease(versionCode = null, versionName = "1.0.7")
    val semanticSame = sampleRelease(versionCode = null, versionName = "1.0.6")
    val semanticMajor = sampleRelease(versionCode = null, versionName = "2.0.0")

    assertTrue(AppVersionComparator.isUpdateNewer(newerCode, 7, "1.0.6"))
    assertFalse(AppVersionComparator.isUpdateNewer(sameCode, 7, "1.0.6"))
    assertFalse(AppVersionComparator.isUpdateNewer(olderCode, 7, "1.0.6"))
    assertTrue(AppVersionComparator.isUpdateNewer(semanticNewer, 7, "1.0.6"))
    assertFalse(AppVersionComparator.isUpdateNewer(semanticSame, 7, "1.0.6"))
    assertTrue(AppVersionComparator.isUpdateNewer(semanticMajor, 7, "1.9.9"))
  }

  @Test
  fun `repository handles 404 releases endpoint gracefully and enforces dismissed versions`() {
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/releases/latest") { exchange ->
        val body = """{"message":"Not Found"}""".toByteArray()
        exchange.sendResponseHeaders(404, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      server.start()
      try {
        val repo =
          GitHubAppUpdateRepository(
            latestReleaseEndpoint = "http://127.0.0.1:${server.address.port}/releases/latest",
            checkIntervalMs = 0L,
          )
        val result =
          repo.checkForLatestUpdate(
            context = context,
            currentVersionCode = 7,
            currentVersionName = "1.0.6",
            force = true,
          )
        assertTrue(result is AppUpdateCheckResult.UpToDate)
        assertEquals(
          "No published GitHub releases found yet.",
          (result as AppUpdateCheckResult.UpToDate).statusMessage,
        )

        val rel8 = sampleRelease(versionCode = 8, versionName = "1.0.7")
        val rel9 = sampleRelease(versionCode = 9, versionName = "1.0.8")
        assertFalse(repo.isVersionDismissed(context, rel8))
        repo.dismissVersion(context, rel8)
        assertTrue(repo.isVersionDismissed(context, rel8))
        assertFalse(repo.isVersionDismissed(context, rel9))
        repo.clearDismissedVersion(context)
        assertFalse(repo.isVersionDismissed(context, rel8))
      } finally {
        server.stop(0)
      }
    }
  }

  @Test
  fun `apk installer verifies valid archive, rejects tampered files, and checks storage`() {
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val tempDir = File(context.cacheDir, "test_apks").apply { mkdirs() }
      val validApk = createSyntheticApk(File(tempDir, "valid.apk"))
      val validSha = AndroidApkUpdateInstaller.computeSha256Hex(validApk)

      val validRelease =
        sampleRelease(
          versionCode = 8,
          versionName = "1.0.7",
          apkSizeBytes = validApk.length(),
          sha256 = validSha,
        )
      assertTrue(
        AndroidApkUpdateInstaller.verifyDownloadedApk(
          context = context,
          apkFile = validApk,
          release = validRelease,
          currentVersionCode = 7,
        ).isSuccess
      )

      // Size mismatch
      assertTrue(
        AndroidApkUpdateInstaller.verifyDownloadedApk(
          context = context,
          apkFile = validApk,
          release = validRelease.copy(apkSizeBytes = validApk.length() + 100L),
          currentVersionCode = 7,
        ).isFailure
      )

      // SHA-256 mismatch
      assertTrue(
        AndroidApkUpdateInstaller.verifyDownloadedApk(
          context = context,
          apkFile = validApk,
          release = validRelease.copy(sha256 = "f".repeat(64)),
          currentVersionCode = 7,
        ).isFailure
      )

      // Corrupt non-ZIP payload
      val htmlFile = File(tempDir, "corrupt.apk").apply { writeText("<html>Error</html>") }
      assertTrue(
        AndroidApkUpdateInstaller.verifyDownloadedApk(
          context = context,
          apkFile = htmlFile,
          release = sampleRelease(versionCode = 8, versionName = "1.0.7"),
          currentVersionCode = 7,
        ).isFailure
      )

      // ZIP missing classes.dex
      val incompleteZip = createSyntheticApk(File(tempDir, "incomplete.apk"), includeDex = false)
      assertTrue(
        AndroidApkUpdateInstaller.verifyDownloadedApk(
          context = context,
          apkFile = incompleteZip,
          release = sampleRelease(versionCode = 8, versionName = "1.0.7"),
          currentVersionCode = 7,
        ).isFailure
      )

      // Refuses non-HTTPS download URL
      val installer = AndroidApkUpdateInstaller()
      val httpDownload =
        installer.downloadAndVerifyApk(
          context = context,
          release =
            sampleRelease(
              versionCode = 8,
              versionName = "1.0.7",
              apkDownloadUrl = "http://insecure.example.com/vynoza.apk",
            ),
          currentVersionCode = 7,
        ) { _, _, _ -> }
      assertTrue(httpDownload.isFailure)

      // Rejects low storage via ShadowStatFs
      try {
        ShadowStatFs.registerStats(context.cacheDir.absolutePath, 1000, 100, 4096)
        val storageCheck =
          AndroidApkUpdateInstaller.ensureEnoughStorageForApk(context, 30L * 1024L * 1024L)
        assertTrue(storageCheck.isFailure)
      } finally {
        ShadowStatFs.reset()
      }

      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `apk installer launches FileProvider package installer and unknown sources settings`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
    val apkFile = createSyntheticApk(File(updatesDir, "vynoza-1.0.7.apk"))
    val installer = AndroidApkUpdateInstaller()

    val launchResult = installer.launchPackageInstaller(context, apkFile)
    assertTrue(launchResult.isSuccess)

    val appShadow = Shadows.shadowOf(RuntimeEnvironment.getApplication())
    val installIntent = appShadow.nextStartedActivity
    assertNotNull(installIntent)
    assertEquals(Intent.ACTION_VIEW, installIntent.action)
    assertEquals("application/vnd.android.package-archive", installIntent.type)
    assertEquals("content", installIntent.data?.scheme)
    assertEquals("com.vynoza.app.fileprovider", installIntent.data?.authority)
    assertTrue(
      (installIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
    )

    val settingsResult = installer.launchUnknownSourcesSettings(context)
    assertTrue(settingsResult.isSuccess)
    val settingsIntent = appShadow.nextStartedActivity
    assertNotNull(settingsIntent)
    assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settingsIntent.action)
    assertEquals("package:com.vynoza.app", settingsIntent.dataString)

    apkFile.delete()
  }

  @Test
  fun `update dialog displays details, handles later dismissal, download progress, cancellation, and permission resume`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val release =
      sampleRelease(
        versionCode = 8,
        versionName = "1.0.7",
        releaseNotes = "- Faster video feed\n- Improved templates preview",
        apkSizeBytes = 29_500_000L,
      )

    val fakeSource = FakeAppUpdateSource(AppUpdateCheckResult.UpdateAvailable(release))
    val gate = CompletableDeferred<Result<File>>()
    val fakeApk = File(context.cacheDir, "updates/fake-update.apk").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
    }
    val fakeInstaller =
      FakeApkInstaller(
        canInstall = false,
        downloadGate = gate,
      )
    val vm =
      AppUpdateViewModel(
        updateRepository = fakeSource,
        apkInstaller = fakeInstaller,
        currentVersionCode = 7,
        currentVersionName = "1.0.6",
      )

    composeTestRule.setContent {
      val state by vm.uiState.collectAsState()
      val visible by vm.isDialogVisible.collectAsState()
      VynozaTheme {
        AppUpdateDialogHost(
          uiState = state,
          isDialogVisible = visible,
          currentVersionName = vm.currentVersionName,
          currentVersionCode = vm.currentVersionCode,
          onUpdateNow = { vm.startDownloadAndInstall(context, it) },
          onCancelDownload = { vm.cancelDownload() },
          onDismiss = { vm.dismissUpdateDialog(context) },
          onAllowUnknownSources = { vm.requestInstallPermission(context) },
          onOpenInstaller = { vm.launchInstaller(context) },
          onRetryCheck = { vm.checkForUpdates(context, force = true) },
        )
      }
    }

    // Trigger startup check -> Update Available dialog appears
    vm.checkForUpdates(context, force = false)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("app_update_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithText("v1.0.7 (Build 8)").assertIsDisplayed()
    composeTestRule.onNodeWithText("Faster video feed", substring = true).assertIsDisplayed()

    // Tap Later -> dialog closes and version is remembered as dismissed
    composeTestRule.onNodeWithTag("update_later_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("app_update_dialog").assertDoesNotExist()
    assertTrue(fakeSource.isVersionDismissed(context, release))

    // Non-forced re-check stays quiet because v8 was dismissed
    vm.checkForUpdates(context, force = false)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("app_update_dialog").assertDoesNotExist()

    // Forced check re-opens dialog
    vm.checkForUpdates(context, force = true)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("app_update_dialog").assertIsDisplayed()

    // Tap Update Now -> enters Downloading state with progress bar and Cancel button
    composeTestRule.onNodeWithTag("update_now_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("update_download_progress").assertIsDisplayed()
    composeTestRule.onNodeWithTag("update_cancel_download_button").assertIsDisplayed()

    // Cancel Download -> cleanly returns to UpdateAvailable
    composeTestRule.onNodeWithTag("update_cancel_download_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("update_now_button").assertIsDisplayed()

    // Start second download and complete it while install permission is denied
    val secondGate = CompletableDeferred<Result<File>>()
    fakeInstaller.downloadGate = secondGate
    composeTestRule.onNodeWithTag("update_now_button").performClick()
    composeTestRule.waitForIdle()
    secondGate.complete(Result.success(fakeApk))
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("update_allow_installs_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("update_allow_installs_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(fakeInstaller.settingsOpened)

    // User grants unknown-sources permission and returns to VYNOZA
    fakeInstaller.canInstall = true
    vm.onAppResumed(context)
    composeTestRule.waitForIdle()

    assertEquals(fakeApk, fakeInstaller.launchedApk)
    composeTestRule.onNodeWithTag("update_open_installer_button").assertIsDisplayed()
  }

  @Test
  fun `profile update card shows current build and switches between Check and Update Now`() {
    val release = sampleRelease(versionCode = 8, versionName = "1.0.7")
    var checkClicked = false
    var openClicked = false
    val uiState = androidx.compose.runtime.mutableStateOf<AppUpdateUiState>(AppUpdateUiState.Idle)

    composeTestRule.setContent {
      VynozaTheme {
        ProfileAppUpdateCard(
          currentVersionName = "1.0.6",
          currentVersionCode = 7,
          updateState = uiState.value,
          onCheckForUpdates = { checkClicked = true },
          onOpenUpdateDialog = { openClicked = true },
        )
      }
    }

    composeTestRule.onNodeWithTag("profile_update_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("Installed: v1.0.6 (Build 7)").assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_check_updates_button").performClick()
    assertTrue(checkClicked)

    uiState.value = AppUpdateUiState.UpdateAvailable(release)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("profile_open_update_button").assertIsDisplayed()
    composeTestRule
      .onNodeWithText("Update Available: v1.0.7 (Build 8)", substring = true)
      .assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_open_update_button").performClick()
    assertTrue(openClicked)
  }

  private fun sampleRelease(
    versionCode: Int?,
    versionName: String,
    releaseNotes: String = "Release notes",
    apkDownloadUrl: String =
      "https://github.com/abako0912/VYNOZA-1/releases/download/v$versionName/vynoza-v$versionName.apk",
    apkSizeBytes: Long? = null,
    sha256: String? = null,
  ) =
    AppReleaseInfo(
      versionCode = versionCode,
      versionName = versionName,
      tagName = "v$versionName",
      title = "VYNOZA v$versionName",
      releaseNotes = releaseNotes,
      apkDownloadUrl = apkDownloadUrl,
      apkFileName = "vynoza-v$versionName.apk",
      apkSizeBytes = apkSizeBytes,
      sha256 = sha256,
    )

  private fun createSyntheticApk(target: File, includeDex: Boolean = true): File {
    ZipOutputStream(FileOutputStream(target)).use { zip ->
      zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
      zip.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
      zip.closeEntry()
      if (includeDex) {
        zip.putNextEntry(ZipEntry("classes.dex"))
        zip.write(byteArrayOf(0x64, 0x65, 0x78, 0x0A))
        zip.closeEntry()
      }
    }
    return target
  }

  private class FakeAppUpdateSource(
    private val nextResult: AppUpdateCheckResult,
  ) : AppUpdateSource {
    private var dismissedCode: Int? = null
    private var dismissedTag: String? = null

    override suspend fun checkForLatestUpdate(
      context: Context,
      currentVersionCode: Int,
      currentVersionName: String,
      force: Boolean,
    ): AppUpdateCheckResult = nextResult

    override fun isVersionDismissed(context: Context, release: AppReleaseInfo): Boolean {
      return (release.versionCode != null && dismissedCode == release.versionCode) ||
        dismissedTag == release.tagName
    }

    override fun dismissVersion(context: Context, release: AppReleaseInfo) {
      dismissedCode = release.versionCode
      dismissedTag = release.tagName
    }

    override fun clearDismissedVersion(context: Context) {
      dismissedCode = null
      dismissedTag = null
    }
  }

  private class FakeApkInstaller(
    var canInstall: Boolean,
    var downloadGate: CompletableDeferred<Result<File>>,
  ) : ApkInstallerPort {
    var settingsOpened = false
    var launchedApk: File? = null

    override suspend fun downloadAndVerifyApk(
      context: Context,
      release: AppReleaseInfo,
      currentVersionCode: Int,
      onProgress: (downloadedBytes: Long, totalBytes: Long?, progress: Float?) -> Unit,
    ): Result<File> {
      onProgress(5_000_000L, release.apkSizeBytes ?: 29_500_000L, 0.25f)
      return downloadGate.await()
    }

    override fun canRequestPackageInstalls(context: Context): Boolean = canInstall

    override fun launchUnknownSourcesSettings(context: Context): Result<Unit> {
      settingsOpened = true
      return Result.success(Unit)
    }

    override fun launchPackageInstaller(context: Context, apkFile: File): Result<Unit> {
      launchedApk = apkFile
      return Result.success(Unit)
    }
  }
}
