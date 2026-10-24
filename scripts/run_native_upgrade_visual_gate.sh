#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
runtime_package=chromahub.rhythm.app.debug
mkdir -p native-results/upgrade
python3 scripts/run_preservation_gate.py --group visual_baseline --output native-results/visual-baseline \
    --legacy-apk native-baseline-artifacts/legacy.apk --legacy-test-apk native-baseline-artifacts/legacy-test.apk

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
capture_upgrade_preferences() {
    python3 - "$1" <<'PY'
import json,subprocess,sys,time,xml.etree.ElementTree as ET
keys={'show_keyboard_on_search_open':False,'dark_mode':True,'onboarding_completed':True}
deadline=time.monotonic()+10
while True:
    xml=subprocess.check_output(['adb','exec-out','run-as','chromahub.rhythm.app.debug','cat','shared_prefs/rhythm_preferences.xml'])
    actual={node.attrib['name']:node.attrib.get('value')=='true' for node in ET.fromstring(xml) if node.attrib.get('name') in keys}
    if actual==keys:break
    assert time.monotonic()<deadline, actual
    time.sleep(.2)
with open('native-results/upgrade/'+sys.argv[1]+'.json','w') as output:json.dump(actual,output,indent=2)
print('Verified persisted upgrade preferences:',actual)
PY
}
capture_upgrade_preferences before-apk-replacement
# The same CI-generated Android signer is used by both builds. This is a genuine in-place upgrade.
adb install -r -t app/build/outputs/apk/github/debug/Rhythm-5.5.499.1327-githubDebug.apk
adb install -r -t app/build/outputs/apk/androidTest/github/debug/app-github-debug-androidTest.apk
capture_upgrade_preferences after-apk-replacement
run_upgrade_assertion chromahub.rhythm.app.features.runtime.NativeApkUpgradeTest \
    "$runtime_package.test/androidx.test.runner.NativeModuleJUnitRunner" native-results/upgrade/native-data-preserved.txt
adb logcat -d > native-results/upgrade/logcat.txt 2>&1 || true
python3 scripts/run_preservation_gate.py --native-base --group visual_candidate --output native-results/visual-native
python3 -m pip install --disable-pip-version-check pillow==11.3.0
python3 scripts/compare_native_preservation_screens.py --baseline native-results/visual-baseline \
    --candidate native-results/visual-native --output native-results/visual-comparison.json
