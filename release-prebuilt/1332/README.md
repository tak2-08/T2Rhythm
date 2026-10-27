# Verified public release reconstruction: 1332

Source revision: `3122d1b6cc27ebc6eb01a90b4cfe0717ef9dad8e`.

GitHub's already public CI artifact supplies all executable code and resource payloads.
The small `rebuild.json` recipe contains public ZIP fields, public APK/module signatures,
build-version metadata and the publishable Android manifest. `rebuild_apk.py` reconstructs
the exact locally signed APK and rejects any source or final checksum mismatch.

No signing private key, APK payload, or large opaque binary patch is transferred here.
The only feature payload difference from CI is public version-control metadata; the
signed feature manifest and public signature are included explicitly for verification.

The workflow requires four successful source checks, verifies the original APK
certificate and the complete signed embedded module, then installs over the actual
1331 APK and checks retained data, exact 1332 module activation and rendered startup UI.
The release tag points to the tested source commit. `metadata/` contains only public
checksums, contracts, certificates and the module public key.
