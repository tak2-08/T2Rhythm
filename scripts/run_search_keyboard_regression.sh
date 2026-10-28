#!/usr/bin/env bash
# android-emulator-runner executes each script line separately; keep the loop in one shell.
set -euo pipefail
cd "$(dirname "$0")/.."
for pass in 1 2 3; do
  # Boot completion can precede a transient adbd reconnect. This does not retry tests.
  timeout --kill-after=5s 60s adb wait-for-device
  deadline=$((SECONDS + 60))
  until timeout 5s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -qx 1; do
    if (( SECONDS >= deadline )); then exit 1; fi
    sleep 1
  done
  timeout --kill-after=15s 360s bash scripts/run_personal_experience_regression.sh
  evidence="app/build/outputs/experience-screenshots/pass-$pass"
  mkdir -p "$evidence"
  cp app/build/outputs/experience-screenshots/*.png "$evidence/"
done
