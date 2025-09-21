// G1ScanResult.aidl
package io.texne.g1.basis.service.protocol;

parcelable G1ScanResult {
    String id;
    String name;
    int signalStrength;
    int rssi;
    long timestampMillis;
}
