package top.rootu.lampa

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hamcrest.CoreMatchers.containsString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import top.rootu.lampa.helpers.Prefs.appPlayer
import top.rootu.lampa.helpers.Prefs.appPrefs
import top.rootu.lampa.helpers.Prefs.appUrl
import top.rootu.lampa.helpers.Prefs.lastPlayedPrefs
import top.rootu.lampa.helpers.Prefs.nextEpisodeMode
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NextEpisodeJustPlayerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val launches = Collections.synchronizedList(mutableListOf<String>())
    private val launchTimes = Collections.synchronizedList(mutableListOf<Long>())
    private lateinit var launchLatch: CountDownLatch
    private lateinit var receiver: BroadcastReceiver
    private var mayClearTestPreferences = false

    @Before
    fun setUp() {
        // Fail before permissions, preference access, or Activity launch. @After also guards
        // itself because JUnit invokes it even when this @Before fails.
        check(context.packageName == "top.rootu.lampa.nexttest" && BuildConfig.BUILD_TYPE == "nextTest") {
            "NextEpisodeJustPlayerTest may run only against top.rootu.lampa.nexttest"
        }
        check(BuildConfig.testPlayerPackage == "top.rootu.lampa.fakeplayer") { "Fake player routing is disabled" }
        context.packageManager.getPackageInfo(BuildConfig.testPlayerPackage, 0)
        mayClearTestPreferences = true
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        context.appPrefs.edit().clear().commit()
        context.lastPlayedPrefs.edit().clear().commit()
        context.appUrl = "about:blank"
        context.appPlayer = BuildConfig.testPlayerPackage
        context.nextEpisodeMode = NextEpisodeMode.ASK
        MainActivity.playerAutoNext = true
        MainActivity.lampaActivity = activityFixture(999991, "Integration Test")

        launchLatch = CountDownLatch(2)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                launches += intent?.getStringExtra("url").orEmpty()
                launchTimes += SystemClock.elapsedRealtime()
                launchLatch.countDown()
            }
        }
        val filter = IntentFilter("top.rootu.lampa.fakeplayer.TEST_LAUNCH")
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    @After
    fun tearDown() {
        if (!mayClearTestPreferences || context.packageName != "top.rootu.lampa.nexttest") return
        runCatching { context.unregisterReceiver(receiver) }
        context.appPrefs.edit().clear().commit()
        context.lastPlayedPrefs.edit().clear().commit()
    }

    @Test
    fun completedJustPlayerEpisodeOffersAndLaunchesNextPlaylistItem() {
        val firstUrl = "https://example.invalid/show-s01e01.mp4"
        val secondUrl = "https://example.invalid/show-s01e02.mp4"
        val playlist = JSONArray()
            .put(JSONObject().put("url", firstUrl).put("title", "Episode 1"))
            .put(JSONObject().put("url", secondUrl).put("title", "Episode 2"))
        val request = JSONObject()
            .put("url", firstUrl)
            .put("title", "Episode 1")
            .put("playlist", playlist)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.runPlayer(request) }

            assertTrue("Just Player was not launched for the first episode", waitForLaunchCount(1))
            assertEquals(firstUrl, launches[0])
            assertTrue(
                "LAMPA did not resume after the first episode",
                waitForActivityResume(scenario)
            )
            onView(withText(R.string.next_episode_prompt_title)).check(matches(isDisplayed()))
            onView(withText(containsString("Episode 2"))).check(matches(isDisplayed()))

            // Activity can be RESUMED while Android is still removing the external player's
            // transition surface; wait until it no longer occludes injected remote/TV clicks.
            Thread.sleep(750L)
            onView(withText(R.string.next_episode_play)).perform(click())

            assertTrue("Just Player was not launched for the next episode", launchLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf(firstUrl, secondUrl), launches.toList())
            assertTrue(
                "LAMPA did not resume after the next episode",
                waitForActivityResume(scenario)
            )
            onView(withText(R.string.next_episode_prompt_title)).check(doesNotExist())
        }
    }

    @Test
    fun autoModeShowsCountdownAndLaunchesNextPlaylistItem() {
        context.nextEpisodeMode = NextEpisodeMode.AUTO
        val (request, firstUrl, secondUrl) = twoEpisodeRequest()
        launchLatch = CountDownLatch(1)
        var completionReportedAt = 0L

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue("LAMPA did not resume before synthetic completion", waitForActivityResume(scenario))
            assertTrue("LAMPA window did not become ready", waitForActivityWindowReady(scenario))
            scenario.onActivity { activity ->
                activityStateManager(activity).saveState(
                    activityJson = MainActivity.lampaActivity,
                    playlist = listOf(
                        PlayerStateManager.PlaylistItem(url = firstUrl, title = "Episode 1"),
                        PlayerStateManager.PlaylistItem(url = secondUrl, title = "Episode 2")
                    ),
                    currentUrl = request.getString("url")
                )
                val reportCompletion = MainActivity::class.java.getDeclaredMethod(
                    "handleMxPlayerResult", Intent::class.java,
                    Int::class.javaPrimitiveType, String::class.java
                ).apply { isAccessible = true }
                completionReportedAt = SystemClock.elapsedRealtime()
                reportCompletion.invoke(activity, Intent().putExtra("end_by", "playback_completion"), -1, firstUrl)
                // Inspect the newly created dialog on the same main callback.
                // Espresso focus synchronization can otherwise consume the entire countdown.
                val dialog = MainActivity::class.java.getDeclaredField("nextEpisodeDialog").apply {
                    isAccessible = true
                }.get(activity) as AlertDialog
                assertTrue("AUTO prompt was not shown", dialog.isShowing)
                val message = dialog.findViewById<TextView>(android.R.id.message)?.text.toString()
                assertTrue("AUTO prompt did not start at ten seconds", message.contains("10"))
                assertTrue("AUTO prompt did not identify the next episode", message.contains("Episode 2"))
                assertEquals(activity.getString(R.string.next_episode_play),
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            }

            val beforeTenSeconds = completionReportedAt + 10_000L - SystemClock.elapsedRealtime()
            assertTrue("Countdown observation started too late", beforeTenSeconds > 0)
            assertFalse("Auto mode launched before ten seconds", launchLatch.await(beforeTenSeconds, TimeUnit.MILLISECONDS))
            assertTrue("Auto mode did not launch the next episode", launchLatch.await(15, TimeUnit.SECONDS))
            assertEquals(listOf(secondUrl), launches.toList())
            assertTrue("Auto launch occurred before ten seconds", launchTimes[0] - completionReportedAt >= 10_000L)
        }
    }

    @Test
    fun autoModeDoesNotLaunchAfterActivityMovesToBackground() {
        context.nextEpisodeMode = NextEpisodeMode.AUTO
        val (request, firstUrl, _) = twoEpisodeRequest()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.runPlayer(request) }

            assertTrue("Just Player was not launched for the first episode", waitForLaunchCount(1))
            assertTrue("LAMPA did not resume after the first episode", waitForActivityResume(scenario))
            onView(withText(R.string.next_episode_prompt_title)).check(matches(isDisplayed()))

            scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(11_000L)

            assertEquals(listOf(firstUrl), launches.toList())
            scenario.moveToState(Lifecycle.State.RESUMED)
            onView(withText(R.string.next_episode_prompt_title)).check(doesNotExist())
            scenario.onActivity { activity ->
                assertTrue(activityStateManager(activity).getState(MainActivity.lampaActivity).playlist.isEmpty())
            }
            Thread.sleep(1_000L)
            assertEquals(listOf(firstUrl), launches.toList())
        }
    }

    @Test
    fun offModeDoesNotOfferOrLaunchNextPlaylistItem() {
        context.nextEpisodeMode = NextEpisodeMode.OFF
        val (request, firstUrl, _) = twoEpisodeRequest()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.runPlayer(request) }

            assertTrue("Just Player was not launched for the first episode", waitForLaunchCount(1))
            assertTrue("LAMPA did not resume after the first episode", waitForActivityResume(scenario))
            Thread.sleep(1_500L)
            onView(withText(R.string.next_episode_prompt_title)).check(doesNotExist())
            assertEquals(listOf(firstUrl), launches.toList())
        }
    }

    @Test
    fun lastPlaylistItemDoesNotOfferAnotherEpisode() {
        context.nextEpisodeMode = NextEpisodeMode.AUTO
        val onlyUrl = "https://example.invalid/show-s01e01.mp4"
        val playlist = JSONArray().put(
            JSONObject().put("url", onlyUrl).put("title", "Episode 1")
        )
        val request = JSONObject()
            .put("url", onlyUrl)
            .put("title", "Episode 1")
            .put("playlist", playlist)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.runPlayer(request) }

            assertTrue("Just Player was not launched", waitForLaunchCount(1))
            assertTrue("LAMPA did not resume after the episode", waitForActivityResume(scenario))
            Thread.sleep(11_000L)
            onView(withText(R.string.next_episode_prompt_title)).check(doesNotExist())
            assertEquals(listOf(onlyUrl), launches.toList())
            scenario.onActivity { activity ->
                assertTrue(activityStateManager(activity).getState(MainActivity.lampaActivity).playlist.isEmpty())
            }
        }
    }

    @Test
    fun nextEpisodeResetsSavedTimelineBeforeConfiguringPlayer() {
        MainActivity.playerTimeCode = "continue"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val nextItem = PlayerStateManager.PlaylistItem(
                    url = "https://example.invalid/show-s01e02.mp4",
                    timeline = PlayerStateManager.PlaylistItem.Timeline(
                        hash = "next-episode", time = 1_200.0, duration = 3_600.0, percent = 33
                    )
                )
                val prepare = MainActivity::class.java.getDeclaredMethod(
                    "prepareNextEpisodeState", List::class.java, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Map::class.java
                ).apply { isAccessible = true }
                val state = prepare.invoke(
                    activity, listOf(PlayerStateManager.PlaylistItem(url = "https://example.invalid/first.mp4"), nextItem),
                    1, 0, emptyMap<String, Any>()
                ) as PlayerStateManager.PlaybackState
                val playbackPosition = MainActivity::class.java.getDeclaredMethod(
                    "getPlaybackPosition", PlayerStateManager.PlaybackState::class.java
                ).apply { isAccessible = true }
                assertEquals(0L, state.currentPosition)
                assertEquals(0.0, state.currentItem?.timeline?.time)
                assertEquals(0, state.currentItem?.timeline?.percent)
                assertEquals("next-episode", state.currentItem?.timeline?.hash)
                assertEquals(3_600.0, state.currentItem?.timeline?.duration)
                assertEquals(0L, playbackPosition.invoke(activity, state))
            }
        }
    }

    private fun activityStateManager(activity: MainActivity): PlayerStateManager =
        MainActivity::class.java.getDeclaredField("playerStateManager").apply {
            isAccessible = true
        }.get(activity) as PlayerStateManager

    private fun activityFixture(id: Int, title: String): String = JSONObject()
        // PlayerStateManager keys activity params at the root, while the card
        // used by WatchNext is nested in movie. Supply both actual contracts.
        .put("id", id)
        .put("component", "full")
        .put("source", "tmdb")
        .put("title", title)
        .put("movie", JSONObject().put("id", id).put("title", title))
        .toString()

    @Test
    fun deferredCompletionDoesNotClearNewPlaybackOrAnotherCard() {
        val completedActivity = MainActivity.lampaActivity
        val anotherActivity = activityFixture(999993, "Another card")
        lateinit var completionJob: Job
        lateinit var manager: PlayerStateManager
        lateinit var newPlayback: PlayerStateManager.PlaybackState
        lateinit var anotherPlayback: PlayerStateManager.PlaybackState
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                manager = activityStateManager(activity)
                val completed = manager.saveState(
                    activityJson = completedActivity,
                    playlist = listOf(PlayerStateManager.PlaylistItem(
                        url = "https://example.invalid/completed.mp4",
                        timeline = PlayerStateManager.PlaylistItem.Timeline("completed", 0.0, 0.0, 100)
                    ))
                )
                val finish = MainActivity::class.java.getDeclaredMethod(
                    "finishCompletedPlayback", String::class.java, PlayerStateManager.PlaybackState::class.java
                ).apply { isAccessible = true }
                // Keep cleanup blocked until both replacement states exist. This
                // deterministically exercises the stale completion callback race.
                synchronized(manager) {
                    completionJob = finish.invoke(activity, completedActivity, completed) as Job
                    newPlayback = manager.saveState(
                        activityJson = completedActivity,
                        playlist = listOf(PlayerStateManager.PlaylistItem(url = "https://example.invalid/new-episode.mp4"))
                    )
                    anotherPlayback = manager.saveState(
                        activityJson = anotherActivity,
                        playlist = listOf(PlayerStateManager.PlaylistItem(url = "https://example.invalid/other-card.mp4"))
                    )
                    assertEquals(completed.activityKey, newPlayback.activityKey)
                    assertNotEquals("Different cards must have distinct activity keys",
                        newPlayback.activityKey, anotherPlayback.activityKey)
                    MainActivity.lampaActivity = anotherActivity
                }
            }
            runBlocking { withTimeout(5_000L) { completionJob.join() } }
            assertSame(newPlayback, manager.getState(completedActivity))
            assertSame(anotherPlayback, manager.getState(anotherActivity))
        }
    }

    @Test
    fun playbackPositionSurvivesPlayerStateManagerRecreation() {
        val activityJson = activityFixture(999992, "Resume Test")
        val url = "https://example.invalid/show-s01e03.mp4"
        val positionMillis = 2_052_000L
        val item = PlayerStateManager.PlaylistItem(
            url = url,
            title = "Episode 3",
            timeline = PlayerStateManager.PlaylistItem.Timeline(
                hash = "resume-test",
                time = positionMillis / 1_000.0,
                duration = 3_600.0,
                percent = 57
            ),
            season = 1,
            episode = 3
        )

        PlayerStateManager(context).saveState(
            activityJson = activityJson,
            playlist = listOf(item),
            currentUrl = url,
            currentPosition = positionMillis
        )

        val restored = PlayerStateManager(context).getState(activityJson)
        assertEquals(positionMillis, restored.currentPosition)
        assertEquals(positionMillis / 1_000.0, restored.currentItem?.timeline?.time)
        assertEquals(1, restored.currentItem?.season)
        assertEquals(3, restored.currentItem?.episode)
    }

    private fun twoEpisodeRequest(): Triple<JSONObject, String, String> {
        val firstUrl = "https://example.invalid/show-s01e01.mp4"
        val secondUrl = "https://example.invalid/show-s01e02.mp4"
        val playlist = JSONArray()
            .put(JSONObject().put("url", firstUrl).put("title", "Episode 1"))
            .put(JSONObject().put("url", secondUrl).put("title", "Episode 2"))
        return Triple(
            JSONObject()
                .put("url", firstUrl)
                .put("title", "Episode 1")
                .put("playlist", playlist),
            firstUrl,
            secondUrl
        )
    }

    private fun waitForActivityResume(
        scenario: ActivityScenario<MainActivity>,
        timeoutSeconds: Long = 10
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (scenario.state == Lifecycle.State.RESUMED) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun waitForActivityWindowReady(
        scenario: ActivityScenario<MainActivity>,
        timeoutSeconds: Long = 10
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                ready = activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                    decor.hasWindowFocus() && !decor.isLayoutRequested
            }
            if (ready) return true
            Thread.sleep(50L)
        }
        return false
    }

    private fun waitForLaunchCount(expected: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (launches.size >= expected) return true
            Thread.sleep(50)
        }
        return launches.size >= expected
    }
}
