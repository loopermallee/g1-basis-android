package io.texne.g1.basis.core

// bluetooth device name ---------------------------------------------------------------------------

const val DEVICE_NAME_PREFIX = "Even G1_"

// hardware ids ------------------------------------------------------------------------------------

const val UART_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
const val UART_WRITE_CHARACTERISTIC_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
const val UART_READ_CHARACTERISTIC_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

// request protocol --------------------------------------------------------------------------------

enum class RequestType(val byte: Byte) {
    // 0x00
    // 0x01 -- initialize?,
    // 0x02 -- antishake?,
    SILENT_MODE(0x03),
    // 0x04
    // 0x05 -- turn on and off logging?
    DASHBOARD(0x06),
    // 0x07 -- start countdown?
    // 0x08
    TELEPROMPTER(0x09),
    NAVIGATION(0x0A),
    // 0x0B
    // 0x0C
    // 0x0D -- translation related?
    MICROPHONE(0x0E),
    // 0x0F -- translation related?
    // 0x10-0x20
    QUICK_NOTE(0x21),
    // 0x22 -- dashboard?
    // 0x23-0x24
    HEARTBEAT(0x25),
    // 0x26-0x4A
    NOTIFICATION(0x4B),
    // 0x4C
    // 0x4D -- initialize?
    SEND_AI_RESULT(0x4E),
    // 0x4F-0xF0
    RECEIVE_MIC_DATA(0xF1.toByte()),
    // 0xF2-0xF4
    START_AI(0xF5.toByte())
    // 0xF6-0xFF
}

abstract class RequestSubType(val byte: Byte)

// response protocol -------------------------------------------------------------------------------

enum class ResponseType(val byte: Byte) {
    // 0x00-0x24
    HEARTBEAT(0x25)
    // 0x26-0xFF
}

abstract class ResponseSubType(val byte: Byte)

// packets -----------------------------------------------------------------------------------------

abstract class Packet(
    val bytes: ByteArray
)

abstract class RequestPacket(
    val type: RequestType,
    val sequence: UByte,
    val subtype: RequestSubType,
    val data: ByteArray
): Packet(
    byteArrayOf(
        type.byte,
        ((5+data.size) and 0xFF).toByte(),
        (((5+data.size) shr 8) and 0xFF).toByte(),
        sequence.toByte(),
        subtype.byte
    ).plus(data)
)

abstract class ResponsePacket(bytes: ByteArray): Packet(bytes) {

    val type = if(bytes.isEmpty()) null else bytes[0]

    companion object {
        fun fromBytes(bytes: ByteArray): ResponsePacket =
            if(bytes.isEmpty()) {
                EmptyResponsePacket()
            } else {
                when (bytes[0]) {
                    ResponseType.HEARTBEAT.byte -> {
                        HeartbeatResponsePacket(bytes)
                    }

                    else -> {
                        UnknownResponse(bytes)
                    }
                }
            }
    }
}

// actual requests ---------------------------------------------------------------------------------

class HeartbeatRequestSubType: RequestSubType(0x04)

class HeartbeatRequestPacket: RequestPacket(
    RequestType.HEARTBEAT,
    0x01.toUByte(),
    HeartbeatRequestSubType(),
    byteArrayOf(0x01)
)

// actual packets ----------------------------------------------------------------------------------

class HeartbeatResponsePacket(bytes: ByteArray): ResponsePacket(bytes)

class EmptyResponsePacket: ResponsePacket(byteArrayOf())

class UnknownResponse(bytes: ByteArray): ResponsePacket(bytes) {
    val rest = bytes.drop(1)
}

//--------------------------------------------------------------------------------------------------