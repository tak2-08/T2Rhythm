#!/usr/bin/env bash
# android-emulator-runner executes each script line separately; keep the loop in one shell.
set -euo pipefail
cd "$(dirname "$0")/.."
for pass in 1 2 3; do
  timeout --kill-after=15s 360s bash scripts/run_personal_experience_regression.sh
  evidence="app/build/outputs/experience-screenshots/pass-$pass"
  mkdir -p "$evidence"
  cp app/build/outputs/experience-screenshots/*.png "$evidence/"
done
