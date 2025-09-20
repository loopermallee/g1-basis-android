package io.texne.g1.basis.core

import android.bluetooth.BluetoothDevice
import io.mockk.every
import io.mockk.mockk
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
            val leftName = pair.left?.device?.name
            val rightName = pair.right?.device?.name
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
    fun `asymmetric suffix pairs normalize to same key`() {
        val foundAddresses = mutableListOf<String>()
        val foundPairs = mutableMapOf<String, G1.Companion.FoundPair>()

        val results = listOf(
            fakeScanResult("AA:BB:CC:DD:EE:10", "Even G1_7_L_CEOCDF"),
            fakeScanResult("AA:BB:CC:DD:EE:11", "Even G1_7_R_1D7162"),
            fakeScanResult("AA:BB:CC:DD:EE:12", "Even G1_7_L_unpaired"),
            fakeScanResult("AA:BB:CC:DD:EE:13", "Even G1_7_R_different"),
        )

        val completed = G1.collectCompletePairs(results, foundAddresses, foundPairs)

        assertEquals("Expected one completed pair", 1, completed.size)
        val pair = completed.single()
        assertEquals("Even G1_7_L_CEOCDF", pair.left?.device?.name)
        assertEquals("Even G1_7_R_1D7162", pair.right?.device?.name)

        assertEquals(
            setOf("Even G1_7_unpaired", "Even G1_7_different"),
            foundPairs.keys
        )
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

    private fun fakeScanResult(address: String, name: String): ScanResult {
        val device = mockk<BluetoothDevice>()
        every { device.address } returns address
        every { device.name } returns name
        val scanResult = mockk<ScanResult>()
        every { scanResult.device } returns device
        return scanResult
    }
}
