#!/usr/bin/env python3
"""Run native regression classes with strict result counts and retain screenshots before clearing data."""
import argparse, hashlib, json, os, re, subprocess, sys, time, shutil
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--project', default='.')
parser.add_argument('--group', required=True)
parser.add_argument('--output', required=True)
parser.add_argument('--native-base', action='store_true', help='Run the same behavior tests through the fixed base and child feature loader')
parser.add_argument('--legacy-apk', type=Path, help='Explicit baseline APK for the pre-migration comparison')
parser.add_argument('--legacy-test-apk', type=Path)
parser.add_argument('--app-apk', type=Path)
parser.add_argument('--test-apk', type=Path)
parser.add_argument('--package', default='chromahub.rhythm.app.debug')
args = parser.parse_args()
root = Path(args.project).resolve()
out = Path(args.output).resolve(); out.mkdir(parents=True, exist_ok=True)
pkg = args.package
runner = pkg + '.test/androidx.test.runner.' + ('AndroidJUnitRunner' if args.legacy_apk else 'NativeModuleJUnitRunner')

def adb(*values, check=True, timeout=120):
    result = subprocess.run(['adb', *values], text=True, errors='replace', stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f'adb {values}: {result.stdout[-8000:]}')
    return result.stdout

sources = {}
source_directories = [root / 'app/src/androidTest/java']
for path in (path for directory in source_directories for path in directory.rglob('*.kt')):
    text = path.read_text()
    names = re.findall(r'@Test(?:\([^)]*\))?(?:\s+@Suppress\([^)]*\))?\s+fun\s+(\w+)', text)
    # Multiline @Test annotations and method declarations are normal Kotlin syntax.
    count = len(re.findall(r'(?m)^\s*@Test\b', text))
    if count:
        namespace = re.search(r'(?m)^package ([\w.]+)', text).group(1)
        sources[path.stem] = (namespace + '.' + path.stem, count, names)

groups = {
 'native_boot': ['NativeRuntimeBootTest'],
 'file_names': ['LearningFileNamesTest'],
 'runtime': ['NativeModuleLoadingTest'],
 'fixes': ['PlaybackContinuityTest','ReleaseNotesUiTest','PlayerRegressionTest','MetadataConsistencyTest','LyricsEntryAndUpdatesTest','LibrarySearchNavigationTest','PopularPlaybackNavigationTest'],
 'metadata': ['MetadataConsistencyTest','ListeningRecapFlowTest','PageArtworkFlowTest','LearningFileNamesTest','LibraryPagesTest','GenreControlsTest','ManualLyricTimingFlowTest','PersonalInsightsScreensTest','ArtworkRefreshTest','LyricsEntryAndUpdatesTest','UsabilityBehaviorTest'],
 'persistence': ['MetadataEditRaceTest','ContentFlowRegressionTest','DiscoveryPipelineTest','PersonalMediaTest','MaintenanceLifecycleTest','AssetIntegrityRegressionTest','DiscoveryContextTest','PerformancePersistenceTest','DurableStorageTest','LegacyHistoryMigrationTest','ListeningPersistenceTest','LibraryProgressRegressionTest','LocalModeMigrationTest'],
 'navigation': ['DownloadPlaybackRegressionTest','MaintenanceScreensTest','PopularPlaybackNavigationTest','DiscoveryScreensTest','PopularNavigationTest','LibrarySearchNavigationTest','LocalSearchStateTest','GenreSearchStateTest','YouTubeProtocolTest','PlayerRegressionTest','MixShelfTest'],
 'streaming': ['PrivateStreamingTransportTest','PrivateStreamingFlowTest','PeerNetworkFlowTest','PeerNetworkRegressionTest','UpdateHistoryFlowTest','UpdateJournalRegressionTest'],
 'compat': ['MetadataConsistencyTest','ListeningRecapFlowTest','LearningFileNamesTest','LibraryPagesTest','GenreControlsTest','LocalSearchStateTest','GenreSearchStateTest'],
 'artwork': ['PageArtworkFlowTest'],
 'compat_remaining': ['GenreControlsTest','LocalSearchStateTest','GenreSearchStateTest'],
 'newflows': ['PrivateStreamingFlowTest','LibrarySearchNavigationTest','LocalSearchStateTest','GenreSearchStateTest'],
 'visual_baseline': ['UsabilityAuditTest'], 'visual_candidate': ['UsabilityAuditTest'],
}
selected = groups[args.group]
api = int(adb('shell','getprop','ro.build.version.sdk').strip())
adb('shell','dumpsys','battery','set','ac','1')
adb('shell','dumpsys','battery','set','level','100')
adb('shell','settings','put','global','stay_on_while_plugged_in','3')
adb('shell','settings','put','system','screen_off_timeout','1800000')
storage_deadline = time.monotonic() + 60
while True:
    ready = adb('shell', 'mkdir -p /sdcard/Music && touch /sdcard/Music/.t2-ready && rm /sdcard/Music/.t2-ready && echo T2_STORAGE_READY',check=False)
    if 'T2_STORAGE_READY' in ready: break
    assert time.monotonic() < storage_deadline, ready
    time.sleep(1)
fixture = out / 'library-fixture.m4a'
shutil.copyfile(Path(__file__).resolve().parents[1] / 'app/src/androidTest/assets/library-fixture.m4a',fixture)
adb('push', str(fixture), '/sdcard/Music/T2-preservation-fixture.m4a')
adb('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d','file:///sdcard/Music/T2-preservation-fixture.m4a')
scan_deadline = time.monotonic() + 60
while True:
    library = adb('shell','content','query','--uri','content://media/external/audio/media',
        '--projection','title:artist:album:duration',check=False)
    if 'Archive test track' in library and 'T2 Regression' in library: break
    assert time.monotonic() < scan_deadline, library
    time.sleep(1)
