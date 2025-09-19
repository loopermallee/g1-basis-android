// IG1ServiceClient.aidl
package io.texne.g1.basis.service.protocol;

import io.texne.g1.basis.service.protocol.GestureCallback;
import io.texne.g1.basis.service.protocol.ObserveStateCallback;
import io.texne.g1.basis.service.protocol.OperationCallback;

interface IG1ServiceClient {
    void observeState(ObserveStateCallback callback);
    void displayTextPage(String id, in String[] page, @nullable OperationCallback callback);
    void stopDisplaying(String id, @nullable OperationCallback callback);
    void observeHudGestures(GestureCallback callback);
}
