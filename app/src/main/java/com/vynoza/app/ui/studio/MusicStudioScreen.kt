package com.vynoza.app.ui.studio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vynoza.app.R
import com.vynoza.app.core.model.UiState
import com.vynoza.app.core.studio.MusicDiscoverySection
import com.vynoza.app.core.studio.MusicTrack
import com.vynoza.app.core.studio.MusicTrimSelection
import com.vynoza.app.data.music.AudioPreviewPlaybackState
import com.vynoza.app.data.music.AudiusDiscoveryCategory
import com.vynoza.app.ui.components.VynozaEmptyStateCard
import com.vynoza.app.ui.components.VynozaErrorCard
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaErrorContainer
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaRoseAccent
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import com.vynoza.app.ui.theme.VynozaSurfaceHigh
import com.vynoza.app.ui.theme.VynozaTextMuted
import com.vynoza.app.ui.theme.VynozaTextSecondary
import com.vynoza.app.ui.theme.VynozaVioletContainer

@Composable
fun MusicStudioScreen(
  viewModel: MusicStudioViewModel,
  onBack: () -> Unit,
  onSoundSelected: (MusicTrimSelection) -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val keyboardController = LocalSoftwareKeyboardController.current

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(VynozaObsidian)
        .statusBarsPadding()
        .testTag("music_studio_screen"),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .widthIn(max = 720.dp)
          .align(Alignment.TopCenter),
    ) {
      // Top Bar
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        IconButton(
          onClick = onBack,
          modifier = Modifier.size(48.dp).testTag("music_back_button"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.music_studio_back),
            tint = MaterialTheme.colorScheme.onBackground,
          )
        }

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.music_studio_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
          )
          Text(
            text = stringResource(R.string.music_studio_audius_subtitle),
            style = MaterialTheme.typography.labelSmall,
            color = VynozaLavenderSoft,
          )
        }

        Surface(
          shape = RoundedCornerShape(99.dp),
          color = VynozaVioletContainer,
          border = BorderStroke(1.dp, VynozaPurplePrimary.copy(alpha = 0.45f)),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.VerifiedUser,
              contentDescription = null,
              tint = VynozaLavenderSoft,
              modifier = Modifier.size(14.dp),
            )
            Text(
              text = "Audius API",
              style = MaterialTheme.typography.labelSmall,
              color = VynozaLavenderSoft,
            )
          }
        }
      }

      // Search Bar
      OutlinedTextField(
        value = uiState.searchQuery,
        onValueChange = viewModel::onSearchQueryChanged,
        placeholder = {
          Text(
            text = stringResource(R.string.music_studio_search_hint),
            color = VynozaTextMuted,
          )
        },
        leadingIcon = {
          Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = VynozaLavenderSoft,
          )
        },
        trailingIcon = {
          if (uiState.searchQuery.isNotEmpty()) {
            IconButton(
              onClick = viewModel::clearSearch,
              modifier = Modifier.testTag("music_clear_search_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Clear search",
                tint = VynozaTextSecondary,
              )
            }
          }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions =
          KeyboardActions(
            onSearch = {
              keyboardController?.hide()
              viewModel.submitSearch()
            }
          ),
        shape = RoundedCornerShape(16.dp),
        colors =
          OutlinedTextFieldDefaults.colors(
            focusedContainerColor = VynozaSurfaceElevated,
            unfocusedContainerColor = VynozaSurfaceElevated,
            focusedBorderColor = VynozaPurplePrimary,
            unfocusedBorderColor = VynozaBorderSubtle,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
          ),
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("music_search_input"),
      )

      Spacer(modifier = Modifier.height(10.dp))

      // Discovery Section Tabs
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        val tabs =
          listOf(
            MusicDiscoverySection.TRENDING to "music_tab_trending",
            MusicDiscoverySection.RECOMMENDATIONS to "music_tab_recommendations",
            MusicDiscoverySection.FAVORITES to "music_tab_favorites",
            MusicDiscoverySection.RECENTLY_PLAYED to "music_tab_recent",
          )
        tabs.forEach { (section, tag) ->
          val selected = uiState.activeSection == section
          FilterChip(
            selected = selected,
            onClick = { viewModel.selectSection(section) },
            label = {
              val suffix =
                when (section) {
                  MusicDiscoverySection.FAVORITES ->
                    if (uiState.favoriteTracks.isNotEmpty()) " (${uiState.favoriteTracks.size})" else ""
                  MusicDiscoverySection.RECENTLY_PLAYED ->
                    if (uiState.recentTracks.isNotEmpty()) " (${uiState.recentTracks.size})" else ""
                  else -> ""
                }
              Text("${section.label}$suffix")
            },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = VynozaVioletContainer,
                selectedLabelColor = VynozaLavenderSoft,
                containerColor = VynozaSurfaceDeep,
                labelColor = VynozaTextSecondary,
              ),
            border =
              FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = VynozaBorderSubtle,
                selectedBorderColor = VynozaPurplePrimary,
              ),
            modifier = Modifier.testTag(tag),
          )
        }
      }

      // Genre Chips for Trending
      if (uiState.activeSection == MusicDiscoverySection.TRENDING) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          AudiusDiscoveryCategory.entries.forEach { category ->
            val selected = uiState.selectedCategory == category
            FilterChip(
              selected = selected,
              onClick = { viewModel.selectCategory(category) },
              label = { Text(category.label) },
              colors =
                FilterChipDefaults.filterChipColors(
                  selectedContainerColor = VynozaPurplePrimary.copy(alpha = 0.25f),
                  selectedLabelColor = Color.White,
                  containerColor = VynozaSurfaceElevated,
                  labelColor = VynozaTextSecondary,
                ),
              modifier = Modifier.testTag("music_genre_${category.name.lowercase()}"),
            )
          }
        }
      }

      if (uiState.activeSection == MusicDiscoverySection.RECENTLY_PLAYED && uiState.recentTracks.isNotEmpty()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(
            onClick = viewModel::clearRecentlyPlayed,
            modifier = Modifier.testTag("music_clear_recent_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.DeleteSweep,
              contentDescription = null,
              tint = VynozaTextSecondary,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Clear History", color = VynozaTextSecondary)
          }
        }
      }

      val inlineError = uiState.selectionError ?: uiState.playback.errorMessage
      if (inlineError != null && uiState.trimSelection == null) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = VynozaErrorContainer,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 6.dp)
              .testTag("music_error_banner"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = inlineError,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::dismissSelectionError) {
              Text(stringResource(R.string.dismiss))
            }
          }
        }
      }

      // Main Track List Content
      Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        when (val state = uiState.displayedTracksState) {
          UiState.Loading -> {
            Box(
              modifier = Modifier.fillMaxSize().testTag("music_loading_indicator"),
              contentAlignment = Alignment.Center,
            ) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                CircularProgressIndicator(color = VynozaPurplePrimary)
                Text(
                  text = "Fetching real tracks from Audius...",
                  style = MaterialTheme.typography.bodyMedium,
                  color = VynozaTextSecondary,
                )
              }
            }
          }

          is UiState.Error -> {
            Box(
              modifier = Modifier.fillMaxSize().padding(20.dp).testTag("music_error_state"),
              contentAlignment = Alignment.Center,
            ) {
              VynozaErrorCard(
                message = state.message,
                onRetry = viewModel::retry,
              )
            }
          }

          is UiState.Success -> {
            val tracks = state.data
            if (tracks.isEmpty()) {
              Box(
                modifier = Modifier.fillMaxSize().padding(20.dp).testTag("music_empty_state"),
                contentAlignment = Alignment.Center,
              ) {
                val (icon, title, subtitle) =
                  when (uiState.activeSection) {
                    MusicDiscoverySection.FAVORITES ->
                      Triple(
                        Icons.Rounded.FavoriteBorder,
                        "No favorite songs yet",
                        "Tap the heart icon on any Audius track to save it here for quick studio access.",
                      )
                    MusicDiscoverySection.RECENTLY_PLAYED ->
                      Triple(
                        Icons.Rounded.History,
                        "No recently played tracks",
                        "Preview or trim any song from Trending or Search and it will appear here.",
                      )
                    MusicDiscoverySection.SEARCH ->
                      Triple(
                        Icons.Rounded.Search,
                        "No tracks match \"${uiState.searchQuery.trim()}\"",
                        "Try another artist, song title, or mood keyword from Audius.",
                      )
                    else ->
                      Triple(
                        Icons.Rounded.MusicNote,
                        "No streamable tracks in this view",
                        "Switch genres or search Audius to find open-streaming creator music.",
                      )
                  }
                VynozaEmptyStateCard(
                  icon = icon,
                  title = title,
                  subtitle = subtitle,
                  actionLabel = if (uiState.activeSection == MusicDiscoverySection.SEARCH) "Browse Trending" else null,
                  onActionClick =
                    if (uiState.activeSection == MusicDiscoverySection.SEARCH) {
                      { viewModel.clearSearch() }
                    } else {
                      null
                    },
                )
              }
            } else {
              LazyColumn(
                contentPadding =
                  PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = if (uiState.trimSelection != null) 340.dp else 24.dp,
                  ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().navigationBarsPadding().testTag("music_tracks_list"),
              ) {
                items(tracks, key = { it.id }) { track ->
                  MusicTrackRow(
                    track = track,
                    isFavorite = track.id in uiState.favoriteIds,
                    playback = uiState.playback,
                    onTogglePreview = { viewModel.onTogglePreview(track) },
                    onToggleFavorite = { viewModel.onToggleFavorite(track) },
                    onOpenTrimmer = { viewModel.openTrimmer(track) },
                    onOpenLicense = { viewModel.openLicenseInfo(track) },
                  )
                }
              }
            }
          }
        }
      }
    }

    // Bottom Waveform Trimmer Sheet Overlay
    val activeTrim = uiState.trimSelection
    if (activeTrim != null) {
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(VynozaObsidian.copy(alpha = 0.56f))
            .clickable(onClick = viewModel::dismissTrimmer),
      )
      MusicWaveformTrimmerSheet(
        selection = activeTrim,
        playback = uiState.playback,
        isSamplingWaveform = uiState.isSamplingWaveform,
        isWaveformRealDecoded = uiState.isWaveformRealDecoded,
        isVerifyingSelection = uiState.isVerifyingSelection,
        errorMessage = uiState.selectionError ?: uiState.playback.errorMessage,
        onTogglePlayback = viewModel::toggleTrimPreviewPlayback,
        onStartChanged = viewModel::updateTrimStart,
        onDurationChanged = viewModel::updateTrimDuration,
        onSeekPreview = viewModel::seekTrimPreview,
        onConfirm = { viewModel.confirmTrimSelection(onSoundSelected) },
        onDismiss = viewModel::dismissTrimmer,
        modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 720.dp),
      )
    }
  }

  // Artist Attribution & Licensing Detail Dialog
  val licenseTrack = uiState.licenseDialogTrack
  if (licenseTrack != null) {
    val decision = com.vynoza.app.data.music.AudiusLicenseEvaluator.evaluate(licenseTrack)
    AlertDialog(
      onDismissRequest = viewModel::dismissLicenseInfo,
      containerColor = VynozaSurfaceElevated,
      title = {
        Text(
          text = "Track Licensing & Attribution",
          style = MaterialTheme.typography.titleMedium,
        )
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = licenseTrack.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "Artist: ${licenseTrack.artistName} (@${licenseTrack.artistHandle})",
            style = MaterialTheme.typography.bodyMedium,
            color = VynozaTextSecondary,
          )
          Text(
            text = "License: ${licenseTrack.license} (${decision.badgeLabel})",
            style = MaterialTheme.typography.bodyMedium,
            color = VynozaLavenderSoft,
          )
          Text(
            text = decision.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = VynozaTextSecondary,
          )
        }
      },
      confirmButton = {
        Button(onClick = viewModel::dismissLicenseInfo) {
          Text("Got it")
        }
      },
    )
  }
}

