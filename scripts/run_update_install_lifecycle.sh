#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
evidence=app/build/outputs/private-streaming-regression/UpdateInstallLifecycleTest
mkdir -p "$evidence"
./gradlew :app:assembleGithubDebug -PversionCodeOverride=554991325 -PversionNameOverride=5.5.499.1325 \
  --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g --console=plain
fixture=app/build/outputs/apk/github/debug/Rhythm-5.5.499.1325-githubDebug.apk
test -s "$fixture"
adb shell mkdir -p /sdcard/Android/data/chromahub.rhythm.app.debug/files/Download/verified-updates
adb push "$fixture" /sdcard/Android/data/chromahub.rhythm.app.debug/files/Download/verified-updates/lifecycle-future.apk
run_phase() {
  local phase="$1"
  adb shell am instrument -w \
    -e class chromahub.rhythm.app.features.updates.UpdateInstallLifecycleTest \
    -e updateLifecyclePhase "$phase" \
    chromahub.rhythm.app.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$evidence/$phase.txt"
  python3 - "$evidence/$phase.txt" <<'PY'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text()
assert re.search(r'OK \(1 tests?\)', text) and 'FAILURES!!!' not in text, text[-4000:]
PY
}
run_phase seed
adb install -r -t "$fixture"
run_phase verify
adb pull /sdcard/Android/data/chromahub.rhythm.app.debug/files/update-lifecycle-evidence.json "$evidence/evidence.json"
python3 - "$evidence/evidence.json" <<'PY'
import json, pathlib, sys
data = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert data['installedCode'] == data['historyCode'] == 554991325, data
assert data['stage'] == 'INSTALLED' and data['apkRemoved'] and not data['apkExists'], data
print('Verified real package replacement, durable installation history and APK cleanup:', data)
PY
