package top.rootu.lampa

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
import org.hamcrest.CoreMatchers.containsString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
    private lateinit var launchLatch: CountDownLatch
    private lateinit var receiver: BroadcastReceiver

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        context.appPrefs.edit().clear().commit()
        context.lastPlayedPrefs.edit().clear().commit()
        context.appUrl = "about:blank"
        context.appPlayer = "com.brouken.player"
        context.nextEpisodeMode = NextEpisodeMode.ASK
        MainActivity.playerAutoNext = true
        MainActivity.lampaActivity = "{\"movie\":{\"id\":999991,\"title\":\"Integration Test\"}}"

        launchLatch = CountDownLatch(2)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                launches += intent?.getStringExtra("url").orEmpty()
                launchLatch.countDown()
            }
        }
        val filter = IntentFilter("com.brouken.player.TEST_LAUNCH")
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    @After
    fun tearDown() {
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

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.runPlayer(request) }

            assertTrue("Just Player was not launched for the first episode", waitForLaunchCount(1))
            assertTrue("LAMPA did not resume after the first episode", waitForActivityResume(scenario))
            onView(withText(containsString("10"))).check(matches(isDisplayed()))

            assertTrue("Auto mode did not launch the next episode", launchLatch.await(15, TimeUnit.SECONDS))
            assertEquals(listOf(firstUrl, secondUrl), launches.toList())
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
            Thread.sleep(500L)
            onView(withText(R.string.next_episode_prompt_title)).check(doesNotExist())
            assertEquals(listOf(onlyUrl), launches.toList())
        }
    }

    @Test
    fun playbackPositionSurvivesPlayerStateManagerRecreation() {
        val activityJson = "{\"movie\":{\"id\":999992,\"title\":\"Resume Test\"}}"
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

    private fun waitForLaunchCount(expected: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (launches.size >= expected) return true
            Thread.sleep(50)
        }
        return launches.size >= expected
    }
}
