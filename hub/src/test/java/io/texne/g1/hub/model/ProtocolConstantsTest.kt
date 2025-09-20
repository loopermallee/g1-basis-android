package io.texne.g1.hub.model

import io.texne.g1.basis.service.protocol.RSSI_UNKNOWN
import io.texne.g1.basis.service.protocol.SIGNAL_STRENGTH_UNKNOWN
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolConstantsTest {
    @Test
    fun `protocol constants are accessible from hub`() {
        assertEquals(-1, SIGNAL_STRENGTH_UNKNOWN)
        assertEquals(Int.MIN_VALUE, RSSI_UNKNOWN)
    }
}
