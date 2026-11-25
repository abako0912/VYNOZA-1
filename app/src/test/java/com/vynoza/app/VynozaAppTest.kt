package com.vynoza.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseUser
import com.sun.net.httpserver.HttpServer
import com.vynoza.app.core.model.FollowEdge
import com.vynoza.app.core.model.FollowerEdge
import com.vynoza.app.core.model.NotificationType
import com.vynoza.app.core.model.UserPublicProfile
import com.vynoza.app.core.model.VideoComment
import com.vynoza.app.core.model.VideoPost
import com.vynoza.app.core.model.VynozaNotification
import com.vynoza.app.data.repository.UserRepository
import com.vynoza.app.data.repository.VynozaNotificationSource
import com.vynoza.app.data.repository.VynozaUserSource
import com.vynoza.app.data.repository.VynozaVideoSource
import com.vynoza.app.data.service.AuthOperationResult
import com.vynoza.app.data.service.ClipSource
import com.vynoza.app.data.service.ClipValidationResult
import com.vynoza.app.data.service.CloudinaryMediaStorageService
import com.vynoza.app.data.service.LocalVideoClip
import com.vynoza.app.data.service.MediaStorageService
import com.vynoza.app.data.service.VideoUploadResult
import com.vynoza.app.data.service.VynozaAuthService
import com.vynoza.app.ui.auth.AuthMode
import com.vynoza.app.ui.auth.AuthScreen
import com.vynoza.app.ui.auth.AuthViewModel
import com.vynoza.app.ui.components.buildProfileShareSendIntent
import com.vynoza.app.ui.components.buildProfileShareText
import com.vynoza.app.ui.components.buildVideoShareSendIntent
import com.vynoza.app.ui.components.buildVideoShareText
import com.vynoza.app.ui.components.formatCompactCount
import com.vynoza.app.ui.components.resolveCloudinaryThumbnailUrl
import com.vynoza.app.ui.components.resolveShareableProfileUrl
import com.vynoza.app.ui.components.resolveShareableVideoUrl
import com.vynoza.app.ui.create.CreateScreen
import com.vynoza.app.ui.create.CreateViewModel
import com.vynoza.app.ui.create.PublishPostState
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
import com.vynoza.app.ui.theme.VynozaTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VynozaAppTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun `applicationId, packageName, and firebase google-services resources match com vynoza app`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("com.vynoza.app", BuildConfig.APPLICATION_ID)
    assertEquals("com.vynoza.app", context.packageName)
    assertEquals("VYNOZA", context.getString(R.string.app_name))
    assertEquals(
      "ai-studio-android-vynoza-949a66ed-fd5d-471c-842b-9e4334b3819d",
      context.getString(R.string.firestore_database_id),
    )

    val projectIdRes = context.resources.getIdentifier("project_id", "string", context.packageName)
    val googleAppIdRes =
      context.resources.getIdentifier("google_app_id", "string", context.packageName)
    assertTrue("google-services project_id resource missing", projectIdRes != 0)
    assertTrue("google-services google_app_id resource missing", googleAppIdRes != 0)
    assertEquals("gen-lang-client-0304923032", context.getString(projectIdRes))
    assertEquals("1:793715939776:android:217cf65f129f9b9eaa5025", context.getString(googleAppIdRes))
  }

  @Test
  fun `main activity starts cleanly and initializes firebase without crashing`() {
    val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    val activity = controller.get()
    assertFalse(activity.isFinishing)
    assertTrue(FirebaseApp.getApps(activity).isNotEmpty())
    assertEquals("gen-lang-client-0304923032", FirebaseApp.getInstance().options.projectId)
    assertEquals(
      "1:793715939776:android:217cf65f129f9b9eaa5025",
      FirebaseApp.getInstance().options.applicationId,
    )
    controller.pause().stop().destroy()
  }

  @Test
  fun `deriveValidUsername always satisfies firestore security rules regex and bounds`() {
    val usernameRegex = Regex("^[a-z0-9_.]+$")
    val samples =
      listOf(
        null to "ABC123DEF",
        "" to "u1",
        "Abubakar Bako!" to "xyz987654321",
        "VeryLongCreatorHandleThatExceedsThirtyCharacters" to "uid001122",
      )
    for ((displayName, uid) in samples) {
      val derived = UserRepository.deriveValidUsername(displayName, uid)
      assertTrue("Length out of bounds: $derived", derived.length in 3..30)
      assertTrue("Invalid characters: $derived", usernameRegex.matches(derived))
    }
  }

  @Test
  fun `notification type safely falls back to system on unknown wire value`() {
    assertEquals(NotificationType.LIKE, NotificationType.fromWire("like"))
    assertEquals(NotificationType.FOLLOW, NotificationType.fromWire("follow"))
    assertEquals(NotificationType.SYSTEM, NotificationType.fromWire("unknown_future_type"))
    assertEquals(NotificationType.SYSTEM, NotificationType.fromWire(null))
  }

  @Test
  fun `cloudinary config constants and response parser handle success and failure payloads`() {
    assertEquals("idkq3e9l", CloudinaryMediaStorageService.CLOUDINARY_CLOUD_NAME)
    assertEquals("vynoza_videos", CloudinaryMediaStorageService.CLOUDINARY_UPLOAD_PRESET)
    assertEquals("vynoza/videos", CloudinaryMediaStorageService.CLOUDINARY_UPLOAD_FOLDER)
    assertEquals(
      "https://api.cloudinary.com/v1_1/idkq3e9l/video/upload",
      CloudinaryMediaStorageService.CLOUDINARY_VIDEO_UPLOAD_ENDPOINT,
    )

    val ok =
      CloudinaryMediaStorageService.parseCloudinaryResponse(
        200,
        """{"secure_url":"https://res.cloudinary.com/idkq3e9l/video/upload/v1/demo.mp4","public_id":"vynoza/demo"}""",
      )
    assertTrue(ok is VideoUploadResult.Success)
    ok as VideoUploadResult.Success
    assertEquals("https://res.cloudinary.com/idkq3e9l/video/upload/v1/demo.mp4", ok.secureUrl)
    assertEquals("vynoza/demo", ok.publicId)

    val missingUrl =
      CloudinaryMediaStorageService.parseCloudinaryResponse(
        200,
        """{"public_id":"vynoza/demo"}""",
      )
    assertTrue(missingUrl is VideoUploadResult.Error)

    val presetError =
      CloudinaryMediaStorageService.parseCloudinaryResponse(
        400,
        """{"error":{"message":"Upload preset must be whitelisted for unsigned uploads"}}""",
      )
    assertTrue(presetError is VideoUploadResult.Error)
    assertTrue((presetError as VideoUploadResult.Error).message.contains("Upload preset"))

    val unknownKeyError =
      CloudinaryMediaStorageService.parseCloudinaryResponse(
        401,
        """{"error":{"message":"Unknown API key "}}""",
      )
    assertTrue(unknownKeyError is VideoUploadResult.Error)
    val unknownKeyMsg = (unknownKeyError as VideoUploadResult.Error).message
    assertTrue(unknownKeyMsg.contains("Unknown API key"))
    assertTrue(unknownKeyMsg.contains("idkq3e9l"))
  }

  @Test
  fun `cloudinary streaming upload sends unsigned upload_preset and video bytes over http`() =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val tempVideo = File(context.cacheDir, "sample_upload.mp4").apply {
        writeBytes(ByteArray(32 * 1024) { 0x42 })
      }
      val videoUri = Uri.fromFile(tempVideo)

      var capturedBody = ""
      var capturedAuthHeader: String? = null
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/v1_1/idkq3e9l/video/upload") { exchange ->
        capturedAuthHeader = exchange.requestHeaders.getFirst("Authorization")
        capturedBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val responseJson =
          """{"secure_url":"https://res.cloudinary.com/idkq3e9l/video/upload/v123/sample_upload.mp4","public_id":"sample_upload"}"""
        val bytes = responseJson.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      server.start()

      try {
        val service =
          CloudinaryMediaStorageService(
            uploadEndpoint = "http://127.0.0.1:${server.address.port}/v1_1/idkq3e9l/video/upload"
          )
        val clip = service.inspectLocalClip(context, videoUri, ClipSource.CAMERA_CAPTURE)
        assertTrue(service.validateClipForUpload(context, clip) is ClipValidationResult.Valid)

        val progressEvents = mutableListOf<Float?>()
        val result = service.uploadVideo(context, clip) { progressEvents.add(it) }
        assertTrue(result is VideoUploadResult.Success)
        result as VideoUploadResult.Success
        assertEquals(
          "https://res.cloudinary.com/idkq3e9l/video/upload/v123/sample_upload.mp4",
          result.secureUrl,
        )
        assertEquals("sample_upload", result.publicId)
        assertEquals(null, capturedAuthHeader)
        assertTrue(capturedBody.contains("name=\"upload_preset\""))
        assertTrue(capturedBody.contains("vynoza_videos"))
        assertTrue(capturedBody.contains("name=\"folder\""))
        assertTrue(capturedBody.contains("vynoza/videos"))
        assertFalse(capturedBody.contains("name=\"api_key\""))
        assertFalse(capturedBody.contains("name=\"signature\""))
        assertFalse(capturedBody.contains("name=\"timestamp\""))
        assertTrue(progressEvents.any { it == 1f })
      } finally {
        server.stop(0)
        tempVideo.delete()
      }
    }

  @Test
  fun `unsigned upload request only includes upload_preset, folder, and file without api_key or signature`() {
    val service = CloudinaryMediaStorageService()
    val dummyBody = ByteArray(16).toRequestBody(null)
    val request = service.buildUnsignedUploadRequest(fileName = "clip.mp4", fileBody = dummyBody)

    assertEquals("https://api.cloudinary.com/v1_1/idkq3e9l/video/upload", request.url.toString())
    assertEquals(null, request.header("Authorization"))

    val multipart = request.body as okhttp3.MultipartBody
    val partDispositions =
      multipart.parts.mapNotNull { it.headers?.get("Content-Disposition") }
    assertEquals(3, partDispositions.size)
    assertTrue(partDispositions.any { it.contains("name=\"upload_preset\"") })
    assertTrue(partDispositions.any { it.contains("name=\"folder\"") })
    assertTrue(partDispositions.any { it.contains("name=\"file\"") })
    assertFalse(partDispositions.any { it.contains("api_key") })
    assertFalse(partDispositions.any { it.contains("signature") })
    assertFalse(partDispositions.any { it.contains("timestamp") })

    val wireBuffer = okio.Buffer()
    multipart.writeTo(wireBuffer)
    val wireText = wireBuffer.readUtf8()
    assertTrue(wireText.contains("vynoza_videos"))
    assertTrue(wireText.contains("vynoza/videos"))
    assertFalse(wireText.contains("ygsfys4"))
    assertFalse(wireText.contains("api_key"))
    assertFalse(wireText.contains("signature"))
  }

  @Test
  fun `cloudinary validation rejects non-video files and empty files before upload`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val service = CloudinaryMediaStorageService()

    val imageClip =
      LocalVideoClip(
        uri = Uri.parse("file:///nonexistent/photo.jpg"),
        displayName = "photo.jpg",
        sizeBytes = 1024L,
        mimeType = "image/jpeg",
        source = ClipSource.GALLERY_PICKER,
      )
    val invalidMime = service.validateClipForUpload(context, imageClip)
    assertTrue(invalidMime is ClipValidationResult.Invalid)

    val emptyVideo = File(context.cacheDir, "empty_clip.mp4").apply { writeBytes(ByteArray(0)) }
    try {
      val emptyClip = service.inspectLocalClip(context, Uri.fromFile(emptyVideo), ClipSource.GALLERY_PICKER)
      val invalidEmpty = service.validateClipForUpload(context, emptyClip)
      assertTrue(invalidEmpty is ClipValidationResult.Invalid)
    } finally {
      emptyVideo.delete()
    }
  }

  @Test
  fun `auth screen validates and executes registration, login, password reset, and logout state`() {
    var signInEmail: String? = null
    var createdEmail: String? = null
    var createdDisplayName: String? = null
    var resetCalledWith: String? = null
    var signOutCount = 0

    val fakeAuth =
      object : VynozaAuthService {
        private val state = MutableStateFlow<FirebaseUser?>(null)
        override val currentUser: FirebaseUser?
          get() = state.value

        override fun authStateFlow(): Flow<FirebaseUser?> = state.asStateFlow()

        override suspend fun signInWithEmailAndPassword(
          email: String,
          password: String,
        ): AuthOperationResult {
          signInEmail = email
          return AuthOperationResult.Success
        }

        override suspend fun createAccountWithEmailAndPassword(
          email: String,
          password: String,
          displayName: String,
        ): AuthOperationResult {
          createdEmail = email
          createdDisplayName = displayName
          return AuthOperationResult.Success
        }

        override suspend fun sendPasswordResetEmail(email: String): AuthOperationResult {
          resetCalledWith = email
          return AuthOperationResult.Success
        }

        override fun signOut() {
          signOutCount += 1
          state.value = null
        }
      }

    val vm = AuthViewModel(fakeAuth)
    composeTestRule.setContent {
      VynozaTheme {
        AuthScreen(viewModel = vm, onAuthSuccess = {})
      }
    }
    composeTestRule.waitForIdle()

    // 1. Login validation & submission
    composeTestRule.onNodeWithText("VYNOZA").assertIsDisplayed()
    composeTestRule.onNodeWithTag("auth_email_input").performScrollTo().performTextInput("creator@vynoza.app")
    composeTestRule.onNodeWithTag("auth_password_input").performScrollTo().performTextInput("123")
    composeTestRule.onNodeWithTag("auth_submit_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Password must be at least 6 characters.").assertIsDisplayed()

    composeTestRule.onNodeWithTag("auth_password_input").performScrollTo().performTextInput("456")
    composeTestRule.onNodeWithTag("auth_submit_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals("creator@vynoza.app", signInEmail)
    assertEquals("", vm.uiState.value.password)

    // 2. Registration validation & submission
    composeTestRule.onNodeWithTag("auth_mode_create_tab").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("auth_display_name_input").performScrollTo().performTextInput("Vynoza Creator")
    composeTestRule.onNodeWithTag("auth_password_input").performScrollTo().performTextInput("secret123")
    composeTestRule.onNodeWithTag("auth_confirm_password_input").performScrollTo().performTextInput("mismatch")
    composeTestRule.onNodeWithTag("auth_submit_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Passwords do not match.").assertIsDisplayed()

    composeTestRule.onNodeWithTag("auth_confirm_password_input").performScrollTo()
    vm.onConfirmPasswordChanged("secret123")
    composeTestRule.onNodeWithTag("auth_submit_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals("creator@vynoza.app", createdEmail)
    assertEquals("Vynoza Creator", createdDisplayName)
    assertEquals(AuthMode.SIGN_IN, vm.uiState.value.mode)
    assertEquals("", vm.uiState.value.password)

    // 3. Password reset flow
    composeTestRule.onNodeWithTag("auth_forgot_password_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("auth_submit_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals("creator@vynoza.app", resetCalledWith)
    composeTestRule
      .onNodeWithText("Password reset link sent to creator@vynoza.app. Check your inbox.")
      .assertIsDisplayed()

    // 4. Logout clears auth state cleanly
    fakeAuth.signOut()
    assertEquals(1, signOutCount)
    assertEquals(null, fakeAuth.currentUser)
    composeTestRule.onNodeWithTag("auth_email_input").performScrollTo().assertTextContains("creator@vynoza.app")
  }

  @Test
  fun `create screen uploads only on publish, prevents duplicates, and gates success on firestore save`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val uploadGate = CompletableDeferred<VideoUploadResult>()
    var uploadCalls = 0
    var firestoreShouldFail = true
    var firestorePublishCalls = 0

    val fakeStorage =
      object : MediaStorageService {
        override fun inspectLocalClip(context: Context, uri: Uri, source: ClipSource) =
          LocalVideoClip(uri, "sample.mp4", 2048L, "video/mp4", source)

        override fun validateClipForUpload(context: Context, clip: LocalVideoClip) =
          ClipValidationResult.Valid("video/mp4", 2048L)

        override suspend fun uploadVideo(
          context: Context,
          clip: LocalVideoClip,
          onProgress: (Float?) -> Unit,
        ): VideoUploadResult {
          uploadCalls += 1
          onProgress(0.5f)
          return uploadGate.await()
        }
      }

    val vm =
      CreateViewModel(
        mediaStorageService = fakeStorage,
        currentUserId = "test_uid",
        postPublisher = { _, _, _, _ ->
          firestorePublishCalls += 1
          if (firestoreShouldFail) {
            Result.failure(RuntimeException("Firestore unavailable"))
          } else {
            Result.success("vid_123")
          }
        },
      )

    composeTestRule.setContent {
      VynozaTheme { CreateScreen(viewModel = vm) }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("create_record_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("create_gallery_button").assertIsDisplayed()
    composeTestRule.onNodeWithText("Cloudinary Video Upload").performScrollTo().assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("create_cloudinary_target")
      .performScrollTo()
      .assertTextContains("idkq3e9l", substring = true)
    composeTestRule
      .onNodeWithText("Firebase Storage is not provisioned", substring = true)
      .assertDoesNotExist()

    // Selecting clip must NOT trigger automatic upload
    vm.onClipAcquired(context, Uri.parse("content://media/external/video/media/42"), ClipSource.GALLERY_PICKER)
    composeTestRule.waitForIdle()
    assertEquals(0, uploadCalls)

    composeTestRule.onNodeWithTag("create_caption_input").performScrollTo().performTextInput("First upload")
    composeTestRule.onNodeWithTag("create_publish_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    // Duplicate click while uploading must be ignored
    vm.publishSelectedClip(context)
    assertEquals(1, uploadCalls)
    assertTrue(vm.uiState.value.publishState is PublishPostState.Uploading)

    // Complete upload, simulate Firestore save error -> must show error, NOT success
    uploadGate.complete(
      VideoUploadResult.Success(
        secureUrl = "https://res.cloudinary.com/idkq3e9l/video/upload/v1/sample.mp4",
        publicId = "sample",
      )
    )
    composeTestRule.waitForIdle()
    assertEquals(1, firestorePublishCalls)
    assertTrue(vm.uiState.value.publishState is PublishPostState.Error)
    composeTestRule.onNodeWithTag("create_error_banner").performScrollTo().assertIsDisplayed()

    // Retry when Firestore succeeds -> reuses cached upload & shows success banner
    firestoreShouldFail = false
    composeTestRule.onNodeWithTag("create_publish_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals(1, uploadCalls)
    assertEquals(2, firestorePublishCalls)
    assertTrue(vm.uiState.value.publishState is PublishPostState.Success)
    composeTestRule.onNodeWithTag("create_success_banner").performScrollTo().assertIsDisplayed()
  }

  private class InMemoryVynozaBackend(
    initialVideos: List<VideoPost>,
    initialProfiles: Map<String, UserPublicProfile>,
    initialNotifications: List<VynozaNotification> = emptyList(),
  ) : VynozaVideoSource, VynozaUserSource, VynozaNotificationSource {
    private val videosFlow = MutableStateFlow(initialVideos)
    private val profilesFlow = MutableStateFlow(initialProfiles)
    private val notificationsFlow = MutableStateFlow(initialNotifications)
    private val likesByVideo = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val commentsByVideo = MutableStateFlow<Map<String, List<VideoComment>>>(emptyMap())
    private val savedByUser = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val followingByUser = MutableStateFlow<Map<String, List<FollowEdge>>>(emptyMap())
    private val followersByUser = MutableStateFlow<Map<String, List<FollowerEdge>>>(emptyMap())
    private var nextCommentSeq = 1
    var toggleCalls = 0
    var addCommentCalls = 0
    var saveToggleCalls = 0
    var followCalls = 0
    var gate: CompletableDeferred<Unit>? = null
    var commentGate: CompletableDeferred<Unit>? = null
    var saveGate: CompletableDeferred<Unit>? = null
    var followGate: CompletableDeferred<Unit>? = null
    var failNextLikeWith: Throwable? = null
    var failNextCommentWith: Throwable? = null
    var failNextDeleteCommentWith: Throwable? = null
    var failNextSaveWith: Throwable? = null
    var failNextFollowWith: Throwable? = null
    var activeAuthenticatedUserId: String? = null

    override fun observePublicFeed(): Flow<List<VideoPost>> =
      videosFlow.map { list -> list.filter { it.visibility == "public" } }

    override fun observeUserVideos(userId: String): Flow<List<VideoPost>> =
      videosFlow.map { list -> list.filter { it.authorId == userId } }

    override fun observeUserPublicVideos(userId: String, limit: Long): Flow<List<VideoPost>> =
      videosFlow.map { list ->
        list
          .filter { it.authorId == userId && it.visibility == "public" }
          .take(limit.toInt().coerceAtLeast(1))
      }

    override fun observeNotifications(userId: String): Flow<List<VynozaNotification>> =
      notificationsFlow.map { list -> list.filter { it.recipientId == userId } }

    override suspend fun markAsRead(userId: String, notificationId: String): Result<Unit> {
      notificationsFlow.update { list ->
        list.map { item ->
          if (item.recipientId == userId && item.id == notificationId) item.copy(read = true) else item
        }
      }
      return Result.success(Unit)
    }

    override fun observeLikedVideoIds(userId: String): Flow<Set<String>> =
      likesByVideo.map { map ->
        map.filterValues { userId in it }.keys.toSet()
      }

    override suspend fun toggleVideoLike(
      userId: String,
      videoId: String,
      expectedCurrentlyLiked: Boolean?,
      actorUsername: String?,
    ): Result<Boolean> {
      toggleCalls += 1
      gate?.await()
      failNextLikeWith?.let { err ->
        failNextLikeWith = null
        return Result.failure(err)
      }

      var resultLiked = false
      synchronized(this) {
        val currentMap = likesByVideo.value
        val likers = currentMap[videoId].orEmpty()
        val currentlyLiked = userId in likers
        val desiredLiked = expectedCurrentlyLiked?.let { !it } ?: !currentlyLiked
        resultLiked = desiredLiked

        if (desiredLiked != currentlyLiked) {
          val updatedLikers = if (desiredLiked) likers + userId else likers - userId
          likesByVideo.value = currentMap + (videoId to updatedLikers)
          videosFlow.update { videos ->
            videos.map { video ->
              if (video.id == videoId) {
                video.copy(likesCount = updatedLikers.size.toLong())
              } else {
                video
              }
            }
          }
        }
      }
      return Result.success(resultLiked)
    }

    override fun observeVideoComments(videoId: String): Flow<List<VideoComment>> =
      commentsByVideo.map { map -> map[videoId].orEmpty() }

    override suspend fun addComment(
      userId: String,
      videoId: String,
      text: String,
    ): Result<VideoComment> {
      addCommentCalls += 1
      commentGate?.await()
      failNextCommentWith?.let { err ->
        failNextCommentWith = null
        return Result.failure(err)
      }
      val cleanText = text.trim()
      if (cleanText.isEmpty()) {
        return Result.failure(IllegalArgumentException("Comment cannot be empty."))
      }
      val video =
        videosFlow.value.firstOrNull { it.id == videoId }
          ?: return Result.failure(IllegalStateException("Video not found."))
      if (video.visibility != "public" && video.authorId != userId) {
        return Result.failure(IllegalStateException("Permission denied."))
      }
      val profile = profilesFlow.value[userId]
      val comment =
        VideoComment(
          id = "cmt_${nextCommentSeq++}",
          videoId = videoId,
          authorId = userId,
          authorUsername = profile?.username ?: "user_$userId",
          authorDisplayName = profile?.displayName ?: "Vynoza User",
          authorAvatarUrl = profile?.avatarUrl,
          text = cleanText,
          createdAt = Timestamp.now(),
        )
      synchronized(this) {
        val current = commentsByVideo.value[videoId].orEmpty()
        val updated = listOf(comment) + current
        commentsByVideo.value = commentsByVideo.value + (videoId to updated)
        videosFlow.update { videos ->
          videos.map { item ->
            if (item.id == videoId) item.copy(commentsCount = updated.size.toLong()) else item
          }
        }
      }
      return Result.success(comment)
    }

    override suspend fun deleteComment(
      userId: String,
      videoId: String,
      commentId: String,
    ): Result<Unit> {
      failNextDeleteCommentWith?.let { err ->
        failNextDeleteCommentWith = null
        return Result.failure(err)
      }
      synchronized(this) {
        val current = commentsByVideo.value[videoId].orEmpty()
        val target =
          current.firstOrNull { it.id == commentId }
            ?: return Result.failure(IllegalStateException("Comment not found."))
        if (target.authorId != userId) {
          return Result.failure(IllegalStateException("You can only delete your own comments."))
        }
        val updated = current.filterNot { it.id == commentId }
        commentsByVideo.value = commentsByVideo.value + (videoId to updated)
        videosFlow.update { videos ->
          videos.map { item ->
            if (item.id == videoId) item.copy(commentsCount = updated.size.toLong()) else item
          }
        }
      }
      return Result.success(Unit)
    }

    override fun observeSavedVideoIds(userId: String): Flow<List<String>> {
      val actor = activeAuthenticatedUserId
      if (actor != null && actor != userId) {
        throw SecurityException("Permission denied: only owner can access saved videos.")
      }
      return savedByUser.map { map -> map[userId].orEmpty() }
    }

    override suspend fun toggleVideoSave(
      userId: String,
      videoId: String,
      expectedCurrentlySaved: Boolean?,
    ): Result<Boolean> {
      saveToggleCalls += 1
      saveGate?.await()
      val actor = activeAuthenticatedUserId
      if (actor != null && actor != userId) {
        return Result.failure(SecurityException("Permission denied: cannot modify another user's saved videos."))
      }
      failNextSaveWith?.let { err ->
        failNextSaveWith = null
        return Result.failure(err)
      }
      val video =
        videosFlow.value.firstOrNull { it.id == videoId }
          ?: return Result.failure(IllegalStateException("Video not found."))
      if (video.visibility != "public" && video.authorId != userId) {
        return Result.failure(SecurityException("Permission denied."))
      }

      var resultSaved = false
      synchronized(this) {
        val current = savedByUser.value[userId].orEmpty()
        val currentlySaved = videoId in current
        val desiredSaved = expectedCurrentlySaved?.let { !it } ?: !currentlySaved
        resultSaved = desiredSaved
        val updated =
          if (desiredSaved) {
            if (currentlySaved) current else listOf(videoId) + current
          } else {
            current.filterNot { it == videoId }
          }
        savedByUser.value = savedByUser.value + (userId to updated)
      }
      return Result.success(resultSaved)
    }

    override suspend fun getVideoById(videoId: String): Result<VideoPost?> {
      val post = videosFlow.value.firstOrNull { it.id == videoId }
      return if (post != null && (post.visibility == "public" || post.authorId == activeAuthenticatedUserId)) {
        Result.success(post)
      } else {
        Result.success(null)
      }
    }

    override fun observePublicProfile(userId: String): Flow<UserPublicProfile?> =
      profilesFlow.map { it[userId] }

    override fun observeDiscoverCreators(): Flow<List<UserPublicProfile>> =
      profilesFlow.map { it.values.toList() }

    override fun observeFollowing(userId: String): Flow<List<FollowEdge>> =
      followingByUser.map { it[userId].orEmpty() }

    override fun observeFollowers(userId: String): Flow<List<FollowerEdge>> =
      followersByUser.map { it[userId].orEmpty() }

    override suspend fun updatePublicProfile(
      userId: String,
      displayName: String,
      username: String,
      bio: String,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun followCreator(
      currentUserId: String,
      currentUsername: String,
      target: UserPublicProfile,
    ): Result<Unit> {
      followCalls += 1
      followGate?.await()
      failNextFollowWith?.let { err ->
        failNextFollowWith = null
        return Result.failure(err)
      }
      if (currentUserId == target.userId) {
        return Result.failure(IllegalArgumentException("Cannot follow yourself."))
      }
      synchronized(this) {
        val currentEdges = followingByUser.value[currentUserId].orEmpty()
        val currentFollowers = followersByUser.value[target.userId].orEmpty()
        val alreadyFollowing = currentEdges.any { it.targetUserId == target.userId }
        val alreadyHasFollower = currentFollowers.any { it.followerId == currentUserId }

        if (!alreadyFollowing) {
          val edge =
            FollowEdge(
              id = target.userId,
              followerId = currentUserId,
              targetUserId = target.userId,
              targetUsername = target.username,
              createdAt = Timestamp.now(),
            )
          followingByUser.value = followingByUser.value + (currentUserId to (currentEdges + edge))
          profilesFlow.update { map ->
            map.toMutableMap().apply {
              map[currentUserId]?.let { put(currentUserId, it.copy(followingCount = it.followingCount + 1)) }
            }
          }
        }
        if (!alreadyHasFollower) {
          val followerEdge =
            FollowerEdge(
              id = currentUserId,
              followerId = currentUserId,
              targetUserId = target.userId,
              followerUsername = currentUsername,
              createdAt = Timestamp.now(),
            )
          followersByUser.value = followersByUser.value + (target.userId to (currentFollowers + followerEdge))
          profilesFlow.update { map ->
            map.toMutableMap().apply {
              map[target.userId]?.let { put(target.userId, it.copy(followersCount = it.followersCount + 1)) }
            }
          }
        }
      }
      return Result.success(Unit)
    }

    override suspend fun unfollowCreator(
      currentUserId: String,
      targetUserId: String,
    ): Result<Unit> {
      followCalls += 1
      followGate?.await()
      failNextFollowWith?.let { err ->
        failNextFollowWith = null
        return Result.failure(err)
      }
      synchronized(this) {
        val currentEdges = followingByUser.value[currentUserId].orEmpty()
        val currentFollowers = followersByUser.value[targetUserId].orEmpty()
        val hasFollowing = currentEdges.any { it.targetUserId == targetUserId }
        val hasFollower = currentFollowers.any { it.followerId == currentUserId }
        if (hasFollowing) {
          followingByUser.value =
            followingByUser.value + (currentUserId to currentEdges.filterNot { it.targetUserId == targetUserId })
          profilesFlow.update { map ->
            map.toMutableMap().apply {
              map[currentUserId]?.let {
                put(currentUserId, it.copy(followingCount = (it.followingCount - 1).coerceAtLeast(0L)))
              }
            }
          }
        }
        if (hasFollower) {
          followersByUser.value =
            followersByUser.value + (targetUserId to currentFollowers.filterNot { it.followerId == currentUserId })
        }
        if (hasFollower || hasFollowing) {
          profilesFlow.update { map ->
            map.toMutableMap().apply {
              map[targetUserId]?.let {
                put(targetUserId, it.copy(followersCount = (it.followersCount - 1).coerceAtLeast(0L)))
              }
            }
          }
        }
      }
      return Result.success(Unit)
    }

    fun seedFollowerEdgeWithoutCounter(followerId: String, targetUserId: String, followerUsername: String) {
      val current = followersByUser.value[targetUserId].orEmpty()
      if (current.none { it.followerId == followerId }) {
        val edge =
          FollowerEdge(
            id = followerId,
            followerId = followerId,
            targetUserId = targetUserId,
            followerUsername = followerUsername,
            createdAt = Timestamp.now(),
          )
        followersByUser.value = followersByUser.value + (targetUserId to (current + edge))
      }
    }
  }

  @Test
  fun `like feature supports Account A self-like, Account B like, unlike, rapid tap guard, error handling, and restart persistence`() {
    val accountA = "user_alpha"
    val accountB = "user_beta"
    val video =
      VideoPost(
        id = "vid_shared_01",
        authorId = accountA,
        authorUsername = "alice_v",
        caption = "Sunset skyline loop",
        visibility = "public",
        status = "ready",
        playbackUrl = null,
        likesCount = 0L,
        createdAt = Timestamp.now(),
      )
    val profiles =
      mapOf(
        accountA to UserPublicProfile(userId = accountA, username = "alice_v", displayName = "Alice"),
        accountB to UserPublicProfile(userId = accountB, username = "bob_v", displayName = "Bob"),
      )
    val backend = InMemoryVynozaBackend(listOf(video), profiles)

    // 1. Account A opens Home and likes its own video (0 -> 1)
    val homeVmA = HomeViewModel(backend, backend, accountA)
    val activeSlot = MutableStateFlow("home_a")
    var restartedDiscoverVmB: DiscoverViewModel? = null
    var restartedProfileVmA: ProfileViewModel? = null

    composeTestRule.setContent {
      val slot = activeSlot.collectAsState().value
      VynozaTheme {
        when (slot) {
          "home_a" -> HomeScreen(viewModel = homeVmA, onNavigateToCreate = {}, isScreenVisible = false)
          "discover_b" -> DiscoverScreen(viewModel = restartedDiscoverVmB!!)
          "profile_a" -> ProfileScreen(viewModel = restartedProfileVmA!!, onSignOut = {})
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_like_count_vid_shared_01").assertTextEquals("0")
    composeTestRule.onNodeWithTag("home_like_button_vid_shared_01").assertContentDescriptionEquals("Like video")

    // Hold transaction in flight to test rapid duplicate tap prevention
    val inFlightGate = CompletableDeferred<Unit>()
    backend.gate = inFlightGate
    composeTestRule.onNodeWithTag("home_like_button_vid_shared_01").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_like_button_vid_shared_01").assertIsNotEnabled()
    homeVmA.toggleLike(video, currentlyLiked = false, actorUsername = "alice_v")
    homeVmA.toggleLike(video, currentlyLiked = false, actorUsername = "alice_v")
    assertEquals(1, backend.toggleCalls)

    inFlightGate.complete(Unit)
    backend.gate = null
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_like_count_vid_shared_01").assertTextEquals("1")
    composeTestRule.onNodeWithTag("home_like_button_vid_shared_01").assertContentDescriptionEquals("Unlike video")

    // 2. Account B likes Account A's video (1 -> 2), and Account A's Home feed updates consistently
    val discoverVmB = DiscoverViewModel(backend, backend, accountB)
    discoverVmB.toggleLike(video.copy(likesCount = 1L), currentlyLiked = false, currentUsername = "bob_v")
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_like_count_vid_shared_01").assertTextEquals("2")

    // 3. Simulated network failure surfaces error banner and preserves persisted state (still 2, still liked)
    backend.failNextLikeWith = IllegalStateException("Network unavailable")
    composeTestRule.onNodeWithTag("home_like_button_vid_shared_01").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_like_error_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("home_like_count_vid_shared_01").assertTextEquals("2")
    composeTestRule.onNodeWithTag("home_like_button_vid_shared_01").assertContentDescriptionEquals("Unlike video")

    // 4. Account A unlikes (2 -> 1), then re-likes (1 -> 2)
    composeTestRule.onNodeWithTag("home_like_button_vid_shared_01").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_like_count_vid_shared_01").assertTextEquals("1")
    composeTestRule.onNodeWithTag("home_like_button_vid_shared_01").assertContentDescriptionEquals("Like video")

    composeTestRule.onNodeWithTag("home_like_button_vid_shared_01").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_like_count_vid_shared_01").assertTextEquals("2")

    // 5. Simulate app restart: new ViewModels for Account B (Discover) and Account A (Profile)
    restartedDiscoverVmB = DiscoverViewModel(backend, backend, accountB)
    activeSlot.value = "discover_b"
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("discover_like_count_vid_shared_01", useUnmergedTree = true).assertTextEquals("2")
    composeTestRule.onNodeWithTag("discover_like_button_vid_shared_01", useUnmergedTree = true).assertContentDescriptionEquals("Unlike video")
    composeTestRule.onNodeWithTag("discover_like_button_vid_shared_01", useUnmergedTree = true).performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("discover_like_count_vid_shared_01", useUnmergedTree = true).assertTextEquals("1")
    composeTestRule.onNodeWithTag("discover_like_button_vid_shared_01", useUnmergedTree = true).assertContentDescriptionEquals("Like video")

    // Switch to restarted ProfileScreen for Account A and verify tile shows persisted count == 1
    restartedProfileVmA = ProfileViewModel(backend, backend, accountA)
    activeSlot.value = "profile_a"
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("profile_grid").performScrollToNode(hasTestTag("profile_video_tile_vid_shared_01"))
    composeTestRule.onNodeWithTag("profile_tile_like_count_vid_shared_01", useUnmergedTree = true).assertTextEquals("1")
  }

  @Test
  fun `comments feature supports creating, real-time multi-account reading, ownership delete, empty check, and errors`() {
    val accountA = "user_alice"
    val accountB = "user_bob"
    val video =
      VideoPost(
        id = "vid_comment_01",
        authorId = accountA,
        authorUsername = "alice_v",
        caption = "Rooftop sunset clip",
        visibility = "public",
        status = "ready",
        playbackUrl = null,
        likesCount = 0L,
        commentsCount = 0L,
        createdAt = Timestamp.now(),
      )
    val profiles =
      mapOf(
        accountA to
          UserPublicProfile(
            userId = accountA,
            username = "alice_v",
            displayName = "Alice",
            avatarUrl = "https://example.com/alice.png",
          ),
        accountB to
          UserPublicProfile(
            userId = accountB,
            username = "bob_v",
            displayName = "Bob",
          ),
      )
    val backend = InMemoryVynozaBackend(listOf(video), profiles)

    val homeVmA = HomeViewModel(backend, backend, accountA)
    val activeSlot = MutableStateFlow("home_a")
    var discoverVmB: DiscoverViewModel? = null
    var profileVmA: ProfileViewModel? = null

    composeTestRule.setContent {
      val slot = activeSlot.collectAsState().value
      VynozaTheme {
        when (slot) {
          "home_a" -> HomeScreen(viewModel = homeVmA, onNavigateToCreate = {}, isScreenVisible = false)
          "discover_b" -> DiscoverScreen(viewModel = discoverVmB!!)
          "profile_a" -> ProfileScreen(viewModel = profileVmA!!, onSignOut = {})
        }
      }
    }
    composeTestRule.waitForIdle()

    // 1. Open comments sheet from Home feed -> empty state & count 0
    composeTestRule.onNodeWithTag("home_comment_count_vid_comment_01").assertTextEquals("0")
    composeTestRule.onNodeWithTag("home_comment_button_vid_comment_01").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("comments_bottom_sheet").assertIsDisplayed()
    composeTestRule.onNodeWithTag("comments_sheet_header").assertTextEquals("Comments (0)")
    composeTestRule.onNodeWithText("No comments yet").assertIsDisplayed()

    // 2. Prevent empty/whitespace comments
    composeTestRule.onNodeWithTag("comment_send_button").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("comment_input").performTextInput("   ")
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("comment_send_button").assertIsNotEnabled()
    homeVmA.submitComment()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("comments_error_banner").assertIsDisplayed()
    assertEquals(0, backend.addCommentCalls)
    composeTestRule.onNodeWithTag("comment_input").performTextClearance()

    // 3. Account A posts a valid comment (with in-flight duplicate guard)
    val gate = CompletableDeferred<Unit>()
    backend.commentGate = gate
    composeTestRule.onNodeWithTag("comment_input").performTextInput("First post on VYNOZA!")
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("comment_send_button").assertIsEnabled()
    composeTestRule.onNodeWithTag("comment_send_button").performClick()
    composeTestRule.waitForIdle()
    homeVmA.submitComment() // Duplicate tap while in flight must be ignored
    assertEquals(1, backend.addCommentCalls)

    gate.complete(Unit)
    backend.commentGate = null
    composeTestRule.waitForIdle()

    // Comment saved, timestamp + username + delete button visible, header & home count updated to 1
    composeTestRule.onNodeWithTag("comments_sheet_header").assertTextEquals("Comments (1)")
    composeTestRule.onNodeWithTag("home_comment_count_vid_comment_01").assertTextEquals("1")
    composeTestRule.onNodeWithTag("comment_item_cmt_1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("comment_avatar_cmt_1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("comment_author_cmt_1").assertTextEquals("@alice_v")
    composeTestRule.onNodeWithTag("comment_timestamp_cmt_1").assertTextContains("Just now", substring = true)
    composeTestRule.onNodeWithText("First post on VYNOZA!").assertIsDisplayed()
    composeTestRule.onNodeWithTag("delete_comment_button_cmt_1").assertIsDisplayed()

    // Close sheet for Account A
    composeTestRule.onNodeWithTag("comments_sheet_close").performClick()
    composeTestRule.waitForIdle()

    // 4. Switch to Account B on Discover -> sees count 1, opens sheet, sees Alice's comment without delete button
    discoverVmB = DiscoverViewModel(backend, backend, accountB)
    activeSlot.value = "discover_b"
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("discover_comment_count_vid_comment_01", useUnmergedTree = true).assertTextEquals("1")
    composeTestRule.onNodeWithTag("discover_comment_button_vid_comment_01", useUnmergedTree = true).performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("First post on VYNOZA!").assertIsDisplayed()
    composeTestRule.onNodeWithTag("delete_comment_button_cmt_1").assertDoesNotExist()

    // 5. Failed comment submission keeps draft text and displays error; retry succeeds
    backend.failNextCommentWith = IllegalStateException("Network timeout")
    composeTestRule.onNodeWithTag("comment_input").performTextInput("Amazing sunset!")
    composeTestRule.onNodeWithTag("comment_send_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("comments_error_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("comment_input").assertTextContains("Amazing sunset!")

    // Retry sending preserved draft
    composeTestRule.onNodeWithTag("comment_send_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("comments_sheet_header").assertTextEquals("Comments (2)")
    composeTestRule.onNodeWithTag("discover_comment_count_vid_comment_01", useUnmergedTree = true).assertTextEquals("2")
    composeTestRule.onNodeWithText("Amazing sunset!").assertIsDisplayed()
    composeTestRule.onNodeWithTag("delete_comment_button_cmt_2").assertIsDisplayed()

    // 6. Unauthorized delete guard: Account B cannot delete Account A's comment
    discoverVmB.deleteComment(
      VideoComment(
        id = "cmt_1",
        videoId = "vid_comment_01",
        authorId = accountA,
        authorUsername = "alice_v",
        text = "First post on VYNOZA!",
      )
    )
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("comments_error_banner").assertIsDisplayed()
    composeTestRule.onNodeWithText("First post on VYNOZA!").assertIsDisplayed()

    // 7. Account B deletes its own comment -> count decrements (2 -> 1), comment removed
    composeTestRule.onNodeWithTag("delete_comment_button_cmt_2").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("comments_sheet_header").assertTextEquals("Comments (1)")
    composeTestRule.onNodeWithTag("discover_comment_count_vid_comment_01", useUnmergedTree = true).assertTextEquals("1")
    composeTestRule.onNodeWithText("Amazing sunset!").assertDoesNotExist()
    composeTestRule.onNodeWithText("First post on VYNOZA!").assertIsDisplayed()
    composeTestRule.onNodeWithTag("comments_sheet_close").performClick()
    composeTestRule.waitForIdle()

    // 8. Account A restarts into ProfileScreen -> persisted comment count == 1 on tile
    profileVmA = ProfileViewModel(backend, backend, accountA)
    activeSlot.value = "profile_a"
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("profile_grid").performScrollToNode(hasTestTag("profile_video_tile_vid_comment_01"))
    composeTestRule.onNodeWithTag("profile_tile_comment_count_vid_comment_01", useUnmergedTree = true).assertTextEquals("1")
  }

  @Test
  fun `quick follow button appears on others videos, hides on own video, updates UI immediately, and syncs across restarts`() {
    val accountA = "user_alice"
    val accountB = "user_bob"
    val otherVideo =
      VideoPost(
        id = "vid_other_01",
        authorId = accountB,
        authorUsername = "bob_v",
        caption = "Bob skyline clip",
        visibility = "public",
        status = "ready",
        playbackUrl = "https://res.cloudinary.com/idkq3e9l/video/upload/v1/bob.mp4",
        createdAt = Timestamp(1000L, 0),
      )
    val ownVideo =
      VideoPost(
        id = "vid_own_01",
        authorId = accountA,
        authorUsername = "alice_v",
        caption = "Alice own clip",
        visibility = "public",
        status = "ready",
        playbackUrl = "https://res.cloudinary.com/idkq3e9l/video/upload/v1/alice.mp4",
        createdAt = Timestamp(2000L, 0),
      )
    val profiles =
      mapOf(
        accountA to UserPublicProfile(userId = accountA, username = "alice_v", displayName = "Alice"),
        accountB to UserPublicProfile(userId = accountB, username = "bob_v", displayName = "Bob"),
      )
    val backend = InMemoryVynozaBackend(listOf(otherVideo, ownVideo), profiles)
    val homeVm = HomeViewModel(backend, backend, accountA)

    composeTestRule.setContent {
      VynozaTheme {
        HomeScreen(viewModel = homeVm, onNavigateToCreate = {}, isScreenVisible = false)
      }
    }
    composeTestRule.waitForIdle()

    // Page 0 is Bob's video (or navigate to targetVideoId); target Bob's video directly first
    composeTestRule.onNodeWithTag("home_creator_avatar_vid_other_01").assertIsDisplayed()
    composeTestRule.onNodeWithTag("home_follow_button_vid_other_01").assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("home_follow_button_vid_other_01")
      .assertContentDescriptionEquals("Follow @bob_v")

    // Hold network follow in flight -> optimistic UI immediately reflects Following state
    val gate = CompletableDeferred<Unit>()
    backend.followGate = gate
    composeTestRule.onNodeWithTag("home_follow_button_vid_other_01").performClick()
    composeTestRule.waitForIdle()
    composeTestRule
      .onNodeWithTag("home_follow_button_vid_other_01")
      .assertContentDescriptionEquals("Following @bob_v")

    gate.complete(Unit)
    backend.followGate = null
    composeTestRule.waitForIdle()
    assertEquals(1, backend.followCalls)

    // Recreate HomeViewModel (simulating restart) and check Following tab & synced badge
    val restartedHomeVm = HomeViewModel(backend, backend, accountA)
    runBlocking {
      val edges = backend.observeFollowing(accountA).first()
      assertEquals(listOf(accountB), edges.map { it.targetUserId })
    }

    // Own video must never display a quick Follow button
    restartedHomeVm.toggleFollowCreator(ownVideo, currentlyFollowing = false)
    assertEquals(1, backend.followCalls)
  }

  @Test
  fun `home hides quick follow badge on current users own video`() {
    val accountA = "user_alice"
    val ownVideo =
      VideoPost(
        id = "vid_self_only",
        authorId = accountA,
        authorUsername = "alice_v",
        caption = "Solo studio session",
        visibility = "public",
        status = "ready",
        playbackUrl = null,
        createdAt = Timestamp.now(),
      )
    val profiles =
      mapOf(accountA to UserPublicProfile(userId = accountA, username = "alice_v", displayName = "Alice"))
    val backend = InMemoryVynozaBackend(listOf(ownVideo), profiles)
    val homeVm = HomeViewModel(backend, backend, accountA)

    composeTestRule.setContent {
      VynozaTheme {
        HomeScreen(viewModel = homeVm, onNavigateToCreate = {}, isScreenVisible = false)
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("home_creator_avatar_vid_self_only").assertIsDisplayed()
    composeTestRule.onNodeWithTag("home_follow_button_vid_self_only").assertDoesNotExist()
  }

  @Test
  fun `bookmark save persists across restarts, prevents duplicates, enforces owner privacy, and removes from profile saved tab`() {
    val accountA = "user_alice"
    val accountB = "user_bob"
    val video =
      VideoPost(
        id = "vid_bookmark_01",
        authorId = accountB,
        authorUsername = "bob_v",
        caption = "Mountain drone shot",
        visibility = "public",
        status = "ready",
        playbackUrl = "https://res.cloudinary.com/idkq3e9l/video/upload/v1/drone.mp4",
        createdAt = Timestamp.now(),
      )
    val profiles =
      mapOf(
        accountA to UserPublicProfile(userId = accountA, username = "alice_v", displayName = "Alice"),
        accountB to UserPublicProfile(userId = accountB, username = "bob_v", displayName = "Bob"),
      )
    val backend = InMemoryVynozaBackend(listOf(video), profiles).apply {
      activeAuthenticatedUserId = accountA
    }
    val homeVmA = HomeViewModel(backend, backend, accountA)
    val activeSlot = MutableStateFlow("home_a")
    var restartedProfileVmA: ProfileViewModel? = null

    composeTestRule.setContent {
      val slot = activeSlot.collectAsState().value
      VynozaTheme {
        when (slot) {
          "home_a" -> HomeScreen(viewModel = homeVmA, onNavigateToCreate = {}, isScreenVisible = false)
          "profile_a" -> ProfileScreen(viewModel = restartedProfileVmA!!, onSignOut = {})
        }
      }
    }
    composeTestRule.waitForIdle()

    // 1. Initial unsaved state -> tap bookmark to save
    composeTestRule.onNodeWithTag("home_save_label_vid_bookmark_01").assertTextEquals("Save")
    composeTestRule.onNodeWithTag("home_save_button_vid_bookmark_01").assertContentDescriptionEquals("Save video")

    composeTestRule.onNodeWithTag("home_save_button_vid_bookmark_01").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_save_label_vid_bookmark_01").assertTextEquals("Saved")
    composeTestRule.onNodeWithTag("home_save_button_vid_bookmark_01").assertContentDescriptionEquals("Remove saved video")

    // 2. Duplicate save calls must never store duplicate IDs
    runBlocking {
      val duplicateAttempt = backend.toggleVideoSave(accountA, "vid_bookmark_01", expectedCurrentlySaved = false)
      assertTrue(duplicateAttempt.isSuccess)
      assertEquals(listOf("vid_bookmark_01"), backend.observeSavedVideoIds(accountA).first())
    }

    // 3. Another user cannot access or mutate Account A's saved list
    runBlocking {
      backend.activeAuthenticatedUserId = accountB
      val unauthorizedWrite = backend.toggleVideoSave(accountA, "vid_bookmark_01", expectedCurrentlySaved = true)
      assertTrue(unauthorizedWrite.isFailure)
      assertEquals(emptyList<String>(), backend.observeSavedVideoIds(accountB).first())
      backend.activeAuthenticatedUserId = accountA
    }

    // 4. Simulate app restart into ProfileScreen -> Saved tab displays saved video, and removing unsaves it
    restartedProfileVmA = ProfileViewModel(backend, backend, accountA)
    activeSlot.value = "profile_a"
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("profile_grid").performScrollToNode(hasTestTag("profile_tab_saved"))
    composeTestRule.onNodeWithTag("profile_tab_saved").assertTextContains("Saved (1)")
    composeTestRule.onNodeWithTag("profile_tab_saved").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("profile_grid").performScrollToNode(hasTestTag("profile_saved_tile_vid_bookmark_01"))
    composeTestRule.onNodeWithTag("profile_saved_tile_vid_bookmark_01").assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_remove_saved_vid_bookmark_01", useUnmergedTree = true).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("profile_tab_saved").assertTextContains("Saved (0)")
    composeTestRule.onNodeWithTag("profile_saved_tile_vid_bookmark_01").assertDoesNotExist()
    runBlocking {
      assertEquals(emptyList<String>(), backend.observeSavedVideoIds(accountA).first())
    }
  }

  @Test
  fun `share button launches native Android sharesheet with real video url and deep link parses cleanly`() {
    val accountA = "user_alice"
    val realVideoUrl = "https://res.cloudinary.com/idkq3e9l/video/upload/v77/city_walk.mp4"
    val videoWithUrl =
      VideoPost(
        id = "vid_share_01",
        authorId = "user_bob",
        authorUsername = "bob_v",
        caption = "Night walk in Tokyo",
        visibility = "public",
        status = "ready",
        playbackUrl = realVideoUrl,
        createdAt = Timestamp.now(),
      )
    val videoWithoutUrl =
      VideoPost(
        id = "vid_share_deeplink",
        authorId = "user_bob",
        authorUsername = "bob_v",
        caption = "Draft clip",
        visibility = "public",
        status = "ready",
        playbackUrl = null,
      )

    // Unit checks on share helpers & deep link parser
    assertEquals(realVideoUrl, resolveShareableVideoUrl(videoWithUrl))
    assertEquals("vynoza://video/vid_share_deeplink", resolveShareableVideoUrl(videoWithoutUrl))
    assertNull(resolveShareableVideoUrl(VideoPost(id = "", playbackUrl = null)))

    val shareText = buildVideoShareText(videoWithUrl)
    assertNotNull(shareText)
    assertTrue(shareText!!.contains("@bob_v"))
    assertTrue(shareText.contains("Night walk in Tokyo"))
    assertTrue(shareText.contains(realVideoUrl))

    val sendIntent = buildVideoShareSendIntent(videoWithUrl)
    assertNotNull(sendIntent)
    assertEquals(Intent.ACTION_SEND, sendIntent!!.action)
    assertEquals("text/plain", sendIntent.type)
    assertEquals(shareText, sendIntent.getStringExtra(Intent.EXTRA_TEXT))

    val parsedDeepLink =
      MainActivity.extractVideoIdFromIntent(
        Intent(Intent.ACTION_VIEW, Uri.parse("vynoza://video/vid_share_deeplink"))
      )
    assertEquals("vid_share_deeplink", parsedDeepLink)

    // UI check on HomeScreen Share button
    val profiles =
      mapOf(
        accountA to UserPublicProfile(userId = accountA, username = "alice_v", displayName = "Alice"),
        "user_bob" to UserPublicProfile(userId = "user_bob", username = "bob_v", displayName = "Bob"),
      )
    val backend = InMemoryVynozaBackend(listOf(videoWithUrl), profiles)
    val homeVm = HomeViewModel(backend, backend, accountA)

    composeTestRule.setContent {
      VynozaTheme {
        HomeScreen(viewModel = homeVm, onNavigateToCreate = {}, isScreenVisible = false)
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("home_share_label_vid_share_01").assertTextEquals("Share")
    composeTestRule.onNodeWithTag("home_share_button_vid_share_01").performClick()
    composeTestRule.waitForIdle()

    val launchedIntent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
    assertNotNull("Expected native Android Sharesheet chooser intent", launchedIntent)
    assertEquals(Intent.ACTION_CHOOSER, launchedIntent!!.action)
    @Suppress("DEPRECATION")
    val wrappedSend = launchedIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
    assertNotNull(wrappedSend)
    assertEquals(Intent.ACTION_SEND, wrappedSend!!.action)
    assertEquals("text/plain", wrappedSend.type)
    val launchedText = wrappedSend.getStringExtra(Intent.EXTRA_TEXT)
    assertNotNull(launchedText)
    assertTrue(launchedText!!.contains("@bob_v"))
    assertTrue(launchedText.contains(realVideoUrl))
  }

  @Test
  fun `public user profile navigation from Home, Discover, Comments, and Notifications respects ownership and privacy`() {
    val currentUserId = "user_alice"
    val creatorBobId = "user_bob"
    val creatorCaraId = "user_cara"

    val bobPublicVideo =
      VideoPost(
        id = "vid_bob_pub_1",
        authorId = creatorBobId,
        authorUsername = "bob_v",
        caption = "Street dance session",
        visibility = "public",
        status = "ready",
        playbackUrl = "https://res.cloudinary.com/idkq3e9l/video/upload/v50/bob_dance.mp4",
        likesCount = 14L,
        commentsCount = 1L,
        viewsCount = 1500L,
        createdAt = Timestamp(3000L, 0),
      )
    val bobPrivateVideo =
      VideoPost(
        id = "vid_bob_priv_1",
        authorId = creatorBobId,
        authorUsername = "bob_v",
        caption = "Hidden draft clip",
        visibility = "private",
        status = "ready",
        playbackUrl = "https://res.cloudinary.com/idkq3e9l/video/upload/v51/bob_priv.mp4",
        createdAt = Timestamp(2500L, 0),
      )
    val profiles =
      mapOf(
        currentUserId to
          UserPublicProfile(
            userId = currentUserId,
            username = "alice_v",
            displayName = "Alice",
            bio = "Own creator bio",
          ),
        creatorBobId to
          UserPublicProfile(
            userId = creatorBobId,
            username = "bob_v",
            displayName = "Bob Motion",
            bio = "Visual storyteller",
            followersCount = 0L,
            followingCount = 4L,
            videosCount = 2L,
          ),
        creatorCaraId to
          UserPublicProfile(
            userId = creatorCaraId,
            username = "cara_k",
            displayName = "Cara Kim",
            bio = "Choreographer",
          ),
      )
    val notifications =
      listOf(
        VynozaNotification(
          id = "notif_cara_1",
          recipientId = currentUserId,
          actorId = creatorCaraId,
          actorUsername = "cara_k",
          type = "follow",
          message = "@cara_k started following you",
          read = false,
          createdAt = Timestamp.now(),
        )
      )
    val backend = InMemoryVynozaBackend(listOf(bobPublicVideo, bobPrivateVideo), profiles, notifications)

    runBlocking {
      backend.addComment(creatorCaraId, bobPublicVideo.id, "Clean transitions!")
    }

    val routeLog = mutableListOf<String>()
    val activeSlot = MutableStateFlow("home")
    val selectedPublicTarget = MutableStateFlow<String?>(null)

    fun openCreator(targetId: String) {
      routeLog.add(targetId)
      if (targetId == currentUserId) {
        activeSlot.value = "own_profile"
      } else {
        selectedPublicTarget.value = targetId
        activeSlot.value = "public_profile"
      }
    }

    val homeVm = HomeViewModel(backend, backend, currentUserId)
    val discoverVm = DiscoverViewModel(backend, backend, currentUserId)
    val notifVm = NotificationsViewModel(backend, currentUserId)
    val ownProfileVm = ProfileViewModel(backend, backend, currentUserId)

    composeTestRule.setContent {
      val slot = activeSlot.collectAsState().value
      val targetId = selectedPublicTarget.collectAsState().value
      VynozaTheme {
        when (slot) {
          "home" ->
            HomeScreen(
              viewModel = homeVm,
              onNavigateToCreate = {},
              onOpenCreatorProfile = ::openCreator,
              isScreenVisible = false,
            )
          "discover" ->
            DiscoverScreen(
              viewModel = discoverVm,
              onOpenCreatorProfile = ::openCreator,
            )
          "notifications" ->
            NotificationsScreen(
              viewModel = notifVm,
              onOpenCreatorProfile = ::openCreator,
            )
          "own_profile" ->
            ProfileScreen(
              viewModel = ownProfileVm,
              onSignOut = {},
              onOpenCreatorProfile = ::openCreator,
            )
          "public_profile" -> {
            val pubVm =
              androidx.compose.runtime.remember(targetId) {
                PublicProfileViewModel(backend, backend, targetId!!, currentUserId)
              }
            PublicProfileScreen(
              viewModel = pubVm,
              onBack = { activeSlot.value = "home" },
              onOpenCreatorProfile = ::openCreator,
            )
          }
        }
      }
    }
    composeTestRule.waitForIdle()

    // 1. Tap creator username on Home feed -> opens Bob's PublicProfileScreen (NOT own editable profile)
    composeTestRule.onNodeWithTag("home_creator_username_vid_bob_pub_1").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("public_profile_display_name").assertTextEquals("Bob Motion")
    composeTestRule.onNodeWithTag("public_profile_username").assertTextEquals("@bob_v")
    composeTestRule.onNodeWithTag("public_profile_bio").assertTextEquals("Visual storyteller")
    composeTestRule.onNodeWithTag("public_profile_followers_count").assertTextContains("0")
    composeTestRule.onNodeWithTag("public_profile_following_count").assertTextContains("4")
    // Must only count & show Bob's 1 public video (never leak private video count or tile)
    composeTestRule.onNodeWithTag("public_profile_videos_count").assertTextContains("1")
    composeTestRule.onNodeWithTag("public_profile_grid").performScrollToNode(hasTestTag("public_profile_video_tile_vid_bob_pub_1"))
    composeTestRule.onNodeWithTag("public_profile_video_tile_vid_bob_pub_1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("public_profile_tile_views_count_vid_bob_pub_1", useUnmergedTree = true).assertTextEquals("1.5K")
    composeTestRule.onNodeWithTag("public_profile_video_tile_vid_bob_priv_1").assertDoesNotExist()
    // Must never render own-profile Edit, Sign Out, or Saved controls
    composeTestRule.onNodeWithTag("edit_profile_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("sign_out_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("profile_tab_saved").assertDoesNotExist()

    // Back returns cleanly to Home; tapping avatar also navigates to Bob's public profile
    composeTestRule.onNodeWithTag("public_profile_back_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_creator_avatar_vid_bob_pub_1").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("public_profile_username").assertTextEquals("@bob_v")

    // 2. Tap video tile on PublicProfileScreen -> opens player dialog & comments -> tap Cara's comment -> opens Cara's profile
    composeTestRule.onNodeWithTag("public_profile_grid").performScrollToNode(hasTestTag("public_profile_video_tile_vid_bob_pub_1"))
    composeTestRule.onNodeWithTag("public_profile_video_tile_vid_bob_pub_1").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("player_dialog_author_vid_bob_pub_1").assertTextEquals("@bob_v")
    composeTestRule.onNodeWithTag("player_dialog_comment_button_vid_bob_pub_1").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("comment_author_cmt_1").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("public_profile_display_name").assertTextEquals("Cara Kim")
    composeTestRule.onNodeWithTag("public_profile_username").assertTextEquals("@cara_k")
    composeTestRule.onNodeWithTag("public_profile_grid").performScrollToNode(hasTestTag("public_profile_empty_videos"))
    composeTestRule.onNodeWithTag("public_profile_empty_videos").assertIsDisplayed()

    // 3. DiscoverScreen creator card & video author clicks
    activeSlot.value = "discover"
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("discover_creator_card_user_bob").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("public_profile_username").assertTextEquals("@bob_v")

    activeSlot.value = "discover"
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("discover_results_list").performScrollToNode(hasTestTag("discover_video_author_vid_bob_pub_1"))
    composeTestRule.onNodeWithTag("discover_video_author_vid_bob_pub_1").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("public_profile_username").assertTextEquals("@bob_v")

    // 4. NotificationsScreen actor click marks read & opens Cara's public profile
    activeSlot.value = "notifications"
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("notification_item_notif_cara_1").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("public_profile_username").assertTextEquals("@cara_k")
    runBlocking {
      assertTrue(backend.observeNotifications(currentUserId).first().first().read)
    }

    // 5. Clicking own creator ID routes cleanly to own profile (never another user's view)
    openCreator(currentUserId)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("edit_profile_button").assertIsDisplayed()
    assertTrue(routeLog.contains(creatorBobId) && routeLog.contains(creatorCaraId))
  }

  @Test
  fun `public user profile follow syncs follower and following counts, hides on self, paginates videos, and shares deep link`() {
    val currentUserId = "user_alice"
    val creatorBobId = "user_bob"
    val manyPublicVideos =
      (1..28).map { index ->
        VideoPost(
          id = "vid_bob_page_$index",
          authorId = creatorBobId,
          authorUsername = "bob_v",
          caption = "Episode #$index",
          visibility = "public",
          status = "ready",
          playbackUrl = "https://res.cloudinary.com/idkq3e9l/video/upload/v$index/ep_$index.mp4",
          viewsCount = if (index == 1) 2000L else null,
          createdAt = Timestamp((3000 - index).toLong(), 0),
        )
      }
    val profiles =
      mapOf(
        currentUserId to
          UserPublicProfile(
            userId = currentUserId,
            username = "alice_v",
            displayName = "Alice",
            followingCount = 0L,
          ),
        creatorBobId to
          UserPublicProfile(
            userId = creatorBobId,
            username = "bob_v",
            displayName = "Bob Studio",
            bio = "Daily creative clips",
            followersCount = 0L,
            followingCount = 2L,
            videosCount = 28L,
          ),
      )
    val backend = InMemoryVynozaBackend(manyPublicVideos, profiles)

    // Helper checks for Cloudinary thumbnail rewrite & compact view count formatting
    assertEquals(
      "https://res.cloudinary.com/idkq3e9l/video/upload/so_0,w_480,c_fill,q_auto/v99/vynoza/videos/clip.jpg",
      resolveCloudinaryThumbnailUrl("https://res.cloudinary.com/idkq3e9l/video/upload/v99/vynoza/videos/clip.mp4"),
    )
    assertNull(resolveCloudinaryThumbnailUrl("https://example.com/plain.mp4"))
    assertEquals("1.5K", formatCompactCount(1500L))
    assertEquals("2K", formatCompactCount(2000L))
    assertEquals("1.2M", formatCompactCount(1_250_000L))

    // Profile share & deep link parser checks
    val bobProfile = profiles.getValue(creatorBobId)
    assertEquals("vynoza://user/user_bob", resolveShareableProfileUrl(bobProfile))
    assertTrue(buildProfileShareText(bobProfile)!!.contains("vynoza://user/user_bob"))
    assertEquals(
      creatorBobId,
      MainActivity.extractUserIdFromIntent(
        Intent(Intent.ACTION_VIEW, Uri.parse("vynoza://user/user_bob"))
      ),
    )

    val viewTarget = MutableStateFlow(creatorBobId)
    val bobPublicVm = PublicProfileViewModel(backend, backend, creatorBobId, currentUserId)
    val selfPublicVm = PublicProfileViewModel(backend, backend, currentUserId, currentUserId)

    composeTestRule.setContent {
      val target = viewTarget.collectAsState().value
      VynozaTheme {
        PublicProfileScreen(
          viewModel = if (target == creatorBobId) bobPublicVm else selfPublicVm,
          onBack = {},
        )
      }
    }
    composeTestRule.waitForIdle()

    // 1. Follow toggles cleanly and updates both Bob's followersCount and Alice's followingCount
    composeTestRule.onNodeWithTag("public_profile_follow_button").assertTextContains("Follow")
    composeTestRule.onNodeWithTag("public_profile_followers_count").assertTextContains("0")
    composeTestRule.onNodeWithTag("public_profile_follow_button").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("public_profile_follow_button").assertTextContains("Following")
    composeTestRule.onNodeWithTag("public_profile_followers_count").assertTextContains("1")
    runBlocking {
      assertEquals(1L, backend.observePublicProfile(currentUserId).first()!!.followingCount)
      assertEquals(1L, backend.observePublicProfile(creatorBobId).first()!!.followersCount)
    }

    // Unfollow decrements both back to 0
    composeTestRule.onNodeWithTag("public_profile_follow_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("public_profile_follow_button").assertTextContains("Follow")
    composeTestRule.onNodeWithTag("public_profile_followers_count").assertTextContains("0")
    runBlocking {
      assertEquals(0L, backend.observePublicProfile(currentUserId).first()!!.followingCount)
    }

    // 2. Share profile launches native Sharesheet with vynoza://user/user_bob
    composeTestRule.onNodeWithTag("public_profile_share_button").performClick()
    composeTestRule.waitForIdle()
    val launchedShare = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
    assertNotNull(launchedShare)
    assertEquals(Intent.ACTION_CHOOSER, launchedShare!!.action)
    @Suppress("DEPRECATION")
    val wrappedSend = launchedShare.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
    assertTrue(wrappedSend!!.getStringExtra(Intent.EXTRA_TEXT)!!.contains("vynoza://user/user_bob"))

    // 3. Pagination: first page loads 24 items, Load More fetches all 28
    composeTestRule.onNodeWithTag("public_profile_grid").performScrollToNode(hasTestTag("public_profile_video_tile_vid_bob_page_1"))
    composeTestRule.onNodeWithTag("public_profile_video_tile_vid_bob_page_1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("public_profile_tile_views_count_vid_bob_page_1", useUnmergedTree = true).assertTextEquals("2K")
    composeTestRule.onNodeWithTag("public_profile_video_tile_vid_bob_page_28").assertDoesNotExist()

    composeTestRule.onNodeWithTag("public_profile_grid").performScrollToNode(hasTestTag("public_profile_video_tile_vid_bob_page_24"))
    composeTestRule.waitForIdle()
    val loadMoreNodes = composeTestRule.onAllNodes(hasTestTag("public_profile_load_more_button")).fetchSemanticsNodes()
    if (loadMoreNodes.isNotEmpty()) {
      composeTestRule.onNodeWithTag("public_profile_load_more_button").performClick()
      composeTestRule.waitForIdle()
    }
    composeTestRule.onNodeWithTag("public_profile_grid").performScrollToNode(hasTestTag("public_profile_video_tile_vid_bob_page_28"))
    composeTestRule.onNodeWithTag("public_profile_video_tile_vid_bob_page_28").assertIsDisplayed()

    // 4. Viewing own profile via PublicProfileScreen hides Follow button
    viewTarget.value = currentUserId
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("public_profile_follow_button").assertDoesNotExist()
  }

  @Test
  fun `followers count updates across Home, Discover, PublicProfile, and ProfileScreen with duplicate guard and unfollow`() {
    val accountA = "user_alpha"
    val accountB = "user_beta"
    val accountC = "user_charlie"
    val bobVideo =
      VideoPost(
        id = "vid_bob_follow_test",
        authorId = accountB,
        authorUsername = "bob_v",
        caption = "Bob showcase clip",
        visibility = "public",
        status = "ready",
        playbackUrl = null,
        createdAt = Timestamp.now(),
      )
    val profiles =
      mapOf(
        accountA to UserPublicProfile(userId = accountA, username = "alice_v", displayName = "Alice"),
        accountB to UserPublicProfile(userId = accountB, username = "bob_v", displayName = "Bob"),
        accountC to UserPublicProfile(userId = accountC, username = "cara_k", displayName = "Cara"),
      )
    val backend = InMemoryVynozaBackend(listOf(bobVideo), profiles)

    val activeSlot = MutableStateFlow("home_a")
    val homeVmA = HomeViewModel(backend, backend, accountA)
    val discoverVmC = DiscoverViewModel(backend, backend, accountC)
    val profileVmB = ProfileViewModel(backend, backend, accountB)
    var publicBobFromA = PublicProfileViewModel(backend, backend, accountB, accountA)

    composeTestRule.setContent {
      val slot = activeSlot.collectAsState().value
      VynozaTheme {
        when (slot) {
          "home_a" -> HomeScreen(viewModel = homeVmA, onNavigateToCreate = {}, isScreenVisible = false)
          "discover_c" -> DiscoverScreen(viewModel = discoverVmC)
          "profile_b" -> ProfileScreen(viewModel = profileVmB, onSignOut = {})
          "public_b_from_a" -> PublicProfileScreen(viewModel = publicBobFromA, onBack = {})
        }
      }
    }
    composeTestRule.waitForIdle()

    // 1. Account A follows Account B from Home quick-follow button
    composeTestRule.onNodeWithTag("home_follow_button_vid_bob_follow_test").performClick()
    composeTestRule.waitForIdle()
    runBlocking {
      assertEquals(1L, backend.observePublicProfile(accountA).first()!!.followingCount)
      assertEquals(1L, backend.observePublicProfile(accountB).first()!!.followersCount)
      assertEquals(1, backend.observeFollowers(accountB).first().size)
    }

    // Account B's own ProfileScreen reflects Followers = 1 immediately
    activeSlot.value = "profile_b"
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("profile_followers_count").assertTextContains("1")

    // 2. Account C follows Account B from Discover with rapid-tap duplicate protection (1 -> 2)
    activeSlot.value = "discover_c"
    composeTestRule.waitForIdle()
    val gate = CompletableDeferred<Unit>()
    backend.followGate = gate
    val callsBeforeDiscoverFollow = backend.followCalls
    composeTestRule.onNodeWithTag("discover_follow_button_$accountB").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("discover_follow_button_$accountB").assertIsNotEnabled()
    discoverVmC.toggleFollow(profiles.getValue(accountB), currentlyFollowing = false, currentUsername = "cara_k")
    assertEquals(callsBeforeDiscoverFollow + 1, backend.followCalls)

    gate.complete(Unit)
    backend.followGate = null
    composeTestRule.waitForIdle()

    // Revisit Account B's own ProfileScreen -> Followers = 2
    activeSlot.value = "profile_b"
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("profile_followers_count").assertTextContains("2")

    // Open Account B's PublicProfileScreen from Account A -> shows 2 followers and Following button
    publicBobFromA = PublicProfileViewModel(backend, backend, accountB, accountA)
    activeSlot.value = "public_b_from_a"
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("public_profile_followers_count").assertTextContains("2")
    composeTestRule.onNodeWithTag("public_profile_follow_button").assertTextContains("Following")

    // 3. Account A unfollows Account B -> decrements from 2 -> 1
    composeTestRule.onNodeWithTag("public_profile_follow_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("public_profile_followers_count").assertTextContains("1")
    composeTestRule.onNodeWithTag("public_profile_follow_button").assertTextContains("Follow")
    runBlocking {
      assertEquals(0L, backend.observePublicProfile(accountA).first()!!.followingCount)
      assertEquals(1L, backend.observePublicProfile(accountB).first()!!.followersCount)
      assertEquals(1, backend.observeFollowers(accountB).first().size)
    }

    // Account B's ProfileScreen also decrements to 1
    activeSlot.value = "profile_b"
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("profile_followers_count").assertTextContains("1")

    // 4. Stale users_public.followersCount = 0 still displays real /followers subcollection count when edge exists
    val staleBackend =
      InMemoryVynozaBackend(
        listOf(bobVideo),
        mapOf(
          accountA to UserPublicProfile(userId = accountA, username = "alice_v", displayName = "Alice"),
          accountB to UserPublicProfile(userId = accountB, username = "bob_v", displayName = "Bob", followersCount = 0L),
        ),
      )
    staleBackend.seedFollowerEdgeWithoutCounter(accountA, accountB, "alice_v")
    val staleProfileVmB = ProfileViewModel(staleBackend, staleBackend, accountB)
    val stalePublicVmB = PublicProfileViewModel(staleBackend, staleBackend, accountB, accountA)
    runBlocking {
      val ownData = (staleProfileVmB.profileState.first { it is com.vynoza.app.core.model.UiState.Success } as com.vynoza.app.core.model.UiState.Success).data
      val pubData = (stalePublicVmB.publicProfileState.first { it is com.vynoza.app.core.model.UiState.Success } as com.vynoza.app.core.model.UiState.Success).data
      assertEquals(1L, ownData.followersCount)
      assertEquals(1L, pubData.followersCount)
    }
  }
}


