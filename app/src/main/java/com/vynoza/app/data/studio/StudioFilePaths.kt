package com.vynoza.app.data.studio

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object StudioFilePaths {
  const val CAMERA_AND_AUDIO_CACHE_DIR = "studio"
  const val STUDIO_MEDIA_FILES_DIR = "studio_media"
  const val DRAFTS_FILES_DIR = "drafts"

  fun studioCacheDir(context: Context): File =
    File(context.cacheDir, CAMERA_AND_AUDIO_CACHE_DIR).apply { mkdirs() }

  fun studioMediaDir(context: Context): File =
    File(context.filesDir, STUDIO_MEDIA_FILES_DIR).apply { mkdirs() }

  fun draftsRootDir(context: Context): File =
    File(context.filesDir, DRAFTS_FILES_DIR).apply { mkdirs() }

  fun draftDir(context: Context, draftId: String): File {
    val safeId = draftId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "draft" }
    return File(draftsRootDir(context), safeId).apply { mkdirs() }
  }

  fun createTempCameraFile(context: Context, prefix: String, extension: String): File {
    val cleanExt = extension.trimStart('.')
    return File(studioCacheDir(context), "${prefix}_${System.currentTimeMillis()}.$cleanExt")
  }

  fun createPersistentStudioFile(context: Context, prefix: String, extension: String): File {
    val cleanExt = extension.trimStart('.')
    return File(studioMediaDir(context), "${prefix}_${System.currentTimeMillis()}.$cleanExt")
  }

  fun createDraftCoverFile(context: Context, draftId: String): File =
    File(draftDir(context, draftId), "cover_${System.currentTimeMillis()}.jpg")

  fun fileProviderUriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

  fun deleteDraftDirectory(filesDir: File, draftId: String) {
    val safeId = draftId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { return }
    val dir = File(File(filesDir, DRAFTS_FILES_DIR), safeId)
    if (dir.exists()) {
      runCatching { dir.deleteRecursively() }
    }
  }
}
