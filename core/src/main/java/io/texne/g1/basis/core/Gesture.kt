package io.texne.g1.basis.core

/**
 * Gestures detected from unsolicited packets emitted by the G1 glasses.
 */
enum class GlassesGesture(val rawCode: Int) {
    SINGLE_TAP(0x01),
    DOUBLE_TAP(0x02),
    SWIPE_FORWARD(0x03),
    SWIPE_BACK(0x04),
    LONG_PRESS(0x05),
    UNKNOWN(-1);

    companion object {
        fun fromRaw(rawCode: Int): GlassesGesture = when (rawCode) {
            SINGLE_TAP.rawCode -> SINGLE_TAP
            DOUBLE_TAP.rawCode -> DOUBLE_TAP
            SWIPE_FORWARD.rawCode -> SWIPE_FORWARD
            SWIPE_BACK.rawCode -> SWIPE_BACK
            LONG_PRESS.rawCode -> LONG_PRESS
            else -> UNKNOWN
        }
    }
}
