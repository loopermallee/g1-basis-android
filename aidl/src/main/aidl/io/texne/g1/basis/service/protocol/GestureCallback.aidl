// GestureCallback.aidl
package io.texne.g1.basis.service.protocol;

/** Callback invoked by the service when a HUD gesture is detected. */
interface GestureCallback {
    /**
     * @param gesture Wire value matching [HudGesture.wireValue].
     */
    void onGesture(int gesture);
}
