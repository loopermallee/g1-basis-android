package io.texne.g1.basis.core

// bluetooth device name ---------------------------------------------------------------------------

const val DEVICE_NAME_PREFIX = "Even G1_"

// hardware ids ------------------------------------------------------------------------------------

const val UART_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
const val UART_WRITE_CHARACTERISTIC_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
const val UART_READ_CHARACTERISTIC_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

// request protocol --------------------------------------------------------------------------------

enum class OutgoingPacketType(val byte: Byte) {
                                                                                             // 0x00
    BRIGHTNESS(0x01),
                                                                               // 0x02 -- antishake?
    SILENT_MODE(0x03),
    SETUP(0x04),
                                                                 // 0x05 -- turn on and off logging?
    SHOW_DASHBOARD(0x06),
                                                                         // 0x07 -- start countdown?
                                                                                             // 0x08
    TELEPROMPTER(0x09),
    NAVIGATION(0x0A),
    HEADUP_ANGLE(0x0B),
                                                                                             // 0x0C
                                                                     // 0x0D -- translation related?
    MICROPHONE(0x0E),
                                                                     // 0x0F -- translation related?
                                                                                        // 0x10-0x14
    BMP(0x15),
    CRC(0x16),
                                                                                        // 0x17-0x1D
    ADD_QUICK_NOTE(0x1E),
                                                                                        // 0x1F-0x20
    QUICK_NOTE(0x21),
    DASHBOARD(0x22),
                                                                                        // 0x23-0x24
//    HEARTBEAT(0x25),                                     unused, even realities uses battery check
    DASHBOARD_POSITION(0x26),
    GLASS_WEARING(0x27),
                                                                                        // 0x28-0x2B
    BATTERY_LEVEL(0x2C),
                                                                                        // 0x2D-0x4A
    NOTIFICATION(0x4B),
                                                                                             // 0x4C
    INITIALIZE(0x4D),
    SEND_AI_RESULT(0x4E),
                                                                                        // 0x4F-0xF0
    RECEIVE_MIC_DATA(0xF1.toByte()),
                                                                                        // 0xF2-0xF4
    START_AI(0xF5.toByte());
                                                                                        // 0xF6-0xFF



    override fun toString(): String =
        when(this.byte) {
            BRIGHTNESS.byte -> "BRIGHTNESS"
            SILENT_MODE.byte -> "SILENT_MODE"
            SETUP.byte -> "SETUP"
            SHOW_DASHBOARD.byte -> "SHOW_DASHBOARD"
            TELEPROMPTER.byte -> "TELEPROMPTER"
            NAVIGATION.byte -> "NAVIGATION"
            HEADUP_ANGLE.byte -> "HEADUP_ANGLE"
            MICROPHONE.byte -> "MICROPHONE"
            BMP.byte -> "BMP"
            CRC.byte -> "CRC"
            ADD_QUICK_NOTE.byte -> "ADD_QUICK_NOTE"
            QUICK_NOTE.byte -> "QUICK_NOTE"
            DASHBOARD.byte -> "DASHBOARD"
            // HEARTBEAT.byte -> "HEARTBEAT"
            BATTERY_LEVEL.byte -> "BATTERY_LEVEL"
            DASHBOARD_POSITION.byte -> "DASHBOARD_POSITION"
            GLASS_WEARING.byte -> "GLASS_WEARING"
            NOTIFICATION.byte -> "NOTIFICATION"
            INITIALIZE.byte -> "INITIALIZE"
            SEND_AI_RESULT.byte -> "SEND_AI_RESULT"
            RECEIVE_MIC_DATA.byte -> "RECEIVE_MIC_DATA"
            START_AI.byte -> "START_AI"
            else -> "UNKNOWN"
        }
}

abstract class RequestSubType(val byte: Byte)

// response protocol -------------------------------------------------------------------------------

