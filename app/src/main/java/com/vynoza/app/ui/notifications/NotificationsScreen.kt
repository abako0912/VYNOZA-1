package com.vynoza.app.ui.notifications

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vynoza.app.R
import com.vynoza.app.core.model.NotificationType
import com.vynoza.app.core.model.UiState
import com.vynoza.app.core.model.VynozaNotification
import com.vynoza.app.ui.components.VynozaEmptyStateCard
import com.vynoza.app.ui.components.VynozaErrorCard
import com.vynoza.app.ui.theme.VynozaBorderSubtle
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaPurplePrimary
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaSurfaceElevated
import com.vynoza.app.ui.theme.VynozaVioletContainer

@Composable
fun NotificationsScreen(
  viewModel: NotificationsViewModel,
  onOpenCreatorProfile: (String) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val notificationsState by viewModel.notificationsState.collectAsStateWithLifecycle()
  val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(VynozaObsidian)
      .statusBarsPadding()
      .padding(horizontal = 20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
      Spacer(modifier = Modifier.height(16.dp))
      Text(
        text = stringResource(R.string.notifications_title),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
      )
      Spacer(modifier = Modifier.height(12.dp))
      Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        NotificationFilter.entries.forEach { filter ->
          FilterChip(
            selected = selectedFilter == filter,
            onClick = { viewModel.selectFilter(filter) },
            label = { Text(filter.label) },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = VynozaVioletContainer,
              selectedLabelColor = VynozaLavenderSoft,
              containerColor = VynozaSurfaceDeep,
              labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            border = FilterChipDefaults.filterChipBorder(
              enabled = true,
              selected = selectedFilter == filter,
              borderColor = VynozaBorderSubtle,
              selectedBorderColor = VynozaPurplePrimary,
            ),
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    when (val state = notificationsState) {
      UiState.Loading -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(color = VynozaPurplePrimary)
        }
      }

      is UiState.Error -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          VynozaErrorCard(
            message = state.message,
            onRetry = { viewModel.selectFilter(selectedFilter) },
          )
        }
      }

      is UiState.Success -> {
        if (state.data.isEmpty()) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            VynozaEmptyStateCard(
              icon = Icons.Rounded.Notifications,
              title = stringResource(R.string.notifications_empty_title),
              subtitle = stringResource(R.string.notifications_empty_subtitle),
            )
          }
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 640.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            items(state.data, key = { it.id }) { item ->
              NotificationRow(
                notification = item,
                onClick = {
                  if (!item.read) {
                    viewModel.markAsRead(item.id)
                  }
                  if (item.actorId.isNotBlank() && item.actorId != NotificationType.SYSTEM.wireValue) {
                    onOpenCreatorProfile(item.actorId)
                  }
                },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun NotificationRow(
  notification: VynozaNotification,
  onClick: () -> Unit,
) {
  val icon =
    when (NotificationType.fromWire(notification.type)) {
      NotificationType.LIKE -> Icons.Rounded.Favorite
      NotificationType.COMMENT -> Icons.AutoMirrored.Rounded.Comment
      NotificationType.FOLLOW -> Icons.Rounded.PersonAdd
      NotificationType.SYSTEM -> Icons.Rounded.Notifications
    }

  Surface(
    shape = RoundedCornerShape(18.dp),
    color = if (notification.read) VynozaSurfaceDeep else VynozaSurfaceElevated,
    border = BorderStroke(
      width = 1.dp,
      color = if (notification.read) VynozaBorderSubtle else VynozaPurplePrimary.copy(alpha = 0.65f),
    ),
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(18.dp))
      .clickable(onClick = onClick)
      .testTag("notification_item_${notification.id}"),
  ) {
    Row(
      modifier = Modifier.padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(42.dp)
          .clip(CircleShape)
          .background(VynozaVioletContainer),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = VynozaLavenderSoft,
          modifier = Modifier.size(20.dp),
        )
      }

      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = "@${notification.actorUsername}",
          style = MaterialTheme.typography.labelLarge,
          color = VynozaLavenderSoft,
          modifier = Modifier.testTag("notification_actor_${notification.id}"),
        )
        Text(
          text = notification.message,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }

      if (!notification.read) {
        Box(
          modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(VynozaPurplePrimary)
        )
      }
    }
  }
}
