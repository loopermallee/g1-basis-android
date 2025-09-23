package io.texne.g1.basis.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private fun BluetoothGattCharacteristic.supportsWriting(): Boolean =
    properties and (
        BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        ) != 0

private fun BluetoothGattCharacteristic.supportsNotifications(): Boolean =
    properties and (
        BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE
        ) != 0

@SuppressLint("MissingPermission")
internal class G1BLEManager private constructor(
    context: Context,
    private val device: BluetoothDevice
) : BluetoothGattCallback() {

    private val applicationContext = context.applicationContext
    private val deviceName = device.name ?: device.address
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val writableConnectionState =
        MutableStateFlow(G1.ConnectionState.CONNECTING)
    val connectionState = writableConnectionState.asStateFlow()

    private val writableIncoming = MutableSharedFlow<IncomingPacket>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val incoming = writableIncoming.asSharedFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var notificationDescriptor: BluetoothGattDescriptor? = null

    private val ready = AtomicBoolean(false)
    private val notificationEnabled = AtomicBoolean(false)

    private val connectionLock = Any()
    private var connectionDeferred: CompletableDeferred<Boolean>? = null
    private var disconnectDeferred: CompletableDeferred<Unit>? = null

    private val writeLock = Any()
    private var pendingWriteLatch: CountDownLatch? = null
    private var pendingWriteSuccess: Boolean = false

    private var heartbeatJob: Job? = null

    private var mtuFallbackRequested = false
    private var servicesRequested = false

    fun isReady(): Boolean = ready.get()

    fun deviceAddress(): String = device.address

    fun connectBlocking(timeoutMs: Long): Boolean =
        runBlocking { ensureConnected(timeoutMs) }

    suspend fun ensureConnected(timeoutMs: Long): Boolean {
        if (ready.get()) {
            return true
        }
        val deferred = synchronized(connectionLock) {
            val existing = connectionDeferred
            if (existing != null && !existing.isCompleted) {
                existing
            } else {
                writableConnectionState.value = G1.ConnectionState.CONNECTING
                val newDeferred = CompletableDeferred<Boolean>()
                connectionDeferred = newDeferred
                handler.post {
                    val active = bluetoothGatt
                    if (active != null) {
                        Log.d(TAG, "Reusing existing GATT for ${device.address}")
                        active.connect()
                    } else {
                        Log.d(TAG, "Opening GATT for ${device.address}")
                        try {
                            bluetoothGatt = device.connectGatt(applicationContext, false, this)
                        } catch (error: Throwable) {
                            Log.w(TAG, "connectGatt error", error)
                            completeConnection(success = false)
                        }
                    }
                }
                newDeferred
            }
        }
        val result = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: false
        if (!result) {
            synchronized(connectionLock) {
                if (connectionDeferred === deferred) {
                    connectionDeferred = null
                }
            }
            bluetoothGatt?.let { disconnectDueToError(it) }
        }
        return result
    }

    suspend fun disconnect() {
        val gatt = bluetoothGatt ?: return
        withContext(Dispatchers.IO) {
            writableConnectionState.value = G1.ConnectionState.DISCONNECTING
            val deferred = CompletableDeferred<Unit>()
            synchronized(connectionLock) {
                disconnectDeferred = deferred
            }
            handler.post {
                try {
                    gatt.disconnect()
                } catch (error: Throwable) {
                    Log.w(TAG, "disconnect error", error)
                }
            }
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                deferred.await()
            }
            handler.post {
                try {
                    gatt.close()
                } catch (error: Throwable) {
                    Log.w(TAG, "close error", error)
                }
            }
            clearConnectionState()
        }
    }

    fun send(packet: OutgoingPacket): Boolean {
        val gatt = bluetoothGatt ?: return false
        val characteristic = writeCharacteristic ?: return false
        if (!ready.get() || !notificationEnabled.get()) {
            return false
        }
        Log.d(
            TAG,
            "G1_TRAFFIC_SEND ${packet.bytes.joinToString(" ") { String.format("%02x", it) }}"
        )
        val latch = CountDownLatch(1)
        val success = synchronized(writeLock) {
            if (pendingWriteLatch != null) {
                Log.w(TAG, "Write already in progress; dropping packet ${packet.type}")
                return@synchronized false
            }
            pendingWriteSuccess = false
            pendingWriteLatch = latch
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = packet.bytes
            val enqueued = try {
                gatt.writeCharacteristic(characteristic)
            } catch (error: Throwable) {
                Log.w(TAG, "writeCharacteristic error", error)
                false
            }
            if (!enqueued) {
                pendingWriteLatch = null
            }
            enqueued
        }
        if (!success) {
            return false
        }
        val completed = latch.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        synchronized(writeLock) {
            pendingWriteLatch = null
        }
        return completed && pendingWriteSuccess
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        Log.i(
            TAG,
            "onConnectionStateChange status=$status newState=$newState address=${device.address}"
        )
        if (status != BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_DISCONNECTED) {
            Log.w(TAG, "GATT_STATUS=$status")
        }
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                bluetoothGatt = gatt
                servicesRequested = false
                mtuFallbackRequested = false
                requestHighMtu(gatt)
            }
            BluetoothProfile.STATE_DISCONNECTING -> {
                writableConnectionState.value = G1.ConnectionState.DISCONNECTING
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                stopHeartbeat()
                notificationEnabled.set(false)
                ready.set(false)
                writableConnectionState.value = G1.ConnectionState.DISCONNECTED
                completeConnection(success = false, markError = false)
                synchronized(connectionLock) {
                    disconnectDeferred?.complete(Unit)
                    disconnectDeferred = null
                }
                synchronized(writeLock) {
                    pendingWriteLatch?.countDown()
                    pendingWriteLatch = null
                }
                handler.post {
                    try {
                        gatt.close()
                    } catch (error: Throwable) {
                        Log.w(TAG, "close error", error)
                    }
                }
                bluetoothGatt = null
                removeFromCaches(device.address)
            }
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        Log.i(TAG, "MTU=$mtu status=$status for ${device.address}")
        if (status == BluetoothGatt.GATT_SUCCESS && mtu >= MIN_ACCEPTABLE_MTU) {
            discoverServicesIfNeeded(gatt)
        } else if (!mtuFallbackRequested) {
            mtuFallbackRequested = true
            handler.post {
                val requested = try {
                    gatt.requestMtu(MIN_ACCEPTABLE_MTU)
                } catch (error: Throwable) {
                    Log.w(TAG, "requestMtu fallback error", error)
                    false
                }
                if (!requested) {
                    discoverServicesIfNeeded(gatt)
                }
            }
        } else {
            discoverServicesIfNeeded(gatt)
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "Service discovery failed with status=$status")
            disconnectDueToError(gatt)
            completeConnection(false)
            return
        }
        val service = gatt.getService(UUID.fromString(UART_SERVICE_UUID))
        if (service == null) {
            Log.e(TAG, "UART service missing for $deviceName")
            disconnectDueToError(gatt)
            completeConnection(false)
            return
        }
        val write = service.getCharacteristic(UUID.fromString(UART_WRITE_CHARACTERISTIC_UUID))
        if (write == null || !write.supportsWriting()) {
            Log.e(TAG, "UART write characteristic missing or not writable for $deviceName")
            disconnectDueToError(gatt)
            completeConnection(false)
            return
        }
        val read = service.getCharacteristic(UUID.fromString(UART_READ_CHARACTERISTIC_UUID))
        if (read == null || !read.supportsNotifications()) {
            Log.e(TAG, "UART read characteristic missing or not notifiable for $deviceName")
            disconnectDueToError(gatt)
            completeConnection(false)
            return
        }
        writeCharacteristic = write
        readCharacteristic = read
        notificationDescriptor = read.getDescriptor(CCCD_UUID)
        logFirmwareServices(gatt)
        val cccd = notificationDescriptor
        if (cccd == null) {
            Log.e(TAG, "UART read characteristic missing CCCD for $deviceName")
            disconnectDueToError(gatt)
            completeConnection(false)
            return
        }
        val notificationSet = try {
            gatt.setCharacteristicNotification(read, true)
        } catch (error: Throwable) {
            Log.w(TAG, "setCharacteristicNotification error", error)
            false
        }
        if (!notificationSet) {
            disconnectDueToError(gatt)
            completeConnection(false)
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val enqueued = try {
            gatt.writeDescriptor(cccd)
        } catch (error: Throwable) {
            Log.w(TAG, "writeDescriptor error", error)
            false
        }
        if (!enqueued) {
            disconnectDueToError(gatt)
            completeConnection(false)
        }
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int
    ) {
        if (descriptor != notificationDescriptor) {
            return
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            notificationEnabled.set(true)
            ready.set(true)
            writableConnectionState.value = G1.ConnectionState.CONNECTED
            startHeartbeat()
            completeConnection(true)
            Log.i(TAG, "Notifications enabled for $deviceName")
        } else {
            Log.e(TAG, "Failed to enable notifications for $deviceName status=$status")
            disconnectDueToError(gatt)
            completeConnection(false)
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (characteristic.uuid.toString().equals(UART_READ_CHARACTERISTIC_UUID, ignoreCase = true)) {
            val data = characteristic.value ?: return
            val packet = IncomingPacket.fromBytes(data)
            val sideLabel = device.name?.split('_')?.getOrNull(2) ?: device.address
            Log.d(TAG, "TRAFFIC_LOG $sideLabel - $packet")
            if (packet != null) {
                scope.launch {
                    writableIncoming.emit(packet)
                }
            }
        }
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        if (characteristic == writeCharacteristic) {
            synchronized(writeLock) {
                pendingWriteSuccess = status == BluetoothGatt.GATT_SUCCESS
                pendingWriteLatch?.countDown()
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Write failed for $deviceName status=$status")
            }
        }
    }

    private fun requestHighMtu(gatt: BluetoothGatt) {
        val requested = try {
            gatt.requestMtu(PREFERRED_MTU)
        } catch (error: Throwable) {
            Log.w(TAG, "requestMtu error", error)
            false
        }
        if (!requested) {
            discoverServicesIfNeeded(gatt)
        }
    }

    private fun discoverServicesIfNeeded(gatt: BluetoothGatt) {
        if (servicesRequested) {
            return
        }
        servicesRequested = true
        val started = try {
            gatt.discoverServices()
        } catch (error: Throwable) {
            Log.w(TAG, "discoverServices error", error)
            false
        }
        if (!started) {
            disconnectDueToError(gatt)
            completeConnection(false)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            send(HeartbeatPacket())
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!send(HeartbeatPacket())) {
                    Log.e(TAG, "Heartbeat send failed for $deviceName")
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun completeConnection(success: Boolean, markError: Boolean = true) {
        synchronized(connectionLock) {
            val deferred = connectionDeferred
            if (deferred != null && !deferred.isCompleted) {
                deferred.complete(success)
            }
            connectionDeferred = null
        }
        if (!success && markError) {
            writableConnectionState.value = G1.ConnectionState.ERROR
        }
    }

    private fun clearConnectionState() {
        stopHeartbeat()
        ready.set(false)
        notificationEnabled.set(false)
        writeCharacteristic = null
        readCharacteristic = null
        notificationDescriptor = null
        synchronized(connectionLock) {
            connectionDeferred = null
        }
    }

    private fun logFirmwareServices(gatt: BluetoothGatt) {
        val hasSmp = gatt.getService(UUID.fromString(SMP_SERVICE_UUID)) != null
        val hasDfu = gatt.getService(UUID.fromString(DFU_SERVICE_UUID)) != null
        Log.d(
            TAG,
            "Firmware services detected for $deviceName - SMP: $hasSmp, DFU: $hasDfu"
        )
    }

    private fun disconnectDueToError(gatt: BluetoothGatt) {
        handler.post {
            try {
                gatt.disconnect()
            } catch (error: Throwable) {
                Log.w(TAG, "disconnectDueToError error", error)
            }
        }
    }

    fun close() {
        stopHeartbeat()
        scope.cancel()
        bluetoothGatt?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (error: Throwable) {
                Log.w(TAG, "close error", error)
            }
        }
        bluetoothGatt = null
        removeFromCaches(device.address)
    }

    companion object {
        private const val TAG = "G1BLEManager"
        private const val HEARTBEAT_INTERVAL_MS = 28_000L
        private const val WRITE_TIMEOUT_MS = 3_000L
        private const val DISCONNECT_TIMEOUT_MS = 5_000L
        private const val PREFERRED_MTU = 251
        private const val MIN_ACCEPTABLE_MTU = 185
        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val cachedManagers = ConcurrentHashMap<String, G1BLEManager>()

        fun create(context: Context, device: BluetoothDevice): G1BLEManager =
            G1BLEManager(context, device)

        fun cache(manager: G1BLEManager) {
            cachedManagers[manager.deviceAddress()] = manager
        }

        fun claim(device: BluetoothDevice): G1BLEManager? =
            cachedManagers.remove(device.address)

        fun hasCached(address: String): Boolean = cachedManagers.containsKey(address)

        private fun removeFromCaches(address: String) {
            cachedManagers.remove(address)
        }
    }
}
