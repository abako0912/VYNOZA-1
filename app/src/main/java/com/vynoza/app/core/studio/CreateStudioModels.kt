package com.vynoza.app.core.studio

import java.util.UUID

// ---------------------------------------------------------------------------
// Music Studio Models
// ---------------------------------------------------------------------------

enum class MusicDiscoverySection(val label: String) {
  TRENDING("Trending"),
  RECOMMENDATIONS("For You"),
  FAVORITES("Favorites"),
  RECENTLY_PLAYED("Recent"),
  SEARCH("Search"),
}

data class MusicTrack(
  val id: String,
  val title: String,
  val artistName: String,
  val artistHandle: String,
  val artworkUrl: String?,
  val artworkLargeUrl: String? = null,
  val durationSeconds: Int,
  val genre: String = "",
  val mood: String = "",
  val license: String = "All Rights Reserved",
  val isStreamable: Boolean = true,
  val isDownloadable: Boolean = false,
  val streamUrl: String,
  val playCount: Long = 0L,
  val permalink: String = "",
) {
  val durationMs: Long
    get() = durationSeconds.coerceAtLeast(1) * 1000L

  val attributionSummary: String
    get() =
      buildString {
        append(artistName.ifBlank { artistHandle })
        if (artistHandle.isNotBlank() && !artistHandle.equals(artistName, ignoreCase = true)) {
          append(" (@")
          append(artistHandle.removePrefix("@"))
          append(")")
        }
        if (license.isNotBlank()) {
          append(" • ")
          append(license)
        }
      }
}

data class MusicTrimSelection(
  val track: MusicTrack,
  val startMs: Long = 0L,
  val durationMs: Long = DEFAULT_TRIM_DURATION_MS,
  val waveformAmplitudes: List<Float> = emptyList(),
) {
  val clampedStartMs: Long
    get() = startMs.coerceIn(0L, (track.durationMs - MIN_TRIM_DURATION_MS).coerceAtLeast(0L))

  val clampedDurationMs: Long
    get() {
      val remaining = (track.durationMs - clampedStartMs).coerceAtLeast(MIN_TRIM_DURATION_MS)
      return durationMs.coerceIn(MIN_TRIM_DURATION_MS, minOf(MAX_TRIM_DURATION_MS, remaining))
    }

  val endMs: Long
    get() = (clampedStartMs + clampedDurationMs).coerceAtMost(track.durationMs)

  companion object {
    const val MIN_TRIM_DURATION_MS = 3_000L
    const val DEFAULT_TRIM_DURATION_MS = 15_000L
    const val MAX_TRIM_DURATION_MS = 60_000L
  }
}

// ---------------------------------------------------------------------------
// Camera Studio Models
// ---------------------------------------------------------------------------

enum class CaptureMode(val label: String) {
  VIDEO("Video"),
  PHOTO("Photo"),
  TEXT("Text"),
}

enum class RecordingDurationOption(
  val seconds: Int,
  val label: String,
) {
  FIFTEEN_SECONDS(15, "15s"),
  SIXTY_SECONDS(60, "60s"),
  TEN_MINUTES(600, "10m");

  val durationMs: Long
    get() = seconds * 1000L
}

enum class TextStudioBackground(
  val label: String,
  val startColorArgb: Long,
  val endColorArgb: Long,
  val textColorArgb: Long,
) {
  VIOLET_NIGHT("Violet Night", 0xFF2D1252, 0xFF0E0B1A, 0xFFF6F4FB),
  SUNSET_ROSE("Sunset Rose", 0xFF9D174D, 0xFFF59E0B, 0xFFFFFFFF),
  CYBER_CYAN("Cyber Cyan", 0xFF0F172A, 0xFF0EA5E9, 0xFFF8FAFC),
  EDITORIAL_CREAM("Editorial", 0xFFF5EFE6, 0xFFE6D5B8, 0xFF1E1926),
  EMERALD_DARK("Emerald", 0xFF064E3B, 0xFF090D16, 0xFFECFDF5),
}

