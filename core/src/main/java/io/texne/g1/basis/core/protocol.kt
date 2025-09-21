package io.texne.g1.basis.core

// hardware ids ------------------------------------------------------------------------------------

const val UART_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
const val UART_WRITE_CHARACTERISTIC_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
const val UART_READ_CHARACTERISTIC_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
const val SMP_SERVICE_UUID = "8D53DC1D-1DB7-4CD3-868B-8A527460AA84"
const val DFU_SERVICE_UUID = "00001530-1212-EFDE-1523-785FEABCD123"

// request protocol --------------------------------------------------------------------------------

enum class OutgoingPacketType(val label: String) {
    EXIT("EXIT"),                                                                             // x18
    GET_BATTERY_LEVEL("GET_BATTERY_LEVEL"),                                                   // x2C
    SEND_AI_RESULT("SEND_AI_RESULT"),                                                         // x4E
    ;
    override fun toString() = label
}

                                        // still unmapped ------------------------------------------

                                                        //    BRIGHTNESS(0x01),
                                                                                // x02 -- antishake?
                                                        //    SILENT_MODE(0x03),
                                                        //    SETUP(0x04),
                                                                  // x05 -- turn on and off logging?
                                                        //    SHOW_DASHBOARD(0x06),
                                                                          // x07 -- start countdown?
                                                                                              // x08
                                                        //    TELEPROMPTER(0x09),
                                                        //    NAVIGATION(0x0A),
                                                        //    HEADUP_ANGLE(0x0B),
                                                                                              // x0C
                                                                      // x0D -- translation related?
                                                        //    MICROPHONE(0x0E),
                                                                      // x0F -- translation related?
                                                                                         // x10-0x14
                                                        //    BMP(0x15),
                                                        //    CRC(0x16),
                                                                                         // x17-0x1D
                                                        //    ADD_QUICK_NOTE(0x1E),
                                                                                         // x1F-0x20
                                                        //    QUICK_NOTE(0x21),
                                                        //    DASHBOARD(0x22),
                                                        //    FIRMWARE(0x23),
                                                                                              // x24
                                                        //    HEARTBEAT(0x25),
                                                        //    DASHBOARD_POSITION(0x26),
                                                        //    GLASS_WEARING(0x27),
                                                                                         // x28-0x2B
                                                        //    NOTIFICATION(0x4B),
                                                                                              // x4C
                                                        //    INITIALIZE(0x4D),
                                                                                         // x4F-0xF0
                                                        //    RECEIVE_MIC_DATA(0xF1.toByte()),
                                                                                         // xF2-0xF4
                                                        //    START_AI(0xF5.toByte()),

// response protocol -------------------------------------------------------------------------------

internal fun hasFirst(byte: Byte): (ByteArray) -> Boolean = { bytes ->
    bytes.isNotEmpty() && bytes[0] == byte
}

internal fun hasFirstTwo(first: Byte, second: Byte): (ByteArray) -> Boolean = { bytes ->
    bytes.size > 1 && bytes[0] == first && bytes[1] == second
}

internal fun ByteArray.readUInt16LittleEndian(offset: Int): Int? {
    if (size <= offset + 1) {
        return null
    }
    return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
}

enum class IncomingPacketType(
    val label: String,
    val isType: (bytes: ByteArray) -> Boolean,
    val factory: (type: IncomingPacketType, bytes: ByteArray) -> IncomingPacket?
) {
    EXIT("EXIT", hasFirst(0x18), { _, bytes -> ExitResponsePacket(bytes) }),
    GLASSES_BATTERY_LEVEL("GLASSES_BATTERY_LEVEL", hasFirstTwo(0x2C, 0x66), { _, bytes -> BatteryLevelResponsePacket(bytes) }),
    AI_RESULT_RECEIVED("AI_RESULT_RECEIVED", hasFirst(0x4E), { _, bytes -> SendTextResponsePacket(bytes) }),
    DASHBOARD_STATUS("DASHBOARD_STATUS", hasFirstTwo(0x22, 0x05), { _, bytes -> DashboardStatusPacket(bytes) }),
    GESTURE_TAP("GESTURE_TAP", hasFirst(0x29), { type, bytes -> GesturePacket(type, bytes, G1Gesture.Type.TAP) }),
    GESTURE_HOLD("GESTURE_HOLD", hasFirst(0x2B), { type, bytes -> GesturePacket(type, bytes, G1Gesture.Type.HOLD) }),
;
    override fun toString() = label
}

                                        // still unmapped ------------------------------------------

                                                                                        // x00-0x24
                                                        //    HEARTBEAT(0x25),
                                                                                        // x26-0x28
                                                        // x29 - observed use by ER app
                                                                                        // x2A
                                                        // x2B - observed use by ER app
                                                                                        // x2D-0x36
                                                        // x37 - observed use by ER app
                                                                                        // x38-0x4D
                                                                                        // x38-0x6D
                                                        // x6E - observed use by ER app
                                                                                        // x6F-0xFF

