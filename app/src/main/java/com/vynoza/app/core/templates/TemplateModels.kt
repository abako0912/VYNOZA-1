package com.vynoza.app.core.templates

import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VynozaTemplateId(val routeArg: String) {
  CLONE_ECHO("clone_echo"),
  CINEMATIC_PORTRAIT("cinematic_portrait"),
  MOTION_BLUR("motion_blur"),
  MULTIPLE_EXPOSURE("multiple_exposure"),
  MEMORIES("memories");

  companion object {
    fun fromRouteArg(value: String?): VynozaTemplateId {
      return entries.firstOrNull { it.routeArg == value } ?: CLONE_ECHO
    }
  }
}

enum class TemplateMediaKind {
  PHOTO,
  VIDEO,
}

enum class TemplateMediaSupport(val label: String) {
  PHOTO_AND_VIDEO("Photo & Video"),
  PHOTO_ONLY("Photo Only"),
}

enum class CloneArrangement(val label: String) {
  HORIZONTAL_FAN("Fan"),
  DIAGONAL_ECHO("Diagonal"),
  ARC_HALO("Halo Arc"),
}

enum class ExposureBlendMode(val label: String) {
  SCREEN("Screen"),
  LIGHTEN("Lighten"),
  OVERLAY("Overlay"),
  SOFT_ADD("Dream Glow"),
}

enum class MemoriesFrameStyle(val label: String) {
  POLAROID("Polaroid"),
  FILM_STRIP("35mm Film"),
  SCRAPBOOK("Editorial"),
}

enum class MemoriesTransitionStyle(val label: String) {
  KEN_BURNS("Gentle Zoom"),
  FADE_GLOW("Soft Fade"),
  WARM_REVEAL("Warm Sweep"),
}

enum class PortraitFraming(val label: String) {
  ORIGINAL("Original"),
  PORTRAIT_4_5("4:5 Portrait"),
  CINEMA_9_16("9:16 Reels"),
}

data class TemplateParameters(
  // Clone & Echo
  val cloneCount: Int = 3,
  val cloneSpacing: Float = 0.42f, // 0.1f..1.0f
  val cloneOpacity: Float = 0.62f, // 0.15f..0.95f
  val cloneArrangement: CloneArrangement = CloneArrangement.HORIZONTAL_FAN,
  val cloneEchoTint: Boolean = true,

  // Cinematic Portrait
  val contrast: Float = 1.24f, // 0.7f..1.8f
  val saturation: Float = 1.15f, // 0.3f..1.7f
  val shadows: Float = 0.18f, // -0.5f..0.6f
  val highlights: Float = -0.22f, // -0.6f..0.5f
  val portraitFraming: PortraitFraming = PortraitFraming.PORTRAIT_4_5,
  val smoothZoom: Float = 0.14f, // 0.0f..0.35f (for video / photo-to-video)

  // Motion Blur
  val blurIntensity: Float = 0.58f, // 0.05f..1.0f
  val blurAngleDegrees: Float = 18f, // 0f..360f
  val ghostTrail: Float = 0.52f, // 0.0f..1.0f

  // Multiple Exposure
  val exposureOpacity: Float = 0.55f, // 0.15f..0.90f
  val exposureOffset: Float = 0.35f, // 0.05f..0.85f
  val exposureScale: Float = 1.12f, // 0.85f..1.40f
  val exposureBlendMode: ExposureBlendMode = ExposureBlendMode.SCREEN,
  val exposureMirrorSecond: Boolean = true,

  // Memories
  val memoriesFrameStyle: MemoriesFrameStyle = MemoriesFrameStyle.POLAROID,
  val memoriesTitle: String = "Golden Hour Memories",
  val memoriesDateText: String = defaultDateStamp(),
  val memoriesTransition: MemoriesTransitionStyle = MemoriesTransitionStyle.KEN_BURNS,
  val memoriesWarmth: Float = 0.45f, // 0.0f..1.0f
) {
  companion object {
    fun defaultDateStamp(): String {
      return SimpleDateFormat("MMM dd, yyyy", Locale.US).format(Date())
    }

    fun defaultsFor(id: VynozaTemplateId): TemplateParameters {
      return when (id) {
        VynozaTemplateId.CLONE_ECHO -> TemplateParameters(
          cloneCount = 3,
          cloneSpacing = 0.45f,
          cloneOpacity = 0.65f,
          cloneArrangement = CloneArrangement.HORIZONTAL_FAN,
          cloneEchoTint = true,
        )
        VynozaTemplateId.CINEMATIC_PORTRAIT -> TemplateParameters(
          contrast = 1.28f,
          saturation = 1.16f,
          shadows = 0.20f,
          highlights = -0.24f,
          portraitFraming = PortraitFraming.PORTRAIT_4_5,
          smoothZoom = 0.16f,
        )
        VynozaTemplateId.MOTION_BLUR -> TemplateParameters(
          blurIntensity = 0.62f,
          blurAngleDegrees = 16f,
          ghostTrail = 0.56f,
        )
        VynozaTemplateId.MULTIPLE_EXPOSURE -> TemplateParameters(
          exposureOpacity = 0.58f,
          exposureOffset = 0.38f,
          exposureScale = 1.14f,
          exposureBlendMode = ExposureBlendMode.SCREEN,
          exposureMirrorSecond = true,
        )
        VynozaTemplateId.MEMORIES -> TemplateParameters(
          memoriesFrameStyle = MemoriesFrameStyle.POLAROID,
          memoriesTitle = "Golden Hour Memories",
          memoriesDateText = defaultDateStamp(),
          memoriesTransition = MemoriesTransitionStyle.KEN_BURNS,
          memoriesWarmth = 0.48f,
        )
      }
    }
  }
}