enum class TextStudioAlignment(val label: String) {
  CENTER("Center"),
  LEFT("Left"),
  RIGHT("Right"),
}

data class CameraCaptureOutput(
  val clips: List<EditorMediaClip>,
  val selectedMusic: MusicTrimSelection? = null,
  val effectPresetId: String = StudioEffectCatalog.EFFECT_NONE_ID,
)

enum class CameraLensFacing(val label: String) {
  BACK("Back"),
  FRONT("Front"),
}

enum class CameraFlashMode(val label: String) {
  OFF("Off"),
  ON("On"),
  AUTO("Auto"),
}

enum class CameraTimerOption(val seconds: Int, val label: String) {
  OFF(0, "Off"),
  THREE_SECONDS(3, "3s"),
  TEN_SECONDS(10, "10s"),
}

enum class CameraLayoutOption(
  val label: String,
  val aspectRatio: Float,
  val showRuleOfThirds: Boolean = false,
  val isSplitGuide: Boolean = false,
) {
  PORTRAIT_9_16("9:16 Full", 9f / 16f, showRuleOfThirds = false),
  GRID_9_16("9:16 Grid", 9f / 16f, showRuleOfThirds = true),
  FEED_4_5("4:5 Portrait", 4f / 5f, showRuleOfThirds = false),
  SQUARE_1_1("1:1 Square", 1f, showRuleOfThirds = false),
  SPLIT_DUAL("Split Guide", 9f / 16f, showRuleOfThirds = false, isSplitGuide = true),
}

enum class StudioEffectCategory(val label: String) {
  ALL("All"),
  COLOR("Color"),
  GLOW("Glow"),
  MOTION("Motion"),
  RETRO("Retro"),
}

data class StudioEffectPreset(
  val id: String,
  val name: String,
  val description: String,
  val category: StudioEffectCategory,
  val badge: String,
  val accentColorArgb: Long,
  val isFeaturedInCarousel: Boolean,
)

object StudioEffectCatalog {
  const val EFFECT_NONE_ID = "none"

  val presets: List<StudioEffectPreset> =
    listOf(
      StudioEffectPreset(
        id = EFFECT_NONE_ID,
        name = "Normal",
        description = "True-to-life capture with balanced exposure.",
        category = StudioEffectCategory.ALL,
        badge = "Clean",
        accentColorArgb = 0xFF8E8AA0,
        isFeaturedInCarousel = true,
      ),
      StudioEffectPreset(
        id = "violet_aura",
        name = "Violet Aura",
        description = "Signature VYNOZA neon-violet highlights and rich velvet shadows.",
        category = StudioEffectCategory.GLOW,
        badge = "Signature",
        accentColorArgb = 0xFF9D4EDD,
        isFeaturedInCarousel = true,
      ),
      StudioEffectPreset(
        id = "cinematic_noir",
        name = "Cine Noir",
        description = "High-contrast film grade with crisp monochrome depth.",
        category = StudioEffectCategory.COLOR,
        badge = "Film",
        accentColorArgb = 0xFF5A566B,
        isFeaturedInCarousel = true,
      ),
      StudioEffectPreset(
        id = "golden_hour",
        name = "Golden Hour",
        description = "Warm sunset skin tones with lifted amber highlights.",
        category = StudioEffectCategory.COLOR,
        badge = "Warm",
        accentColorArgb = 0xFFFF9F1C,
        isFeaturedInCarousel = true,
      ),
      StudioEffectPreset(
        id = "cyber_prism",
        name = "Cyber Prism",
        description = "Electric magenta & cyan edge bloom for dance and night clips.",
        category = StudioEffectCategory.GLOW,
        badge = "Neon",
        accentColorArgb = 0xFFEC4899,
        isFeaturedInCarousel = true,
      ),
      StudioEffectPreset(
        id = "clone_echo",
        name = "Clone Echo",
        description = "Dynamic multi-copy ghost trails synchronized to movement.",
        category = StudioEffectCategory.MOTION,
        badge = "FX",
        accentColorArgb = 0xFFC77DFF,
        isFeaturedInCarousel = true,
      ),
      StudioEffectPreset(
        id = "motion_streak",
        name = "Speed Blur",
        description = "Directional velocity streaks and high-energy afterimages.",
        category = StudioEffectCategory.MOTION,
        badge = "Motion",
        accentColorArgb = 0xFF38BDF8,
        isFeaturedInCarousel = true,
      ),
      StudioEffectPreset(
        id = "double_exposure",
        name = "Double Vision",
        description = "Layered translucent exposure blend for dreamy portraits.",
        category = StudioEffectCategory.GLOW,
        badge = "Blend",
        accentColorArgb = 0xFFA855F7,
        isFeaturedInCarousel = false,
      ),
      StudioEffectPreset(
        id = "vhs_memories",
        name = "90s Camcorder",
        description = "Nostalgic warm analog halation with retro tape framing.",
        category = StudioEffectCategory.RETRO,
        badge = "Retro",
        accentColorArgb = 0xFFF59E0B,
        isFeaturedInCarousel = false,
      ),
      StudioEffectPreset(
        id = "polaroid_fade",
        name = "Instant Fade",
        description = "Soft faded blacks and creamy highlights inspired by instant film.",
        category = StudioEffectCategory.RETRO,
        badge = "Soft",
        accentColorArgb = 0xFFFBBF24,
        isFeaturedInCarousel = false,
      ),
    )

