package com.vynoza.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.vynoza.app.core.studio.CameraCaptureOutput
import com.vynoza.app.core.studio.CameraFlashMode
import com.vynoza.app.core.studio.CameraLayoutOption
import com.vynoza.app.core.studio.CameraLensFacing
import com.vynoza.app.core.studio.CameraTimerOption
import com.vynoza.app.core.studio.CaptureMode
import com.vynoza.app.core.studio.EditorMediaKind
import com.vynoza.app.core.studio.MusicTrack
import com.vynoza.app.core.studio.MusicTrimSelection
import com.vynoza.app.core.studio.RecordingDurationOption
import com.vynoza.app.core.studio.StudioEffectCatalog
import com.vynoza.app.core.studio.TextStudioAlignment
import com.vynoza.app.core.studio.TextStudioBackground
import com.vynoza.app.data.music.AudioPreviewPlaybackState
import com.vynoza.app.data.music.StudioAudioPreviewController
import com.vynoza.app.data.studio.CameraBindResult
import com.vynoza.app.data.studio.CameraRecordCallbackEvent
import com.vynoza.app.data.studio.StudioCameraController
import com.vynoza.app.data.studio.StudioFilePaths
import com.vynoza.app.data.studio.StudioMediaProcessor
import com.vynoza.app.ui.studio.CameraStudioScreen
import com.vynoza.app.ui.studio.CameraStudioViewModel
import com.vynoza.app.ui.theme.VynozaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CameraStudioTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Before
  @After
  fun cleanupRobolectricState() {
    ShadowStatFs.reset()
    runCatching {
      val field = FileProvider::class.java.getDeclaredField("sCache")
      field.isAccessible = true
      (field.get(null) as? MutableMap<*, *>)?.clear()
    }
  }

  private class FakeStudioCameraController : StudioCameraController {
    var bindResult: Result<CameraBindResult> =
      Result.success(
        CameraBindResult(
          hasFlashUnit = true,
          hasFrontCamera = true,
          hasBackCamera = true,
        )
      )
    var bindCount = 0
    var lastCaptureMode: CaptureMode? = null
    var lastLensFacing: CameraLensFacing? = null
    var lastLayoutOption: CameraLayoutOption? = null
    val appliedFlashModes = mutableListOf<CameraFlashMode>()

    var lastRecordingFile: File? = null
    var lastWithAudio: Boolean? = null
    var recordingEventSink: ((CameraRecordCallbackEvent) -> Unit)? = null
    var stopRecordingCalls = 0
    var emitFinalizeErrorOnStop: String? = null
    var releaseCalled = false

    var nextPhotoResultSuccess = true

    override fun bindCamera(
      context: Context,
      lifecycleOwner: LifecycleOwner,
      previewView: PreviewView,
      captureMode: CaptureMode,
      lensFacing: CameraLensFacing,
      layoutOption: CameraLayoutOption,
      flashMode: CameraFlashMode,
      onBound: (Result<CameraBindResult>) -> Unit,
    ) {
      bindCount++
      lastCaptureMode = captureMode
      lastLensFacing = lensFacing
      lastLayoutOption = layoutOption
      onBound(bindResult)
    }

    override fun applyFlashMode(flashMode: CameraFlashMode, captureMode: CaptureMode) {
      appliedFlashModes.add(flashMode)
    }

    override fun startRecording(
      context: Context,
      outputFile: File,
      withAudio: Boolean,
      onEvent: (CameraRecordCallbackEvent) -> Unit,
    ): Boolean {
      lastRecordingFile = outputFile
      lastWithAudio = withAudio
      recordingEventSink = onEvent
      outputFile.writeBytes(byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112))
      onEvent(CameraRecordCallbackEvent.Started)
      return true
    }

    override fun stopRecording() {
      stopRecordingCalls++
      val sink = recordingEventSink ?: return
      recordingEventSink = null
      val file = lastRecordingFile
      val errorMsg = emitFinalizeErrorOnStop
      if (errorMsg != null) {
        file?.delete()
        sink(
          CameraRecordCallbackEvent.Finalized(
            outputFile = null,
            uri = null,
            durationMs = 0L,
            errorMessage = errorMsg,
          )
        )
      } else if (file != null) {
        sink(
          CameraRecordCallbackEvent.Finalized(
            outputFile = file,
            uri = Uri.fromFile(file),
            durationMs = 4_200L,
            errorMessage = null,
          )
        )
      }
    }

    override fun capturePhoto(
      context: Context,
      outputFile: File,
      flashMode: CameraFlashMode,
      onComplete: (Result<File>) -> Unit,
    ) {
      if (!nextPhotoResultSuccess) {
        onComplete(Result.failure(IllegalStateException("Camera shutter busy")))
        return
      }
      val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
      bmp.eraseColor(Color.rgb(220, 60, 40))
      FileOutputStream(outputFile).use { out ->
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
      }
      bmp.recycle()
      onComplete(Result.success(outputFile))
    }

    override fun release() {
      releaseCalled = true
    }
  }

  private class FakeSoundController : StudioAudioPreviewController {
    private val _state = MutableStateFlow(AudioPreviewPlaybackState())
    override val state: StateFlow<AudioPreviewPlaybackState> = _state.asStateFlow()

    var playCount = 0
    var pauseCount = 0

    override fun playPreview(track: MusicTrack, startMs: Long, endMs: Long) {
      playCount++
      _state.update {
        it.copy(
          activeTrackId = track.id,
          isPlaying = true,
          trimStartMs = startMs,
          trimEndMs = endMs,
        )
      }
    }

    override fun updateTrimWindow(startMs: Long, endMs: Long) = Unit
    override fun seekTo(positionMs: Long) = Unit

    override fun pause() {
      pauseCount++
      _state.update { it.copy(isPlaying = false) }
    }

    override fun resume() = Unit
    override fun stop() = Unit
    override fun release() = Unit
  }

  private fun sampleMusicSelection() =
    MusicTrimSelection(
      track =
        MusicTrack(
          id = "aud_cam_1",
          title = "Midnight Velocity",
          artistName = "DJ Pulse",
          artistHandle = "djpulse",
          artworkUrl = null,
          durationSeconds = 90,
          license = "CC BY",
          isStreamable = true,
          streamUrl = "https://example.org/stream.mp3",
        ),
      startMs = 5_000L,
      durationMs = 15_000L,
    )

  private fun grantCameraAndMicPermissions(camera: Boolean, mic: Boolean) {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val shadowApp = Shadows.shadowOf(app)
    if (camera) shadowApp.grantPermissions(Manifest.permission.CAMERA)
    else shadowApp.denyPermissions(Manifest.permission.CAMERA)
    if (mic) shadowApp.grantPermissions(Manifest.permission.RECORD_AUDIO)
    else shadowApp.denyPermissions(Manifest.permission.RECORD_AUDIO)
  }

  private fun pumpMainLooperUntil(timeoutMs: Long = 4_000L, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!predicate() && System.currentTimeMillis() < deadline) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeTestRule.waitForIdle()
      Thread.sleep(20L)
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    composeTestRule.waitForIdle()
  }

  @Test
  fun `permission denial shows rationale while keeping text mode and gallery accessible`() {
    grantCameraAndMicPermissions(camera = false, mic = false)
    val controller = FakeStudioCameraController()
    val viewModel = CameraStudioViewModel(controller)

    composeTestRule.setContent {
      VynozaTheme {
        CameraStudioScreen(
          viewModel = viewModel,
          onClose = {},
          onCaptureCompleted = {},
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("camera_permission_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("camera_permission_gallery_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("camera_permission_text_button").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("camera_text_mode_canvas").assertIsDisplayed()
  }

  @Test
  fun `camera flip, flash, layout, effects gallery, and duration controls update bound camera state`() {
    grantCameraAndMicPermissions(camera = true, mic = true)
    val controller = FakeStudioCameraController()
    val viewModel = CameraStudioViewModel(controller)

    composeTestRule.setContent {
      VynozaTheme {
        CameraStudioScreen(
          viewModel = viewModel,
          onClose = {},
          onCaptureCompleted = {},
        )
      }
    }
    composeTestRule.waitForIdle()

    assertTrue(viewModel.uiState.value.isCameraBound)
    assertEquals(CameraLensFacing.BACK, viewModel.uiState.value.lensFacing)

    // Switch duration options: 15s -> 60s -> 10m
    composeTestRule.onNodeWithTag("camera_duration_60s").performClick()
    assertEquals(RecordingDurationOption.SIXTY_SECONDS, viewModel.uiState.value.durationOption)
    composeTestRule.onNodeWithTag("camera_duration_10m").performClick()
    assertEquals(RecordingDurationOption.TEN_MINUTES, viewModel.uiState.value.durationOption)

    // Toggle flash ON
    composeTestRule.onNodeWithTag("camera_flash_button").performClick()
    assertEquals(CameraFlashMode.ON, viewModel.uiState.value.flashMode)
    assertTrue(controller.appliedFlashModes.contains(CameraFlashMode.ON))

    // Flip camera resets torch flash safely and rebinds FRONT
    composeTestRule.onNodeWithTag("camera_flip_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(CameraLensFacing.FRONT, viewModel.uiState.value.lensFacing)
    assertEquals(CameraLensFacing.FRONT, controller.lastLensFacing)
    assertEquals(CameraFlashMode.OFF, viewModel.uiState.value.flashMode)

    // Cycle layouts
    composeTestRule.onNodeWithTag("camera_layout_button").performClick()
    assertEquals(CameraLayoutOption.GRID_9_16, viewModel.uiState.value.layoutOption)
    composeTestRule.onNodeWithTag("camera_layout_button").performClick()
    assertEquals(CameraLayoutOption.FEED_4_5, viewModel.uiState.value.layoutOption)

    // Open Effects Gallery & filter by Retro category, select 90s Camcorder
    composeTestRule.onNodeWithTag("camera_effects_rail_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("camera_effects_gallery_sheet").assertIsDisplayed()
    composeTestRule.onNodeWithTag("effects_category_retro").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("effects_gallery_item_vhs_memories").performClick()
    composeTestRule.waitForIdle()
    assertEquals("vhs_memories", viewModel.uiState.value.selectedEffect.id)
    assertFalse(viewModel.uiState.value.isEffectsGalleryOpen)
  }

  @Test
  fun `video recording syncs sound, tracks timer, auto-stops at 15s limit, and opens playback review`() {
    grantCameraAndMicPermissions(camera = true, mic = true)
    val controller = FakeStudioCameraController()
    val soundController = FakeSoundController()
    val viewModel = CameraStudioViewModel(controller, soundController)
    viewModel.onMusicSelected(sampleMusicSelection())

    var emittedOutput: CameraCaptureOutput? = null

    composeTestRule.setContent {
      VynozaTheme {
        CameraStudioScreen(
          viewModel = viewModel,
          onClose = {},
          onCaptureCompleted = { emittedOutput = it },
        )
      }
    }
    composeTestRule.waitForIdle()

    // Start video recording
    composeTestRule.onNodeWithTag("camera_shutter_button").performClick()
    pumpMainLooperUntil { viewModel.uiState.value.isRecording }

    assertEquals(true, controller.lastWithAudio)
    assertEquals(1, soundController.playCount)
    composeTestRule.onNodeWithTag("camera_recording_timer").assertIsDisplayed()

    // Simulate CameraX progress hitting 15s limit -> auto-stops & opens review
    controller.recordingEventSink?.invoke(CameraRecordCallbackEvent.Progress(15_000L))
    pumpMainLooperUntil { viewModel.uiState.value.reviewOutput != null }

    assertFalse(viewModel.uiState.value.isRecording)
    assertEquals(1, controller.stopRecordingCalls)
    assertTrue(soundController.pauseCount >= 1)
    composeTestRule.onNodeWithTag("camera_review_overlay").assertIsDisplayed()

    // Tap Use Clip to forward captured clip + sound + effect to editor pipeline
    composeTestRule.onNodeWithTag("camera_review_use_button").performClick()
    assertNotNull(emittedOutput)
    assertEquals(EditorMediaKind.VIDEO, emittedOutput?.clips?.firstOrNull()?.kind)
    assertEquals("aud_cam_1", emittedOutput?.selectedMusic?.track?.id)
  }

  @Test
  fun `countdown timer starts and cancels cleanly without triggering recording`() {
    grantCameraAndMicPermissions(camera = true, mic = true)
    val controller = FakeStudioCameraController()
    val viewModel = CameraStudioViewModel(controller)

    composeTestRule.setContent {
      VynozaTheme {
        CameraStudioScreen(
          viewModel = viewModel,
          onClose = {},
          onCaptureCompleted = {},
        )
      }
    }
    composeTestRule.waitForIdle()

    // Cycle timer to 3s and trigger shutter
    composeTestRule.onNodeWithTag("camera_timer_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals(CameraTimerOption.THREE_SECONDS, viewModel.uiState.value.timerOption)

    composeTestRule.onNodeWithTag("camera_shutter_button").performClick()
    pumpMainLooperUntil { viewModel.uiState.value.countdownRemainingSec != null }
    composeTestRule.onNodeWithTag("camera_countdown_overlay").assertIsDisplayed()

    // Cancel mid-countdown
    composeTestRule.onNodeWithTag("camera_countdown_cancel").performClick()
    pumpMainLooperUntil { viewModel.uiState.value.countdownRemainingSec == null }
    assertFalse(viewModel.uiState.value.isRecording)
    assertNull(controller.lastRecordingFile)
  }

  @Test
  fun `photo mode captures and grades real JPEG file with active ColorMatrix effect and supports retake`() {
    grantCameraAndMicPermissions(camera = true, mic = true)
    val controller = FakeStudioCameraController()
    val viewModel = CameraStudioViewModel(controller)

    composeTestRule.setContent {
      VynozaTheme {
        CameraStudioScreen(
          viewModel = viewModel,
          onClose = {},
          onCaptureCompleted = {},
        )
      }
    }
    composeTestRule.waitForIdle()

    // Switch to Photo mode and pick Cine Noir (monochrome)
    composeTestRule.onNodeWithTag("camera_mode_photo").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("camera_effect_chip_cinematic_noir").performClick()
    composeTestRule.waitForIdle()

    // Cycle photo flash: OFF -> ON -> AUTO
    composeTestRule.onNodeWithTag("camera_flash_button").performClick()
    assertEquals(CameraFlashMode.ON, viewModel.uiState.value.flashMode)
    composeTestRule.onNodeWithTag("camera_flash_button").performClick()
    assertEquals(CameraFlashMode.AUTO, viewModel.uiState.value.flashMode)

    // Take photo
    composeTestRule.onNodeWithTag("camera_shutter_button").performClick()
    pumpMainLooperUntil { viewModel.uiState.value.reviewOutput != null }

    val review = viewModel.uiState.value.reviewOutput!!
    assertEquals(EditorMediaKind.PHOTO, review.clips.first().kind)
    assertEquals("cinematic_noir", review.effectPresetId)

    // Verify Cine Noir ColorMatrix mathematically desaturates RGB channels & output JPEG is valid
    val cmArray = StudioMediaProcessor.colorMatrixForEffect("cinematic_noir")!!.array
    val outR = cmArray[0] * 220f + cmArray[1] * 60f + cmArray[2] * 40f + cmArray[4]
    val outG = cmArray[5] * 220f + cmArray[6] * 60f + cmArray[7] * 40f + cmArray[9]
    val outB = cmArray[10] * 220f + cmArray[11] * 60f + cmArray[12] * 40f + cmArray[14]
    assertEquals(outR, outG, 0.01f)
    assertEquals(outG, outB, 0.01f)

    val renderedFile =
      File(
        StudioFilePaths.studioCacheDir(ApplicationProvider.getApplicationContext()),
        review.clips.first().displayName,
      )
    assertTrue("Expected graded photo file at ${renderedFile.absolutePath}", renderedFile.exists())
    val decoded = BitmapFactory.decodeFile(renderedFile.absolutePath)
    assertNotNull(decoded)
    assertEquals(48, decoded!!.width)
    assertEquals(48, decoded.height)
    decoded.recycle()

    // Tap Retake returns to live camera preview
    composeTestRule.onNodeWithTag("camera_review_retake_button").performClick()
    composeTestRule.waitForIdle()
    assertNull(viewModel.uiState.value.reviewOutput)
  }

  @Test
  fun `text mode creates real 1080x1920 gradient JPEG with alignment and background presets`() {
    grantCameraAndMicPermissions(camera = true, mic = true)
    val controller = FakeStudioCameraController()
    val viewModel = CameraStudioViewModel(controller)

    composeTestRule.setContent {
      VynozaTheme {
        CameraStudioScreen(
          viewModel = viewModel,
          onClose = {},
          onCaptureCompleted = {},
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("camera_mode_text").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("camera_text_bg_cyber_cyan").performClick()
    composeTestRule.onNodeWithTag("camera_text_align_button").performClick()
    composeTestRule.onNodeWithTag("camera_text_input").performTextInput("VYNOZA Night Session")
    composeTestRule.waitForIdle()

    assertEquals(TextStudioBackground.CYBER_CYAN, viewModel.uiState.value.textBackground)
    assertEquals(TextStudioAlignment.LEFT, viewModel.uiState.value.textAlignment)

    composeTestRule.onNodeWithTag("camera_text_create_button").performClick()
    pumpMainLooperUntil { viewModel.uiState.value.reviewOutput != null }

    val clip = viewModel.uiState.value.reviewOutput!!.clips.first()
    val outFile =
      File(
        StudioFilePaths.studioCacheDir(ApplicationProvider.getApplicationContext()),
        clip.displayName,
      )
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(outFile.absolutePath, opts)
    assertEquals(1080, opts.outWidth)
    assertEquals(1920, opts.outHeight)
  }

  @Test
  fun `mic denied allows video-only recording, lifecycle ON_PAUSE stops active recording, and low storage blocks capture`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    grantCameraAndMicPermissions(camera = true, mic = false)
    val controller = FakeStudioCameraController()
    val soundController = FakeSoundController()
    val viewModel = CameraStudioViewModel(controller, soundController)
    viewModel.syncPermissions(cameraGranted = true, micGranted = false, requested = true)
    assertTrue(viewModel.uiState.value.recordingNotice?.contains("without microphone audio") == true)

    // Start recording without audio permission
    viewModel.onShutterPressed(context)
    assertTrue(viewModel.uiState.value.isRecording)
    assertEquals(false, controller.lastWithAudio)

    // Lifecycle ON_PAUSE stops recording gracefully and sets notice
    viewModel.onAppLifecyclePause()
    assertFalse(viewModel.uiState.value.isRecording)
    assertTrue(viewModel.uiState.value.recordingNotice?.contains("background") == true)

    // Simulate low storage (< 40 MB)
    ShadowStatFs.registerStats(context.filesDir.absolutePath, 100, 10, 10)
    viewModel.onShutterPressed(context)
    assertFalse(viewModel.uiState.value.isRecording)
    assertTrue(viewModel.uiState.value.errorMessage?.contains("Insufficient storage") == true)

    // Unsupported flash hardware guard
    val noFlashController = FakeStudioCameraController().apply {
      bindResult = Result.success(CameraBindResult(hasFlashUnit = false, hasFrontCamera = false, hasBackCamera = true))
    }
    val guardedVm = CameraStudioViewModel(noFlashController)
    guardedVm.syncPermissions(cameraGranted = true, micGranted = true, requested = true)
    guardedVm.cycleFlashMode()
    assertEquals(CameraFlashMode.OFF, guardedVm.uiState.value.flashMode)
  }

  @Test
  fun `gallery URI inspection inspects selected media files into review clips`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val samplePhoto = StudioFilePaths.createTempCameraFile(context, "gal_photo", "jpg")
    val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    FileOutputStream(samplePhoto).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    bmp.recycle()

    try {
      val photoUri = StudioFilePaths.fileProviderUriFor(context, samplePhoto)
      val clips = StudioMediaProcessor.inspectGalleryUris(context, listOf(photoUri))
      assertEquals(1, clips.size)
      assertEquals(EditorMediaKind.PHOTO, clips.first().kind)
      assertEquals(photoUri.toString(), clips.first().uriString)
    } finally {
      samplePhoto.delete()
    }
  }
}
