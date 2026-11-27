package com.vynoza.app.data.studio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vynoza.app.core.studio.AudioMixSettings
import com.vynoza.app.core.studio.CreateStudioDraft
import com.vynoza.app.core.studio.DraftPrivacy
import com.vynoza.app.core.studio.EditorMediaClip
import com.vynoza.app.core.studio.EditorMediaKind
import com.vynoza.app.core.studio.MusicTrack
import com.vynoza.app.core.studio.MusicTrimSelection
import com.vynoza.app.core.studio.StudioEffectCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

interface CreateStudioLocalStore {
  fun observeDrafts(): Flow<List<CreateStudioDraft>>
  suspend fun getDraft(draftId: String): CreateStudioDraft?
  suspend fun saveDraft(draft: CreateStudioDraft): Result<CreateStudioDraft>
  suspend fun deleteDraft(draftId: String): Result<Unit>

  fun observeFavorites(): Flow<List<MusicTrack>>
  fun observeFavoriteIds(): Flow<Set<String>>
  suspend fun toggleFavorite(track: MusicTrack): Result<Boolean>

  fun observeRecentlyPlayed(): Flow<List<MusicTrack>>
  suspend fun recordRecentlyPlayed(track: MusicTrack, maxItems: Int = DEFAULT_RECENT_LIMIT): Result<Unit>
  suspend fun clearRecentlyPlayed(): Result<Unit>

  companion object {
    const val DEFAULT_RECENT_LIMIT = 25
  }
}

object StudioStoreProvider {
  @Volatile private var instance: DataStoreCreateStudioStore? = null

  fun get(context: Context): CreateStudioLocalStore {
    val existing = instance
    if (existing != null) return existing
    return synchronized(this) {
      instance
        ?: run {
          val appContext = context.applicationContext
          val dataStore =
            PreferenceDataStoreFactory.create(
              scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
              produceFile = { File(appContext.filesDir, "datastore/vynoza_create_studio.preferences_pb") },
            )
          DataStoreCreateStudioStore(dataStore, appContext.filesDir).also { instance = it }
        }
    }
  }
}

class DataStoreCreateStudioStore(
  private val dataStore: DataStore<Preferences>,
  private val filesDir: File,
) : CreateStudioLocalStore {

  private val preferencesFlow: Flow<Preferences> =
    dataStore.data.catch { error ->
      if (error is IOException) {
        emit(emptyPreferences())
      } else {
        throw error
      }
    }

  override fun observeDrafts(): Flow<List<CreateStudioDraft>> =
    preferencesFlow.map { prefs ->
      StudioStoreJson.decodeDrafts(prefs[KEY_DRAFTS]).sortedByDescending { it.updatedAtMs }
    }

  override suspend fun getDraft(draftId: String): CreateStudioDraft? =
    observeDrafts().first().firstOrNull { it.id == draftId }

  override suspend fun saveDraft(draft: CreateStudioDraft): Result<CreateStudioDraft> =
    runCatching {
      val updated = draft.copy(updatedAtMs = System.currentTimeMillis())
      dataStore.edit { prefs ->
        val existing = StudioStoreJson.decodeDrafts(prefs[KEY_DRAFTS]).filterNot { it.id == updated.id }
        val next = (listOf(updated) + existing).sortedByDescending { it.updatedAtMs }
        prefs[KEY_DRAFTS] = StudioStoreJson.encodeDrafts(next)
      }
      updated
    }

  override suspend fun deleteDraft(draftId: String): Result<Unit> =
    runCatching {
      dataStore.edit { prefs ->
        val next = StudioStoreJson.decodeDrafts(prefs[KEY_DRAFTS]).filterNot { it.id == draftId }
        prefs[KEY_DRAFTS] = StudioStoreJson.encodeDrafts(next)
      }
      StudioFilePaths.deleteDraftDirectory(filesDir, draftId)
    }

  override fun observeFavorites(): Flow<List<MusicTrack>> =
    preferencesFlow.map { prefs ->
      StudioStoreJson.decodeTracks(prefs[KEY_MUSIC_FAVORITES])
    }

  override fun observeFavoriteIds(): Flow<Set<String>> =
    observeFavorites().map { tracks -> tracks.map { it.id }.toSet() }

  override suspend fun toggleFavorite(track: MusicTrack): Result<Boolean> =
    runCatching {
      var nowFavorited = false
      dataStore.edit { prefs ->
        val existing = StudioStoreJson.decodeTracks(prefs[KEY_MUSIC_FAVORITES])
        val alreadyExists = existing.any { it.id == track.id }
        val next =
          if (alreadyExists) {
            nowFavorited = false
            existing.filterNot { it.id == track.id }
          } else {
            nowFavorited = true
            listOf(track) + existing
          }
        prefs[KEY_MUSIC_FAVORITES] = StudioStoreJson.encodeTracks(next)
      }
      nowFavorited
    }

  override fun observeRecentlyPlayed(): Flow<List<MusicTrack>> =
    preferencesFlow.map { prefs ->
      StudioStoreJson.decodeTracks(prefs[KEY_MUSIC_RECENT])
    }

  override suspend fun recordRecentlyPlayed(track: MusicTrack, maxItems: Int): Result<Unit> =
    runCatching {
      val safeCap = maxItems.coerceAtLeast(1)
      dataStore.edit { prefs ->
        val existing = StudioStoreJson.decodeTracks(prefs[KEY_MUSIC_RECENT]).filterNot { it.id == track.id }
        val next = (listOf(track) + existing).take(safeCap)
        prefs[KEY_MUSIC_RECENT] = StudioStoreJson.encodeTracks(next)
      }
      Unit
    }

  override suspend fun clearRecentlyPlayed(): Result<Unit> =
    runCatching {
      dataStore.edit { prefs ->
        prefs.remove(KEY_MUSIC_RECENT)
      }
      Unit
    }

  private companion object {
    val KEY_DRAFTS = stringPreferencesKey("studio_drafts_json")
    val KEY_MUSIC_FAVORITES = stringPreferencesKey("studio_music_favorites_json")
    val KEY_MUSIC_RECENT = stringPreferencesKey("studio_music_recent_json")
  }
}

