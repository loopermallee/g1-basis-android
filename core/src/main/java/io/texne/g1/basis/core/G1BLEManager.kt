package io.texne.g1.basis.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.util.UUID

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

@SuppressLint("MissingPermission")
internal class G1BLEManager(private val deviceName: String, context: Context, private val coroutineScope: CoroutineScope): BleManager(context) {

    private val writableConnectionState = MutableStateFlow<G1.ConnectionState>(G1.ConnectionState.CONNECTING)
    val connectionState = writableConnectionState.asStateFlow()
    private val writableIncoming = MutableSharedFlow<IncomingPacket>()
    val incoming = writableIncoming.asSharedFlow()

    private var deviceGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null

    override fun initialize() {
        requestMtu(251)
            .enqueue()
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
            }
        })
        val notificationCharacteristic = readCharacteristic
        if (notificationCharacteristic == null) {
            Log.w("G1BLEManager", "Read characteristic unavailable; skipping notification subscription")
            return
        }

        setNotificationCallback(
            notificationCharacteristic
        ).with { device, data ->
            val split = device.name.split('_')
            val packet = IncomingPacket.fromBytes(data.toByteArray())
            if(packet == null) {
                Log.d("G1BLEManager", "TRAFFIC_LOG ${split[2]} - ${packet}")
            } else {
                Log.d("G1BLEManager", "TRAFFIC_LOG ${split[2]} - ${packet}")
                coroutineScope.launch {
                    writableIncoming.emit(packet)
                }
            }
        }
        enableNotifications(notificationCharacteristic)
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

        var attemptsRemaining = 3
        var success: Boolean = false
        while(!success && attemptsRemaining > 0) {
            if(--attemptsRemaining != 2) {
                Log.d("G1BLEManager", "G1_TRAFFIC_SEND retrying, attempt ${3-attemptsRemaining}")
            }
            success = try {
                writeCharacteristic(
                    writeCharacteristic!!,
                    packet.bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ).await()
                true
            } catch (e: Exception) {
                val attemptNumber = 3 - attemptsRemaining
                val status = writableConnectionState.value
                Log.e(
                    "G1BLEManager",
                    "Failed to send packet on attempt $attemptNumber (status=$status): ${e.message}",
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
            logFirmwareServices(gatt)
            return true
        }
        Log.e("G1BLEManager", "UART service missing for $deviceName")
        return false
    }


    override fun onServicesInvalidated() {
        writeCharacteristic = null
        readCharacteristic = null
        writableConnectionState.value = G1.ConnectionState.DISCONNECTED
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
