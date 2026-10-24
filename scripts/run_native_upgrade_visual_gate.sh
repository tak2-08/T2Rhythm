#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
runtime_package=chromahub.rhythm.app.debug
mkdir -p app/build/outputs/apk/github/debug app/build/outputs/apk/androidTest/github/debug native-results/upgrade
cp native-baseline-artifacts/legacy.apk app/build/outputs/apk/github/debug/Rhythm-legacy-githubDebug.apk
cp native-baseline-artifacts/legacy-test.apk app/build/outputs/apk/androidTest/github/debug/app-github-debug-androidTest.apk
python3 scripts/run_preservation_gate.py --group visual_baseline --output native-results/visual-baseline

run_upgrade_assertion() {
    local runtime_class="$1"
    local runtime_runner="$2"
    local runtime_output="$3"
    adb shell am instrument -w -r -e class "$runtime_class" -e timeout_msec 240000 "$runtime_runner" > "$runtime_output"
    python3 - "$runtime_output" <<'PY'
import pathlib,re,sys
text=pathlib.Path(sys.argv[1]).read_text()
statuses=[int(n) for n in re.findall(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)',text)]
assert re.search(r'OK \(1 tests?\)',text) and statuses.count(0)==1 and all(n in (0,1,2) for n in statuses), text[-12000:]
print('Verified one real APK upgrade assertion:',pathlib.Path(sys.argv[1]).name)
PY
}
adb shell am force-stop "$runtime_package"
run_upgrade_assertion chromahub.rhythm.app.features.runtime.LegacyUpgradeSeedTest \
    "$runtime_package.test/androidx.test.runner.AndroidJUnitRunner" native-results/upgrade/legacy-seed.txt
# The same CI-generated Android signer is used by both builds. This is a genuine in-place upgrade.
adb install -r -t runtime-app-base/build/outputs/apk/debug/runtime-app-base-debug.apk
adb install -r -t runtime-app-base/build/outputs/apk/androidTest/debug/runtime-app-base-debug-androidTest.apk
run_upgrade_assertion chromahub.rhythm.app.features.runtime.NativeApkUpgradeTest \
    "$runtime_package.test/androidx.test.runner.NativeModuleJUnitRunner" native-results/upgrade/native-data-preserved.txt
adb logcat -d > native-results/upgrade/logcat.txt 2>&1 || true
python3 scripts/run_preservation_gate.py --native-base --group visual_candidate --output native-results/visual-native
python3 -m pip install --disable-pip-version-check pillow==11.3.0
python3 scripts/compare_native_preservation_screens.py --baseline native-results/visual-baseline \
    --candidate native-results/visual-native --output native-results/visual-comparison.json
