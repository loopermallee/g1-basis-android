package io.texne.g1.hub.todo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class TodoPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREF_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    suspend fun load(): Snapshot = withContext(Dispatchers.IO) {
        Snapshot(
            parseItems(sharedPreferences.getString(KEY_ACTIVE, null)),
            parseItems(sharedPreferences.getString(KEY_ARCHIVED, null))
        )
    }

    suspend fun persist(active: List<TodoItem>, archived: List<TodoItem>) = withContext(Dispatchers.IO) {
        sharedPreferences.edit(commit = true) {
            putString(KEY_ACTIVE, encodeItems(active))
            putString(KEY_ARCHIVED, encodeItems(archived))
        }
    }

    private fun parseItems(raw: String?): List<TodoItem> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }

        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val item = json.toTodoItemOrNull() ?: continue
                    add(item)
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to parse todo list", error)
            emptyList()
        }
    }

    private fun encodeItems(items: List<TodoItem>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(item.toJson())
        }
        return array.toString()
    }

    data class Snapshot(
        val active: List<TodoItem>,
        val archived: List<TodoItem>
    )

    private fun JSONObject.toTodoItemOrNull(): TodoItem? {
        val id = optString(KEY_ID).takeIf { it.isNotBlank() } ?: return null
        val shortText = optString(KEY_SHORT_TEXT).takeIf { it.isNotBlank() } ?: return null
        val fullTextRaw = optString(KEY_FULL_TEXT, shortText)
        val fullText = if (fullTextRaw.isBlank()) shortText else fullTextRaw
        val isDone = optBoolean(KEY_IS_DONE, false)
        val archivedAt = if (has(KEY_ARCHIVED_AT) && !isNull(KEY_ARCHIVED_AT)) {
            optLong(KEY_ARCHIVED_AT)
        } else {
            null
        }
        val position = optInt(KEY_POSITION, 0)

        return TodoItem(
            id = id,
            shortText = shortText,
            fullText = fullText,
            isDone = isDone,
            archivedAt = archivedAt,
            position = position
        )
    }

    private fun TodoItem.toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_SHORT_TEXT, shortText)
        put(KEY_FULL_TEXT, fullText)
        put(KEY_IS_DONE, isDone)
        if (archivedAt != null) {
            put(KEY_ARCHIVED_AT, archivedAt)
        } else {
            put(KEY_ARCHIVED_AT, JSONObject.NULL)
        }
        put(KEY_POSITION, position)
    }

    companion object {
        private const val TAG = "TodoPreferences"
        private const val PREF_NAME = "todo_items"
        private const val KEY_ACTIVE = "active"
        private const val KEY_ARCHIVED = "archived"
        private const val KEY_ID = "id"
        private const val KEY_SHORT_TEXT = "short_text"
        private const val KEY_FULL_TEXT = "full_text"
        private const val KEY_IS_DONE = "is_done"
        private const val KEY_ARCHIVED_AT = "archived_at"
        private const val KEY_POSITION = "position"
    }
}
