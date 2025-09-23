package io.texne.g1.basis.client

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.texne.g1.basis.service.protocol.G1Glasses
import io.texne.g1.basis.service.protocol.G1ServiceState
import io.texne.g1.basis.service.protocol.IG1ServiceClient
import io.texne.g1.basis.service.protocol.ObserveStateCallback
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class G1ServiceClient private constructor(context: Context): G1ServiceCommon<IG1ServiceClient>(context) {

    companion object {
        fun open(context: Context): G1ServiceClient? {
            val client = G1ServiceClient(context)
            client.populateBondedGlasses(context)
            val intent = Intent("io.texne.g1.basis.service.protocol.IG1ServiceClient")
            intent.setClassName("io.texne.g1.hub", "io.texne.g1.basis.service.G1Service")
            if (context.bindService(
                    intent,
                    client.serviceConnection,
                    Context.BIND_AUTO_CREATE
                )
            ) {
                return client
            }
            return null
        }

        fun openHub(context: Context) {
            context.startActivity(Intent(Intent.ACTION_MAIN).also {
                it.setClassName("io.texne.g1.hub", "io.texne.g1.hub.MainActivity")
            })
        }
    }

    override val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            name: ComponentName?,
            binder: IBinder?
        ) {
            service = IG1ServiceClient.Stub.asInterface(binder)
            service?.observeState(object : ObserveStateCallback.Stub() {
                override fun onStateChange(newState: G1ServiceState?) {
                    if(newState != null) {
                        writableState.value = State(
                            status = when(newState.status) {
                                G1ServiceState.READY -> ServiceStatus.READY
                                G1ServiceState.LOOKING -> ServiceStatus.LOOKING
                                G1ServiceState.LOOKED -> ServiceStatus.LOOKED
                                G1ServiceState.PERMISSION_REQUIRED -> ServiceStatus.PERMISSION_REQUIRED
                                G1ServiceState.ERROR -> ServiceStatus.ERROR
                                else -> ServiceStatus.ERROR
                            },
                            glasses = newState.glasses.map { glass ->
                                val status = when(glass.connectionState) {
                                    G1Glasses.UNINITIALIZED -> GlassesStatus.UNINITIALIZED
                                    G1Glasses.DISCONNECTED -> GlassesStatus.DISCONNECTED
                                    G1Glasses.CONNECTING -> GlassesStatus.CONNECTING
                                    G1Glasses.CONNECTED -> GlassesStatus.CONNECTED
                                    G1Glasses.DISCONNECTING -> GlassesStatus.DISCONNECTING
                                    else -> GlassesStatus.ERROR
                                }
                                Glasses(
                                    id = glass.id,
                                    name = glass.name,
                                    status = status,
                                    batteryPercentage = glass.batteryPercentage,
                                    leftStatus = when(glass.leftConnectionState) {
                                        G1Glasses.UNINITIALIZED -> GlassesStatus.UNINITIALIZED
                                        G1Glasses.DISCONNECTED -> GlassesStatus.DISCONNECTED
                                        G1Glasses.CONNECTING -> GlassesStatus.CONNECTING
                                        G1Glasses.CONNECTED -> GlassesStatus.CONNECTED
                                        G1Glasses.DISCONNECTING -> GlassesStatus.DISCONNECTING
                                        else -> GlassesStatus.ERROR
                                    },
                                    rightStatus = when(glass.rightConnectionState) {
                                        G1Glasses.UNINITIALIZED -> GlassesStatus.UNINITIALIZED
                                        G1Glasses.DISCONNECTED -> GlassesStatus.DISCONNECTED
                                        G1Glasses.CONNECTING -> GlassesStatus.CONNECTING
                                        G1Glasses.CONNECTED -> GlassesStatus.CONNECTED
                                        G1Glasses.DISCONNECTING -> GlassesStatus.DISCONNECTING
                                        else -> GlassesStatus.ERROR
                                    },
                                    leftBatteryPercentage = glass.leftBatteryPercentage,
                                    rightBatteryPercentage = glass.rightBatteryPercentage,
                                    signalStrength = glass.signalStrength,
                                    rssi = glass.rssi
                                )
                            }
                        )
                    }
                }
            })
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }


    @SuppressLint("MissingPermission")
    private fun populateBondedGlasses(context: Context) {
        if (writableState.value != null) {
            return
        }

        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        val bondedDevices = try {
            adapter?.bondedDevices ?: emptySet()
        } catch (_: SecurityException) {
            emptySet()
        }

        val glasses = bondedDevices
            .groupBondedDevicesByIdentifier()
            .mapNotNull(::createPlaceholderGlasses)
            .sortedBy { it.name }

        writableState.value = State(
            status = ServiceStatus.READY,
            glasses = glasses
        )
    }

    private fun Set<BluetoothDevice>.groupBondedDevicesByIdentifier(): List<BondedDevicePair> {
        val grouped = mutableMapOf<String, BondedDevicePair>()
        forEach { device ->
            val name = device.name ?: return@forEach
            if (!name.isRecognizedG1Name()) {
                return@forEach
            }
            val side = name.detectDeviceSide() ?: return@forEach
            val identifier = pairingIdentifier(name, device.address) ?: return@forEach
            val pair = grouped.getOrPut(identifier) { BondedDevicePair() }
            when (side) {
                DeviceSide.LEFT -> if (pair.left == null) pair.left = device
                DeviceSide.RIGHT -> if (pair.right == null) pair.right = device
            }
        }
        return grouped.values.toList()
    }

    private fun createPlaceholderGlasses(pair: BondedDevicePair): Glasses? {
        val left = pair.left ?: return null
        val right = pair.right ?: return null
        val displayName = left.name?.let(::displayNameFromDeviceName)
            ?: right.name?.let(::displayNameFromDeviceName)
            ?: return null
        val id = (left.address + right.address).filterNot { it == ':' }
        return Glasses(
            id = id,
            name = displayName,
            status = GlassesStatus.DISCONNECTED,
            batteryPercentage = -1,
            leftStatus = GlassesStatus.DISCONNECTED,
            rightStatus = GlassesStatus.DISCONNECTED,
            leftBatteryPercentage = -1,
            rightBatteryPercentage = -1,
        )
    }

    private enum class DeviceSide { LEFT, RIGHT }

    private data class BondedDevicePair(
        var left: BluetoothDevice? = null,
        var right: BluetoothDevice? = null,
    )

    private fun String.detectDeviceSide(): DeviceSide? {
        val segments = normalizedSegments()
        val sideIndex = segments.indexOfFirst { it.equals("L", ignoreCase = true) || it.equals("R", ignoreCase = true) }
        if (sideIndex == -1) {
            return null
        }
        val token = segments[sideIndex]
        return if (token.equals("L", ignoreCase = true)) DeviceSide.LEFT else DeviceSide.RIGHT
    }

    private fun pairingIdentifier(name: String, address: String): String? {
        if (!name.isRecognizedG1Name()) {
            return null
        }
        val segments = name.normalizedSegments()
        val sideIndex = segments.indexOfFirst { it.equals("L", ignoreCase = true) || it.equals("R", ignoreCase = true) }
        if (sideIndex == -1) {
            return null
        }

        val prefixSegments = segments.take(sideIndex).filter { it.isNotEmpty() }
        val suffixSegments = segments.drop(sideIndex + 1).filter { it.isNotEmpty() }

        if (suffixSegments.isNotEmpty()) {
            val identifierSuffix = suffixSegments.joinToString("_")
            val identifierPrefix = prefixSegments.joinToString("_")
            return listOfNotNull(
                identifierPrefix.takeIf { it.isNotEmpty() },
                identifierSuffix.takeIf { it.isNotEmpty() }
            ).joinToString("_")
        }

        val identifierPrefix = prefixSegments.joinToString("_")
        if (identifierPrefix.isNotEmpty()) {
            return identifierPrefix
        }

        val fallbackSuffix = address.replace(":", "").takeLast(6)
        return fallbackSuffix.takeIf { it.isNotEmpty() }
    }

    private fun displayNameFromDeviceName(rawName: String): String? {
        val segments = rawName.normalizedSegments()
        val first = segments.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val second = segments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return "$first.$second"
    }

    private fun String.normalizeSeparators(): String =
        if (contains("_")) this else replace('-', '_').replace(' ', '_')

    private fun String.normalizedSegments(): List<String> =
        normalizeSeparators().split("_").filter { it.isNotEmpty() }

    private fun String.isRecognizedG1Name(): Boolean {
        val normalized = lowercase(Locale.US)
        return normalized.contains("even") || normalized.contains("g1")
    }


    override suspend fun sendTextPage(id: String, page: List<String>) =
        suspendCoroutine<Boolean> { continuation ->
            service?.displayTextPage(
                id,
                page.toTypedArray(),
                object : io.texne.g1.basis.service.protocol.OperationCallback.Stub() {
                    override fun onResult(success: Boolean) {
                        continuation.resume(success)
                    }
                })
        }

    override suspend fun stopDisplaying(id: String) = suspendCoroutine<Boolean> { continuation ->
        service?.stopDisplaying(
            id,
            object : io.texne.g1.basis.service.protocol.OperationCallback.Stub() {
                override fun onResult(success: Boolean) {
                    continuation.resume(success)
                }
            })
    }
}