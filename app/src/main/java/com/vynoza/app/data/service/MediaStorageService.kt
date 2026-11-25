package com.vynoza.app.data.service

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class LocalVideoClip(
  val uri: Uri,
  val displayName: String,
  val sizeBytes: Long?,
  val mimeType: String?,
  val source: ClipSource,
)

enum class ClipSource {
  GALLERY_PICKER,
  CAMERA_CAPTURE,
  TEMPLATE_STUDIO,
}

sealed interface ClipValidationResult {
  data class Valid(val resolvedMimeType: String, val sizeBytes: Long?) : ClipValidationResult
  data class Invalid(val reason: String) : ClipValidationResult
}

sealed interface VideoUploadResult {
  data class Success(val secureUrl: String, val publicId: String) : VideoUploadResult
  data class Error(val message: String) : VideoUploadResult
}

interface MediaStorageService {
  fun inspectLocalClip(context: Context, uri: Uri, source: ClipSource): LocalVideoClip
  fun validateClipForUpload(context: Context, clip: LocalVideoClip): ClipValidationResult
  suspend fun uploadVideo(
    context: Context,
    clip: LocalVideoClip,
    onProgress: (Float?) -> Unit,
  ): VideoUploadResult
}

class CloudinaryMediaStorageService(
  private val client: OkHttpClient = defaultOkHttpClient(),
  private val uploadEndpoint: String = CLOUDINARY_VIDEO_UPLOAD_ENDPOINT,
) : MediaStorageService {

  override fun inspectLocalClip(context: Context, uri: Uri, source: ClipSource): LocalVideoClip {
    var name = "vynoza_clip.mp4"
    var size: Long? = null
    try {
      context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
          if (nameIdx >= 0) {
            cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
          }
          if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
            size = cursor.getLong(sizeIdx)
          }
        }
      }
    } catch (_: Exception) {
      // Retain safe fallback metadata if provider omits size/display columns
    }

    if (size == null) {
      size = resolveAssetLength(context.contentResolver, uri)
    }

    val mimeType = context.contentResolver.getType(uri)
    return LocalVideoClip(
      uri = uri,
      displayName = name,
      sizeBytes = size,
      mimeType = mimeType,
      source = source,
    )
  }

  override fun validateClipForUpload(context: Context, clip: LocalVideoClip): ClipValidationResult {
    val contentResolver = context.contentResolver
    val resolvedMime = resolveVideoMimeType(contentResolver, clip)
      ?: return ClipValidationResult.Invalid(
        "Unsupported file type. Please choose a valid video (MP4, MOV, WebM, or 3GP)."
      )

    if (!resolvedMime.startsWith("video/", ignoreCase = true)) {
      return ClipValidationResult.Invalid(
        "Only video files can be published to VYNOZA (detected: $resolvedMime)."
      )
    }

    val resolvedSize = clip.sizeBytes ?: resolveAssetLength(contentResolver, clip.uri)
    if (resolvedSize != null && resolvedSize <= 0L) {
      return ClipValidationResult.Invalid("The selected video file is empty or incomplete.")
    }
    if (resolvedSize != null && resolvedSize > MAX_VIDEO_SIZE_BYTES) {
      return ClipValidationResult.Invalid(
        "Video exceeds the 100 MB upload limit. Please select or record a shorter clip."
      )
    }

    val canOpenNonEmpty =
      try {
        contentResolver.openInputStream(clip.uri)?.use { stream ->
          if (resolvedSize != null) {
            true
          } else {
            stream.read() != -1
          }
        } ?: false
      } catch (_: SecurityException) {
        false
      } catch (_: Exception) {
        false
      }

    if (!canOpenNonEmpty) {
      return ClipValidationResult.Invalid(
        if (resolvedSize == null) {
          "The selected video file is empty or unreadable. Please select or record it again."
        } else {
          "Unable to read the selected video file. Please select or record it again."
        }
      )
    }

    return ClipValidationResult.Valid(resolvedMimeType = resolvedMime, sizeBytes = resolvedSize)
  }

  override suspend fun uploadVideo(
    context: Context,
    clip: LocalVideoClip,
    onProgress: (Float?) -> Unit,
  ): VideoUploadResult {
    val validation = validateClipForUpload(context, clip)
    if (validation is ClipValidationResult.Invalid) {
      return VideoUploadResult.Error(validation.reason)
    }
    val valid = validation as ClipValidationResult.Valid

    val mediaType = valid.resolvedMimeType.toMediaTypeOrNull() ?: "video/mp4".toMediaType()
    val safeFileName = sanitizeFileName(clip.displayName, valid.resolvedMimeType)
    val streamingBody =
      UriStreamingRequestBody(
        contentResolver = context.contentResolver,
        uri = clip.uri,
        mediaType = mediaType,
        knownSizeBytes = valid.sizeBytes,
        maxAllowedBytes = MAX_VIDEO_SIZE_BYTES,
        onProgress = onProgress,
      )

    val request = buildUnsignedUploadRequest(fileName = safeFileName, fileBody = streamingBody)

    return try {
      onProgress(if (valid.sizeBytes != null && valid.sizeBytes > 0L) 0f else null)
      executeCall(request)
    } catch (e: CancellationException) {
      throw e
    } catch (e: UnknownHostException) {
      VideoUploadResult.Error("No internet connection. Reconnect and try publishing again.")
    } catch (e: SocketTimeoutException) {
      VideoUploadResult.Error("Upload timed out due to a slow connection. Please try again.")
    } catch (e: IOException) {
      val reason = e.message?.takeIf { it.isNotBlank() } ?: "Network error while uploading video."
      VideoUploadResult.Error(reason)
    } catch (e: Exception) {
      VideoUploadResult.Error(
        e.localizedMessage ?: "Unexpected error while uploading video to Cloudinary."
      )
    }
  }

  private suspend fun executeCall(request: Request): VideoUploadResult =
    suspendCancellableCoroutine { continuation ->
      val call = client.newCall(request)
      continuation.invokeOnCancellation { call.cancel() }

      call.enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isActive) return
            val result =
              when (e) {
                is UnknownHostException ->
                  VideoUploadResult.Error(
                    "No internet connection. Reconnect and try publishing again."
                  )
                is SocketTimeoutException ->
                  VideoUploadResult.Error(
                    "Upload timed out. Check your connection and try again."
                  )
                else ->
                  VideoUploadResult.Error(
                    e.message?.takeIf { it.isNotBlank() }
                      ?: "Network interruption during upload. Please try again."
                  )
              }
            continuation.resume(result)
          }

          override fun onResponse(call: Call, response: Response) {
            response.use { res ->
              val bodyText =
                try {
                  res.body?.string().orEmpty()
                } catch (e: IOException) {
                  if (continuation.isActive) {
                    continuation.resume(
                      VideoUploadResult.Error("Failed to read upload server response.")
                    )
                  }
                  return
                }
              if (continuation.isActive) {
                continuation.resume(parseCloudinaryResponse(res.code, bodyText))
              }
            }
          }
        }
      )
    }

  internal fun buildUnsignedUploadRequest(
    fileName: String,
    fileBody: RequestBody,
  ): Request {
    val multipartBody =
      MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("upload_preset", CLOUDINARY_UPLOAD_PRESET)
        .addFormDataPart("folder", CLOUDINARY_UPLOAD_FOLDER)
        .addFormDataPart("file", fileName, fileBody)
        .build()

    return Request.Builder()
      .url(uploadEndpoint)
      .post(multipartBody)
      .build()
  }

  internal companion object {
    const val CLOUDINARY_CLOUD_NAME = "idkq3e9l"
    const val CLOUDINARY_UPLOAD_PRESET = "vynoza_videos"
    const val CLOUDINARY_UPLOAD_FOLDER = "vynoza/videos"
    const val CLOUDINARY_VIDEO_UPLOAD_ENDPOINT =
      "https://api.cloudinary.com/v1_1/$CLOUDINARY_CLOUD_NAME/video/upload"
    const val MAX_VIDEO_SIZE_BYTES = 100L * 1024L * 1024L

    private val SUPPORTED_EXTENSIONS =
      mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "3gp" to "video/3gpp",
        "3gpp" to "video/3gpp",
        "mkv" to "video/x-matroska",
      )

    fun defaultOkHttpClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    internal fun parseCloudinaryResponse(statusCode: Int, responseBody: String): VideoUploadResult {
      val json =
        try {
          if (responseBody.isBlank()) null else JSONObject(responseBody)
        } catch (_: Exception) {
          null
        }

      if (statusCode in 200..299) {
        if (json == null) {
          return VideoUploadResult.Error("Cloudinary returned an invalid response.")
        }
        val secureUrl = json.optString("secure_url", "").trim()
        val publicId = json.optString("public_id", "").trim()
        if (!secureUrl.startsWith("https://")) {
          return VideoUploadResult.Error("Upload succeeded without a valid HTTPS playback URL.")
        }
        if (publicId.isEmpty()) {
          return VideoUploadResult.Error("Upload succeeded without a Cloudinary public_id.")
        }
        if (secureUrl.length > 500 || publicId.length > 500) {
          return VideoUploadResult.Error(
            "Cloudinary metadata exceeded the supported 500-character storage limit."
          )
        }
        return VideoUploadResult.Success(secureUrl = secureUrl, publicId = publicId)
      }

      val cloudinaryMessage =
        json?.optJSONObject("error")?.optString("message", "")?.trim()?.takeIf { it.isNotBlank() }
      val message =
        when {
          statusCode == 401 &&
            cloudinaryMessage?.startsWith("Unknown API key", ignoreCase = true) == true ->
            "Cloudinary upload failed (401): $cloudinaryMessage. " +
              "No api_key is sent in unsigned mode — verify that Cloud name '$CLOUDINARY_CLOUD_NAME' " +
              "matches your active Cloudinary Product Environment exactly and preset '$CLOUDINARY_UPLOAD_PRESET' is Unsigned."
          cloudinaryMessage != null -> "Cloudinary upload failed ($statusCode): $cloudinaryMessage"
          statusCode == 400 -> "Cloudinary rejected the video file or upload preset ($statusCode)."
          statusCode == 401 || statusCode == 403 ->
            "Cloudinary upload preset '$CLOUDINARY_UPLOAD_PRESET' must allow unsigned video uploads ($statusCode)."
          statusCode == 413 -> "Video file exceeds Cloudinary's maximum upload size."
          statusCode in 500..599 -> "Cloudinary service is temporarily unavailable ($statusCode)."
          else -> "Upload failed with HTTP $statusCode."
        }
      return VideoUploadResult.Error(message)
    }

    private fun resolveAssetLength(contentResolver: ContentResolver, uri: Uri): Long? {
      return try {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
          afd.length.takeIf { it >= 0L }
        }
      } catch (_: Exception) {
        null
      }
    }

    private fun resolveVideoMimeType(contentResolver: ContentResolver, clip: LocalVideoClip): String? {
      val providerType = clip.mimeType?.takeIf { it.isNotBlank() } ?: contentResolver.getType(clip.uri)
      if (!providerType.isNullOrBlank() && providerType != "application/octet-stream") {
        return providerType
      }
      val extensionFromName =
        clip.displayName.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() }
      val extensionFromUri =
        MimeTypeMap.getFileExtensionFromUrl(clip.uri.toString()).lowercase().takeIf { it.isNotBlank() }
      val ext = extensionFromName ?: extensionFromUri ?: return null
      return SUPPORTED_EXTENSIONS[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun sanitizeFileName(rawName: String, mimeType: String): String {
      val trimmed = rawName.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
      if (trimmed.isNotBlank() && trimmed.contains('.')) {
        return trimmed.take(80)
      }
      val ext =
        when (mimeType.lowercase()) {
          "video/quicktime" -> "mov"
          "video/webm" -> "webm"
          "video/3gpp" -> "3gp"
          else -> "mp4"
        }
      return "vynoza_video.$ext"
    }
  }
}

