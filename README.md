# Sanji Control

An Android app that lets a controller use this phone remotely, with a big
**ON/OFF switch** so it uses **zero mobile data when OFF**.

- **Package:** `com.sanji.control`
- **App name:** Sanji Control
- **minSdk 26, targetSdk 34, compileSdk 34**
- **Language:** Java (pure Android framework — no Kotlin, no OkHttp, no
  AndroidX, no external dependencies)
- **Relay:** a single `wss://` URL in `Config.kt` → `Config.java`
  (`RELAY_URL`), currently the live Railway relay.

## How it works

1. **MainActivity** — one screen. A big **TURN ON / TURN OFF** button, a
   status line (`OFF` / `Connecting` / `ON` + relay URL + data used this
   session in KB/MB), and a short setup checklist (Accessibility Service
   ON? Screen capture allowed?) with buttons that open the right Settings
   screens. No login, no account.
2. **ControlService** — a foreground service (persistent notification
   "Sanji Control ON — remote control active"). Connects to the relay over
   WebSocket, sends `{"type":"hello","role":"device","device":"<brand model>"}`
   on connect, receives JSON commands and dispatches them. Reconnects with
   backoff (2s → 30s) while ON. Tracks bytes sent/received in `AppState`
   and updates the UI.
3. **ControlAccessibilityService** — performs the control actions:
   `tap(x,y)` (dispatchGesture), `swipe(x1,y1,x2,y2,durationMs)`,
   `typeText(text)` (ACTION_SET_TEXT, clipboard + ACTION_PASTE fallback),
   `pressBack` / `pressHome` / `pressRecents` (GLOBAL_ACTION_*),
   `openApp(package)` (getLaunchIntentForPackage). Every action returns
   ok true/false + info. If the accessibility service is not enabled,
   every action returns ok false `"accessibility off"`.
4. **ScreenCapture** — MediaProjection screenshots. Two modes:
   - **Live stream:** while the app is ON and a controller is connected,
     frames are sent continuously at ~4 frames/sec (one every 250 ms) as
     `{"type":"screenshot","data":"..."}`. The web panel already displays
     the latest frame, so it becomes a live feed automatically.
   - **On demand:** the `screenshot` command still sends one frame
     immediately. Frames are downscaled to max width 540 and compressed
     to JPEG quality 50, base64-encoded. Every frame carries the real
     screen size as `"sw"`/`"sh"` (e.g. 720x1456) so the panel can map
     tap coordinates from image space back to real screen pixels.
     If MediaProjection is not granted, the reply is ok false
     `"screen capture not allowed"`.
   The stream sends **nothing** when no controller is connected (the
   relay does not announce controllers, so "connected" is inferred from
   controller activity: any command proves one is present, and the
   stream pauses 30 s after the last one — send the `ping` command
   periodically as a keepalive while only watching). When OFF, nothing
   is sent at all.
5. **ControlDeviceAdmin** — Device Admin receiver, **remote lock only**.
   The controller can send the `lock` command, which calls
   `DevicePolicyManager.lockNow()`. No wipe, no password reset, no other
   policies — `res/xml/device_admin.xml` declares `<force-lock />` and
   nothing else. The user enables it from the app ("Enable Device Admin"
   button, opens the system activation screen). Without it, `lock`
   replies ok false `"device admin not enabled"`.
   Note: Device Admin does **not** make the screen-capture permission
   permanent. Android still asks "Start now" every time a new
   screen-capture session starts (privacy rule), so the screen
   permission is requested each time the app is turned ON.
5. **WebSocketClient** — a minimal RFC 6455 WebSocket client over
   `java.net.Socket` (no library). Supports `wss://` (TLS) and `ws://`,
   masked client frames, fragmented-message reassembly, ping → pong,
   and close handling.

## Command protocol (JSON over the WebSocket)

Every command has an `"id"` string. The service replies
`{"type":"result","id":...,"ok":true/false,"info":"..."}`; screenshots
are sent as `{"type":"screenshot","id":...,"data":"...","sw":...,"sh":...}`
(`sw`/`sh` = real screen width/height in pixels, for tap mapping).

