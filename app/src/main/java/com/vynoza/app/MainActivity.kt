package com.vynoza.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.vynoza.app.data.repository.NotificationRepository
import com.vynoza.app.data.repository.UserRepository
import com.vynoza.app.data.repository.VideoRepository
import com.vynoza.app.data.service.CloudinaryMediaStorageService
import com.vynoza.app.data.service.FirebaseVynozaAuthService
import com.vynoza.app.ui.navigation.VynozaAppRoot
import com.vynoza.app.ui.theme.VynozaTheme

class MainActivity : ComponentActivity() {
  private var deepLinkedVideoId by mutableStateOf<String?>(null)
  private var deepLinkedUserId by mutableStateOf<String?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    deepLinkedVideoId = extractVideoIdFromIntent(intent)
    deepLinkedUserId = extractUserIdFromIntent(intent)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )

    if (FirebaseApp.getApps(this).isEmpty()) {
      FirebaseApp.initializeApp(this)
    }
    val databaseId = getString(R.string.firestore_database_id)
    val firestore = FirebaseFirestore.getInstance(databaseId)

    setContent {
      VynozaTheme {
        val authService = remember { FirebaseVynozaAuthService() }
        val userRepository = remember(firestore) { UserRepository(firestore) }
        val videoRepository = remember(firestore) { VideoRepository(firestore) }
        val notificationRepository = remember(firestore) { NotificationRepository(firestore) }
        val mediaStorageService = remember { CloudinaryMediaStorageService() }

        VynozaAppRoot(
          authService = authService,
          userRepository = userRepository,
          videoRepository = videoRepository,
          notificationRepository = notificationRepository,
          mediaStorageService = mediaStorageService,
          deepLinkedVideoId = deepLinkedVideoId,
          onDeepLinkConsumed = { deepLinkedVideoId = null },
          deepLinkedUserId = deepLinkedUserId,
          onUserDeepLinkConsumed = { deepLinkedUserId = null },
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    extractVideoIdFromIntent(intent)?.let { videoId ->
      deepLinkedVideoId = videoId
    }
    extractUserIdFromIntent(intent)?.let { userId ->
      deepLinkedUserId = userId
    }
  }

  companion object {
    internal fun extractVideoIdFromIntent(intent: Intent?): String? {
      val data = intent?.data ?: return null
      if (data.scheme != "vynoza" || data.host != "video") return null
      return data.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    internal fun extractUserIdFromIntent(intent: Intent?): String? {
      val data = intent?.data ?: return null
      if (data.scheme != "vynoza" || data.host != "user") return null
      return data.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
    }
  }
}
