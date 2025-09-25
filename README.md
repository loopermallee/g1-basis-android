# G1 Basis

G1 Basis is an open Android stack for connecting Even Realities G1 glasses to mobile experiences. The app bundle ships with a shared background service, a Hub companion app, and sample HUD experiences that showcase how voice, chat, and task data can flow to the glasses in real time.

## Capabilities

- **Reliable connectivity.** A dedicated background service maintains simultaneous links to both arms of the glasses, negotiates MTU size, and keeps mandatory heartbeat traffic flowing so other apps can concentrate on their UX.
- **Shared access for multiple apps.** The client libraries expose simple coroutine-based APIs so several apps can talk to the same glasses without fighting over Bluetooth resources.
- **Assistant mode.** The Hub's Assistant tab streams trimmed ChatGPT responses to the glasses. Users provide their own OpenAI API key, and replies are automatically paginated into four-line, 32-character screens to keep the HUD readable at a glance.
- **Live subtitles pipeline.** Speech recognized on the phone is condensed and relayed to the HUD for near-real-time captioning while remaining open to future polish.
- **Todo HUD.** Encrypted on-device storage powers a glanceable task list with manual pagination and AI-powered add/complete/reorder commands triggered from voice or chat input.

## Pairing logic

Connecting directly to the glasses over Bluetooth Low Energy requires handling a few device-specific behaviors:

- **Dual radios.** Each arm advertises separately. Connection logic must treat them as distinct peripherals so commands can be mirrored when required.
- **Nordic UART Service (NUS).** All messaging flows through the NUS (UUID `6e400001-b5a3-f393-e0a9-e50e24dcca9e`). After connecting, request a larger MTU (for example, 251 bytes) and subscribe to notifications on the RX characteristic before sending commands.
- **Heartbeat requirement.** Send a heartbeat packet (`0x25`) every 28–30 seconds. Without it, the glasses will disconnect even if the Android system still shows the bond.
- **Bond-aware discovery.** Some firmware revisions restrict advertisement data until a bond exists. Always check `BluetoothAdapter.getBondedDevices()` before starting a fresh scan, and allow wide-open scans (no strict service filters) as a fallback.

### Recommended pairing workflow

1. **Query bonded devices.** On launch, inspect paired devices for entries containing "Even" or "G1" and connect immediately when found.
2. **Run a broad scan if needed.** When no bond exists, scan with a permissive `ScanFilter` for roughly 10 seconds and confirm the NUS service during discovery.
3. **Connect to both radios.** Maintain independent `BluetoothGatt` sessions for the left and right arms, duplicating commands that must reach both while routing side-specific operations appropriately.
4. **Negotiate MTU and schedule heartbeats.** Once connected, request the larger MTU and start the 28–30 second heartbeat timer.
5. **Log diagnostics.** Record the discovery path and key lifecycle events (MTU negotiation, heartbeat scheduling) to aid troubleshooting when pairing fails.
