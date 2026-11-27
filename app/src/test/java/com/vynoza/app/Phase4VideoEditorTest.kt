package com.vynoza.app

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.test.core.app.ApplicationProvider
import com.vynoza.app.core.studio.AudioMixSettings
import com.vynoza.app.core.studio.CreateStudioDraft
import com.vynoza.app.core.studio.DraftPrivacy
import com.vynoza.app.core.studio.EditorMediaClip
import com.vynoza.app.core.studio.EditorMediaKind
import com.vynoza.app.core.studio.MusicTrack
import com.vynoza.app.core.studio.MusicTrimSelection
import com.vynoza.app.data.music.AudioPreviewPlaybackState
import com.vynoza.app.data.music.AudiusLicenseEvaluator
import com.vynoza.app.data.music.MusicEmbedEligibility
import com.vynoza.app.data.music.StudioAudioPreviewController
import com.vynoza.app.data.service.ClipSource
import com.vynoza.app.data.service.ClipValidationResult
import com.vynoza.app.data.service.LocalVideoClip
import com.vynoza.app.data.service.MediaStorageService
import com.vynoza.app.data.service.VideoUploadResult
import com.vynoza.app.data.studio.ClipMediaInspector
import com.vynoza.app.data.studio.DataStoreCreateStudioStore
import com.vynoza.app.data.studio.StudioDraftMediaHelper
import com.vynoza.app.data.studio.StudioExportRequest
import com.vynoza.app.data.studio.StudioVideoExportDriver
import com.vynoza.app.data.studio.StudioVideoExporter
import com.vynoza.app.data.studio.TransientAudioFetcher
import com.vynoza.app.data.studio.VolumeAudioProcessor
import com.vynoza.app.ui.create.CreateViewModel
import com.vynoza.app.ui.create.PublishPostState
import com.vynoza.app.ui.studio.VideoEditorExportState
import com.vynoza.app.ui.studio.VideoEditorScreen
import com.vynoza.app.ui.studio.VideoEditorViewModel
import com.vynoza.app.ui.theme.VynozaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase4VideoEditorTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Before
  @After
  fun resetFileProviderCache() {
    runCatching {
      val field = FileProvider::class.java.getDeclaredField("sCache")
      field.isAccessible = true
      (field.get(null) as? MutableMap<*, *>)?.clear()
    }
  }

  private fun createIsolatedStore(
    context: Context,
    fileName: String = "phase4_test_${UUID.randomUUID()}.preferences_pb",
  ): Pair<DataStoreCreateStudioStore, CoroutineScope> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val file = File(context.filesDir, fileName)
    val dataStore: DataStore<Preferences> =
      PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { file },
      )
    return DataStoreCreateStudioStore(dataStore, context.filesDir) to scope
  }

  private suspend fun awaitMainCondition(
    timeoutMs: Long = 5_000L,
    predicate: () -> Boolean,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!predicate()) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      if (System.currentTimeMillis() >= deadline) break
      delay(20L)
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle()
  }

  private fun sampleTrack(
    id: String = "aud_1",
    license: String = "Creative Commons BY",
    isStreamable: Boolean = true,
    isDownloadable: Boolean = false,
  ) =
    MusicTrack(
      id = id,
      title = "Midnight Skyline",
      artistName = "Nova Pulse",
      artistHandle = "novapulse",
      artworkUrl = null,
      durationSeconds = 90,
      genre = "Electronic",
      license = license,
      isStreamable = isStreamable,
      isDownloadable = isDownloadable,
      streamUrl = "https://api.audius.co/v1/tracks/$id/stream?app_name=VYNOZA",
    )

  @Test
  fun `audius license evaluator permits CC0 and CC BY with attribution and blocks All Rights Reserved, NC, ND, and unclear`() {
    val cc0 = AudiusLicenseEvaluator.evaluate(sampleTrack(license = "CC0 1.0 Public Domain"))
    assertEquals(MusicEmbedEligibility.ELIGIBLE, cc0.eligibility)
    assertTrue(cc0.canEmbed)

    val ccBy = AudiusLicenseEvaluator.evaluate(sampleTrack(license = "Creative Commons BY 4.0"))
    assertEquals(MusicEmbedEligibility.ELIGIBLE, ccBy.eligibility)
    assertTrue(ccBy.canEmbed)
    assertTrue(ccBy.attributionText.contains("Nova Pulse"))
    assertTrue(ccBy.attributionText.contains("@novapulse"))

    val ccBySa = AudiusLicenseEvaluator.evaluate(sampleTrack(license = "CC BY-SA"))
    assertTrue(ccBySa.canEmbed)

    // Downloadable & streamable All Rights Reserved MUST still be blocked from MP4 embedding
    val arr =
      AudiusLicenseEvaluator.evaluate(
        sampleTrack(license = "All Rights Reserved", isStreamable = true, isDownloadable = true)
      )
    assertEquals(MusicEmbedEligibility.PREVIEW_ONLY_ALL_RIGHTS_RESERVED, arr.eligibility)
    assertFalse(arr.canEmbed)

    val nd = AudiusLicenseEvaluator.evaluate(sampleTrack(license = "CC BY-ND"))
    assertEquals(MusicEmbedEligibility.BLOCKED_NO_DERIVATIVES, nd.eligibility)
    assertFalse(nd.canEmbed)

    val nc = AudiusLicenseEvaluator.evaluate(sampleTrack(license = "Creative Commons BY-NC-SA"))
    assertEquals(MusicEmbedEligibility.BLOCKED_NON_COMMERCIAL, nc.eligibility)
    assertFalse(nc.canEmbed)

    val unclear = AudiusLicenseEvaluator.evaluate(sampleTrack(license = "Custom Proprietary License"))
    assertEquals(MusicEmbedEligibility.BLOCKED_UNCLEAR_LICENSE, unclear.eligibility)
    assertFalse(unclear.canEmbed)

    val unstreamable = AudiusLicenseEvaluator.evaluate(sampleTrack(license = "CC0", isStreamable = false))
    assertEquals(MusicEmbedEligibility.BLOCKED_STREAM_RESTRICTED, unstreamable.eligibility)
    assertFalse(unstreamable.canEmbed)
  }

  @OptIn(UnstableApi::class)
  @Test
  fun `volume audio processor scales 16-bit and float PCM samples accurately and clamps bounds`() {
    val halfGain = VolumeAudioProcessor(0.5f)
    halfGain.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
    halfGain.flush()
    assertTrue(halfGain.isActive)

    val input16 =
      ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).apply {
        putShort(10_000)
        putShort(-20_000)
        putShort(Short.MAX_VALUE)
        putShort(Short.MIN_VALUE)
        flip()
      }
    halfGain.queueInput(input16)
    val out16 = halfGain.output
    assertEquals(5_000.toShort(), out16.getShort())
    assertEquals((-10_000).toShort(), out16.getShort())
    assertEquals(16_384.toShort(), out16.getShort())
    assertEquals((-16_384).toShort(), out16.getShort())

    val muted = VolumeAudioProcessor(0f)
    muted.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT))
    muted.flush()
    val inputFloat =
      ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).apply {
        putFloat(0.75f)
        putFloat(-0.5f)
        flip()
      }
    muted.queueInput(inputFloat)
    val outFloat = muted.output
    assertEquals(0f, outFloat.getFloat(), 0.0001f)
    assertEquals(0f, outFloat.getFloat(), 0.0001f)
  }

  @OptIn(UnstableApi::class)
  @Test
  fun `exporter plans trims, sync offsets, looping, volume levels, and enforces license & temp audio cleanup`() =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val videoFile = File(context.cacheDir, "raw_clip_test.mp4").apply { writeBytes(ByteArray(2048) { 0x11 }) }
      val tempCapturedMusicFiles = mutableListOf<File>()

      try {
        val videoClip =
          EditorMediaClip(
            id = "clip_v1",
            uriString = Uri.fromFile(videoFile).toString(),
            kind = EditorMediaKind.VIDEO,
            durationMs = 20_000L,
            trimStartMs = 2_000L,
            trimEndMs = 17_000L,
            displayName = "raw_clip.mp4",
          )
        val openMusic =
          MusicTrimSelection(
            track = sampleTrack("aud_cc", license = "Creative Commons BY"),
            startMs = 5_000L,
            durationMs = 10_000L,
          )
        val mix = AudioMixSettings(originalAudioVolume = 0.45f, musicVolume = 0.8f, syncOffsetMs = 1_500L)

        var capturedComposition: Composition? = null
        val successDriver =
          object : StudioVideoExportDriver {
            override suspend fun exportComposition(
              context: Context,
              composition: Composition,
              outputFile: File,
              onProgress: (Float) -> Unit,
            ): Result<File> {
              capturedComposition = composition
              onProgress(0.5f)
              outputFile.writeBytes(ByteArray(4096) { 0x2A })
              onProgress(1f)
              return Result.success(outputFile)
            }
          }
        val fakeFetcher =
          object : TransientAudioFetcher {
            override suspend fun downloadTransientAudio(
              context: Context,
              track: MusicTrack,
              targetFile: File,
            ): Result<File> {
              tempCapturedMusicFiles.add(targetFile)
              targetFile.writeBytes(ByteArray(1024) { 0x55 })
              return Result.success(targetFile)
            }
          }
        val fakeInspector =
          object : ClipMediaInspector {
            override fun hasAudioTrack(context: Context, uri: Uri): Boolean = true
            override fun verifyOutputMp4Valid(context: Context, outputFile: File): Boolean =
              outputFile.exists() && outputFile.length() > 0L
          }

        val exporter = StudioVideoExporter(successDriver, fakeFetcher, fakeInspector)
        val request =
          StudioExportRequest(
            clips = listOf(videoClip),
            effectPresetId = "violet_aura",
            selectedMusic = openMusic,
            audioMix = mix,
          )

        val plan = exporter.buildExportPlan(request) { true }.getOrThrow()
        assertEquals(15_000L, plan.totalVideoDurationMs)
        assertEquals(2_000L, plan.clips.first().trimStartMs)
        assertEquals(17_000L, plan.clips.first().trimEndMs)
        assertTrue(plan.clips.first().includeOriginalAudio)
        assertEquals(0.45f, plan.clips.first().originalAudioVolume, 0.01f)
        assertTrue(plan.includeBackgroundMusic)
        assertEquals(5_000L, plan.musicStartMs)
        assertEquals(15_000L, plan.musicEndMs)
        assertEquals(1_500L, plan.musicSyncOffsetMs)
        assertTrue(plan.loopMusic) // 15s video minus 1.5s offset = 13.5s > 10s music trim window

        val progressLog = mutableListOf<Float>()
        val exportedFile =
          exporter.exportEditedVideo(context, request) { progressLog.add(it) }.getOrThrow()
        assertTrue(exportedFile.exists())
        assertTrue(progressLog.any { it >= 0.99f })
        assertNotNull(capturedComposition)
        assertEquals(2, capturedComposition!!.sequences.size)
        // Transient Audius audio file MUST be deleted after export
        assertTrue(tempCapturedMusicFiles.isNotEmpty())
        tempCapturedMusicFiles.forEach { assertFalse("Temp Audius file was not deleted", it.exists()) }

        // Restricted All Rights Reserved track must fail export unless muted/removed
        val restrictedRequest =
          request.copy(
            selectedMusic =
              MusicTrimSelection(
                track = sampleTrack("aud_arr", license = "All Rights Reserved", isStreamable = true, isDownloadable = true),
                startMs = 0L,
                durationMs = 10_000L,
              )
          )
        val blockedResult = exporter.exportEditedVideo(context, restrictedRequest)
        assertTrue(blockedResult.isFailure)
        assertTrue(blockedResult.exceptionOrNull()?.message?.contains("All Rights Reserved") == true)

        // Muting music (0%) allows exporting without downloading restricted audio
        val mutedResult =
          exporter.exportEditedVideo(
            context,
            restrictedRequest.copy(audioMix = AudioMixSettings(originalAudioVolume = 1f, musicVolume = 0f)),
          )
        assertTrue(mutedResult.isSuccess)
      } finally {
        videoFile.delete()
      }
    }

  @Test
  fun `exporter cleans up output and transient audio on driver failure and cancellation`() =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val videoFile = File(context.cacheDir, "cancel_clip.mp4").apply { writeBytes(ByteArray(1024) { 0x09 }) }
      var capturedOutput: File? = null
      var capturedTempAudio: File? = null

      try {
        val gate = CompletableDeferred<Unit>()
        val cancellingDriver =
          object : StudioVideoExportDriver {
            override suspend fun exportComposition(
              context: Context,
              composition: Composition,
              outputFile: File,
              onProgress: (Float) -> Unit,
            ): Result<File> {
              capturedOutput = outputFile
              outputFile.writeBytes(byteArrayOf(1, 2, 3))
              gate.await()
              return Result.success(outputFile)
            }
          }
        val failingDriver =
          object : StudioVideoExportDriver {
            override suspend fun exportComposition(
              context: Context,
              composition: Composition,
              outputFile: File,
              onProgress: (Float) -> Unit,
            ): Result<File> {
              capturedOutput = outputFile
              outputFile.writeBytes(byteArrayOf(1, 2, 3))
              return Result.failure(IOException("MediaCodec encoder rejected frame size"))
            }
          }
        val fetcher =
          object : TransientAudioFetcher {
            override suspend fun downloadTransientAudio(
              context: Context,
              track: MusicTrack,
              targetFile: File,
            ): Result<File> {
              capturedTempAudio = targetFile
              targetFile.writeBytes(byteArrayOf(7, 8, 9))
              return Result.success(targetFile)
            }
          }
        val inspector =
          object : ClipMediaInspector {
            override fun hasAudioTrack(context: Context, uri: Uri): Boolean = false
            override fun verifyOutputMp4Valid(context: Context, outputFile: File): Boolean = true
          }

        val request =
          StudioExportRequest(
            clips =
              listOf(
                EditorMediaClip(
                  uriString = Uri.fromFile(videoFile).toString(),
                  kind = EditorMediaKind.VIDEO,
                  durationMs = 5_000L,
                )
              ),
            selectedMusic = MusicTrimSelection(track = sampleTrack(license = "CC0")),
            audioMix = AudioMixSettings(originalAudioVolume = 0f, musicVolume = 1f),
          )

        // 1. Cancellation cleanup
        val cancelExporter = StudioVideoExporter(cancellingDriver, fetcher, inspector)
        val job = async { cancelExporter.exportEditedVideo(context, request) }
        delay(80L)
        job.cancel()
        try {
          job.await()
        } catch (_: CancellationException) {
        }
        val cancelOut = checkNotNull(capturedOutput)
        val cancelAudio = checkNotNull(capturedTempAudio)
        assertFalse(cancelOut.exists())
        assertFalse(cancelAudio.exists())

        // 2. Failure cleanup
        capturedOutput = null
        capturedTempAudio = null
        val failExporter = StudioVideoExporter(failingDriver, fetcher, inspector)
        val failRes = failExporter.exportEditedVideo(context, request)
        assertTrue(failRes.isFailure)
        val failOut = checkNotNull(capturedOutput)
        val failAudio = checkNotNull(capturedTempAudio)
        assertFalse(failOut.exists())
        assertFalse(failAudio.exists())
      } finally {
        videoFile.delete()
      }
    }

  @Test
  fun `draft persistence recovers across restarts and detects missing local media files safely`(): Unit =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val storeFileName = "restart_drafts_${UUID.randomUUID()}.preferences_pb"
      val (store1, scope1) = createIsolatedStore(context, storeFileName)
      val sourceClip = File(context.cacheDir, "ephemeral_cam.mp4").apply { writeBytes(ByteArray(1024) { 0x33 }) }
      var savedDraftId = ""

      try {
        val exporter =
          StudioVideoExporter(
            exportDriver =
              object : StudioVideoExportDriver {
                override suspend fun exportComposition(
                  context: Context,
                  composition: Composition,
                  outputFile: File,
                  onProgress: (Float) -> Unit,
                ) = Result.failure<File>(UnsupportedOperationException())
              }
          )
        val draft =
          CreateStudioDraft(
            id = "draft_persist_1",
            clips =
              listOf(
                EditorMediaClip(
                  id = "c1",
                  uriString = Uri.fromFile(sourceClip).toString(),
                  kind = EditorMediaKind.VIDEO,
                  durationMs = 8_000L,
                  trimStartMs = 1_000L,
                  trimEndMs = 6_000L,
                  displayName = "ephemeral_cam.mp4",
                )
              ),
            selectedMusic = MusicTrimSelection(track = sampleTrack(license = "CC BY"), startMs = 4_000L),
            audioMix = AudioMixSettings(originalAudioVolume = 0.6f, musicVolume = 0.9f, syncOffsetMs = 500L),
            caption = "Saved rooftop edit",
            hashtags = listOf("vynoza"),
            mentions = listOf("nova"),
            privacy = DraftPrivacy.PRIVATE,
          )
        savedDraftId = draft.id

        val editorVm =
          VideoEditorViewModel(
            localStore = store1,
            videoExporter = exporter,
            initialDraft = draft,
          )
        editorVm.saveDraft(context)
        awaitMainCondition {
          !editorVm.uiState.value.isSavingDraft && editorVm.uiState.value.draftStatusMessage != null
        }

        // Cancel first DataStore scope to simulate app process termination cleanly before reopen
        scope1.cancel()

        // Deleting volatile cache clip must NOT break the materialized draft clip
        sourceClip.delete()

        val (store2, scope2) = createIsolatedStore(context, storeFileName)
        try {
          val reloaded = store2.getDraft(draft.id)
          assertNotNull(reloaded)
          assertEquals(DraftPrivacy.PRIVATE, reloaded!!.privacy)
          assertEquals(1_000L, reloaded.clips.first().trimStartMs)
          assertEquals(6_000L, reloaded.clips.first().trimEndMs)
          assertEquals(500L, reloaded.audioMix.syncOffsetMs)

          val checkOk = StudioDraftMediaHelper.validateDraftMedia(context, reloaded)
          assertFalse(checkOk.hasMissingMedia)

          // Now simulate user/OS deleting the draft clip file -> missing clip must be safely detected
          val brokenDraft =
            reloaded.copy(
              clips = listOf(reloaded.clips.first().copy(uriString = "file:///nonexistent/deleted.mp4"))
            )
          val checkMissing = StudioDraftMediaHelper.validateDraftMedia(context, brokenDraft)
          assertTrue(checkMissing.hasMissingMedia)
          assertEquals(setOf("c1"), checkMissing.missingClipIds)

          store2.deleteDraft(savedDraftId)
        } finally {
          scope2.cancel()
        }
      } finally {
        scope1.cancel()
      }
    }

  @Test
  fun `editor enforces 300 char caption limit and publishes exported MP4 through CreateViewModel pipeline`(): Unit =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val (store, scope) = createIsolatedStore(context)
      val sourceVideo = File(context.cacheDir, "pub_src.mp4").apply { writeBytes(ByteArray(1536) { 0x77 }) }

      try {
        var uploadedClipName: String? = null
        var publishedCaption: String? = null
        var publishedVisibility: String? = null
        var publishedUrl: String? = null

        val fakeStorage =
          object : MediaStorageService {
            override fun inspectLocalClip(context: Context, uri: Uri, source: ClipSource) =
              LocalVideoClip(uri, uri.lastPathSegment ?: "exported.mp4", 4096L, "video/mp4", source)

            override fun validateClipForUpload(context: Context, clip: LocalVideoClip) =
              ClipValidationResult.Valid("video/mp4", 4096L)

            override suspend fun uploadVideo(
              context: Context,
              clip: LocalVideoClip,
              onProgress: (Float?) -> Unit,
            ): VideoUploadResult {
              uploadedClipName = clip.displayName
              onProgress(0.75f)
              return VideoUploadResult.Success(
                secureUrl = "https://res.cloudinary.com/idkq3e9l/video/upload/v99/mixed.mp4",
                publicId = "mixed_99",
              )
            }
          }

        val createVm =
          CreateViewModel(
            mediaStorageService = fakeStorage,
            currentUserId = "creator_uid",
            postPublisher = { caption, visibility, secureUrl, _ ->
              publishedCaption = caption
              publishedVisibility = visibility
              publishedUrl = secureUrl
              Result.success("vid_phase4_01")
            },
          )

        val exporter =
          StudioVideoExporter(
            exportDriver =
              object : StudioVideoExportDriver {
                override suspend fun exportComposition(
                  context: Context,
                  composition: Composition,
                  outputFile: File,
                  onProgress: (Float) -> Unit,
                ): Result<File> {
                  outputFile.writeBytes(ByteArray(4096) { 0x42 })
                  return Result.success(outputFile)
                }
              },
            mediaInspector =
              object : ClipMediaInspector {
                override fun hasAudioTrack(context: Context, uri: Uri): Boolean = true
                override fun verifyOutputMp4Valid(context: Context, outputFile: File): Boolean = true
              },
          )

        val editorVm =
          VideoEditorViewModel(
            localStore = store,
            videoExporter = exporter,
            createViewModel = createVm,
            initialDraft =
              CreateStudioDraft(
                clips =
                  listOf(
                    EditorMediaClip(
                      uriString = Uri.fromFile(sourceVideo).toString(),
                      kind = EditorMediaKind.VIDEO,
                      durationMs = 10_000L,
                    )
                  )
              ),
          )

        editorVm.updateCaption("A".repeat(315))
        assertEquals(300, editorVm.uiState.value.caption.length)

        editorVm.updateCaption("Night studio session")
        editorVm.addHashtag("vynoza")
        editorVm.addMention("audius")
        editorVm.selectPrivacy(DraftPrivacy.PRIVATE)
        assertTrue(editorVm.uiState.value.composedCaption.contains("@audius"))
        assertTrue(editorVm.uiState.value.composedCaption.contains("#vynoza"))

        // Export and verify edits invalidate stale export
        editorVm.exportEditedVideo(context, thenPublish = false)
        awaitMainCondition {
          editorVm.uiState.value.exportState is VideoEditorExportState.Exported ||
            editorVm.uiState.value.exportState is VideoEditorExportState.Error
        }
        assertTrue(
          "Expected Exported, got ${editorVm.uiState.value.exportState}",
          editorVm.uiState.value.exportState is VideoEditorExportState.Exported,
        )

        editorVm.updateOriginalAudioVolume(0.5f)
        assertTrue(editorVm.uiState.value.exportState is VideoEditorExportState.Idle)

        // Publish automatically runs export + Cloudinary upload + Firestore post save
        editorVm.publishToVynoza(context)
        awaitMainCondition {
          createVm.uiState.value.publishState is PublishPostState.Success ||
            createVm.uiState.value.publishState is PublishPostState.Error
        }

        val pubState = createVm.uiState.value.publishState
        assertTrue("Expected PublishPostState.Success, got $pubState", pubState is PublishPostState.Success)
        assertNotNull(uploadedClipName)
        assertEquals("Night studio session @audius #vynoza", publishedCaption)
        assertEquals("private", publishedVisibility)
        assertEquals("https://res.cloudinary.com/idkq3e9l/video/upload/v99/mixed.mp4", publishedUrl)
      } finally {
        scope.cancel()
        sourceVideo.delete()
      }
    }

  @Test
  fun `video editor screen displays license guardrails, allows muting restricted sound, and supports tab flows`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val (store, scope) = createIsolatedStore(context)
    val tempPhoto = File(context.cacheDir, "editor_ui_photo.jpg").apply { writeBytes(ByteArray(512) { 0x1A }) }

    try {
      val exporter =
        StudioVideoExporter(
          exportDriver =
            object : StudioVideoExportDriver {
              override suspend fun exportComposition(
                context: Context,
                composition: Composition,
                outputFile: File,
                onProgress: (Float) -> Unit,
              ): Result<File> {
                outputFile.writeBytes(ByteArray(1024) { 0x33 })
                return Result.success(outputFile)
              }
            },
          mediaInspector =
            object : ClipMediaInspector {
              override fun hasAudioTrack(context: Context, uri: Uri): Boolean = false
              override fun verifyOutputMp4Valid(context: Context, outputFile: File): Boolean = true
            },
        )

      val vm =
        VideoEditorViewModel(
          localStore = store,
          videoExporter = exporter,
          initialDraft =
            CreateStudioDraft(
              clips =
                listOf(
                  EditorMediaClip(
                    uriString = Uri.fromFile(tempPhoto).toString(),
                    kind = EditorMediaKind.PHOTO,
                    durationMs = 4_000L,
                    displayName = "cover_shot.jpg",
                  )
                ),
              selectedMusic =
                MusicTrimSelection(
                  track = sampleTrack("arr_ui", license = "All Rights Reserved", isStreamable = true),
                  startMs = 0L,
                  durationMs = 15_000L,
                ),
            ),
        )

      composeTestRule.setContent {
        VynozaTheme {
          VideoEditorScreen(
            viewModel = vm,
            publishState = PublishPostState.Idle,
            onBack = {},
            onNavigateToHome = {},
          )
        }
      }
      composeTestRule.waitForIdle()

      // Restricted music blocks export until muted or removed
      composeTestRule.onNodeWithTag("editor_license_banner").performScrollTo().assertIsDisplayed()
      composeTestRule.onNodeWithText("Preview Only", substring = true).assertIsDisplayed()

      composeTestRule.onNodeWithTag("editor_mute_blocked_music_button").performScrollTo().performClick()
      composeTestRule.waitForIdle()
      assertFalse(vm.uiState.value.hasMusicLicenseConflict)

      // Switch to Cover & Publish tab and edit caption & privacy
      composeTestRule.onNodeWithTag("editor_tab_cover_and_publish").performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("editor_caption_input").performScrollTo().performTextInput("Studio look")
      composeTestRule.onNodeWithTag("editor_privacy_private").performScrollTo().performClick()
      composeTestRule.waitForIdle()

      assertEquals("Studio look", vm.uiState.value.caption)
      assertEquals(DraftPrivacy.PRIVATE, vm.uiState.value.privacy)
    } finally {
      scope.cancel()
      tempPhoto.delete()
    }
  }

  @OptIn(UnstableApi::class)
  @Test
  fun `composition handles sync offset looping without repeating gap, silent clips, and no-music exports`(): Unit =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val clipFile = File(context.cacheDir, "audit_comp_clip.mp4").apply { writeBytes(ByteArray(1024) { 0x15 }) }
      val tempMusicFile = File(context.cacheDir, "audit_comp_music.mp3").apply { writeBytes(ByteArray(1024) { 0x25 }) }

      try {
        val exporter = StudioVideoExporter()
        val videoClip =
          EditorMediaClip(
            id = "v_loop",
            uriString = Uri.fromFile(clipFile).toString(),
            kind = EditorMediaKind.VIDEO,
            durationMs = 15_000L,
            trimStartMs = 0L,
            trimEndMs = 15_000L,
          )
        val musicSelection =
          MusicTrimSelection(
            track = sampleTrack("aud_loop", license = "CC BY 4.0"),
            startMs = 2_000L,
            durationMs = 5_000L,
          )

        // 1. Offset > 0 + looping needed: gap added once (not looped), music unrolled cleanly (2_000 offset -> 13_000 remaining = 5_000 + 5_000 + 3_000)
        val offsetLoopRequest =
          StudioExportRequest(
            clips = listOf(videoClip),
            selectedMusic = musicSelection,
            audioMix = AudioMixSettings(originalAudioVolume = 0f, musicVolume = 0.75f, syncOffsetMs = 2_000L),
          )
        val offsetPlan = exporter.buildExportPlan(offsetLoopRequest) { false }.getOrThrow()
        assertTrue(offsetPlan.loopMusic)
        assertFalse(offsetPlan.clips.first().includeOriginalAudio)

        val offsetComposition =
          exporter.buildMedia3Composition(offsetLoopRequest, offsetPlan, Uri.fromFile(tempMusicFile))
        assertEquals(2, offsetComposition.sequences.size)
        val musicSeq = offsetComposition.sequences[1]
        assertFalse("Gap must not be looped when syncOffsetMs > 0", musicSeq.isLooping)
        // Media3 EditedMediaItemSequence represents addGap() as index 0, followed by 2 full loops + 1 tail item
        assertEquals(4, musicSeq.editedMediaItems.size)
        assertEquals(2_000_000L, musicSeq.editedMediaItems[0].durationUs)
        assertEquals(2_000L, musicSeq.editedMediaItems[1].mediaItem.clippingConfiguration.startPositionMs)
        assertEquals(7_000L, musicSeq.editedMediaItems[1].mediaItem.clippingConfiguration.endPositionMs)
        assertEquals(2_000L, musicSeq.editedMediaItems[3].mediaItem.clippingConfiguration.startPositionMs)
        assertEquals(5_000L, musicSeq.editedMediaItems[3].mediaItem.clippingConfiguration.endPositionMs)

        // 2. Offset == 0 + looping needed: Media3 native looping is enabled for the single music item
        val zeroOffsetRequest =
          offsetLoopRequest.copy(
            audioMix = AudioMixSettings(originalAudioVolume = 0.8f, musicVolume = 0.75f, syncOffsetMs = 0L),
          )
        val zeroOffsetPlan = exporter.buildExportPlan(zeroOffsetRequest) { true }.getOrThrow()
        assertTrue(zeroOffsetPlan.clips.first().includeOriginalAudio)
        val zeroOffsetComp =
          exporter.buildMedia3Composition(zeroOffsetRequest, zeroOffsetPlan, Uri.fromFile(tempMusicFile))
        assertEquals(2, zeroOffsetComp.sequences.size)
        assertTrue(zeroOffsetComp.sequences[1].isLooping)

        // 3. No background music: only primary video/audio sequence is present
        val noMusicRequest =
          StudioExportRequest(
            clips = listOf(videoClip),
            selectedMusic = null,
            audioMix = AudioMixSettings(originalAudioVolume = 1f, musicVolume = 0f),
          )
        val noMusicPlan = exporter.buildExportPlan(noMusicRequest) { true }.getOrThrow()
        assertFalse(noMusicPlan.includeBackgroundMusic)
        val noMusicComp = exporter.buildMedia3Composition(noMusicRequest, noMusicPlan, null)
        assertEquals(1, noMusicComp.sequences.size)
      } finally {
        clipFile.delete()
        tempMusicFile.delete()
      }
    }

  @Test
  fun `license evaluator closes delimiter loopholes for NC, ND, and unverified suffixes even when downloadable`() {
    val parenNc =
      AudiusLicenseEvaluator.evaluate(
        sampleTrack(license = "Creative Commons BY (NC)", isStreamable = true, isDownloadable = true)
      )
    assertEquals(MusicEmbedEligibility.BLOCKED_NON_COMMERCIAL, parenNc.eligibility)
    assertFalse(parenNc.canEmbed)

    val slashNd =
      AudiusLicenseEvaluator.evaluate(
        sampleTrack(license = "Creative Commons BY/ND", isStreamable = true, isDownloadable = true)
      )
    assertEquals(MusicEmbedEligibility.BLOCKED_NO_DERIVATIVES, slashNd.eligibility)
    assertFalse(slashNd.canEmbed)

    val extraSuffix =
      AudiusLicenseEvaluator.evaluate(
        sampleTrack(license = "CC BY 4.0 Custom Non-Transferable", isStreamable = true, isDownloadable = true)
      )
    assertEquals(MusicEmbedEligibility.BLOCKED_UNCLEAR_LICENSE, extraSuffix.eligibility)
    assertFalse(extraSuffix.canEmbed)

    val emptyLicense =
      AudiusLicenseEvaluator.evaluate(
        sampleTrack(license = "", isStreamable = true, isDownloadable = true)
      )
    assertEquals(MusicEmbedEligibility.BLOCKED_UNCLEAR_LICENSE, emptyLicense.eligibility)
    assertFalse(emptyLicense.canEmbed)

    val validCcBySa =
      AudiusLicenseEvaluator.evaluate(
        sampleTrack(license = "Creative Commons Attribution-ShareAlike 4.0", isStreamable = true)
      )
    assertEquals(MusicEmbedEligibility.ELIGIBLE, validCcBySa.eligibility)
    assertTrue(validCcBySa.canEmbed)
  }

  @Test
  fun `resaving an existing draft preserves materialized file bytes without self-truncation and retains cover URI`(): Unit =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val (store, scope) = createIsolatedStore(context)
      val sourceClip = File(context.cacheDir, "resave_src.mp4").apply { writeBytes(ByteArray(2048) { 0x61 }) }
      val coverFile = File(context.filesDir, "drafts/draft_resave_1/cover.jpg").apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(512) { 0x4C })
      }
      val exporter =
        StudioVideoExporter(
          exportDriver =
            object : StudioVideoExportDriver {
              override suspend fun exportComposition(
                context: Context,
                composition: Composition,
                outputFile: File,
                onProgress: (Float) -> Unit,
              ) = Result.failure<File>(UnsupportedOperationException())
            }
        )

      try {
        val initialDraft =
          CreateStudioDraft(
            id = "draft_resave_1",
            clips =
              listOf(
                EditorMediaClip(
                  id = "clip_r1",
                  uriString = Uri.fromFile(sourceClip).toString(),
                  kind = EditorMediaKind.VIDEO,
                  durationMs = 5_000L,
                  displayName = "resave_src.mp4",
                )
              ),
            coverFrameUriString = Uri.fromFile(coverFile).toString(),
          )

        val editorVm =
          VideoEditorViewModel(
            localStore = store,
            videoExporter = exporter,
            initialDraft = initialDraft,
          )

        // First save copies into files/drafts/draft_resave_1/ and emits a FileProvider URI
        editorVm.saveDraft(context)
        awaitMainCondition { !editorVm.uiState.value.isSavingDraft && editorVm.uiState.value.draftStatusMessage != null }
        val firstUri = editorVm.uiState.value.clips.first().uriString
        assertTrue(StudioDraftMediaHelper.isUriReadable(context, firstUri))

        // Delete volatile source cache file, update caption, and save the SAME draft a second time
        sourceClip.delete()
        editorVm.dismissMessages()
        editorVm.updateCaption("Second save pass")
        editorVm.saveDraft(context)
        awaitMainCondition { !editorVm.uiState.value.isSavingDraft && editorVm.uiState.value.draftStatusMessage != null }

        val savedTwice = store.getDraft("draft_resave_1")
        assertNotNull(savedTwice)
        assertEquals("Second save pass", savedTwice!!.caption)
        assertEquals(Uri.fromFile(coverFile).toString(), savedTwice.coverFrameUriString)
        assertTrue(
          "Materialized clip must remain intact and non-zero after re-saving",
          StudioDraftMediaHelper.isUriReadable(context, savedTwice.clips.first().uriString),
        )
        store.deleteDraft("draft_resave_1")
      } finally {
        scope.cancel()
        sourceClip.delete()
        coverFile.delete()
      }
    }

  @Test
  fun `music preview volume updates controller and CreateViewModel clearSelection resets stale publish Success`(): Unit =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val (store, scope) = createIsolatedStore(context)
      val sourceClip = File(context.cacheDir, "session_reset.mp4").apply { writeBytes(ByteArray(1024) { 0x19 }) }

      try {
        val volumeHistory = mutableListOf<Float>()
        val fakePreviewController =
          object : StudioAudioPreviewController {
            private val _state = MutableStateFlow(AudioPreviewPlaybackState())
            override val state: StateFlow<AudioPreviewPlaybackState> = _state.asStateFlow()
            override fun playPreview(track: MusicTrack, startMs: Long, endMs: Long) {}
            override fun updateTrimWindow(startMs: Long, endMs: Long) {}
            override fun seekTo(positionMs: Long) {}
            override fun setVolume(volume: Float) {
              volumeHistory.add(volume)
            }
            override fun pause() {}
            override fun resume() {}
            override fun stop() {}
            override fun release() {}
          }

        val fakeStorage =
          object : MediaStorageService {
            override fun inspectLocalClip(context: Context, uri: Uri, source: ClipSource) =
              LocalVideoClip(uri, "out.mp4", 2048L, "video/mp4", source)

            override fun validateClipForUpload(context: Context, clip: LocalVideoClip) =
              ClipValidationResult.Valid("video/mp4", 2048L)

            override suspend fun uploadVideo(
              context: Context,
              clip: LocalVideoClip,
              onProgress: (Float?) -> Unit,
            ) = VideoUploadResult.Success("https://res.cloudinary.com/demo/video/upload/v1/ok.mp4", "ok")
          }

        val createVm =
          CreateViewModel(
            mediaStorageService = fakeStorage,
            currentUserId = "creator_2",
            postPublisher = { _, _, _, _ -> Result.success("vid_first") },
          )

        val exporter =
          StudioVideoExporter(
            exportDriver =
              object : StudioVideoExportDriver {
                override suspend fun exportComposition(
                  context: Context,
                  composition: Composition,
                  outputFile: File,
                  onProgress: (Float) -> Unit,
                ): Result<File> {
                  outputFile.writeBytes(ByteArray(1024) { 0x3A })
                  return Result.success(outputFile)
                }
              },
            mediaInspector =
              object : ClipMediaInspector {
                override fun hasAudioTrack(context: Context, uri: Uri): Boolean = false
                override fun verifyOutputMp4Valid(context: Context, outputFile: File): Boolean = true
              },
          )

        val editorVm =
          VideoEditorViewModel(
            localStore = store,
            videoExporter = exporter,
            audioPreviewController = fakePreviewController,
            createViewModel = createVm,
            initialDraft =
              CreateStudioDraft(
                clips =
                  listOf(
                    EditorMediaClip(
                      uriString = Uri.fromFile(sourceClip).toString(),
                      kind = EditorMediaKind.VIDEO,
                      durationMs = 5_000L,
                    )
                  )
              ),
          )

        editorVm.updateMusicVolume(0.35f)
        assertEquals(0.35f, volumeHistory.last(), 0.001f)
        editorVm.openMusicStudio()
        assertEquals(1.0f, volumeHistory.last(), 0.001f)
        editorVm.closeMusicStudio()
        assertEquals(0.35f, volumeHistory.last(), 0.001f)

        editorVm.publishToVynoza(context)
        awaitMainCondition { createVm.uiState.value.publishState is PublishPostState.Success }
        assertTrue(editorVm.uiState.value.hasStartedPublishInSession)

        // Navigating away / starting a new edit clears stale Success back to Idle
        createVm.clearSelection()
        assertTrue(createVm.uiState.value.publishState is PublishPostState.Idle)
      } finally {
        scope.cancel()
        sourceClip.delete()
      }
    }
}
