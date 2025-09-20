package io.texne.g1.basis.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nabinbhandari.android.permissions.PermissionHandler
import com.nabinbhandari.android.permissions.Permissions
import io.texne.g1.basis.core.G1
import io.texne.g1.basis.core.G1Gesture
import io.texne.g1.basis.service.protocol.G1DiscoveredDevice
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import no.nordicsemi.android.support.v18.scanner.ScanResult
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
    return glasses
}

private fun Map<String, String>.toParcelableDevices(): Array<G1DiscoveredDevice> =
    this.entries.map { (address, name) ->
        G1DiscoveredDevice().also { device ->
            device.address = address
            device.name = name
        }
    }.toTypedArray()

private fun G1Service.InternalState.toState(): G1ServiceState {
    val state = G1ServiceState()
    state.status = this.status.toInt()
    state.glasses = this.glasses.values.map { it -> it.toGlasses() }.toTypedArray()
    state.gestureEvent = this.gestureEvent?.toParcelable()
    state.leftDevices = this.leftDevices.toParcelableDevices()
    state.rightDevices = this.rightDevices.toParcelableDevices()
    return state
}

//

class G1Service: Service() {

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
        val g1: G1
    )
    internal data class InternalState(
        val status: ServiceStatus = ServiceStatus.READY,
        val glasses: Map<String, InternalGlasses> = mapOf(),
        val gestureEvent: ServiceGestureEvent? = null,
        val leftDevices: Map<String, String> = mapOf(),
        val rightDevices: Map<String, String> = mapOf()
    )
    internal data class ServiceGestureEvent(
        val sequence: Int,
        val gesture: G1Gesture
    )
    private val state = MutableStateFlow<InternalState>(InternalState())
    private val gestureCollectors = mutableMapOf<String, Job>()
    private var nextGestureSequence: Int = 1
    private val discoveredLeftDevices = mutableMapOf<String, ScanResult>()
    private val discoveredRightDevices = mutableMapOf<String, ScanResult>()

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

    private fun registerGlasses(g1: G1) {
        if(state.value.glasses.containsKey(g1.id)) {
            return
        }
        state.value = state.value.copy(
            glasses = state.value.glasses.plus(
                Pair(
                    g1.id,
                    InternalGlasses(
                        g1.state.value.connectionState,
                        g1.state.value.batteryPercentage,
                        g1
                    )
                )
            )
        )
        observeGestures(g1.id, g1)
        coroutineScope.launch {
            g1.state.collect { glassesState ->
                Log.d(
                    "G1Service",
                    "GLASSES_STATE ${g1.name} (${g1.id}) = ${glassesState}"
                )
                state.value = state.value.copy(
                    glasses = state.value.glasses.entries.associate { entry ->
                        if (entry.key == g1.id) {
                            Pair(
                                entry.key,
                                entry.value.copy(
                                    connectionState = glassesState.connectionState,
                                    batteryPercentage = glassesState.batteryPercentage,
                                    g1 = entry.value.g1
                                )
                            )
                        } else {
                            Pair(entry.key, entry.value)
                        }
                    }
                )
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

    private fun connectDevicesInternal(
        leftAddress: String,
        rightAddress: String,
        callback: OperationCallback?
    ) {
        val leftResult = discoveredLeftDevices[leftAddress]
        val rightResult = discoveredRightDevices[rightAddress]
        if(leftResult == null || rightResult == null) {
            callback?.onResult(false)
            return
        }
        val id = G1.buildId(leftAddress, rightAddress)
        if(state.value.glasses.containsKey(id).not()) {
            val g1 = G1.fromScanResults(rightResult, leftResult)
            registerGlasses(g1)
        }
        val glasses = state.value.glasses[id]
        if(glasses == null) {
            callback?.onResult(false)
            return
        }
        when(glasses.connectionState) {
            G1.ConnectionState.CONNECTED -> {
                callback?.onResult(true)
                return
            }
            G1.ConnectionState.CONNECTING,
            G1.ConnectionState.DISCONNECTING -> {
                callback?.onResult(false)
                return
            }
            else -> {}
        }
        coroutineScope.launch {
            val result = glasses.g1.connect(this@G1Service, coroutineScope)
            if(result == true) {
                val LAST_CONNECTED_ID = stringPreferencesKey("last_connected_id")
                dataStore.edit { settings ->
                    settings[LAST_CONNECTED_ID] = id
                }
            }
            callback?.onResult(result)
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
                    // clear all glasses except connected
                    state.value = state.value.copy(
                        status = ServiceStatus.LOOKING,
                        glasses = state.value.glasses.values
                            .filter { it.connectionState == G1.ConnectionState.CONNECTED }
                            .associate { Pair(it.g1.id, it) },
                        leftDevices = mapOf(),
                        rightDevices = mapOf()
                    )

                    // make sure last connected id makes sense, fix if it doesn't for some reason
                    val LAST_CONNECTED_ID = stringPreferencesKey("last_connected_id")
                    var lastConnectedId = dataStore.data.map { preferences -> preferences[LAST_CONNECTED_ID] }.first()
                    val connectedId = state.value.glasses.values.find { it.connectionState == G1.ConnectionState.CONNECTED }?.g1?.id
                    if(connectedId != null && lastConnectedId != connectedId) {
                        dataStore.edit { settings ->
                            settings[LAST_CONNECTED_ID] = connectedId
                        }
                        lastConnectedId = connectedId
                    }

                    discoveredLeftDevices.clear()
                    discoveredRightDevices.clear()

                    G1.find(15.toDuration(DurationUnit.SECONDS)).collect { event ->
                        when(event) {
                            is G1.Companion.ScanEvent.Update -> {
                                event.newLeftDevices.forEach { result ->
                                    discoveredLeftDevices[result.device.address] = result
                                }
                                event.newRightDevices.forEach { result ->
                                    discoveredRightDevices[result.device.address] = result
                                }

                                event.completedPairs.forEach { pair ->
                                    val left = pair.left
                                    val right = pair.right
                                    if(left != null) {
                                        discoveredLeftDevices[left.device.address] = left
                                    }
                                    if(right != null) {
                                        discoveredRightDevices[right.device.address] = right
                                    }
                                    if(left != null && right != null) {
                                        val g1 = G1.fromScanResults(right, left)
                                        registerGlasses(g1)
                                        val internalGlasses = state.value.glasses[g1.id]
                                        if(
                                            lastConnectedId == g1.id &&
                                            internalGlasses != null &&
                                            (internalGlasses.connectionState == G1.ConnectionState.DISCONNECTED ||
                                                internalGlasses.connectionState == G1.ConnectionState.UNINITIALIZED)
                                        ) {
                                            connectDevicesInternal(left.device.address, right.device.address, null)
                                        }
                                    }
                                }

                                state.value = state.value.copy(
                                    leftDevices = discoveredLeftDevices.mapValues { entry ->
                                        entry.value.device.name ?: entry.key
                                    },
                                    rightDevices = discoveredRightDevices.mapValues { entry ->
                                        entry.value.device.name ?: entry.key
                                    }
                                )
                            }
                            G1.Companion.ScanEvent.Finished -> {
                                state.value = state.value.copy(
                                    status = ServiceStatus.LOOKED,
                                    leftDevices = discoveredLeftDevices.mapValues { entry ->
                                        entry.value.device.name ?: entry.key
                                    },
                                    rightDevices = discoveredRightDevices.mapValues { entry ->
                                        entry.value.device.name ?: entry.key
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        override fun connectDevices(
            leftAddress: String?,
            rightAddress: String?,
            callback: OperationCallback?
        ) {
            if(leftAddress != null && rightAddress != null) {
                connectDevicesInternal(leftAddress, rightAddress, callback)
            } else {
                callback?.onResult(false)
            }
        }

        override fun disconnectGlasses(id: String?, callback: OperationCallback?) {
            if(id != null) {
                val glasses = state.value.glasses.get(id)
                if (glasses != null) {
                    coroutineScope.launch {
                        glasses.g1.disconnect()
                        gestureCollectors.remove(id)?.cancel()
                        val LAST_CONNECTED_ID = stringPreferencesKey("last_connected_id")
                        dataStore.edit { settings ->
                            settings.remove(LAST_CONNECTED_ID)
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

    private fun withPermissions(block: () -> Unit) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Permissions.check(this@G1Service,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.POST_NOTIFICATIONS
                ) else arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                ),
                "Please provide the permissions so the service can interact with the G1 G1Glasses",
                Permissions.Options().setCreateNewTask(true),
                object: PermissionHandler() {
                    override fun onGranted() {
                        block()
                    }
                })
        } else {
            block()
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
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(Intent(applicationContext, G1Service::class.java))
        } else {
            applicationContext.startService(Intent(applicationContext, G1Service::class.java))
        }

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
        onDestroy()
    }
}