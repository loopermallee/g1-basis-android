// G1ServiceState.aidl
package io.texne.g1.basis.service.protocol;

import io.texne.g1.basis.service.protocol.G1Glasses;
import io.texne.g1.basis.service.protocol.G1GestureEvent;

parcelable G1ServiceState {
    const int READY = 1;
    const int LOOKING = 2;
    const int LOOKED = 3;
    const int ERROR = 666;

    int status;
    G1Glasses[] glasses;
    G1GestureEvent gestureEvent;
}
