#!/usr/bin/env python3
"""Exercise consent, live audio, real code/resource replacement and recovery across Android starts."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--project', type=Path, default=Path('.'))
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
root = args.project.resolve()
out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
package = 'chromahub.rhythm.app.debug'
runner = package + '.test/androidx.test.runner.NativeModuleJUnitRunner'
test_class = 'chromahub.rhythm.app.features.runtime.NativeUpdateLifecycleTest'
results = []


def adb(*command, binary=False, timeout=120, check=True):
    value = subprocess.run(['adb', *command], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if check and value.returncode:
        raise RuntimeError(f'ADB lifecycle command failed: {value.stderr.decode(errors="replace")[-2000:]}')
    return value.stdout if binary else value.stdout.decode(errors='replace')


def state():
    raw = adb('exec-out', 'run-as', package, 'cat', 'no_backup/native-runtime/activation/activation.bin', binary=True)
    if len(raw) < 40 or hashlib.sha256(raw[:-32]).digest() != raw[-32:]:
        raise ValueError('Incomplete activation journal')
    offset = 8
    if struct.unpack_from('>I', raw, 4)[0] != 1: raise ValueError('Unexpected activation format')
    def text():
        nonlocal offset
        size = struct.unpack_from('>H', raw, offset)[0]; offset += 2
        value = raw[offset:offset + size].decode('ascii'); offset += size
        return value
    def boolean():
        nonlocal offset
        value = raw[offset]; offset += 1
        if value not in (0, 1): raise ValueError('Invalid journal boolean')
        return bool(value)
    active = text()
    previous = text() if boolean() else None
    candidate = text() if boolean() else None
    approved, trial = boolean(), boolean()
    highest = struct.unpack_from('>q', raw, offset)[0]; offset += 8
    failed = text() if boolean() else None
    if offset != len(raw) - 32: raise ValueError('Trailing journal data')
    return dict(active=active, previous=previous, candidate=candidate, approved=approved,
                trial=trial, highest=highest, failed=failed)


def wait_state(predicate, label, timeout=120):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = state()
            if predicate(last):
                (out / (label + '.json')).write_text(json.dumps(last, indent=2))
                return last
        except (ValueError, RuntimeError): pass
        time.sleep(.5)
    raise AssertionError(f'{label} did not reach its committed startup state: {last}')


def phase(method):
    result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', test_class + '#' + method,
                 '-e', 'timeout_msec', '240000', runner, timeout=360, check=False)
    (out / (method + '.txt')).write_text(result)
    statuses = [int(value) for value in re.findall(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', result)]
    success = bool(re.search(r'OK \(1 test\)', result)) and statuses.count(0) == 1 and all(s in (0, 1, 2) for s in statuses)
    results.append(dict(method=method, expected=1, passed=statuses.count(0), status_codes=statuses, success=success))
    (out / 'summary.json').write_text(json.dumps(dict(results=results), indent=2))
    print(json.dumps(results[-1]), flush=True)
    assert success, f'{method} failed; see its instrumentation output'


def launch():
    adb('shell', 'am', 'force-stop', package)
    adb('shell', 'am', 'start', '-W', '-n', package + '/chromahub.rhythm.app.activities.MainActivity')


def wait_recovery_activity():
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        activities = adb('shell', 'dumpsys', 'activity', 'activities')
        if re.search(r'(?:mResumedActivity|topResumedActivity)\s*[:=][^\n]*RuntimeRecoveryActivity', activities):
            (out / 'recovery-activity.txt').write_text(activities)
            return
        time.sleep(.5)
    raise AssertionError('The signed bad entry did not reach the installed recovery Activity')


def collect():
    (out / 'logcat.txt').write_text(adb('logcat', '-d', timeout=30, check=False))
    (out / 'activities.txt').write_text(adb('shell', 'dumpsys', 'activity', 'activities', check=False))
    remote = f'/sdcard/Android/data/{package}/files'
    for name in adb('shell', 'find', remote, '-type', 'f', check=False).splitlines():
        if Path(name).name.startswith('native-lifecycle-') and name.endswith('.json'):
            adb('pull', name, str(out / Path(name).name), check=False)


try:
    methods = ['seedRealUserDataAndStageWithoutConsent', 'freshProcessStillUsesOldCodeUntilTheUserApproves',
               'cancelThenApproveWhileRealLocalMusicKeepsPlaying', 'actualNewDexAndNewResourceRunWithoutAnotherApkInstallation',
               'approveSignedStartupFailureFixture', 'failedStartupRestoresKnownGoodCodeAndKeepsUserData']
    source = root / 'runtime-app-base/src/androidTest/java/chromahub/rhythm/app/features/runtime/NativeUpdateLifecycleTest.kt'
    assert set(re.findall(r'@Test\s+fun\s+(\w+)', source.read_text())) == set(methods)
    app = root / 'runtime-app-base/build/outputs/apk/debug/runtime-app-base-debug.apk'
    tests = root / 'runtime-app-base/build/outputs/apk/androidTest/debug/runtime-app-base-debug-androidTest.apk'
    adb('install', '-r', '-t', str(app), timeout=180)
    adb('install', '-r', '-t', str(tests), timeout=180)
    assert 'Success' in adb('shell', 'pm', 'clear', package)
    api = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    permission = 'android.permission.READ_MEDIA_AUDIO' if api >= 33 else 'android.permission.READ_EXTERNAL_STORAGE'
    adb('shell', 'pm', 'grant', package, permission)
    if api >= 33: adb('shell', 'pm', 'grant', package, 'android.permission.POST_NOTIFICATIONS')
    phase(methods[0])
    staged = wait_state(lambda value: value['candidate'] is not None and not value['approved'], 'staged-unapproved')
    phase(methods[1])
    phase(methods[2])
    approved = wait_state(lambda value: value['approved'] and value['active'] == staged['active'], 'approved-old-code-pinned')
    launch()
    healthy = wait_state(lambda value: value['active'] == approved['candidate'] and not value['trial'], 'v2-healthy-same-installed-apk')
    phase(methods[3])
    phase(methods[4])
    broken = wait_state(lambda value: value['approved'] and value['active'] == healthy['active'], 'signed-failure-approved')
    launch()
    wait_state(lambda value: value['active'] == broken['candidate'] and value['trial'], 'signed-failure-left-trial-for-recovery')
    wait_recovery_activity()
    launch()
    wait_state(lambda value: value['active'] == healthy['active'] and not value['trial'] and value['failed'] == broken['candidate'], 'known-good-recovered')
    phase(methods[5])
    assert len(results) == 6 and all(result['success'] for result in results)
    print('Verified 6 native update lifecycle phases across process boundaries.', flush=True)
finally:
    collect()
