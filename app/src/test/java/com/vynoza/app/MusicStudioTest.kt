package com.vynoza.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.vynoza.app.core.studio.MusicDiscoverySection
import com.vynoza.app.core.studio.MusicTrack
import com.vynoza.app.core.studio.MusicTrimSelection
import com.vynoza.app.data.music.AudioPreviewPlaybackState
import com.vynoza.app.data.music.AudioWaveformExtractor
import com.vynoza.app.data.music.AudioWaveformSampler
import com.vynoza.app.data.music.AudiusDiscoveryCategory
import com.vynoza.app.data.music.AudiusMusicRepository
import com.vynoza.app.data.music.AudiusTrackUnavailableException
import com.vynoza.app.data.music.MusicRepository
import com.vynoza.app.data.music.StudioAudioPreviewController
import com.vynoza.app.data.music.WaveformSampleResult
import com.vynoza.app.data.studio.DataStoreCreateStudioStore
import com.vynoza.app.ui.studio.MusicStudioScreen
import com.vynoza.app.ui.studio.MusicStudioViewModel
import com.vynoza.app.ui.theme.VynozaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MusicStudioTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun createIsolatedStore(context: Context): Pair<DataStoreCreateStudioStore, CoroutineScope> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val uniqueFile = File(context.filesDir, "music_test_${UUID.randomUUID()}.preferences_pb")
    val dataStore: DataStore<Preferences> =
      PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { uniqueFile },
      )
    return DataStoreCreateStudioStore(dataStore, context.filesDir) to scope
  }

  private class FakePreviewController : StudioAudioPreviewController {
    private val _state = MutableStateFlow(AudioPreviewPlaybackState())
    override val state: StateFlow<AudioPreviewPlaybackState> = _state.asStateFlow()

    val playedTrackIds = mutableListOf<String>()
    var released = false
      private set

    override fun playPreview(track: MusicTrack, startMs: Long, endMs: Long) {
      if (!track.isStreamable) {
        _state.update {
          it.copy(
            activeTrackId = track.id,
            isPlaying = false,
            errorMessage = "Track restricted by creator",
          )
        }
        return
      }
      playedTrackIds.add(track.id)
      _state.value =
        AudioPreviewPlaybackState(
          activeTrackId = track.id,
          isPlaying = true,
          isBuffering = false,
          positionMs = startMs,
          trimStartMs = startMs,
          trimEndMs = endMs,
          errorMessage = null,
        )
    }

    override fun updateTrimWindow(startMs: Long, endMs: Long) {
      _state.update { current ->
        val clampedPos = current.positionMs.coerceIn(startMs, endMs.coerceAtLeast(startMs))
        current.copy(trimStartMs = startMs, trimEndMs = endMs, positionMs = clampedPos)
      }
    }

    override fun seekTo(positionMs: Long) {
      _state.update { it.copy(positionMs = positionMs) }
    }

    override fun pause() {
      _state.update { it.copy(isPlaying = false) }
    }

    override fun resume() {
      _state.update { it.copy(isPlaying = true) }
    }

    override fun stop() {
      _state.value = AudioPreviewPlaybackState()
    }

    override fun release() {
      released = true
      stop()
    }
  }

  private fun sampleAudiusTrack(
    id: String,
    title: String = "Track $id",
    genre: String = "Electronic",
    license: String = "Creative Commons BY",
    durationSeconds: Int = 90,
    isStreamable: Boolean = true,
  ) =
    MusicTrack(
      id = id,
      title = title,
      artistName = "Artist $id",
      artistHandle = "artist_$id",
      artworkUrl = "https://discoveryprovider.audius.co/content/$id-150.jpg",
      artworkLargeUrl = "https://discoveryprovider.audius.co/content/$id-480.jpg",
      durationSeconds = durationSeconds,
      genre = genre,
      mood = "Energetic",
      license = license,
      isStreamable = isStreamable,
      streamUrl = "https://api.audius.co/v1/tracks/$id/stream?app_name=VYNOZA",
      playCount = 12_500L,
    )

  @Test
  fun `audius repository parses real v1 JSON payloads, enforces app_name, filters restricted tracks, and handles HTTP errors`() =
    runBlocking {
      val recordedRequests = mutableListOf<String>()
      var verifyShouldFailForbidden = false

      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/v1/tracks/trending") { exchange ->
        recordedRequests.add(exchange.requestURI.toString())
        val body =
          """
          {
            "data": [
              {
                "id": "aud_ok_1",
                "title": "Neon Horizon",
                "duration": 184,
                "genre": "Electronic",
                "mood": "Upbeat",
                "license": "Creative Commons BY",
                "is_streamable": true,
                "is_unlisted": false,
                "is_downloadable": false,
                "play_count": 24010,
                "artwork": {
                  "150x150": "https://node.audius.co/art-150.jpg",
                  "480x480": "https://node.audius.co/art-480.jpg"
                },
                "user": {
                  "name": "Kora Wave",
                  "handle": "korawave"
                }
              },
              {
                "id": "aud_restricted_2",
                "title": "Restricted Song",
                "duration": 120,
                "genre": "Electronic",
                "is_streamable": false,
                "user": { "name": "Locked Artist", "handle": "locked" }
              },
              {
                "id": "aud_gated_3",
                "title": "Gated Exclusive",
                "duration": 150,
                "is_streamable": true,
                "access": { "stream": false, "download": false },
                "user": { "name": "Club Member", "handle": "club" }
              },
              {
                "id": "aud_unlisted_4",
                "title": "Secret Draft",
                "duration": 90,
                "is_streamable": true,
                "is_unlisted": true,
                "user": { "name": "Hidden", "handle": "hidden" }
              }
            ]
          }
          """.trimIndent()
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      server.createContext("/v1/tracks/trending/underground") { exchange ->
        recordedRequests.add(exchange.requestURI.toString())
        val body =
          """
          {
            "data": [
              {
                "id": "aud_ug_9",
                "title": "Midnight Transit",
                "duration": 142,
                "genre": "Synthwave",
                "license": "All Rights Reserved",
                "is_streamable": true,
                "user": { "name": "Pixel Drift", "handle": "pixeldrift" }
              }
            ]
          }
          """.trimIndent()
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      server.createContext("/v1/tracks/search") { exchange ->
        recordedRequests.add(exchange.requestURI.toString())
        val body =
          if (exchange.requestURI.query.orEmpty().contains("query=empty")) {
            """{"data": []}"""
          } else {
            """
            {
              "data": [
                {
                  "id": "aud_sr_5",
                  "title": "Violet Skyline",
                  "duration": 96,
                  "genre": "Pop",
                  "is_streamable": true,
                  "user": { "name": "Sora", "handle": "sora_music" }
                }
              ]
            }
            """.trimIndent()
          }
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      server.createContext("/v1/tracks/aud_ok_1") { exchange ->
        recordedRequests.add(exchange.requestURI.toString())
        if (verifyShouldFailForbidden) {
          val msg = """{"message":"Track streaming disabled"}""".toByteArray()
          exchange.sendResponseHeaders(403, msg.size.toLong())
          exchange.responseBody.use { it.write(msg) }
        } else {
          val body =
            """
            {
              "data": {
                "id": "aud_ok_1",
                "title": "Neon Horizon",
                "duration": 184,
                "genre": "Electronic",
                "license": "Creative Commons BY",
                "is_streamable": true,
                "user": { "name": "Kora Wave", "handle": "korawave" }
              }
            }
            """.trimIndent()
          val bytes = body.toByteArray()
          exchange.sendResponseHeaders(200, bytes.size.toLong())
          exchange.responseBody.use { it.write(bytes) }
        }
      }
      server.createContext("/v1/tracks/aud_broken") { exchange ->
        val err = """{"message":"Server unavailable"}""".toByteArray()
        exchange.sendResponseHeaders(500, err.size.toLong())
        exchange.responseBody.use { it.write(err) }
      }
      server.start()

      try {
        val baseUrl = "http://127.0.0.1:${server.address.port}/v1"
        val repo = AudiusMusicRepository(baseUrl = baseUrl, appName = "VYNOZA")

        // Trending filters out restricted, gated, and unlisted tracks
        val trending = repo.getTrendingTracks(genre = "Electronic").getOrThrow()
        assertEquals(1, trending.size)
        val first = trending.first()
        assertEquals("aud_ok_1", first.id)
        assertEquals("Neon Horizon", first.title)
        assertEquals("Kora Wave", first.artistName)
        assertEquals("korawave", first.artistHandle)
        assertEquals("Creative Commons BY", first.license)
        assertEquals(184, first.durationSeconds)
        assertEquals("https://node.audius.co/art-150.jpg", first.artworkUrl)
        assertEquals("$baseUrl/tracks/aud_ok_1/stream?app_name=VYNOZA", first.streamUrl)
        assertTrue(recordedRequests.any { it.contains("app_name=VYNOZA") && it.contains("genre=Electronic") })

        // Recommendations combine genre trending + underground
        val recs = repo.getRecommendedTracks(preferredGenres = listOf("Electronic")).getOrThrow()
        assertEquals(listOf("aud_ok_1", "aud_ug_9"), recs.map { it.id })

        // Search returns real match and handles empty response cleanly
        val found = repo.searchTracks("violet").getOrThrow()
        assertEquals("aud_sr_5", found.single().id)
        assertTrue(repo.searchTracks("empty").getOrThrow().isEmpty())
        assertTrue(repo.searchTracks("   ").getOrThrow().isEmpty())

        // Licensing verification succeeds when streamable and blocks on 403
        val verified = repo.verifyTrackStreamable("aud_ok_1").getOrThrow()
        assertTrue(verified.isStreamable)
        verifyShouldFailForbidden = true
        val blocked = repo.verifyTrackStreamable("aud_ok_1")
        assertTrue(blocked.isFailure)
        assertTrue(blocked.exceptionOrNull() is AudiusTrackUnavailableException)

        // HTTP 500 surfaces failure cleanly
        assertTrue(repo.verifyTrackStreamable("aud_broken").isFailure)
      } finally {
        server.stop(0)
      }
    }

  @Test
  fun `audius repository sends x-api-key when configured, omits placeholder or blank keys, never sends Bearer, and handles 401 or 429`() =
    runBlocking {
      assertNotNull(BuildConfig.AUDIUS_API_KEY)
      assertNull(AudiusMusicRepository.sanitizeApiKey(null))
      assertNull(AudiusMusicRepository.sanitizeApiKey("   "))
      assertNull(AudiusMusicRepository.sanitizeApiKey("AUDIUS_API_KEY_DEFAULT_VALUE"))
      assertEquals("real_key_123", AudiusMusicRepository.sanitizeApiKey("  real_key_123  "))

      val seenApiKeys = mutableListOf<String?>()
      val seenAuthHeaders = mutableListOf<String?>()
      var statusOverride = 200

      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/v1/tracks/trending") { exchange ->
        seenApiKeys.add(exchange.requestHeaders.getFirst("x-api-key"))
        seenAuthHeaders.add(exchange.requestHeaders.getFirst("Authorization"))
        when (statusOverride) {
          401 -> {
            val bytes = """{"message":"Invalid API key"}""".toByteArray()
            exchange.sendResponseHeaders(401, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
          }
          429 -> {
            val bytes = """{"message":"Rate limit exceeded"}""".toByteArray()
            exchange.sendResponseHeaders(429, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
          }
          else -> {
            val bytes =
              """
              {
                "data": [
                  {
                    "id": "aud_keyed_1",
                    "title": "Signal Spark",
                    "duration": 95,
                    "genre": "Electronic",
                    "license": "Creative Commons BY",
                    "is_streamable": true,
                    "user": { "name": "Nova Synth", "handle": "novasynth" }
                  }
                ]
              }
              """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
          }
        }
      }
      server.createContext("/v1/tracks/search") { exchange ->
        seenApiKeys.add(exchange.requestHeaders.getFirst("x-api-key"))
        seenAuthHeaders.add(exchange.requestHeaders.getFirst("Authorization"))
        val bytes = """{"data": []}""".toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      server.start()

      try {
        val baseUrl = "http://127.0.0.1:${server.address.port}/v1"

        // 1. Real key sends x-api-key without Bearer authorization
        val keyedRepo = AudiusMusicRepository(baseUrl = baseUrl, appName = "VYNOZA", apiKey = "audius_key_xyz")
        val tracks = keyedRepo.getTrendingTracks(limit = 5).getOrThrow()
        assertEquals("aud_keyed_1", tracks.single().id)
        assertTrue(keyedRepo.searchTracks("synth").getOrThrow().isEmpty())
        assertEquals(listOf("audius_key_xyz", "audius_key_xyz"), seenApiKeys)
        assertTrue(seenAuthHeaders.all { it == null })

        // 2. Placeholder or blank keys never emit a bogus x-api-key header
        val placeholderRepo =
          AudiusMusicRepository(baseUrl = baseUrl, appName = "VYNOZA", apiKey = "AUDIUS_API_KEY_DEFAULT_VALUE")
        placeholderRepo.getTrendingTracks(limit = 5).getOrThrow()
        assertNull(seenApiKeys.last())

        // 3. 401 and 429 are surfaced cleanly
        statusOverride = 401
        val unauthorized = keyedRepo.getTrendingTracks(limit = 5)
        assertTrue(unauthorized.isFailure)
        assertTrue(unauthorized.exceptionOrNull()?.message.orEmpty().contains("Invalid API key"))

        statusOverride = 429
        val rateLimited = keyedRepo.getTrendingTracks(limit = 5)
        assertTrue(rateLimited.isFailure)
        assertTrue(rateLimited.exceptionOrNull()?.message.orEmpty().contains("rate limit", ignoreCase = true))
      } finally {
        server.stop(0)
      }
    }

  @Test
  fun `waveform sampler extracts real PCM amplitude contrast between quiet and loud segments`() {
    val sampleCount = 4800
    val pcm =
      ShortArray(sampleCount) { idx ->
        if (idx < sampleCount / 2) {
          // Quiet half
          (sin(idx * 0.1) * 400.0).toInt().toShort()
        } else {
          // Loud half
          (sin(idx * 0.25) * 28000.0).toInt().toShort()
        }
      }

    val bars = AudioWaveformSampler.computeAmplitudesFromPcm(pcm, barCount = 24)
    assertEquals(24, bars.size)
    val firstHalfAvg = bars.take(12).average()
    val secondHalfAvg = bars.drop(12).average()
    assertTrue("Expected loud segment to have higher amplitude than quiet segment", secondHalfAvg > firstHalfAvg * 2.0)
    assertTrue(bars.all { it in 0.16f..1.0f })
  }

  @Test
  fun `music studio UI handles loading, errors, search, preview playback, favorites, recent history, and waveform trim confirmation`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val (store, scope) = createIsolatedStore(context)
    try {
      val t1 = sampleAudiusTrack("t_aurora", "Aurora Pulse", genre = "Electronic", durationSeconds = 80)
      val t2 = sampleAudiusTrack("t_midnight", "Midnight Drive", genre = "Hip-Hop/Rap", durationSeconds = 45)
      var shouldFailTrending = true
      var allowVerify = true

      val repo =
        object : MusicRepository {
          override suspend fun getTrendingTracks(genre: String?, timeWindow: String, limit: Int): Result<List<MusicTrack>> {
            if (shouldFailTrending) return Result.failure(IOException("Network unreachable"))
            val all = listOf(t1, t2)
            return Result.success(if (genre == null) all else all.filter { it.genre == genre })
          }

          override suspend fun getRecommendedTracks(preferredGenres: List<String>, limit: Int): Result<List<MusicTrack>> =
            Result.success(listOf(t2, t1))

          override suspend fun searchTracks(query: String, limit: Int): Result<List<MusicTrack>> =
            if (query.contains("none", ignoreCase = true)) {
              Result.success(emptyList())
            } else {
              Result.success(listOf(t1, t2).filter { it.title.contains(query, ignoreCase = true) })
            }

          override suspend fun verifyTrackStreamable(trackId: String): Result<MusicTrack> {
            val found = listOf(t1, t2).first { it.id == trackId }
            return if (allowVerify) {
              Result.success(found)
            } else {
              Result.failure(AudiusTrackUnavailableException("Restricted by artist"))
            }
          }
        }

      val previewController = FakePreviewController()
      val waveformExtractor =
        object : AudioWaveformExtractor {
          override suspend fun sampleWaveform(track: MusicTrack, barCount: Int): WaveformSampleResult =
            WaveformSampleResult(
              amplitudes = List(barCount) { i -> 0.25f + (i % 5) * 0.15f },
              isDecodedFromAudio = true,
            )
        }

      val vm =
        MusicStudioViewModel(
          musicRepository = repo,
          localStore = store,
          previewController = previewController,
          waveformExtractor = waveformExtractor,
        )

      var confirmedSelection: MusicTrimSelection? = null
      composeTestRule.setContent {
        VynozaTheme {
          MusicStudioScreen(
            viewModel = vm,
            onBack = {},
            onSoundSelected = { confirmedSelection = it },
          )
        }
      }
      composeTestRule.waitForIdle()

      // 1. Error state shows retry, recovering to real track list
      composeTestRule.onNodeWithTag("music_error_state").assertIsDisplayed()
      shouldFailTrending = false
      composeTestRule.onNodeWithText("Retry").performClick()
      composeTestRule.waitForIdle()

      composeTestRule.onNodeWithTag("music_track_card_t_aurora").assertIsDisplayed()
      composeTestRule.onNodeWithTag("music_track_card_t_midnight").assertIsDisplayed()

      // 2. Preview playback & Favorite toggle persist to Recently Played and Favorites
      composeTestRule.onNodeWithTag("music_play_button_t_aurora").performClick()
      composeTestRule.waitForIdle()
      assertEquals("t_aurora", previewController.state.value.activeTrackId)
      assertTrue(previewController.state.value.isPlaying)

      composeTestRule.onNodeWithTag("music_favorite_button_t_aurora").performClick()
      val deadline = System.currentTimeMillis() + 5_000L
      while (System.currentTimeMillis() < deadline) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        composeTestRule.waitForIdle()
        val state = vm.uiState.value
        if ("t_aurora" in state.favoriteIds && state.recentTracks.firstOrNull()?.id == "t_aurora") {
          break
        }
        Thread.sleep(20L)
      }
      val afterFavState = vm.uiState.value
      assertTrue(
        "Expected t_aurora in favoriteIds, got ${afterFavState.favoriteIds}",
        "t_aurora" in afterFavState.favoriteIds,
      )
      assertEquals(
        "Expected t_aurora in recentTracks, got ${afterFavState.recentTracks.map { it.id }}",
        "t_aurora",
        afterFavState.recentTracks.firstOrNull()?.id,
      )

      // 3. Genre chip filters Trending
      composeTestRule.onNodeWithTag("music_genre_hip_hop").performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("music_track_card_t_midnight").assertIsDisplayed()
      composeTestRule.onNodeWithTag("music_track_card_t_aurora").assertDoesNotExist()

      // 4. Empty search state & clearing search
      composeTestRule.onNodeWithTag("music_search_input").performTextInput("none")
      composeTestRule.onNodeWithTag("music_search_input").performImeAction()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("music_empty_state").assertIsDisplayed()

      composeTestRule.onNodeWithTag("music_clear_search_button").performClick()
      composeTestRule.waitForIdle()

      // 5. Switch to Favorites tab & open Waveform Trimmer
      composeTestRule.onNodeWithTag("music_tab_favorites").performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("music_select_button_t_aurora").performClick()
      composeTestRule.waitForIdle()

      composeTestRule.onNodeWithTag("music_trimmer_sheet").assertIsDisplayed()
      composeTestRule.onNodeWithTag("music_waveform_canvas").assertIsDisplayed()

      // Adjust trim start & clip duration preset (30s) -> updates playback trim window
      vm.updateTrimStart(12_000L)
      composeTestRule.onNodeWithTag("music_trim_preset_30s").performScrollTo().performClick()
      composeTestRule.waitForIdle()
      assertEquals(12_000L, previewController.state.value.trimStartMs)
      assertEquals(42_000L, previewController.state.value.trimEndMs)

      // Restricted verification blocks selection cleanly with banner
      allowVerify = false
      composeTestRule.onNodeWithTag("music_confirm_trim_button").performScrollTo().performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("music_trim_error_banner").performScrollTo().assertIsDisplayed()
      assertEquals(null, confirmedSelection)

      // Allowed verification confirms selection with waveform & trim window
      allowVerify = true
      composeTestRule.onNodeWithTag("music_confirm_trim_button").performScrollTo().performClick()
      composeTestRule.waitForIdle()
      assertNotNull(confirmedSelection)
      assertEquals("t_aurora", confirmedSelection?.track?.id)
      assertEquals(12_000L, confirmedSelection?.clampedStartMs)
      assertEquals(30_000L, confirmedSelection?.clampedDurationMs)
      assertFalse(previewController.state.value.isPlaying)

      // 6. Recently Played tab reflects played/trimmed track
      composeTestRule.onNodeWithTag("music_tab_recent").performClick()
      composeTestRule.waitForIdle()
      assertEquals(MusicDiscoverySection.RECENTLY_PLAYED, vm.uiState.value.activeSection)
      composeTestRule.onNodeWithTag("music_track_card_t_aurora").assertIsDisplayed()
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun `live audius api verification when enabled`() =
    runBlocking {
      Assume.assumeTrue(System.getenv("VERIFY_LIVE_AUDIUS") == "true")
      val keyConfigured = AudiusMusicRepository.resolveConfiguredApiKey() != null
      println("LIVE_AUDIUS_KEY_CONFIGURED=$keyConfigured")
      val repo = AudiusMusicRepository()
      val trending = repo.getTrendingTracks(limit = 5).getOrThrow()
      assertTrue("Expected live trending tracks from Audius", trending.isNotEmpty())
      val first = trending.first()
      assertTrue(first.title.isNotBlank())
      assertTrue(first.artistName.isNotBlank())
      assertTrue(first.streamUrl.startsWith("https://api.audius.co/v1/tracks/"))

      val search = repo.searchTracks("electronic", limit = 5).getOrThrow()
      assertTrue("Expected live search results from Audius", search.isNotEmpty())

      val verified = repo.verifyTrackStreamable(first.id).getOrThrow()
      assertTrue(verified.isStreamable)
    }
}
