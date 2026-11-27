package com.vynoza.app.data.studio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Scales raw PCM audio samples (16-bit signed or 32-bit float) by [volume] in 0f..1f
 * for Media3 Transformer export mixing and ExoPlayer preview playback.
 */
@OptIn(UnstableApi::class)
class VolumeAudioProcessor(
  volume: Float,
) : BaseAudioProcessor() {

  @Volatile
  var volume: Float = volume.coerceIn(0f, 1f)
    private set

  fun updateVolume(newVolume: Float) {
    volume = newVolume.coerceIn(0f, 1f)
  }

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    return when (inputAudioFormat.encoding) {
      C.ENCODING_PCM_16BIT,
      C.ENCODING_PCM_FLOAT -> inputAudioFormat
      else -> throw UnhandledAudioFormatException(inputAudioFormat)
    }
  }

  override fun isActive(): Boolean {
    return super.isActive() && volume < 0.999f
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val remaining = inputBuffer.remaining()
    if (remaining == 0) return

    val output = replaceOutputBuffer(remaining)
    val gain = volume.coerceIn(0f, 1f)

    when (inputAudioFormat.encoding) {
      C.ENCODING_PCM_16BIT -> {
        var pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        while (pos + 1 < limit) {
          val sample = inputBuffer.getShort(pos)
          val scaled =
            if (gain <= 0.0001f) {
              0
            } else {
              (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            }
          output.putShort(scaled.toShort())
          pos += 2
        }
        inputBuffer.position(limit)
      }

      C.ENCODING_PCM_FLOAT -> {
        var pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        while (pos + 3 < limit) {
          val sample = inputBuffer.getFloat(pos)
          val scaled =
            if (gain <= 0.0001f) {
              0f
            } else {
              (sample * gain).coerceIn(-1.0f, 1.0f)
            }
          output.putFloat(scaled)
          pos += 4
        }
        inputBuffer.position(limit)
      }
    }

    output.flip()
  }
}
