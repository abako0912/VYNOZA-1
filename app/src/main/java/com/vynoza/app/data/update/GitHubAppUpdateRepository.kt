package com.vynoza.app.data.update

import android.content.Context
import android.content.SharedPreferences
import com.vynoza.app.core.update.AppReleaseInfo
import com.vynoza.app.core.update.AppUpdateCheckResult
import com.vynoza.app.core.update.AppVersionComparator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

interface AppUpdateSource {
  suspend fun checkForLatestUpdate(
    context: Context,
    currentVersionCode: Int,
    currentVersionName: String,
    force: Boolean,
  ): AppUpdateCheckResult

  fun isVersionDismissed(context: Context, release: AppReleaseInfo): Boolean

  fun dismissVersion(context: Context, release: AppReleaseInfo)

  fun clearDismissedVersion(context: Context)
}

class GitHubAppUpdateRepository(
  private val client: OkHttpClient = defaultClient(),
  private val latestReleaseEndpoint: String = GITHUB_LATEST_RELEASE_URL,
  private val checkIntervalMs: Long = AUTO_CHECK_COOLDOWN_MS,
) : AppUpdateSource {

  override suspend fun checkForLatestUpdate(
    context: Context,
    currentVersionCode: Int,
    currentVersionName: String,
    force: Boolean,
  ): AppUpdateCheckResult =
    withContext(Dispatchers.IO) {
      val prefs = prefsFor(context)
      val now = System.currentTimeMillis()
      if (!force) {
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        if (lastCheck > 0L && now - lastCheck < checkIntervalMs) {
          return@withContext AppUpdateCheckResult.UpToDate(
            currentVersionName = currentVersionName,
            currentVersionCode = currentVersionCode,
          )
        }
      }

      try {
        val request =
          Request.Builder()
            .url(latestReleaseEndpoint)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "VYNOZA-Android/$currentVersionName")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
          val bodyText = response.body?.string().orEmpty()
          if (response.code == 404) {
            prefs.edit().putLong(KEY_LAST_CHECK_AT, now).apply()
            return@withContext AppUpdateCheckResult.UpToDate(
              currentVersionName = currentVersionName,
              currentVersionCode = currentVersionCode,
              statusMessage = "No published GitHub releases found yet.",
            )
          }
          if (!response.isSuccessful) {
            return@withContext AppUpdateCheckResult.Error(
              "Unable to check updates (HTTP ${response.code})."
            )
          }

          val metadataAssetUrl = extractMetadataJsonAssetUrl(bodyText)
          val metadataJson = metadataAssetUrl?.let { fetchMetadataJson(it, currentVersionName) }

          val release =
            parseLatestReleaseJson(
              releaseJsonText = bodyText,
              metadataJsonText = metadataJson,
            )
              ?: return@withContext AppUpdateCheckResult.UpToDate(
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode,
                statusMessage = "Latest release has no downloadable Android APK.",
              )

          prefs.edit().putLong(KEY_LAST_CHECK_AT, now).apply()

          if (AppVersionComparator.isUpdateNewer(release, currentVersionCode, currentVersionName)) {
            AppUpdateCheckResult.UpdateAvailable(release)
          } else {
            AppUpdateCheckResult.UpToDate(
              currentVersionName = currentVersionName,
              currentVersionCode = currentVersionCode,
            )
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: IOException) {
        AppUpdateCheckResult.Error(
          e.localizedMessage?.takeIf { it.isNotBlank() }
            ?: "Network error while checking for updates."
        )
      } catch (e: Exception) {
        AppUpdateCheckResult.Error(
          e.localizedMessage?.takeIf { it.isNotBlank() }
            ?: "Failed to read update information."
        )
      }
    }

  override fun isVersionDismissed(context: Context, release: AppReleaseInfo): Boolean {
    val prefs = prefsFor(context)
    val dismissedTag = prefs.getString(KEY_DISMISSED_TAG, null)
    val dismissedCode = prefs.getInt(KEY_DISMISSED_CODE, -1)
    return if (release.versionCode != null && dismissedCode > 0) {
      dismissedCode >= release.versionCode
    } else {
      dismissedTag != null && dismissedTag == release.tagName
    }
  }

  override fun dismissVersion(context: Context, release: AppReleaseInfo) {
    prefsFor(context)
      .edit()
      .putString(KEY_DISMISSED_TAG, release.tagName)
      .putInt(KEY_DISMISSED_CODE, release.versionCode ?: -1)
      .apply()
  }

  override fun clearDismissedVersion(context: Context) {
    prefsFor(context)
      .edit()
      .remove(KEY_DISMISSED_TAG)
      .remove(KEY_DISMISSED_CODE)
      .apply()
  }

  private fun fetchMetadataJson(url: String, currentVersionName: String): String? {
    if (!url.startsWith("https://", ignoreCase = true)) return null
    return try {
      val request =
        Request.Builder()
          .url(url)
          .header("Accept", "application/json")
          .header("User-Agent", "VYNOZA-Android/$currentVersionName")
          .get()
          .build()
      client.newCall(request).execute().use { resp ->
        if (resp.isSuccessful) resp.body?.string() else null
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun prefsFor(context: Context): SharedPreferences {
    return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }

  companion object {
    const val GITHUB_REPO_WEB_URL = "https://github.com/abako0912/VYNOZA-1"
    const val GITHUB_LATEST_RELEASE_URL =
      "https://api.github.com/repos/abako0912/VYNOZA-1/releases/latest"

    private const val PREFS_NAME = "vynoza_app_updates"
    private const val KEY_LAST_CHECK_AT = "last_check_at_ms"
    private const val KEY_DISMISSED_TAG = "dismissed_tag"
    private const val KEY_DISMISSED_CODE = "dismissed_version_code"
    private const val AUTO_CHECK_COOLDOWN_MS = 4L * 60L * 60L * 1000L

    private fun defaultClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    internal fun extractMetadataJsonAssetUrl(releaseJsonText: String): String? {
      if (releaseJsonText.isBlank()) return null
      return try {
        val root = JSONObject(releaseJsonText)
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
          val asset = assets.optJSONObject(i) ?: continue
          val name = asset.optString("name", "").trim().lowercase()
          val url = asset.optString("browser_download_url", "").trim()
          if (
            (name == "vynoza-update.json" || name == "update.json") &&
              url.startsWith("https://", ignoreCase = true)
          ) {
            return url
          }
        }
        null
      } catch (_: Exception) {
        null
      }
    }

    internal fun parseLatestReleaseJson(
      releaseJsonText: String,
      metadataJsonText: String? = null,
    ): AppReleaseInfo? {
      if (releaseJsonText.isBlank()) return null
      val root = JSONObject(releaseJsonText)
      if (root.optBoolean("draft", false)) return null

      val tagName = root.optString("tag_name", "").trim()
      val releaseTitle = root.optString("name", "").trim().ifBlank { tagName }
      val rawBody = root.optString("body", "").trim()
      val publishedAt = root.optString("published_at", "").trim().takeIf { it.isNotBlank() }
      val htmlUrl = root.optString("html_url", "").trim().takeIf { it.startsWith("https://") }

      val metaObj =
        metadataJsonText?.takeIf { it.isNotBlank() }?.let { raw ->
          runCatching { JSONObject(raw) }.getOrNull()
        }

      // Locate APK asset from GitHub release assets (or metadata override if HTTPS)
      var apkUrl: String? = null
      var apkName = "vynoza-update.apk"
      var apkSize: Long? = null

      val assets = root.optJSONArray("assets")
      if (assets != null) {
        for (i in 0 until assets.length()) {
          val asset = assets.optJSONObject(i) ?: continue
          val name = asset.optString("name", "").trim()
          val url = asset.optString("browser_download_url", "").trim()
          val contentType = asset.optString("content_type", "").trim()
          val isApk =
            name.endsWith(".apk", ignoreCase = true) ||
              contentType.equals("application/vnd.android.package-archive", ignoreCase = true)
          if (isApk && url.startsWith("https://", ignoreCase = true)) {
            apkUrl = url
            apkName = name.ifBlank { apkName }
            apkSize = asset.optLong("size", -1L).takeIf { it > 0L }
            break
          }
        }
      }

      if (apkUrl == null) {
        val metaApkUrl = metaObj?.optString("apkUrl", "")?.trim().orEmpty()
        if (metaApkUrl.startsWith("https://", ignoreCase = true)) {
          apkUrl = metaApkUrl
        }
      }
      val resolvedApkUrl = apkUrl ?: return null

      val metaVersionCode = metaObj?.optInt("versionCode", -1)?.takeIf { it > 0 }
      val resolvedVersionCode =
        metaVersionCode ?: parseVersionCode(tagName = tagName, releaseTitle = releaseTitle, body = rawBody)

      val metaVersionName = metaObj?.optString("versionName", "")?.trim()?.takeIf { it.isNotBlank() }
      val resolvedVersionName = metaVersionName ?: normalizeVersionName(tagName, releaseTitle)
      if (resolvedVersionName.isBlank()) return null

      val metaNotes = metaObj?.optString("releaseNotes", "")?.trim()?.takeIf { it.isNotBlank() }
      val cleanNotes =
        (metaNotes ?: cleanReleaseNotes(rawBody)).ifBlank {
          "Includes improvements, stability updates, and the latest VYNOZA features."
        }

      val metaSha256 =
        metaObj?.optString("sha256", "")?.trim()?.lowercase()?.takeIf { isValidSha256Hex(it) }
      val resolvedSha256 = metaSha256 ?: parseSha256(rawBody)

      val metaSize = metaObj?.optLong("apkSizeBytes", -1L)?.takeIf { it > 0L }
      val resolvedSize = apkSize ?: metaSize

      return AppReleaseInfo(
        versionCode = resolvedVersionCode,
        versionName = resolvedVersionName,
        tagName = tagName.ifBlank { "v$resolvedVersionName" },
        title = releaseTitle.ifBlank { "VYNOZA v$resolvedVersionName" },
        releaseNotes = cleanNotes,
        apkDownloadUrl = resolvedApkUrl,
        apkFileName = apkName,
        apkSizeBytes = resolvedSize,
        sha256 = resolvedSha256,
        publishedAt = publishedAt,
        htmlUrl = htmlUrl,
      )
    }

    internal fun parseVersionCode(tagName: String, releaseTitle: String, body: String): Int? {
      val bodyPatterns =
        listOf(
          Regex("""(?:versionCode|version_code|Version\s*Code)\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE),
          Regex(""""versionCode"\s*:\s*(\d+)""", RegexOption.IGNORE_CASE),
        )
      for (pattern in bodyPatterns) {
        val match = pattern.find(body) ?: pattern.find(releaseTitle)
        val parsed = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (parsed != null && parsed > 0) return parsed
      }

      val tagPatterns =
        listOf(
          Regex("""[+\-]c(\d+)$""", RegexOption.IGNORE_CASE),
          Regex("""\+(\d+)$"""),
          Regex("""-build(\d+)$""", RegexOption.IGNORE_CASE),
        )
      for (pattern in tagPatterns) {
        val match = pattern.find(tagName.trim())
        val parsed = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (parsed != null && parsed > 0) return parsed
      }
      return null
    }

    internal fun parseSha256(body: String): String? {
      val match =
        Regex("""(?:sha256|sha-256)\s*[:=]\s*([a-fA-F0-9]{64})""", RegexOption.IGNORE_CASE)
          .find(body)
      return match?.groupValues?.getOrNull(1)?.lowercase()?.takeIf { isValidSha256Hex(it) }
    }

    internal fun normalizeVersionName(tagName: String, fallbackTitle: String): String {
      val raw = tagName.ifBlank { fallbackTitle }.trim()
      val stripped =
        raw
          .removePrefix("v")
          .removePrefix("V")
          .substringBefore('+')
          .replace(Regex("""-(?:c|build)\d+$""", RegexOption.IGNORE_CASE), "")
          .substringBefore("-debug")
          .trim()
      return stripped.ifBlank { raw }
    }

    private fun cleanReleaseNotes(body: String): String {
      if (body.isBlank()) return ""
      return body
        .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .filterNot { line ->
          val trimmed = line.trim()
          trimmed.matches(Regex("""^(?:versionCode|version_code|sha256|sha-256)\s*[:=].*$""", RegexOption.IGNORE_CASE))
        }
        .joinToString("\n")
        .trim()
    }

    private fun isValidSha256Hex(value: String): Boolean {
      return value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
  }
}
