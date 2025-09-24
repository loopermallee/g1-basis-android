package io.texne.g1.basis.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.observer.ConnectionObserver

private fun Data.toByteArray(): ByteArray {
    val array = ByteArray(this.size())
    for (index in 0 until this.size()) {
        array[index] = this.getByte(index)!!
    }
    return array
}

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
) : BleManager(context) {
    private val deviceName = device.name ?: device.address
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val writableConnectionState = MutableStateFlow(G1.ConnectionState.UNINITIALIZED)
    val connectionState = writableConnectionState.asStateFlow()

    private val writableIncoming = MutableSharedFlow<IncomingPacket>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val incoming = writableIncoming.asSharedFlow()

    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null

    private var heartbeatJob: Job? = null

    override fun initialize() {
        setConnectionObserver(
            object : ConnectionObserver {
                override fun onDeviceConnecting(device: BluetoothDevice) {
                    writableConnectionState.value = G1.ConnectionState.CONNECTING
                }

                override fun onDeviceConnected(device: BluetoothDevice) {
                    // Initialization will handle readiness updates.
                }

                override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                    writableConnectionState.value = G1.ConnectionState.ERROR
                }

                override fun onDeviceReady(device: BluetoothDevice) {
                    writableConnectionState.value = G1.ConnectionState.CONNECTED
                }

                override fun onDeviceDisconnecting(device: BluetoothDevice) {
                    writableConnectionState.value = G1.ConnectionState.DISCONNECTING
                }

                override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                    writableConnectionState.value = G1.ConnectionState.DISCONNECTED
                    stopHeartbeat()
                }
            }
        )

        requestMtu(PREFERRED_MTU)
            .with { _, mtu ->
                Log.d(TAG, "MTU negotiated to $mtu for $deviceName")
            }
            .fail { _, status ->
                Log.w(
                    TAG,
                    "Failed to negotiate MTU $PREFERRED_MTU for $deviceName (status=$status); retrying with $MIN_ACCEPTABLE_MTU"
                )
                requestMtu(MIN_ACCEPTABLE_MTU)
                    .fail { _, fallbackStatus ->
                        Log.e(
                            TAG,
                            "Failed fallback MTU $MIN_ACCEPTABLE_MTU for $deviceName (status=$fallbackStatus)"
                        )
                    }
                    .enqueue()
            }
            .enqueue()

        val notificationCharacteristic = readCharacteristic
        if (notificationCharacteristic == null) {
            Log.w(TAG, "Read characteristic unavailable; skipping notification subscription")
            return
        }

        setNotificationCallback(notificationCharacteristic)
            .with { device, data ->
                val packet = IncomingPacket.fromBytes(data.toByteArray())
                val sideLabel = device.name?.split('_')?.getOrNull(2) ?: device.address
                Log.d(TAG, "TRAFFIC_LOG $sideLabel - $packet")
                if (packet != null) {
                    scope.launch {
                        writableIncoming.emit(packet)
                    }
                }
            }

        enableNotifications(notificationCharacteristic)
            .done {
                startHeartbeat()
            }
            .fail { _, status ->
                Log.e(TAG, "Failed to enable notifications for $deviceName (status: $status)")
            }
            .enqueue()
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(UUID.fromString(UART_SERVICE_UUID)) ?: run {
            Log.e(TAG, "UART service missing for $deviceName")
            return false
        }

        val write = service.getCharacteristic(UUID.fromString(UART_WRITE_CHARACTERISTIC_UUID)) ?: run {
            Log.e(TAG, "UART write characteristic missing for $deviceName")
            return false
        }
        if (!write.supportsWriting()) {
            Log.e(TAG, "UART write characteristic missing write properties for $deviceName")
            return false
        }

        val read = service.getCharacteristic(UUID.fromString(UART_READ_CHARACTERISTIC_UUID)) ?: run {
            Log.e(TAG, "UART read characteristic missing for $deviceName")
            return false
        }
        if (!read.supportsNotifications()) {
            Log.e(TAG, "UART read characteristic missing notify/indicate properties for $deviceName")
            return false
        }

        writeCharacteristic = write
        readCharacteristic = read
        gatt.setCharacteristicNotification(read, true)
        logFirmwareServices(gatt)
        return true
    }

    override fun onServicesInvalidated() {
        writeCharacteristic = null
        readCharacteristic = null
        stopHeartbeat()
        writableConnectionState.value = G1.ConnectionState.DISCONNECTED
    }

    override fun getMinLogPriority(): Int = Log.VERBOSE

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    fun send(packet: OutgoingPacket): Boolean {
        val characteristic = writeCharacteristic ?: run {
            Log.w(TAG, "Write characteristic unavailable for $deviceName")
            return false
        }

        Log.d(
            TAG,
            "G1_TRAFFIC_SEND ${packet.bytes.joinToString(" ") { String.format("%02x", it) }}"
        )

        var attemptsRemaining = 3
        while (attemptsRemaining > 0) {
            val attempt = 4 - attemptsRemaining
            attemptsRemaining -= 1
            try {
                runBlocking {
                    writeCharacteristic(
                        characteristic,
                        packet.bytes,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ).suspend()
                }
                return true
            } catch (error: Throwable) {
                Log.e(
                    TAG,
                    "Failed to send packet ${packet.type} on attempt $attempt for $deviceName",
                    error
                )
            }
        }
        return false
    }

    fun connectBlocking(timeoutMs: Long): Boolean =
        runBlocking { connect(timeoutMs) }

    suspend fun connect(timeoutMs: Long): Boolean {
        if (isReady) {
            return true
        }
        writableConnectionState.value = G1.ConnectionState.CONNECTING
        return try {
            connect(device)
                .useAutoConnect(false)
                .retry(1)
                .timeout(timeoutMs)
                .suspend()
            true
        } catch (error: Throwable) {
            Log.e(TAG, "connect error for $deviceName", error)
            false
        }
    }

    suspend fun disconnectAwait() {
        stopHeartbeat()
        try {
            super.disconnect().suspend()
        } catch (error: Throwable) {
            Log.w(TAG, "disconnect error for $deviceName", error)
        }
    }

    override fun close() {
        stopHeartbeat()
        scope.cancel()
        removeFromCaches(device.address)
        super.close()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            if (!send(HeartbeatPacket())) {
                Log.e(TAG, "Initial heartbeat send failed for $deviceName")
            }
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

    private fun logFirmwareServices(gatt: BluetoothGatt) {
        val hasSmp = gatt.getService(UUID.fromString(SMP_SERVICE_UUID)) != null
        val hasDfu = gatt.getService(UUID.fromString(DFU_SERVICE_UUID)) != null
        Log.d(
            TAG,
            "Firmware services detected for $deviceName - SMP: $hasSmp, DFU: $hasDfu"
        )
    }

    companion object {
        private const val TAG = "G1BLEManager"
        private const val HEARTBEAT_INTERVAL_MS = 28_000L
        private const val PREFERRED_MTU = 251
        private const val MIN_ACCEPTABLE_MTU = 185

        private val cachedManagers = ConcurrentHashMap<String, G1BLEManager>()

        fun create(context: Context, device: BluetoothDevice): G1BLEManager =
            G1BLEManager(context, device)

        fun cache(manager: G1BLEManager) {
            cachedManagers[manager.device.address] = manager
        }

        fun claim(device: BluetoothDevice): G1BLEManager? =
            cachedManagers.remove(device.address)

        fun hasCached(address: String): Boolean = cachedManagers.containsKey(address)

        private fun removeFromCaches(address: String) {
            cachedManagers.remove(address)
        }
    }
}