  val carouselPresets: List<StudioEffectPreset>
    get() = presets.filter { it.isFeaturedInCarousel }

  fun presetsFor(category: StudioEffectCategory): List<StudioEffectPreset> =
    if (category == StudioEffectCategory.ALL) presets else presets.filter { it.category == category }

  fun findById(id: String?): StudioEffectPreset =
    presets.firstOrNull { it.id == id } ?: presets.first()
}

// ---------------------------------------------------------------------------
// Template Studio Models (Multi-Slot Discovery & Real Rendering)
// ---------------------------------------------------------------------------

enum class TemplateDiscoveryCategory(val label: String) {
  FOR_YOU("For You"),
  VIRAL_SONG("Viral Song"),
  TRENDY("Trendy"),
  AI("AI"),
  MONTHLY("Monthly"),
}

enum class TemplateSlotMediaType(val label: String) {
  PHOTO("Photo"),
  VIDEO("Video"),
  PHOTO_OR_VIDEO("Photo or Video"),
}

data class TemplateSlotSpec(
  val index: Int,
  val label: String,
  val durationMs: Long,
  val allowedMedia: TemplateSlotMediaType = TemplateSlotMediaType.PHOTO_OR_VIDEO,
)

data class StudioTemplateSpec(
  val id: String,
  val title: String,
  val subtitle: String,
  val categories: Set<TemplateDiscoveryCategory>,
  val creatorName: String,
  val creatorHandle: String,
  val slots: List<TemplateSlotSpec>,
  val effectPresetId: String,
  val transitionStyle: String,
  val suggestedMusicQuery: String,
  val aspectRatioLabel: String = "9:16",
  val previewColorsArgb: Pair<Long, Long>,
  val badge: String,
) {
  val requiredMediaCount: Int
    get() = slots.size

  val estimatedDurationSec: Int
    get() = ((slots.sumOf { it.durationMs }) / 1000L).toInt().coerceAtLeast(1)

  val allowsPhotos: Boolean
    get() = slots.any { it.allowedMedia == TemplateSlotMediaType.PHOTO || it.allowedMedia == TemplateSlotMediaType.PHOTO_OR_VIDEO }

  val allowsVideos: Boolean
    get() = slots.any { it.allowedMedia == TemplateSlotMediaType.VIDEO || it.allowedMedia == TemplateSlotMediaType.PHOTO_OR_VIDEO }
}

