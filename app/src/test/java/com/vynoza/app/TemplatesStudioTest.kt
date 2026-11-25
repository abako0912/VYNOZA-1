package com.vynoza.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.transformer.EditedMediaItem
import androidx.test.core.app.ApplicationProvider
import com.vynoza.app.core.templates.CloneArrangement
import com.vynoza.app.core.templates.ExposureBlendMode
import com.vynoza.app.core.templates.MemoriesFrameStyle
import com.vynoza.app.core.templates.MemoriesTransitionStyle
import com.vynoza.app.core.templates.PortraitFraming
import com.vynoza.app.core.templates.TemplateExportState
import com.vynoza.app.core.templates.TemplateMediaKind
import com.vynoza.app.core.templates.TemplateParameters
import com.vynoza.app.core.templates.VynozaTemplateCatalog
import com.vynoza.app.core.templates.VynozaTemplateId
import com.vynoza.app.data.service.ClipSource
import com.vynoza.app.data.service.ClipValidationResult
import com.vynoza.app.data.service.CloudinaryMediaStorageService
import com.vynoza.app.data.service.LocalVideoClip
import com.vynoza.app.data.service.MediaStorageService
import com.vynoza.app.data.service.VideoUploadResult
import com.vynoza.app.data.templates.PhotoTemplateProcessor
import com.vynoza.app.data.templates.TemplateMediaStorage
import com.vynoza.app.data.templates.TemplateVideoEffects
import com.vynoza.app.data.templates.TemplateVideoExportDriver
import com.vynoza.app.data.templates.VideoTemplateProcessor
import com.vynoza.app.ui.create.CreateScreen
import com.vynoza.app.ui.create.CreateViewModel
import com.vynoza.app.ui.templates.TemplatesStudioScreen
import com.vynoza.app.ui.templates.TemplatesStudioViewModel
import com.vynoza.app.ui.theme.VynozaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TemplatesStudioTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun createStudioTestBitmap(width: Int = 160, height: Int = 200): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    // Uniform dark charcoal studio backdrop
    canvas.drawColor(Color.rgb(18, 16, 26))
    // Centered bright silhouette & vertical bar for unambiguous pixel displacement detection
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 120, 240) }
    canvas.drawRect(width * 0.42f, height * 0.28f, width * 0.58f, height * 0.85f, bodyPaint)
    val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 210, 180) }
    canvas.drawCircle(width * 0.5f, height * 0.22f, width * 0.10f, headPaint)
    return bmp
  }

  private fun pixelDiffCount(a: Bitmap, b: Bitmap): Int {
    val w = minOf(a.width, b.width)
    val h = minOf(a.height, b.height)
    var diffs = 0
    for (y in 0 until h) {
      for (x in 0 until w) {
        if (a.getPixel(x, y) != b.getPixel(x, y)) diffs++
      }
    }
    return diffs
  }

  @Test
  fun `clone and echo actually duplicates subject into side echo zones and respects spacing`() {
    val src = createStudioTestBitmap()
    val tight =
      PhotoTemplateProcessor.applyTemplate(
        src,
        VynozaTemplateId.CLONE_ECHO,
        TemplateParameters(
          cloneCount = 3,
          cloneSpacing = 0.20f,
          cloneOpacity = 0.85f,
          cloneArrangement = CloneArrangement.HORIZONTAL_FAN,
        ),
      )
    val wide =
      PhotoTemplateProcessor.applyTemplate(
        src,
        VynozaTemplateId.CLONE_ECHO,
        TemplateParameters(
          cloneCount = 3,
          cloneSpacing = 0.90f,
          cloneOpacity = 0.85f,
          cloneArrangement = CloneArrangement.HORIZONTAL_FAN,
        ),
      )

    assertEquals(src.width, tight.width)
    assertEquals(src.height, tight.height)
    assertTrue("Expected pixel differences from source", pixelDiffCount(src, wide) > 500)
    assertTrue("Expected spacing change to alter pixels", pixelDiffCount(tight, wide) > 200)
    // Far left region (x = 18..36) starts as pure backdrop (18,16,26) in source; wide echo shifts subject there
    val shiftedPixel = wide.getPixel(22, 100)
    assertNotEquals(Color.rgb(18, 16, 26), shiftedPixel)
  }

  @Test
  fun `cinematic portrait grades pixels and enforces 4_5 framing crop`() {
    val src = createStudioTestBitmap(width = 200, height = 200)
    val graded =
      PhotoTemplateProcessor.applyTemplate(
        src,
        VynozaTemplateId.CINEMATIC_PORTRAIT,
        TemplateParameters(
          contrast = 1.45f,
          saturation = 1.4f,
          shadows = 0.35f,
          highlights = -0.35f,
          portraitFraming = PortraitFraming.PORTRAIT_4_5,
        ),
      )

    assertEquals(160, graded.width)
    assertEquals(200, graded.height)
    assertTrue("Center subject pixel color should be graded", graded.getPixel(80, 110) != src.getPixel(100, 110))
  }

  @Test
  fun `motion blur streaks along direction vector and responds to angle changes`() {
    val src = createStudioTestBitmap()
    val horizontal =
      PhotoTemplateProcessor.applyTemplate(
        src,
        VynozaTemplateId.MOTION_BLUR,
        TemplateParameters(blurIntensity = 0.9f, blurAngleDegrees = 0f, ghostTrail = 0.7f),
      )
    val vertical =
      PhotoTemplateProcessor.applyTemplate(
        src,
        VynozaTemplateId.MOTION_BLUR,
        TemplateParameters(blurIntensity = 0.9f, blurAngleDegrees = 90f, ghostTrail = 0.7f),
      )

    assertTrue("Motion blur must transform pixels", pixelDiffCount(src, horizontal) > 400)
    assertTrue("Different blur angles must produce distinct blurs", pixelDiffCount(horizontal, vertical) > 300)
  }

  @Test
  fun `multiple exposure blends offset layers cleanly across blend modes`() {
    val src = createStudioTestBitmap()
    val screenOut =
      PhotoTemplateProcessor.applyTemplate(
        src,
        VynozaTemplateId.MULTIPLE_EXPOSURE,
        TemplateParameters(
          exposureOpacity = 0.75f,
          exposureOffset = 0.55f,
          exposureBlendMode = ExposureBlendMode.SCREEN,
          exposureMirrorSecond = true,
        ),
      )
    val overlayOut =
      PhotoTemplateProcessor.applyTemplate(
        src,
        VynozaTemplateId.MULTIPLE_EXPOSURE,
        TemplateParameters(
          exposureOpacity = 0.75f,
          exposureOffset = 0.55f,
          exposureBlendMode = ExposureBlendMode.OVERLAY,
          exposureMirrorSecond = true,
        ),
      )

    assertTrue("Screen exposure must modify pixels", pixelDiffCount(src, screenOut) > 500)
    assertTrue("Different blend modes must produce different outputs", pixelDiffCount(screenOut, overlayOut) > 100)
  }

  @Test
  fun `memories renders polaroid film strip and editorial frame layouts`() {
    val src = createStudioTestBitmap()
    val polaroid =
      PhotoTemplateProcessor.applyTemplate(
        src,
        VynozaTemplateId.MEMORIES,
        TemplateParameters(
          memoriesFrameStyle = MemoriesFrameStyle.POLAROID,
          memoriesTitle = "Summer Glow",
          memoriesDateText = "NOV 22, 2026",
        ),
      )
    val film =
      PhotoTemplateProcessor.applyTemplate(
        src,
        VynozaTemplateId.MEMORIES,
        TemplateParameters(
          memoriesFrameStyle = MemoriesFrameStyle.FILM_STRIP,
          memoriesTitle = "Summer Glow",
          memoriesDateText = "NOV 22, 2026",
        ),
      )

    assertTrue(pixelDiffCount(src, polaroid) > 1000)
    assertTrue(pixelDiffCount(polaroid, film) > 1000)
  }

  @Test
  fun `renderAndSavePhoto writes valid readable JPEG and keeps source file untouched`(): Unit =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val sourceFile = File(context.cacheDir, "unit_src_photo.jpg")
      FileOutputStream(sourceFile).use { out ->
        createStudioTestBitmap(240, 320).compress(Bitmap.CompressFormat.JPEG, 95, out)
      }
      val originalBytes = sourceFile.readBytes()

      val progressLog = mutableListOf<Float>()
      val result =
        PhotoTemplateProcessor.renderAndSavePhoto(
          context = context,
          sourceUri = Uri.fromFile(sourceFile),
          templateId = VynozaTemplateId.CINEMATIC_PORTRAIT,
          params = TemplateParameters.defaultsFor(VynozaTemplateId.CINEMATIC_PORTRAIT),
          onProgress = { progressLog.add(it) },
        )

      assertTrue("Photo export failed: ${result.exceptionOrNull()}", result.isSuccess)
      val outFile = result.getOrThrow()
      assertTrue(outFile.exists() && outFile.length() > 0L)
      val decoded = BitmapFactory.decodeFile(outFile.absolutePath)
      assertNotNull("Exported JPEG must decode cleanly", decoded)
      assertTrue(decoded.width > 0 && decoded.height > 0)
      assertTrue(progressLog.any { it >= 0.99f })
      assertTrue("Original photo must remain unmodified", originalBytes.contentEquals(sourceFile.readBytes()))
      outFile.delete()
      sourceFile.delete()
    }

  @Test
  fun `video effects builder constructs real Media3 effects for all 5 templates in both video and photo-motion modes`() {
    VynozaTemplateCatalog.templates.forEach { spec ->
      val videoEffects =
        TemplateVideoEffects.buildVideoEffects(spec.id, spec.defaultParameters, isPhotoMotionExport = false)
      assertTrue("Video effects missing for ${spec.id}", videoEffects.isNotEmpty())

      val photoMotionEffects =
        TemplateVideoEffects.buildVideoEffects(spec.id, spec.defaultParameters, isPhotoMotionExport = true)
      assertTrue("Photo-motion effects missing for ${spec.id}", photoMotionEffects.isNotEmpty())
    }

    MemoriesTransitionStyle.entries.forEach { transition ->
      val effects =
        TemplateVideoEffects.buildVideoEffects(
          VynozaTemplateId.MEMORIES,
          TemplateParameters(memoriesTransition = transition, memoriesWarmth = 0.7f),
          isPhotoMotionExport = false,
        )
      assertTrue("Effects missing for Memories transition $transition", effects.isNotEmpty())
    }

    PortraitFraming.entries.forEach { framing ->
      val effects =
        TemplateVideoEffects.buildVideoEffects(
          VynozaTemplateId.CINEMATIC_PORTRAIT,
          TemplateParameters(portraitFraming = framing, contrast = 1.3f),
          isPhotoMotionExport = true,
        )
      assertTrue("Effects missing for Portrait framing $framing", effects.isNotEmpty())
    }
  }

  @Test
  fun `video export supports cancellation, cleans partial output, preserves original, and propagates driver failures`(): Unit =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val sourceVideo = File(context.cacheDir, "unit_src_clip.mp4").apply {
        writeBytes(ByteArray(4096) { 0x1A })
      }
      val startedGate = CompletableDeferred<File>()
      var partialFileRef: File? = null

      val cancellingDriver =
        object : TemplateVideoExportDriver {
          override suspend fun transformToMp4(
            context: Context,
            editedMediaItem: EditedMediaItem,
            outputFile: File,
            onProgress: (Float) -> Unit,
          ): Result<File> {
            partialFileRef = outputFile
            outputFile.writeBytes(byteArrayOf(1, 2, 3))
            onProgress(0.35f)
            startedGate.complete(outputFile)
            delay(5_000L)
            return Result.success(outputFile)
          }
        }

      val processor = VideoTemplateProcessor(cancellingDriver)
      val job =
        async {
          processor.exportVideoWithTemplate(
            context = context,
            sourceUri = Uri.fromFile(sourceVideo),
            estimatedSizeBytes = sourceVideo.length(),
            templateId = VynozaTemplateId.CLONE_ECHO,
            params = TemplateParameters.defaultsFor(VynozaTemplateId.CLONE_ECHO),
            onProgress = {},
          )
        }

      val partial = withTimeout(5_000L) { startedGate.await() }
      assertTrue("Partial file created during export", partial.exists())
      job.cancel(CancellationException("User cancelled export"))
      runCatching { job.await() }

      assertFalse("Partial export file must be cleaned up on cancellation", partialFileRef?.exists() == true)
      assertTrue("Original video file must stay intact", sourceVideo.exists() && sourceVideo.length() == 4096L)

      val failingProcessor =
        VideoTemplateProcessor(
          object : TemplateVideoExportDriver {
            override suspend fun transformToMp4(
              context: Context,
              editedMediaItem: EditedMediaItem,
              outputFile: File,
              onProgress: (Float) -> Unit,
            ): Result<File> = Result.failure(IOException("Simulated encoder error"))
          }
        )
      val failResult =
        failingProcessor.exportVideoWithTemplate(
          context = context,
          sourceUri = Uri.fromFile(sourceVideo),
          estimatedSizeBytes = sourceVideo.length(),
          templateId = VynozaTemplateId.MOTION_BLUR,
          params = TemplateParameters.defaultsFor(VynozaTemplateId.MOTION_BLUR),
          onProgress = {},
        )
      assertTrue(failResult.isFailure)
      assertEquals("Simulated encoder error", failResult.exceptionOrNull()?.message)

      val missingUriResult =
        failingProcessor.exportVideoWithTemplate(
          context = context,
          sourceUri = Uri.fromFile(File(context.cacheDir, "does_not_exist_123.mp4")),
          estimatedSizeBytes = null,
          templateId = VynozaTemplateId.CINEMATIC_PORTRAIT,
          params = TemplateParameters.defaultsFor(VynozaTemplateId.CINEMATIC_PORTRAIT),
          onProgress = {},
        )
      assertTrue(missingUriResult.isFailure)
      sourceVideo.delete()
    }

  @Test
  fun `exported template mp4 flows cleanly into CreateViewModel for publishing`(): Unit =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val templateOutDir = File(context.cacheDir, "templates").apply { mkdirs() }
      val fakeRenderedMp4 = File(templateOutDir, "vynoza_cinematic_test.mp4").apply {
        writeBytes(ByteArray(16 * 1024) { 0x33 })
      }

      val stubMediaService =
        object : MediaStorageService {
          override fun inspectLocalClip(context: Context, uri: Uri, source: ClipSource): LocalVideoClip {
            return LocalVideoClip(
              uri = uri,
              displayName = "vynoza_cinematic_test.mp4",
              sizeBytes = fakeRenderedMp4.length(),
              mimeType = "video/mp4",
              source = source,
            )
          }

          override fun validateClipForUpload(context: Context, clip: LocalVideoClip): ClipValidationResult {
            return ClipValidationResult.Valid("video/mp4", clip.sizeBytes)
          }

          override suspend fun uploadVideo(
            context: Context,
            clip: LocalVideoClip,
            onProgress: (Float?) -> Unit,
          ): VideoUploadResult {
            return VideoUploadResult.Success("https://res.cloudinary.com/idkq3e9l/video/upload/v1/t.mp4", "t")
          }
        }

      val createVm =
        CreateViewModel(
          mediaStorageService = stubMediaService,
          currentUserId = "creator_1",
          postPublisher = { _, _, _, _ -> Result.success("vid_tpl_01") },
        )

      val uri = TemplateMediaStorage.fileProviderUriFor(context, fakeRenderedMp4)
      createVm.onClipAcquired(context, uri, ClipSource.TEMPLATE_STUDIO)

      val state = createVm.uiState.value
      assertEquals(ClipSource.TEMPLATE_STUDIO, state.selectedClip?.source)
      assertEquals("vynoza_cinematic_test.mp4", state.selectedClip?.displayName)
      assertEquals(null, state.validationError)
      fakeRenderedMp4.delete()
    }

  @Test
  fun `create screen shows templates studio entry and studio navigates gallery to editor`() {
    var openedStudio = false
    val createVm =
      CreateViewModel(
        mediaStorageService = CloudinaryMediaStorageService(),
        currentUserId = "user_1",
      )

    composeTestRule.setContent {
      VynozaTheme {
        CreateScreen(
          viewModel = createVm,
          onOpenTemplatesStudio = { openedStudio = true },
        )
      }
    }

    composeTestRule.onNodeWithTag("create_templates_studio_button").performScrollTo().performClick()
    assertTrue(openedStudio)
  }

  @Test
  fun `templates studio screen displays all 5 templates, opens editor, and updates compare toggle`() {
    val studioVm = TemplatesStudioViewModel()
    composeTestRule.setContent {
      VynozaTheme {
        TemplatesStudioScreen(
          viewModel = studioVm,
          onBackToCreate = {},
          onExportedVideoReadyForPublish = {},
        )
      }
    }
    composeTestRule.waitForIdle()

    VynozaTemplateCatalog.templates.forEach { spec ->
      composeTestRule
        .onNodeWithTag("templates_gallery_grid")
        .performScrollToNode(hasTestTag("template_card_${spec.id.routeArg}"))
      composeTestRule.onNodeWithTag("template_card_${spec.id.routeArg}").assertExists()
    }

    composeTestRule
      .onNodeWithTag("templates_gallery_grid")
      .performScrollToNode(hasTestTag("template_card_cinematic_portrait"))
    composeTestRule.onNodeWithTag("template_card_cinematic_portrait").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("templates_compare_toggle").assertIsDisplayed().performClick()
    assertTrue(studioVm.uiState.value.showOriginalComparison)
    composeTestRule.onNodeWithTag("templates_save_photo_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("templates_publish_video_button").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun `inspectSelectedMedia detects image and video kinds and rejects unknown mime`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val imgFile = File(context.cacheDir, "sample.jpg").apply { writeBytes(byteArrayOf(1, 2)) }
    val vidFile = File(context.cacheDir, "sample.mp4").apply { writeBytes(byteArrayOf(1, 2)) }
    val txtFile = File(context.cacheDir, "notes.txt").apply { writeText("hello") }

    val imgMeta = TemplateMediaStorage.inspectSelectedMedia(context, Uri.fromFile(imgFile))
    val vidMeta = TemplateMediaStorage.inspectSelectedMedia(context, Uri.fromFile(vidFile))
    val txtMeta = TemplateMediaStorage.inspectSelectedMedia(context, Uri.fromFile(txtFile))

    assertEquals(TemplateMediaKind.PHOTO, imgMeta?.kind)
    assertEquals(TemplateMediaKind.VIDEO, vidMeta?.kind)
    assertEquals(null, txtMeta)

    imgFile.delete()
    vidFile.delete()
    txtFile.delete()
  }

  @Test
  fun `decodeSampledBitmap downscales large images cleanly and fails gracefully on corrupt data`(): Unit =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val largeFile = File(context.cacheDir, "large_input.jpg")
      FileOutputStream(largeFile).use { out ->
        createStudioTestBitmap(800, 1000).compress(Bitmap.CompressFormat.JPEG, 92, out)
      }
      val corruptFile = File(context.cacheDir, "corrupt_input.jpg").apply {
        writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
      }

      val sampledResult =
        PhotoTemplateProcessor.decodeSampledBitmap(context, Uri.fromFile(largeFile), maxDimension = 180)
      assertTrue(sampledResult.isSuccess)
      val bmp = sampledResult.getOrThrow()
      assertTrue("Expected downsampled max edge <= 180, got ${bmp.width}x${bmp.height}", maxOf(bmp.width, bmp.height) <= 180)

      val corruptResult = PhotoTemplateProcessor.decodeSampledBitmap(context, Uri.fromFile(corruptFile))
      assertTrue("Corrupt image bytes must fail cleanly", corruptResult.isFailure)

      largeFile.delete()
      corruptFile.delete()
    }

  @Test
  fun `templates studio viewModel cancels active video export cleanly and resets to Idle`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val srcVideo = File(context.cacheDir, "vm_cancel_clip.mp4").apply {
      writeBytes(ByteArray(2048) { 0x22 })
    }
    val gate = CompletableDeferred<Unit>()
    val vm =
      TemplatesStudioViewModel(
        videoTemplateProcessor =
          VideoTemplateProcessor(
            object : TemplateVideoExportDriver {
              override suspend fun transformToMp4(
                context: Context,
                editedMediaItem: EditedMediaItem,
                outputFile: File,
                onProgress: (Float) -> Unit,
              ): Result<File> {
                outputFile.writeBytes(byteArrayOf(7, 8, 9))
                onProgress(0.4f)
                gate.complete(Unit)
                delay(5_000L)
                return Result.success(outputFile)
              }
            }
          )
      )

    vm.onMediaPicked(context, Uri.fromFile(srcVideo))
    vm.exportAndSaveVideoToDevice(context)

    val deadlineMs = System.currentTimeMillis() + 5_000L
    while (!gate.isCompleted && System.currentTimeMillis() < deadlineMs) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      Thread.sleep(15L)
    }
    assertTrue("Timed out waiting for export driver to start", gate.isCompleted)
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertTrue(vm.uiState.value.exportState is TemplateExportState.Processing)
    vm.cancelExport()
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertEquals(TemplateExportState.Idle, vm.uiState.value.exportState)
    srcVideo.delete()
  }

  @Test
  fun `storage check rejects photo and video export when free space is critically low`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    try {
      // Simulate 4 MB total / 2 MB free on cache filesystem
      ShadowStatFs.registerStats(context.cacheDir.absolutePath, 1024, 512, 512)
      val photoCheck = TemplateMediaStorage.ensureEnoughStorageForPhoto(context)
      val videoCheck = TemplateMediaStorage.ensureEnoughStorageForVideo(context, 10L * 1024L * 1024L)
      assertTrue("Low storage must reject photo export", photoCheck.isFailure)
      assertTrue("Low storage must reject video export", videoCheck.isFailure)
    } finally {
      ShadowStatFs.reset()
    }
  }
}
