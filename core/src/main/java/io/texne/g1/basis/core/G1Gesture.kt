package io.texne.g1.basis.core

sealed class G1Gesture(
    open val side: Side,
    open val timestampMillis: Long
) {
    abstract val type: Type

    enum class Type { TAP, HOLD }
    enum class Side { LEFT, RIGHT }

    data class Tap(
        override val side: Side,
        override val timestampMillis: Long
    ): G1Gesture(side, timestampMillis) {
        override val type: Type = Type.TAP
    }

    data class Hold(
        override val side: Side,
        override val timestampMillis: Long
    ): G1Gesture(side, timestampMillis) {
        override val type: Type = Type.HOLD
    }
}
