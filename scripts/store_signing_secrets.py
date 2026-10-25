#!/usr/bin/env python3
"""Validate T2Rhythm signing inputs and send them to GitHub Secrets over authenticated gh stdin."""
import argparse
import base64
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa

APK_CERTIFICATE = 'c55ec7843dd8190f2ce249ddc1562c616647c50c317930076e2e98bf7e304c18'
ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', default='tak2-08/T2Rhythm')
    parser.add_argument('--module-key', type=Path, required=True)
    parser.add_argument('--properties', type=Path, default=ROOT / '.config/keystore.properties')
    parser.add_argument('--check-only', action='store_true', help='Validate without contacting GitHub')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repo):
        parser.error('Invalid repository name')
    # Fail closed on unsupported Java-properties escapes, rather than uploading changed passwords.
    properties = {}
    for line in args.properties.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith(('#', '!')):
            continue
        name, separator, value = line.partition('=')
        if not separator or '\\' in line:
            raise ValueError('Use simple key=value signing properties without escape sequences')
        properties[name.strip()] = value.strip()
    required = {'key_alias', 'key_password', 'store_password', 'store_file'}
    if set(properties) != required or any(not properties[k] for k in required):
        raise ValueError('Expected the four original APK signing properties')
    keystore = Path(properties['store_file'])
    if not keystore.is_absolute():
        keystore = ROOT / keystore
    keystore_bytes = keystore.read_bytes()
    child_env = dict(os.environ, T2_SIGNING_STORE_PASSWORD=properties['store_password'])
    exported = subprocess.run(['keytool', '-exportcert', '-rfc', '-keystore', str(keystore),
        '-alias', properties['key_alias'], '-storepass:env', 'T2_SIGNING_STORE_PASSWORD'],
        env=child_env, capture_output=True)
    if exported.returncode or x509.load_pem_x509_certificate(exported.stdout).fingerprint(hashes.SHA256()).hex() != APK_CERTIFICATE:
        raise ValueError('Original APK signing certificate verification failed')
    private_bytes = args.module_key.read_bytes()
    key = serialization.load_der_private_key(private_bytes, password=None)
    if not isinstance(key, rsa.RSAPrivateKey) or key.key_size not in (2048, 3072, 4096):
        raise ValueError('Expected an RSA PKCS#8 module key')
    public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    if public != (ROOT / 'runtime-contract/module-public.der').read_bytes():
        raise ValueError('Module key does not match the pinned production public key')
    properties['store_file'] = 'config/signing/rhythm_keystore.jks'
    normalized = ''.join(f'{name}={properties[name]}\n' for name in sorted(required)).encode()
    secrets = {'RUNTIME_MODULE_SIGNING_KEY': private_bytes,
               'SIGNING_KEYSTORE': keystore_bytes, 'SIGNING_PROPERTIES': normalized}
    encoded = {name: base64.b64encode(value) for name, value in secrets.items()}
    if any(len(value) > 48 * 1024 for value in encoded.values()):
        raise ValueError('A secret exceeds the GitHub size limit')
    print('Validated original APK certificate and pinned module key: ' + hashlib.sha256(public).hexdigest())
    if args.check_only:
        print('Check only: no secrets uploaded.')
        return
    auth = subprocess.run(['gh', 'auth', 'status'], capture_output=True)
    if auth.returncode:
        raise ValueError('GitHub CLI authentication is required; no secrets uploaded')
    for name, value in encoded.items():
        result = subprocess.run(['gh', 'secret', 'set', name, '--repo', args.repo],
            input=value, capture_output=True)
        if result.returncode:
            raise ValueError('Secret registration failed for ' + name + '; retry after fixing repository access')
        print('Registered encrypted repository secret: ' + name)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never include subprocess output, exception values or secret-bearing file contents.
        print('Signing secret setup stopped (' + type(error).__name__ + '). Check input files and GitHub access; no key values were printed.', file=sys.stderr)
        sys.exit(1)
