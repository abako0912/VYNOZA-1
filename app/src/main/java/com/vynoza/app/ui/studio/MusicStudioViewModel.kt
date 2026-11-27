package com.vynoza.app.ui.studio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vynoza.app.core.model.UiState
import com.vynoza.app.core.studio.MusicDiscoverySection
import com.vynoza.app.core.studio.MusicTrack
import com.vynoza.app.core.studio.MusicTrimSelection
import com.vynoza.app.data.music.AudioPreviewPlaybackState
import com.vynoza.app.data.music.AudioWaveformExtractor
import com.vynoza.app.data.music.AudiusDiscoveryCategory
import com.vynoza.app.data.music.AudiusTrackUnavailableException
import com.vynoza.app.data.music.MusicRepository
import com.vynoza.app.data.music.StudioAudioPreviewController
import com.vynoza.app.data.studio.CreateStudioLocalStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MusicStudioUiState(
  val activeSection: MusicDiscoverySection = MusicDiscoverySection.TRENDING,
  val selectedCategory: AudiusDiscoveryCategory = AudiusDiscoveryCategory.ALL,
  val searchQuery: String = "",
  val tracksState: UiState<List<MusicTrack>> = UiState.Loading,
  val favoriteIds: Set<String> = emptySet(),
  val favoriteTracks: List<MusicTrack> = emptyList(),
  val recentTracks: List<MusicTrack> = emptyList(),
  val playback: AudioPreviewPlaybackState = AudioPreviewPlaybackState(),
  val trimSelection: MusicTrimSelection? = null,
  val isSamplingWaveform: Boolean = false,
  val isWaveformRealDecoded: Boolean = false,
  val isVerifyingSelection: Boolean = false,
  val selectionError: String? = null,
  val licenseDialogTrack: MusicTrack? = null,
) {
  val displayedTracksState: UiState<List<MusicTrack>>
    get() =
      when (activeSection) {
        MusicDiscoverySection.FAVORITES -> UiState.Success(favoriteTracks)
        MusicDiscoverySection.RECENTLY_PLAYED -> UiState.Success(recentTracks)
        else -> tracksState
      }
}

