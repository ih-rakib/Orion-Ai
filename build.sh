#!/usr/bin/env bash
# Build Orion.apk without Gradle, using only the Android SDK
# command-line tools (aapt2 + javac + d8 + zipalign + apksigner).
# Same pattern as Orion-IDM.
set -euo pipefail

if [ -x "$HOME/jdk17/bin/javac" ]; then
    export PATH="$HOME/jdk17/bin:$PATH"
    export JAVA_HOME="$HOME/jdk17"
fi

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"
APP_DIR="$(cd "$(dirname "$0")" && pwd)/app"
OUT="$(cd "$(dirname "$0")" && pwd)/build"
KEYSTORE="$(cd "$(dirname "$0")" && pwd)/debug.keystore"

mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/res-compiled"
# Always drop stale compiled resources first: a deleted res file must not
# survive into the next APK via an old .flat (happened with the v1.0
# adaptive icon XML in the v1.1 build).
rm -rf "$OUT/res-compiled" "$OUT/gen" "$OUT/classes"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/res-compiled"

"$BT/aapt2" compile --dir "$APP_DIR/src/main/res" -o "$OUT/res-compiled"
"$BT/aapt2" link \
    -o "$OUT/app-unsigned.apk" \
    -I "$PLATFORM" \
    --manifest "$APP_DIR/src/main/AndroidManifest.xml" \
    --java "$OUT/gen" \
    "$OUT/res-compiled"/*.flat

find "$APP_DIR/src/main/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -cp "$PLATFORM" \
    -d "$OUT/classes" @"$OUT/sources.txt"

"$BT/d8" --lib "$PLATFORM" --output "$OUT" $(find "$OUT/classes" -name '*.class')

cd "$OUT"
zip -q app-unsigned.apk classes.dex
"$BT/zipalign" -f 4 app-unsigned.apk app-aligned.apk

if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out Orion.apk app-aligned.apk

echo "Built: $OUT/Orion.apk"
"$BT/apksigner" verify --print-certs "$OUT/Orion.apk" | head -3
ls -lh "$OUT/Orion.apk"
