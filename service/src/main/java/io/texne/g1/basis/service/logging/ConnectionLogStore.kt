package io.texne.g1.basis.service.logging

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

private val Context.connectionLogDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "connection_logs"
)

object ConnectionLogStore {
    private const val MAX_ENTRIES = 50
    private val ENTRIES_KEY = stringPreferencesKey("entries")

    suspend fun append(context: Context, entry: String) {
        context.connectionLogDataStore.edit { preferences ->
            val entries = decode(preferences[ENTRIES_KEY]).apply { add(entry) }
            while (entries.size > MAX_ENTRIES) {
                entries.removeAt(0)
            }
            preferences[ENTRIES_KEY] = encode(entries)
        }
    }

    suspend fun read(context: Context): List<String> = runCatching {
        val raw = context.connectionLogDataStore.data
            .map { preferences -> preferences[ENTRIES_KEY] }
            .first()
        decode(raw)
    }.getOrElse { emptyList() }

    private fun encode(entries: List<String>): String {
        val json = JSONArray()
        entries.forEach { json.put(it) }
        return json.toString()
    }

    private fun decode(raw: String?): MutableList<String> {
        if (raw.isNullOrEmpty()) {
            return mutableListOf()
        }
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index -> array.optString(index) }
        } catch (error: JSONException) {
            mutableListOf()
        }
    }
}
