# Native music application

`:app` is the installed T2Rhythm base. `:runtime-content` contains the existing Kotlin/Compose music application, its assets and original business tests. `:runtime-components` retains the original Android component class names and pinned shared SDK/JNI dependencies. `:runtime-api`, `:runtime-core` and `:runtime-host` provide the fixed callback API, signature/activation rules, private journal and native loader. There is no WebView conversion.

Android Activities use the real installed Activity's lifecycle, ActivityResult registry, ViewModelStore and saved state. The music service uses the installed MediaLibraryService for its Media3 session and notification. WorkManager and Glance use fixed factories/action wrappers. The original application ID, provider authorities, database path, preference files and Android signing identity remain unchanged.

The feature owns music models, ViewModels, screens, player logic, the crawler, extractor, metadata and streaming libraries. Shared Compose, Media3, WorkManager and other SDK classes exist once in the base. D8 rejects changes to resolved shared SDK versions. An ownership/desugaring audit checks actual base and feature DEX classes and Android 8 backport methods.

The frozen resource contract preserves 13,441 IDs from the signed 1326 APK plus base confirmation/recovery labels. Feature containers retain AGP compression choices and are zip-aligned. Public Android resource APIs support API 26 without hidden API calls. Per-context copies isolate mutable resource configurations; a process file lease lets later processes remove dead process resource caches without touching live asset readers.

## Build and signing

Java 17, Android SDK 37/build tools 37.0.0, Python 3 and `cryptography==46.0.3` are required. Ordinary Gradle app builds independently build the feature DEX/resources, validate the Room identity and resource IDs, sign a `.t2module`, and embed it with its public key and host fingerprint:

```sh
./gradlew :app:assembleGithubDebug :app:assembleGithubDebugAndroidTest
./gradlew :runtime-content:testGithubDebugUnitTest :runtime-core:test :crawler:test
```

Without a production key these builds use disposable integration trust, set `android:testOnly=true`, and cannot be exported for publication. Their private key stays in the ignored root `build/runtime-signing` directory. Tests compile against a feature-only JAR but never link those music classes into the base/test APK. `NativeModuleJUnitRunner` shares the selected feature's real loader and SDK identity.

Publication requires the original Android signer in `.config/keystore.properties`, the external RSA PKCS#8 module key matching `runtime-contract/module-public.der`, and explicit production trust:

```sh
./gradlew :app:assembleGithubDebug :app:assembleGithubRelease :app:assembleFdroidRelease \
  -PruntimeTestOnly=false -PrequireReleaseSigning=true -PruntimeSigningKey=/protected/module-production.pk8
```

`RUNTIME_SIGNING_KEY_FILE` is an alternative to the key path property. The release workflow reads `RUNTIME_MODULE_SIGNING_KEY` from GitHub repository secrets. Keys are never packaged, cached as task outputs, or uploaded as artifacts. `scripts/export_runtime_release.py` rejects integration packages, verifies module signatures and the original APK certificate, and exports `T2Rhythm-feature-CODE-FINGERPRINT.t2module` plus checksums/contracts. `runtimeDistributionDir` supports a separately prepared distribution for integration fixtures.

Both channels retain the original build tasks and APK names. The complete shared SDK ABI is deliberately unshrunk in production: independently compiled future features must be able to call SDK methods that today's feature does not use. Feature-owned code is separately D8-compiled. Changing shrinking/renaming requires a new tested base contract.

The fingerprint covers the signing identity, application ID/channel, fixed resource IDs, Room identity, compiler/desugaring versions, actual shared SDK binary hashes, fixed API/components/host sources and base bootstrap/manifest. Ordinary feature code and new private feature resources are excluded. A shared SDK, host or permission change therefore requests a base APK update; an ordinary compatible feature release is selected inside the existing updater.

## Approval, activation and recovery

Download and staging verify exact signatures, sizes, hashes and compatibility without approving or executing the new code. The non-exported base confirmation Activity binds the user's separate approval to the exact archive hash. A fresh user-visible main process may activate it. Reopening an Activity in a live music/sharing/worker process keeps that process's existing loader. The app never interrupts playback to force an update.

The journal commits a trial before executing the candidate, retains its previous good version and confirms health after actual UI initialization. Incomplete startup or a reported fatal error preserves a compatible fallback for the next process; it never swaps loaders in place. The separate crash process cannot consume the primary trial. Explicit offline recovery can restore the signed embedded version and repair activation metadata without deleting music, playlists or settings.

Android-confirmed base upgrades may adopt a newer embedded feature or the same version rebuilt for a new contract; they cannot silently downgrade an approved newer feature. UI saved state is tagged by archive hash, so an updated feature cannot decode old implementation Parcelables. Room schema/identity changes are rejected until migration and rollback compatibility are established.

## Validation

The experience-regression workflow has `native_update` (real independently compiled v2, new resource, consent during playback, signed failure and recovery on APIs 26/29/30/35/37) and `native_full` (original behavior groups, original-to-base APK upgrade/data preservation, and seven screenshot comparisons). See [current evidence](NATIVE_VALIDATION_20261024.md). Probe and smoke checks alone do not establish complete feature/design preservation.
