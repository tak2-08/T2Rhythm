# First complete native-music host validation

Source: `26ff4d26b29f5a5e633c6a877996bad552d3444d`.
Run: https://github.com/tak2-08/T2Rhythm/actions/runs/40107230359

The independently compiled full music feature, signed resource/code package, embedded fixed base,
separate instrumentation APK and shared desugared-method/class-ownership audit all built successfully.
500 JVM assertions passed (378 application, 61 crawler, 61 runtime), without failures/errors/skips.

The three real-application checks passed on API 29, 30, 35 and 37: original MainActivity/Compose
and widget code loaded from the feature, the installed Media3 service played a local audio file
without rewinding, and a persisted WorkManager class name executed through the feature factory.
All twelve assertions passed, without skips. This is broader than the earlier tiny Compose probe,
but is not the complete behavior/design preservation gate.

API26 failed in test setup before those assertions: `UiAutomation.grantRuntimePermission` was
called although its public API begins at 28. Both new suites now use `pm grant` on older Android.
The three API26 checks must be rerun; this failed run is not counted as Android 8 app validation.

Downloaded evidence was verified against GitHub's artifact digest:

| Artifact | ID | SHA-256 |
| --- | --- | --- |
| API26 failure | 12566155417 | 8d90534b1fe2472488794bfdde111bf0e01902be3fff7de0c5fa2b06a0327a11 |
| API29 success | 12566315438 | 7b1cacee9846edf20cfcda7d0e41fb311ede91c03fdc50dab5149060b0e4987a |
| API37 success | 12565752089 | 67c0b593ea2081a3e94e384d45aebe07cc2c7dbbfcba83a4e7de29cb56ea9b65 |

The API29 and API37 PNGs were visually inspected: the original home layout, typography, icons,
floating navigation and SDK-dependent theme rendering are present. They do not replace comparison
against the legacy visual baseline or the remaining navigation/player/genre/sharing/settings tests.

The follow-up `native_update` batch additionally builds an actual second full feature with a new
resource, keeps the installed v1 APK unchanged, and checks six ordered phases across processes:
rejection of tampering, download without consent, cancellation/approval during local playback,
new DEX/resource execution, signed startup failure, and known-good recovery with user data retained.
Those checks and the API26 correction are pending at this checkpoint.
