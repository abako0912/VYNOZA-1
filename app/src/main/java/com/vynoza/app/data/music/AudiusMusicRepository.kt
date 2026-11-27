package com.vynoza.app.data.music

import com.vynoza.app.BuildConfig
import com.vynoza.app.core.studio.MusicTrack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class AudiusDiscoveryCategory(
  val label: String,
  val apiGenre: String?,
) {
  ALL("All", null),
  ELECTRONIC("Electronic", "Electronic"),
  HIP_HOP("Hip-Hop/Rap", "Hip-Hop/Rap"),
  POP("Pop", "Pop"),
  RNB("R&B/Soul", "R&B/Soul"),
  DANCE("Dance", "Dance"),
  AMBIENT("Ambient", "Ambient"),
  WORLD("World", "World"),
}

interface MusicRepository {
  suspend fun getTrendingTracks(
    genre: String? = null,
    timeWindow: String = "week",
    limit: Int = 25,
  ): Result<List<MusicTrack>>

  suspend fun getRecommendedTracks(
    preferredGenres: List<String> = emptyList(),
    limit: Int = 25,
  ): Result<List<MusicTrack>>

  suspend fun searchTracks(
    query: String,
    limit: Int = 25,
  ): Result<List<MusicTrack>>

  suspend fun verifyTrackStreamable(trackId: String): Result<MusicTrack>
}

class AudiusTrackUnavailableException(message: String) : IOException(message)