| action | fields | example |
|---|---|---|
| `tap` | `x`, `y` | `{"id":"1","action":"tap","x":100,"y":200}` |
| `swipe` | `x1`,`y1`,`x2`,`y2`,`durationMs?` (default 300) | `{"id":"2","action":"swipe","x1":0,"y1":0,"x2":0,"y2":800}` |
| `type` | `text` | `{"id":"3","action":"type","text":"hello"}` |
| `back` | — | `{"id":"4","action":"back"}` |
| `home` | — | `{"id":"5","action":"home"}` |
| `recents` | — | `{"id":"6","action":"recents"}` |
| `open_app` | `package` | `{"id":"7","action":"open_app","package":"com.whatsapp"}` |
| `screenshot` | — | `{"id":"8","action":"screenshot"}` |
| `lock` | — | `{"id":"9","action":"lock"}` (locks the phone; needs Device Admin) |
| `ping` | — | `{"id":"10","action":"ping"}` (keepalive, replies ok true "pong") |

Unknown action → `{"type":"result","id":...,"ok":false,"info":"unknown action"}`.

Live-stream frames arrive as `{"type":"screenshot","data":"...","sw":...,"sh":...}` (no
`"id"` — they are pushed, not replies to a command).

## Data saving

- **OFF:** the service is stopped and the socket is closed — **zero data**.
- **ON:** the screen is streamed live (~4 frames/sec, JPEG quality 50,
  max width 540) **only while a controller is connected**. When no
  controller is connected, no frames are sent at all. No background
  polling, no analytics, no extra network calls.
- The status line shows bytes sent/received this session.

## How to build

### In this VM (no Gradle download needed)

The sandbox blocks Java's TCP connections, so Gradle cannot download the
Android Gradle Plugin / Kotlin / OkHttp through the proxy — only curl and
Python can fetch. The app is therefore dependency-free and is built
directly with the Android SDK tools. This is fully offline.

Requirements (already set up in this VM):

- Android SDK at `~/android-sdk` (platform android-34, build-tools 34.0.0)
- JDK 17 at `~/jdk-17` (Temurin; the system `/usr/lib/jvm` JDK was wiped
  by a VM replacement, so a copy lives in the home directory)

Build:

```sh
./gradlew assembleDebug
# (gradlew -> build.sh -> build-manual.sh)
```

This runs `aapt2 compile` → `aapt2 link` → `javac` → `d8` → `zipalign`
→ `apksigner` and produces:

```
app/build/outputs/apk/debug/app-debug.apk
```

Verify:

```sh
~/android-sdk/build-tools/34.0.0/aapt dump badging app/build/outputs/apk/debug/app-debug.apk
~/android-sdk/build-tools/34.0.0/apksigner verify app/build/outputs/apk/debug/app-debug.apk
```

### On a machine with working Java networking (Gradle path)

The Gradle project files (`settings.gradle`, `build.gradle`,
`app/build.gradle`, `gradlew`) are kept for this path. It needs the
Android Gradle Plugin 8.2.2 downloaded once. Run the Gradle build with:

```sh
./build-gradle.sh assembleDebug
```

Note: `./gradlew` in this VM runs the manual build (see above), because
Gradle cannot download dependencies here.

## How to install (sideload)

1. Copy `app-debug.apk` to the phone (USB, WhatsApp, Drive, etc.).
2. On the phone, tap the APK. Allow **"Install unknown apps"** for the
   app you opened it from (e.g. Chrome / Files) when prompted.
3. Open **Sanji Control**.

## First-time setup (on the phone)

1. Tap **TURN ON**.
2. If asked, allow the **notification** permission (Android 13+).
3. If the Accessibility Service is OFF, the app opens the Accessibility
   Settings — find **Sanji Control** in the list, turn it **ON**, then go
   back. The app continues automatically.
4. A system dialog asks to allow **screen capture** — tap **Start now**.
   (If you deny it, taps/typing still work; only screenshots reply
   "screen capture not allowed".) **This is asked every time the app is
   turned ON** — Android requires it for every new screen-capture
   session, and enabling Device Admin does not change that.
5. Optional: tap **Enable Device Admin** if you want the remote **lock**
   command to work. This only allows locking the phone — no wipe, no
   password changes.
6. **Stay in the background (important on Oppo / ColorOS):** tap **Allow
   background running** and allow the battery-optimization exemption —
   otherwise the phone may kill the app in the background and it has to
   be turned ON again. On Oppo/ColorOS also open **Open App Settings**
   and allow **Auto-start** and **Allow background running** for Sanji
   Control in the phone Settings → Apps. After a reboot the app restarts
   by itself only if it was ON — taps and typing keep working, but the
   screen needs "Start now" again (the screen permission is asked every
   time, Android rule).
