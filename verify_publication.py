from pathlib import Path
import hashlib, json, os, re, subprocess, zipfile
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
root=Path('prepared-apks').resolve()
manifest=json.loads((root/'manifest.json').read_text())
assert manifest['source_revision']==os.environ['EXPECTED_REVISION']
output=Path('release-assets'); output.mkdir(exist_ok=True)
seen=set()
for asset in manifest['assets']:
    name=asset['name']
    assert Path(name).name==name and name not in seen
    assert name=='SHA256SUMS' or name.endswith(('.apk','.t2module','.contract.json','.sha256'))
    seen.add(name); digest=hashlib.sha256(); size=0
    with (output/name).open('wb') as target:
        for relative in asset['parts']:
            part=(root/relative).resolve()
            assert part.is_relative_to(root) and part.suffix=='.part'
            data=part.read_bytes(); target.write(data); digest.update(data); size+=len(data)
    assert size==asset['size'] and digest.hexdigest()==asset['sha256']
assert len(seen)==16
def sha(data): return hashlib.sha256(data).hexdigest()
public_bytes=(root/'module-public.der').read_bytes()
public=serialization.load_der_public_key(public_bytes)
modules={}
for path in output.glob('*.t2module'):
    with zipfile.ZipFile(path) as z:
        assert set(z.namelist())=={'manifest.json','manifest.sig','content.dex.jar','notes.md'}
        raw=z.read('manifest.json'); m=json.loads(raw)
        public.verify(z.read('manifest.sig'),raw,padding.PKCS1v15(),hashes.SHA256())
        for entry,prefix in [('content.dex.jar','payload'),('notes.md','notes')]:
            data=z.read(entry)
            assert len(data)==m[prefix+'Size'] and sha(data)==m[prefix+'Sha256']
        assert m['versionCode']==554991327 and m['minSdk']==26
        assert path.name==f"T2Rhythm-feature-{m['versionCode']}-{m['runtimeFingerprint']}.t2module"
        modules[m['runtimeFingerprint']]=sha(path.read_bytes())
sdk=Path(os.environ['ANDROID_HOME'])
signer=sorted((sdk/'build-tools').glob('*/apksigner'),key=lambda p:tuple(map(int,p.parent.name.split('.'))))[-1]
for path in output.glob('*.apk'):
    certificates=subprocess.check_output([str(signer),'verify','--verbose','--print-certs',str(path)],text=True)
    values=set(re.findall(r'(?m)^(?:Signer #\d+|V\d+(?:\.\d+)? Signer:) certificate SHA-256 digest: ([a-f0-9]+)$',certificates))
    assert values=={manifest['certificate_sha256']}
    xml=subprocess.check_output([str(signer.parent/'aapt2'),'dump','xmltree',str(path),'--file','AndroidManifest.xml'],text=True)
    assert re.search(r':testOnly\(0x01010272\)=false\b',xml)
    with zipfile.ZipFile(path) as z:
        assert z.read('assets/runtime/module-public.der')==public_bytes
        fp=re.fullmatch(r'runtimeFingerprint=([a-f0-9]{64})\n',z.read('assets/runtime/host.properties').decode('ascii'))[1]
        assert modules[fp]==sha(z.read('assets/runtime/default.t2module'))
    subprocess.run([str(signer.parent/'zipalign'),'-c','-P','16','4',str(path)],check=True)
assert len(list(output.glob('*.apk')))==3 and len(modules)==3
subprocess.run(['sha256sum','-c','SHA256SUMS'],cwd=output,check=True)
print('Verified all 16 public assets, 3 original-signer APKs and 3 production-signed native modules.')
