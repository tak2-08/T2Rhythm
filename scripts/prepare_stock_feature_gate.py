#!/usr/bin/env python3
"""Prepare the public 1327 base payload and production-signed 1328 feature for isolated emulator tests.

Only Android installation signatures are replaced with an ephemeral test identity, allowing
instrumentation without exporting the real APK signing key. Module trust and base code stay exact.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import urllib.request
import zipfile
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--prepared', type=Path, required=True)
parser.add_argument('--out', type=Path, default=Path('stock-gate'))
args = parser.parse_args()
prepared = args.prepared.resolve()
output = args.out.resolve(); output.mkdir(parents=True, exist_ok=True)
manifest = json.loads((prepared/'manifest.json').read_text())
assert {x['name'] for x in manifest['assets']} == {'next.t2module', 'instrumentation-unsigned.zip', 'current-base-original.apk'}
for asset in manifest['assets']:
    path = output/asset['name']
    checksum = hashlib.sha256(); size = 0
    with path.open('wb') as dest:
        for relative in asset['parts']:
            part = (prepared/relative).resolve()
            assert part.is_relative_to(prepared) and part.suffix == '.part'
            data = part.read_bytes(); dest.write(data); checksum.update(data); size += len(data)
    assert size == asset['size'] and checksum.hexdigest() == asset['sha256']

baseline = output/'original-base.apk'
request = urllib.request.Request('https://github.com/tak2-08/T2Rhythm/releases/download/v5.5.499.1327/T2Rhythm-5.5.499.1327-debug.apk',
                                 headers={'User-Agent': 'T2Rhythm-stock-base-validation'})
with urllib.request.urlopen(request, timeout=180) as response, baseline.open('wb') as dest:
    shutil.copyfileobj(response, dest)
with baseline.open('rb') as stream:
    baseline_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
assert baseline_hash == '6f89eccb03c8ac0d1cf8693c6f9ab9f3e2c93f1aff2a0ddc066b3154908c0cac'
sdk = Path(os.environ['ANDROID_HOME'])
signer = sorted((sdk/'build-tools').glob('*/apksigner'), key=lambda p:tuple(map(int,p.parent.name.split('.'))))[-1]
certificates = subprocess.check_output([str(signer), 'verify', '--verbose', '--print-certs', str(baseline)], text=True)
assert set(re.findall(r'(?m)^(?:Signer #\d+|V\d+(?:\.\d+)? Signer:) certificate SHA-256 digest: ([a-f0-9]+)$', certificates)) == {
    'c55ec7843dd8190f2ce249ddc1562c616647c50c317930076e2e98bf7e304c18'}
with zipfile.ZipFile(baseline) as base:
    public_bytes = base.read('assets/runtime/module-public.der')
    fingerprint = re.fullmatch(r'runtimeFingerprint=([a-f0-9]{64})\n', base.read('assets/runtime/host.properties').decode('ascii'))[1]
public = serialization.load_der_public_key(public_bytes)
current_base = output/'current-base-original.apk'
current_certificates = subprocess.check_output([str(signer), 'verify', '--verbose', '--print-certs', str(current_base)], text=True)
assert set(re.findall(r'(?m)^(?:Signer #\d+|V\d+(?:\.\d+)? Signer:) certificate SHA-256 digest: ([a-f0-9]+)$', current_certificates)) == {
    'c55ec7843dd8190f2ce249ddc1562c616647c50c317930076e2e98bf7e304c18'}
with zipfile.ZipFile(current_base) as current:
    assert current.read('assets/runtime/module-public.der') == public_bytes
    assert current.read('assets/runtime/host.properties').decode('ascii') == f'runtimeFingerprint={fingerprint}\n'
    assert hashlib.sha256(current.read('assets/runtime/default.t2module')).hexdigest() == hashlib.file_digest((output/'next.t2module').open('rb'), 'sha256').hexdigest()
with zipfile.ZipFile(output/'next.t2module') as package:
    assert set(package.namelist()) == {'manifest.json','manifest.sig','content.dex.jar','notes.md'}
    raw = package.read('manifest.json'); module = json.loads(raw)
    public.verify(package.read('manifest.sig'), raw, padding.PKCS1v15(), hashes.SHA256())
    assert module['runtimeFingerprint'] == fingerprint and module['versionCode'] == 554991328
    assert module['signingKeyId'] == hashlib.sha256(public_bytes).hexdigest()
    for name, prefix in [('content.dex.jar','payload'),('notes.md','notes')]:
        data = package.read(name)
        assert len(data) == module[prefix+'Size'] and hashlib.sha256(data).hexdigest() == module[prefix+'Sha256']

def signature_entry(name):
    name = name.upper()
    return name.startswith('META-INF/') and (name.endswith(('.SF','.RSA','.DSA','.EC')) or name == 'META-INF/MANIFEST.MF')

def payload(path):
    with zipfile.ZipFile(path) as archive:
        return {info.filename:hashlib.sha256(archive.read(info)).hexdigest() for info in archive.infolist() if not signature_entry(info.filename)}

unsigned = output/'test-with-feature-unsigned.apk'
with zipfile.ZipFile(output/'instrumentation-unsigned.zip') as source, zipfile.ZipFile(unsigned,'w') as dest:
    for entry in source.infolist():
        if not signature_entry(entry.filename) and entry.filename != 'assets/next.t2module':
            dest.writestr(entry,source.read(entry))
    dest.write(output/'next.t2module','assets/next.t2module',compress_type=zipfile.ZIP_STORED)
aligned = output/'test-aligned.apk'
subprocess.run([str(signer.parent/'zipalign'),'-f','-P','16','4',str(unsigned),str(aligned)],check=True)
temporary = Path(os.environ.get('RUNNER_TEMP', str(output)))
keystore = temporary/f't2-stock-test-{secrets.token_hex(8)}.jks'
env = os.environ.copy(); env['T2_STOCK_TEST_PASS'] = secrets.token_hex(24)
try:
    subprocess.run([shutil.which('keytool'),'-genkeypair','-alias','stock-test','-keyalg','RSA','-keysize','2048',
                    '-validity','2','-dname','CN=T2Rhythm isolated emulator test','-keystore',str(keystore),
                    '-storepass:env','T2_STOCK_TEST_PASS','-keypass:env','T2_STOCK_TEST_PASS'],env=env,check=True,stderr=subprocess.DEVNULL)
    for source,name in [(baseline,'base.apk'),(aligned,'test.apk'),(current_base,'current-base.apk')]:
        target = output/name
        subprocess.run([str(signer),'sign','--ks',str(keystore),'--ks-key-alias','stock-test',
                        '--ks-pass','env:T2_STOCK_TEST_PASS','--key-pass','env:T2_STOCK_TEST_PASS',
                        '--v4-signing-enabled','false','--out',str(target),str(source)],env=env,check=True)
        subprocess.run([str(signer),'verify',str(target)],check=True)
        subprocess.run([str(signer.parent/'zipalign'),'-c','-P','16','4',str(target)],check=True)
        assert payload(source) == payload(target)
finally:
    keystore.unlink(missing_ok=True)
proof = dict(originalBaseSha256=baseline_hash, originalSignerVerified=True, basePayloadUnchanged=True,
             androidInstrumentationSigner='ephemeral test identity', moduleProductionSignatureVerified=True,
             baseFingerprint=fingerprint, moduleVersion=module['versionCode'], source=manifest.get('source_revision'))
(output/'proof.json').write_text(json.dumps(proof,indent=2)+'\n')
print('Verified public 1327 base payload, production module signature and unchanged resource/SDK contract.')
