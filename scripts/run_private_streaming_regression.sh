#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
collect() {
  local evidence=app/build/outputs/private-streaming-screenshots
  mkdir -p "$evidence"
  for name in streaming-host-ready streaming-connect-validation streaming-pair-approval streaming-remote-library streaming-large-text-help streaming-playback-controls streaming-song-info; do
    adb pull "/sdcard/Android/data/chromahub.rhythm.app.debug/files/$name.png" "$evidence/$name.png" 2>/dev/null || true
  done
  timeout 15s adb logcat -d > "$evidence/logcat.txt" 2>&1 || true
}
trap collect EXIT
adb shell dumpsys battery set ac 1
classes=(PrivateStreamingTransportTest PrivateStreamingFlowTest)
counts=(16 7)
failed=0
for index in "${!classes[@]}"; do
  name="${classes[$index]}"
  result=0
  ./gradlew :app:connectedGithubDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=chromahub.rhythm.app.features.streaming.$name" \
    -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000 \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g --console=plain || result=$?
  evidence="app/build/outputs/private-streaming-regression/$name"
  mkdir -p "$evidence"
  cp -R app/build/outputs/androidTest-results/connected "$evidence/results"
  cp -R app/build/reports/androidTests/connected "$evidence/reports"
  if (( result != 0 )); then failed=1; continue; fi
  if ! python3 - "$evidence/results" "${counts[$index]}" <<'PY'
import pathlib, sys, xml.etree.ElementTree as ET
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for path in pathlib.Path(sys.argv[1]).rglob('TEST-*.xml'):
    root = ET.parse(path).getroot()
    for key in counts: counts[key] += int(root.attrib.get(key, 0))
assert counts == dict(tests=int(sys.argv[2]), failures=0, errors=0, skipped=0), counts
print('Verified private streaming Android tests:', counts)
PY
  then failed=1; fi
done
exit "$failed"
