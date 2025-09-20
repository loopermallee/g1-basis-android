package io.texne.g1.basis.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nabinbhandari.android.permissions.PermissionHandler
import com.nabinbhandari.android.permissions.Permissions
import io.texne.g1.basis.core.G1
import io.texne.g1.basis.core.G1Gesture
import io.texne.g1.basis.service.protocol.G1Glasses
import io.texne.g1.basis.service.protocol.ObserveStateCallback
import io.texne.g1.basis.service.protocol.OperationCallback
import io.texne.g1.basis.service.protocol.G1ServiceState
import io.texne.g1.basis.service.protocol.G1GestureEvent
import io.texne.g1.basis.service.protocol.IG1Service
import io.texne.g1.basis.service.protocol.IG1ServiceClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.DurationUnit
import kotlin.time.toDuration

//

private fun G1.ConnectionState.toInt(): Int =
    when(this) {
        G1.ConnectionState.UNINITIALIZED -> G1Glasses.UNINITIALIZED
        G1.ConnectionState.DISCONNECTED -> G1Glasses.DISCONNECTED
        G1.ConnectionState.CONNECTING -> G1Glasses.CONNECTING
        G1.ConnectionState.CONNECTED -> G1Glasses.CONNECTED
        G1.ConnectionState.DISCONNECTING -> G1Glasses.DISCONNECTING
        G1.ConnectionState.ERROR -> G1Glasses.ERROR
    }

private fun G1Service.ServiceStatus.toInt(): Int =
    when(this) {
        G1Service.ServiceStatus.READY -> G1ServiceState.READY
        G1Service.ServiceStatus.LOOKING -> G1ServiceState.LOOKING
        G1Service.ServiceStatus.LOOKED -> G1ServiceState.LOOKED
        G1Service.ServiceStatus.ERROR -> G1ServiceState.ERROR
    }

private fun G1Gesture.Side.toInt(): Int =
    when(this) {
        G1Gesture.Side.LEFT -> G1GestureEvent.SIDE_LEFT
        G1Gesture.Side.RIGHT -> G1GestureEvent.SIDE_RIGHT
    }

private fun G1Gesture.toTypeInt(): Int =
    when(this) {
        is G1Gesture.Tap -> G1GestureEvent.TYPE_TAP
        is G1Gesture.Hold -> G1GestureEvent.TYPE_HOLD
    }

private fun G1Service.ServiceGestureEvent.toParcelable(): G1GestureEvent {
    val event = G1GestureEvent()
    event.sequence = sequence
    event.timestampMillis = gesture.timestampMillis
    event.side = gesture.side.toInt()
    event.type = gesture.toTypeInt()
    return event
}

private fun G1Service.InternalGlasses.toGlasses(): G1Glasses {
    val glasses = G1Glasses()
    glasses.id = this.g1.id
    glasses.name = this.g1.name
    glasses.connectionState = this.connectionState.toInt()
    glasses.batteryPercentage = this.batteryPercentage ?: -1
    glasses.leftConnectionState = this.leftConnectionState.toInt()
    glasses.rightConnectionState = this.rightConnectionState.toInt()
    glasses.leftBatteryPercentage = this.leftBatteryPercentage ?: -1
    glasses.rightBatteryPercentage = this.rightBatteryPercentage ?: -1
    return glasses
}

private fun G1Service.InternalState.toState(): G1ServiceState {
    val state = G1ServiceState()
    state.status = this.status.toInt()
    state.glasses = this.glasses.values.map { it -> it.toGlasses() }.toTypedArray()
    state.gestureEvent = this.gestureEvent?.toParcelable()
    return state
}

//

class G1Service: Service() {

    companion object {
        const val EXTRA_SIDE_FILTER = "io.texne.g1.basis.service.EXTRA_SIDE_FILTER"
    }

    enum class ServiceStatus {
        READY,
        LOOKING,
        LOOKED,
        ERROR
    }

    // internal state ------------------------------------------------------------------------------

