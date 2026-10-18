#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

collect_evidence() {
    local evidence_dir="app/build/outputs/experience-screenshots"
    mkdir -p "$evidence_dir"
    adb logcat -d > "$evidence_dir/logcat.txt" 2>&1 || true
    for screenshot in insights-download-search.png insights-algorithms.png insights-listening-report.png; do
        adb pull "/sdcard/Android/data/chromahub.rhythm.app/files/$screenshot" "$evidence_dir/$screenshot" || true
    done
}
trap collect_evidence EXIT
adb shell dumpsys battery set ac 1
./gradlew :app:connectedGithubDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=chromahub.rhythm.app.features.local.data.metadata.AssetIntegrityRegressionTest,chromahub.rhythm.app.features.local.data.video.DownloadPlaybackRegressionTest,chromahub.rhythm.app.features.local.presentation.metadata.PersonalInsightsScreensTest,chromahub.rhythm.app.features.local.presentation.metadata.ArtworkRefreshTest,chromahub.rhythm.app.features.local.data.discovery.MetadataEditRaceTest \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g --console=plain