class AudiusMusicRepository(
  private val baseUrl: String = DEFAULT_BASE_URL,
  private val appName: String = DEFAULT_APP_NAME,
  apiKey: String? = resolveConfiguredApiKey(),
  private val httpClient: OkHttpClient = defaultHttpClient(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MusicRepository {

  private val normalizedBaseUrl: String = baseUrl.trimEnd('/')
  private val effectiveApiKey: String? = sanitizeApiKey(apiKey)

  override suspend fun getTrendingTracks(
    genre: String?,
    timeWindow: String,
    limit: Int,
  ): Result<List<MusicTrack>> =
    withContext(ioDispatcher) {
      runCatching {
        val url =
          buildUrl("tracks/trending") {
            addQueryParameter("time", timeWindow.ifBlank { "week" })
            addQueryParameter("limit", limit.coerceIn(1, 50).toString())
            if (!genre.isNullOrBlank()) {
              addQueryParameter("genre", genre)
            }
          }
        parseTrackListResponse(executeGet(url))
      }
    }

  override suspend fun getRecommendedTracks(
    preferredGenres: List<String>,
    limit: Int,
  ): Result<List<MusicTrack>> =
    withContext(ioDispatcher) {
      runCatching {
        val safeLimit = limit.coerceIn(1, 50)
        val topGenre = preferredGenres.firstOrNull { it.isNotBlank() }

        val undergroundUrl =
          buildUrl("tracks/trending/underground") {
            addQueryParameter("limit", safeLimit.toString())
          }
        val underground = runCatching { parseTrackListResponse(executeGet(undergroundUrl)) }.getOrDefault(emptyList())

        val genrePicks =
          if (topGenre != null) {
            val genreUrl =
              buildUrl("tracks/trending") {
                addQueryParameter("genre", topGenre)
                addQueryParameter("time", "month")
                addQueryParameter("limit", safeLimit.toString())
              }
            runCatching { parseTrackListResponse(executeGet(genreUrl)) }.getOrDefault(emptyList())
          } else {
            val monthlyUrl =
              buildUrl("tracks/trending") {
                addQueryParameter("time", "month")
                addQueryParameter("limit", safeLimit.toString())
              }
            parseTrackListResponse(executeGet(monthlyUrl))
          }

        val combined =
          (genrePicks + underground)
            .distinctBy { it.id }
            .take(safeLimit)

        if (combined.isEmpty() && underground.isEmpty() && genrePicks.isEmpty()) {
          // If underground endpoint had an issue and fallback also failed above, surface error from trending
          val fallbackUrl =
            buildUrl("tracks/trending") {
              addQueryParameter("time", "week")
              addQueryParameter("limit", safeLimit.toString())
            }
          parseTrackListResponse(executeGet(fallbackUrl))
        } else {
          combined
        }
      }
    }

  override suspend fun searchTracks(
    query: String,
    limit: Int,
  ): Result<List<MusicTrack>> =
    withContext(ioDispatcher) {
      val trimmed = query.trim()
      if (trimmed.isEmpty()) {
        return@withContext Result.success(emptyList())
      }
      runCatching {
        val url =
          buildUrl("tracks/search") {
            addQueryParameter("query", trimmed)
            addQueryParameter("limit", limit.coerceIn(1, 50).toString())
          }
        parseTrackListResponse(executeGet(url))
      }
    }

  override suspend fun verifyTrackStreamable(trackId: String): Result<MusicTrack> =
    withContext(ioDispatcher) {
      val cleanId = trackId.trim()
      if (cleanId.isEmpty()) {
        return@withContext Result.failure(AudiusTrackUnavailableException("Missing track identifier."))
      }
      runCatching {
        val encodedId = URLEncoder.encode(cleanId, Charsets.UTF_8.name())
        val url = buildUrl("tracks/$encodedId") {}
        val root = JSONObject(executeGet(url))
        val dataObj =
          root.optJSONObject("data")
            ?: throw AudiusTrackUnavailableException("Track metadata is unavailable on Audius.")
        val parsed =
          parseTrackObject(dataObj, includeRestricted = true)
            ?: throw AudiusTrackUnavailableException("This track is no longer available on Audius.")
        if (!parsed.isStreamable) {
          throw AudiusTrackUnavailableException(
            "\"${parsed.title}\" has streaming restricted by its creator on Audius."
          )
        }
        parsed
      }
    }

  private fun buildUrl(
    relativePath: String,
    configure: okhttp3.HttpUrl.Builder.() -> Unit,
  ): String {
    val base =
      "$normalizedBaseUrl/${relativePath.trimStart('/')}".toHttpUrlOrNull()
        ?: throw IOException("Invalid Audius endpoint URL: $normalizedBaseUrl/$relativePath")
    return base
      .newBuilder()
      .addQueryParameter("app_name", appName)
      .apply(configure)
      .build()
      .toString()
  }

  private fun executeGet(url: String): String {
    val requestBuilder =
      Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("User-Agent", "VYNOZA-Android")
        .get()
    if (effectiveApiKey != null) {
      requestBuilder.header("x-api-key", effectiveApiKey)
    }
    val request = requestBuilder.build()

    httpClient.newCall(request).execute().use { response ->
      val bodyText = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        val detail =
          runCatching { JSONObject(bodyText).optString("message") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        when (response.code) {
          401 -> throw IOException(detail ?: "Audius API key was rejected (HTTP 401). Check your AUDIUS_API_KEY configuration.")
          404 -> throw AudiusTrackUnavailableException(detail ?: "Requested music was not found on Audius.")
          403 -> throw AudiusTrackUnavailableException(detail ?: "This track cannot be streamed under its current license.")
          429 -> throw IOException("Audius rate limit reached. Please wait a moment and retry.")
          else -> throw IOException(detail ?: "Audius request failed (HTTP ${response.code}).")
        }
      }
      if (bodyText.isBlank()) {
        throw IOException("Empty response from Audius API.")
      }
      return bodyText
    }
  }

  internal fun parseTrackListResponse(rawJson: String): List<MusicTrack> {
    val root = JSONObject(rawJson)
    val dataArray = root.optJSONArray("data") ?: JSONArray()
    return buildList {
      for (i in 0 until dataArray.length()) {
        val obj = dataArray.optJSONObject(i) ?: continue
        parseTrackObject(obj, includeRestricted = false)?.let(::add)
      }
    }
  }

  internal fun parseTrackObject(
    obj: JSONObject,
    includeRestricted: Boolean,
  ): MusicTrack? {
    val id = obj.optString("id").trim()
    if (id.isEmpty()) return null

    val isUnlisted = obj.optBoolean("is_unlisted", false)
    val isDeleted = obj.optBoolean("is_delete", false)
    if (isUnlisted || isDeleted) return null

    val isStreamableFlag = if (obj.has("is_streamable")) obj.optBoolean("is_streamable", false) else true
    val accessObj = obj.optJSONObject("access")
    val accessAllowsStream = accessObj == null || accessObj.optBoolean("stream", true)
    val streamAllowed = isStreamableFlag && accessAllowsStream

    if (!includeRestricted && !streamAllowed) {
      return null
    }

    val title = obj.optString("title").trim().ifBlank { "Untitled Track" }
    val durationSec = obj.optInt("duration", 0)
    if (durationSec <= 0) return null

    val userObj = obj.optJSONObject("user")
    val artistName = userObj?.optString("name")?.trim().orEmpty().ifBlank { "Audius Creator" }
    val artistHandle = userObj?.optString("handle")?.trim().orEmpty().ifBlank {
      artistName.lowercase().filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "creator" }
    }

    val artworkObj = obj.optJSONObject("artwork")
    val thumbArtwork =
      artworkObj?.optNonEmpty("150x150")
        ?: artworkObj?.optNonEmpty("480x480")
        ?: artworkObj?.optNonEmpty("1000x1000")
    val largeArtwork =
      artworkObj?.optNonEmpty("480x480")
        ?: artworkObj?.optNonEmpty("1000x1000")
        ?: thumbArtwork

    val genre = obj.optString("genre", "").trim()
    val mood = obj.optString("mood", "").trim()
    val license =
      obj.optNonEmpty("license")
        ?: "All Rights Reserved"
    val isDownloadable = obj.optBoolean("is_downloadable", false)
    val playCount = obj.optLong("play_count", 0L).coerceAtLeast(0L)
    val permalink = obj.optString("permalink", "").trim()

    val encodedId = URLEncoder.encode(id, Charsets.UTF_8.name())
    val encodedApp = URLEncoder.encode(appName, Charsets.UTF_8.name())
    val streamUrl = "$normalizedBaseUrl/tracks/$encodedId/stream?app_name=$encodedApp"

    return MusicTrack(
      id = id,
      title = title,
      artistName = artistName,
      artistHandle = artistHandle,
      artworkUrl = thumbArtwork,
      artworkLargeUrl = largeArtwork,
      durationSeconds = durationSec,
      genre = genre,
      mood = mood,
      license = license,
      isStreamable = streamAllowed,
      isDownloadable = isDownloadable,
      streamUrl = streamUrl,
      playCount = playCount,
      permalink = permalink,
    )
  }

  private fun JSONObject.optNonEmpty(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
  }

  companion object {
    const val DEFAULT_BASE_URL = "https://api.audius.co/v1"
    const val DEFAULT_APP_NAME = "VYNOZA"
    internal const val API_KEY_PLACEHOLDER = "AUDIUS_API_KEY_DEFAULT_VALUE"

    fun sanitizeApiKey(rawKey: String?): String? {
      val trimmed = rawKey?.trim().orEmpty()
      if (trimmed.isEmpty() || trimmed.equals(API_KEY_PLACEHOLDER, ignoreCase = true)) {
        return null
      }
      return trimmed
    }

    fun resolveConfiguredApiKey(): String? = sanitizeApiKey(BuildConfig.AUDIUS_API_KEY)

    fun defaultHttpClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
  }
}
