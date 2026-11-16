# Testing

## Automated tests

`./gradlew test` runs **95 tests**:

- **domain (59):**
  - Command building: key batching, text escaping including `%s`, quotes and shell
    metacharacters, package validation.
  - Probe parsing.
  - Connection state machine: auth hint, refused, hang → timeout → close.
  - Session: coalescing, idle release, background release, reconnect-and-resend, backoff,
    no-retry on auth rejection, NO_WIFI, device switching.
  - Discovery merging and codecs.
  - Injector: pushed and used, not re-pushed when the hash matches, failed start → fallback,
    hung injector → dropped and the key resent, stopped on disconnect, safe start command.
- **data (11):**
  - ADB public-key encoding is **byte-identical to dadb's AOSP-format output**.
  - Key store persistence, encryption and regeneration.
  - Refused vs unreachable mapping using a real closed socket.
  - Subnet candidates.
  - Legacy (pre-Android 7) shell: exit-code marker parsing, pty echo filtering.
  - App label/icon disk cache.
- **wear (24):**
  - Screen: kept on only on the remote and touchpad; dims after the watch timeout; the waking touch presses nothing.
  - Rotary step accumulation, gesture classification, tile actions.
  - Robolectric Compose tests of the real screens against the fake Fire TV: OK sends
    SELECT; labelled keys; swipe to the media page; refused error → guidance → retry;
    touchpad swipe and tap; the whole UI in French; the connection-state node doesn't
    cover the buttons (TalkBack regression); back goes to the previous page and never closes
    the remote unless allowed; back leaves the touchpad and Settings; the media page
    without volume keys.

## Emulator end-to-end run (2026-11-15)

Setup: Wear OS 7 emulator (API 37) as the watch, Android TV 11 emulator (API 30, the
Android version Fire OS 8 is based on, `ro.adb.secure=1`, TCP ADB mode) as the TV.

| Step | Result |
|---|---|
| `ACCESS_LOCAL_NETWORK` runtime prompt (shown as "Nearby devices") | ✅ granted via the real system dialog |
| App-level Wi-Fi `requestNetwork` + subnet scan | ✅ TV found; the watch's own address is now excluded |
| ADB RSA handshake → "Allow USB debugging?" on TV | ✅ fingerprint on TV **matches** the watch's |
| Prompt ignored/denied → watch shows "No answer from the TV" + Retry after 45 s | ✅ |
| "Always allow" + Allow → watch shows "Connected!" | ✅ |
| D-pad from the watch moves focus on the TV | ✅ |
| Latency per command, before the injector | ❌ 507 ms: Android 11 has no `cmd input`, so each `input keyevent` starts a JVM |
| **Latency per command, with the injector** | ✅ **10–14 ms** (Settings shows "fast input") |
| Injector killed mid-session → next key falls back to `input` and still works | ✅ |
| Injector stored in a 0700 directory, file 0644, hash-checked (not re-pushed when unchanged) | ✅ |
| "Always allow" survives a reconnect | ⚠️ not on this emulator image: it saves the key but never writes it to `adb_keys`. A plain host-side dadb client shows the same thing, so it's the emulator, not the app. Check on a real Fire TV |
| Manual IP entry via the system keyboard | ⚠️ couldn't be automated (the emulator IME ignores injected text); to verify by hand |

## Hardware test checklist (real watch + Fire TV)

These can't be checked on emulators. If you run them, please report the results (including
the latency numbers) in an issue.

- [ ] First connection: the TV prompt appears, the fingerprint matches, "Always allow" → Connected.
- [ ] Latency: watch *Settings* should show "fast input" and *Last command: N ms*. Target < 150 ms.
- [ ] Reconnect after the idle timeout does **not** show the TV prompt again (after "Always allow").
- [ ] Every key on pages 1 and 2 does the right thing. Volume/mute with your TV/soundbar over CEC.
- [ ] Bezel: exactly one focus step per detent; clockwise = down / volume up.
- [ ] Touchpad: swipes in 4 directions, tap = OK, hold = Back; doesn't close the screen by accident.
- [ ] Text: Fire TV search field ← "stranger things"; voice input; "Send + Enter".
- [ ] Apps page lists your apps; launching Netflix / Prime Video / YouTube works.
- [ ] Discovery finds the TV by mDNS (by name) and/or scan (as "ADB device").
- [ ] With the phone connected over Bluetooth: the app still connects (Wi-Fi comes up on its own).
- [ ] Turn TV Wi-Fi off and on, or reboot the TV → the app reconnects on the next press.
- [ ] Leave the app 60 s → ring turns grey (disconnected); next press reconnects.
- [ ] Tile: Play/Pause works without opening the app; complication opens the remote.
- [ ] Sleep / Wake from Settings.
- [ ] TalkBack reads every button.
- [ ] Battery: 30 min of mixed use; note the % drop.