class MusicStudioViewModel(
  private val musicRepository: MusicRepository,
  private val localStore: CreateStudioLocalStore,
  private val previewController: StudioAudioPreviewController,
  private val waveformExtractor: AudioWaveformExtractor,
  initialQuery: String = "",
) : ViewModel() {

  private val internalState =
    MutableStateFlow(
      MusicStudioUiState(
        activeSection =
          if (initialQuery.isNotBlank()) MusicDiscoverySection.SEARCH else MusicDiscoverySection.TRENDING,
        searchQuery = initialQuery,
      )
    )

  private var lastBrowseSection: MusicDiscoverySection = MusicDiscoverySection.TRENDING
  private var searchDebounceJob: Job? = null
  private var waveformJob: Job? = null
  private var loadJob: Job? = null

  val uiState: StateFlow<MusicStudioUiState> =
    combine(
      internalState,
      localStore.observeFavorites(),
      localStore.observeFavoriteIds(),
      localStore.observeRecentlyPlayed(),
      previewController.state,
    ) { base, favorites, favoriteIds, recent, playback ->
      base.copy(
        favoriteTracks = favorites,
        favoriteIds = favoriteIds,
        recentTracks = recent,
        playback = playback,
      )
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000L),
      initialValue = internalState.value,
    )

  init {
    if (initialQuery.isNotBlank()) {
      performSearch(initialQuery)
    } else {
      loadTrending()
    }
  }

  fun selectSection(section: MusicDiscoverySection) {
    if (section != MusicDiscoverySection.SEARCH) {
      lastBrowseSection = section
    }
    internalState.update { it.copy(activeSection = section, selectionError = null) }
    when (section) {
      MusicDiscoverySection.TRENDING -> loadTrending()
      MusicDiscoverySection.RECOMMENDATIONS -> loadRecommendations()
      MusicDiscoverySection.SEARCH -> {
        val q = internalState.value.searchQuery.trim()
        if (q.isNotEmpty()) performSearch(q)
      }
      MusicDiscoverySection.FAVORITES,
      MusicDiscoverySection.RECENTLY_PLAYED -> Unit
    }
  }

  fun selectCategory(category: AudiusDiscoveryCategory) {
    internalState.update {
      it.copy(
        selectedCategory = category,
        activeSection = MusicDiscoverySection.TRENDING,
        selectionError = null,
      )
    }
    lastBrowseSection = MusicDiscoverySection.TRENDING
    loadTrending(category)
  }

  fun onSearchQueryChanged(query: String) {
    val trimmed = query.trim()
    internalState.update {
      it.copy(
        searchQuery = query,
        activeSection = if (trimmed.isNotEmpty()) MusicDiscoverySection.SEARCH else lastBrowseSection,
        selectionError = null,
      )
    }
    searchDebounceJob?.cancel()
    if (trimmed.isEmpty()) {
      refreshActiveSection()
      return
    }
    searchDebounceJob =
      viewModelScope.launch {
        delay(350L)
        performSearch(trimmed)
      }
  }

  fun submitSearch() {
    searchDebounceJob?.cancel()
    val trimmed = internalState.value.searchQuery.trim()
    if (trimmed.isEmpty()) {
      clearSearch()
      return
    }
    internalState.update { it.copy(activeSection = MusicDiscoverySection.SEARCH, selectionError = null) }
    performSearch(trimmed)
  }

  fun clearSearch() {
    searchDebounceJob?.cancel()
    internalState.update {
      it.copy(
        searchQuery = "",
        activeSection = lastBrowseSection,
        selectionError = null,
      )
    }
    refreshActiveSection()
  }

  fun retry() {
    refreshActiveSection()
  }

  private fun refreshActiveSection() {
    when (internalState.value.activeSection) {
      MusicDiscoverySection.TRENDING -> loadTrending()
      MusicDiscoverySection.RECOMMENDATIONS -> loadRecommendations()
      MusicDiscoverySection.SEARCH -> {
        val q = internalState.value.searchQuery.trim()
        if (q.isNotEmpty()) performSearch(q) else loadTrending()
      }
      MusicDiscoverySection.FAVORITES,
      MusicDiscoverySection.RECENTLY_PLAYED -> Unit
    }
  }

  private fun loadTrending(category: AudiusDiscoveryCategory = internalState.value.selectedCategory) {
    loadJob?.cancel()
    internalState.update { it.copy(tracksState = UiState.Loading) }
    loadJob =
      viewModelScope.launch {
        val result = musicRepository.getTrendingTracks(genre = category.apiGenre)
        internalState.update { state ->
          result.fold(
            onSuccess = { tracks -> state.copy(tracksState = UiState.Success(tracks)) },
            onFailure = { error ->
              state.copy(
                tracksState =
                  UiState.Error(
                    error.localizedMessage ?: "Unable to load trending Audius tracks."
                  )
              )
            },
          )
        }
      }
  }

  private fun loadRecommendations() {
    loadJob?.cancel()
    internalState.update { it.copy(tracksState = UiState.Loading) }
    loadJob =
      viewModelScope.launch {
        val current = uiState.value
        val preferredGenres =
          (current.favoriteTracks + current.recentTracks)
            .mapNotNull { it.genre.trim().takeIf { g -> g.isNotEmpty() } }
            .distinct()
        val result = musicRepository.getRecommendedTracks(preferredGenres = preferredGenres)
        internalState.update { state ->
          result.fold(
            onSuccess = { tracks -> state.copy(tracksState = UiState.Success(tracks)) },
            onFailure = { error ->
              state.copy(
                tracksState =
                  UiState.Error(
                    error.localizedMessage ?: "Unable to load music recommendations."
                  )
              )
            },
          )
        }
      }
  }

  private fun performSearch(query: String) {
    loadJob?.cancel()
    internalState.update { it.copy(tracksState = UiState.Loading) }
    loadJob =
      viewModelScope.launch {
        val result = musicRepository.searchTracks(query = query)
        internalState.update { state ->
          result.fold(
            onSuccess = { tracks -> state.copy(tracksState = UiState.Success(tracks)) },
            onFailure = { error ->
              state.copy(
                tracksState =
                  UiState.Error(
                    error.localizedMessage ?: "Unable to search Audius music right now."
                  )
              )
            },
          )
        }
      }
  }

  fun onTogglePreview(track: MusicTrack) {
    if (!track.isStreamable) {
      internalState.update {
        it.copy(selectionError = "\"${track.title}\" cannot be streamed due to creator licensing settings.")
      }
      return
    }
    val currentPlayback = previewController.state.value
    if (currentPlayback.activeTrackId == track.id && (currentPlayback.isPlaying || currentPlayback.isBuffering)) {
      previewController.pause()
      return
    }

    internalState.update { it.copy(selectionError = null) }
    viewModelScope.launch {
      localStore.recordRecentlyPlayed(track)
    }
    previewController.playPreview(track, startMs = 0L, endMs = track.durationMs)
  }

  fun onToggleFavorite(track: MusicTrack) {
    viewModelScope.launch {
      localStore.toggleFavorite(track)
    }
  }

  fun clearRecentlyPlayed() {
    viewModelScope.launch {
      localStore.clearRecentlyPlayed()
    }
  }

  fun openTrimmer(track: MusicTrack) {
    if (!track.isStreamable) {
      internalState.update {
        it.copy(selectionError = "\"${track.title}\" has streaming restricted by its artist.")
      }
      return
    }
    val defaultClipDuration = minOf(MusicTrimSelection.DEFAULT_TRIM_DURATION_MS, track.durationMs)
    val initialSelection =
      MusicTrimSelection(
        track = track,
        startMs = 0L,
        durationMs = defaultClipDuration,
      )
    internalState.update {
      it.copy(
        trimSelection = initialSelection,
        isSamplingWaveform = true,
        isWaveformRealDecoded = false,
        selectionError = null,
      )
    }
    viewModelScope.launch {
      localStore.recordRecentlyPlayed(track)
    }
    previewController.playPreview(
      track = track,
      startMs = initialSelection.clampedStartMs,
      endMs = initialSelection.endMs,
    )

    waveformJob?.cancel()
    waveformJob =
      viewModelScope.launch {
        val sampled = waveformExtractor.sampleWaveform(track)
        internalState.update { state ->
          val active = state.trimSelection
          if (active != null && active.track.id == track.id) {
            state.copy(
              trimSelection = active.copy(waveformAmplitudes = sampled.amplitudes),
              isSamplingWaveform = false,
              isWaveformRealDecoded = sampled.isDecodedFromAudio,
            )
          } else {
            state.copy(isSamplingWaveform = false)
          }
        }
      }
  }

  fun dismissTrimmer() {
    waveformJob?.cancel()
    previewController.pause()
    internalState.update {
      it.copy(
        trimSelection = null,
        isSamplingWaveform = false,
        isVerifyingSelection = false,
        selectionError = null,
      )
    }
  }

  fun updateTrimStart(startMs: Long) {
    val current = internalState.value.trimSelection ?: return
    val maxStart = (current.track.durationMs - minTrimDurationFor(current.track)).coerceAtLeast(0L)
    val clampedStart = startMs.coerceIn(0L, maxStart)
    val maxDur = (current.track.durationMs - clampedStart).coerceAtLeast(minTrimDurationFor(current.track))
    val nextDuration = current.durationMs.coerceIn(minTrimDurationFor(current.track), minOf(MusicTrimSelection.MAX_TRIM_DURATION_MS, maxDur))
    val next = current.copy(startMs = clampedStart, durationMs = nextDuration)
    internalState.update { it.copy(trimSelection = next) }
    previewController.updateTrimWindow(next.clampedStartMs, next.endMs)
  }

  fun updateTrimDuration(durationMs: Long) {
    val current = internalState.value.trimSelection ?: return
    val minDur = minTrimDurationFor(current.track)
    val maxAllowed = minOf(MusicTrimSelection.MAX_TRIM_DURATION_MS, current.track.durationMs)
    val clampedDuration = durationMs.coerceIn(minDur, maxAllowed.coerceAtLeast(minDur))
    val maxStart = (current.track.durationMs - clampedDuration).coerceAtLeast(0L)
    val adjustedStart = current.startMs.coerceIn(0L, maxStart)
    val next = current.copy(startMs = adjustedStart, durationMs = clampedDuration)
    internalState.update { it.copy(trimSelection = next) }
    previewController.updateTrimWindow(next.clampedStartMs, next.endMs)
  }

  fun toggleTrimPreviewPlayback() {
    val current = internalState.value.trimSelection ?: return
    val playback = previewController.state.value
    if (playback.activeTrackId == current.track.id && (playback.isPlaying || playback.isBuffering)) {
      previewController.pause()
    } else {
      previewController.playPreview(current.track, current.clampedStartMs, current.endMs)
    }
  }

  fun seekTrimPreview(positionMs: Long) {
    previewController.seekTo(positionMs)
  }

  fun confirmTrimSelection(onSoundSelected: (MusicTrimSelection) -> Unit) {
    val current = internalState.value.trimSelection ?: return
    if (internalState.value.isVerifyingSelection) return

    internalState.update { it.copy(isVerifyingSelection = true, selectionError = null) }
    viewModelScope.launch {
      val verification = musicRepository.verifyTrackStreamable(current.track.id)
      verification.fold(
        onSuccess = { verifiedTrack ->
          val finalSelection =
            current.copy(
              track = verifiedTrack,
              startMs = current.clampedStartMs,
              durationMs = current.clampedDurationMs,
            )
          localStore.recordRecentlyPlayed(verifiedTrack)
          previewController.pause()
          internalState.update {
            it.copy(
              trimSelection = null,
              isVerifyingSelection = false,
              selectionError = null,
            )
          }
          onSoundSelected(finalSelection)
        },
        onFailure = { error ->
          val msg =
            when (error) {
              is AudiusTrackUnavailableException -> error.message ?: "Track is unavailable for streaming."
              else -> error.localizedMessage ?: "Could not verify Audius stream license. Check connection and retry."
            }
          internalState.update {
            it.copy(
              isVerifyingSelection = false,
              selectionError = msg,
            )
          }
        },
      )
    }
  }

  fun openLicenseInfo(track: MusicTrack) {
    internalState.update { it.copy(licenseDialogTrack = track) }
  }

  fun dismissLicenseInfo() {
    internalState.update { it.copy(licenseDialogTrack = null) }
  }

  fun dismissSelectionError() {
    internalState.update { it.copy(selectionError = null) }
  }

  private fun minTrimDurationFor(track: MusicTrack): Long =
    minOf(MusicTrimSelection.MIN_TRIM_DURATION_MS, track.durationMs)

  override fun onCleared() {
    waveformJob?.cancel()
    searchDebounceJob?.cancel()
    loadJob?.cancel()
    previewController.release()
    super.onCleared()
  }
}