object StudioTemplateCatalog {
  val templates: List<StudioTemplateSpec> =
    listOf(
      StudioTemplateSpec(
        id = "tpl_beat_pulse_3",
        title = "Neon Beat Pulse",
        subtitle = "3-clip fast cut sequence with Violet Aura glow & zoom hits.",
        categories = setOf(TemplateDiscoveryCategory.FOR_YOU, TemplateDiscoveryCategory.VIRAL_SONG),
        creatorName = "VYNOZA Sound Lab",
        creatorHandle = "vynoza.sound",
        slots =
          listOf(
            TemplateSlotSpec(0, "Opening Hook", 2_500L),
            TemplateSlotSpec(1, "Beat Drop", 2_000L),
            TemplateSlotSpec(2, "Outro Glow", 3_000L),
          ),
        effectPresetId = "violet_aura",
        transitionStyle = "Beat Zoom",
        suggestedMusicQuery = "electronic dance",
        previewColorsArgb = 0xFF6D28D9 to 0xFFEC4899,
        badge = "Viral Cut",
      ),
      StudioTemplateSpec(
        id = "tpl_clone_choreo",
        title = "Clone Echo Stage",
        subtitle = "Motion-tracked echo trails built for solo dance & outfit turns.",
        categories = setOf(TemplateDiscoveryCategory.FOR_YOU, TemplateDiscoveryCategory.TRENDY),
        creatorName = "VYNOZA Motion",
        creatorHandle = "vynoza.motion",
        slots =
          listOf(
            TemplateSlotSpec(0, "Main Clip", 4_000L, TemplateSlotMediaType.PHOTO_OR_VIDEO),
            TemplateSlotSpec(1, "Finale Pose", 2_500L, TemplateSlotMediaType.PHOTO_OR_VIDEO),
          ),
        effectPresetId = "clone_echo",
        transitionStyle = "Echo Fan",
        suggestedMusicQuery = "hip hop beat",
        previewColorsArgb = 0xFF7B2CBF to 0xFFC77DFF,
        badge = "Trending",
      ),
      StudioTemplateSpec(
        id = "tpl_ai_dreamscape",
        title = "AI Prism Morph",
        subtitle = "Multi-exposure prism layering with cyber highlights across 4 shots.",
        categories = setOf(TemplateDiscoveryCategory.FOR_YOU, TemplateDiscoveryCategory.AI),
        creatorName = "VYNOZA AI Lab",
        creatorHandle = "vynoza.ai",
        slots =
          listOf(
            TemplateSlotSpec(0, "Portrait A", 2_200L),
            TemplateSlotSpec(1, "Angle B", 2_200L),
            TemplateSlotSpec(2, "Motion C", 2_200L),
            TemplateSlotSpec(3, "Reveal D", 2_400L),
          ),
        effectPresetId = "cyber_prism",
        transitionStyle = "Prism Blend",
        suggestedMusicQuery = "synthwave",
        previewColorsArgb = 0xFF2563EB to 0xFFD946EF,
        badge = "AI Style",
      ),
      StudioTemplateSpec(
        id = "tpl_monthly_recap_5",
        title = "Monthly Photo Dump",
        subtitle = "5-moment warm instant-film recap with nostalgic pacing.",
        categories = setOf(TemplateDiscoveryCategory.FOR_YOU, TemplateDiscoveryCategory.MONTHLY),
        creatorName = "VYNOZA Editorial",
        creatorHandle = "vynoza.journal",
        slots =
          listOf(
            TemplateSlotSpec(0, "Week 1", 2_000L),
            TemplateSlotSpec(1, "Week 2", 2_000L),
            TemplateSlotSpec(2, "Week 3", 2_000L),
            TemplateSlotSpec(3, "Week 4", 2_000L),
            TemplateSlotSpec(4, "Highlight", 3_000L),
          ),
        effectPresetId = "vhs_memories",
        transitionStyle = "Film Burn",
        suggestedMusicQuery = "lofi chill",
        previewColorsArgb = 0xFFD97706 to 0xFF7C3AED,
        badge = "Monthly",
      ),
      StudioTemplateSpec(
        id = "tpl_viral_bass_drop",
        title = "Bass Drop Velocity",
        subtitle = "High-energy directional speed blur for car, gym & street edits.",
        categories = setOf(TemplateDiscoveryCategory.VIRAL_SONG, TemplateDiscoveryCategory.TRENDY),
        creatorName = "VYNOZA Sound Lab",
        creatorHandle = "vynoza.sound",
        slots =
          listOf(
            TemplateSlotSpec(0, "Build-up", 3_000L),
            TemplateSlotSpec(1, "Drop Clip", 3_000L),
            TemplateSlotSpec(2, "Freeze Tag", 2_000L),
          ),
        effectPresetId = "motion_streak",
        transitionStyle = "Velocity Whip",
        suggestedMusicQuery = "phonk bass",
        previewColorsArgb = 0xFF0284C7 to 0xFF6D28D9,
        badge = "Fast Cut",
      ),
      StudioTemplateSpec(
        id = "tpl_ai_double_soul",
        title = "AI Twin Exposure",
        subtitle = "Dreamy mirrored double exposure combining portrait and skyline.",
        categories = setOf(TemplateDiscoveryCategory.AI, TemplateDiscoveryCategory.FOR_YOU),
        creatorName = "VYNOZA AI Lab",
        creatorHandle = "vynoza.ai",
        slots =
          listOf(
            TemplateSlotSpec(0, "Subject", 3_500L),
            TemplateSlotSpec(1, "Atmosphere", 3_500L),
          ),
        effectPresetId = "double_exposure",
        transitionStyle = "Ghost Dissolve",
        suggestedMusicQuery = "ambient dream",
        previewColorsArgb = 0xFF4C1D95 to 0xFFA855F7,
        badge = "AI Blend",
      ),
      StudioTemplateSpec(
        id = "tpl_trendy_noir_fit",
        title = "Cine Noir Fit Check",
        subtitle = "Sharp editorial contrast & smooth zoom across 3 outfit frames.",
        categories = setOf(TemplateDiscoveryCategory.TRENDY),
        creatorName = "VYNOZA Studio",
        creatorHandle = "vynoza.studio",
        slots =
          listOf(
            TemplateSlotSpec(0, "Detail Shot", 2_200L),
            TemplateSlotSpec(1, "Full Fit", 2_800L),
            TemplateSlotSpec(2, "Walkout", 3_000L),
          ),
        effectPresetId = "cinematic_noir",
        transitionStyle = "Smooth Zoom",
        suggestedMusicQuery = "rnb groove",
        previewColorsArgb = 0xFF1F2937 to 0xFF7C3AED,
        badge = "Editorial",
      ),
      StudioTemplateSpec(
        id = "tpl_monthly_golden_reel",
        title = "Golden Archive",
        subtitle = "Sunlit 4-clip month-in-review framed for cozy weekend stories.",
        categories = setOf(TemplateDiscoveryCategory.MONTHLY, TemplateDiscoveryCategory.VIRAL_SONG),
        creatorName = "VYNOZA Editorial",
        creatorHandle = "vynoza.journal",
        slots =
          listOf(
            TemplateSlotSpec(0, "Morning", 2_500L),
            TemplateSlotSpec(1, "Afternoon", 2_500L),
            TemplateSlotSpec(2, "Golden Hour", 2_500L),
            TemplateSlotSpec(3, "Night Lights", 2_500L),
          ),
        effectPresetId = "golden_hour",
        transitionStyle = "Warm Reveal",
        suggestedMusicQuery = "indie acoustic",
        previewColorsArgb = 0xFFF59E0B to 0xFFEC4899,
        badge = "Story",
      ),
    )

