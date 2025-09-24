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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ConnectRequest
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.observer.ConnectionObserver
import no.nordicsemi.android.ble.ktx.suspend

private fun Data.toByteArray(): ByteArray {
    val array = ByteArray(this.size())
    (0..this.size()-1).forEach {
        array[it] = this.getByte(it)!!
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

private const val MAX_MTU = 251
private const val HEARTBEAT_INTERVAL_MS = 28_000L

@SuppressLint("MissingPermission")
public class G1BLEManager private constructor(
    private val deviceName: String,
    context: Context,
    private val deviceAddress: String
) : BleManager(context.applicationContext) {

    companion object {
        private val managers = ConcurrentHashMap<String, G1BLEManager>()
        private val pendingGatts = ConcurrentHashMap<String, BluetoothGatt>()

        fun getOrCreate(device: BluetoothDevice, context: Context): G1BLEManager {
            val address = device.address
            require(!address.isNullOrEmpty()) { "Device address required" }
            return managers.getOrPut(address) {
                G1BLEManager(device.name ?: address, context.applicationContext, address)
            }
        }

        fun attach(gatt: BluetoothGatt) {
            val address = gatt.device?.address
            if (!address.isNullOrEmpty()) {
                pendingGatts[address] = gatt
            }
        }

        internal fun consumePendingGatt(address: String): BluetoothGatt? = pendingGatts.remove(address)

        internal fun remove(address: String) {
            managers.remove(address)
            pendingGatts.remove(address)
        }
    }

    private val scopeJob = SupervisorJob()
    private val internalScope: CoroutineScope = CoroutineScope(scopeJob + Dispatchers.IO)

    private val writableConnectionState = MutableStateFlow<G1.ConnectionState>(G1.ConnectionState.DISCONNECTED)
    val connectionState = writableConnectionState.asStateFlow()
    private val writableIncoming = MutableSharedFlow<IncomingPacket>()
    val incoming = writableIncoming.asSharedFlow()

    private var deviceGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var heartbeatJob: Job? = null

    override fun initialize() {
        requestMtu(MAX_MTU).enqueue()
        setConnectionObserver(object: ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                writableConnectionState.value = G1.ConnectionState.CONNECTING
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                // EMPTY
            }

            override fun onDeviceFailedToConnect(
                device: BluetoothDevice,
                reason: Int
            ) {
                writableConnectionState.value = G1.ConnectionState.ERROR
            }

            override fun onDeviceReady(device: BluetoothDevice) {
                writableConnectionState.value = G1.ConnectionState.CONNECTED
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                writableConnectionState.value = G1.ConnectionState.DISCONNECTING
            }

            override fun onDeviceDisconnected(
                device: BluetoothDevice,
                reason: Int
            ) {
                writableConnectionState.value = G1.ConnectionState.DISCONNECTED
                stopHeartbeat()
            }
        })
        val notificationCharacteristic = readCharacteristic
        if (notificationCharacteristic == null) {
            Log.w("G1BLEManager", "Read characteristic unavailable; skipping notification subscription")
            return
        }

        setNotificationCallback(notificationCharacteristic).with { device, data ->
            val nameParts = device.name.orEmpty().split('_')
            val packet = IncomingPacket.fromBytes(data.toByteArray())
            if (packet == null) {
                Log.d("G1BLEManager", "TRAFFIC_LOG ${nameParts.getOrNull(2) ?: "?"} - null")
            } else {
                Log.d("G1BLEManager", "TRAFFIC_LOG ${nameParts.getOrNull(2) ?: "?"} - $packet")
                internalScope.launch {
                    writableIncoming.emit(packet)
                }
            }
        }
        enableNotifications(notificationCharacteristic)
            .done {
                startHeartbeat()
            }
            .fail { _, status ->
                Log.e(
                    "G1BLEManager",
                    "Failed to enable notifications for $deviceName (status: $status)"
                )
            }
            .enqueue()
    }

    //

    fun send(packet: OutgoingPacket): Boolean {
        Log.d("G1BLEManager", "G1_TRAFFIC_SEND ${packet.bytes.map { String.format("%02x", it) }.joinToString(" ")}")

        return writeTx(packet.bytes)
    }

    fun writeTx(data: ByteArray): Boolean {
        val characteristic = writeCharacteristic
        if (characteristic == null) {
            Log.e("G1BLEManager", "UART write characteristic missing for $deviceName")
            return false
        }

        var attemptsRemaining = 3
        var success: Boolean = false
        while(!success && attemptsRemaining > 0) {
            if(--attemptsRemaining != 2) {
                Log.d("G1BLEManager", "G1_TRAFFIC_SEND retrying, attempt ${3-attemptsRemaining}")
            }
            success = try {
                writeCharacteristic(
                    characteristic,
                    data,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ).await()
                true
            } catch (e: Exception) {
                val attemptNumber = 3 - attemptsRemaining
                Log.e(
                    "G1BLEManager",
                    "Failed to send packet on attempt $attemptNumber (status=${writableConnectionState.value}): ${e.message}",
                    e
                )
                false
            }
        }
        return success
    }

    //

    override fun getMinLogPriority(): Int {
        return Log.VERBOSE
    }
    override fun log(priority: Int, message: String) {
        Log.println(priority, "G1BLEManager", message)
    }

    //

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(UUID.fromString(UART_SERVICE_UUID))
        if(service != null) {
            val write = service.getCharacteristic(UUID.fromString(UART_WRITE_CHARACTERISTIC_UUID))
            val read = service.getCharacteristic(UUID.fromString(UART_READ_CHARACTERISTIC_UUID))

            if(write == null) {
                Log.e("G1BLEManager", "UART write characteristic missing for $deviceName")
                return false
            }
            if(!write.supportsWriting()) {
                Log.e("G1BLEManager", "UART write characteristic missing write properties for $deviceName")
                return false
            }

            if(read == null) {
                Log.e("G1BLEManager", "UART read characteristic missing for $deviceName")
                return false
            }
            if(!read.supportsNotifications()) {
                Log.e("G1BLEManager", "UART read characteristic missing notify/indicate properties for $deviceName")
                return false
            }

            writeCharacteristic = write
            readCharacteristic = read
            deviceGatt = gatt
            gatt.setCharacteristicNotification(read, true)
            attach(gatt)
            logFirmwareServices(gatt)
            return true
        }
        Log.e("G1BLEManager", "UART service missing for $deviceName")
        return false
    }


    override fun onServicesInvalidated() {
        writeCharacteristic = null
        readCharacteristic = null
        stopHeartbeat()
        writableConnectionState.value = G1.ConnectionState.DISCONNECTED
    }

    internal fun connectIfNeeded(device: BluetoothDevice): ConnectRequest? {
        val address = device.address
        if (!address.isNullOrEmpty()) {
            consumePendingGatt(address)
        }
        if (isConnected) {
            return null
        }
        return connect(device)
    }

    internal fun currentGatt(): BluetoothGatt? = deviceGatt

    suspend fun disconnectAndClose() {
        stopHeartbeat()
        val read = readCharacteristic
        if (read != null) {
            runCatching { disableNotifications(read).suspend() }
                .onFailure { Log.w("G1BLEManager", "disableNotifications failed for $deviceName", it) }
        }
        runCatching { disconnect().suspend() }
            .onFailure { Log.w("G1BLEManager", "disconnect failed for $deviceName", it) }
        close()
        scopeJob.cancel()
        remove(deviceAddress)
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) {
            return
        }
        heartbeatJob = internalScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!writeTx(byteArrayOf(0x25))) {
                    Log.w("G1BLEManager", "Failed to send heartbeat for $deviceName")
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
            "G1BLEManager",
            "Firmware services detected for $deviceName - SMP: $hasSmp, DFU: $hasDfu"
        )
    }
}
