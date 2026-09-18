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

wait_for_id() {
    for _attempt in 1 2 3 4 5; do
        if has_id "$1"; then
            return 0
        fi
        sleep 2
    done
    return 1
}

tap_id() {
    coords=""
    for _attempt in 1 2 3 4 5; do
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
        if [ -n "$coords" ]; then
            adb shell input tap $coords
            sleep 1
            return 0
        fi
        sleep 2
    done
    echo "Could not find UI target $1"
    return 1
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
wait_for_id "$PACKAGE:id/screen_settings" || fail_with_logs "Settings screen did not open."
adb shell input keyevent KEYCODE_BACK
sleep 1
wait_for_id "$PACKAGE:id/screen_home" || fail_with_logs "Back did not return to Home."

# Verify a selected sample is clearly represented and Back returns Home.
adb shell input swipe 720 1900 720 750 500
sleep 1
tap_id "$PACKAGE:id/sample_bill" || fail_with_logs "Could not select bill sample."
sleep 2
wait_for_id "$PACKAGE:id/screen_result" || fail_with_logs "Sample result did not open."
capture_ui
grep -q "SAMPLE" "$UI_XML" || fail_with_logs "Sample context is not visible."
grep -q "Electricity bill" "$UI_XML" || fail_with_logs "Selected sample identity is not visible."
adb shell input keyevent KEYCODE_BACK
sleep 1
wait_for_id "$PACKAGE:id/screen_home" || fail_with_logs "Back from result did not return Home."

# Verify text sharing cold-starts the safe typed check and Back preserves the draft.
adb shell am force-stop "$PACKAGE"
share_output="$(adb shell am start -W \
    -a android.intent.action.SEND \
    -t text/plain \
    --es android.intent.extra.TEXT "pay-now-share-OTP" \
    -n "$COMPONENT" 2>&1)"
printf '%s\n' "$share_output" > startup-share.txt
sleep 2
wait_for_id "$PACKAGE:id/screen_voice" || {
    cat startup-share.txt
    fail_with_logs "Shared text did not open the voice/text check."
}
adb shell input swipe 720 2000 720 650 500
adb shell input swipe 720 2000 720 650 500
adb shell input swipe 720 2000 720 650 500
sleep 1
tap_id "$PACKAGE:id/voice_check" || fail_with_logs "Could not run the typed safety check."
wait_for_id "$PACKAGE:id/screen_voice_result" || fail_with_logs "Typed safety result did not open."
adb shell input keyevent KEYCODE_BACK
sleep 1
wait_for_id "$PACKAGE:id/screen_voice" || fail_with_logs "Back did not return to the voice/text screen."
adb shell input swipe 720 2000 720 650 500
adb shell input swipe 720 2000 720 650 500
sleep 1
capture_ui
grep -q "pay-now-share-OTP" "$UI_XML" || fail_with_logs "Voice/text draft was not preserved on Back."

# Finish the shared-text Activity, then cold-start Home for permission checks.
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell am start -W -n "$COMPONENT" >/dev/null
sleep 2
wait_for_id "$PACKAGE:id/screen_home" || fail_with_logs "Normal launch did not return Home."

# Android 13+ must show and grant the real notification runtime permission on request.
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [ "$sdk" -ge 33 ]; then
    adb shell input swipe 720 2000 720 650 500
    adb shell input swipe 720 2000 720 650 500
    sleep 1
    tap_id "$PACKAGE:id/notification_enable" || fail_with_logs "Could not request notifications."
    tap_id "com.android.permissioncontroller:id/permission_allow_button" \
        || fail_with_logs "Notification permission dialog did not appear."
    adb shell dumpsys package "$PACKAGE" > package-permissions.txt
    grep -q "android.permission.POST_NOTIFICATIONS: granted=true" package-permissions.txt \
        || fail_with_logs "Notification permission was not granted."
fi

pid="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
[ -n "$pid" ] || fail_with_logs "App process stopped during interaction checks."
adb logcat -d -v threadtime > startup-logcat.txt

echo "Startup, sample context, and Back navigation passed with process $pid."