object StudioStoreJson {
  fun encodeTracks(tracks: List<MusicTrack>): String =
    JSONArray().apply { tracks.forEach { put(trackToJson(it)) } }.toString()

  fun decodeTracks(raw: String?): List<MusicTrack> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          jsonToTrack(obj)?.let(::add)
        }
      }
    }.getOrDefault(emptyList())
  }

  fun encodeDrafts(drafts: List<CreateStudioDraft>): String =
    JSONArray().apply { drafts.forEach { put(draftToJson(it)) } }.toString()

  fun decodeDrafts(raw: String?): List<CreateStudioDraft> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          jsonToDraft(obj)?.let(::add)
        }
      }
    }.getOrDefault(emptyList())
  }

  private fun trackToJson(track: MusicTrack): JSONObject =
    JSONObject().apply {
      put("id", track.id)
      put("title", track.title)
      put("artistName", track.artistName)
      put("artistHandle", track.artistHandle)
      put("artworkUrl", track.artworkUrl ?: JSONObject.NULL)
      put("artworkLargeUrl", track.artworkLargeUrl ?: JSONObject.NULL)
      put("durationSeconds", track.durationSeconds)
      put("genre", track.genre)
      put("mood", track.mood)
      put("license", track.license)
      put("isStreamable", track.isStreamable)
      put("isDownloadable", track.isDownloadable)
      put("streamUrl", track.streamUrl)
      put("playCount", track.playCount)
      put("permalink", track.permalink)
    }

  private fun jsonToTrack(obj: JSONObject): MusicTrack? {
    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
    val streamUrl = obj.optString("streamUrl").takeIf { it.isNotBlank() } ?: return null
    return MusicTrack(
      id = id,
      title = obj.optString("title", "Untitled"),
      artistName = obj.optString("artistName", ""),
      artistHandle = obj.optString("artistHandle", ""),
      artworkUrl = obj.optNullableString("artworkUrl"),
      artworkLargeUrl = obj.optNullableString("artworkLargeUrl"),
      durationSeconds = obj.optInt("durationSeconds", 30).coerceAtLeast(1),
      genre = obj.optString("genre", ""),
      mood = obj.optString("mood", ""),
      license = obj.optString("license", "All Rights Reserved"),
      isStreamable = obj.optBoolean("isStreamable", true),
      isDownloadable = obj.optBoolean("isDownloadable", false),
      streamUrl = streamUrl,
      playCount = obj.optLong("playCount", 0L),
      permalink = obj.optString("permalink", ""),
    )
  }

  private fun draftToJson(draft: CreateStudioDraft): JSONObject =
    JSONObject().apply {
      put("id", draft.id)
      put("title", draft.title)
      put("createdAtMs", draft.createdAtMs)
      put("updatedAtMs", draft.updatedAtMs)
      put(
        "clips",
        JSONArray().apply {
          draft.clips.forEach { clip ->
            put(
              JSONObject().apply {
                put("id", clip.id)
                put("uriString", clip.uriString)
                put("kind", clip.kind.name)
                put("durationMs", clip.durationMs)
                put("trimStartMs", clip.trimStartMs)
                put("trimEndMs", clip.trimEndMs)
                put("displayName", clip.displayName)
              }
            )
          }
        },
      )
      if (draft.selectedMusic != null) {
        put(
          "selectedMusic",
          JSONObject().apply {
            put("track", trackToJson(draft.selectedMusic.track))
            put("startMs", draft.selectedMusic.startMs)
            put("durationMs", draft.selectedMusic.durationMs)
            put(
              "waveformAmplitudes",
              JSONArray().apply {
                draft.selectedMusic.waveformAmplitudes.forEach { put(it.toDouble()) }
              },
            )
          },
        )
      } else {
        put("selectedMusic", JSONObject.NULL)
      }
      put(
        "audioMix",
        JSONObject().apply {
          put("originalAudioVolume", draft.audioMix.originalAudioVolume.toDouble())
          put("musicVolume", draft.audioMix.musicVolume.toDouble())
          put("syncOffsetMs", draft.audioMix.syncOffsetMs)
        },
      )
      put("effectPresetId", draft.effectPresetId)
      put("templateId", draft.templateId ?: JSONObject.NULL)
      put("coverFrameUriString", draft.coverFrameUriString ?: JSONObject.NULL)
      put("coverTimestampMs", draft.coverTimestampMs)
      put("caption", draft.caption)
      put("hashtags", JSONArray(draft.hashtags))
      put("mentions", JSONArray(draft.mentions))
      put("privacy", draft.privacy.name)
    }

  private fun jsonToDraft(obj: JSONObject): CreateStudioDraft? {
    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
    val clipsArr = obj.optJSONArray("clips") ?: JSONArray()
    val clips =
      buildList {
        for (i in 0 until clipsArr.length()) {
          val c = clipsArr.optJSONObject(i) ?: continue
          val uri = c.optString("uriString").takeIf { it.isNotBlank() } ?: continue
          val kind =
            runCatching { EditorMediaKind.valueOf(c.optString("kind", "VIDEO")) }
              .getOrDefault(EditorMediaKind.VIDEO)
          add(
            EditorMediaClip(
              id = c.optString("id").ifBlank { "clip_$i" },
              uriString = uri,
              kind = kind,
              durationMs = c.optLong("durationMs", EditorMediaClip.DEFAULT_PHOTO_CLIP_DURATION_MS),
              trimStartMs = c.optLong("trimStartMs", 0L),
              trimEndMs = c.optLong("trimEndMs", EditorMediaClip.DEFAULT_PHOTO_CLIP_DURATION_MS),
              displayName = c.optString("displayName", "clip"),
            )
          )
        }
      }

    val musicObj = obj.optJSONObject("selectedMusic")
    val selectedMusic =
      musicObj?.let { m ->
        val track = m.optJSONObject("track")?.let(::jsonToTrack) ?: return@let null
        val waveArr = m.optJSONArray("waveformAmplitudes") ?: JSONArray()
        val wave =
          buildList {
            for (i in 0 until waveArr.length()) {
              add(waveArr.optDouble(i, 0.0).toFloat().coerceIn(0f, 1f))
            }
          }
        MusicTrimSelection(
          track = track,
          startMs = m.optLong("startMs", 0L),
          durationMs = m.optLong("durationMs", MusicTrimSelection.DEFAULT_TRIM_DURATION_MS),
          waveformAmplitudes = wave,
        )
      }

    val mixObj = obj.optJSONObject("audioMix")
    val audioMix =
      if (mixObj != null) {
        AudioMixSettings(
          originalAudioVolume = mixObj.optDouble("originalAudioVolume", 1.0).toFloat(),
          musicVolume = mixObj.optDouble("musicVolume", 0.85).toFloat(),
          syncOffsetMs = mixObj.optLong("syncOffsetMs", 0L),
        ).normalized()
      } else {
        AudioMixSettings()
      }

    val hashtags = obj.optJSONArray("hashtags").toStringList()
    val mentions = obj.optJSONArray("mentions").toStringList()
    val privacy =
      runCatching { DraftPrivacy.valueOf(obj.optString("privacy", DraftPrivacy.PUBLIC.name)) }
        .getOrDefault(DraftPrivacy.PUBLIC)

    return CreateStudioDraft(
      id = id,
      title = obj.optString("title", ""),
      createdAtMs = obj.optLong("createdAtMs", 0L),
      updatedAtMs = obj.optLong("updatedAtMs", 0L),
      clips = clips,
      selectedMusic = selectedMusic,
      audioMix = audioMix,
      effectPresetId = obj.optString("effectPresetId", StudioEffectCatalog.EFFECT_NONE_ID),
      templateId = obj.optNullableString("templateId"),
      coverFrameUriString = obj.optNullableString("coverFrameUriString"),
      coverTimestampMs = obj.optLong("coverTimestampMs", 0L),
      caption = obj.optString("caption", ""),
      hashtags = hashtags,
      mentions = mentions,
      privacy = privacy,
    )
  }

  private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() }
  }

  private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
      for (i in 0 until length()) {
        optString(i).takeIf { it.isNotBlank() }?.let(::add)
      }
    }
  }
}
