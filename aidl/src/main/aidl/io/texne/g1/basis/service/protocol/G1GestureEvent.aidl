// G1GestureEvent.aidl
package io.texne.g1.basis.service.protocol;

parcelable G1GestureEvent {
    const int TYPE_TAP = 1;
    const int TYPE_HOLD = 2;

    const int SIDE_LEFT = 1;
    const int SIDE_RIGHT = 2;

    int sequence;
    int type;
    int side;
    long timestampMillis;
}
