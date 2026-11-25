package com.vynoza.app.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.FileProvider
import com.vynoza.app.core.update.AppReleaseInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

interface ApkInstallerPort {
  suspend fun downloadAndVerifyApk(
    context: Context,
    release: AppReleaseInfo,
    currentVersionCode: Int,
    onProgress: (downloadedBytes: Long, totalBytes: Long?, progress: Float?) -> Unit,
  ): Result<File>

  fun canRequestPackageInstalls(context: Context): Boolean

  fun launchUnknownSourcesSettings(context: Context): Result<Unit>

  fun launchPackageInstaller(context: Context, apkFile: File): Result<Unit>
}

class AndroidApkUpdateInstaller(
  private val client: OkHttpClient = defaultDownloadClient(),
) : ApkInstallerPort {

  override suspend fun downloadAndVerifyApk(
    context: Context,
    release: AppReleaseInfo,
    currentVersionCode: Int,
    onProgress: (downloadedBytes: Long, totalBytes: Long?, progress: Float?) -> Unit,
  ): Result<File> =
    withContext(Dispatchers.IO) {
      var activeCall: Call? = null
      var partFile: File? = null
      try {
        if (!release.apkDownloadUrl.startsWith("https://", ignoreCase = true)) {
          return@withContext Result.failure(
            IOException("Refusing insecure update URL. HTTPS is required.")
          )
        }

        ensureEnoughStorageForApk(context, release.apkSizeBytes).getOrThrow()

        val updatesDir = File(context.cacheDir, UPDATES_CACHE_DIR).apply { mkdirs() }
        val safeName =
          release.apkFileName
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "vynoza-update.apk" }
        val finalFile = File(updatesDir, safeName)
        partFile = File(updatesDir, "$safeName.part")
        if (partFile.exists()) partFile.delete()

        val request =
          Request.Builder()
            .url(release.apkDownloadUrl)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
            .header("User-Agent", "VYNOZA-Android")
            .get()
            .build()

        val call = client.newCall(request)
        activeCall = call

        call.execute().use { response ->
          if (!response.isSuccessful) {
            return@withContext Result.failure(
              IOException("APK download failed with HTTP ${response.code}.")
            )
          }
          val body =
            response.body
              ?: return@withContext Result.failure(IOException("APK response body was empty."))

          val headerLength = body.contentLength().takeIf { it > 0L }
          val expectedTotal = headerLength ?: release.apkSizeBytes

          body.byteStream().use { input ->
            FileOutputStream(partFile).use { output ->
              val buffer = ByteArray(BUFFER_SIZE_BYTES)
              var downloaded = 0L
              var lastProgressPct = -1

              while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                downloaded += read

                val pctFraction =
                  expectedTotal?.takeIf { it > 0L }?.let { total ->
                    (downloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                  }
                val pctInt = pctFraction?.let { (it * 100).toInt() } ?: -1
                if (pctInt != lastProgressPct || pctFraction == null) {
                  lastProgressPct = pctInt
                  onProgress(downloaded, expectedTotal, pctFraction)
                }
              }
              output.flush()
            }
          }
        }

        currentCoroutineContext().ensureActive()
        verifyDownloadedApk(
          context = context,
          apkFile = partFile,
          release = release,
          currentVersionCode = currentVersionCode,
        ).getOrThrow()

        if (finalFile.exists()) {
          finalFile.delete()
        }
        if (!partFile.renameTo(finalFile)) {
          partFile.copyTo(finalFile, overwrite = true)
          partFile.delete()
        }

        Result.success(finalFile)
      } catch (e: CancellationException) {
        runCatching { activeCall?.cancel() }
        safeDelete(partFile)
        throw e
      } catch (e: Exception) {
        safeDelete(partFile)
        Result.failure(e)
      }
    }

  override fun canRequestPackageInstalls(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.packageManager.canRequestPackageInstalls()
    } else {
      true
    }
  }

  override fun launchUnknownSourcesSettings(context: Context): Result<Unit> =
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val intent =
          Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
          ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
        context.startActivity(intent)
      }
    }

  override fun launchPackageInstaller(context: Context, apkFile: File): Result<Unit> =
    runCatching {
      if (!apkFile.exists() || apkFile.length() <= 0L) {
        throw IOException("Downloaded APK file is missing or empty.")
      }
      val apkUri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
      val intent =
        Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(apkUri, APK_MIME_TYPE)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      context.startActivity(intent)
    }

  companion object {
    private const val UPDATES_CACHE_DIR = "updates"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val BUFFER_SIZE_BYTES = 16 * 1024
    private const val MIN_FALLBACK_FREE_BYTES = 35L * 1024L * 1024L

    private fun defaultDownloadClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    internal fun ensureEnoughStorageForApk(context: Context, expectedSizeBytes: Long?): Result<Unit> {
      val required =
        maxOf(
          MIN_FALLBACK_FREE_BYTES,
          ((expectedSizeBytes ?: 0L).coerceAtMost(300L * 1024L * 1024L) * 1.25).toLong(),
        )
      val available = queryAvailableBytes(context)
      return if (available != null && available < required) {
        Result.failure(
          IOException(
            "Insufficient storage to download update (${available / (1024 * 1024)} MB free, " +
              "${required / (1024 * 1024)} MB needed)."
          )
        )
      } else {
        Result.success(Unit)
      }
    }

    internal fun verifyDownloadedApk(
      context: Context,
      apkFile: File,
      release: AppReleaseInfo,
      currentVersionCode: Int,
    ): Result<Unit> =
      runCatching {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
          throw IOException("Downloaded APK file is empty or missing.")
        }

        val expectedSize = release.apkSizeBytes
        if (expectedSize != null && expectedSize > 0L && apkFile.length() != expectedSize) {
          throw IOException(
            "Downloaded APK size mismatch (${apkFile.length()} bytes vs expected $expectedSize bytes)."
          )
        }

        // Verify ZIP/APK magic header (PK\x03\x04)
        val magic = ByteArray(4)
        FileInputStream(apkFile).use { input ->
          if (input.read(magic) < 4) {
            throw IOException("Downloaded file is too small to be an Android APK.")
          }
        }
        if (
          magic[0] != 0x50.toByte() ||
            magic[1] != 0x4B.toByte() ||
            magic[2] != 0x03.toByte() ||
            magic[3] != 0x04.toByte()
        ) {
          throw IOException("Downloaded file is not a valid APK package.")
        }

        // Verify essential Android APK entries inside archive
        ZipFile(apkFile).use { zip ->
          val manifestEntry = zip.getEntry("AndroidManifest.xml")
          val dexEntry = zip.getEntry("classes.dex")
          if (manifestEntry == null || dexEntry == null) {
            throw IOException("Downloaded archive is missing AndroidManifest.xml or classes.dex.")
          }
        }

        // Verify SHA-256 checksum if provided in release metadata
        val expectedSha256 = release.sha256
        if (!expectedSha256.isNullOrBlank()) {
          val actualSha256 = computeSha256Hex(apkFile)
          if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            throw IOException("APK SHA-256 verification failed. Aborting update for safety.")
          }
        }

        // Inspect PackageManager archive metadata when available on Android runtime
        val archiveInfo = readPackageArchiveInfo(context.packageManager, apkFile.absolutePath)
        if (archiveInfo != null) {
          if (archiveInfo.packageName != context.packageName) {
            throw IOException(
              "APK package ID (${archiveInfo.packageName}) does not match ${context.packageName}."
            )
          }
          val archiveVersionCode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
              archiveInfo.longVersionCode
            } else {
              @Suppress("DEPRECATION")
              archiveInfo.versionCode.toLong()
            }
          if (archiveVersionCode <= currentVersionCode.toLong()) {
            throw IOException(
              "Downloaded APK build ($archiveVersionCode) is not newer than installed build ($currentVersionCode)."
            )
          }
        }
      }

    internal fun computeSha256Hex(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      val buffer = ByteArray(BUFFER_SIZE_BYTES)
      FileInputStream(file).use { input ->
        while (true) {
          val read = input.read(buffer)
          if (read == -1) break
          digest.update(buffer, 0, read)
        }
      }
      return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    @Suppress("DEPRECATION")
    private fun readPackageArchiveInfo(pm: PackageManager, path: String) =
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0))
        } else {
          pm.getPackageArchiveInfo(path, 0)
        }
      } catch (_: Exception) {
        null
      }

    private fun queryAvailableBytes(context: Context): Long? {
      return try {
        val stat = StatFs(context.cacheDir.absolutePath)
        if (stat.totalBytes <= 0L) null else stat.availableBytes
      } catch (_: Exception) {
        null
      }
    }

    private fun safeDelete(file: File?) {
      if (file != null && file.exists()) {
        runCatching { file.delete() }
      }
    }
  }
}
