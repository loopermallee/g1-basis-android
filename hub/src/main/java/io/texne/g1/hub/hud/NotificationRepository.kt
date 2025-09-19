package io.texne.g1.hub.hud

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class NotificationRepository @Inject constructor(
    @ApplicationContext context: Context
) {

    private val notificationManager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private val _state = MutableStateFlow(NotificationState())
    val notifications: StateFlow<NotificationState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val manager = notificationManager
        if (manager == null) {
            _state.value = NotificationState(hasAccess = false)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            _state.value = NotificationState(activeCount = 0, hasAccess = true)
            return
        }

        val count = try {
            manager.activeNotifications?.size ?: 0
        } catch (securityException: SecurityException) {
            _state.value = NotificationState(activeCount = 0, hasAccess = false)
            return
        }
        _state.value = NotificationState(activeCount = count, hasAccess = true)
    }

    @VisibleForTesting
    fun update(state: NotificationState) {
        _state.value = state
    }
}

data class NotificationState(
    val activeCount: Int = 0,
    val hasAccess: Boolean = true
)
