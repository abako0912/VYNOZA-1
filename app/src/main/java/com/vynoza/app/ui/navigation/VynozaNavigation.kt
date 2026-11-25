package com.vynoza.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vynoza.app.R
import com.vynoza.app.data.repository.NotificationRepository
import com.vynoza.app.data.repository.UserRepository
import com.vynoza.app.data.repository.VideoRepository
import com.vynoza.app.data.service.MediaStorageService
import com.vynoza.app.data.service.VynozaAuthService
import com.vynoza.app.ui.auth.AuthScreen
import com.vynoza.app.ui.auth.AuthViewModel
import com.vynoza.app.ui.create.CreateScreen
import com.vynoza.app.ui.create.CreateViewModel
import com.vynoza.app.ui.discover.DiscoverScreen
import com.vynoza.app.ui.discover.DiscoverViewModel
import com.vynoza.app.ui.home.HomeScreen
import com.vynoza.app.ui.home.HomeViewModel
import com.vynoza.app.ui.notifications.NotificationsScreen
import com.vynoza.app.ui.notifications.NotificationsViewModel
import com.vynoza.app.ui.profile.ProfileScreen
import com.vynoza.app.ui.profile.ProfileViewModel
import com.vynoza.app.ui.profile.PublicProfileScreen
import com.vynoza.app.ui.profile.PublicProfileViewModel
import android.net.Uri
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vynoza.app.data.service.ClipSource
import com.vynoza.app.ui.templates.TemplatesStudioScreen
import com.vynoza.app.ui.templates.TemplatesStudioViewModel
import com.vynoza.app.ui.theme.VynozaLavenderSoft
import com.vynoza.app.ui.theme.VynozaObsidian
import com.vynoza.app.ui.theme.VynozaSurfaceDeep
import com.vynoza.app.ui.theme.VynozaVioletContainer
import com.vynoza.app.ui.update.AppUpdateDialogHost
import com.vynoza.app.ui.update.AppUpdateViewModel

private const val PUBLIC_PROFILE_ROUTE = "public_profile/{userId}"
private const val TEMPLATES_STUDIO_ROUTE = "templates_studio"

enum class VynozaDestination(
  val route: String,
  @StringRes val labelRes: Int,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
) {
  HOME("home", R.string.nav_home, Icons.Rounded.Home, Icons.Outlined.Home, "nav_home"),
  DISCOVER("discover", R.string.nav_discover, Icons.Rounded.Explore, Icons.Outlined.Explore, "nav_discover"),
  CREATE("create", R.string.nav_create, Icons.Rounded.AddBox, Icons.Outlined.AddBox, "nav_create"),
  NOTIFICATIONS(
    "notifications",
    R.string.nav_notifications,
    Icons.Rounded.Notifications,
    Icons.Outlined.Notifications,
    "nav_notifications",
  ),
  PROFILE("profile", R.string.nav_profile, Icons.Rounded.Person, Icons.Outlined.Person, "nav_profile"),
}

@Composable
fun VynozaAppRoot(
  authService: VynozaAuthService,
  userRepository: UserRepository,
  videoRepository: VideoRepository,
  notificationRepository: NotificationRepository,
  mediaStorageService: MediaStorageService,
  deepLinkedVideoId: String? = null,
  onDeepLinkConsumed: () -> Unit = {},
  deepLinkedUserId: String? = null,
  onUserDeepLinkConsumed: () -> Unit = {},
) {
  val currentUser by
    authService.authStateFlow().collectAsStateWithLifecycle(initialValue = authService.currentUser)

  val user = currentUser
  if (user == null) {
    val authVm: AuthViewModel =
      viewModel(
        key = "auth_vm",
        factory = viewModelFactory {
          initializer { AuthViewModel(authService) }
        },
      )
    AuthScreen(
      viewModel = authVm,
      onAuthSuccess = { /* authStateFlow updates reactively */ },
    )
  } else {
    key(user.uid) {
      LaunchedEffect(user.uid) {
        val resolvedDisplayName =
          user.displayName?.takeIf { it.isNotBlank() } ?: authService.consumePendingDisplayName()
        userRepository.ensureUserInitialized(
          userId = user.uid,
          email = user.email,
          displayName = resolvedDisplayName,
          avatarUrl = user.photoUrl?.toString(),
        )
      }

      VynozaMainShell(
        currentUserId = user.uid,
        authService = authService,
        userRepository = userRepository,
        videoRepository = videoRepository,
        notificationRepository = notificationRepository,
        mediaStorageService = mediaStorageService,
        deepLinkedVideoId = deepLinkedVideoId,
        onDeepLinkConsumed = onDeepLinkConsumed,
        deepLinkedUserId = deepLinkedUserId,
        onUserDeepLinkConsumed = onUserDeepLinkConsumed,
      )
    }
  }
}

