package com.vynoza.app.data.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.vynoza.app.core.studio.CreateStudioDraft
import com.vynoza.app.core.studio.EditorMediaClip
import com.vynoza.app.core.studio.EditorMediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class DraftMediaInspection(
  val missingClipIds: Set<String>,
  val isCoverMissing: Boolean,
) {
  val hasMissingMedia: Boolean
    get() = missingClipIds.isNotEmpty()
}

object StudioDraftMediaHelper {

  suspend fun validateDraftMedia(
    context: Context,
    draft: CreateStudioDraft,
  ): DraftMediaInspection =
    withContext(Dispatchers.IO) {
      val missingClips =
        draft.clips
          .filter { clip -> !isUriReadable(context, clip.uriString) }
          .map { it.id }
          .toSet()

      val coverMissing =
        !draft.coverFrameUriString.isNullOrBlank() &&
          !isUriReadable(context, draft.coverFrameUriString)

      DraftMediaInspection(
        missingClipIds = missingClips,
        isCoverMissing = coverMissing,
      )
    }

  /**
   * Copies volatile cache / picker clips into app-private draft storage so saved drafts survive
   * cache eviction and transient FileProvider grants, while never storing raw Audius audio files.
   */
  suspend fun materializeDraftMedia(
    context: Context,
    draft: CreateStudioDraft,
  ): CreateStudioDraft =
    withContext(Dispatchers.IO) {
      val draftFolder = StudioFilePaths.draftDir(context, draft.id)
      val materializedClips =
        draft.clips.mapIndexed { index, clip ->
          val uri = runCatching { Uri.parse(clip.uriString) }.getOrNull() ?: return@mapIndexed clip
          val currentFile = resolveOwnedFile(context, uri)
          if (currentFile != null && currentFile.parentFile?.absolutePath == draftFolder.absolutePath) {
            return@mapIndexed clip
          }

          val ext =
            if (clip.kind == EditorMediaKind.VIDEO) "mp4" else "jpg"
          val targetFile = File(draftFolder, "clip_${index}_${clip.id.take(8)}.$ext")
          if (currentFile != null && runCatching { currentFile.canonicalPath == targetFile.canonicalPath }.getOrDefault(false)) {
            return@mapIndexed clip
          }
          val copied = copyUriToFile(context, uri, targetFile)
          if (copied && targetFile.exists() && targetFile.length() > 0L) {
            val stableUri =
              runCatching { StudioFilePaths.fileProviderUriFor(context, targetFile) }
                .getOrElse { Uri.fromFile(targetFile) }
            clip.copy(uriString = stableUri.toString())
          } else {
            clip
          }
        }

      draft.copy(clips = materializedClips)
    }

  suspend fun extractCoverFrame(
    context: Context,
    clips: List<EditorMediaClip>,
    coverTimestampMs: Long,
    draftId: String,
  ): Result<Uri> =
    withContext(Dispatchers.IO) {
      runCatching {
        require(clips.isNotEmpty()) { "Add at least one clip before extracting a cover frame." }

        val targetFile = StudioFilePaths.createDraftCoverFile(context, draftId)
        val clampedGlobalMs = coverTimestampMs.coerceAtLeast(0L)

        var elapsedMs = 0L
        var chosenClip = clips.first()
        var localOffsetMs = 0L
        for (clip in clips) {
          val clipDur = clip.effectiveDurationMs
          if (clampedGlobalMs <= elapsedMs + clipDur || clip == clips.last()) {
            chosenClip = clip
            localOffsetMs = (clampedGlobalMs - elapsedMs).coerceIn(0L, clipDur)
            break
          }
          elapsedMs += clipDur
        }

        val uri = Uri.parse(chosenClip.uriString)
        val bitmap: Bitmap =
          if (chosenClip.kind == EditorMediaKind.VIDEO) {
            val sourcePositionUs = (chosenClip.trimStartMs + localOffsetMs) * 1_000L
            extractVideoBitmap(context, uri, sourcePositionUs)
              ?: throw IllegalStateException("Unable to extract video frame for cover.")
          } else {
            decodeScaledPhotoBitmap(context, uri, 720)
              ?: throw IllegalStateException("Unable to decode photo frame for cover.")
          }

        try {
          FileOutputStream(targetFile).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
              throw IllegalStateException("Failed to write cover image file.")
            }
          }
        } finally {
          if (!bitmap.isRecycled) {
            bitmap.recycle()
          }
        }

