package io.texne.g1.hub.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.io.path.createTempFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HudSettingsRepositoryTest {

    @Test
    fun toggleAndMovePersist() = runTest {
        val dataStore = PreferenceDataStoreFactory.createWithPath(
            scope = backgroundScope,
            produceFile = { createTempFile("hud_repo", ".preferences_pb") }
        )
        val repository = HudSettingsRepository(dataStore)

        val initialOrder = repository.widgets.first().map { it.type }
        assertEquals(
            listOf(
                HudWidgetType.CLOCK,
                HudWidgetType.WEATHER,
                HudWidgetType.NEWS,
                HudWidgetType.NOTIFICATIONS
            ),
            initialOrder
        )

        repository.setEnabled(HudWidgetType.WEATHER, enabled = false)
        repository.move(HudWidgetType.NOTIFICATIONS, delta = -1)

        val updated = repository.widgets.drop(2).first()
        assertFalse(updated.first { it.type == HudWidgetType.WEATHER }.enabled)
        assertEquals(HudWidgetType.NOTIFICATIONS, updated[2].type)

        val persisted = dataStore.data.first()[stringPreferencesKey("hud_widgets_v1")]
        assertTrue(persisted?.contains("weather:false") == true)
    }
}
