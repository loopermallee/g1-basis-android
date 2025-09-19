package io.texne.g1.hub.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

internal const val HUD_WIDGET_DATA_STORE_NAME = "hud_widget_preferences"
private val HUD_WIDGETS_KEY = stringPreferencesKey("hud_widgets_v1")

val Context.hudWidgetDataStore: DataStore<Preferences> by preferencesDataStore(
    name = HUD_WIDGET_DATA_STORE_NAME
)

@Singleton
class HudSettingsRepository @Inject constructor(
    @Named(HUD_WIDGET_DATA_STORE_NAME) private val dataStore: DataStore<Preferences>
) {

    val widgets: Flow<List<HudWidget>> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val encoded = preferences[HUD_WIDGETS_KEY]
            decode(encoded)
        }

    suspend fun setEnabled(type: HudWidgetType, enabled: Boolean) {
        update { current ->
            current.map { widget ->
                if (widget.type == type) widget.copy(enabled = enabled) else widget
            }
        }
    }

    suspend fun move(type: HudWidgetType, delta: Int) {
        if (delta == 0) return
        update { current ->
            if (current.size <= 1) return@update current
            val index = current.indexOfFirst { it.type == type }
            if (index == -1) return@update current
            val target = (index + delta).coerceIn(0, current.lastIndex)
            if (index == target) return@update current
            val mutable = current.toMutableList()
            val item = mutable.removeAt(index)
            mutable.add(target, item)
            mutable
        }
    }

    suspend fun replaceAll(widgets: List<HudWidget>) {
        update { widgets }
    }

    private suspend fun update(transform: (List<HudWidget>) -> List<HudWidget>) {
        dataStore.edit { preferences ->
            val current = decode(preferences[HUD_WIDGETS_KEY])
            val updated = normalize(transform(current))
            preferences[HUD_WIDGETS_KEY] = encode(updated)
        }
    }

    private fun decode(raw: String?): List<HudWidget> {
        if (raw.isNullOrBlank()) {
            return HudWidgetDefaults.widgets
        }

        val entries = raw.split(ENTRY_SEPARATOR)
            .mapNotNull { entry ->
                val parts = entry.split(VALUE_SEPARATOR)
                if (parts.size != 2) return@mapNotNull null
                val type = HudWidgetType.fromId(parts[0]) ?: return@mapNotNull null
                val enabled = parts[1].toBooleanStrictOrNull() ?: true
                HudWidget(type, enabled)
            }

        return normalize(entries)
    }

    private fun encode(widgets: List<HudWidget>): String {
        return normalize(widgets)
            .joinToString(separator = ENTRY_SEPARATOR) { widget ->
                "${widget.type.id}$VALUE_SEPARATOR${widget.enabled}"
            }
    }

    private fun normalize(widgets: List<HudWidget>): List<HudWidget> {
        val orderedMap = linkedMapOf<HudWidgetType, HudWidget>()
        widgets.forEach { widget ->
            orderedMap.put(widget.type, widget)
        }
        HudWidgetDefaults.widgets.forEach { default ->
            orderedMap.putIfAbsent(default.type, default)
        }
        return orderedMap.values.toList()
    }

    private companion object {
        private const val ENTRY_SEPARATOR = ";"
        private const val VALUE_SEPARATOR = ":"
    }
}
