package io.texne.g1.basis.service.protocol

/**
 * Gestures originating from the G1 glasses HUD touchpad.
 *
 * Values map to the wire protocol integers delivered by the service layer via AIDL.
 */
enum class HudGesture(val wireValue: Int) {
    /** Primary activation gesture, e.g. a single tap. */
    ACTIVATE(0),

    /** Navigate forward within an interactive HUD experience (e.g. swipe forward). */
    NEXT_PAGE(1),

    /** Navigate backward within an interactive HUD experience (e.g. swipe back). */
    PREVIOUS_PAGE(2);

    companion object {
        fun fromWireValue(value: Int): HudGesture? = entries.firstOrNull { it.wireValue == value }
    }
}

fun HudGesture.toWireValue(): Int = wireValue