data class VynozaTemplateSpec(
  val id: VynozaTemplateId,
  val title: String,
  val badge: String,
  val shortDescription: String,
  val mediaSupport: TemplateMediaSupport,
  val videoEffectNote: String,
  val photoEffectNote: String,
  val defaultParameters: TemplateParameters,
)

object VynozaTemplateCatalog {
  val templates: List<VynozaTemplateSpec> = listOf(
    VynozaTemplateSpec(
      id = VynozaTemplateId.CLONE_ECHO,
      title = "Clone & Echo",
      badge = "Echo FX",
      shortDescription = "Multi-copy spatial clones with adjustable scale, spacing, opacity, and video trail.",
      mediaSupport = TemplateMediaSupport.PHOTO_AND_VIDEO,
      photoEffectNote = "Extracts subject contours and arranges scaled echo copies across the canvas.",
      videoEffectNote = "Runs an on-device GLSL spatial echo & motion-trail shader during preview and MP4 export.",
      defaultParameters = TemplateParameters.defaultsFor(VynozaTemplateId.CLONE_ECHO),
    ),
    VynozaTemplateSpec(
      id = VynozaTemplateId.CINEMATIC_PORTRAIT,
      title = "Cinematic Portrait",
      badge = "Film Grade",
      shortDescription = "Sculpted contrast, highlight roll-off, shadow lift, portrait framing, and smooth zoom.",
      mediaSupport = TemplateMediaSupport.PHOTO_AND_VIDEO,
      photoEffectNote = "Applies tone-curve grading, saturation sculpting, radial vignette, and portrait crop.",
      videoEffectNote = "Renders GLSL film grading + smooth presentation-time zoom in ExoPlayer and Transformer.",
      defaultParameters = TemplateParameters.defaultsFor(VynozaTemplateId.CINEMATIC_PORTRAIT),
    ),
    VynozaTemplateSpec(
      id = VynozaTemplateId.MOTION_BLUR,
      title = "Motion Blur",
      badge = "Velocity",
      shortDescription = "Directional multi-tap speed blur with trailing ghost echoes and adjustable vector.",
      mediaSupport = TemplateMediaSupport.PHOTO_AND_VIDEO,
      photoEffectNote = "Computes directional kernel convolution along your chosen angle plus ghost trail.",
      videoEffectNote = "Applies an 11-tap directional GLSL motion-blur and ghost-trail shader across frames.",
      defaultParameters = TemplateParameters.defaultsFor(VynozaTemplateId.MOTION_BLUR),
    ),
    VynozaTemplateSpec(
      id = VynozaTemplateId.MULTIPLE_EXPOSURE,
      title = "Multiple Exposure",
      badge = "Double Blend",
      shortDescription = "Layered dual exposures with Screen, Lighten, Overlay & Dream Glow blend modes.",
      mediaSupport = TemplateMediaSupport.PHOTO_AND_VIDEO,
      photoEffectNote = "Composites mirrored, scaled, and shifted photo exposures using pixel blend math.",
      videoEffectNote = "Blends scaled, offset, and color-shifted live video exposures in a real-time GLSL shader.",
      defaultParameters = TemplateParameters.defaultsFor(VynozaTemplateId.MULTIPLE_EXPOSURE),
    ),
    VynozaTemplateSpec(
      id = VynozaTemplateId.MEMORIES,
      title = "Memories",
      badge = "Story Frame",
      shortDescription = "Curated Polaroid, 35mm film, and editorial frames with custom title, date & transitions.",
      mediaSupport = TemplateMediaSupport.PHOTO_AND_VIDEO,
      photoEffectNote = "Renders warm vintage color finish, frame layout, and custom typography caption.",
      videoEffectNote = "Bakes the framed border + caption overlay with fade/zoom transition into an MP4 clip.",
      defaultParameters = TemplateParameters.defaultsFor(VynozaTemplateId.MEMORIES),
    ),
  )

  fun findById(id: VynozaTemplateId): VynozaTemplateSpec {
    return templates.first { it.id == id }
  }
}

data class SelectedTemplateMedia(
  val uri: Uri,
  val kind: TemplateMediaKind,
  val displayName: String,
  val mimeType: String?,
  val sizeBytes: Long?,
)

enum class ExportTargetAction {
  SAVE_PHOTO_TO_DEVICE,
  SAVE_VIDEO_TO_DEVICE,
  PREPARE_VIDEO_FOR_PUBLISH,
}

sealed interface TemplateExportState {
  data object Idle : TemplateExportState

  data class Processing(
    val action: ExportTargetAction,
    val progress: Float,
    val statusLabel: String,
  ) : TemplateExportState

  data class SavedPhoto(
    val filePath: String,
    val contentUri: Uri,
  ) : TemplateExportState

  data class ExportedVideo(
    val filePath: String,
    val fileUri: Uri,
    val savedToGallery: Boolean,
    val readyForPublish: Boolean,
  ) : TemplateExportState

  data class Error(val message: String) : TemplateExportState
}