enum class IncomingPacketType(val byte: Byte?) {
    EMPTY(null),
                                                                                        // 0x00-0x24
//    HEARTBEAT(0x25),                          -- unused, even realities uses battery check instead
                                                                                        // 0x26-0x28
    // 0x29 - observed use by ER app
                                                                                        // 0x2A
    // 0x2B - observed use by ER app
    BATTERY_LEVEL(0x2C)
                                                                                        // 0x2D-0x36
    // 0x37 - observed use by ER app
                                                                                        // 0x38-0x6D
    // 0x6E - observed use by ER app
                                                                                        // 0x6F-0xFF
    ;


    override fun toString(): String =
        when(this.byte) {
            null -> "EMPTY"
//            HEARTBEAT.byte -> "HEARTBEAT"
            BATTERY_LEVEL.byte -> "BATTERY_LEVEL"
            else -> "UNKNOWN"
        }

    companion object {
        fun from(byte: Byte?): IncomingPacketType? =
            when(byte) {
                null -> EMPTY
//                HEARTBEAT.byte -> HEARTBEAT
                BATTERY_LEVEL.byte -> BATTERY_LEVEL
                else -> null
            }
    }
}

// packets -----------------------------------------------------------------------------------------

abstract class Packet(
    val bytes: ByteArray
)

abstract class OutgoingPacket(
    val type: OutgoingPacketType,
    rest: ByteArray
): Packet(
    byteArrayOf(type.byte).plus(rest)
)

abstract class SequencedOutgoingPacket(
    type: OutgoingPacketType,
    val sequence: UByte,
    val subtype: RequestSubType,
    val data: ByteArray
): OutgoingPacket(
    type,
    byteArrayOf(
        ((5+data.size) and 0xFF).toByte(),
        (((5+data.size) shr 8) and 0xFF).toByte(),
        sequence.toByte(),
        subtype.byte
    ).plus(data)
)

abstract class IncomingPacket(bytes: ByteArray): Packet(bytes) {

    val type = IncomingPacketType.from(if(bytes.isEmpty()) null else bytes[0])

    companion object {
        fun fromBytes(bytes: ByteArray): IncomingPacket =
            if(bytes.isEmpty()) {
                EmptyIncomingPacket()
            } else {
                when (bytes[0]) {
                    // OutgoingPacketType.HEARTBEAT.byte -> HeartbeatResponsePacket(bytes)
                    OutgoingPacketType.BATTERY_LEVEL.byte -> BatteryLevelResponsePacket(bytes)
                    else -> UnknownIncomingPacket(bytes)
                }
            }
    }
}

// outgoing ////////////////////////////////////////////////////////////////////////////////////////

// heartbeat - unused, the even realities app uses battery check instead

//class HeartbeatRequestSubType: RequestSubType(0x04)
//class HeartbeatRequestPacket: SequencedOutgoingPacket(
//    OutgoingPacketType.HEARTBEAT,
//    0x01.toUByte(),
//    HeartbeatRequestSubType(),
//    byteArrayOf(0x01)
//)

// battery level request

class BatteryLevelRequestPacket: OutgoingPacket(
    OutgoingPacketType.BATTERY_LEVEL,
    byteArrayOf(0x01.toByte())
)

// incoming ////////////////////////////////////////////////////////////////////////////////////////

// heartbeat

//class HeartbeatResponsePacket(bytes: ByteArray): IncomingPacket(bytes)

// battery level

class BatteryLevelResponsePacket(bytes: ByteArray): IncomingPacket(bytes) {
    val level = bytes[2].toInt()
    override fun toString(): String {
        return "${type} => ${level}%"
    }
}

// empty

class EmptyIncomingPacket: IncomingPacket(byteArrayOf())

// unknown

class UnknownIncomingPacket(bytes: ByteArray): IncomingPacket(bytes) {
    override fun toString(): String {
        return "UNKNOWN => ${String.format("%02d", bytes.size)} b" +
                " => ${bytes.map { it -> String.format("%02X", it) }.joinToString(" ")}"
    }
}
