# Architecture

## Modules

```
:wear     Compose for Wear OS UI, ViewModels, Tile, complication, AppContainer (manual DI)
:data     dadb transport, Keystore-wrapped ADB key, Wi-Fi network gate, NSD + subnet scan, DataStore
:domain   Pure Kotlin: models, ShellCommandBuilder, ShellFireTvConnection, RemoteSession
:testing  Fakes: FakeFireTvConnection, FakeShellTransport, FakeNetworkGate, in-memory repos
```

## How a key press travels

```
UI → RemoteViewModel → RemoteSession (queue, coalesce bursts, connect-on-demand, idle release, backoff)
   → ShellFireTvConnection (probe, timeout, state machine) → DadbShellTransport → TCP 5555 → Fire TV
```

- **Connection.** One authenticated ADB connection is kept open while in use.
- **Fast keys (on-TV injector).** After connecting, the watch pushes a ~4 KB helper
  (`wear/src/injector/`, compiled to `injector.dex` by Gradle) to the TV and starts it with
  `app_process` as the ADB shell user. Each key press is then one line on its stdin
  (`K 19 19`), answered with `OK`: about **10–15 ms** instead of ~500 ms per `input keyevent`
  on Android ≤ 11. It also answers `I <package>` with the app's label and icon (PNG, base64):
  it reads the app's own resources from its APK, without needing a Context. Old versions of
  the helper are deleted when a new one is pushed.
  - **Where it lives:** a private directory, `/data/local/tmp/firetvonwatch` (mode 0700),
    together with its ART cache (`ANDROID_DATA`).
  - **Integrity:** the file is checked against its SHA-256 before every start and pushed
    again if it differs. It's made read-only (0644).
  - **Lifetime:** it exits when the connection closes.
- **Fallback.** Until the injector is ready, or if it fails or stops answering (1.5 s), keys go
  through a short-lived shell running `cmd input keyevent …` (`input` on old Fire OS). Text
  and app launches always use this path. A burst of key presses goes out in **one** call.
- **Wi-Fi.** `ConnectivityManager.requestNetwork(TRANSPORT_WIFI)` plus `bindProcessToNetwork`,
  so traffic doesn't go through the phone's Bluetooth proxy. The request is released together
  with the connection.
- **Battery.** The connection and Wi-Fi request are dropped as soon as the app leaves the
  foreground, or after the idle timeout (30 s to 5 min, default 60 s). They reconnect on the
  next key press.
- **Key security.** RSA-2048 ADB key. The private key is stored AES-256-GCM encrypted with a
  non-exportable Android Keystore key, in `noBackupFilesDir`. Backups are turned off. *Settings
  → Reset watch key* creates a new identity.

## Libraries

| Library | Version | License | Why |
|---|---|---|---|
| dev.mobile:dadb | 2.0.0 | Apache-2.0 | Pure-JVM ADB client (no adb binary or server) |
| androidx.wear.compose (material3, foundation, navigation) | 1.7.1 | Apache-2.0 | Round-screen UI |
| androidx.navigation:navigation-compose | 2.10.2 | Apache-2.0 | Back-stack state for the Wear nav host |
| androidx.wear.tiles / protolayout | 1.6.2 / 1.4.2 | Apache-2.0 | Tile |
| androidx.wear.watchface complications-data-source-ktx | 1.3.0 | Apache-2.0 | Complication |
| androidx.wear:wear-input | 1.2.0 | Apache-2.0 | System keyboard/voice input |
| androidx.datastore:datastore-preferences | 1.3.0 | Apache-2.0 | Paired devices and settings |
| kotlinx-coroutines | 1.11.0 | Apache-2.0 | Concurrency |
| Test: JUnit 4, Robolectric 4.18, Compose UI test, Turbine, coroutines-test | – | EPL/MIT/Apache | Tests |

Build: AGP 9.5.1, Kotlin 2.4.21, Gradle 9.9.1, compile/target SDK 37, min SDK 34.
