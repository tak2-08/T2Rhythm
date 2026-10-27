"""Check the actual installed feature, including implicit embedded-default activation."""
from pathlib import Path
import argparse,hashlib,io,json,struct,subprocess,time,xml.etree.ElementTree as ET,zipfile
parser=argparse.ArgumentParser();parser.add_argument('--assets',type=Path,default=Path('release-assets'));args=parser.parse_args()
package='chromahub.rhythm.app'
def adb(*command):return subprocess.check_output(['adb',*command],timeout=60)
module=next(args.assets.glob('*.t2module'));archive_hash=hashlib.sha256(module.read_bytes()).hexdigest()
with zipfile.ZipFile(module) as z:manifest=json.loads(z.read('manifest.json'))
assert manifest['versionCode']==554991332
payload_path=f'/data/user/0/{package}/no_backup/runtime-trial/{archive_hash}/content.apk'
last=None
for attempt in range(12):
 try:
  payload=adb('exec-out','cat',payload_path)
  assert len(payload)==manifest['payloadSize'] and hashlib.sha256(payload).hexdigest()==manifest['payloadSha256']
  mode=int(adb('shell','stat','-c','%a',payload_path).strip(),8);assert not mode&0o222
  pids=adb('shell','pidof',package).decode().strip().split();assert pids
  maps='\n'.join(adb('exec-out','cat',f'/proc/{pid}/maps').decode() for pid in pids)
  Path('upgrade-process-maps.txt').write_text(maps)
  mapped=archive_hash in maps
  assert mapped,'New feature must be mapped in the running app process'
  break
 except (AssertionError,subprocess.SubprocessError) as error:
  last=error
  if attempt==11:raise
  time.sleep(3)
journal=f'/data/user/0/{package}/no_backup/native-runtime/activation/activation.bin'
exists=subprocess.run(['adb','shell','test','-f',journal],capture_output=True).returncode==0
journal_kind='implicit-embedded-default'
if exists:
 raw=adb('exec-out','cat',journal);Path('upgrade-activation.bin').write_bytes(raw)
 payload_record,checksum=raw[:-32],raw[-32:];assert hashlib.sha256(payload_record).digest()==checksum
 stream=io.BytesIO(payload_record);assert struct.unpack('>II',stream.read(8))==(0x54325253,1)
 def text():return stream.read(struct.unpack('>H',stream.read(2))[0]).decode('ascii')
 def flag():
  value=stream.read(1);assert value in (b'\0',b'\1');return value==b'\1'
 assert text()==archive_hash
 if flag():text()
 assert not flag() and not flag() and not flag()
 assert struct.unpack('>q',stream.read(8))[0]==554991332
 assert not flag() and stream.read()==b''
 journal_kind='persisted-activation'
assert adb('shell','cat',f'/data/user/0/{package}/files/release-upgrade-marker').strip()==b'retained'
adb('shell','uiautomator','dump','/sdcard/upgrade-ui.xml');adb('pull','/sdcard/upgrade-ui.xml','upgrade-ui.xml')
ui=ET.parse('upgrade-ui.xml').getroot();assert any(n.attrib.get('package')==package for n in ui.iter('node'))
adb('shell','screencap','-p','/sdcard/upgrade-startup.png');adb('pull','/sdcard/upgrade-startup.png','upgrade-startup.png')
result={'versionCode':554991332,'activeModuleSha256':archive_hash,'mappedPayloadSha256':manifest['payloadSha256'],'mappedInRunningProcess':True,'payloadReadOnly':True,'activation':journal_kind,'retainedData':True,'startupUiRendered':True}
Path('upgrade-verification.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