private class UriStreamingRequestBody(
  private val contentResolver: ContentResolver,
  private val uri: Uri,
  private val mediaType: MediaType,
  private val knownSizeBytes: Long?,
  private val maxAllowedBytes: Long,
  private val onProgress: (Float?) -> Unit,
) : RequestBody() {

  override fun contentType(): MediaType = mediaType

  override fun contentLength(): Long = knownSizeBytes?.takeIf { it > 0L } ?: -1L

  override fun writeTo(sink: BufferedSink) {
    val input =
      contentResolver.openInputStream(uri)
        ?: throw IOException("Unable to open the selected video file for upload.")

    input.use { stream ->
      val buffer = ByteArray(BUFFER_SIZE_BYTES)
      var totalWritten = 0L
      var lastReportedPercent = -1
      val expectedTotal = knownSizeBytes?.takeIf { it > 0L }

      while (true) {
        val read = stream.read(buffer)
        if (read == -1) break
        totalWritten += read
        if (totalWritten > maxAllowedBytes) {
          throw IOException("Video exceeds the 100 MB upload limit.")
        }
        sink.write(buffer, 0, read)

        if (expectedTotal != null) {
          val fraction = (totalWritten.toDouble() / expectedTotal.toDouble()).toFloat().coerceIn(0f, 1f)
          val percent = (fraction * 100).toInt()
          if (percent != lastReportedPercent) {
            lastReportedPercent = percent
            onProgress(fraction)
          }
        } else {
          onProgress(null)
        }
      }

      if (totalWritten == 0L) {
        throw IOException("The selected video file is empty.")
      }
      onProgress(1f)
    }
  }

  private companion object {
    const val BUFFER_SIZE_BYTES = 16 * 1024
  }
}
