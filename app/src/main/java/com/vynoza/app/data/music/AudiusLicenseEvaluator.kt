package com.vynoza.app.data.music

import com.vynoza.app.core.studio.MusicTrack
import java.util.Locale

enum class MusicEmbedEligibility {
  ELIGIBLE,
  PREVIEW_ONLY_ALL_RIGHTS_RESERVED,
  BLOCKED_NO_DERIVATIVES,
  BLOCKED_NON_COMMERCIAL,
  BLOCKED_STREAM_RESTRICTED,
  BLOCKED_UNCLEAR_LICENSE,
}

data class MusicLicenseDecision(
  val eligibility: MusicEmbedEligibility,
  val canEmbed: Boolean,
  val badgeLabel: String,
  val explanation: String,
  val attributionText: String,
)

/**
 * Evaluates Audius track metadata and license strings before allowing audio
 * mixing/embedding into an exported MP4 video.
 *
 * Audius streamability (`isStreamable`) or downloadability (`isDownloadable`)
 * never imply sync/modification/commercial redistribution rights.
 */
object AudiusLicenseEvaluator {

  fun formatAttribution(track: MusicTrack): String {
    val handleSuffix =
      if (track.artistHandle.isNotBlank() &&
        !track.artistHandle.equals(track.artistName, ignoreCase = true)
      ) {
        " (@${track.artistHandle.removePrefix("@")})"
      } else {
        ""
      }
    val artist = track.artistName.ifBlank { track.artistHandle.ifBlank { "Audius Creator" } }
    val licenseLabel = track.license.trim().ifBlank { "Unspecified License" }
    return "Music: \"${track.title}\" by $artist$handleSuffix • $licenseLabel"
  }

  fun evaluate(track: MusicTrack?): MusicLicenseDecision {
    if (track == null) {
      return MusicLicenseDecision(
        eligibility = MusicEmbedEligibility.ELIGIBLE,
        canEmbed = true,
        badgeLabel = "No Music",
        explanation = "No background music track selected.",
        attributionText = "",
      )
    }

    val attribution = formatAttribution(track)
    if (!track.isStreamable || track.streamUrl.isBlank()) {
      return MusicLicenseDecision(
        eligibility = MusicEmbedEligibility.BLOCKED_STREAM_RESTRICTED,
        canEmbed = false,
        badgeLabel = "Restricted",
        explanation =
          "This track's streaming is restricted by the artist on Audius and cannot be previewed or mixed.",
        attributionText = attribution,
      )
    }

    val rawLicense = track.license.trim()
    val normalized =
      rawLicense
        .lowercase(Locale.US)
        .replace('_', '-')
        .replace(Regex("\\s+"), " ")

    if (normalized.isEmpty()) {
      return MusicLicenseDecision(
        eligibility = MusicEmbedEligibility.BLOCKED_UNCLEAR_LICENSE,
        canEmbed = false,
        badgeLabel = "Preview Only",
        explanation =
          "No explicit open license was reported by Audius. Preview playback only — embedding into exported videos is disabled.",
        attributionText = attribution,
      )
    }

    if (normalized.contains("all rights reserved")) {
      return MusicLicenseDecision(
        eligibility = MusicEmbedEligibility.PREVIEW_ONLY_ALL_RIGHTS_RESERVED,
        canEmbed = false,
        badgeLabel = "Preview Only",
        explanation =
          "\"All Rights Reserved\" tracks can be previewed in-app via Audius, but cannot be embedded into exported/published videos.",
        attributionText = attribution,
      )
    }

    val hasNoDerivatives =
      normalized.contains("noderiv") ||
        normalized.contains("no deriv") ||
        normalized.contains("no-deriv") ||
        Regex("\\bnd\\b").containsMatchIn(normalized)

    if (hasNoDerivatives) {
      return MusicLicenseDecision(
        eligibility = MusicEmbedEligibility.BLOCKED_NO_DERIVATIVES,
        canEmbed = false,
        badgeLabel = "Preview Only (ND)",
        explanation =
          "License \"$rawLicense\" prohibits derivative works (syncing/trimming with video). Preview-only in Studio.",
        attributionText = attribution,
      )
    }

    val hasNonCommercial =
      normalized.contains("noncommercial") ||
        normalized.contains("non-commercial") ||
        normalized.contains("non commercial") ||
        Regex("\\bnc\\b").containsMatchIn(normalized)

    if (hasNonCommercial) {
      return MusicLicenseDecision(
        eligibility = MusicEmbedEligibility.BLOCKED_NON_COMMERCIAL,
        canEmbed = false,
        badgeLabel = "Preview Only (NC)",
        explanation =
          "License \"$rawLicense\" is Non-Commercial only and is blocked from social feed video export.",
        attributionText = attribution,
      )
    }

    val isCcZeroOrPublicDomain =
      Regex("^(cc0|cc-0|public domain|no rights reserved)(\\s+[0-9]+\\.[0-9]+)?(\\s+public domain)?$").matches(normalized) ||
        normalized == "cc0 1.0 universal"

    if (isCcZeroOrPublicDomain) {
      return MusicLicenseDecision(
        eligibility = MusicEmbedEligibility.ELIGIBLE,
        canEmbed = true,
        badgeLabel = "CC0 • Embed OK",
        explanation =
          "Open Public Domain / CC0 license permits video mixing and publishing. Creator attribution is still shown.",
        attributionText = attribution,
      )
    }

    val isCcBySa =
      Regex("^(creative commons|cc)[-\\s]+(attribution|by)[-\\s]*(sa|share[-\\s]*alike)(\\s+[0-9]+\\.[0-9]+)?$").matches(normalized)

    if (isCcBySa) {
      return MusicLicenseDecision(
        eligibility = MusicEmbedEligibility.ELIGIBLE,
        canEmbed = true,
        badgeLabel = "Embed OK • Attribution (SA)",
        explanation =
          "\"$rawLicense\" permits syncing, distribution, and commercial use with required artist attribution and ShareAlike notice.",
        attributionText = attribution,
      )
    }

    val isCreativeCommonsBy =
      Regex("^(creative commons|cc)[-\\s]+(attribution|by)(\\s+[0-9]+\\.[0-9]+)?$").matches(normalized)

    if (isCreativeCommonsBy) {
      return MusicLicenseDecision(
        eligibility = MusicEmbedEligibility.ELIGIBLE,
        canEmbed = true,
        badgeLabel = "Embed OK • Attribution",
        explanation =
          "\"$rawLicense\" permits syncing, reproduction, distribution, and commercial use with required artist attribution.",
        attributionText = attribution,
      )
    }

    return MusicLicenseDecision(
      eligibility = MusicEmbedEligibility.BLOCKED_UNCLEAR_LICENSE,
      canEmbed = false,
      badgeLabel = "Preview Only",
      explanation =
        "License \"$rawLicense\" could not be verified for video embedding. Preview-only until verified.",
      attributionText = attribution,
    )
  }
}
