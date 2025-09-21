package io.texne.g1.hub.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException

private val Context.scanHistoryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "scan_history"
)

internal object ScanHistoryStore {
    private const val MAX_ENTRIES = 100
    private val ENTRIES_KEY = stringPreferencesKey("entries")

    suspend fun store(context: Context, timestamps: List<Long>) {
        val limited = timestamps.takeLast(MAX_ENTRIES)
        runCatching {
            context.scanHistoryDataStore.edit { preferences ->
                preferences[ENTRIES_KEY] = encode(limited)
            }
        }
    }

    suspend fun read(context: Context): List<Long> {
        return runCatching {
            val raw = context.scanHistoryDataStore.data
                .map { preferences -> preferences[ENTRIES_KEY] }
                .first()
            decode(raw)
        }.getOrElse { emptyList() }
    }

    private fun encode(entries: List<Long>): String {
        val json = JSONArray()
        entries.forEach { json.put(it) }
        return json.toString()
    }

    private fun decode(raw: String?): List<Long> {
        if (raw.isNullOrEmpty()) {
            return emptyList()
        }
        return try {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.optLong(index) }
        } catch (error: JSONException) {
            emptyList()
        }
    }
}