print('MediaStore fixture:',library,flush=True)
if args.app_apk:
    assert args.test_apk
    app, test_app = args.app_apk, args.test_apk
elif args.legacy_apk:
    assert args.legacy_test_apk, 'A baseline test APK is required'
    app, test_app = args.legacy_apk, args.legacy_test_apk
else:
    app = root / 'app/build/outputs/apk/github/debug/Rhythm-5.5.499.1325-githubDebug.apk'
    if not app.exists():
        apps = [p for p in (root/'app/build/outputs/apk/github/debug').glob('*.apk') if not any(s in p.name for s in ['-arm64-v8a','-armeabi-v7a','-x86'])]
        assert len(apps) == 1, apps
        app = apps[0]
    test_app = root / 'app/build/outputs/apk/androidTest/github/debug/app-github-debug-androidTest.apk'
adb('install','-r','-t',str(app), timeout=180)
adb('install','-r','-t',str(test_app), timeout=180)
results=[]

def collect(name):
    destination = out / name; destination.mkdir(exist_ok=True)
    remote = f'/sdcard/Android/data/{pkg}/files'
    for file in adb('shell','find',remote,'-type','f',check=False).splitlines():
        file = file.strip()
        if file.endswith('.png') or file.endswith('update-lifecycle-evidence.json'):
            adb('pull',file,str(destination/Path(file).name),check=False,timeout=60)
    (destination/'logcat.txt').write_text(adb('logcat','-d',check=False,timeout=30), errors='replace')
    return destination

def targets_for(name):
    class_name, expected, methods = sources[name]
    isolate = args.group in ('artwork', 'compat', 'compat_remaining') or name == 'LearningFileNamesTest'
    if args.group == 'compat_remaining' and name == 'GenreControlsTest':
        wanted = {'visibilitySwitchHidesTrainingEntryAndKeepsAlgorithmServiceAndData',
                  'hidingTrainingToolsKeepsRecommendationPlaybackAndLyricsContent'}
        methods = [m for m in methods if m in wanted]
        expected = 2
    targets = [(class_name + '#' + method, 1) for method in methods] if isolate else [(class_name, expected)]
    assert targets and (not isolate or len(methods) == expected), (name, expected, methods)
    return targets

for name in selected:
    targets = targets_for(name)
    for target, count in targets:
        label = target.rsplit('.',1)[-1].replace('#','-')
        try:
            adb('shell','am','force-stop',pkg)
            assert 'Success' in adb('shell','pm','clear',pkg)
            permissions = ['android.permission.READ_MEDIA_AUDIO'] if api >= 33 else ['android.permission.READ_EXTERNAL_STORAGE']
            if api >= 33: permissions.append('android.permission.POST_NOTIFICATIONS')
            if api >= 37: permissions.append('android.permission.ACCESS_LOCAL_NETWORK')
            for permission in permissions: adb('shell','pm','grant',pkg,permission,check=False)
            adb('shell','settings','put','system','font_scale','1.0')
            # Android 8 images may refuse clearing logd; diagnostic cleanup must not skip tests.
            adb('logcat','-c',check=False)
            if name in ('ContentFlowRegressionTest','DiscoveryPipelineTest'):
                model = Path(os.environ['PRESERVATION_MODEL'])
                assert hashlib.sha256(model.read_bytes()).hexdigest() == 'eea36124087fed26c59996a4761519458e3bd185e8ea9d9865ad8760c4a1d989'
                adb('shell','mkdir','-p',f'/sdcard/Android/data/{pkg}/files')
                adb('push',str(model),f'/sdcard/Android/data/{pkg}/files/vosk-test-model.zip')
            output = adb('shell','am','instrument','-w','-r','-e','class',target,'-e','timeout_msec','240000',runner,check=False,timeout=600)
            (out/(label+'.txt')).write_text(output)
            statuses = [int(n) for n in re.findall(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)',output)]
            passed = statuses.count(0)
            failures = [n for n in statuses if n not in (0,1,2)]
            ok = bool(re.search(r'OK \('+str(count)+r' tests?\)',output)) and passed == count and not failures and 'FAILURES!!!' not in output
            results.append(dict(target=target, expected=count, passed=passed, status_codes=statuses, success=ok))
            print(json.dumps(results[-1]),flush=True)
            collect(label)
            (out/'summary.json').write_text(json.dumps(dict(api=api, group=args.group, results=results),indent=2))
        except Exception as error:
            if results and results[-1]['target'] == target:
                results[-1].update(success=False,error=str(error))
            else:
                results.append(dict(target=target,expected=count,passed=0,success=False,error=str(error)))
            print(json.dumps(results[-1]),flush=True)
            try: collect(label)
            except Exception: pass
            (out/'summary.json').write_text(json.dumps(dict(api=api,group=args.group,results=results),indent=2))
            try:
                connected = adb('get-state',check=False,timeout=10).strip() == 'device'
            except Exception:
                connected = False
            if not connected:
                raise RuntimeError('Emulator disconnected; remaining assertions did not run.') from error
assert len(results) == sum(len(targets_for(n)) for n in selected), results
assert all(r['success'] for r in results), results
print('Verified native preservation tests:',sum(r['passed'] for r in results),flush=True)
