# G1 Basis
Open, multipurpose infrastructure for writing Android applications that talk to the [Even Realities G1](https://www.evenrealities.com/g1) glasses.

## Core
The **core** module contains the source code to the core library. 
This library allows for interfacing directly with the glasses through a simple abstraction that uses modern Android and Kotlin features like coroutines and Flow. 
When using this library directly, only one application can connect to and interface with the glasses at one time. The basis core lirbary is used under the hood by the **service**.

*(more details coming soon)*

## Service
The **service** module implements a shared Android service that multiple applications can use to interface simultaneously with the glasses.  
Applications interface with the service using the AIDL-defined generated code and a simple wrapper that exposes the service using coroutines and flow the way the Core library does.
The service also handles requesting the necessary permissions at runtime, so calling applications do not have to.

### Service Wrapper

#### 1. Initialization

Initialize the client by calling

```
val client = G1ServiceClient(applicationContext)
val success = client.open()
```

This starts the service (if it is not already running) and connects to it.
Similarly, when you do not need to use the service anymore, call

```
client.close()
```

In the example application (a single-activity Compose app) open() is called in the activity's onCreate() and close() is called in the onDestroy().

The client exposes its state through

```
client.state
```

client.state is a StateFlow that is null if the service is initalizing, or type 

```
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

```
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
)
```

*(more details coming soon)*

## Example
The **example** module contains a Compose application that demonstrates use of the service. 
The application can seamlessly and reliably discover, connect and disconnect, and send commands to glasses.

*(more details coming soon)*
