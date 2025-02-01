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
```

To starts the service (if it is not already running) and connect to it, call

```
val success = service.open()
```

Similarly, when you do not need to use the service anymore, call

```
service.close()
```

In the example application (a single-activity Compose app) open() is called in the activity's onCreate() and close() is called in the onDestroy().



*(more details coming soon)*

## Example
The **example** module contains a Compose application that demonstrates use of the service. 
The application can seamlessly and reliably discover, connect and disconnect, and send commands to glasses.

*(more details coming soon)*
