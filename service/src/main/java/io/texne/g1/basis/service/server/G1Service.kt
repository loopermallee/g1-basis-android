package io.texne.g1.basis.service.server

import io.texne.g1.basis.service.R
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
import io.texne.g1.basis.service.protocol.G1Glasses
import io.texne.g1.basis.service.protocol.IG1Service
import io.texne.g1.basis.service.protocol.ObserveStateCallback
import io.texne.g1.basis.service.protocol.OperationCallback
import io.texne.g1.basis.service.protocol.G1ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

private fun G1Service.InternalGlasses.toGlasses(): G1Glasses {
    val glasses = G1Glasses()
    glasses.id = this.g1.id
    glasses.name = this.g1.name
    glasses.connectionState = this.connectionState.toInt()
    glasses.batteryPercentage = this.batteryPercentage ?: -1
    return glasses
}

private fun G1Service.InternalState.toState(): G1ServiceState {
    val state = G1ServiceState()
    state.status = this.status.toInt()
    state.glasses = this.glasses.values.map { it -> it.toGlasses() }.toTypedArray()
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
        val glasses: Map<String, InternalGlasses> = mapOf()
    )
    private val state = MutableStateFlow<InternalState>(InternalState())

    // client-service interface --------------------------------------------------------------------

    private val binder = object : IG1Service.Stub() {

        override fun observeState(callback: ObserveStateCallback?) {
            if(callback != null) {
                coroutineScope.launch {
                    state.collect {
                        callback.onStateChange(it.toState())
                    }
                }
            }
        }

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
                            .associate { Pair(it.g1.id, it) }
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

                    G1.find(15.toDuration(DurationUnit.SECONDS)).collect { found ->
                        if(found != null) {
                            Log.d("G1Service", "SCANNING FOUND = ${found}")

                            // add to glasses state if not already there
                            if(state.value.glasses.values.find { it.g1.id == found.id } == null) {
                                state.value = state.value.copy(
                                    glasses = state.value.glasses.plus(
                                        Pair(found.id, InternalGlasses(found.state.value.connectionState, found.state.value.batteryPercentage, found))
                                    )
                                )
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
                                    }
                                }

                                // if this is the pair we were connected to before, reconnect
                                if(lastConnectedId == found.id) {
                                    connectGlasses(found.id, null)
                                }
                            }
                        } else {
                            state.value = state.value.copy(
                                status = ServiceStatus.LOOKED,
                                glasses = state.value.glasses,
                            )
                        }
                    }
                }
            }
        }

        override fun connectGlasses(id: String?, callback: OperationCallback?) {
            if(id != null) {
                val glasses = state.value.glasses.get(id)
                if (glasses != null) {
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
                        glasses.g1.disconnect()
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

        override fun stopDisplaying(id: String?, callback: OperationCallback?) {
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
    }

    // infrastructure ------------------------------------------------------------------------------

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // permissions infrastructure ------------------------------------------------------------------

    private fun withPermissions(block: () -> Unit) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Permissions.check(this@G1Service, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
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
                    NotificationManager.IMPORTANCE_MIN
                )
                notificationChannel.description =
                    getString(R.string.notification_channel_description)
                val notificationManager =
                    getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(notificationChannel)

                val notification = Notification.Builder(this, G1_SERVICE_NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_service)
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
        return binder
    }

    override fun onDestroy() {
        // disconnect any connected devices
        coroutineScope.launch {
            state.value.glasses.values.filter { it.connectionState == G1.ConnectionState.CONNECTED }.forEach {
                it.g1.disconnect()
            }
        }
        onDestroy()
    }
}