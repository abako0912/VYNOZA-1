package com.vynoza.app.data.templates

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.vynoza.app.core.templates.SelectedTemplateMedia
import com.vynoza.app.core.templates.TemplateMediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object TemplateMediaStorage {
  private const val TEMPLATE_CACHE_DIR = "templates"
  private const val MIN_PHOTO_FREE_BYTES = 15L * 1024L * 1024L
  private const val MIN_VIDEO_FREE_BYTES = 40L * 1024L * 1024L

  fun inspectSelectedMedia(context: Context, uri: Uri): SelectedTemplateMedia? {
    val resolver = context.contentResolver
    var displayName = "vynoza_media"
    var sizeBytes: Long? = null
    try {
      resolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
          if (nameIndex >= 0) {
            cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { displayName = it }
          }
          if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
            sizeBytes = cursor.getLong(sizeIndex)
          }
        }
      }
    } catch (_: Exception) {
      // Fallback to URI inspection
    }

    if (sizeBytes == null) {
      sizeBytes = resolveLength(resolver, uri)
    }

    val mime = resolveMimeType(resolver, uri, displayName)
    val kind =
      when {
        mime?.startsWith("video/", ignoreCase = true) == true -> TemplateMediaKind.VIDEO
        mime?.startsWith("image/", ignoreCase = true) == true -> TemplateMediaKind.PHOTO
        hasVideoExtension(displayName, uri) -> TemplateMediaKind.VIDEO
        hasImageExtension(displayName, uri) -> TemplateMediaKind.PHOTO
        else -> return null
      }

    return SelectedTemplateMedia(
      uri = uri,
      kind = kind,
      displayName = displayName,
      mimeType = mime,
      sizeBytes = sizeBytes,
    )
  }

  fun ensureEnoughStorageForPhoto(context: Context): Result<Unit> {
    val available = queryAvailableBytes(context)
    return if (available != null && available < MIN_PHOTO_FREE_BYTES) {
      Result.failure(
        IOException("Insufficient device storage (${available / (1024 * 1024)} MB free). Free up at least 15 MB to save this photo.")
      )
    } else {
      Result.success(Unit)
    }
  }

  fun ensureEnoughStorageForVideo(context: Context, estimatedInputBytes: Long?): Result<Unit> {
    val requiredBytes =
      maxOf(MIN_VIDEO_FREE_BYTES, (estimatedInputBytes ?: 0L).coerceAtMost(200L * 1024L * 1024L) * 2L)
    val available = queryAvailableBytes(context)
    return if (available != null && available < requiredBytes) {
      Result.failure(
        IOException(
          "Insufficient storage for video rendering (${available / (1024 * 1024)} MB free, " +
            "${requiredBytes / (1024 * 1024)} MB recommended)."
        )
      )
    } else {
      Result.success(Unit)
    }
  }

  fun createTempOutputFile(context: Context, prefix: String, extension: String): File {
    val dir = File(context.cacheDir, TEMPLATE_CACHE_DIR).apply { mkdirs() }
    val cleanExt = extension.trimStart('.')
    return File(dir, "${prefix}_${System.currentTimeMillis()}.$cleanExt")
  }

  fun fileProviderUriFor(context: Context, file: File): Uri {
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
  }

  suspend fun saveJpegToGallery(context: Context, sourceFile: File, titlePrefix: String): Result<Uri> =
    withContext(Dispatchers.IO) {
      runCatching {
        ensureEnoughStorageForPhoto(context).getOrThrow()
        if (!sourceFile.exists() || sourceFile.length() <= 0L) {
          throw IOException("Rendered image file is empty or missing.")
        }
        val fileName = "${titlePrefix}_${System.currentTimeMillis()}.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          val values =
            ContentValues().apply {
              put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
              put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
              put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VYNOZA")
              put(MediaStore.Images.Media.IS_PENDING, 1)
            }
          val resolver = context.contentResolver
          val itemUri =
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
              ?: throw IOException("Unable to create gallery photo entry.")
          try {
            resolver.openOutputStream(itemUri)?.use { out ->
              sourceFile.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("Unable to write rendered photo to MediaStore.")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            itemUri
          } catch (e: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw e
          }
        } else {
          val picturesDir =
            File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, "VYNOZA")
              .apply { mkdirs() }
          val dest = File(picturesDir, fileName)
          sourceFile.inputStream().use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
          }
          Uri.fromFile(dest)
        }
      }
    }

  suspend fun saveMp4ToGallery(context: Context, sourceFile: File, titlePrefix: String): Result<Uri> =
    withContext(Dispatchers.IO) {
      runCatching {
        ensureEnoughStorageForVideo(context, sourceFile.length()).getOrThrow()
        if (!sourceFile.exists() || sourceFile.length() <= 0L) {
          throw IOException("Exported video file is empty or missing.")
        }
        val fileName = "${titlePrefix}_${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          val values =
            ContentValues().apply {
              put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
              put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
              put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VYNOZA")
              put(MediaStore.Video.Media.IS_PENDING, 1)
            }
          val resolver = context.contentResolver
          val itemUri =
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
              ?: throw IOException("Unable to create gallery video entry.")
          try {
            resolver.openOutputStream(itemUri)?.use { out ->
              sourceFile.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("Unable to write exported video to MediaStore.")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            itemUri
          } catch (e: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw e
          }
        } else {
          val moviesDir =
            File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "VYNOZA")
              .apply { mkdirs() }
          val dest = File(moviesDir, fileName)
          sourceFile.inputStream().use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
          }
          Uri.fromFile(dest)
        }
      }
    }

  fun safeDelete(file: File?) {
    if (file != null && file.exists()) {
      runCatching { file.delete() }
    }
  }

  private fun queryAvailableBytes(context: Context): Long? {
    return try {
      val stat = StatFs(context.cacheDir.absolutePath)
      if (stat.totalBytes <= 0L) null else stat.availableBytes
    } catch (_: Exception) {
      null
    }
  }

  private fun resolveLength(resolver: ContentResolver, uri: Uri): Long? {
    return try {
      resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
        afd.length.takeIf { it >= 0L }
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun resolveMimeType(resolver: ContentResolver, uri: Uri, displayName: String): String? {
    val provider = resolver.getType(uri)?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
    if (provider != null) return provider
    val ext =
      displayName.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() }
        ?: MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase().takeIf { it.isNotBlank() }
        ?: return null
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
  }

  private fun hasVideoExtension(displayName: String, uri: Uri): Boolean {
    val ext = extractExt(displayName, uri)
    return ext in setOf("mp4", "mov", "m4v", "webm", "3gp", "3gpp", "mkv")
  }

  private fun hasImageExtension(displayName: String, uri: Uri): Boolean {
    val ext = extractExt(displayName, uri)
    return ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp")
  }

  private fun extractExt(displayName: String, uri: Uri): String {
    return displayName.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() }
      ?: uri.toString().substringBefore('?').substringAfterLast('.', "").lowercase()
  }
}