// outgoing ////////////////////////////////////////////////////////////////////////////////////////

abstract class OutgoingPacket(
    val type: OutgoingPacketType,
    val bytes: ByteArray = byteArrayOf()
)

// exit

class ExitRequestPacket: OutgoingPacket(
    // EXAMPLE: 18
    OutgoingPacketType.EXIT,
    byteArrayOf(0x18)
)

// battery level request

class BatteryLevelRequestPacket: OutgoingPacket(
    // EXAMPLE: 2C
    OutgoingPacketType.GET_BATTERY_LEVEL,
    byteArrayOf(0x2C)
)

// send text

class SendTextPacket(
    text: String,
    pageNumber: Int,
    maxPages: Int
): OutgoingPacket(
    // EXAMPLE: TODO
    OutgoingPacketType.SEND_AI_RESULT,
    byteArrayOf(
        0x4E.toByte(),
        0x00.toByte(),
        0x01.toByte(),
        0x00.toByte(),
        0x71.toByte(),
        0x00.toByte(),
        0x00.toByte(),
        pageNumber.toByte(),
        maxPages.toByte()
    ).plus(
        text.encodeToByteArray()
    )
)

// incoming ////////////////////////////////////////////////////////////////////////////////////////

abstract class IncomingPacket(val type: IncomingPacketType, val bytes: ByteArray, val responseTo: OutgoingPacketType? = null) {
    companion object {
        fun fromBytes(bytes: ByteArray): IncomingPacket? =
            IncomingPacketType.entries.firstOrNull { it.isType(bytes) }?.let { type ->
                type.factory(type, bytes)
            }
    }
}

// exit

class ExitResponsePacket(bytes: ByteArray): IncomingPacket(
    IncomingPacketType.EXIT,
    bytes,
    OutgoingPacketType.EXIT
) {
    // EXAMPLE: 18 c9 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
    //          18 -> packet id
    //             c9 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 -> TODO UNKNOWN
}

// battery level

class BatteryLevelResponsePacket(bytes: ByteArray): IncomingPacket(
    IncomingPacketType.GLASSES_BATTERY_LEVEL,
    bytes,
    OutgoingPacketType.GET_BATTERY_LEVEL
) {
    // EXAMPLE: 2c 66 5e 00 e6 a0 19 00 00 00 01 05 00 00 00 00 00 00 00 00
    //          2c 66 -> packet id
    //                5e 00 e6 a0 19 00 00 00 01 05 00 00 00 00 00 00 00 00 -> TODO UNKNOWN

    val level = bytes[2].toInt()
    override fun toString(): String {
        return "${type} => ${level}%"
    }
}

class DashboardStatusPacket(bytes: ByteArray): IncomingPacket(
    IncomingPacketType.DASHBOARD_STATUS,
    bytes
) {
    val countdownTicks: Int? = bytes.readUInt16LittleEndian(2)
    val currentPage: Int? = bytes.readUInt16LittleEndian(4)
    val totalPages: Int? = bytes.readUInt16LittleEndian(6)

    override fun toString(): String {
        val countdownText = countdownTicks?.let { value ->
            "${value} (0x${value.toString(16).padStart(4, '0')})"
        } ?: "null"
        val pageText = if(currentPage != null && totalPages != null) {
            "${currentPage}/${totalPages}"
        } else {
            "${currentPage ?: "null"}/${totalPages ?: "null"}"
        }
        return "${type} => countdownTicks=${countdownText}, page=${pageText}"
    }
}

// send text

class SendTextResponsePacket(bytes: ByteArray): IncomingPacket(
    IncomingPacketType.AI_RESULT_RECEIVED,
    bytes,
    OutgoingPacketType.SEND_AI_RESULT
)

// gestures

class GesturePacket(
    type: IncomingPacketType,
    bytes: ByteArray,
    val gestureType: G1Gesture.Type,
): IncomingPacket(type, bytes) {
    val timestampMillis: Long = System.currentTimeMillis()
}
