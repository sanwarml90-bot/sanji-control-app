#!/bin/bash
# Manual build for Sanji Control — no Gradle, no dependency downloads.
#
# Why: in this VM the sandbox blocks Java's TCP connections ("Other TCP
# connections is turned off"), so Gradle (a Java process) cannot download
# the Android Gradle Plugin, Kotlin, or OkHttp through the proxy — only
# curl/Python can fetch. The app is therefore pure Java + Android
# framework (no Kotlin, no OkHttp, no AndroidX), and it is built directly
# with the Android SDK tools: aapt2 (resources), javac (compile),
# d8 (dex), zipalign, apksigner (sign). Everything runs offline.
#
# Produces: app/build/outputs/apk/debug/app-debug.apk
# Run: ./build-manual.sh  (or ./gradlew assembleDebug, which calls this)
set -e
cd "$(dirname "$0")"

export JAVA_HOME="$HOME/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/android-sdk"
BT="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"
JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"

BUILD_DIR="app/build"
INTER="$BUILD_DIR/intermediates"
OUT_APK="$BUILD_DIR/outputs/apk/debug/app-debug.apk"
KEYSTORE="$BUILD_DIR/debug.keystore"

rm -rf "$INTER" "$BUILD_DIR/outputs"
mkdir -p "$INTER/compiled_res" "$INTER/classes" "$INTER/dex" "$INTER/gen" "$(dirname "$OUT_APK")"

echo "== aapt2 compile (resources) =="
"$BT/aapt2" compile --dir app/src/main/res -o "$INTER/compiled_res.zip"

echo "== aapt2 link (manifest + resources -> APK, generate R.java) =="
"$BT/aapt2" link -o "$INTER/base.apk" \
  -I "$PLATFORM" \
  --manifest app/src/main/AndroidManifest.xml \
  -R "$INTER/compiled_res.zip" \
  --auto-add-overlay \
  --java "$INTER/gen"

echo "== javac (sources + R.java against android.jar) =="
find app/src/main/java -name "*.java" > "$INTER/sources.txt"
find "$INTER/gen" -name "*.java" >> "$INTER/sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 \
  -cp "$PLATFORM" \
  -d "$INTER/classes" \
  @"$INTER/sources.txt"

echo "== d8 (classes -> dex) =="
"$BT/d8" --output "$INTER/dex" \
  --lib "$PLATFORM" \
  --min-api 26 \
  $(find "$INTER/classes" -name "*.class")

echo "== add classes.dex to APK =="
cp "$INTER/base.apk" "$INTER/app-debug-unaligned.apk"
cd "$INTER/dex" && zip -q -r "../app-debug-unaligned.apk" classes*.dex && cd - >/dev/null

echo "== zipalign =="
"$BT/zipalign" -f 4 "$INTER/app-debug-unaligned.apk" "$INTER/app-debug-aligned.apk"

echo "== apksigner (debug keystore) =="
if [ ! -f "$KEYSTORE" ]; then
  "$KEYTOOL" -genkeypair -v -keystore "$KEYSTORE" -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android \
  --key-pass pass:android --out "$OUT_APK" "$INTER/app-debug-aligned.apk"

echo "== verify =="
"$BT/apksigner" verify --verbose "$OUT_APK" | tail -3
echo "APK: $OUT_APK"
ls -la "$OUT_APK"
