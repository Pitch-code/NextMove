#!/usr/bin/env bash

set +e

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PACKAGE="com.pitchcode.nextmove"
COMPONENT="$PACKAGE/.MainActivity"
UI_XML="startup-ui.xml"

capture_ui() {
    adb shell uiautomator dump /sdcard/nextmove-ui.xml >/dev/null 2>&1
    adb pull /sdcard/nextmove-ui.xml "$UI_XML" >/dev/null 2>&1
}

has_id() {
    capture_ui
    grep -q "resource-id=\"$1\"" "$UI_XML"
}

tap_id() {
    capture_ui
    coords="$(python3 - "$UI_XML" "$1" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
target = sys.argv[2]
for node in root.iter("node"):
    if node.attrib.get("resource-id") == target:
        values = [int(value) for value in re.findall(r"\d+", node.attrib.get("bounds", ""))]
        if len(values) == 4:
            print(f"{(values[0] + values[2]) // 2} {(values[1] + values[3]) // 2}")
            break
PY
)"
    if [ -z "$coords" ]; then
        echo "Could not find UI target $1"
        return 1
    fi
    adb shell input tap $coords
    sleep 1
}

fail_with_logs() {
    echo "$1"
    adb logcat -d -v threadtime > startup-logcat.txt
    grep -E "AndroidRuntime|FATAL EXCEPTION|Process: $PACKAGE|am_crash|Force finishing" \
        startup-logcat.txt || true
    exit 1
}

adb install -r "$APK"
install_rc=$?
adb logcat -c
adb shell am force-stop "$PACKAGE"
launch_output="$(adb shell am start -W -n "$COMPONENT" 2>&1)"
launch_rc=$?
printf '%s\n' "$launch_output" > startup-launch.txt
sleep 5
pid="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"

if [ "$install_rc" -ne 0 ] || [ "$launch_rc" -ne 0 ] || \
   ! grep -q "Status: ok" startup-launch.txt || [ -z "$pid" ]; then
    fail_with_logs "Startup smoke failed; process is not alive."
fi

# Dismiss the prototype's first-run notification explanation when present.
if has_id "android:id/button2"; then
    tap_id "android:id/button2" || fail_with_logs "Could not dismiss first-run explanation."
fi

# Verify system Back returns from Settings to Home without killing the app.
tap_id "$PACKAGE:id/nav_settings" || fail_with_logs "Could not open Settings."
has_id "$PACKAGE:id/screen_settings" || fail_with_logs "Settings screen did not open."
adb shell input keyevent KEYCODE_BACK
sleep 1
has_id "$PACKAGE:id/screen_home" || fail_with_logs "Back did not return to Home."

# Verify a selected sample is clearly represented and Back returns Home.
adb shell input swipe 720 1900 720 750 500
sleep 1
tap_id "$PACKAGE:id/sample_bill" || fail_with_logs "Could not select bill sample."
sleep 2
has_id "$PACKAGE:id/screen_result" || fail_with_logs "Sample result did not open."
capture_ui
grep -q "SAMPLE" "$UI_XML" || fail_with_logs "Sample context is not visible."
adb shell input keyevent KEYCODE_BACK
sleep 1
has_id "$PACKAGE:id/screen_home" || fail_with_logs "Back from result did not return Home."

pid="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
[ -n "$pid" ] || fail_with_logs "App process stopped during interaction checks."
adb logcat -d -v threadtime > startup-logcat.txt

echo "Startup, sample context, and Back navigation passed with process $pid."
