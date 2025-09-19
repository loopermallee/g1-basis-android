package io.texne.g1.hub.hud

import android.test.mock.MockContext
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.texne.g1.hub.settings.HudSettingsRepository
import io.texne.g1.hub.settings.HudWidget
import io.texne.g1.hub.settings.HudWidgetType
import kotlin.io.path.createTempFile
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HudStatusComposerTest {

    @Test
    fun newsHeadlineIsTrimmedForHud() = runTest {
        val dataStore = PreferenceDataStoreFactory.createWithPath(
            scope = backgroundScope,
            produceFile = { createTempFile("hud_status", ".preferences_pb") }
        )
        val hudRepository = HudSettingsRepository(dataStore)
        hudRepository.replaceAll(listOf(HudWidget(HudWidgetType.NEWS, enabled = true)))

        val weatherRepository = WeatherRepository()
        val newsRepository = NewsRepository()
        val notificationRepository = NotificationRepository(TestContext())

        val longHeadline = buildString {
            append("Breaking: ")
            repeat(20) { append("headline update ") }
        }
        newsRepository.update(NewsDigest(headlines = listOf(longHeadline), isStale = false))

        val composer = HudStatusComposer(
            hudSettingsRepository = hudRepository,
            weatherRepository = weatherRepository,
            newsRepository = newsRepository,
            notificationRepository = notificationRepository,
            scope = this
        )

        advanceUntilIdle()
        val payload = composer.status.value

        assertTrue(payload.lines.isNotEmpty(), "Expected HUD payload to include at least one line")
        assertTrue(payload.lines.all { it.length <= 32 }, "HUD lines must not exceed 32 characters")
        assertTrue(payload.lines.size <= 4, "HUD overlay limited to four lines")
        assertTrue(payload.truncated, "Long headline should be marked truncated")
    }

    private class TestContext : MockContext() {
        override fun getSystemService(name: String): Any? = null
    }
}
