package com.vynoza.app.data.music

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.vynoza.app.core.studio.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AudioPreviewPlaybackState(
  val activeTrackId: String? = null,
  val isPlaying: Boolean = false,
  val isBuffering: Boolean = false,
  val positionMs: Long = 0L,
  val trimStartMs: Long = 0L,
  val trimEndMs: Long = 0L,
  val errorMessage: String? = null,
)

interface StudioAudioPreviewController {
  val state: StateFlow<AudioPreviewPlaybackState>
  fun playPreview(track: MusicTrack, startMs: Long = 0L, endMs: Long = track.durationMs)
  fun updateTrimWindow(startMs: Long, endMs: Long)
  fun seekTo(positionMs: Long)
  fun setVolume(volume: Float) {}
  fun pause()
  fun resume()
  fun stop()
  fun release()
}

class ExoStudioAudioPreviewController(
  context: Context,
  private val apiKey: String? = AudiusMusicRepository.resolveConfiguredApiKey(),
) : StudioAudioPreviewController {

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var tickerJob: Job? = null
  private var player: ExoPlayer? = null
  private var currentVolume: Float = 1f

  private val _state = MutableStateFlow(AudioPreviewPlaybackState())
  override val state: StateFlow<AudioPreviewPlaybackState> = _state.asStateFlow()

  @OptIn(UnstableApi::class)
  private fun getOrCreatePlayer(): ExoPlayer {
    player?.let { return it }
    val audioAttrs =
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()
    val httpDataSourceFactory =
      DefaultHttpDataSource.Factory()
        .setUserAgent("VYNOZA-Android")
        .setAllowCrossProtocolRedirects(true)
        .apply {
          AudiusMusicRepository.sanitizeApiKey(apiKey)?.let { key ->
            setDefaultRequestProperties(mapOf("x-api-key" to key))
          }
        }
    return ExoPlayer.Builder(appContext)
      .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
      .setAudioAttributes(audioAttrs, true)
      .build()
      .also { exo ->
        exo.volume = currentVolume
        exo.addListener(
          object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
              _state.update {
                it.copy(
                  isBuffering = playbackState == Player.STATE_BUFFERING,
                  isPlaying = exo.isPlaying,
                )
              }
              if (playbackState == Player.STATE_ENDED) {
                val loopStart = _state.value.trimStartMs
                exo.seekTo(loopStart)
                exo.playWhenReady = true
              }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
              _state.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlayerError(error: PlaybackException) {
              _state.update {
                it.copy(
                  isPlaying = false,
                  isBuffering = false,
                  errorMessage = "Audio preview unavailable on Audius right now (${error.errorCodeName}).",
                )
              }
            }
          }
        )
        player = exo
      }
  }

  override fun playPreview(track: MusicTrack, startMs: Long, endMs: Long) {
    if (!track.isStreamable || track.streamUrl.isBlank()) {
      _state.update {
        it.copy(
          activeTrackId = track.id,
          isPlaying = false,
          isBuffering = false,
          errorMessage = "\"${track.title}\" is not streamable under its artist license.",
        )
      }
      return
    }
    val safeStart = startMs.coerceIn(0L, track.durationMs)
    val safeEnd = endMs.coerceIn(safeStart + 1_000L, track.durationMs)

    val exo = getOrCreatePlayer()
    exo.volume = currentVolume
    val sameTrack = _state.value.activeTrackId == track.id && exo.mediaItemCount > 0
    _state.update {
      it.copy(
        activeTrackId = track.id,
        isBuffering = !sameTrack,
        positionMs = safeStart,
        trimStartMs = safeStart,
        trimEndMs = safeEnd,
        errorMessage = null,
      )
    }

    if (!sameTrack) {
      exo.setMediaItem(MediaItem.fromUri(track.streamUrl))
      exo.prepare()
    }
    exo.seekTo(safeStart)
    exo.playWhenReady = true
    startTicker()
  }

  override fun updateTrimWindow(startMs: Long, endMs: Long) {
    val safeStart = startMs.coerceAtLeast(0L)
    val safeEnd = endMs.coerceAtLeast(safeStart + 1_000L)
    _state.update { it.copy(trimStartMs = safeStart, trimEndMs = safeEnd) }
    player?.let { exo ->
      if (exo.currentPosition < safeStart || exo.currentPosition >= safeEnd) {
        exo.seekTo(safeStart)
      }
    }
  }

  override fun seekTo(positionMs: Long) {
    val current = _state.value
    val clamped =
      positionMs.coerceIn(
        current.trimStartMs,
        current.trimEndMs.coerceAtLeast(current.trimStartMs),
      )
    player?.seekTo(clamped)
    _state.update { it.copy(positionMs = clamped) }
  }

  override fun setVolume(volume: Float) {
    currentVolume = volume.coerceIn(0f, 1f)
    player?.volume = currentVolume
  }

  override fun pause() {
    player?.playWhenReady = false
    _state.update { it.copy(isPlaying = false) }
  }

  override fun resume() {
    val exo = player ?: return
    if (_state.value.activeTrackId != null) {
      exo.playWhenReady = true
      startTicker()
    }
  }

  override fun stop() {
    tickerJob?.cancel()
    tickerJob = null
    currentVolume = 1f
    player?.volume = 1f
    player?.stop()
    _state.value = AudioPreviewPlaybackState()
  }

  override fun release() {
    tickerJob?.cancel()
    tickerJob = null
    player?.release()
    player = null
    scope.cancel()
  }

  private fun startTicker() {
    if (tickerJob?.isActive == true) return
    tickerJob =
      scope.launch {
        while (isActive) {
          val exo = player
          val st = _state.value
          if (exo != null && st.activeTrackId != null) {
            val pos = exo.currentPosition.coerceAtLeast(0L)
            if (st.trimEndMs > st.trimStartMs && pos >= st.trimEndMs && exo.isPlaying) {
              exo.seekTo(st.trimStartMs)
              _state.update { it.copy(positionMs = st.trimStartMs) }
            } else {
              _state.update { it.copy(positionMs = pos) }
            }
          }
          delay(80L)
        }
      }
  }
}
