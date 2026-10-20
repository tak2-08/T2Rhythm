#!/usr/bin/env bash
set -euo pipefail
collect_audit() {
    mkdir -p app/build/outputs/usability-audit
    adb logcat -d > app/build/outputs/usability-audit/logcat.txt 2>&1 || true
    for name in audit-01-timing-large-text.png audit-02-timing-landscape.png audit-03-download-search-large-text.png audit-04-settings-search.png audit-05-listening-report-large-text.png audit-06-actual-home.png audit-07-actual-settings.png; do
        adb pull "/sdcard/Android/data/chromahub.rhythm.app.debug/files/$name" "app/build/outputs/usability-audit/$name" || true
    done
    for name in audit-01-timing-large-text.png audit-02-timing-landscape.png audit-03-download-search-large-text.png audit-04-settings-search.png audit-05-listening-report-large-text.png audit-06-actual-home.png audit-07-actual-settings.png; do
        test -s "app/build/outputs/usability-audit/$name"
    done
}
trap collect_audit EXIT
adb shell dumpsys battery set ac 1
./gradlew :app:connectedGithubDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=chromahub.rhythm.app.features.local.presentation.metadata.UsabilityAuditTest \
    -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000 \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g --console=plain