@Composable
private fun VynozaMainShell(
  currentUserId: String,
  authService: VynozaAuthService,
  userRepository: UserRepository,
  videoRepository: VideoRepository,
  notificationRepository: NotificationRepository,
  mediaStorageService: MediaStorageService,
  deepLinkedVideoId: String? = null,
  onDeepLinkConsumed: () -> Unit = {},
  deepLinkedUserId: String? = null,
  onUserDeepLinkConsumed: () -> Unit = {},
) {
  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = navBackStackEntry?.destination?.route
  var pendingTemplateVideoUri by remember { mutableStateOf<Uri?>(null) }

  fun navigateTo(route: String) {
    navController.navigate(route) {
      popUpTo(navController.graph.findStartDestination().id) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  fun openCreatorProfile(targetUserId: String) {
    val cleanId = targetUserId.trim()
    if (cleanId.isEmpty()) return
    if (cleanId == currentUserId) {
      navigateTo(VynozaDestination.PROFILE.route)
      return
    }
    navController.navigate("public_profile/$cleanId") {
      launchSingleTop = true
    }
  }

  LaunchedEffect(deepLinkedUserId) {
    val userId = deepLinkedUserId?.takeIf { it.isNotBlank() }
    if (userId != null) {
      openCreatorProfile(userId)
      onUserDeepLinkConsumed()
    }
  }

  val appContext = LocalContext.current
  val appUpdateVm: AppUpdateViewModel =
    viewModel(
      key = "app_update_$currentUserId",
      factory = viewModelFactory {
        initializer { AppUpdateViewModel() }
      },
    )
  val appUpdateState by appUpdateVm.uiState.collectAsStateWithLifecycle()
  val isUpdateDialogVisible by appUpdateVm.isDialogVisible.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) {
    appUpdateVm.checkForUpdates(appContext, force = false)
  }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, appContext) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          appUpdateVm.onAppResumed(appContext)
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  AppUpdateDialogHost(
    uiState = appUpdateState,
    isDialogVisible = isUpdateDialogVisible,
    currentVersionName = appUpdateVm.currentVersionName,
    currentVersionCode = appUpdateVm.currentVersionCode,
    onUpdateNow = { release -> appUpdateVm.startDownloadAndInstall(appContext, release) },
    onCancelDownload = appUpdateVm::cancelDownload,
    onDismiss = { appUpdateVm.dismissUpdateDialog(appContext) },
    onAllowUnknownSources = { appUpdateVm.requestInstallPermission(appContext) },
    onOpenInstaller = { appUpdateVm.launchInstaller(appContext) },
    onRetryCheck = { appUpdateVm.checkForUpdates(appContext, force = true) },
  )

  BoxWithConstraints(
    modifier = Modifier.fillMaxSize().background(VynozaObsidian)
  ) {
    val useNavigationRail = maxWidth >= 600.dp
    val isFullScreenRoute = currentRoute == TEMPLATES_STUDIO_ROUTE

    Scaffold(
      containerColor = VynozaObsidian,
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      bottomBar = {
        if (!useNavigationRail && !isFullScreenRoute) {
          NavigationBar(
            containerColor = VynozaSurfaceDeep,
            windowInsets = WindowInsets.navigationBars,
            tonalElevation = 0.dp,
          ) {
            VynozaDestination.entries.forEach { dest ->
              val selected = currentRoute == dest.route
              NavigationBarItem(
                selected = selected,
                onClick = { navigateTo(dest.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                    contentDescription = stringResource(dest.labelRes),
                  )
                },
                label = {
                  Text(
                    text = stringResource(dest.labelRes),
                    style = MaterialTheme.typography.labelSmall,
                  )
                },
                colors = NavigationBarItemDefaults.colors(
                  selectedIconColor = VynozaLavenderSoft,
                  selectedTextColor = VynozaLavenderSoft,
                  indicatorColor = VynozaVioletContainer,
                  unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                  unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.testTag(dest.testTag),
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        if (useNavigationRail && !isFullScreenRoute) {
          NavigationRail(
            containerColor = VynozaSurfaceDeep,
            modifier = Modifier.fillMaxHeight(),
          ) {
            VynozaDestination.entries.forEach { dest ->
              val selected = currentRoute == dest.route
              NavigationRailItem(
                selected = selected,
                onClick = { navigateTo(dest.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                    contentDescription = stringResource(dest.labelRes),
                  )
                },
                label = { Text(stringResource(dest.labelRes)) },
                colors = NavigationRailItemDefaults.colors(
                  selectedIconColor = VynozaLavenderSoft,
                  selectedTextColor = VynozaLavenderSoft,
                  indicatorColor = VynozaVioletContainer,
                  unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                  unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.testTag(dest.testTag),
              )
            }
          }
        }

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
          NavHost(
            navController = navController,
            startDestination = VynozaDestination.HOME.route,
          ) {
            composable(VynozaDestination.HOME.route) {
              val homeVm: HomeViewModel =
                viewModel(
                  key = "home_$currentUserId",
                  factory = viewModelFactory {
                    initializer { HomeViewModel(videoRepository, userRepository, currentUserId) }
                  },
                )
              HomeScreen(
                viewModel = homeVm,
                isScreenVisible = currentRoute == VynozaDestination.HOME.route,
                targetVideoId = deepLinkedVideoId,
                onTargetVideoConsumed = onDeepLinkConsumed,
                onNavigateToCreate = { navigateTo(VynozaDestination.CREATE.route) },
                onOpenCreatorProfile = ::openCreatorProfile,
              )
            }

            composable(VynozaDestination.DISCOVER.route) {
              val discoverVm: DiscoverViewModel =
                viewModel(
                  key = "discover_$currentUserId",
                  factory = viewModelFactory {
                    initializer { DiscoverViewModel(userRepository, videoRepository, currentUserId) }
                  },
                )
              DiscoverScreen(
                viewModel = discoverVm,
                onOpenCreatorProfile = ::openCreatorProfile,
              )
            }

            composable(VynozaDestination.CREATE.route) {
              val context = LocalContext.current
              val createVm: CreateViewModel =
                viewModel(
                  key = "create_$currentUserId",
                  factory = viewModelFactory {
                    initializer {
                      CreateViewModel(
                        mediaStorageService = mediaStorageService,
                        videoRepository = videoRepository,
                        userRepository = userRepository,
                        currentUserId = currentUserId,
                      )
                    }
                  },
                )
              LaunchedEffect(pendingTemplateVideoUri) {
                val uri = pendingTemplateVideoUri
                if (uri != null) {
                  createVm.onClipAcquired(context, uri, ClipSource.TEMPLATE_STUDIO)
                  pendingTemplateVideoUri = null
                }
              }
              CreateScreen(
                viewModel = createVm,
                onNavigateToHome = { navigateTo(VynozaDestination.HOME.route) },
                onOpenTemplatesStudio = {
                  navController.navigate(TEMPLATES_STUDIO_ROUTE) {
                    launchSingleTop = true
                  }
                },
              )
            }

            composable(TEMPLATES_STUDIO_ROUTE) {
              val studioVm: TemplatesStudioViewModel =
                viewModel(
                  key = "templates_studio_$currentUserId",
                  factory = viewModelFactory {
                    initializer { TemplatesStudioViewModel() }
                  },
                )
              TemplatesStudioScreen(
                viewModel = studioVm,
                onBackToCreate = { navController.popBackStack() },
                onExportedVideoReadyForPublish = { exportedUri ->
                  pendingTemplateVideoUri = exportedUri
                  navController.popBackStack()
                },
              )
            }

            composable(VynozaDestination.NOTIFICATIONS.route) {
              val notifVm: NotificationsViewModel =
                viewModel(
                  key = "notifications_$currentUserId",
                  factory = viewModelFactory {
                    initializer { NotificationsViewModel(notificationRepository, currentUserId) }
                  },
                )
              NotificationsScreen(
                viewModel = notifVm,
                onOpenCreatorProfile = ::openCreatorProfile,
              )
            }

            composable(VynozaDestination.PROFILE.route) {
              val profileVm: ProfileViewModel =
                viewModel(
                  key = "profile_$currentUserId",
                  factory = viewModelFactory {
                    initializer { ProfileViewModel(userRepository, videoRepository, currentUserId) }
                  },
                )
              ProfileScreen(
                viewModel = profileVm,
                onSignOut = { authService.signOut() },
                onOpenCreatorProfile = ::openCreatorProfile,
                appUpdateState = appUpdateState,
                currentAppVersionName = appUpdateVm.currentVersionName,
                currentAppVersionCode = appUpdateVm.currentVersionCode,
                onCheckForUpdates = { appUpdateVm.checkForUpdates(appContext, force = true) },
                onOpenUpdateDialog = appUpdateVm::openUpdateDialog,
              )
            }

            composable(
              route = PUBLIC_PROFILE_ROUTE,
              arguments = listOf(navArgument("userId") { type = NavType.StringType }),
            ) { entry ->
              val targetUserId = entry.arguments?.getString("userId").orEmpty()
              val publicVm: PublicProfileViewModel =
                viewModel(
                  key = "public_profile_${currentUserId}_$targetUserId",
                  factory = viewModelFactory {
                    initializer {
                      PublicProfileViewModel(
                        userRepository = userRepository,
                        videoRepository = videoRepository,
                        targetUserId = targetUserId,
                        currentUserId = currentUserId,
                      )
                    }
                  },
                )
              PublicProfileScreen(
                viewModel = publicVm,
                onBack = { navController.popBackStack() },
                onOpenCreatorProfile = ::openCreatorProfile,
              )
            }
          }
        }
      }
    }
  }
}
