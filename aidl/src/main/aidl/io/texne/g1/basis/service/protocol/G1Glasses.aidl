// G1Glasses.aidl
package io.texne.g1.basis.service.protocol;

parcelable G1Glasses {
    const int UNINITIALIZED = 0;
    const int DISCONNECTED = 1;
    const int CONNECTING = 2;
    const int CONNECTED = 3;
    const int DISCONNECTING = 4;
    const int ERROR = 666;
    const int MTU_UNKNOWN = 0;

    String id;
    String name;
    int connectionState;
    int batteryPercentage;
    int leftConnectionState;
    int rightConnectionState;
    int leftBatteryPercentage;
    int rightBatteryPercentage;
    int signalStrength;
    int rssi;
    String leftMacAddress;
    String rightMacAddress;
    int leftNegotiatedMtu;
    int rightNegotiatedMtu;
    long leftLastConnectionAttemptMillis;
    long rightLastConnectionAttemptMillis;
    long leftLastConnectionSuccessMillis;
    long rightLastConnectionSuccessMillis;
    long leftLastDisconnectMillis;
    long rightLastDisconnectMillis;
    long lastConnectionAttemptMillis;
    long lastConnectionSuccessMillis;
    long lastDisconnectMillis;
}
