const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated users cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users_public").where("discoverable", "==", true).get());
  await assertFails(unauthDb.collection("videos").where("visibility", "==", "public").get());
  await assertFails(
    unauthDb.collection("users_public").doc(ALICE_UID).set({
      userId: ALICE_UID,
      username: "alice",
      displayName: "Alice",
      bio: "",
      discoverable: true,
      followersCount: 0,
      followingCount: 0,
      videosCount: 0,
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Private account (/users/{uid}): owner-only read/write & PII isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 1000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice@vynoza.app",
      accountTier: "standard",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob cannot read or modify Alice's private PII document
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      email: "hijack@vynoza.app",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Alice cannot inject ghost fields or change immutable accountTier
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      accountTier: "admin",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Public profile (/users_public/{uid}): creation, partial update, Discover query, and negative bounds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 5000);

  // Reject inflated initial follower counts
  await assertFails(
    aliceDb.collection("users_public").doc(ALICE_UID).set({
      userId: ALICE_UID,
      username: "alice_v",
      displayName: "Alice",
      bio: "Creator",
      discoverable: true,
      followersCount: 9999,
      followingCount: 0,
      videosCount: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Reject @DocumentId "id" key in payload
  await assertFails(
    aliceDb.collection("users_public").doc(ALICE_UID).set({
      id: ALICE_UID,
      userId: ALICE_UID,
      username: "alice_v",
      displayName: "Alice",
      bio: "Creator",
      discoverable: true,
      followersCount: 0,
      followingCount: 0,
      videosCount: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Valid creation matching Kotlin UserPublicProfileRepository payload
  await assertSucceeds(
    aliceDb.collection("users_public").doc(ALICE_UID).set({
      userId: ALICE_UID,
      username: "alice_v",
      displayName: "Alice Vynoza",
      bio: "Short video creator",
      discoverable: true,
      followersCount: 0,
      followingCount: 0,
      videosCount: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Partial update by owner succeeds
  await assertSucceeds(
    aliceDb.collection("users_public").doc(ALICE_UID).update({
      displayName: "Alice V.",
      bio: "Filmmaker & creator",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Owner cannot spoof followersCount or tamper with createdAt
  await assertFails(
    aliceDb.collection("users_public").doc(ALICE_UID).update({
      followersCount: 500,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Bob cannot modify Alice's profile
  await assertFails(
    bobDb.collection("users_public").doc(ALICE_UID).update({
      bio: "Hacked",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Bob's aligned Discover query succeeds; unfiltered query fails
  await assertSucceeds(
    bobDb.collection("users_public").where("discoverable", "==", true).get()
  );
  await assertFails(bobDb.collection("users_public").get());
});

test("Videos (/videos/{videoId}): owner creation, visibility query alignment, and negative checks", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const now = new Date(Date.now() - 10000);
    await ctx.firestore().collection("users_public").doc(ALICE_UID).set({
      userId: ALICE_UID,
      username: "alice_v",
      displayName: "Alice",
      bio: "",
      discoverable: true,
      followersCount: 0,
      followingCount: 0,
      videosCount: 0,
      createdAt: now,
      updatedAt: now,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 2000);

  // Create public video metadata
  await assertSucceeds(
    aliceDb.collection("videos").doc("vid_001").set({
      authorId: ALICE_UID,
      authorUsername: "alice_v",
      caption: "First Vynoza clip",
      visibility: "public",
      status: "pending_storage",
      likesCount: 0,
      commentsCount: 0,
      sharesCount: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Create private video metadata
  await assertSucceeds(
    aliceDb.collection("videos").doc("vid_priv").set({
      authorId: ALICE_UID,
      authorUsername: "alice_v",
      caption: "Private draft",
      visibility: "private",
      status: "pending_storage",
      likesCount: 0,
      commentsCount: 0,
      sharesCount: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Reject future timestamp tampering
  await assertFails(
    aliceDb.collection("videos").doc("vid_future").set({
      authorId: ALICE_UID,
      authorUsername: "alice_v",
      caption: "Future",
      visibility: "public",
      status: "pending_storage",
      likesCount: 0,
      commentsCount: 0,
      sharesCount: 0,
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );

  // Alice publishes ready Cloudinary video post + increments videosCount atomically
  const batch = aliceDb.batch();
  batch.set(aliceDb.collection("videos").doc("vid_cloudinary_001"), {
    authorId: ALICE_UID,
    authorUsername: "alice_v",
    caption: "Live on VYNOZA",
    visibility: "public",
    status: "ready",
    storagePath: "vynoza/alice_clip_001",
    playbackUrl: "https://res.cloudinary.com/idkq3e9l/video/upload/v1732150000/vynoza/alice_clip_001.mp4",
    likesCount: 0,
    commentsCount: 0,
    sharesCount: 0,
    createdAt: now,
    updatedAt: now,
  });
  batch.update(aliceDb.collection("users_public").doc(ALICE_UID), {
    videosCount: 1,
    updatedAt: now,
  });
  await assertSucceeds(batch.commit());

  // Bob can query public videos and sees Alice's published Cloudinary post
  const bobFeedSnap = await assertSucceeds(
    bobDb.collection("videos").where("visibility", "==", "public").get()
  );
  const publicIds = bobFeedSnap.docs.map((d) => d.id);
  if (!publicIds.includes("vid_cloudinary_001")) {
    throw new Error("Expected second authenticated user to see published public video");
  }

  // Bob cannot read Alice's private draft, cannot run unfiltered list
  await assertFails(bobDb.collection("videos").doc("vid_priv").get());
  await assertFails(bobDb.collection("videos").get());

  // Alice can query her own videos on Profile
  const aliceProfileSnap = await assertSucceeds(
    aliceDb.collection("videos").where("authorId", "==", ALICE_UID).get()
  );
  if (!aliceProfileSnap.docs.some((d) => d.id === "vid_cloudinary_001")) {
    throw new Error("Expected uploader profile query to return published video");
  }
});

test("Notifications & Following subcollections: strict owner isolation and parent existence", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const now = new Date(Date.now() - 10000);
    await ctx.firestore().collection("users_public").doc(ALICE_UID).set({
      userId: ALICE_UID,
      username: "alice_v",
      displayName: "Alice",
      bio: "",
      discoverable: true,
      followersCount: 0,
      followingCount: 0,
      videosCount: 0,
      createdAt: now,
      updatedAt: now,
    });
    await ctx.firestore().collection("users_public").doc(BOB_UID).set({
      userId: BOB_UID,
      username: "bob_v",
      displayName: "Bob",
      bio: "",
      discoverable: true,
      followersCount: 0,
      followingCount: 0,
      videosCount: 0,
      createdAt: now,
      updatedAt: now,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 1000);

  // Standalone uncoupled follow write without reciprocal /followers and followersCount must fail
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).collection("following").doc(ALICE_UID).set({
      followerId: BOB_UID,
      targetUserId: ALICE_UID,
      targetUsername: "alice_v",
      createdAt: now,
    })
  );

  // Bob atomically follows Alice (updates following, Alice's followers, and both counts)
  await assertSucceeds(
    bobDb.runTransaction(async (tx) => {
      const followingRef = bobDb.collection("users").doc(BOB_UID).collection("following").doc(ALICE_UID);
      const followerRef = bobDb.collection("users").doc(ALICE_UID).collection("followers").doc(BOB_UID);
      const bobRef = bobDb.collection("users_public").doc(BOB_UID);
      const aliceRef = bobDb.collection("users_public").doc(ALICE_UID);
      const bobSnap = await tx.get(bobRef);
      const aliceSnap = await tx.get(aliceRef);
      tx.set(followingRef, {
        followerId: BOB_UID,
        targetUserId: ALICE_UID,
        targetUsername: "alice_v",
        createdAt: now,
      });
      tx.set(followerRef, {
        followerId: BOB_UID,
        targetUserId: ALICE_UID,
        followerUsername: "bob_v",
        createdAt: now,
      });
      tx.update(bobRef, {
        followingCount: bobSnap.data().followingCount + 1,
        updatedAt: now,
      });
      tx.update(aliceRef, {
        followersCount: aliceSnap.data().followersCount + 1,
        updatedAt: now,
      });
    })
  );

  // Cannot follow self
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).collection("following").doc(BOB_UID).set({
      followerId: BOB_UID,
      targetUserId: BOB_UID,
      targetUsername: "bob_v",
      createdAt: now,
    })
  );

  // Bob creates follow notification for Alice
  await assertSucceeds(
    bobDb.collection("users").doc(ALICE_UID).collection("notifications").doc("notif_1").set({
      recipientId: ALICE_UID,
      actorId: BOB_UID,
      actorUsername: "bob_v",
      type: "follow",
      message: "@bob_v started following you",
      read: false,
      createdAt: now,
    })
  );

  // Bob cannot read Alice's notifications
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("notifications").get());

  // Alice can read & mark her notification as read
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("notifications").get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("notifications").doc("notif_1").update({
      read: true,
    })
  );
});

test("Video likes (/videos/{id}/likes & /users/{uid}/liked_videos): atomic transactions, multi-account consistency, and anti-spoofing", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const now = new Date(Date.now() - 10000);
    await ctx.firestore().collection("users_public").doc(ALICE_UID).set({
      userId: ALICE_UID,
      username: "alice_v",
      displayName: "Alice",
      bio: "",
      discoverable: true,
      followersCount: 0,
      followingCount: 0,
      videosCount: 2,
      createdAt: now,
      updatedAt: now,
    });
    await ctx.firestore().collection("users_public").doc(BOB_UID).set({
      userId: BOB_UID,
      username: "bob_v",
      displayName: "Bob",
      bio: "",
      discoverable: true,
      followersCount: 0,
      followingCount: 0,
      videosCount: 0,
      createdAt: now,
      updatedAt: now,
    });
    await ctx.firestore().collection("videos").doc("vid_public").set({
      authorId: ALICE_UID,
      authorUsername: "alice_v",
      caption: "Public clip",
      visibility: "public",
      status: "ready",
      storagePath: "vynoza/videos/pub",
      playbackUrl: "https://res.cloudinary.com/idkq3e9l/video/upload/v1/pub.mp4",
      likesCount: 0,
      commentsCount: 0,
      sharesCount: 0,
      createdAt: now,
      updatedAt: now,
    });
    await ctx.firestore().collection("videos").doc("vid_private").set({
      authorId: ALICE_UID,
      authorUsername: "alice_v",
      caption: "Private clip",
      visibility: "private",
      status: "ready",
      storagePath: "vynoza/videos/priv",
      playbackUrl: "https://res.cloudinary.com/idkq3e9l/video/upload/v1/priv.mp4",
      likesCount: 0,
      commentsCount: 0,
      sharesCount: 0,
      createdAt: now,
      updatedAt: now,
    });
  });

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  async function toggleLikeTx(db, uid, videoId) {
    const videoRef = db.collection("videos").doc(videoId);
    const videoLikeRef = videoRef.collection("likes").doc(uid);
    const userLikedRef = db.collection("users").doc(uid).collection("liked_videos").doc(videoId);
    return db.runTransaction(async (tx) => {
      const videoSnap = await tx.get(videoRef);
      const videoLikeSnap = await tx.get(videoLikeRef);
      const userLikedSnap = await tx.get(userLikedRef);
      const currentLikes = videoSnap.data().likesCount;
      const now = new Date(Date.now() - 500);
      if (videoLikeSnap.exists) {
        tx.delete(videoLikeRef);
        if (userLikedSnap.exists) {
          tx.delete(userLikedRef);
        }
        tx.update(videoRef, { likesCount: currentLikes - 1, updatedAt: now });
        return false;
      } else {
        const payload = { videoId, userId: uid, createdAt: now };
        tx.set(videoLikeRef, payload);
        tx.set(userLikedRef, payload);
        tx.update(videoRef, { likesCount: currentLikes + 1, updatedAt: now });
        return true;
      }
    });
  }

  // Unauthenticated user cannot like or read liked_videos
  await assertFails(
    unauthDb.collection("videos").doc("vid_public").collection("likes").doc("anon").set({
      videoId: "vid_public",
      userId: "anon",
      createdAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("liked_videos").get());

  // Cannot create standalone like doc without updating likesCount + liked_videos
  await assertFails(
    bobDb.collection("videos").doc("vid_public").collection("likes").doc(BOB_UID).set({
      videoId: "vid_public",
      userId: BOB_UID,
      createdAt: new Date(Date.now() - 500),
    })
  );

  // Cannot inflate likesCount directly without atomic like record
  await assertFails(
    bobDb.collection("videos").doc("vid_public").update({
      likesCount: 5,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Account A (Alice) likes her own public video (0 -> 1)
  const aliceLiked = await assertSucceeds(toggleLikeTx(aliceDb, ALICE_UID, "vid_public"));
  if (aliceLiked !== true) throw new Error("Expected Alice like to succeed");

  // Duplicate +1 transaction when Alice already liked must fail
  await assertFails(
    aliceDb.runTransaction(async (tx) => {
      const videoRef = aliceDb.collection("videos").doc("vid_public");
      const snap = await tx.get(videoRef);
      const now = new Date(Date.now() - 200);
      const payload = { videoId: "vid_public", userId: ALICE_UID, createdAt: now };
      tx.set(videoRef.collection("likes").doc(ALICE_UID), payload);
      tx.set(aliceDb.collection("users").doc(ALICE_UID).collection("liked_videos").doc("vid_public"), payload);
      tx.update(videoRef, { likesCount: snap.data().likesCount + 1, updatedAt: now });
    })
  );

  // Account B (Bob) likes Alice's public video (1 -> 2)
  const bobLiked = await assertSucceeds(toggleLikeTx(bobDb, BOB_UID, "vid_public"));
  if (bobLiked !== true) throw new Error("Expected Bob like to succeed");

  // Both Account A and Account B observe consistent likesCount == 2 and their own liked_videos list
  const aliceVideoSnap = await assertSucceeds(aliceDb.collection("videos").doc("vid_public").get());
  const bobVideoSnap = await assertSucceeds(bobDb.collection("videos").doc("vid_public").get());
  if (aliceVideoSnap.data().likesCount !== 2 || bobVideoSnap.data().likesCount !== 2) {
    throw new Error(`Expected consistent likesCount 2, got ${aliceVideoSnap.data().likesCount} / ${bobVideoSnap.data().likesCount}`);
  }
  const aliceLikedList = await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("liked_videos").get()
  );
  const bobLikedList = await assertSucceeds(
    bobDb.collection("users").doc(BOB_UID).collection("liked_videos").get()
  );
  if (!aliceLikedList.docs.some((d) => d.id === "vid_public") || !bobLikedList.docs.some((d) => d.id === "vid_public")) {
    throw new Error("Expected both accounts' liked_videos subcollections to contain vid_public");
  }

  // Cross-user isolation: Bob cannot read Alice's liked_videos, forge Alice's like, or delete Alice's like
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("liked_videos").get());
  await assertFails(
    bobDb.collection("videos").doc("vid_public").collection("likes").doc(ALICE_UID).delete()
  );

  // Bob cannot like Alice's private video, whereas Alice can like her own private video
  await assertFails(toggleLikeTx(bobDb, BOB_UID, "vid_private"));
  await assertSucceeds(toggleLikeTx(aliceDb, ALICE_UID, "vid_private"));

  // Unlike flow: Alice unlikes (2 -> 1), then Bob unlikes (1 -> 0)
  const aliceUnliked = await assertSucceeds(toggleLikeTx(aliceDb, ALICE_UID, "vid_public"));
  const bobUnliked = await assertSucceeds(toggleLikeTx(bobDb, BOB_UID, "vid_public"));
  if (aliceUnliked !== false || bobUnliked !== false) {
    throw new Error("Expected both unlikes to return false");
  }

  const finalVideoSnap = await assertSucceeds(bobDb.collection("videos").doc("vid_public").get());
  if (finalVideoSnap.data().likesCount !== 0) {
    throw new Error(`Expected final likesCount == 0, got ${finalVideoSnap.data().likesCount}`);
  }

  // Cannot decrement likesCount below 0
  await assertFails(
    bobDb.collection("videos").doc("vid_public").update({
      likesCount: -1,
      updatedAt: new Date(Date.now() - 100),
    })
  );
});

test("Video comments (/videos/{id}/comments): atomic transactions, author ownership, empty rejection, and private isolation", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const now = new Date(Date.now() - 10000);
    await ctx.firestore().collection("users_public").doc(ALICE_UID).set({
      userId: ALICE_UID,
      username: "alice_v",
      displayName: "Alice",
      bio: "",
      avatarUrl: "https://example.com/alice.png",
      discoverable: true,
      followersCount: 0,
      followingCount: 0,
      videosCount: 2,
      createdAt: now,
      updatedAt: now,
    });
    await ctx.firestore().collection("users_public").doc(BOB_UID).set({
      userId: BOB_UID,
      username: "bob_v",
      displayName: "Bob",
      bio: "",
      discoverable: true,
      followersCount: 0,
      followingCount: 0,
      videosCount: 0,
      createdAt: now,
      updatedAt: now,
    });
    await ctx.firestore().collection("videos").doc("vid_public").set({
      authorId: ALICE_UID,
      authorUsername: "alice_v",
      caption: "Public clip",
      visibility: "public",
      status: "ready",
      storagePath: "vynoza/videos/pub",
      playbackUrl: "https://res.cloudinary.com/idkq3e9l/video/upload/v1/pub.mp4",
      likesCount: 0,
      commentsCount: 0,
      sharesCount: 0,
      createdAt: now,
      updatedAt: now,
    });
    await ctx.firestore().collection("videos").doc("vid_private").set({
      authorId: ALICE_UID,
      authorUsername: "alice_v",
      caption: "Private clip",
      visibility: "private",
      status: "ready",
      storagePath: "vynoza/videos/priv",
      playbackUrl: "https://res.cloudinary.com/idkq3e9l/video/upload/v1/priv.mp4",
      likesCount: 0,
      commentsCount: 0,
      sharesCount: 0,
      createdAt: now,
      updatedAt: now,
    });
  });

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  async function addCommentTx(db, uid, username, displayName, avatarUrl, videoId, commentId, text) {
    const videoRef = db.collection("videos").doc(videoId);
    const commentRef = videoRef.collection("comments").doc(commentId);
    return db.runTransaction(async (tx) => {
      const videoSnap = await tx.get(videoRef);
      const currentComments = videoSnap.data().commentsCount;
      const now = new Date(Date.now() - 400);
      const payload = {
        videoId,
        authorId: uid,
        authorUsername: username,
        authorDisplayName: displayName,
        text,
        createdAt: now,
      };
      if (avatarUrl) {
        payload.authorAvatarUrl = avatarUrl;
      }
      tx.set(commentRef, payload);
      tx.update(videoRef, {
        commentsCount: currentComments + 1,
        lastCommentId: commentId,
        updatedAt: now,
      });
    });
  }

  async function deleteCommentTx(db, videoId, commentId) {
    const videoRef = db.collection("videos").doc(videoId);
    const commentRef = videoRef.collection("comments").doc(commentId);
    return db.runTransaction(async (tx) => {
      const videoSnap = await tx.get(videoRef);
      await tx.get(commentRef);
      const currentComments = videoSnap.data().commentsCount;
      const now = new Date(Date.now() - 200);
      tx.delete(commentRef);
      tx.update(videoRef, {
        commentsCount: currentComments - 1,
        lastCommentId: commentId,
        updatedAt: now,
      });
    });
  }

  // Unauthenticated cannot read or comment
  await assertFails(unauthDb.collection("videos").doc("vid_public").collection("comments").get());
  await assertFails(
    unauthDb.collection("videos").doc("vid_public").collection("comments").doc("cmt_anon").set({
      videoId: "vid_public",
      authorId: "anon",
      authorUsername: "anon_user",
      authorDisplayName: "Anon",
      text: "Hello",
      createdAt: new Date(Date.now() - 500),
    })
  );

  // Uncoupled comment write without atomic commentsCount update must fail
  await assertFails(
    bobDb.collection("videos").doc("vid_public").collection("comments").doc("cmt_uncoupled").set({
      videoId: "vid_public",
      authorId: BOB_UID,
      authorUsername: "bob_v",
      authorDisplayName: "Bob",
      text: "Should fail",
      createdAt: new Date(Date.now() - 500),
    })
  );

  // Direct commentsCount manipulation without creating a comment must fail
  await assertFails(
    bobDb.collection("videos").doc("vid_public").update({
      commentsCount: 42,
      lastCommentId: "cmt_fake",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Blank or whitespace-only comment must fail
  await assertFails(
    addCommentTx(bobDb, BOB_UID, "bob_v", "Bob", null, "vid_public", "cmt_blank", "   \n  ")
  );

  // Account A (Alice) comments on her own video (0 -> 1)
  await assertSucceeds(
    addCommentTx(
      aliceDb,
      ALICE_UID,
      "alice_v",
      "Alice",
      "https://example.com/alice.png",
      "vid_public",
      "cmt_alice_1",
      "First clip on VYNOZA!"
    )
  );

  // Account B (Bob) comments on Alice's public video (1 -> 2)
  await assertSucceeds(
    addCommentTx(
      bobDb,
      BOB_UID,
      "bob_v",
      "Bob",
      null,
      "vid_public",
      "cmt_bob_1",
      "Love the lighting!"
    )
  );

  // Both accounts read consistent comments list and commentsCount == 2
  const bobCommentsSnap = await assertSucceeds(
    bobDb.collection("videos").doc("vid_public").collection("comments").get()
  );
  if (bobCommentsSnap.docs.length !== 2) {
    throw new Error(`Expected 2 comments, got ${bobCommentsSnap.docs.length}`);
  }
  const aliceVideoSnap = await assertSucceeds(aliceDb.collection("videos").doc("vid_public").get());
  if (aliceVideoSnap.data().commentsCount !== 2) {
    throw new Error(`Expected commentsCount == 2, got ${aliceVideoSnap.data().commentsCount}`);
  }

  // Comments are immutable (cannot update another user's or own comment directly)
  await assertFails(
    bobDb.collection("videos").doc("vid_public").collection("comments").doc("cmt_alice_1").update({
      text: "Hijacked",
    })
  );
  await assertFails(
    bobDb.collection("videos").doc("vid_public").collection("comments").doc("cmt_bob_1").update({
      text: "Edited",
    })
  );

  // Cross-user deletion must fail: Bob cannot delete Alice's comment, and video owner Alice cannot delete Bob's comment
  await assertFails(deleteCommentTx(bobDb, "vid_public", "cmt_alice_1"));
  await assertFails(deleteCommentTx(aliceDb, "vid_public", "cmt_bob_1"));

  // Private video isolation: Bob cannot read or comment on Alice's private video
  await assertFails(bobDb.collection("videos").doc("vid_private").collection("comments").get());
  await assertFails(
    addCommentTx(bobDb, BOB_UID, "bob_v", "Bob", null, "vid_private", "cmt_bob_priv", "Sneaky")
  );

  // Owner deletion succeeds atomically: Bob deletes cmt_bob_1 (2 -> 1), Alice deletes cmt_alice_1 (1 -> 0)
  await assertSucceeds(deleteCommentTx(bobDb, "vid_public", "cmt_bob_1"));
  await assertSucceeds(deleteCommentTx(aliceDb, "vid_public", "cmt_alice_1"));

  const finalVideoSnap = await assertSucceeds(bobDb.collection("videos").doc("vid_public").get());
  if (finalVideoSnap.data().commentsCount !== 0) {
    throw new Error(`Expected final commentsCount == 0, got ${finalVideoSnap.data().commentsCount}`);
  }
});

test("Saved videos (/users/{uid}/saved_videos/{videoId}): owner-only read/write, private isolation, and removal", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const now = new Date(Date.now() - 10000);
    await ctx.firestore().collection("users_public").doc(ALICE_UID).set({
      userId: ALICE_UID,
      username: "alice_v",
      displayName: "Alice",
      bio: "",
      discoverable: true,
      followersCount: 0,
      followingCount: 0,
      videosCount: 2,
      createdAt: now,
      updatedAt: now,
    });
    await ctx.firestore().collection("users_public").doc(BOB_UID).set({
      userId: BOB_UID,
      username: "bob_v",
      displayName: "Bob",
      bio: "",
      discoverable: true,
      followersCount: 0,
      followingCount: 0,
      videosCount: 0,
      createdAt: now,
      updatedAt: now,
    });
    await ctx.firestore().collection("videos").doc("vid_public").set({
      authorId: ALICE_UID,
      authorUsername: "alice_v",
      caption: "Public clip",
      visibility: "public",
      status: "ready",
      storagePath: "vynoza/videos/pub",
      playbackUrl: "https://res.cloudinary.com/idkq3e9l/video/upload/v1/pub.mp4",
      likesCount: 0,
      commentsCount: 0,
      sharesCount: 0,
      createdAt: now,
      updatedAt: now,
    });
    await ctx.firestore().collection("videos").doc("vid_private").set({
      authorId: ALICE_UID,
      authorUsername: "alice_v",
      caption: "Private clip",
      visibility: "private",
      status: "ready",
      storagePath: "vynoza/videos/priv",
      playbackUrl: "https://res.cloudinary.com/idkq3e9l/video/upload/v1/priv.mp4",
      likesCount: 0,
      commentsCount: 0,
      sharesCount: 0,
      createdAt: now,
      updatedAt: now,
    });
  });

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 500);

  // Unauthenticated cannot read or write saved videos
  await assertFails(unauthDb.collection("users").doc(BOB_UID).collection("saved_videos").get());
  await assertFails(
    unauthDb.collection("users").doc(BOB_UID).collection("saved_videos").doc("vid_public").set({
      videoId: "vid_public",
      userId: BOB_UID,
      createdAt: now,
    })
  );

  // Bob can save Alice's public video
  await assertSucceeds(
    bobDb.collection("users").doc(BOB_UID).collection("saved_videos").doc("vid_public").set({
      videoId: "vid_public",
      userId: BOB_UID,
      createdAt: now,
    })
  );

  // Saved record is immutable (cannot update/tamper directly)
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).collection("saved_videos").doc("vid_public").update({
      videoId: "vid_private",
    })
  );

  // Cross-user privacy: Alice cannot read, add to, or delete from Bob's saved list
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("saved_videos").get());
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("saved_videos").doc("vid_public").get()
  );
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("saved_videos").doc("vid_public").delete()
  );
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("saved_videos").doc("vid_private").set({
      videoId: "vid_private",
      userId: BOB_UID,
      createdAt: now,
    })
  );

  // Bob cannot save Alice's private video or a nonexistent video
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).collection("saved_videos").doc("vid_private").set({
      videoId: "vid_private",
      userId: BOB_UID,
      createdAt: now,
    })
  );
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).collection("saved_videos").doc("vid_missing").set({
      videoId: "vid_missing",
      userId: BOB_UID,
      createdAt: now,
    })
  );

  // Owner can list their saved videos and remove them
  const bobSaved = await assertSucceeds(
    bobDb.collection("users").doc(BOB_UID).collection("saved_videos").get()
  );
  if (bobSaved.docs.length !== 1 || bobSaved.docs[0].id !== "vid_public") {
    throw new Error(`Expected 1 saved video vid_public, got ${bobSaved.docs.length}`);
  }

  await assertSucceeds(
    bobDb.collection("users").doc(BOB_UID).collection("saved_videos").doc("vid_public").delete()
  );
  const bobSavedAfterDelete = await assertSucceeds(
    bobDb.collection("users").doc(BOB_UID).collection("saved_videos").get()
  );
  if (bobSavedAfterDelete.docs.length !== 0) {
    throw new Error(`Expected 0 saved videos after delete, got ${bobSavedAfterDelete.docs.length}`);
  }
});

test("Public user profile (/users_public/{uid} & /videos): public video query isolation, atomic follower count sync, and edit protection", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const now = new Date(Date.now() - 10000);
    await ctx.firestore().collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice_private@vynoza.app",
      accountTier: "standard",
      createdAt: now,
      updatedAt: now,
    });
    await ctx.firestore().collection("users_public").doc(ALICE_UID).set({
      userId: ALICE_UID,
      username: "alice_v",
      displayName: "Alice Creator",
      bio: "Filmmaker",
      discoverable: true,
      followersCount: 0,
      followingCount: 0,
      videosCount: 2,
      createdAt: now,
      updatedAt: now,
    });
    await ctx.firestore().collection("users_public").doc(BOB_UID).set({
      userId: BOB_UID,
      username: "bob_v",
      displayName: "Bob Viewer",
      bio: "",
      discoverable: true,
      followersCount: 0,
      followingCount: 0,
      videosCount: 0,
      createdAt: now,
      updatedAt: now,
    });
    await ctx.firestore().collection("videos").doc("vid_alice_pub").set({
      authorId: ALICE_UID,
      authorUsername: "alice_v",
      caption: "Public showcase",
      visibility: "public",
      status: "ready",
      storagePath: "vynoza/videos/pub",
      playbackUrl: "https://res.cloudinary.com/idkq3e9l/video/upload/v1/pub.mp4",
      likesCount: 0,
      commentsCount: 0,
      sharesCount: 0,
      viewsCount: 120,
      createdAt: now,
      updatedAt: now,
    });
    await ctx.firestore().collection("videos").doc("vid_alice_priv").set({
      authorId: ALICE_UID,
      authorUsername: "alice_v",
      caption: "Secret draft",
      visibility: "private",
      status: "ready",
      storagePath: "vynoza/videos/priv",
      playbackUrl: "https://res.cloudinary.com/idkq3e9l/video/upload/v1/priv.mp4",
      likesCount: 0,
      commentsCount: 0,
      sharesCount: 0,
      createdAt: now,
      updatedAt: now,
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Bob can read Alice's public profile but cannot read Alice's private /users/{uid} PII
  const alicePubSnap = await assertSucceeds(bobDb.collection("users_public").doc(ALICE_UID).get());
  if (alicePubSnap.data().username !== "alice_v") {
    throw new Error("Expected Bob to read Alice's public profile");
  }
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());

  // Bob can query Alice's public videos and receives ONLY public videos
  const alicePublicVideos = await assertSucceeds(
    bobDb
      .collection("videos")
      .where("authorId", "==", ALICE_UID)
      .where("visibility", "==", "public")
      .get()
  );
  if (
    alicePublicVideos.docs.length !== 1 ||
    alicePublicVideos.docs[0].id !== "vid_alice_pub"
  ) {
    throw new Error(`Expected only vid_alice_pub, got ${alicePublicVideos.docs.map((d) => d.id)}`);
  }

  // Bob CANNOT query Alice's videos without visibility == 'public' filter
  await assertFails(
    bobDb.collection("videos").where("authorId", "==", ALICE_UID).get()
  );

  // Bob CANNOT edit Alice's public profile or spoof followersCount standalone
  await assertFails(
    bobDb.collection("users_public").doc(ALICE_UID).update({
      bio: "Unauthorized bio edit",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    bobDb.collection("users_public").doc(ALICE_UID).update({
      followersCount: 1,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Bob atomically follows Alice -> Bob.followingCount (0 -> 1), Alice.followersCount (0 -> 1), and both edges exist
  await assertSucceeds(
    bobDb.runTransaction(async (tx) => {
      const followingRef = bobDb.collection("users").doc(BOB_UID).collection("following").doc(ALICE_UID);
      const followerRef = bobDb.collection("users").doc(ALICE_UID).collection("followers").doc(BOB_UID);
      const bobRef = bobDb.collection("users_public").doc(BOB_UID);
      const aliceRef = bobDb.collection("users_public").doc(ALICE_UID);
      const followingSnap = await tx.get(followingRef);
      const followerSnap = await tx.get(followerRef);
      const bobSnap = await tx.get(bobRef);
      const aliceSnap = await tx.get(aliceRef);
      if (followingSnap.exists || followerSnap.exists) throw new Error("Unexpected existing edge");
      const now = new Date(Date.now() - 300);
      tx.set(followingRef, {
        followerId: BOB_UID,
        targetUserId: ALICE_UID,
        targetUsername: "alice_v",
        createdAt: now,
      });
      tx.set(followerRef, {
        followerId: BOB_UID,
        targetUserId: ALICE_UID,
        followerUsername: "bob_v",
        createdAt: now,
      });
      tx.update(bobRef, {
        followingCount: bobSnap.data().followingCount + 1,
        updatedAt: now,
      });
      tx.update(aliceRef, {
        followersCount: aliceSnap.data().followersCount + 1,
        updatedAt: now,
      });
    })
  );

  const aliceAfterFollow = await assertSucceeds(bobDb.collection("users_public").doc(ALICE_UID).get());
  const bobAfterFollow = await assertSucceeds(bobDb.collection("users_public").doc(BOB_UID).get());
  const aliceFollowersAfterFollow = await assertSucceeds(
    bobDb.collection("users").doc(ALICE_UID).collection("followers").get()
  );
  if (
    aliceAfterFollow.data().followersCount !== 1 ||
    bobAfterFollow.data().followingCount !== 1 ||
    aliceFollowersAfterFollow.docs.length !== 1
  ) {
    throw new Error("Expected synchronized followersCount == 1, followers edge == 1, and followingCount == 1");
  }

  // Bob atomically unfollows Alice -> both edges deleted and both counts return to 0
  await assertSucceeds(
    bobDb.runTransaction(async (tx) => {
      const followingRef = bobDb.collection("users").doc(BOB_UID).collection("following").doc(ALICE_UID);
      const followerRef = bobDb.collection("users").doc(ALICE_UID).collection("followers").doc(BOB_UID);
      const bobRef = bobDb.collection("users_public").doc(BOB_UID);
      const aliceRef = bobDb.collection("users_public").doc(ALICE_UID);
      await tx.get(followingRef);
      await tx.get(followerRef);
      const bobSnap = await tx.get(bobRef);
      const aliceSnap = await tx.get(aliceRef);
      const now = new Date(Date.now() - 200);
      tx.delete(followingRef);
      tx.delete(followerRef);
      tx.update(bobRef, {
        followingCount: bobSnap.data().followingCount - 1,
        updatedAt: now,
      });
      tx.update(aliceRef, {
        followersCount: aliceSnap.data().followersCount - 1,
        updatedAt: now,
      });
    })
  );

  const aliceAfterUnfollow = await assertSucceeds(bobDb.collection("users_public").doc(ALICE_UID).get());
  const bobAfterUnfollow = await assertSucceeds(bobDb.collection("users_public").doc(BOB_UID).get());
  const aliceFollowersAfterUnfollow = await assertSucceeds(
    bobDb.collection("users").doc(ALICE_UID).collection("followers").get()
  );
  if (
    aliceAfterUnfollow.data().followersCount !== 0 ||
    bobAfterUnfollow.data().followingCount !== 0 ||
    aliceFollowersAfterUnfollow.docs.length !== 0
  ) {
    throw new Error("Expected synchronized followersCount == 0, followers edge == 0, and followingCount == 0 after unfollow");
  }
});

test("Followers subcollection (/users/{uid}/followers): reads, anti-spoofing, duplicate guard, and legacy unfollow preservation", async () => {
  const CARA_UID = "cara_uid_789";
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const now = new Date(Date.now() - 10000);
    await ctx.firestore().collection("users_public").doc(ALICE_UID).set({
      userId: ALICE_UID,
      username: "alice_v",
      displayName: "Alice",
      bio: "",
      discoverable: true,
      followersCount: 0,
      followingCount: 0,
      videosCount: 0,
      createdAt: now,
      updatedAt: now,
    });
    await ctx.firestore().collection("users_public").doc(BOB_UID).set({
      userId: BOB_UID,
      username: "bob_v",
      displayName: "Bob",
      bio: "",
      discoverable: true,
      followersCount: 0,
      followingCount: 1,
      videosCount: 0,
      createdAt: now,
      updatedAt: now,
    });
    // Legacy following edge without reciprocal /followers doc and with Alice followersCount == 0
    await ctx.firestore().collection("users").doc(BOB_UID).collection("following").doc(ALICE_UID).set({
      followerId: BOB_UID,
      targetUserId: ALICE_UID,
      targetUsername: "alice_v",
      createdAt: now,
    });
  });

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const caraDb = testEnv.authenticatedContext(CARA_UID).firestore();

  // Unauthenticated cannot read or write /followers
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("followers").get());

  // Legacy unfollow preserves cleanup: Bob can delete legacy /following edge when /followers didn't exist
  await assertSucceeds(
    bobDb.runTransaction(async (tx) => {
      const followingRef = bobDb.collection("users").doc(BOB_UID).collection("following").doc(ALICE_UID);
      const bobRef = bobDb.collection("users_public").doc(BOB_UID);
      const bobSnap = await tx.get(bobRef);
      tx.delete(followingRef);
      tx.update(bobRef, {
        followingCount: bobSnap.data().followingCount - 1,
        updatedAt: new Date(Date.now() - 500),
      });
    })
  );

  // Bob cannot write standalone /followers without following edge & followersCount + 1
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("followers").doc(BOB_UID).set({
      followerId: BOB_UID,
      targetUserId: ALICE_UID,
      followerUsername: "bob_v",
      createdAt: new Date(Date.now() - 500),
    })
  );

  // Bob cannot spoof follower doc as Cara
  await assertFails(
    bobDb.runTransaction(async (tx) => {
      const followingRef = bobDb.collection("users").doc(BOB_UID).collection("following").doc(ALICE_UID);
      const followerRef = bobDb.collection("users").doc(ALICE_UID).collection("followers").doc(CARA_UID);
      const aliceRef = bobDb.collection("users_public").doc(ALICE_UID);
      const aliceSnap = await tx.get(aliceRef);
      const now = new Date(Date.now() - 400);
      tx.set(followingRef, {
        followerId: BOB_UID,
        targetUserId: ALICE_UID,
        targetUsername: "alice_v",
        createdAt: now,
      });
      tx.set(followerRef, {
        followerId: CARA_UID,
        targetUserId: ALICE_UID,
        followerUsername: "cara_k",
        createdAt: now,
      });
      tx.update(aliceRef, {
        followersCount: aliceSnap.data().followersCount + 1,
        updatedAt: now,
      });
    })
  );

  // Valid atomic follow by Bob creates both sides & updates counts
  await assertSucceeds(
    bobDb.runTransaction(async (tx) => {
      const followingRef = bobDb.collection("users").doc(BOB_UID).collection("following").doc(ALICE_UID);
      const followerRef = bobDb.collection("users").doc(ALICE_UID).collection("followers").doc(BOB_UID);
      const bobRef = bobDb.collection("users_public").doc(BOB_UID);
      const aliceRef = bobDb.collection("users_public").doc(ALICE_UID);
      const bobSnap = await tx.get(bobRef);
      const aliceSnap = await tx.get(aliceRef);
      const now = new Date(Date.now() - 300);
      tx.set(followingRef, {
        followerId: BOB_UID,
        targetUserId: ALICE_UID,
        targetUsername: "alice_v",
        createdAt: now,
      });
      tx.set(followerRef, {
        followerId: BOB_UID,
        targetUserId: ALICE_UID,
        followerUsername: "bob_v",
        createdAt: now,
      });
      tx.update(bobRef, {
        followingCount: bobSnap.data().followingCount + 1,
        updatedAt: now,
      });
      tx.update(aliceRef, {
        followersCount: aliceSnap.data().followersCount + 1,
        updatedAt: now,
      });
    })
  );

  // Both Alice (owner) and Cara (authenticated viewer) can read Alice's /followers records
  const aliceRead = await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("followers").get());
  const caraRead = await assertSucceeds(caraDb.collection("users").doc(ALICE_UID).collection("followers").get());
  if (aliceRead.docs.length !== 1 || caraRead.docs.length !== 1) {
    throw new Error("Expected both Alice and Cara to read 1 follower record");
  }

  // Duplicate follow increment when follower edge already exists must fail
  await assertFails(
    bobDb.runTransaction(async (tx) => {
      const aliceRef = bobDb.collection("users_public").doc(ALICE_UID);
      const aliceSnap = await tx.get(aliceRef);
      tx.update(aliceRef, {
        followersCount: aliceSnap.data().followersCount + 1,
        updatedAt: new Date(Date.now() - 200),
      });
    })
  );
});





