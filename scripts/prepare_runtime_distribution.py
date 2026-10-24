#!/usr/bin/env python3
"""Build a signed, non-installable native feature bundle from independently compiled inputs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            value.update(chunk)
    return value.hexdigest()


def write_entry(output, name, value, compression=zipfile.ZIP_DEFLATED):
    info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
    info.compress_type = compression
    info.external_attr = 0o100444 << 16
    output.writestr(info, value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--aapt2', type=Path, required=True)
    parser.add_argument('--dex', type=Path, required=True)
    parser.add_argument('--shared-contract', type=Path, required=True)
    parser.add_argument('--database-implementation', type=Path, required=True)
    parser.add_argument('--private-key', type=Path, required=True)
    parser.add_argument('--notes', type=Path, required=True)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--version-name', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--test-only', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    from export_runtime_resource_ids import export
    actual_ids, _ = export(args.apk, args.aapt2, 'chromahub.rhythm.app')
    actual = set(actual_ids.splitlines())
    expected = set((root / 'runtime-contract/resource-ids-v1.txt').read_text().splitlines())
    debug_only = set((root / 'runtime-contract/debug-only-resource-ids-v1.txt').read_text().splitlines())
    assert debug_only <= expected
    required = expected if args.version_name.endswith('-debug') else expected - debug_only
    actual_map = dict(line.split(' = ') for line in actual)
    if not required <= actual or any(name in actual_map and actual_map[name] != value
                                     for name, value in (line.split(' = ') for line in expected)):
        parser.error('The feature removed or renumbered fixed base resources')
    badging = subprocess.run([str(args.aapt2), 'dump', 'badging', str(args.apk)],
                             check=True, capture_output=True, text=True).stdout
    version = re.search(r"^package:.* versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if not version or int(version[1]) != args.version_code or version[2] != args.version_name:
        parser.error('The requested version differs from the independently compiled feature APK')
    key = serialization.load_der_private_key(args.private_key.read_bytes(), password=None)
    if not isinstance(key, rsa.RSAPrivateKey) or key.key_size not in (2048, 3072, 4096):
        parser.error('An RSA PKCS#8 signing key is required')
    public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    if not args.test_only and public != (root / 'runtime-contract/module-public.der').read_bytes():
        parser.error('The signing key does not match the pinned production public key')
    if not 1 <= args.version_code <= 2_147_483_647 or not args.version_name.strip():
        parser.error('Invalid feature version')
    notes = args.notes.read_bytes()
    korean_notes = root / 'app/src/main/res/raw-ko/current_release_notes.txt'
    if korean_notes.is_file():
        notes = ('## 한국어\n\n' + korean_notes.read_text().strip() + '\n\n## English\n\n' +
                 notes.decode('utf-8', errors='strict').strip() + '\n').encode('utf-8')
    if not 1 <= len(notes) <= 128 * 1024 or not notes.decode('utf-8', errors='strict').strip() or b'\0' in notes:
        parser.error('Release notes must be bounded UTF-8 text')
    # A schema change needs a new base/data contract, rather than silently breaking rollback.
    schema = re.search(r'RoomOpenDelegate\((\d+), "([a-f0-9]{32})"', args.database_implementation.read_text())
    if not schema or schema.groups() != ('9', '1a766bc20f966392233ab880e6a63ffb'):
        parser.error('Database identity changed; establish and validate a new base/data contract first')
    shared = json.loads(args.shared_contract.read_text())
    if not shared or any(set(item) != {'id', 'sha256'} for item in shared):
        parser.error('Invalid shared SDK contract')
    package_name = re.search(r"^package: name='([^']+)'", badging, re.M).group(1)
    channel = 'fdroid' if '-fdroid' in args.version_name else 'github'
    inputs = {'shared': shared, 'hostApi': 1, 'database': list(schema.groups()),
              'applicationId': package_name, 'distribution': channel,
              'signingKeyId': hashlib.sha256(public).hexdigest(),
              'debugOnlyResourceIds': digest(root / 'runtime-contract/debug-only-resource-ids-v1.txt'),
              'kotlin': '2.4.21', 'agp': '9.4.1', 'desugar': '2.1.5', 'resourceIds': digest(root / 'runtime-contract/resource-ids-v1.txt')}
    # The feature's ordinary screen/business source is deliberately absent from this fingerprint.
    # Changing a base ABI or pinned SDK requires a base update; ordinary feature code does not.
    inputs['baseSources'] = {
        str(path.relative_to(root)): digest(path)
        for module in ('runtime-api', 'runtime-components', 'runtime-host')
        for path in sorted((root / module / 'src/main').rglob('*')) if path.is_file()
    }
    inputs['baseSources'].update({
        str(path.relative_to(root)): digest(path)
        for path in sorted((root / 'app/src/main/java').rglob('*')) if path.is_file()
    })
    inputs['baseSources']['app/src/main/AndroidManifest.xml'] = digest(root / 'app/src/main/AndroidManifest.xml')
    fingerprint = hashlib.sha256(json.dumps(inputs, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    args.out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='t2-native-package-', dir=args.out) as temporary:
        payload = Path(temporary) / 'content.apk'
        with zipfile.ZipFile(args.apk) as archive, zipfile.ZipFile(args.dex) as dex, zipfile.ZipFile(payload, 'w') as output:
            dex_names = dex.namelist()
            if not dex_names or any(not re.fullmatch(r'classes(?:[2-9]|[1-9][0-9]+)?\.dex', name) for name in dex_names):
                parser.error('The feature D8 output must contain only Android DEX entries')
            seen = set()
            for entry in archive.infolist():
                name = entry.filename
                if entry.is_dir() or re.fullmatch(r'classes\d*\.dex', name) or name.startswith('lib/'):
                    continue
                if name.startswith('META-INF/') and (name.upper().endswith(('.SF', '.RSA', '.DSA')) or name == 'META-INF/MANIFEST.MF'):
                    continue
                if name in seen or name.startswith('/') or '..' in Path(name).parts:
                    parser.error('Unsafe or duplicate resource container entry')
                seen.add(name)
                # Keep AGP's no-compress choices: openRawResourceFd/openFd and font/media readers
                # must retain the same seekable assets as the original installed application.
                write_entry(output, name, archive.read(entry), entry.compress_type)
            for name in dex_names:
                write_entry(output, name, dex.read(name))
        # Android's resource parser maps the uncompressed table directly. Repacking must preserve
        # its four-byte alignment even though this resource/code container is never installed.
        aligned = Path(temporary) / 'aligned-content.apk'
        zipalign = args.aapt2.parent / 'zipalign'
        subprocess.run([str(zipalign), '-f', '4', str(payload), str(aligned)], check=True, capture_output=True)
        subprocess.run([str(zipalign), '-c', '4', str(aligned)], check=True, capture_output=True)
        aligned.replace(payload)
        if payload.stat().st_size > 192 * 1024 * 1024:
            parser.error('Feature payload exceeds the host limit')
        manifest = dict(formatVersion=1, moduleId='t2rhythm.main', versionCode=args.version_code,
                        versionName=args.version_name, signingKeyId=hashlib.sha256(public).hexdigest(),
                        entryPointClass='chromahub.rhythm.module.RhythmFeatureModule', hostApi=1,
                        runtimeFingerprint=fingerprint, minBaseVersion=1, maxBaseVersion=1,
                        minSdk=26, maxSdk=2147483647, minDataSchema=1, maxDataSchema=9,
                        payloadSize=payload.stat().st_size, payloadSha256=digest(payload),
                        notesSize=len(notes), notesSha256=hashlib.sha256(notes).hexdigest())
        raw = json.dumps(manifest, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        signature = key.sign(raw, padding.PKCS1v15(), hashes.SHA256())
        package = Path(temporary) / 'default.t2module'
        with zipfile.ZipFile(package, 'w') as output:
            write_entry(output, 'manifest.json', raw, zipfile.ZIP_STORED)
            write_entry(output, 'manifest.sig', signature, zipfile.ZIP_STORED)
            write_entry(output, 'content.dex.jar', payload.read_bytes(), zipfile.ZIP_STORED)
            write_entry(output, 'notes.md', notes, zipfile.ZIP_STORED)
        if package.stat().st_size > 224 * 1024 * 1024:
            parser.error('Signed archive exceeds the host limit')
        package.replace(args.out / 'default.t2module')
    (args.out / 'module-public.der').write_bytes(public)
    (args.out / 'host.properties').write_text(f'runtimeFingerprint={fingerprint}\n')
    (args.out / 'shared-contract.json').write_text(json.dumps(inputs, sort_keys=True, indent=2) + '\n')
    (args.out / 'distribution.json').write_text(json.dumps(dict(manifest=manifest, archiveSha256=digest(args.out / 'default.t2module'),
                                                            testOnly=args.test_only), ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'version': args.version_name, 'runtimeFingerprint': fingerprint,
                      'bytes': (args.out / 'default.t2module').stat().st_size, 'testOnly': args.test_only}))


if __name__ == '__main__':
    main()