  fun templatesFor(category: TemplateDiscoveryCategory): List<StudioTemplateSpec> =
    templates.filter { category in it.categories }

  fun findById(id: String?): StudioTemplateSpec? = templates.firstOrNull { it.id == id }
}

// ---------------------------------------------------------------------------
// Video Editor, Publishing & Local Drafts Models
// ---------------------------------------------------------------------------

enum class EditorMediaKind {
  PHOTO,
  VIDEO,
}

data class EditorMediaClip(
  val id: String = UUID.randomUUID().toString(),
  val uriString: String,
  val kind: EditorMediaKind,
  val durationMs: Long = DEFAULT_PHOTO_CLIP_DURATION_MS,
  val trimStartMs: Long = 0L,
  val trimEndMs: Long = durationMs,
  val displayName: String = "clip",
) {
  val effectiveDurationMs: Long
    get() =
      when (kind) {
        EditorMediaKind.PHOTO -> durationMs.coerceIn(1_000L, 15_000L)
        EditorMediaKind.VIDEO -> (trimEndMs - trimStartMs).coerceAtLeast(500L)
      }

  companion object {
    const val DEFAULT_PHOTO_CLIP_DURATION_MS = 3_000L
  }
}

data class AudioMixSettings(
  val originalAudioVolume: Float = 1.0f,
  val musicVolume: Float = 0.85f,
  val syncOffsetMs: Long = 0L,
) {
  fun normalized(): AudioMixSettings =
    copy(
      originalAudioVolume = originalAudioVolume.coerceIn(0f, 1f),
      musicVolume = musicVolume.coerceIn(0f, 1f),
      syncOffsetMs = syncOffsetMs.coerceIn(0L, 60_000L),
    )
}

