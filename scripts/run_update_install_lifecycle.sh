#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
evidence=app/build/outputs/private-streaming-regression/UpdateInstallLifecycleTest
mkdir -p "$evidence"
read -r t2_future_code t2_future_name < <(python3 - <<'VERSION'
import pathlib,re
text=pathlib.Path('app/build.gradle.kts').read_text()
code=int(re.search(r'versionCode = overrideVersionCode \?: (\d+)',text).group(1))+1
name=re.search(r'versionName = overrideVersionName \?: "([0-9.]+)"',text).group(1)
parts=name.split('.'); parts[-1]=str(int(parts[-1])+1)
print(code,'.'.join(parts))
VERSION
)
if [[ -n "${T2_UPDATE_FIXTURE:-}" ]]; then
  fixture="$T2_UPDATE_FIXTURE"
else
  ./gradlew :app:assembleGithubDebug "-PversionCodeOverride=$t2_future_code" "-PversionNameOverride=$t2_future_name" \
    --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx3g -Pkotlin.compiler.execution.strategy=in-process --console=plain
  fixture="app/build/outputs/apk/github/debug/Rhythm-$t2_future_name-githubDebug.apk"
fi
test -s "$fixture"
run_phase() {
  local phase="$1"
  adb shell am instrument -w \
    -e class chromahub.rhythm.app.features.updates.UpdateInstallLifecycleTest \
    -e updateLifecyclePhase "$phase" \
    chromahub.rhythm.app.debug.test/androidx.test.runner.NativeModuleJUnitRunner | tee "$evidence/$phase.txt"
  python3 - "$evidence/$phase.txt" <<'PY'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text()
assert re.search(r'OK \(1 tests?\)', text) and 'FAILURES!!!' not in text, text[-4000:]
PY
}
run_phase prepare
t2_device_fixture=$(adb shell cat /sdcard/Android/data/chromahub.rhythm.app.debug/files/update-lifecycle-path.txt | tr -d '\r')
[[ "$t2_device_fixture" == /storage/emulated/0/Android/data/chromahub.rhythm.app.debug/files/*/verified-updates/lifecycle-future.apk ]]
adb push "$fixture" "$t2_device_fixture"
adb shell ls -l "$t2_device_fixture"
run_phase seed
adb install -r -t "$fixture"
run_phase verify
adb pull /sdcard/Android/data/chromahub.rhythm.app.debug/files/update-lifecycle-evidence.json "$evidence/evidence.json"
python3 - "$evidence/evidence.json" "$t2_future_code" <<'PY'
import json, pathlib, sys
data = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert data['installedCode'] == data['historyCode'] == int(sys.argv[2]), data
assert data['stage'] == 'INSTALLED' and data['apkRemoved'] and not data['apkExists'], data
print('Verified real package replacement, durable installation history and APK cleanup:', data)
PY
