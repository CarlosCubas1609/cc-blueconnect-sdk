# cc-blueconnect-sdk

[![](https://jitpack.io/v/CarlosCubas1609/cc-blueconnect-sdk.svg)](https://jitpack.io/#CarlosCubas1609/cc-blueconnect-sdk)

Lightweight Android Bluetooth toolkit for **scanning, connecting, and reading frames** from
BLE GATT, Bluetooth Classic (SPP/RFCOMM), and Chipsea-style advertisement-broadcasting devices
— behind a single coroutine-friendly API.

The SDK is **transport-generic**: it forwards raw frames as `BluetoothFrame(data, bytes)` and
lets you decode them yourself. Domain-specific parsers (e.g. weight scales) live in optional
add-on modules so the core stays small.

---

## Modules

All artifacts share the JitPack groupId `com.github.CarlosCubas1609.cc-blueconnect-sdk`.

| Module                | Artifact id          | Purpose                                                            |
|-----------------------|----------------------|--------------------------------------------------------------------|
| `:core`               | `core`               | Public API surface, models, `ConnectionStrategy`, storage interface |
| `:bluetooth`          | `bluetooth`          | Real implementation: GATT, Classic, Chipsea, Demo, scanning, permissions |
| `:storage-datastore`  | `storage-datastore`  | Optional Preferences DataStore adapter for auto-reconnect          |
| `:parser-weight`      | `parser-weight`      | Optional decoder for LP7516, BLE Weight Scale, Chipsea v1.1/v2.0   |
| `:ui`                 | `ui`                 | Optional Compose helpers: permission flow + generic frame viewer    |
| `:app`                | (not published)      | Sample app showing how to wire everything together                 |

A consumer that only needs scan + connect can depend on `:core` + `:bluetooth`. The other
three are opt-in.

---

## Installation

The SDK is published via [JitPack](https://jitpack.io). Add the repository, then pick the
modules you need.

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

`<module>/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.CarlosCubas1609.cc-blueconnect-sdk:bluetooth:1.0.0")

    // Optional add-ons (each one is opt-in):
    implementation("com.github.CarlosCubas1609.cc-blueconnect-sdk:storage-datastore:1.0.0")
    implementation("com.github.CarlosCubas1609.cc-blueconnect-sdk:parser-weight:1.0.0")
    implementation("com.github.CarlosCubas1609.cc-blueconnect-sdk:ui:1.0.0")
}
```

`:core` is pulled in transitively by every other module — you don't have to declare it
explicitly.

Minimum supported Android: **API 24** (Android 7.0).

---

## Quick start

### 1. Create a client

```kotlin
import com.ccubas.blueconnect.BlueConnect
import com.ccubas.blueconnect.storage.datastore.DataStoreSessionStorage

val client = BlueConnect.create(
    context = applicationContext,
    storage = DataStoreSessionStorage(applicationContext), // optional, persists last device
)
```

If you don't need persistence, omit `storage` (defaults to `InMemorySessionStorage`).

### 2. Request runtime permissions

The Compose helper handles the right permissions for each API level:

```kotlin
RequestBluetoothPermissions(
    onPermissionsGranted = { /* you can scan now */ },
    onPermissionsDenied  = { /* show rationale */ },
)
```

If you're not on Compose, request them yourself — the SDK exposes
`BluetoothPermissionUtils.getRequiredPermissions()`.

#### Asking the user to turn Bluetooth on

`FrameViewerScreen` already does this for you: when `client.scanError` emits
`ScanError.BluetoothDisabled`, it pops the system "Turn on Bluetooth?" prompt and retries the
scan if the user accepts.

If you're rolling your own UI, the same helper is available standalone:

```kotlin
val requestEnableBluetooth = rememberBluetoothEnableLauncher(
    onEnabled = { scope.launch { client.startScan() } },
    onDeclined = { /* show rationale */ },
)

LaunchedEffect(client) {
    client.scanError.collect { error ->
        if (error is ScanError.BluetoothDisabled) requestEnableBluetooth()
    }
}
```

### 3. Scan and connect

```kotlin
viewModelScope.launch {
    client.startScan(durationMs = 15_000L)
}

// Observe results
viewModelScope.launch {
    client.discoveredDevices.collect { devices ->
        // devices: Map<String /*MAC*/, DeviceInfo>
    }
}

// When the user picks one
client.connect(device)              // auto-detects strategy
client.connect(device, ConnectionStrategy.BleOnly) // or force one
```

### 4. Read frames

```kotlin
viewModelScope.launch {
    client.lastFrame.collect { frame ->
        // frame: BluetoothFrame? (data: String, bytes: ByteArray?)
        if (frame != null) handle(frame)
    }
}
```

Decode the frame however you like. If your device is a weight scale, the
`:parser-weight` module ships ready-made decoders:

```kotlin
import com.ccubas.blueconnect.parser.weight.WeightFrameParser

val reading = WeightFrameParser.parse(frame) // WeightReading? — null if no protocol matched
reading?.let {
    println("${it.weight} ${it.unit} (stable=${it.isStable}, protocol=${it.protocol})")
}
```

### 5. Disconnect

```kotlin
client.disconnect()
```

---

## Connection strategies

When a device speaks one protocol, just connect and you're done. When it might speak several
(or you don't know yet), pass a `ConnectionStrategy` to control fallback order:

| Strategy        | Order of attempts                       | When to use                                       |
|-----------------|-----------------------------------------|---------------------------------------------------|
| `BleOnly`       | BLE                                     | Known BLE-only device                             |
| `ClassicOnly`   | Classic (SPP)                           | Known Classic-only device (e.g. LP7516)           |
| `ChipseaOnly`   | Chipsea advertisement                   | Known Chipsea / OKOK / similar broadcast scale    |
| `BleFirst`      | BLE → Classic → Chipsea                 | Unknown device, BLE-capable hardware              |
| `ClassicFirst`  | Classic → BLE → Chipsea                 | Unknown device, likely Classic                    |
| `ChipseaFirst`  | Chipsea → BLE → Classic                 | Unknown device, looks broadcast-only              |
| `DemoOnly`      | Synthetic                               | Tests / offline development                        |

If you don't pass one, the coordinator picks a sensible default from `device.type` and the
device name.

---

## Demo mode

Useful when you don't have hardware or want UI tests without a real adapter:

```kotlin
client.setDemoMode(true)
```

While demo mode is on, scans return six fake devices and connections produce simulated
LP7516-style frames once a second.

---

## Auto-reconnect

After a successful connection the client persists `(deviceAddress, deviceName, protocol,
timestamp)` through whatever `BluetoothSessionStorage` you injected. Restore the last device
on app start:

```kotlin
val reconnected = client.reconnectToLastDevice()
```

Implementations shipped:

- **`InMemorySessionStorage`** (default) — process-lifetime only, no persistence.
- **`NoOpSessionStorage`** — silently drops every save (use it to opt out).
- **`DataStoreSessionStorage(context)`** — Preferences DataStore, file
  `cc_blueconnect_session.preferences_pb`. From `:storage-datastore`.

If your app already has its own storage layer, implement `BluetoothSessionStorage` directly.

---

## Generic frame viewer (Compose)

The `:ui` module ships a `FrameViewerScreen(client)` that renders scan controls, the connection
state, the device list, and the latest frame (text + hex). Useful as a debug screen for any
device, regardless of payload format.

```kotlin
FrameViewerScreen(client = client)
```

You can layer your own panels on top via `extraSections`:

```kotlin
FrameViewerScreen(client = client) { slots ->
    // slots: { frame: BluetoothFrame?, connectionState: ConnectionState }
    item {
        MyOwnDecoderCard(frame = slots.frame)
    }
}
```

The sample app uses that hook to add a "Parsed weight" card driven by
`WeightFrameParser`.

### Asking the user which protocol to use

By default `FrameViewerScreen` connects with auto-selected strategy. Override `onConnectClick`
to take control — for example, to show a Material 3 picker:

```kotlin
import com.ccubas.blueconnect.ui.dialog.ProtocolPickerDialog

var deviceForPicker by remember { mutableStateOf<BluetoothDevice?>(null) }

FrameViewerScreen(
    client = client,
    onConnectClick = { device -> deviceForPicker = device },
)

deviceForPicker?.let { device ->
    ProtocolPickerDialog(
        deviceLabel = "${device.name ?: "?"} (${device.address})",
        onDismiss = { deviceForPicker = null },
        onSelect = { strategy ->
            client.connect(device, forcedStrategy = strategy)
            deviceForPicker = null
        },
    )
}
```

By default the dialog shows the three single-protocol options
(`BleOnly`, `ClassicOnly`, `ChipseaOnly`) — the most common picker UX. To include the
multi-protocol "Auto" strategies, pass them explicitly:

```kotlin
import com.ccubas.blueconnect.ui.dialog.AutoPickerStrategies
import com.ccubas.blueconnect.ui.dialog.DefaultPickerStrategies

ProtocolPickerDialog(
    deviceLabel = …,
    onDismiss = …,
    onSelect = …,
    strategies = DefaultPickerStrategies + AutoPickerStrategies,
)
```

Or build any subset you want with `listOf(ConnectionStrategy.BleOnly, …)`.

---

## Permissions

The `:bluetooth` module already declares the permissions it needs in its manifest, so they're
merged into your app automatically:

```xml
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH"          android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN"    android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
```

You still need to request them at runtime on Android 6+ — see the quick-start section above.

---

## Public API at a glance

```kotlin
interface BlueConnectClient {
    val isScanning:         StateFlow<Boolean>
    val scanError:          SharedFlow<ScanError>
    val discoveredDevices:  StateFlow<Map<String, DeviceInfo>>
    val connectionState:    StateFlow<ConnectionState>
    val lastFrame:          StateFlow<BluetoothFrame?>

    suspend fun startScan(durationMs: Long = 15_000L)
    fun stopScan()
    fun clearDevices()

    fun connect(device: BluetoothDevice, forcedStrategy: ConnectionStrategy? = null): Boolean
    fun disconnect()
    fun resetDeviceConnectionHistory(deviceAddress: String)
    fun setDemoMode(enabled: Boolean)

    suspend fun saveSuccessfulConnection(deviceAddress: String, deviceName: String?, protocol: String)
    suspend fun getLastSession(): SavedDevice?
    suspend fun clearSession()
    suspend fun reconnectToLastDevice(): Boolean
}

data class BluetoothFrame(val data: String, val bytes: ByteArray? = null)
data class DeviceInfo(val device: BluetoothDevice, val rssi: Int)
data class SavedDevice(val deviceAddress: String, val deviceName: String?, val successfulProtocol: String, val lastConnectionTimestamp: Long)

sealed class ConnectionState        // Idle / Scanning / Connecting / Connected / ConnectionFailed / Disconnected
sealed class ScanError              // BluetoothDisabled / AdapterNotAvailable / ScanFailed / PermissionDenied
sealed class ConnectionStrategy     // BleOnly / ClassicOnly / ChipseaOnly / BleFirst / ChipseaFirst / DemoOnly

interface BluetoothSessionStorage { /* save / clear / observe */ }

object BlueConnect {
    fun create(context: Context, storage: BluetoothSessionStorage = InMemorySessionStorage()): BlueConnectClient
}
```

---

## Hilt

The SDK does **not** depend on Hilt. If your app uses it, expose the client through your own
module:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object BlueConnectModule {
    @Provides
    @Singleton
    fun provideClient(@ApplicationContext ctx: Context): BlueConnectClient =
        BlueConnect.create(ctx, DataStoreSessionStorage(ctx))
}
```

---

## Sample app

The `:app` module is a working demo: permission flow → generic frame viewer → "Parsed weight"
card driven by the optional weight parser. Build and install it on a real device:

```bash
./gradlew :app:installDebug
```


---

## Project layout

```
.
├── core/                 # public API + models + parser interface (no transport code)
├── bluetooth/            # GATT / Classic / Chipsea / Demo managers + scanning
├── storage-datastore/    # Preferences DataStore session adapter (opt-in)
├── parser-weight/        # WeightFrameParser + WeightReading (opt-in)
├── ui/                   # RequestBluetoothPermissions + FrameViewerScreen (opt-in)
└── app/                  # sample app; not published
```

---

## Publishing a new release

The repo is configured for JitPack-via-tag releases:

1. Bump `SDK_VERSION` in `gradle.properties` if you want a default for local builds (JitPack
   itself overrides it from the Git tag).
2. Commit, then create a Git tag matching the version: `git tag 1.0.1 && git push --tags`.
3. JitPack picks up the tag automatically. Open https://jitpack.io/#CarlosCubas1609/cc-blueconnect-sdk
   and click **Get it** on the new tag if you want to force the build right away.
4. The build runs `./gradlew :core:publishToMavenLocal …` for every library module
   (see `jitpack.yml`); the resulting AARs become available a couple of minutes later.

Local sanity check before tagging:

```bash
./gradlew publishToMavenLocal
ls ~/.m2/repository/com/github/CarlosCubas1609/cc-blueconnect-sdk/
```

You should see `core/`, `bluetooth/`, `storage-datastore/`, `parser-weight/`, `ui/` each
containing the version directory you published.

> The bundled `gradlew.bat` ships with a known Java 20+ incompatibility (passes an empty
> `-classpath`). On Windows + JDK 20+, run via `bash gradlew …` or upgrade the wrapper.

## Roadmap

- Optional `:hilt` module to skip the boilerplate above.
- `BluetoothFrame` write API for two-way protocols (GATT writes, SPP writes).
- More built-in decoders as they're requested (BLE Heart Rate, Battery Service, etc.).
- Maven Central publishing (real `com.ccubas` groupId) once Sonatype + GPG flow is set up.
