#!/bin/bash
# Run the JVM smoke tests (no device, no relay needed).
# Dispatcher routing + screenshot guard + AppState + grant fixes +
# live-stream timing + WebSocket framing + permanent UID + background
# stay-alive + swipe-away restart / reopen auto-start.
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="$HOME/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"
PLATFORM="$HOME/android-sdk/platforms/android-34/android.jar"
rm -rf test/classes && mkdir -p test/classes
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 \
  -cp "test/json-20240303.jar:$PLATFORM" -d test/classes \
  app/src/main/java/com/sanji/control/Config.java \
  app/src/main/java/com/sanji/control/AppState.java \
  app/src/main/java/com/sanji/control/ControlAccessibilityService.java \
  app/src/main/java/com/sanji/control/ScreenCapture.java \
  app/src/main/java/com/sanji/control/CaptureGrantManager.java \
  app/src/main/java/com/sanji/control/StreamController.java \
  app/src/main/java/com/sanji/control/CommandDispatcher.java \
  app/src/main/java/com/sanji/control/WebSocketClient.java \
  app/src/main/java/com/sanji/control/DeviceId.java \
  app/src/main/java/com/sanji/control/Prefs.java \
  app/src/main/java/com/sanji/control/ControlDeviceAdmin.java \
  app/src/main/java/com/sanji/control/ControlService.java \
  app/src/main/java/com/sanji/control/BootReceiver.java \
  app/src/main/java/com/sanji/control/RestartReceiver.java \
  test/TestDispatcher.java test/TestGrantManager.java test/TestStream.java \
  test/TestWsFrames.java test/TestDeviceId.java test/TestRestart.java 2>&1 | grep -v "bootstrap class path" | grep -v "^Note:" || true
echo "=== Dispatcher (32 checks) ==="
"$JAVA_HOME/bin/java" -cp "test/classes:test/json-20240303.jar:$PLATFORM" TestDispatcher
echo "=== Capture grant manager (16 checks) ==="
"$JAVA_HOME/bin/java" -Dsanji.control.service.src=app/src/main/java/com/sanji/control/ControlService.java \
  -cp "test/classes:test/json-20240303.jar:$PLATFORM" TestGrantManager
echo "=== Live-stream timing (16 checks) ==="
"$JAVA_HOME/bin/java" -cp "test/classes:test/json-20240303.jar:$PLATFORM" TestStream
echo "=== WebSocket framing (8 checks) ==="
"$JAVA_HOME/bin/java" -cp "test/classes:test/json-20240303.jar:$PLATFORM" com.sanji.control.TestWsFrames
echo "=== Permanent UID + background stay-alive + device display name (64 checks) ==="
"$JAVA_HOME/bin/java" -Dsanji.control.app.src=. \
  -cp "test/classes:test/json-20240303.jar:$PLATFORM" TestDeviceId
echo "=== Swipe-away restart + reopen auto-start (28 checks) ==="
"$JAVA_HOME/bin/java" -Dsanji.control.app.src=. \
  -cp "test/classes:test/json-20240303.jar:$PLATFORM" TestRestart
