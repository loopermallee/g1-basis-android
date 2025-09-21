# G1 Basis
Open, multipurpose infrastructure for writing Android applications that talk to the [Even Realities G1](https://www.evenrealities.com/g1) glasses.

## Roadmap

- ✅ **Phase 1 – Core connectivity foundations (100%).** Completed with the Core library and shared Service providing stable connections to the glasses, as documented below.
- ✅ **Phase 2 – Hub experience & Assistant mode (100%).** Completed; the Hub's Assistant tab streams trimmed ChatGPT responses directly to connected glasses (see [Assistant mode](#assistant-mode)).
- 🚧 **Phase 3 – Live subtitles companion app (~75%).** In progress; the shipped Subtitles pipeline captures on-device speech recognition, trims transcripts, and streams them through the current speech-recognition and HUD streaming loop while remaining polish lands (see [Subtitles](#subtitles)).
- ⏳ **Phase 4 – Expanded multi-app experiences (~30%).** Underway; the new Todo HUD adds encrypted task workflows, but broader multi-app orchestration and integrations remain (see [Hub](#hub)).
- ⏳ **Phase 5 – Community ecosystem polish (0%).** Not started; future goals include documentation, tooling, and broader release readiness efforts.

## Core
The **core** module contains the source code to the core library.
This library allows for interfacing directly with the glasses through a simple abstraction that uses modern Android and Kotlin features like coroutines and Flow.
When using this library directly, only one application can connect to and interface with the glasses at one time. The basis core library is used under the hood by the **service**.

*(more details coming soon)*

## BLE pairing insights
Working directly with the G1 over Bluetooth Low Energy requires accommodating a handful of protocol expectations that differ from typical single-radio peripherals.

### Key protocol behaviors
- **Dual radios.** The glasses expose independent left and right arm radios. Some commands must be issued to both arms while others are specific to one side, so connection logic should allow multiple simultaneous `BluetoothGatt` sessions.
- **Nordic UART Service (NUS).** All communication flows through the NUS (UUID `6e400001-b5a3-f393-e0a9-e50e24dcca9e`). Clients must negotiate an MTU (for example, 251 bytes) and subscribe to notifications on the RX characteristic (`6e400003-…`) after connecting.
- **Heartbeat.** A heartbeat packet (`0x25`) is required roughly every 28–30 seconds. If the device stops receiving heartbeats it will disconnect even if the Android system still shows the link as paired.
- **Control commands.** Higher-level operations (reboot, microphone toggles, unpair, etc.) are expressed as framed control packets (for example `0x23 0x72` for reboot, `0x0E` for microphone enable/disable). Commands must be sequenced correctly for the glasses to acknowledge them.

### Common reasons scans fail
- **Scan filters that are too strict.** Some firmware revisions advertise only manufacturer data until a bond exists, so requiring the NUS UUID in your `ScanFilter` can hide both radios.
- **Ignoring the second radio.** Treat the left and right arms as separate peripherals during discovery to avoid missing the one that happens to be advertising.
- **Missing runtime permissions.** Android 12 and newer require both `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`; without them no scan results appear.
- **Skipping bonded devices.** When the Even app or Android system pairs with the glasses they appear in `BluetoothAdapter.getBondedDevices()`. Connecting directly to those entries avoids scanning entirely.

### Recommended pairing workflow
1. **Check bonded devices first.** On the "Find Glasses" flow query `BluetoothAdapter.getBondedDevices()`, look for names containing "Even" or "G1", and call `device.connectGatt()` immediately when found.
2. **Fallback to a broad scan.** If no bonded devices match, issue an unrestricted scan (`ScanFilter.Builder().build()` with `SCAN_MODE_BALANCED`) for ~10 seconds, then confirm the NUS service during discovery.
3. **Support both radios.** Maintain connections to each arm as needed, duplicating commands that must reach both sides (such as silent mode) while targeting left/right-specific commands appropriately.
4. **Negotiate MTU and start heartbeats.** After connecting request a larger MTU (e.g., 251 bytes) and schedule heartbeat writes every 28–30 seconds to keep the link alive.
5. **Surface diagnostics.** Log the pairing path taken (bonded device vs. scan) and critical lifecycle events (MTU negotiation, heartbeat scheduling) to improve debugging and UX when pairing fails.

## Service
The **service** module implements a shared Android service that multiple applications can use to interface simultaneously with the glasses.  
Applications interface with the service using the AIDL-defined generated code and a simple wrapper that exposes the service using coroutines and flow the way the Core library does.
The service also handles requesting the necessary permissions at runtime, so calling applications do not have to.

### Releases

The latest release is available on [Maven Central](https://central.sonatype.com/artifact/io.texne.g1.basis/service)

```kotlin
implementation("io.texne.g1.basis:service:1.1.0")
```

## AIDL
The **aidl** module contains the specification of the RPC protocol between the client and service.  
It is the glue that makes it possible for multiple apps using the client to share the service (and access to the glasses).
Both the **client** and **service** module require it, but you would not need to include it or use it directly.

## Hub

The **hub** module contains the application for running the G1 service and managing connections to glasses on Android.
The application can seamlessly and reliably discover, connect and disconnect, and send commands to glasses.
It must be running on your phone and connected to the glasses for any apps using the client library to talk to them.

### Assistant mode

The Hub application now ships with an **Assistant** tab that talks directly to ChatGPT.
Bring your own OpenAI API key from the **Settings** tab and the app will store it in encrypted SharedPreferences.
Responses are auto-paginated into four-line, 32-character pages before being streamed to the connected glasses so the monochrome HUD stays glanceable.
Two starter personalities (Ershin and Fou-Lu) are available to quickly change tone and verbosity.
Responses now support interactive pagination controls so you can revisit pages from the glasses as needed.

*(more details coming soon)*

### Todo HUD

The **Todo HUD** tab introduces a secure, glanceable task list for the glasses. Tasks are stored in encrypted SharedPreferences, and streamed with the same four-line pagination used elsewhere while offering an expanded-detail mode when you want additional context. Manual HUD navigation lets you page forward or backward through tasks without touching the phone, and AI command handling turns voice- or chat-based requests into add, complete, and reorder actions on the fly.

## Client
The **client** module implements a simple native interface to the shared service.

### Releases

The latest release is available on [Maven Central](https://central.sonatype.com/artifact/io.texne.g1.basis/client)

```kotlin
implementation("io.texne.g1.basis:client:1.1.0")
```

### 1. Initialization

Initialize the client by calling

```kotlin
val client = G1ServiceClient.open(applicationContext)
if(client == null) {
    // ERROR
}
```

This starts the service (if it is not already running) and connects to it.
Similarly, when you do not need to use the service anymore, call

```kotlin
client.close()
```

In the example application (a single-activity Compose app) open() is called in the activity's onCreate() and close() is called in the onDestroy().

### 2. Service State

The client exposes its state through

```kotlin
client.state
```

client.state is a StateFlow that is null if the service is initalizing, or type 

```kotlin
data class G1ServiceState(
  val status: Int, 
    // values can be
    //    G1ServiceState.READY - the service is initialized and ready to scan for glasses
    //    G1ServiceState.LOOKING - the service is scanning for glasses
    //    G1ServiceState.LOOKED - the service has looked for glasses and is ready to look again
    //    G1ServiceState.ERROR - the service encountered an error looking for glasses
  val glasses: Array<G1Glasses>
    // glasses that the service has found
)
```

```kotlin
data class G1Glasses(
  val id: String,
    // unique id for glasses, treat as opaque (constructed from device MAC)
  val name: String,
    // label for glasses
  val connectionState: Int,
    // values can be
    //    G1ServiceState.UNINITIALIZED - the service is just setting the glasses up to connect
    //    G1ServiceState.DISCONNECTED - the glasses are not connected
    //    G1ServiceState.CONNECTING - the service is connecting to the glasses
    //    G1ServiceState.CONNECTED - the glasses are ready to use
    //    G1ServiceState.DISCONNECTING - the service is disconnecting from the glasses
    //    G1ServiceState.ERROR - an error ocurred while setting up or connecting the glasses
  val batteryPercentage: Int,
    // the percentage battery left of the side that has the least left
)
```

### 3. Scanning for Glasses (FOR MANAGING APPS)

To start scanning for glasses, call 

```kotlin
client.lookForGlasses()
```

The function will scan for glasses for 15 seconds. The client.state flow will update as changes occur. 

### 4. Connecting and Disconnecting (FOR MANAGING APPS)

To connect to a pair of glasses, call the suspend function

```kotlin
val success = client.connect(id)
```

in a coroutine scope, using the id of the glasses you want to connect.  
The client.state will update as changes in connection state of the glasses occur.
Similarly, to disconnect, invoke

```kotlin
client.disconnect(id)
```

### 5. Listing Connected Glasses

If an app wishes to only use connected glasses, and not manage them, it can just call this to list the currently connected Glasses.

```kotlin
val glasses = client.listConnectedGlasses()
```

It returns a List<Glasses> or null if the service is not initialized or reachable

### 6. Displaying Unformatted Text

The basic facility to display text immediately is the suspend function

```kotlin
val success = client.displayTextPage(id, page)
```

where id is the glasses id, and page is a list of a maximum of five strings of a maximum 40-character width each.
This call displays the text immediately as it is formatted, and leaves it on the display.

To stop displaying text, call

```kotlin
val success = client.stopDisplaying(id)
```

this clears the text and goes back to the previous context (screen off, dashboard).
To automatically remove the text after a given amount of time, call

```kotlin
val success = client.displayTimedTextPage(id, page, milliseconds)
```

where milliseconds is how long to display the text before it disappears.

### 7. Displaying Formatted Text

The client includes convenience methods to format text automatically for the display.
You can call

```kotlin
val success = client.displayFormattedPage(id, formattedPage)
```

where

```kotlin
data class FormattedPage(
    val lines: List<FormattedLine>,
    val justify: JustifyPage
)

enum class JustifyPage { TOP, BOTTOM, CENTER }

data class FormattedLine(
    val text: String,
    val justify: JustifyLine
)

enum class JustifyLine { LEFT, RIGHT, CENTER }
```

this allows you to center text horizontally and vertically. 
This method displays text immediately and indefinitely until it is removed with client.stopDisplaying().

You can also have it automatically removed with

```kotlin
val success = client.displayTimedFormattedPage(id, timedFormattedPage)
```

where

```kotlin
data class TimedFormattedPage(
    val page: FormattedPage,
    val milliseconds: Long
)
```

### 8. Displaying Formatted Page Sequences

You can also display a sequence of formatted pages, each with its own duration:

```kotlin
val success = client.displayFormattedPageSequence(id, sequence)
```

where sequence is a list of TimedFormattedPage.

### 9. Quick Convenience Centered Text Timed Display

Finally, the convenience method:

```kotlin
val success = client.displayCentered(id, textLines, milliseconds)
```

displays the list of strings (maximum five strings of 40 characters limit each) centered on the screen for the duration of milliseconds, or permanently if null.
The default for milliseconds is 2000.

## Subtitles

The **subtitles** module contains an example application that uses the client to transcribe everything it hears and send it 
to the glasses that are connected using the Hub.  It is in progress, it does not yet work.

*(more details coming soon)*