        runCatching { StudioFilePaths.fileProviderUriFor(context, targetFile) }
          .getOrElse { Uri.fromFile(targetFile) }
      }
    }

  fun isUriReadable(context: Context, uriString: String?): Boolean {
    if (uriString.isNullOrBlank()) return false
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
    if (uri.scheme == "file") {
      val path = uri.path ?: return false
      val file = File(path)
      return file.exists() && file.length() > 0L
    }
    return runCatching {
      context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
        afd.length != 0L
      } ?: false
    }.getOrDefault(false)
  }

  private fun resolveOwnedFile(context: Context, uri: Uri): File? {
    if (uri.scheme == "file") {
      return uri.path?.let(::File)?.takeIf { it.exists() }
    }
    if (uri.scheme == "content" && uri.authority == "${context.packageName}.fileprovider") {
      val segments = uri.pathSegments
      if (segments.size >= 2) {
        val root = segments[0]
        val rel = segments.drop(1).joinToString(File.separator)
        val candidate =
          when (root) {
            "vynoza_camera_studio" -> File(File(context.cacheDir, "studio"), rel)
            "vynoza_video_cache" -> File(File(context.cacheDir, "videos"), rel)
            "vynoza_template_exports" -> File(File(context.cacheDir, "templates"), rel)
            "vynoza_app_updates" -> File(File(context.cacheDir, "updates"), rel)
            "vynoza_studio_media" -> File(File(context.filesDir, "studio_media"), rel)
            "vynoza_drafts" -> File(File(context.filesDir, "drafts"), rel)
            else -> null
          }
        if (candidate?.exists() == true) return candidate
      }
    }
    return null
  }

  private fun copyUriToFile(context: Context, sourceUri: Uri, targetFile: File): Boolean {
    val tempFile = File(targetFile.parentFile ?: context.cacheDir, "${targetFile.name}.copy_tmp")
    return try {
      targetFile.parentFile?.mkdirs()
      val copiedBytes =
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
          FileOutputStream(tempFile).use { output ->
            input.copyTo(output)
          }
        } ?: return false
      if (copiedBytes <= 0L || !tempFile.exists() || tempFile.length() <= 0L) {
        tempFile.delete()
        return false
      }
      if (targetFile.exists()) {
        targetFile.delete()
      }
      tempFile.renameTo(targetFile)
    } catch (_: Exception) {
      runCatching { tempFile.delete() }
      false
    }
  }

  private fun extractVideoBitmap(context: Context, uri: Uri, timeUs: Long): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
      if (uri.scheme == "file" && uri.path != null) {
        retriever.setDataSource(uri.path)
      } else {
        retriever.setDataSource(context, uri)
      }
      retriever.getFrameAtTime(timeUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        ?: retriever.frameAtTime
    } catch (_: Exception) {
      null
    } finally {
      runCatching { retriever.release() }
    }
  }

  private fun decodeScaledPhotoBitmap(context: Context, uri: Uri, maxSidePx: Int): Bitmap? {
    return try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
      var sample = 1
      val longest = maxOf(bounds.outWidth, bounds.outHeight)
      while (longest / sample > maxSidePx * 2) {
        sample *= 2
      }
      val opts = BitmapFactory.Options().apply { inSampleSize = sample }
      context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Exception) {
      null
    }
  }
}
