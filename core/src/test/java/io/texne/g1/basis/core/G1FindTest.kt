package io.texne.g1.basis.core

import android.bluetooth.BluetoothDevice
import io.mockk.every
import io.mockk.mockk
import io.texne.g1.basis.core.G1Gesture
import no.nordicsemi.android.support.v18.scanner.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

        val identifiers = completed.map { it.identifier }
        assertEquals(listOf("Even G1_7", "Even G1_7"), identifiers)
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
    fun `pairs without numeric segment are grouped`() {
        val foundAddresses = mutableListOf<String>()
        val foundPairs = mutableMapOf<String, G1.Companion.FoundPair>()

        val results = listOf(
            fakeScanResult("AA:BB:CC:00:01", "Even G1_L"),
            fakeScanResult("AA:BB:CC:00:02", "Even G1_R"),
        )

        val completed = G1.collectCompletePairs(results, foundAddresses, foundPairs)

        assertEquals(1, completed.size)
        assertTrue(foundPairs.isEmpty())
        assertEquals("Even G1", completed.first().identifier)
        assertEquals(
            listOf(
                "AA:BB:CC:00:01",
                "AA:BB:CC:00:02",
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

    private fun fakeScanResult(address: String, name: String): ScanResult {
        val device = mockk<BluetoothDevice>()
        every { device.address } returns address
        every { device.name } returns name
        val scanResult = mockk<ScanResult>()
        every { scanResult.device } returns device
        return scanResult
    }
}
