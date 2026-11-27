package com.vynoza.app

import android.content.Context
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.vynoza.app.core.studio.AudioMixSettings
import com.vynoza.app.core.studio.CreateStudioDraft
import com.vynoza.app.core.studio.DraftPrivacy
import com.vynoza.app.core.studio.EditorMediaClip
import com.vynoza.app.core.studio.EditorMediaKind
import com.vynoza.app.core.studio.MusicTrack
import com.vynoza.app.core.studio.MusicTrimSelection
import com.vynoza.app.core.studio.StudioEffectCatalog
import com.vynoza.app.core.studio.StudioEffectCategory
import com.vynoza.app.core.studio.StudioTemplateCatalog
import com.vynoza.app.core.studio.TemplateDiscoveryCategory
import com.vynoza.app.data.studio.DataStoreCreateStudioStore
import com.vynoza.app.data.studio.StudioFilePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CreateStudioFoundationTest {

  @Before
  @After
  fun resetFileProviderCacheForRobolectric() {
    // Robolectric assigns a fresh temp dataDir per test method while FileProvider holds a static sCache.
    runCatching {
      val field = FileProvider::class.java.getDeclaredField("sCache")
      field.isAccessible = true
      (field.get(null) as? MutableMap<*, *>)?.clear()
    }
  }

  private fun createIsolatedStore(context: Context): Pair<DataStoreCreateStudioStore, CoroutineScope> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val uniqueFile = File(context.filesDir, "datastore_test_${UUID.randomUUID()}.preferences_pb")
    val dataStore: DataStore<Preferences> =
      PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { uniqueFile },
      )
    return DataStoreCreateStudioStore(dataStore, context.filesDir) to scope
  }

  private fun sampleTrack(id: String, title: String = "Track $id") =
    MusicTrack(
      id = id,
      title = title,
      artistName = "Artist $id",
      artistHandle = "artist_$id",
      artworkUrl = "https://example.org/$id-150.jpg",
      artworkLargeUrl = "https://example.org/$id-480.jpg",
      durationSeconds = 120,
      genre = "Electronic",
      mood = "Upbeat",
      license = "Creative Commons BY",
      isStreamable = true,
      streamUrl = "https://api.audius.co/v1/tracks/$id/stream?app_name=VYNOZA",
      playCount = 5000L,
    )

  @Test
  fun `template catalog covers all five discovery categories and validates slot specs`() {
    TemplateDiscoveryCategory.entries.forEach { category ->
      val items = StudioTemplateCatalog.templatesFor(category)
      assertTrue("Expected templates for $category", items.isNotEmpty())
      items.forEach { spec ->
        assertTrue(spec.requiredMediaCount >= 2)
        assertTrue(spec.creatorName.isNotBlank())
        assertTrue(spec.creatorHandle.isNotBlank())
        assertTrue(spec.estimatedDurationSec > 0)
        assertNotNull(StudioEffectCatalog.findById(spec.effectPresetId))
      }
    }

    assertTrue(StudioEffectCatalog.carouselPresets.any { it.id == StudioEffectCatalog.EFFECT_NONE_ID })
    assertTrue(StudioEffectCatalog.presets.size > StudioEffectCatalog.carouselPresets.size)
    StudioEffectCategory.entries.forEach { cat ->
      assertTrue(StudioEffectCatalog.presetsFor(cat).isNotEmpty())
    }
  }

  @Test
  fun `draft buildPublishCaption merges hashtags and mentions within 300 chars`() {
    val draft =
      CreateStudioDraft(
        caption = "Sunset choreography rehearsal",
        hashtags = listOf("vynoza", "#Dance", "#vynoza", "night vibe!"),
        mentions = listOf("creator_one", "@Creator_One", "@guest.two"),
      )

    val result = draft.buildPublishCaption()
    assertTrue(result.startsWith("Sunset choreography rehearsal"))
    assertTrue(result.contains("@creator_one"))
    assertTrue(result.contains("@guest.two"))
    assertTrue(result.contains("#vynoza"))
    assertTrue(result.contains("#Dance"))
    assertTrue(result.contains("#nightvibe"))
    assertTrue(result.length <= CreateStudioDraft.MAX_FIRESTORE_CAPTION_LENGTH)

    val longDraft =
      CreateStudioDraft(
        caption = "A".repeat(290),
        hashtags = listOf("verylonghashtagthatshouldnotoverflow"),
        mentions = listOf("someone"),
      )
    assertTrue(longDraft.buildPublishCaption().length <= 300)
  }

  @Test
  fun `music trim selection clamps start and duration safely within track bounds`() {
    val shortTrack = sampleTrack("short").copy(durationSeconds = 10)
    val selection =
      MusicTrimSelection(
        track = shortTrack,
        startMs = 8_000L,
        durationMs = 25_000L,
      )
    assertEquals(7_000L, selection.clampedStartMs)
    assertEquals(3_000L, selection.clampedDurationMs)
    assertEquals(10_000L, selection.endMs)
  }

  @Test
  fun `datastore persists drafts with clips, music trim, cover, and cleans directory on delete`() =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val (store, scope) = createIsolatedStore(context)
      try {
        val coverFile = StudioFilePaths.createDraftCoverFile(context, "draft_alpha").apply {
          writeBytes(byteArrayOf(1, 2, 3))
        }
        val coverUri = StudioFilePaths.fileProviderUriFor(context, coverFile)

        val draft =
          CreateStudioDraft(
            id = "draft_alpha",
            title = "Neon Walk",
            clips =
              listOf(
                EditorMediaClip(
                  id = "clip_1",
                  uriString = "content://media/video/1",
                  kind = EditorMediaKind.VIDEO,
                  durationMs = 4_500L,
                  trimStartMs = 500L,
                  trimEndMs = 4_000L,
                  displayName = "walk.mp4",
                ),
                EditorMediaClip(
                  id = "clip_2",
                  uriString = "content://media/image/2",
                  kind = EditorMediaKind.PHOTO,
                  durationMs = 3_000L,
                  displayName = "pose.jpg",
                ),
              ),
            selectedMusic =
              MusicTrimSelection(
                track = sampleTrack("aud_1", "Midnight Drift"),
                startMs = 12_000L,
                durationMs = 15_000L,
                waveformAmplitudes = listOf(0.2f, 0.8f, 0.5f, 0.9f),
              ),
            audioMix = AudioMixSettings(originalAudioVolume = 0.3f, musicVolume = 0.9f, syncOffsetMs = 400L),
            effectPresetId = "violet_aura",
            templateId = "tpl_beat_pulse_3",
            coverFrameUriString = coverUri.toString(),
            coverTimestampMs = 1_200L,
            caption = "Draft caption",
            hashtags = listOf("#vynoza", "#neon"),
            mentions = listOf("@dj_nova"),
            privacy = DraftPrivacy.PRIVATE,
          )

        store.saveDraft(draft).getOrThrow()
        val loaded = store.getDraft("draft_alpha")
        assertNotNull(loaded)
        assertEquals("Neon Walk", loaded?.title)
        assertEquals(2, loaded?.clips?.size)
        assertEquals("Midnight Drift", loaded?.selectedMusic?.track?.title)
        assertEquals(4, loaded?.selectedMusic?.waveformAmplitudes?.size)
        assertEquals(0.3f, loaded?.audioMix?.originalAudioVolume ?: 0f, 0.01f)
        assertEquals(DraftPrivacy.PRIVATE, loaded?.privacy)
        assertEquals(coverUri.toString(), loaded?.coverFrameUriString)
        assertTrue(coverFile.exists())

        store.deleteDraft("draft_alpha").getOrThrow()
        assertNull(store.getDraft("draft_alpha"))
        assertFalse("Draft media directory should be removed on delete", coverFile.exists())
      } finally {
        scope.cancel()
      }
    }

  @Test
  fun `datastore toggles music favorites and maintains capped deduplicated recently played history`() =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val (store, scope) = createIsolatedStore(context)
      try {
        val t1 = sampleTrack("t1", "First")
        val t2 = sampleTrack("t2", "Second")

        assertTrue(store.toggleFavorite(t1).getOrThrow())
        assertTrue(store.toggleFavorite(t2).getOrThrow())
        assertEquals(setOf("t1", "t2"), store.observeFavoriteIds().first())

        assertFalse(store.toggleFavorite(t1).getOrThrow())
        assertEquals(listOf("t2"), store.observeFavorites().first().map { it.id })

        for (i in 1..30) {
          store.recordRecentlyPlayed(sampleTrack("rec_$i"), maxItems = 25).getOrThrow()
        }
        val recent = store.observeRecentlyPlayed().first()
        assertEquals(25, recent.size)
        assertEquals("rec_30", recent.first().id)

        // Playing an older track moves it cleanly to the front without duplicating
        store.recordRecentlyPlayed(sampleTrack("rec_10"), maxItems = 25).getOrThrow()
        val reordered = store.observeRecentlyPlayed().first()
        assertEquals(25, reordered.size)
        assertEquals("rec_10", reordered.first().id)
        assertEquals(1, reordered.count { it.id == "rec_10" })

        store.clearRecentlyPlayed().getOrThrow()
        assertTrue(store.observeRecentlyPlayed().first().isEmpty())
      } finally {
        scope.cancel()
      }
    }

  @Test
  fun `fileprovider resolves URIs for new camera cache, studio media, and drafts directories`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tempCam = StudioFilePaths.createTempCameraFile(context, "cam", "mp4").apply { writeBytes(byteArrayOf(1)) }
    val studioMedia = StudioFilePaths.createPersistentStudioFile(context, "photo", "jpg").apply { writeBytes(byteArrayOf(2)) }
    val draftCover = StudioFilePaths.createDraftCoverFile(context, "draft_fp").apply { writeBytes(byteArrayOf(3)) }

    try {
      val camUri = StudioFilePaths.fileProviderUriFor(context, tempCam)
      val mediaUri = StudioFilePaths.fileProviderUriFor(context, studioMedia)
      val coverUri = StudioFilePaths.fileProviderUriFor(context, draftCover)

      assertEquals("com.vynoza.app.fileprovider", camUri.authority)
      assertEquals("com.vynoza.app.fileprovider", mediaUri.authority)
      assertEquals("com.vynoza.app.fileprovider", coverUri.authority)
    } finally {
      tempCam.delete()
      studioMedia.delete()
      StudioFilePaths.deleteDraftDirectory(context.filesDir, "draft_fp")
    }
  }
}
