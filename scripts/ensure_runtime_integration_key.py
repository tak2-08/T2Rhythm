#!/usr/bin/env python3
"""Create app-private, disposable local test trust; never use this key for a published base."""
import os
from pathlib import Path
import sys
import tempfile

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

key_path = Path(sys.argv[1])
key_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
if not key_path.exists():
    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    descriptor, temporary = tempfile.mkstemp(prefix='.integration-', dir=key_path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as output:
            output.write(key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
                                           serialization.NoEncryption()))
            output.flush()
            os.fsync(output.fileno())
        # Link is atomic and cannot replace a key another Gradle task just created.
        try:
            os.link(temporary, key_path)
        except FileExistsError:
            pass
    finally:
        os.unlink(temporary)
