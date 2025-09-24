package io.texne.g1.basis.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ConnectRequest
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.observer.ConnectionObserver
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.exception.RequestFailedException
import java.util.Timer
import java.util.TimerTask

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

private const val TAG = "G1BLEManager"
private const val TARGET_MTU = 247
private const val FALLBACK_MTU = 185
private const val HEARTBEAT_INTERVAL_MS = 30_000L

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
    private val appContext: Context = context.applicationContext
    private val bondRecoveryInProgress = AtomicBoolean(false)

    private val writableConnectionState = MutableStateFlow<G1.ConnectionState>(G1.ConnectionState.DISCONNECTED)
    val connectionState = writableConnectionState.asStateFlow()
    private val writableIncoming = MutableSharedFlow<IncomingPacket>()
    val incoming = writableIncoming.asSharedFlow()

    private var deviceGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var heartbeatTimer: Timer? = null
    @Volatile private var isDeviceReady: Boolean = false
    @Volatile private var notificationsEnabled: Boolean = false

    override fun initialize() {
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                Log.d(TAG, "Device connecting: ${device.address}")
                BleLogger.debug(TAG, "Device connecting for $deviceName (${device.address})")
                isDeviceReady = false
                writableConnectionState.value = G1.ConnectionState.CONNECTING
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                Log.d(TAG, "Device connected: ${device.address}")
                BleLogger.info(TAG, "Device connected for $deviceName (${device.address})")
                writableConnectionState.value = G1.ConnectionState.CONNECTED
                requestMtuForConnection(device)
                if (!notificationsEnabled) {
                    enableRxNotifications("onDeviceConnected")
                }
                startHeartbeat(device)
            }

            override fun onDeviceReady(device: BluetoothDevice) {
                Log.d(TAG, "Device ready: ${device.address}")
                BleLogger.info(TAG, "Device ready for $deviceName (${device.address})")
                isDeviceReady = true
                writableConnectionState.value = G1.ConnectionState.CONNECTED
                if (!notificationsEnabled) {
                    enableRxNotifications("onDeviceReady")
                }
                if (heartbeatTimer == null) {
                    startHeartbeat(device)
                }
            }

            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                Log.e(TAG, "Connection failed: ${device.address}, reason: $reason")
                BleLogger.error(TAG, "Connection failed for $deviceName (${device.address}) (reason=$reason)")
                writableConnectionState.value = G1.ConnectionState.ERROR
                cleanup()
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                Log.d(TAG, "Device disconnecting: ${device.address}")
                BleLogger.info(TAG, "Device disconnecting for $deviceName (${device.address})")
                writableConnectionState.value = G1.ConnectionState.DISCONNECTING
                stopHeartbeat()
                isDeviceReady = false
                notificationsEnabled = false
            }

            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                Log.d(TAG, "Device disconnected: ${device.address}")
                BleLogger.info(TAG, "Device disconnected for $deviceName (${device.address}) (reason=$reason)")
                cleanup()
                writableConnectionState.value = G1.ConnectionState.DISCONNECTED
            }
        })
        val notificationCharacteristic = readCharacteristic
        if (notificationCharacteristic == null) {
            BleLogger.warn(TAG, "Read characteristic unavailable; skipping notification subscription for $deviceName")
            return
        }

        setNotificationCallback(notificationCharacteristic).with { device, data ->
            val nameParts = device.name.orEmpty().split('_')
            val packet = IncomingPacket.fromBytes(data.toByteArray())
            if (packet == null) {
                Log.d(TAG, "TRAFFIC_LOG ${nameParts.getOrNull(2) ?: "?"} - null")
            } else {
                Log.d(TAG, "TRAFFIC_LOG ${nameParts.getOrNull(2) ?: "?"} - $packet")
                internalScope.launch {
                    writableIncoming.emit(packet)
                }
            }
        }
        if (!notificationsEnabled) {
            enableRxNotifications("initialize")
        }
    }

    //

    fun send(packet: OutgoingPacket): Boolean {
        Log.d("G1BLEManager", "G1_TRAFFIC_SEND ${packet.bytes.map { String.format("%02x", it) }.joinToString(" ")}")

        return writeTx(packet.bytes)
    }

    fun writeTx(data: ByteArray): Boolean {
        val characteristic = writeCharacteristic
        if (characteristic == null) {
            BleLogger.error("G1BLEManager", "UART write characteristic missing for $deviceName")
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
                BleLogger.error(
                    "G1BLEManager",
                    "Failed to send packet on attempt $attemptNumber (status=${writableConnectionState.value}): ${e.message}",
                    e
                )
                if (e is RequestFailedException) {
                    scheduleBondRecovery(deviceGatt?.device, e.status, "writeTx")
                }
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
        BleLogger.log(priority, "G1BLEManager", message)
    }

    //

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(UUID.fromString(UART_SERVICE_UUID))
        if(service != null) {
            val write = service.getCharacteristic(UUID.fromString(UART_WRITE_CHARACTERISTIC_UUID))
            val read = service.getCharacteristic(UUID.fromString(UART_READ_CHARACTERISTIC_UUID))

            if(write == null) {
                BleLogger.error("G1BLEManager", "UART write characteristic missing for $deviceName")
                return false
            }
            if(!write.supportsWriting()) {
                BleLogger.error("G1BLEManager", "UART write characteristic missing write properties for $deviceName")
                return false
            }

            if(read == null) {
                BleLogger.error("G1BLEManager", "UART read characteristic missing for $deviceName")
                return false
            }
            if(!read.supportsNotifications()) {
                BleLogger.error("G1BLEManager", "UART read characteristic missing notify/indicate properties for $deviceName")
                return false
            }

            writeCharacteristic = write
            readCharacteristic = read
            deviceGatt = gatt
            gatt.setCharacteristicNotification(read, true)
            attach(gatt)
            logFirmwareServices(gatt)
            BleLogger.info("G1BLEManager", "UART service ready for $deviceName")
            return true
        }
        BleLogger.error("G1BLEManager", "UART service missing for $deviceName")
        return false
    }


    override fun onServicesInvalidated() {
        writeCharacteristic = null
        readCharacteristic = null
        cleanup()
        writableConnectionState.value = G1.ConnectionState.DISCONNECTED
    }

    fun connectIfNeeded(device: BluetoothDevice): ConnectRequest? {
        val address = device.address
        if (!address.isNullOrEmpty()) {
            val pending = consumePendingGatt(address)
            if (pending != null) {
                BleLogger.info("G1BLEManager", "Consumed pending GATT for $deviceName ($address)")
            }
        }
        if (isConnected) {
            BleLogger.debug("G1BLEManager", "Already connected to $deviceName; reusing session")
            return null
        }
        BleLogger.info("G1BLEManager", "Creating connect request for $deviceName")
        return connect(device)
    }

    fun currentGatt(): BluetoothGatt? = deviceGatt

    suspend fun disconnectAndClose() {
        cleanup()
        val read = readCharacteristic
        if (read != null) {
            runCatching { disableNotifications(read).suspend() }
                .onFailure { BleLogger.warn(TAG, "disableNotifications failed for $deviceName", it) }
        }
        runCatching { disconnect().suspend() }
            .onFailure { BleLogger.warn(TAG, "disconnect failed for $deviceName", it) }
        close()
        scopeJob.cancel()
        remove(deviceAddress)
    }

    private fun requestMtuForConnection(device: BluetoothDevice) {
        BleLogger.info(TAG, "Requesting MTU $TARGET_MTU for $deviceName (${device.address})")
        requestMtu(TARGET_MTU)
            .done { _, mtu ->
                BleLogger.info(TAG, "MTU negotiated for $deviceName (mtu=$mtu)")
            }
            .fail { _, status ->
                BleLogger.warn(TAG, "MTU request $TARGET_MTU failed for $deviceName (status=$status)")
                if (FALLBACK_MTU < TARGET_MTU) {
                    BleLogger.info(TAG, "Retrying MTU request with fallback $FALLBACK_MTU for $deviceName")
                    requestMtu(FALLBACK_MTU)
                        .done { _, mtu ->
                            BleLogger.info(TAG, "Fallback MTU negotiated for $deviceName (mtu=$mtu)")
                        }
                        .fail { _, fallbackStatus ->
                            BleLogger.error(TAG, "Fallback MTU request failed for $deviceName (status=$fallbackStatus)")
                        }
                        .enqueue()
                }
            }
            .enqueue()
    }

    private fun enableRxNotifications(origin: String): Boolean {
        if (notificationsEnabled) {
            BleLogger.debug(TAG, "Notifications already enabled for $deviceName (origin=$origin)")
            return true
        }
        val notificationCharacteristic = readCharacteristic
        if (notificationCharacteristic == null) {
            BleLogger.warn(TAG, "Read characteristic unavailable while enabling notifications for $deviceName (origin=$origin)")
            return false
        }
        BleLogger.info(TAG, "Enabling notifications for $deviceName (origin=$origin)")
        enableNotifications(notificationCharacteristic)
            .done {
                BleLogger.info(TAG, "Notifications enabled for $deviceName (origin=$origin)")
                notificationsEnabled = true
            }
            .fail { device, status ->
                BleLogger.error(TAG, "Failed to enable notifications for $deviceName (status=$status, origin=$origin)")
                notificationsEnabled = false
                scheduleBondRecovery(device, status, "enableNotifications:$origin")
            }
            .enqueue()
        return true
    }

    private fun startHeartbeat(device: BluetoothDevice) {
        if (heartbeatTimer != null) {
            BleLogger.debug(TAG, "Heartbeat already scheduled for $deviceName")
            return
        }
        val txCharacteristic = writeCharacteristic
        if (txCharacteristic == null) {
            BleLogger.warn(TAG, "Cannot start heartbeat for $deviceName; TX characteristic unavailable")
            return
        }
        BleLogger.info(TAG, "Starting heartbeat every ${HEARTBEAT_INTERVAL_MS}ms for $deviceName")
        heartbeatTimer = Timer("g1-heartbeat-${device.address}", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    if (!isDeviceReady) {
                        return
                    }
                    val characteristic = writeCharacteristic
                    if (characteristic == null) {
                        BleLogger.warn(TAG, "Heartbeat write characteristic unavailable for $deviceName")
                        return
                    }
                    writeCharacteristic(characteristic, byteArrayOf(0x25.toByte()))
                        .fail { _, status ->
                            BleLogger.warn(TAG, "Heartbeat write failed for $deviceName (status=$status)")
                        }
                        .enqueue()
                }
            }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun stopHeartbeat() {
        heartbeatTimer?.let {
            BleLogger.info(TAG, "Stopping heartbeat for $deviceName")
            it.cancel()
            it.purge()
        }
        heartbeatTimer = null
    }

    private fun cleanup() {
        stopHeartbeat()
        isDeviceReady = false
        notificationsEnabled = false
    }

    private fun Int.isInsufficientAuthenticationStatus(): Boolean =
        this == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
            this == BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION

    private fun scheduleBondRecovery(device: BluetoothDevice?, status: Int, stage: String) {
        if (!status.isInsufficientAuthenticationStatus()) {
            return
        }
        val target = device ?: deviceGatt?.device
        if (target == null) {
            BleLogger.warn(
                "G1BLEManager",
                "Bond recovery requested but device unavailable for $deviceName (stage=$stage)"
            )
            return
        }
        if (!bondRecoveryInProgress.compareAndSet(false, true)) {
            BleLogger.debug(
                "G1BLEManager",
                "Bond recovery already in progress for $deviceName (stage=$stage)"
            )
            return
        }
        internalScope.launch {
            try {
                BleLogger.info(
                    "G1BLEManager",
                    "Attempting bond recovery for $deviceName (stage=$stage, status=$status)"
                )
                val bonded = ensureBond(appContext, target, DEFAULT_BOND_TIMEOUT_MS, "G1BLEManager")
                if (!bonded) {
                    BleLogger.warn(
                        "G1BLEManager",
                        "Bond recovery failed for $deviceName (stage=$stage)"
                    )
                    return@launch
                }
                BleLogger.info(
                    "G1BLEManager",
                    "Bond recovery succeeded for $deviceName (stage=$stage); disconnecting for retry"
                )
                runCatching { disconnect().suspend() }
                    .onFailure {
                        BleLogger.warn(
                            "G1BLEManager",
                            "disconnect during bond recovery failed for $deviceName (stage=$stage)",
                            it
                        )
                    }
            } finally {
                bondRecoveryInProgress.set(false)
            }
        }
    }

    private fun logFirmwareServices(gatt: BluetoothGatt) {
        val hasSmp = gatt.getService(UUID.fromString(SMP_SERVICE_UUID)) != null
        val hasDfu = gatt.getService(UUID.fromString(DFU_SERVICE_UUID)) != null
        BleLogger.debug(
            "G1BLEManager",
            "Firmware services detected for $deviceName - SMP: $hasSmp, DFU: $hasDfu"
        )
    }
}
