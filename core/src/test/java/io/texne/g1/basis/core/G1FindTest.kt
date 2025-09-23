package io.texne.g1.basis.core

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import io.mockk.every
import io.mockk.mockk
import io.texne.g1.basis.core.G1Gesture
import no.nordicsemi.android.support.v18.scanner.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import android.os.ParcelUuid
import android.util.SparseArray
import java.util.UUID

class G1FindTest {

    @Test
    fun `pairs with shared suffix are emitted independently`() {
        val foundAddresses = mutableListOf<String>()
        val foundPairs = mutableMapOf<String, G1.Companion.FoundPair>()

        val results = listOf(
            fakeScanResult("AA:BB:CC:DD:EE:01", "Even G1_7_L_alpha"),
            fakeScanResult("AA:BB:CC:DD:EE:02", "Even G1_7_R_alpha"),
            fakeScanResult("AA:BB:CC:DD:EE:03", "Even G1_7_L_beta"),
            fakeScanResult("AA:BB:CC:DD:EE:04", "Even G1_7_R_beta"),
        )

        val completed = G1.collectCompletePairs(results, foundAddresses, foundPairs)

        assertEquals("Expected two completed pairs", 2, completed.size)
        assertTrue("No partial pairs should remain", foundPairs.isEmpty())

        val emittedNames = completed.map { pair ->
            val leftName = pair.left.device.name
            val rightName = pair.right.device.name
            leftName to rightName
        }.toSet()

        val expectedNames = setOf(
            "Even G1_7_L_alpha" to "Even G1_7_R_alpha",
            "Even G1_7_L_beta" to "Even G1_7_R_beta",
        )
        assertEquals(expectedNames, emittedNames)
        assertEquals(
            listOf(
                "AA:BB:CC:DD:EE:01",
                "AA:BB:CC:DD:EE:02",
                "AA:BB:CC:DD:EE:03",
                "AA:BB:CC:DD:EE:04",
            ),
            foundAddresses
        )
    }

    @Test
    fun `pairs without suffix share base identifier`() {
        val foundAddresses = mutableListOf<String>()
        val foundPairs = mutableMapOf<String, G1.Companion.FoundPair>()

        val results = listOf(
            fakeScanResult("AA:BB:CC:DD:10:01", "Even G1_7_L"),
            fakeScanResult("AA:BB:CC:DD:10:02", "Even G1_7_R"),
            fakeScanResult("AA:BB:CC:DD:10:03", "Even G1_7_L"),
            fakeScanResult("AA:BB:CC:DD:10:04", "Even G1_7_R"),
        )

        val completed = G1.collectCompletePairs(results, foundAddresses, foundPairs)

        assertEquals("Expected two completed pairs", 2, completed.size)
        assertTrue("No partial pairs should remain", foundPairs.isEmpty())

        val identifiers = completed.map { it.identifier }.toSet()
        assertEquals("Pairs without suffix should share identifier", setOf("Even G1_7"), identifiers)
        assertEquals(
            listOf(
                "AA:BB:CC:DD:10:01",
                "AA:BB:CC:DD:10:02",
                "AA:BB:CC:DD:10:03",
                "AA:BB:CC:DD:10:04",
            ),
            foundAddresses
        )
    }

    @Test
    fun `pairs tolerate space separated names`() {
        val foundAddresses = mutableListOf<String>()
        val foundPairs = mutableMapOf<String, G1.Companion.FoundPair>()

        val results = listOf(
            fakeScanResult("AA:BB:CC:DD:21:01", "Even G1 7 L alpha"),
            fakeScanResult("AA:BB:CC:DD:21:02", "Even G1 7 R alpha"),
        )

        val completed = G1.collectCompletePairs(results, foundAddresses, foundPairs)

        assertEquals("Expected one completed pair", 1, completed.size)
        val pair = completed.first()
        assertEquals("Even G1 7 L alpha", pair.left.device.name)
        assertEquals("Even G1 7 R alpha", pair.right.device.name)
        assertTrue("No partial pairs should remain", foundPairs.isEmpty())
        assertEquals(
            listOf(
                "AA:BB:CC:DD:21:01",
                "AA:BB:CC:DD:21:02",
            ),
            foundAddresses
        )
    }

