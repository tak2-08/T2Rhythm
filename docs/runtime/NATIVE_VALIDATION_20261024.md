# Native T2Rhythm preservation validation — 2026-10-24

The installed `app` is the native base and `runtime-content` contains the music application. Existing native UI/business sources are reused; there is no WebView conversion. The source comparison baseline is original `8f0be268f397081a612d08358b6bf9261bebecc7` (1326).

| Gate | Evidence | Result |
| --- | --- | --- |
| Code-only updates, native loading and recovery | [40130744627](https://github.com/tak2-08/T2Rhythm/actions/runs/40130744627) | 140 checks: 28 each on API26/29/30/35/37 |
| Actual release SDK, UI, local Media3 playback and worker | [40128935950](https://github.com/tak2-08/T2Rhythm/actions/runs/40128935950) | 9 checks: 3 each on API26/35/37 |
| Original behavior groups | [40127512122](https://github.com/tak2-08/T2Rhythm/actions/runs/40127512122) | Streaming, navigation, persistence, genres, artwork, player and related groups passed; file-preview test scheduling was verified separately below |
| File names, preview, rename, tags/URI/manual-link preservation | [40130423116](https://github.com/tak2-08/T2Rhythm/actions/runs/40130423116) | All 9 checks in both original and native apps (18 total) |
| Original APK → base APK upgrade and visual preservation | [40132260510](https://github.com/tak2-08/T2Rhythm/actions/runs/40132260510) | Data/UID/signer/storage checks passed; 7/7 screen comparisons passed |
| JVM regressions | Local final build and build-job reports | 504 checks: 378 music, 65 runtime policy, 61 crawler; zero failures/errors/skips |
| DEX ownership/backported-method audit | Actual signed debug, regular and F-Droid APKs | Music implementation absent from base; all 6 feature backport method references resolved |

The code-only gate builds a genuine independent v2 feature with a new private string resource. It verifies tamper rejection before execution, staging without approval, cancellation and approval while local music plays, fresh-process activation without another APK installation, unchanged APK installation timestamp, signed bad-entry failure, and recovery to a known-good module with user data retained. Nineteen framework checks per SDK cover independent native Compose/resource loading, private immutable files, persistent activation metadata and live/dead process resource-cache leases.

The release gate uses the actual non-debug package and shared SDK. It also invokes the retained Java 10 collector backport from the installed base on Android 8; its presence is independently audited in DEX before publishing.

The upgrade gate installs the pinned original APK, seeds a Room playlist, preferences and private file, flushes preferences to disk, and performs a real `adb install -r`. It verifies the same UID, Android signer, first-install time, data directory, playlist, settings and file. Disk preferences are checked before and after replacement, rather than assuming asynchronous test writes have finished.

Seven original/native screenshots cover large text, landscape, download search with a visible keyboard, settings search, listening reports, actual home and actual settings. Six application-region comparisons are pixel-identical. The keyboard capture differs in 0.77% of pixels (mean channel difference 0.434), within the existing 3%/3.0 limits; thresholds were not increased. Both builds use the real Korean app-language API, the same stable settings, and wait for the input-method window. Independently rotating suggestions are disabled consistently for the stable settings-page comparison.

The original behavior batch's file-preview check timed out with its virtual coroutine dispatcher. The separate original/native file-name gate uses ordinary UI coroutine dispatch and preserves every preview/rename assertion. Likewise, the first API30 multi-suite run rewrote an already indexed fixture and lost its MediaStore tags during scanner setup; the final 140-check run reuses an indexed recording only after validating both tags and exact SHA-256. These are test-fixture corrections, not skipped assertions or relaxed product checks.

A source audit found 496 byte-identical ordinary Kotlin files, eight targeted update/quality/crash integration changes and sixteen Android component files moved behind fixed original-name wrappers. All five original music assets and 150 original resource files are byte-identical; only release-note bodies were updated. Frozen IDs preserve the original 13,441-symbol pool plus base confirmation/recovery labels. Release builds may omit the explicit 221 debug-only LeakCanary symbols, while every present frozen symbol retains its ID.

These checks use Android emulators and do not claim physical-device testing of Bluetooth radios, USB audio hardware or camera optics. Their existing implementation and Android permissions remain in place. The shared SDK remains unshrunk to preserve its published ABI; changing that policy, OS permissions or storage contracts requires a separately tested base update. Reopening an Activity in a live process intentionally keeps that process's current code; approved updates activate at a fresh safe launch.
