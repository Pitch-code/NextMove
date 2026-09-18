#!/usr/bin/env bash

set -euo pipefail

bash scripts/startup-smoke.sh
gradle :app:connectedDebugAndroidTest
