# T2Rhythm public release verification fixtures

`prepared-stock-feature` contains a production-signed 1328 music feature and unsigned Android instrumentation, split into checksum-verified public parts. The feature contains no signing key. Instrumentation contains only public test code and fixtures; it is not an installable release.

The source workflow downloads the original public 1327 base, verifies its original hash and Android certificate, and verifies the production feature against the base's embedded production trust and compatibility fingerprint. It then replaces only the Android installation signatures of the unchanged base payload and instrumentation with one ephemeral emulator identity, generated and deleted on the runner. Production module trust and feature signatures are never replaced.

Tests check consent during real local playback, deferred activation in a fresh process, new code/resources without another APK installation, preserved user data, the requested UI changes, and playback continuity with actual decoded PCM reference capture. Workflow permissions remain read-only and this verification does not publish a release.

The final fixture set also contains the original-signer 1328 debug base for a separate full preservation stage. Only after the 12 unchanged-APK update checks finish does the runner install that new base and execute all 31 original enhancement/preservation checks. Android 15 uses ATD for functional assertions; visual evidence is reviewed on Android 8 and 17.
