#!/usr/bin/env python3
"""Activate the real production feature on the unchanged installed public-base payload, then test UI/playback."""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--prepared',type=Path,default=Path('stock-gate'))
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
output=args.output.resolve(); output.mkdir(parents=True,exist_ok=True)
shutil.copyfile(args.prepared/'proof.json',output/'proof.json')
root=Path(__file__).resolve().parents[1]
package='chromahub.rhythm.app.debug'
runner=package+'.test/androidx.test.runner.NativeModuleJUnitRunner'
try:
    subprocess.run(['python3',str(root/'scripts/run_native_update_lifecycle.py'),
        '--app-apk',str((args.prepared/'base.apk').resolve()),'--test-apk',str((args.prepared/'test.apk').resolve()),
        '--resource-name','youtube_artwork_fallback','--resource-text','Use matching YouTube thumbnails as a fallback',
        '--activation-only','--output',str(output/'activation')],check=True)
    results=[]
    for name,count in [('features.local.presentation.metadata.UserEnhancementsTest',6),
                       ('shared.presentation.screens.GenreSearchStateTest',1),
                       ('features.local.presentation.metadata.PlaybackContinuityTest',1)]:
        subprocess.run(['adb','shell','am','force-stop',package],check=True)
        value=subprocess.run(['adb','shell','am','instrument','-w','-r','-e','class','chromahub.rhythm.app.'+name,
            '-e','timeout_msec','240000',runner],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=600)
        (output/(name.rsplit('.',1)[-1]+'.txt')).write_text(value.stdout)
        statuses=[int(x) for x in re.findall(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)',value.stdout)]
        success=bool(re.search(r'OK \('+str(count)+r' tests?\)',value.stdout)) and statuses.count(0)==count and all(x in (0,1,2) for x in statuses)
        results.append(dict(target=name,expected=count,passed=statuses.count(0),success=success))
        (output/'ui-summary.json').write_text(json.dumps(results,indent=2)+'\n')
        assert success,value.stdout[-6000:]
    print('Verified 4 production update phases and 8 UI/playback checks on the unchanged installed 1327 base payload.')
    # Complete the original preservation suite on the separately verified 1328 base.
    # This stage intentionally installs the new base only after all unchanged-APK proofs are committed.
    subprocess.run(['python3',str(root/'scripts/run_preservation_gate.py'),'--native-base','--group','enhancements',
        '--app-apk',str((args.prepared/'current-base.apk').resolve()),'--test-apk',str((args.prepared/'test.apk').resolve()),
        '--output',str(output/'preservation')],check=True)
finally:
    log=subprocess.run(['adb','logcat','-d'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,errors='replace',timeout=30)
    (output/'logcat.txt').write_text(log.stdout,errors='replace')
    files=subprocess.run(['adb','shell','find',f'/sdcard/Android/data/{package}/files','-type','f'],stdout=subprocess.PIPE,text=True,timeout=30)
    for path in files.stdout.splitlines():
        if path.strip().endswith('.png'):
            subprocess.run(['adb','pull',path.strip(),str(output/Path(path.strip()).name)],timeout=60)
