# Native music application port

This branch adds a separately runnable fixed base (`runtime-app-base`) and a music feature
container (`runtime-content`). The legacy `app` is still the comparison application. This is a
pre-PR migration checkpoint, not a production deployment or a completed preservation review.

The feature reuses the original music source, assets, Compose UI and resources. Only Android
component implementations become delegates; their installed class names stay in
`runtime-components`. Activities keep the real Activity's lifecycle, result registry and
ViewModel store. Media3 keeps the actual installed MediaLibraryService. WorkManager and Glance
reflection use fixed factories and action wrappers. Music model classes stay inside the feature.

The base packages the pinned SDK/JNI dependencies and one signed offline default. The feature
DEX includes its own code, crawler, extractor, metadata and streaming libraries. The SDK version
guard rejects an independently upgraded shared dependency. The resource contract retains all
13,441 IDs from the signed 1326 APK and reserves the base's recovery/confirmation labels.
Resource copies retain AGP compression choices and are zip-aligned before signing, so seekable
media/font assets keep working. An ownership audit checks the installed DEX contains no music
implementation and that feature desugared method references are provided.

Downloads reuse the existing progress/resume UI. A native update is copied into private storage,
verified and staged without consent or execution. The fixed, non-exported confirmation Activity
binds approval to its exact archive hash. A fresh user-visible process can activate it; a running
process never switches loaders. A first-start trial retains the last good module. A separate
crash UI does not consume the primary process's trial marker. The current Room identity remains
version 9; an incompatible storage contract requires separate migration/rollback work.

Android-confirmed base APK upgrades can adopt a newer embedded default, or a same-version feature
rebuilt against the new SDK contract. They cannot silently replace an already approved newer
feature with an older embedded package. Persistent music, playlists, preferences and existing
component authorities stay in the same installed application. UI-only saved state is tagged and
nested by archive hash so an updated feature cannot decode obsolete Parcelables; same-version
Activity recreation retains that state.

Development builds require `scripts/prepare_runtime_distribution.py`, then Gradle's
`runtimeDistributionDir` property. Integration trust additionally requires `runtimeTestOnly=true`;
Android marks those APKs test-only. A publishable build must match the committed production
public pin. Private keys stay outside the repository and test artifacts.

The existing experience-regression workflow's `native_app` batch first builds the feature and
base independently, then runs the real MainActivity, local playback service and persisted worker
on API 26, 29, 30, 35 and 37. The original Android tests compile against a feature-only JAR and
run through a test class-loader bridge; they are not linked into the installed base. Passing
legacy JVM tests or the earlier tiny Compose probe does not prove this complete music port.

The `native_update` batch runs genuine independently compiled code/resource updates through user
confirmation and process restarts, including live local audio and a signed bad-startup fixture.
The `native_full` batch runs the original preservation groups through the child-loaded application.
It also builds the pinned original APK with the same Android signer, seeds a real playlist/settings/
private file, upgrades in place, checks UID/storage/signature/data preservation, and compares seven
original/native screenshots including large text, landscape, actual home and settings.

The fixed host records a compatible fallback before delegating an uncaught fatal error to the
original crash UI. The previous good feature remains available after first-start health succeeds.
The installed recovery screen can also explicitly restore the signed embedded default when the
activation journal is damaged; this replaces activation metadata without deleting music or settings.

Before PR: run the original behavior/visual preservation groups through this host, test signed
code-only v1-to-v2 updates and recovery across process boundaries, verify legacy APK user-data
upgrades, exercise release builds, and finish the standard application/release pipeline migration.
