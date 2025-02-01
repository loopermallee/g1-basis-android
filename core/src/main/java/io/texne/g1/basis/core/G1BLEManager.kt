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
        createBondInsecure()
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
        setNotificationCallback(
            readCharacteristic
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
        enableNotifications(readCharacteristic)
    }

    //

    fun send(packet: OutgoingPacket) {
        Log.d("G1BLEManager", "G1_TRAFFIC_SEND ${packet.bytes.map { String.format("%02x", it) }.joinToString(" ")}")
        writeCharacteristic(writeCharacteristic!!, packet.bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).await()
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
            if(write != null) {
                val read = service.getCharacteristic(UUID.fromString(UART_READ_CHARACTERISTIC_UUID))
                if(read != null) {
                    writeCharacteristic = write
                    readCharacteristic = read
                    deviceGatt = gatt
                    gatt.setCharacteristicNotification(read, true)
                    return true
                }
            }
        }
        return false
    }


    override fun onServicesInvalidated() {
        writeCharacteristic = null
        readCharacteristic = null
        writableConnectionState.value = G1.ConnectionState.DISCONNECTED
    }
}