    @Test
    fun `devices with different suffixes remain partial`() {
        val foundAddresses = mutableListOf<String>()
        val foundPairs = mutableMapOf<String, G1.Companion.FoundPair>()

        val results = listOf(
            fakeScanResult("AA:BB:CC:DD:EE:10", "Even G1_7_L_CEOCDF"),
            fakeScanResult("AA:BB:CC:DD:EE:11", "Even G1_7_R_1D7162"),
            fakeScanResult("AA:BB:CC:DD:EE:12", "Even G1_7_L_unpaired"),
            fakeScanResult("AA:BB:CC:DD:EE:13", "Even G1_7_R_different"),
        )

        val completed = G1.collectCompletePairs(results, foundAddresses, foundPairs)

        assertTrue("No completed pairs should be emitted", completed.isEmpty())

        assertEquals(
            setOf(
                "Even G1_7_CEOCDF",
                "Even G1_7_1D7162",
                "Even G1_7_unpaired",
                "Even G1_7_different",
            ),
            foundPairs.keys
        )
        assertEquals("Even G1_7_L_CEOCDF", foundPairs["Even G1_7_CEOCDF"]?.left?.device?.name)
        assertEquals("Even G1_7_R_1D7162", foundPairs["Even G1_7_1D7162"]?.right?.device?.name)
        assertEquals("Even G1_7_L_unpaired", foundPairs["Even G1_7_unpaired"]?.left?.device?.name)
        assertEquals("Even G1_7_R_different", foundPairs["Even G1_7_different"]?.right?.device?.name)

        assertEquals(
            listOf(
                "AA:BB:CC:DD:EE:10",
                "AA:BB:CC:DD:EE:11",
                "AA:BB:CC:DD:EE:12",
                "AA:BB:CC:DD:EE:13",
            ),
            foundAddresses
        )
    }

    @Test
    fun `devices without names but with nus uuid are paired`() {
        val foundAddresses = mutableListOf<String>()
        val foundPairs = mutableMapOf<String, G1.Companion.FoundPair>()
        val serviceUuid = UUID.fromString(UART_SERVICE_UUID)

        val results = listOf(
            fakeScanResult(
                "AA:BB:CC:DD:40:01",
                null,
                serviceUuids = listOf(serviceUuid)
            ),
            fakeScanResult(
                "AA:BB:CC:DD:40:02",
                null,
                serviceUuids = listOf(serviceUuid)
            ),
        )

        val completed = G1.collectCompletePairs(
            results,
            foundAddresses,
            foundPairs,
            addressDedupe = mutableSetOf()
        )

        assertEquals(1, completed.size)
        val pair = completed.first()
        assertEquals("AABBCCDD40", pair.identifier)
        assertEquals("AA:BB:CC:DD:40:01", pair.left.device.address)
        assertEquals("AA:BB:CC:DD:40:02", pair.right.device.address)
        assertTrue(foundPairs.isEmpty())
        assertEquals(
            listOf("AA:BB:CC:DD:40:01", "AA:BB:CC:DD:40:02"),
            foundAddresses
        )
    }

    @Test
    fun `devices with manufacturer data but no names are paired`() {
        val foundAddresses = mutableListOf<String>()
        val foundPairs = mutableMapOf<String, G1.Companion.FoundPair>()
        val payload = "Even".encodeToByteArray()
        val manufacturerId = 0x1234

        val results = listOf(
            fakeScanResult(
                "AA:BB:CC:DD:41:01",
                null,
                manufacturerData = listOf(manufacturerId to payload)
            ),
            fakeScanResult(
                "AA:BB:CC:DD:41:02",
                null,
                manufacturerData = listOf(manufacturerId to payload)
            ),
        )

        val dedupe = mutableSetOf<String>()
        val completed = G1.collectCompletePairs(
            results,
            foundAddresses,
            foundPairs,
            addressDedupe = dedupe
        )

        assertEquals(1, completed.size)
        val pair = completed.first()
        assertTrue(pair.identifier.startsWith("AABBCCDD41_"))
        assertEquals(
            listOf("AA:BB:CC:DD:41:01", "AA:BB:CC:DD:41:02"),
            foundAddresses
        )
        assertEquals(setOf("AA:BB:CC:DD:41:01", "AA:BB:CC:DD:41:02"), dedupe)
        assertTrue(foundPairs.isEmpty())
    }

    @Test
    fun `filtering for left side still produces pairs`() {
        val foundAddresses = mutableListOf<String>()
        val foundPairs = mutableMapOf<String, G1.Companion.FoundPair>()

        val results = listOf(
            fakeScanResult("AA:BB:CC:DD:EE:20", "Even G1_7_L_gamma"),
            fakeScanResult("AA:BB:CC:DD:EE:21", "Even G1_7_R_gamma"),
        )

        val completed = G1.collectCompletePairs(
            results,
            foundAddresses,
            foundPairs,
            setOf(G1Gesture.Side.LEFT)
        )

        assertEquals(1, completed.size)
        assertTrue(foundPairs.isEmpty())
        assertEquals(
            listOf(
                "AA:BB:CC:DD:EE:20",
                "AA:BB:CC:DD:EE:21",
            ),
            foundAddresses
        )
    }

