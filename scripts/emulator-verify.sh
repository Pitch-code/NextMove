#!/usr/bin/env bash

set -euo pipefail

bash scripts/startup-smoke.sh

adb logcat -c || true
set +e
gradle :app:connectedDebugAndroidTest
rc=$?
adb logcat -d -v threadtime > connected-logcat.txt 2>/dev/null || true
exit $rc
