#!/usr/bin/env python3
"""Create integration-only signed startup-failure data; never accept production trust."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--next-distribution', type=Path, required=True)
    parser.add_argument('--private-key', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    metadata = json.loads((args.next_distribution / 'distribution.json').read_text())
    if metadata.get('testOnly') is not True:
        parser.error('Only a test-only distribution may be used as a failure fixture')
    key = serialization.load_der_private_key(args.private_key.read_bytes(), password=None)
    public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    root = Path(__file__).resolve().parents[1]
    if public == (root / 'runtime-contract/module-public.der').read_bytes():
        parser.error('Production signing keys cannot sign failure fixtures')
    args.out.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.next_distribution / 'default.t2module', args.out / 'next.t2module')
    with zipfile.ZipFile(args.out / 'next.t2module') as source:
        manifest = json.loads(source.read('manifest.json'))
        if manifest['signingKeyId'] != hashlib.sha256(public).hexdigest():
            parser.error('Integration key does not match the compiled next feature')
        manifest['versionCode'] = 554991329
        manifest['versionName'] = '5.5.499.1329-startup-failure-fixture'
        manifest['entryPointClass'] = 'chromahub.rhythm.module.DeliberatelyMissingIntegrationEntry'
        notes = b'Integration-only signed startup failure. The base must recover known-good music code.'
        manifest['notesSize'] = len(notes)
        manifest['notesSha256'] = hashlib.sha256(notes).hexdigest()
        raw = json.dumps(manifest, ensure_ascii=False, separators=(',', ':')).encode()
        with zipfile.ZipFile(args.out / 'bad-startup.t2module', 'w') as output:
            output.writestr('manifest.json', raw)
            output.writestr('manifest.sig', key.sign(raw, padding.PKCS1v15(), hashes.SHA256()))
            output.writestr('content.dex.jar', source.read('content.dex.jar'))
            output.writestr('notes.md', notes)
    print('Created two integration-only lifecycle fixtures; no private key was copied.')


if __name__ == '__main__':
    main()