7. The status line shows **ON** and the notification
   "Sanji Control ON — remote control active" appears. The controller can
   now send commands through the relay. While a controller is connected,
   the screen streams live (~4 frames/sec); the `screenshot` command
   still sends one frame on demand.
8. To stop: tap **TURN OFF**. The service stops, the socket closes, and
   the app uses zero mobile data.

## Surviving a swipe-away (recents) + auto-start on reopen

Swiping the app away from recents kills it on Oppo/ColorOS even with
the battery exemption and Auto-start enabled. The app now fights back
on three levels (all only when the app was ON — when the user turned
it OFF, it stays OFF and uses zero data):

1. **Swipe-away restart:** `ControlService.onTaskRemoved()` fires when
   the task is removed. It first tries a direct restart, then sends a
   broadcast to `RestartReceiver`, which restarts the service in
   dataSync mode (no screen capture — the grant died with the old
   process). Both attempts are wrapped in try/catch (Android 12+
   background-start restrictions may throw).
2. **Reopen auto-start:** `MainActivity.onCreate` checks the persisted
   `was_on` flag — if the app was ON and the service is not already
   running, it restarts the service by itself, so reopening the app
   resumes control with no ON tap. The status line then says
   "Screen capture: Not allowed — turn OFF then ON, tap Start now for
   screen", because taps work but screenshots need a fresh grant (the
   capture permission is never auto-requested on open).
3. **Reboot restart:** `BootReceiver` (as before) restarts the service
   after a reboot when the app was ON.

Honest limit: on Oppo/ColorOS a swipe can force-stop the app, and a
force-stopped app receives no broadcasts and no service restart until
the user launches it again — in that case step 2 (reopen auto-start)
is what brings it back. The screen-capture grant is single-use and
gone after any process death, so after a swipe-away or reboot the
screen always needs "Start now" again; taps/typing do not.

## Phone UID (one per phone, permanent)

Every phone gets a permanent unique UID — the Android ID
(`Settings.Secure.ANDROID_ID`, stable across reinstalls, only a factory
reset changes it). It is shown on the main screen as **"Phone UID: …"**
(tap it to copy) and in the notification, and it is sent in the hello
message: `{"type":"hello","role":"device","device":"<brand model>",
"uid":"<android_id>"}`. The device name is the real brand + model
(`DeviceId.deviceDisplayName()` from `Build.MANUFACTURER` + `Build.MODEL`,
e.g. "Oppo CPH2349"; falls back to "sanji-phone" when unreadable), so the
control panel's master-mode list shows which phone is which. Install the app on as many phones as you like;
each has its own UID, and the control panel uses the UID to pick which
phone to control. (If ANDROID_ID is unavailable on a device, a random
UUID is generated once and stored, so the UID still stays stable.)

## Files

```
app/src/main/AndroidManifest.xml          permissions + services
app/src/main/java/com/sanji/control/
  Config.java                             RELAY_URL (single constant)
  AppState.java                           ON/OFF state + byte counters
  MainActivity.java                       ON/OFF toggle + status + checklist
  ControlService.java                     foreground service + WebSocket + dispatch + live stream
  ControlAccessibilityService.java        tap / swipe / type / back / home / recents / open_app
  ControlDeviceAdmin.java                 device admin receiver (remote lock only)
  DeviceId.java                           permanent per-phone UID (ANDROID_ID) + hello JSON
  Prefs.java                              SharedPreferences: was_on + fallback UID (testable store)
  BootReceiver.java                       restart service after reboot when app was ON
  RestartReceiver.java                    restart service after swipe-away when app was ON
  CaptureGrantManager.java                MediaProjection grant state machine (pure Java)
  StreamController.java                   live-stream timing: ~4 fps, only while a controller is connected
  ScreenCapture.java                      MediaProjection screenshots (live stream + on demand)
  CommandDispatcher.java                  JSON command routing
  WebSocketClient.java                    minimal RFC 6455 WebSocket client
app/src/main/res/                         layout, strings, theme, accessibility config
build-manual.sh                           offline build (aapt2/javac/d8/zipalign/apksigner)
build.sh                                  ./gradlew entry point -> build-manual.sh
build-gradle.sh                           Gradle in-process build (networked machines)
test/                                     JVM smoke tests (see below)
```

## Smoke tests (JVM, no device, no relay needed)

Run all of them with:

```sh
./test/run-tests.sh
```