enum class DraftPrivacy(val firestoreValue: String, val label: String) {
  PUBLIC("public", "Public"),
  PRIVATE("private", "Private"),
}

data class CreateStudioDraft(
  val id: String = "draft_${UUID.randomUUID().toString().replace("-", "").take(12)}",
  val title: String = "",
  val createdAtMs: Long = System.currentTimeMillis(),
  val updatedAtMs: Long = createdAtMs,
  val clips: List<EditorMediaClip> = emptyList(),
  val selectedMusic: MusicTrimSelection? = null,
  val audioMix: AudioMixSettings = AudioMixSettings(),
  val effectPresetId: String = StudioEffectCatalog.EFFECT_NONE_ID,
  val templateId: String? = null,
  val coverFrameUriString: String? = null,
  val coverTimestampMs: Long = 0L,
  val caption: String = "",
  val hashtags: List<String> = emptyList(),
  val mentions: List<String> = emptyList(),
  val privacy: DraftPrivacy = DraftPrivacy.PUBLIC,
) {
  /**
   * Composes a Firestore-safe caption string respecting `firestore.rules` (`caption.size() <= 300`),
   * appending unique normalized `#hashtags` and `@mentions` without exceeding the limit.
   */
  fun buildPublishCaption(maxLength: Int = MAX_FIRESTORE_CAPTION_LENGTH): String {
    val cleanBase = caption.trim()
    val normalizedTags =
      hashtags.mapNotNull(::normalizeHashtag).distinctBy { it.lowercase() }
    val normalizedMentions =
      mentions.mapNotNull(::normalizeMention).distinctBy { it.lowercase() }

    val builder = StringBuilder(cleanBase)
    val tokensToAppend =
      (normalizedMentions + normalizedTags).filter { token ->
        !cleanBase.contains(token, ignoreCase = true)
      }

    for (token in tokensToAppend) {
      val separator = if (builder.isEmpty()) "" else " "
      if (builder.length + separator.length + token.length <= maxLength) {
        builder.append(separator).append(token)
      }
    }
    return builder.toString().take(maxLength).trim()
  }

  val totalDurationMs: Long
    get() = clips.sumOf { it.effectiveDurationMs }

  companion object {
    const val MAX_FIRESTORE_CAPTION_LENGTH = 300

    fun normalizeHashtag(raw: String): String? {
      val cleaned =
        raw.trim()
          .removePrefix("#")
          .filter { it.isLetterOrDigit() || it == '_' }
          .take(40)
      return if (cleaned.isBlank()) null else "#$cleaned"
    }

    fun normalizeMention(raw: String): String? {
      val cleaned =
        raw.trim()
          .removePrefix("@")
          .filter { it.isLetterOrDigit() || it == '_' || it == '.' }
          .take(32)
      return if (cleaned.isBlank()) null else "@$cleaned"
    }
  }
}
