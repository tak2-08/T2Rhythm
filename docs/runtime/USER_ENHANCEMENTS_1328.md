# Home, genre, artwork and ambient sound changes

The requested home shortcut is removed. Search uses a 12dp gap above its input, matching its lower padding. Home section orders preserve user ordering while normalizing retired artist aliases and adding missing editor entries; visibility continues to use the original home preferences.

The genre projection is prepared when the song cache changes. It combines exact library URI membership with explicit genre corrections and a browsable vocabulary. The disposable AtomicFile projection is validated against a fingerprint of current URI, modification time and genre. Changed/removed tags invalidate the projection. Vocabulary entries never assign genres to songs and retain a zero count when absent.

Artwork-only lookups can use 90–100% normalized title/artist similarity (94% by default), retaining duration corroboration and rejecting mismatched live/cover/remix editions. Music-site results retain priority. YouTube fallback reuses the existing search transport and imports the thumbnail with its watch-page provenance, without inventing title, artist, lyrics or genre metadata. A verified video duration is retained internally for automatic cache validation. Manual artwork and metadata imports keep their existing preview and explicit Save flow. Provider changes clear stale fallback results; disabling YouTube fallback prevents its requests. Explicit YouTube imports remain available when automatic enrichment/artwork is off, while the global online and YouTube fallback choices still apply.

Ambient compensation taps only consumed decoded PCM through a duplicate buffer; it does not change audio samples or buffer positions. A bounded four-second mono reference at 16kHz exists only during the existing opt-in, foreground, permitted playback measurement context. The microphone remains foreground-only. Alignment searches sample offsets, and an adaptive FIR estimate is validated on held-out microphone samples before subtraction in the PCM/power domain. Uncorrelated references, missing/unsupported PCM and clipped microphone windows retain the raw reading. The UI distinguishes raw and corrected relative dBFS. This is an estimate of playback leakage, not a calibrated acoustic echo canceller or absolute sound pressure measurement; devices, DSP and acoustic paths can prevent reliable separation. No audio samples are written or uploaded.

JVM checks cover delayed and inverted playback, a synthetic room response with independent ambient noise, unrelated/clipped inputs, artwork identity/edition/duration rules, disabled/unavailable YouTube search, old home orders and genre membership. Android checks cover the actual controls, layout geometry, disk projection lifecycle and existing playback/artwork flows.

Android 8 SQLite helpers explicitly implement Closeable so their use/close lifecycle does not depend on newer platform interface declarations. Existing database names and schema versions are preserved. Base component, API and pinned resource contracts are unchanged; each production variant retains its public 1327 compatibility fingerprint.

Completed verification before opening the PR:

- All 521 JVM tests passed locally and in [CI](https://github.com/tak2-08/T2Rhythm/actions/runs/40160859817): 395 music, 61 catalog and 65 host tests, with no failures or skips. Base and feature lint reports contain no errors or warnings.
- All three production variants built with the original Android certificate. Their modules verify against the production public key, retain their 1327 compatibility fingerprints, and pass DEX ownership and Android 8 backport-reference audits.
- Android 8, 15 and 17 each passed 43 checks, totaling 129. Each platform verified four real update phases and eight UI/playback checks on the unchanged installed public 1327 base payload, followed by 31 preservation checks on the separately installed 1328 base.

| Platform | Passing production verification |
| --- | --- |
| Android 8 / API 26 | [43/43](https://github.com/tak2-08/T2Rhythm/actions/runs/40178869800/job/121125414246) |
| Android 15 / API 35 | [43/43](https://github.com/tak2-08/T2Rhythm/actions/runs/40178085370/job/121123034692) |
| Android 17 / API 37 | [43/43, rerun](https://github.com/tak2-08/T2Rhythm/actions/runs/40178085370/job/121125708097) |

The Android 8 harness explicitly closes the reconnected IME before each navigation Back event and waits for outgoing song overlays and restored search results; the original selection, restoration and artwork assertions remain. Android 17's existing artist-description editor test passed on rerun without product changes. All production build inputs have remained identical to compiled commit `052ef184d360b0728eb50ab5e10f01aa72ad74bb` throughout these test-only revisions.

Actual home, search, genre, home-layout editor, artwork and large-text captures were inspected on API 26 and 37. API 35 uses ATD for functional assertions; its black captures are excluded from visual evidence. These emulator checks do not establish acoustic accuracy on physical devices; playback compensation remains a conditional relative estimate.