- Dispatcher tests (29 checks): routes every action name (`tap`, `swipe`,
  `type`, `back`, `home`, `recents`, `open_app`, `screenshot`, `lock`,
  `ping`) to the right handler; unknown action → ok false
  `"unknown action"`; bad JSON → ok false; `lock` without device admin →
  ok false `"device admin not enabled"`; `ping` → ok true `"pong"`.
- Capture-grant tests (16 checks): the grant state machine
  (CaptureGrantManager) plus a source-order check on ControlService —
  `startForeground()` is called before any `ScreenCapture` is created
  (API 34 rule), and a second start with a valid grant while already
  running installs the capture instead of ignoring it.
- Live-stream timing tests (16 checks): no frames without a controller;
  frames paced to ~4/sec (250 ms); stream stops 30 s after the last
  controller message.
- WebSocket framing tests (8 checks, piped streams): valid Upgrade
  handshake, text frames, ping → pong, fragmented-message reassembly,
  close handling.
- Permanent UID + background stay-alive tests (40 checks): the hello
  message carries a non-empty `uid` (and keeps type/role/device);
  ANDROID_ID is preferred and the random fallback is used only when it
  is missing; `was_on` round-trips through Prefs; the boot receiver
  restarts the service only when the app was ON; plus source checks
  that MainActivity persists wasOn on ON/OFF, shows the UID, and has
  the battery-exemption + app-settings buttons, and that the manifest
  declares the boot receiver and the two new permissions.
- Swipe-away restart + reopen auto-start tests (28 checks): the
  restart receiver restarts only when the app was ON; the auto-start
  decision is true only when was ON and the service is not already
  running; plus source checks that ControlService overrides
  onTaskRemoved (wasOn check, direct restart, restart broadcast),
  MainActivity auto-starts in onCreate and shows the "tap Start now
  for screen" hint, and that the manifest declares RestartReceiver.

What the tests verify:

- The dispatcher routes every action name (`tap`, `swipe`, `type`,
  `back`, `home`, `recents`, `open_app`, `screenshot`, `lock`, `ping`) to
  the right handler; unknown action → ok false `"unknown action"`; bad
  JSON → ok false.
- Screenshot returns ok false `"screen capture not allowed"` when
  MediaProjection is not granted, and returns the base64 data when it is.
- AppState OFF → CONNECTING → ON → OFF transitions and byte formatting.
- The grant fixes: a second start with a valid grant while already
  running installs the capture (no silent drop), and startForeground()
  runs before any ScreenCapture creation (API 34 order).
- Live-stream timing: no frames without a controller, ~4 fps pacing,
  stream stops 30 s after the last controller message.
- The WebSocket client sends a valid Upgrade handshake, handles text
  frames, answers ping with pong, reassembles fragmented messages, and
  handles close — all over in-memory piped streams.

A live-relay test (`test/TestWebSocket.java`) and a local-server test
(`test/TestWsServer.py` + `test/TestWsLocal.java`) are also present, but
they cannot run in this VM because the sandbox blocks Java's TCP
connections (even to localhost). On a real phone the WebSocket client
connects directly and works.

## Changing the relay URL

Edit one line in `app/src/main/java/com/sanji/control/Config.java`:

```java
public static final String RELAY_URL = "wss://sanji-control-relay-production.up.railway.app";
```

It must be `wss://` (TLS). Rebuild after changing it.

## Notes / known limits

- The APK is signed with a **debug keystore** generated at build time
  (`app/build/debug.keystore`). For distribution, sign with a release key.
- No launcher icon is bundled (the manifest has no `android:icon`);
  the system default icon is used. Add a `res/mipmap-*` icon and the
  `android:icon` attribute to brand it.
- The foreground service uses type `mediaProjection` when screen capture
  was granted, otherwise `dataSync`, so it can run for taps/typing even
  without the capture grant (API 34 rule). The grant is judged from the
  start intent's shape first, `startForeground()` runs before any
  `getMediaProjection()` call, and every start intent is processed —
  a fresh grant arriving while the service is already running is
  installed instead of dropped (this fixed the live "screen capture not
  allowed" bug).
- Device Admin is **remote lock only** (`lockNow()`). It does not wipe,
  reset passwords, or change anything else, and it does **not** make the
  screen-capture permission permanent — Android asks "Start now" every
  time the app is turned ON.
- The Gradle daemon's socket protocol fails in this VM and the egress
  proxy blocks Java TCP, so `./gradlew` runs the manual build here.
  See `build-gradle.sh` for the Gradle path on a networked machine.