    @Test
    fun `filtering for right side tracks until left reappears`() {
        val foundAddresses = mutableListOf<String>()
        val foundPairs = mutableMapOf<String, G1.Companion.FoundPair>()

        val left = fakeScanResult("AA:BB:CC:DD:EE:30", "Even G1_7_L_delta")
        val right = fakeScanResult("AA:BB:CC:DD:EE:31", "Even G1_7_R_delta")

        var completed = G1.collectCompletePairs(
            listOf(left),
            foundAddresses,
            foundPairs,
            setOf(G1Gesture.Side.RIGHT)
        )
        assertTrue(completed.isEmpty())
        assertTrue(foundPairs.isEmpty())
        assertTrue(foundAddresses.isEmpty())

        completed = G1.collectCompletePairs(
            listOf(right),
            foundAddresses,
            foundPairs,
            setOf(G1Gesture.Side.RIGHT)
        )
        assertTrue(completed.isEmpty())
        assertEquals(setOf("Even G1_7_delta"), foundPairs.keys)

        completed = G1.collectCompletePairs(
            listOf(left),
            foundAddresses,
            foundPairs,
            setOf(G1Gesture.Side.RIGHT)
        )
        assertEquals(1, completed.size)
        assertTrue(foundPairs.isEmpty())
        assertEquals(
            listOf(
                "AA:BB:CC:DD:EE:31",
                "AA:BB:CC:DD:EE:30",
            ),
            foundAddresses
        )
    }

    @Test
    fun `bonded devices are paired immediately`() {
        val devices = linkedSetOf(
            fakeBluetoothDevice("AA:BB:CC:DD:EE:40", "Even G1_7_L_zeta"),
            fakeBluetoothDevice("AA:BB:CC:DD:EE:41", "Even G1_7_R_zeta"),
            fakeBluetoothDevice("AA:BB:CC:DD:EE:42", "Even G1_7_L_eta"),
            fakeBluetoothDevice("AA:BB:CC:DD:EE:43", "Even G1_7_R_eta"),
        )

        val completed = G1.collectBondedPairs(devices)

        assertEquals(2, completed.size)
        val emittedNames = completed.map { pair ->
            pair.left.device.name to pair.right.device.name
        }.toSet()
        val expectedNames = setOf(
            "Even G1_7_L_zeta" to "Even G1_7_R_zeta",
            "Even G1_7_L_eta" to "Even G1_7_R_eta",
        )
        assertEquals(expectedNames, emittedNames)
    }

    @Test
    fun `bonded devices honor side filters`() {
        val devices = linkedSetOf(
            fakeBluetoothDevice("AA:BB:CC:DD:EE:50", "Even G1_7_L_theta"),
            fakeBluetoothDevice("AA:BB:CC:DD:EE:51", "Even G1_7_R_theta"),
        )

        val completed = G1.collectBondedPairs(devices, setOf(G1Gesture.Side.RIGHT))

        assertEquals(1, completed.size)
        val pair = completed.first()
        assertEquals("Even G1_7_L_theta", pair.left.device.name)
        assertEquals("Even G1_7_R_theta", pair.right.device.name)
    }

    @Test
    fun `bonded devices tolerate hyphenated names`() {
        val devices = linkedSetOf(
            fakeBluetoothDevice("AA:BB:CC:DD:EE:60", "Even-G1 7 L hyphen"),
            fakeBluetoothDevice("AA:BB:CC:DD:EE:61", "Even-G1 7 R hyphen"),
        )

        val completed = G1.collectBondedPairs(devices)

        assertEquals(1, completed.size)
        val pair = completed.first()
        assertEquals("Even-G1 7 L hyphen", pair.left.device.name)
        assertEquals("Even-G1 7 R hyphen", pair.right.device.name)
    }

    private fun fakeScanResult(
        address: String,
        name: String?,
        serviceUuids: List<UUID> = emptyList(),
        manufacturerData: List<Pair<Int, ByteArray>> = emptyList(),
        rssi: Int = -60
    ): ScanResult {
        val device = mockk<BluetoothDevice>()
        every { device.address } returns address
        every { device.name } returns name
        val scanRecord = mockk<ScanRecord>()
        every { scanRecord.deviceName } returns name
        val parcelUuids = serviceUuids.takeIf { it.isNotEmpty() }?.map { ParcelUuid(it) }
        every { scanRecord.serviceUuids } returns parcelUuids
        val sparseArray = SparseArray<ByteArray>()
        manufacturerData.forEach { (id, payload) ->
            sparseArray.put(id, payload)
        }
        every { scanRecord.manufacturerSpecificData } returns sparseArray
        val scanResult = mockk<ScanResult>()
        every { scanResult.device } returns device
        every { scanResult.scanRecord } returns scanRecord
        every { scanResult.rssi } returns rssi
        return scanResult
    }

    private fun fakeBluetoothDevice(address: String, name: String): BluetoothDevice {
        val device = mockk<BluetoothDevice>()
        every { device.address } returns address
        every { device.name } returns name
        return device
    }
}
