#!/usr/bin/env python3
"""Export aapt2 --stable-ids input from a verified base APK; never install the APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            digest.update(chunk)
    return digest.hexdigest()


def export(artifact, aapt2, package_name=None):
    result = subprocess.run([str(aapt2), 'dump', 'resources', str(artifact)],
                            check=True, capture_output=True, text=True)
    packages = re.findall(r'^Package name=([\w.]+) id=([0-9a-fA-F]+)\s*$', result.stdout, re.M)
    if len(packages) != 1:
        raise ValueError('Expected one compiled resource package')
    original_name, package_id = packages[0]
    target_name = package_name or original_name
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.]*', target_name):
        raise ValueError('Invalid target package name')
    symbols = {}
    seen_ids = set()
    declarations = re.findall(r'^\s*resource 0x[^\n]+$', result.stdout, re.M)
    for declaration in declarations:
        match = re.fullmatch(r'\s*resource (0x[0-9a-fA-F]{8}) ([\w]+/[\w.$]+)(?:\s.*)?', declaration)
        if not match:
            raise ValueError('Unrecognized resource declaration; refusing a partial ID map')
        identifier, name = match.groups()
        value = int(identifier, 16)
        if value >> 24 != int(package_id, 16) or name in symbols or value in seen_ids:
            raise ValueError('Invalid or duplicate resource declaration')
        symbols[name] = value
        seen_ids.add(value)
    if not symbols:
        raise ValueError('No compiled resources')
    body = ''.join(f'{target_name}:{name} = 0x{value:08x}\n'
                   for name, value in sorted(symbols.items(), key=lambda entry: entry[1]))
    metadata = dict(source_package=original_name, target_package=target_name,
                    apk_sha256=sha256_file(artifact),
                    resource_count=len(symbols), stable_ids_sha256=hashlib.sha256(body.encode()).hexdigest())
    return body, metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--aapt2', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--metadata', type=Path)
    parser.add_argument('--package-name', help='Rewrite only the package prefix for a matching build variant')
    parser.add_argument('--expected-apk-sha256', required=True,
                        help='Checksum of the separately verified base APK')
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9a-f]{64}', args.expected_apk_sha256):
        parser.error('Expected a lowercase SHA-256')
    if sha256_file(args.apk) != args.expected_apk_sha256:
        parser.error('Base APK checksum mismatch')
    body, metadata = export(args.apk, args.aapt2, args.package_name)
    if metadata['apk_sha256'] != args.expected_apk_sha256:
        parser.error('Base APK changed during export')
    args.out.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.out.with_name(args.out.name + f'.{os.getpid()}.tmp')
    try:
        temporary.write_text(body)
        temporary.replace(args.out)
    finally:
        temporary.unlink(missing_ok=True)
    if args.metadata:
        args.metadata.parent.mkdir(parents=True, exist_ok=True)
        args.metadata.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(metadata, ensure_ascii=False))


if __name__ == '__main__':
    main()
