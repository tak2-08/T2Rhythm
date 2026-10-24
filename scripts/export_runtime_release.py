#!/usr/bin/env python3
"""Export only production-signed feature bundles and verify the original Android signing identity."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding


def sha256(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            result.update(chunk)
    return result.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distribution', action='append', type=Path, required=True)
    parser.add_argument('--apk', action='append', type=Path, required=True)
    parser.add_argument('--apksigner', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    public_bytes = (root / 'runtime-contract/module-public.der').read_bytes()
    public = serialization.load_der_public_key(public_bytes)
    certificate = (root / 'runtime-contract/base-cert-sha256.txt').read_text().strip()
    for apk in args.apk:
        verification = subprocess.run([str(args.apksigner), 'verify', '--verbose', '--print-certs', str(apk)],
                                      check=True, capture_output=True, text=True).stdout
        certificates = re.findall(r'(?m)^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)$', verification)
        if certificates != [certificate]:
            parser.error(f'{apk.name}: original Android signing identity differs')
    args.out.mkdir(parents=True, exist_ok=True)
    seen = set()
    for distribution in args.distribution:
        metadata = json.loads((distribution / 'distribution.json').read_text())
        if metadata.get('testOnly') is not False or (distribution / 'module-public.der').read_bytes() != public_bytes:
            parser.error('Integration packages must never be published')
        archive = distribution / 'default.t2module'
        with zipfile.ZipFile(archive) as package:
            raw = package.read('manifest.json')
            public.verify(package.read('manifest.sig'), raw, padding.PKCS1v15(), hashes.SHA256())
            manifest = json.loads(raw)
        if manifest != metadata['manifest'] or sha256(archive) != metadata['archiveSha256']:
            parser.error('Distribution metadata differs from the signed archive')
        name = f"T2Rhythm-feature-{manifest['versionCode']}-{manifest['runtimeFingerprint']}.t2module"
        if name in seen:
            parser.error('Duplicate release compatibility contract')
        seen.add(name)
        target = args.out / name
        shutil.copyfile(archive, target)
        target.with_suffix(target.suffix + '.sha256').write_text(f'{sha256(target)}  {name}\n')
        shutil.copyfile(distribution / 'shared-contract.json', args.out / name.replace('.t2module', '.contract.json'))
    print(f'Verified {len(args.apk)} original-signer APKs and exported {len(seen)} production feature packages.')


if __name__ == '__main__':
    main()