    val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "savedState")

    internal data class InternalGlasses(
        val connectionState: G1.ConnectionState,
        val batteryPercentage: Int?,
        val leftConnectionState: G1.ConnectionState,
        val rightConnectionState: G1.ConnectionState,
        val leftBatteryPercentage: Int?,
        val rightBatteryPercentage: Int?,
        val g1: G1
    )
    internal data class InternalState(
        val status: ServiceStatus = ServiceStatus.READY,
        val glasses: Map<String, InternalGlasses> = mapOf(),
        val gestureEvent: ServiceGestureEvent? = null
    )
    internal data class ServiceGestureEvent(
        val sequence: Int,
        val gesture: G1Gesture
    )
    private val state = MutableStateFlow<InternalState>(InternalState())
    private val gestureCollectors = mutableMapOf<String, Job>()
    private val forcedDisconnectJobs = mutableMapOf<String, Job>()
    private var nextGestureSequence: Int = 1
    private val LAST_CONNECTED_ID = stringPreferencesKey("last_connected_id")
    @Volatile
    private var cachedLastConnectedId: String? = null
    @Volatile
    private var sideMarkerFilter: Set<G1Gesture.Side>? = null

    private suspend fun readLastConnectedId(): String? {
        val cached = cachedLastConnectedId
        if (cached != null) {
            return cached
        }
        val stored = dataStore.data.map { preferences -> preferences[LAST_CONNECTED_ID] }.first()
        cachedLastConnectedId = stored
        return stored
    }

    private suspend fun persistLastConnectedId(id: String?) {
        dataStore.edit { settings ->
            if (id == null) {
                settings.remove(LAST_CONNECTED_ID)
            } else {
                settings[LAST_CONNECTED_ID] = id
            }
        }
        cachedLastConnectedId = id
    }

    private fun ensureFullyDisconnected(id: String, g1: G1, glassesState: G1.State) {
        val leftState = glassesState.leftConnectionState
        val rightState = glassesState.rightConnectionState
        val leftConnected = leftState == G1.ConnectionState.CONNECTED
        val rightConnected = rightState == G1.ConnectionState.CONNECTED
        val leftTerminated = leftState == G1.ConnectionState.DISCONNECTED || leftState == G1.ConnectionState.ERROR
        val rightTerminated = rightState == G1.ConnectionState.DISCONNECTED || rightState == G1.ConnectionState.ERROR

        val halfConnected = (leftConnected && rightTerminated) || (rightConnected && leftTerminated)
        if (!halfConnected || forcedDisconnectJobs.containsKey(id)) {
            return
        }

        Log.w("G1Service", "Half-connected state detected for $id. Forcing full disconnect.")
        forcedDisconnectJobs[id] = coroutineScope.launch {
            try {
                g1.disconnect()
            } finally {
                gestureCollectors.remove(id)?.cancel()
                forcedDisconnectJobs.remove(id)
            }
        }
    }

    private fun observeGestures(id: String, g1: G1) {
        gestureCollectors[id]?.cancel()
        gestureCollectors[id] = coroutineScope.launch {
            g1.gestures.collect { gesture ->
                val event = ServiceGestureEvent(
                    sequence = nextGestureSequence++,
                    gesture = gesture
                )
                state.value = state.value.copy(gestureEvent = event)
            }
        }
    }

    // client-service interface --------------------------------------------------------------------

    private fun commonObserveState(callback: ObserveStateCallback?) {
        if(callback != null) {
            coroutineScope.launch {
                state.collect {
                    callback.onStateChange(it.toState())
                }
            }
        }
    }

    private fun commonDisplayTextPage(
        id: String?,
        page: Array<out String?>?,
        callback: OperationCallback?
    ) {
        if(id != null && page != null) {
            val glasses = state.value.glasses.get(id)
            if (glasses != null) {
                coroutineScope.launch {
                    val result = glasses.g1.displayTextPage(page.filterNotNull())
                    callback?.onResult(result)
                }
            }
        }
    }

    private fun commonStopDisplaying(id: String?, callback: OperationCallback?) {
        if(id != null) {
            val glasses = state.value.glasses.get(id)
            if (glasses != null) {
                coroutineScope.launch {
                    val result = glasses.g1.stopDisplaying()
                    callback?.onResult(result)
                }
            }
        }
    }

    private val clientBinder = object : IG1ServiceClient.Stub() {
        override fun observeState(callback: ObserveStateCallback?) = commonObserveState(callback)
        override fun displayTextPage(
            id: String?,
            page: Array<out String?>?,
            callback: OperationCallback?
        ) = commonDisplayTextPage(id, page, callback)
        override fun stopDisplaying(
            id: String?,
            callback: OperationCallback?
        ) = commonStopDisplaying(id, callback)
    }

    private val binder = object : IG1Service.Stub() {

        override fun observeState(callback: ObserveStateCallback?) = commonObserveState(callback)

        override fun lookForGlasses() {
            if(state.value.status == ServiceStatus.LOOKING) {
                return
            }
            withPermissions {
                coroutineScope.launch {
                    val filterSnapshot = sideMarkerFilter?.toSet()
                    if (filterSnapshot != null) {
                        Log.i(
                            "G1Service",
                            "Scanning with side marker filter: ${filterSnapshot.joinToString { it.name }}"
                        )
                    }
                    // clear all glasses except connected
                    state.value = state.value.copy(
                        status = ServiceStatus.LOOKING,
                        glasses = state.value.glasses.values
                            .filter { it.connectionState == G1.ConnectionState.CONNECTED }
                            .associate { Pair(it.g1.id, it) }
                    )

                    // make sure last connected id makes sense, fix if it doesn't for some reason
                    var lastConnectedId = readLastConnectedId()
                    val connectedId = state.value.glasses.values.find { it.connectionState == G1.ConnectionState.CONNECTED }?.g1?.id
                    if(connectedId != null && lastConnectedId != connectedId) {
                        persistLastConnectedId(connectedId)
                        lastConnectedId = connectedId
                    }

                    try {
                        G1.find(15.toDuration(DurationUnit.SECONDS), filterSnapshot).collect { found ->
                            if(found != null) {
                                Log.d("G1Service", "SCANNING FOUND = ${found}")

                                // add to glasses state if not already there
                                if(state.value.glasses.values.find { it.g1.id == found.id } == null) {
                                    val glassesState = found.state.value
                                    state.value = state.value.copy(
                                        glasses = state.value.glasses.plus(
                                            Pair(
                                                found.id,
                                                InternalGlasses(
                                                    connectionState = glassesState.connectionState,
                                                    batteryPercentage = glassesState.batteryPercentage,
                                                    leftConnectionState = glassesState.leftConnectionState,
                                                    rightConnectionState = glassesState.rightConnectionState,
                                                    leftBatteryPercentage = glassesState.leftBatteryPercentage,
                                                    rightBatteryPercentage = glassesState.rightBatteryPercentage,
                                                    g1 = found
                                                )
                                            )
                                        )
                                    )
                                    observeGestures(found.id, found)
                                    coroutineScope.launch {
                                        found.state.collect { glassesState ->
                                            Log.d(
                                                "G1Service",
                                                "GLASSES_STATE ${found.name} (${found.id}) = ${glassesState}"
                                            )
                                            state.value =
                                                state.value.copy(glasses = state.value.glasses.entries.associate {
                                                    if (it.key == found.id) {
                                                        Pair(
                                                            it.key,
                                                            it.value.copy(
                                                                connectionState = glassesState.connectionState,
                                                                batteryPercentage = glassesState.batteryPercentage,
                                                                leftConnectionState = glassesState.leftConnectionState,
                                                                rightConnectionState = glassesState.rightConnectionState,
                                                                leftBatteryPercentage = glassesState.leftBatteryPercentage,
                                                                rightBatteryPercentage = glassesState.rightBatteryPercentage,
                                                                g1 = it.value.g1
                                                            )
                                                        )
                                                    } else {
                                                        Pair(
                                                            it.key,
                                                            it.value
                                                        )
                                                    }
                                                })
                                            ensureFullyDisconnected(found.id, found, glassesState)
                                        }
                                    }

                                    // if this is the pair we were connected to before, reconnect
                                    if(lastConnectedId == found.id) {
                                        connectGlasses(found.id, null)
                                        lastConnectedId = found.id
                                    }
                                }
                            } else {
                                state.value = state.value.copy(
                                    status = ServiceStatus.LOOKED,
                                    glasses = state.value.glasses,
                                )
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e("G1Service", "Error while scanning for glasses", e)
                        state.value = state.value.copy(status = ServiceStatus.ERROR)
                    }
                }
            }
        }

        override fun connectGlasses(id: String?, callback: OperationCallback?) {
            if(id != null) {
                val glasses = state.value.glasses.get(id)
                if (glasses != null) {
                    coroutineScope.launch {
                        forcedDisconnectJobs.remove(id)?.cancel()
                        val result = glasses.g1.connect(this@G1Service, coroutineScope)
                        if(result == true) {
                            persistLastConnectedId(id)
                        }
                        callback?.onResult(result)
                    }
                } else {
                    callback?.onResult(false)
                }
            } else {
                callback?.onResult(false)
            }
        }

        override fun disconnectGlasses(id: String?, callback: OperationCallback?) {
            if(id != null) {
                val glasses = state.value.glasses.get(id)
                if (glasses != null) {
                    coroutineScope.launch {
                        forcedDisconnectJobs.remove(id)?.cancel()
                        glasses.g1.disconnect()
                        gestureCollectors.remove(id)?.cancel()
                        if(cachedLastConnectedId == id) {
                            persistLastConnectedId(null)
                        }
                        callback?.onResult(true)
                    }
                } else {
                    callback?.onResult(false)
                }
            } else {
                callback?.onResult(false)
            }
        }

        override fun displayTextPage(
            id: String?,
            page: Array<out String?>?,
            callback: OperationCallback?
        ) = commonDisplayTextPage(id, page, callback)

        override fun stopDisplaying(id: String?, callback: OperationCallback?) =
            commonStopDisplaying(id, callback)
    }

    // infrastructure ------------------------------------------------------------------------------

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // permissions infrastructure ------------------------------------------------------------------

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableSetOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        return permissions.toTypedArray()
    }

    private fun hasAllPermissions(permissions: Array<String>): Boolean =
        permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }

    private fun withPermissions(block: () -> Unit) {
        val permissions = requiredPermissions()
        if (permissions.isEmpty()) {
            block()
            return
        }

        if (hasAllPermissions(permissions)) {
            block()
            return
        }

        Permissions.check(
            this@G1Service,
            permissions,
            "Please provide Bluetooth and location access so the service can interact with the G1 glasses",
            Permissions.Options().setCreateNewTask(true),
            object: PermissionHandler() {
                override fun onGranted() {
                    block()
                }
            }
        )
    }

    private fun parseSideFilterTokens(rawTokens: List<String>?): Set<G1Gesture.Side>? {
        if (rawTokens.isNullOrEmpty()) {
            return null
        }

        val sides = rawTokens
            .flatMap { token -> token.split(',', ';').map { it.trim() } }
            .filter { it.isNotEmpty() }
            .mapNotNull { token ->
                when (token.uppercase(Locale.ROOT)) {
                    "L", "LEFT" -> G1Gesture.Side.LEFT
                    "R", "RIGHT" -> G1Gesture.Side.RIGHT
                    else -> null
                }
            }
            .toSet()

        return sides.takeIf { it.isNotEmpty() }
    }

    private fun updateSideMarkerFilter(intent: Intent?) {
        if (intent == null || !intent.hasExtra(EXTRA_SIDE_FILTER)) {
            return
        }

        val tokens = intent.getStringArrayExtra(EXTRA_SIDE_FILTER)?.toList()
            ?: intent.getStringArrayListExtra(EXTRA_SIDE_FILTER)
            ?: intent.getStringExtra(EXTRA_SIDE_FILTER)?.let { listOf(it) }

        val parsed = parseSideFilterTokens(tokens)
        sideMarkerFilter = parsed
        if (parsed == null) {
            Log.i("G1Service", "Side marker filter cleared")
        } else {
            Log.i(
                "G1Service",
                "Side marker filter set to ${parsed.joinToString { it.name }}"
            )
        }
    }

    // internal service mechanism ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            withPermissions {
                val G1_SERVICE_NOTIFICATION_CHANNEL_ID: String = "0xC0FFEE"
                val G1_SERVICE_NOTIFICATION_ID: Int = 0xC0FFEE

                val notificationChannel = NotificationChannel(
                    G1_SERVICE_NOTIFICATION_CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                notificationChannel.description =
                    getString(R.string.notification_channel_description)
                val notificationManager =
                    getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(notificationChannel)

                val notification = Notification.Builder(this, G1_SERVICE_NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_service_foreground)
                    .setContentTitle(getString(R.string.notification_channel_name))
                    .setContentText(getString(R.string.notification_text))
                    .setContentIntent(
                        PendingIntent.getActivity(
                            this, 0, Intent(this, G1Service::class.java),
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    .build()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        G1_SERVICE_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } else {
                    startForeground(G1_SERVICE_NOTIFICATION_ID, notification)
                }
            }
        }
        binder.lookForGlasses()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateSideMarkerFilter(intent)
        if (intent?.hasExtra(EXTRA_SIDE_FILTER) == true && state.value.status != ServiceStatus.LOOKING) {
            binder.lookForGlasses()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(Intent(applicationContext, G1Service::class.java))
        } else {
            applicationContext.startService(Intent(applicationContext, G1Service::class.java))
        }

        updateSideMarkerFilter(intent)
        return when(intent?.action) {
            "io.texne.g1.basis.service.protocol.IG1Service" -> binder
            "io.texne.g1.basis.service.protocol.IG1ServiceClient" -> clientBinder
            else -> null
        }
    }

    override fun onDestroy() {
        // disconnect any connected devices
        gestureCollectors.values.forEach { it.cancel() }
        gestureCollectors.clear()
        coroutineScope.launch {
            state.value.glasses.values.filter { it.connectionState == G1.ConnectionState.CONNECTED }.forEach {
                it.g1.disconnect()
            }
        }
        super.onDestroy()
    }
}
