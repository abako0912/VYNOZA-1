package com.vynoza.app.data.music

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.vynoza.app.core.studio.MusicTrack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

data class WaveformSampleResult(
  val amplitudes: List<Float>,
  val isDecodedFromAudio: Boolean,
)

interface AudioWaveformExtractor {
  suspend fun sampleWaveform(track: MusicTrack, barCount: Int = DEFAULT_BAR_COUNT): WaveformSampleResult

  companion object {
    const val DEFAULT_BAR_COUNT = 56
  }
}

class MediaExtractorWaveformSampler(
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val apiKey: String? = AudiusMusicRepository.resolveConfiguredApiKey(),
) : AudioWaveformExtractor {

  override suspend fun sampleWaveform(
    track: MusicTrack,
    barCount: Int,
  ): WaveformSampleResult =
    withContext(ioDispatcher) {
      val safeCount = barCount.coerceIn(16, 120)
      val decoded = runCatching { extractFromStreamUrl(track.streamUrl, safeCount) }.getOrNull()
      if (decoded != null && decoded.size == safeCount) {
        WaveformSampleResult(amplitudes = decoded, isDecodedFromAudio = true)
      } else {
        WaveformSampleResult(
          amplitudes = AudioWaveformSampler.fallbackEnvelope(track.id, track.durationSeconds, safeCount),
          isDecodedFromAudio = false,
        )
      }
    }

  private fun extractFromStreamUrl(streamUrl: String, barCount: Int): List<Float>? {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    return try {
      val headers =
        buildMap {
          put("User-Agent", "VYNOZA-Android")
          AudiusMusicRepository.sanitizeApiKey(apiKey)?.let { put("x-api-key", it) }
        }
      extractor.setDataSource(streamUrl, headers)
      var audioTrackIndex = -1
      var audioFormat: MediaFormat? = null
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith("audio/")) {
          audioTrackIndex = i
          audioFormat = format
          break
        }
      }
      if (audioTrackIndex < 0 || audioFormat == null) return null

      extractor.selectTrack(audioTrackIndex)
      val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
      val durationUs =
        if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
          audioFormat.getLong(MediaFormat.KEY_DURATION)
        } else {
          0L
        }
      if (durationUs <= 0L) return null

      codec = MediaCodec.createDecoderByType(mime)
      codec.configure(audioFormat, null, null, 0)
      codec.start()

      val peaks = FloatArray(barCount)
      val bufferInfo = MediaCodec.BufferInfo()
      val stepUs = (durationUs / barCount).coerceAtLeast(50_000L)
      val startMs = System.currentTimeMillis()

      for (barIndex in 0 until barCount) {
        // Bound network decode latency so trimming remains responsive on cellular connections
        if (System.currentTimeMillis() - startMs > 3_500L) {
          for (fill in barIndex until barCount) {
            peaks[fill] = peaks[(barIndex - 1).coerceAtLeast(0)]
          }
          break
        }
        val targetUs = barIndex * stepUs
        extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        codec.flush()

        var barPeak = 0f
        var packetsRead = 0
        while (packetsRead < 3) {
          val inIdx = codec.dequeueInputBuffer(15_000L)
          if (inIdx >= 0) {
            val inBuf = codec.getInputBuffer(inIdx) ?: break
            val sampleSize = extractor.readSampleData(inBuf, 0)
            if (sampleSize < 0) {
              codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              break
            } else {
              codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
              extractor.advance()
              packetsRead++
            }
          }

          val outIdx = codec.dequeueOutputBuffer(bufferInfo, 25_000L)
          if (outIdx >= 0) {
            val outBuf = codec.getOutputBuffer(outIdx)
            if (outBuf != null && bufferInfo.size > 0) {
              outBuf.position(bufferInfo.offset)
              outBuf.limit(bufferInfo.offset + bufferInfo.size)
              val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
              val samples = ShortArray(shortBuf.remaining())
              shortBuf.get(samples)
              val rms = AudioWaveformSampler.computeRms(samples)
              if (rms > barPeak) barPeak = rms
            }
            codec.releaseOutputBuffer(outIdx, false)
          }
        }
        peaks[barIndex] = barPeak
      }

      AudioWaveformSampler.normalizeBars(peaks.toList())
    } finally {
      runCatching { codec?.stop() }
      runCatching { codec?.release() }
      runCatching { extractor.release() }
    }
  }
}

object AudioWaveformSampler {
  fun computeRms(samples: ShortArray): Float {
    if (samples.isEmpty()) return 0f
    var sumSquares = 0.0
    for (sample in samples) {
      val normalized = sample / 32768.0
      sumSquares += normalized * normalized
    }
    return sqrt(sumSquares / samples.size).toFloat().coerceIn(0f, 1f)
  }

  fun computeAmplitudesFromPcm(samples: ShortArray, barCount: Int): List<Float> {
    val safeCount = barCount.coerceAtLeast(1)
    if (samples.isEmpty()) return List(safeCount) { 0.15f }
    val chunkSize = (samples.size / safeCount).coerceAtLeast(1)
    val raw =
      (0 until safeCount).map { bar ->
        val start = (bar * chunkSize).coerceAtMost(samples.lastIndex)
        val end = ((bar + 1) * chunkSize).coerceAtMost(samples.size)
        var peak = 0f
        for (i in start until end) {
          val mag = abs(samples[i].toInt()) / 32768f
          if (mag > peak) peak = mag
        }
        peak
      }
    return normalizeBars(raw)
  }

  fun normalizeBars(raw: List<Float>): List<Float> {
    if (raw.isEmpty()) return emptyList()
    val maxVal = raw.maxOrNull()?.takeIf { it > 0.001f } ?: return List(raw.size) { 0.16f }
    return raw.map { value ->
      (0.16f + (value / maxVal).coerceIn(0f, 1f) * 0.84f).coerceIn(0.16f, 1f)
    }
  }

  fun fallbackEnvelope(seedId: String, durationSeconds: Int, barCount: Int): List<Float> {
    val safeCount = barCount.coerceIn(16, 120)
    val seed = (seedId.hashCode().toLong() * 31L) + durationSeconds.toLong()
    return List(safeCount) { index ->
      val phase = ((seed xor (index * 2654435761L)) and 0xFFL).toFloat() / 255f
      val phraseShape = 0.45f + 0.35f * kotlin.math.sin(index * 0.42f + (seed % 7)).let { (it + 1f) * 0.5f }
      (0.20f + phraseShape * 0.48f + phase * 0.32f).coerceIn(0.18f, 0.96f)
    }
  }
}
