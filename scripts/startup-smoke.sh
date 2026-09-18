#!/usr/bin/env bash

set +e

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PACKAGE="com.pitchcode.nextmove"
COMPONENT="$PACKAGE/.MainActivity"

adb install -r "$APK"
install_rc=$?
adb logcat -c
adb shell am force-stop "$PACKAGE"
launch_output="$(adb shell am start -W -n "$COMPONENT" 2>&1)"
launch_rc=$?
printf '%s\n' "$launch_output" > startup-launch.txt
sleep 5
pid="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
adb logcat -d -v threadtime > startup-logcat.txt

if [ "$install_rc" -ne 0 ] || [ "$launch_rc" -ne 0 ] || \
   ! grep -q "Status: ok" startup-launch.txt || [ -z "$pid" ]; then
    echo "Startup smoke failed; process is not alive."
    grep -E "AndroidRuntime|FATAL EXCEPTION|Process: $PACKAGE|am_crash|Force finishing" \
        startup-logcat.txt || true
    exit 1
fi

echo "Startup smoke passed with process $pid."