@Composable
private fun MusicTrackRow(
  track: MusicTrack,
  isFavorite: Boolean,
  playback: AudioPreviewPlaybackState,
  onTogglePreview: () -> Unit,
  onToggleFavorite: () -> Unit,
  onOpenTrimmer: () -> Unit,
  onOpenLicense: () -> Unit,
) {
  val isCurrent = playback.activeTrackId == track.id
  val isPlayingThis = isCurrent && playback.isPlaying
  val isBufferingThis = isCurrent && playback.isBuffering
  val licenseDecision = com.vynoza.app.data.music.AudiusLicenseEvaluator.evaluate(track)

  Surface(
    shape = RoundedCornerShape(18.dp),
    color = if (isCurrent) VynozaSurfaceHigh else VynozaSurfaceElevated,
    border =
      BorderStroke(
        width = 1.dp,
        color = if (isCurrent) VynozaPurplePrimary else VynozaBorderSubtle,
      ),
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(enabled = track.isStreamable, onClick = onOpenTrimmer)
        .testTag("music_track_card_${track.id}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Artwork + Preview Play Overlay
      Box(
        modifier =
          Modifier
            .size(62.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(VynozaSurfaceDeep)
            .clickable(enabled = track.isStreamable, onClick = onTogglePreview)
            .testTag("music_play_button_${track.id}"),
        contentAlignment = Alignment.Center,
      ) {
        if (!track.artworkUrl.isNullOrBlank()) {
          AsyncImage(
            model = track.artworkUrl,
            contentDescription = "${track.title} cover art",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = VynozaLavenderSoft,
            modifier = Modifier.size(26.dp),
          )
        }

        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .background(VynozaObsidian.copy(alpha = if (isCurrent) 0.45f else 0.30f)),
          contentAlignment = Alignment.Center,
        ) {
          if (isBufferingThis) {
            CircularProgressIndicator(
              color = Color.White,
              strokeWidth = 2.dp,
              modifier = Modifier.size(22.dp),
            )
          } else {
            Surface(
              shape = CircleShape,
              color = VynozaObsidian.copy(alpha = 0.72f),
              modifier = Modifier.size(30.dp),
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = if (isPlayingThis) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                  contentDescription = if (isPlayingThis) "Pause ${track.title}" else "Preview ${track.title}",
                  tint = Color.White,
                  modifier = Modifier.size(18.dp),
                )
              }
            }
          }
        }
      }

      // Title, Artist, Duration & License Badge
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Text(
          text = track.title,
          style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${track.artistName} • ${formatDurationMs(track.durationMs)}",
          style = MaterialTheme.typography.bodySmall,
          color = VynozaTextSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          if (track.genre.isNotBlank()) {
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = VynozaSurfaceDeep,
            ) {
              Text(
                text = track.genre,
                style = MaterialTheme.typography.labelSmall,
                color = VynozaLavenderSoft,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
          }
          Surface(
            onClick = onOpenLicense,
            shape = RoundedCornerShape(6.dp),
            color = VynozaSurfaceDeep,
            modifier = Modifier.testTag("music_license_badge_${track.id}"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = "Licensing info for ${track.title}",
                tint = if (licenseDecision.canEmbed) VynozaLavenderSoft else VynozaTextMuted,
                modifier = Modifier.size(11.dp),
              )
              Text(
                text = if (licenseDecision.canEmbed) "${track.license} • Mix OK" else "${track.license} • Preview",
                style = MaterialTheme.typography.labelSmall,
                color = if (licenseDecision.canEmbed) VynozaLavenderSoft else VynozaTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }

      // Favorite Heart Button
      IconButton(
        onClick = onToggleFavorite,
        modifier = Modifier.size(48.dp).testTag("music_favorite_button_${track.id}"),
      ) {
        Icon(
          imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
          contentDescription = if (isFavorite) "Remove ${track.title} from favorites" else "Save ${track.title} to favorites",
          tint = if (isFavorite) VynozaRoseAccent else VynozaTextSecondary,
        )
      }

      // Trim & Select CTA
      OutlinedButton(
        onClick = onOpenTrimmer,
        enabled = track.isStreamable,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, VynozaPurplePrimary),
        colors =
          ButtonDefaults.outlinedButtonColors(
            contentColor = VynozaLavenderSoft,
          ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = Modifier.height(40.dp).testTag("music_select_button_${track.id}"),
      ) {
        Icon(
          imageVector = Icons.Rounded.ContentCut,
          contentDescription = null,
          modifier = Modifier.size(15.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = "Use", style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}
