package io.texne.g1.hub.ui.debug

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.texne.g1.hub.BuildConfig
import io.texne.g1.hub.model.Repository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val repository: Repository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class State(
        val debugInfo: String = "",
        val isRefreshing: Boolean = false,
        val isAutoRefreshEnabled: Boolean = false,
        val isShareAvailable: Boolean = false,
        val lastUpdatedMillis: Long? = null
    )

    sealed interface Event {
        data class Message(val text: String, val isError: Boolean) : Event
    }

    private val clipboardManager: ClipboardManager? = context.getSystemService()

    private val _state = MutableStateFlow(
        State(isShareAvailable = isShareIntentSupported(context))
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    private val autoRefreshEnabled = MutableStateFlow(false)

    init {
        refreshDebugInfo()

        viewModelScope.launch {
            autoRefreshEnabled
                .distinctUntilChanged()
                .flatMapLatest { enabled ->
                    if (enabled) {
                        repository.getServiceStateFlow()
                    } else {
                        emptyFlow<Repository.ServiceSnapshot?>()
                    }
                }
                .collect { snapshot ->
                    refreshDebugInfoInternal(snapshot)
                }
        }
    }

    fun refreshDebugInfo() {
        viewModelScope.launch {
            refreshDebugInfoInternal(repository.getServiceStateFlow().value)
        }
    }

    fun setAutoRefreshEnabled(enabled: Boolean) {
        autoRefreshEnabled.value = enabled
        _state.update { state ->
            state.copy(isAutoRefreshEnabled = enabled)
        }
        if (enabled) {
            refreshDebugInfo()
        }
    }

    fun copyDebugInfo() {
        viewModelScope.launch {
            val info = state.value.debugInfo
            val clipboard = clipboardManager
            if (clipboard == null) {
                showMessage("Clipboard service unavailable", isError = true)
                return@launch
            }
            try {
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("G1 Hub Debug Info", info)
                )
                showMessage("Debug info copied to clipboard")
            } catch (throwable: Throwable) {
                Log.e(TAG, "Failed to copy debug info", throwable)
                showMessage(throwable.message ?: "Unable to copy debug info", isError = true)
            }
        }
    }

    fun exportDebugLog() {
        viewModelScope.launch(Dispatchers.IO) {
            val info = state.value.debugInfo
            val timestamp = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(Date())
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.getExternalFilesDir(null)
                ?: context.filesDir
            val file = File(directory, "debug_log_${'$'}timestamp.txt")
            try {
                file.writeText(info)
                showMessage("Exported debug log to ${'$'}{file.absolutePath}")
            } catch (throwable: Throwable) {
                Log.e(TAG, "Failed to export debug log", throwable)
                showMessage(throwable.message ?: "Unable to export debug log", isError = true)
            }
        }
    }

    fun shareDebugInfo() {
        if (!state.value.isShareAvailable) {
            return
        }
        viewModelScope.launch {
            val info = state.value.debugInfo
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, info)
                putExtra(Intent.EXTRA_SUBJECT, "G1 Hub Debug Info")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                val chooser = Intent.createChooser(intent, "Share Debug Info").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (throwable: ActivityNotFoundException) {
                Log.e(TAG, "No activity found to share debug info", throwable)
                showMessage("No app available to share debug info", isError = true)
            } catch (throwable: Throwable) {
                Log.e(TAG, "Failed to share debug info", throwable)
                showMessage(throwable.message ?: "Unable to share debug info", isError = true)
            }
        }
    }

    private suspend fun refreshDebugInfoInternal(snapshot: Repository.ServiceSnapshot?) {
        try {
            _state.update { it.copy(isRefreshing = true) }
            val info = withContext(Dispatchers.Default) {
                buildDebugInfo(snapshot, autoRefreshEnabled.value)
            }
            val now = System.currentTimeMillis()
            _state.update { state ->
                state.copy(
                    debugInfo = info,
                    isRefreshing = false,
                    lastUpdatedMillis = now
                )
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to refresh debug info", throwable)
            _state.update { it.copy(isRefreshing = false) }
            showMessage(throwable.message ?: "Failed to refresh debug info", isError = true)
        }
    }

    private fun buildDebugInfo(
        snapshot: Repository.ServiceSnapshot?,
        autoRefresh: Boolean
    ): String {
        val builder = StringBuilder()
        val date = SimpleDateFormat(DISPLAY_PATTERN, Locale.US).format(Date())
        builder.appendLine("G1 Hub Debug Report")
        builder.appendLine("App Version: ${'$'}{BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        builder.appendLine("Generated: ${'$'}date")
        builder.appendLine("Auto Refresh Enabled: ${'$'}autoRefresh")
        builder.appendLine()
        if (snapshot == null) {
            builder.appendLine("Service status: unavailable")
            return builder.toString().trimEnd()
        }
        builder.appendLine("Service status: ${'$'}{snapshot.status}")
        builder.appendLine("Glasses count: ${'$'}{snapshot.glasses.size}")
        builder.appendLine()
        snapshot.glasses.forEachIndexed { index, glasses ->
            builder.appendLine("[${'$'}index] ${'$'}{glasses.name} (${glasses.id})")
            builder.appendLine("  Status: ${'$'}{glasses.status}")
            builder.appendLine("  Battery: ${'$'}{glasses.batteryPercentage}%")
            builder.appendLine("  Signal strength: ${'$'}{glasses.signalStrength ?: "n/a"}")
            builder.appendLine("  RSSI: ${'$'}{glasses.rssi ?: "n/a"}")
            builder.appendLine(
                "  Left eye: status=${'$'}{glasses.left.status}, battery=${'$'}{glasses.left.batteryPercentage}%"
            )
            builder.appendLine(
                "  Right eye: status=${'$'}{glasses.right.status}, battery=${'$'}{glasses.right.batteryPercentage}%"
            )
            builder.appendLine()
        }
        return builder.toString().trimEnd()
    }

    private suspend fun showMessage(message: String, isError: Boolean = false) {
        _events.emit(Event.Message(message, isError))
    }

    companion object {
        private const val TAG = "DebugViewModel"
        private const val TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss"
        private const val DISPLAY_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"

        private fun isShareIntentSupported(context: Context): Boolean {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                ) != null
            } else {
                @Suppress("DEPRECATION")
                val result = context.packageManager.resolveActivity(
                    intent,
                    PackageManager.MATCH_DEFAULT_ONLY
                )
                result != null
            }
        }
    }
}
