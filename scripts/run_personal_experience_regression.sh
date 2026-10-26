#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

collect_evidence() {
    local evidence_dir="app/build/outputs/experience-screenshots"
    mkdir -p "$evidence_dir"
    adb logcat -d > "$evidence_dir/logcat.txt" 2>&1 || true
    for screenshot in library-search-collapsed-filters.png library-search-large-font.png library-search-discover.png library-search-initials.png library-search-tokens.png library-search-genre-tags.png library-search-genre-songs.png library-search-related-genres.png insights-download-search.png insights-algorithms.png insights-listening-report.png lyrics-ai-entry.png lyrics-ai-options.png lyrics-ai-material.png lyrics-ai-fullscreen.png lyrics-ai-review.png recommendation-settings.png app-updates-clean.png manual-lyrics-editor.png manual-lyrics-save-failed.png genre-multiple-selection.png genre-manual-teaching.png genre-service-with-training-hidden.png genre-library-teaching.png lyrics-manual-in-edit.png page-description-editor.png player-genre-date.png folder-album-setting.png learning-battery-range.png file-name-settings.png file-name-preview.png artist-page-clean.png artist-page-edit-menu.png album-page-clean.png album-page-edit-menu.png artist-online-artwork-search.png catalog-artwork-preview.png catalog-selectable-lyrics.png song-editor-online-artwork.png catalog-large-text-landscape.png recap-overview.png recap-highlights.png recap-large-text.png recap-empty.png; do
        local remote_file="/sdcard/Android/data/chromahub.rhythm.app.debug/files/$screenshot"
        if adb shell test -f "$remote_file"; then
            adb pull "$remote_file" "$evidence_dir/$screenshot" || true
        fi
    done
}
trap collect_evidence EXIT
adb shell dumpsys battery set ac 1

# Separate invocations avoid silently running only the first comma-separated class on AGP 9.
classes=(
    chromahub.rhythm.app.shared.presentation.screens.settings.SettingsSearchRegressionTest
    chromahub.rhythm.app.util.PlaylistTransferRegressionTest
    chromahub.rhythm.app.features.local.presentation.metadata.LibrarySearchNavigationTest
    chromahub.rhythm.app.shared.presentation.screens.LocalSearchStateTest
    chromahub.rhythm.app.shared.presentation.screens.GenreSearchStateTest
    chromahub.rhythm.app.features.ai.AiSafetyTest
    chromahub.rhythm.app.features.runtime.NativeModuleLoadingTest
    chromahub.rhythm.app.features.local.presentation.metadata.MetadataConsistencyTest
    chromahub.rhythm.app.features.local.presentation.metadata.ListeningRecapFlowTest
    chromahub.rhythm.app.features.local.presentation.metadata.PageArtworkFlowTest
    chromahub.rhythm.app.features.local.presentation.metadata.LearningFileNamesTest
    chromahub.rhythm.app.features.local.presentation.metadata.LibraryPagesTest
    chromahub.rhythm.app.features.local.presentation.metadata.GenreControlsTest
    chromahub.rhythm.app.features.local.presentation.metadata.ManualLyricTimingFlowTest
    chromahub.rhythm.app.features.local.data.metadata.AssetIntegrityRegressionTest
    chromahub.rhythm.app.features.local.data.video.DownloadPlaybackRegressionTest
    chromahub.rhythm.app.features.local.presentation.metadata.PersonalInsightsScreensTest
    chromahub.rhythm.app.features.local.presentation.metadata.ArtworkRefreshTest
    chromahub.rhythm.app.features.local.data.discovery.MetadataEditRaceTest
    chromahub.rhythm.app.features.local.presentation.metadata.LyricsEntryAndUpdatesTest
    chromahub.rhythm.app.features.local.presentation.metadata.UsabilityBehaviorTest
)
expected_counts=(2 2 1 2 1 2 20 4 5 13 9 9 14 8 4 1 4 1 1 8 7)
if [[ "${FILE_NAME_COMPAT_ONLY:-0}" == 1 ]]; then
    classes=(chromahub.rhythm.app.features.local.presentation.metadata.MetadataConsistencyTest chromahub.rhythm.app.features.local.presentation.metadata.ListeningRecapFlowTest chromahub.rhythm.app.features.local.presentation.metadata.PageArtworkFlowTest chromahub.rhythm.app.features.local.presentation.metadata.LearningFileNamesTest)
    expected_counts=(4 5 13 9)
fi
for index in "${!classes[@]}"; do
    class_name="${classes[$index]}"
    result=0
    timeout=120000
    if [[ "$class_name" == *ManualLyricTimingFlowTest ]]; then timeout=240000; fi
    ./gradlew :app:connectedGithubDebugAndroidTest \
        "-Pandroid.testInstrumentationRunnerArguments.class=$class_name" \
        "-Pandroid.testInstrumentationRunnerArguments.timeout_msec=$timeout" \
        -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
        --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g --console=plain || result=$?
    evidence="app/build/outputs/experience-regression/${class_name##*.}"
    mkdir -p "$evidence"
    cp -R app/build/outputs/androidTest-results/connected "$evidence/results"
    cp -R app/build/reports/androidTests/connected "$evidence/reports"
    collect_evidence
    if (( result != 0 )); then exit "$result"; fi
    python3 - "$evidence/results" "${expected_counts[$index]}" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for path in pathlib.Path(sys.argv[1]).rglob('TEST-*.xml'):
    root = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(root.attrib.get(key, 0))
assert counts == dict(tests=int(sys.argv[2]), failures=0, errors=0, skipped=0), counts
print('Verified Android test counts:', counts)
PY
done
