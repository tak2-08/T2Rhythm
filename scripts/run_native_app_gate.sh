#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
runtime_api_level="$1"
runtime_batch="$2"
runtime_group="${3:-native_boot}"
if [[ "$runtime_batch" == native_release ]]; then
    python3 scripts/run_preservation_gate.py --group native_boot --package chromahub.rhythm.app \
        --app-apk app/build/outputs/apk/github/release/Rhythm-5.5.499.1327-githubRelease.apk \
        --test-apk app/build/outputs/apk/androidTest/github/release/app-github-release-androidTest.apk \
        --output "native-results/release-api${runtime_api_level}"
    exit 0
fi
if [[ "$runtime_batch" == native_full || "$runtime_batch" == native_diagnostics || "$runtime_batch" == native_upgrade ]]; then
    if [[ "$runtime_group" == baseline_file_names ]]; then
        python3 scripts/run_preservation_gate.py --group file_names --output "native-results/baseline-file-names-api${runtime_api_level}" \
            --legacy-apk native-baseline-artifacts/legacy.apk --legacy-test-apk native-baseline-artifacts/legacy-test.apk
    elif [[ "$runtime_group" == baseline_newflows ]]; then
        python3 scripts/run_preservation_gate.py --group newflows --output "native-results/baseline-newflows-api${runtime_api_level}" \
            --legacy-apk native-baseline-artifacts/legacy.apk --legacy-test-apk native-baseline-artifacts/legacy-test.apk
    elif [[ "$runtime_group" == upgrade_visual ]]; then
        bash scripts/run_native_upgrade_visual_gate.sh
    else
        python3 scripts/run_preservation_gate.py --native-base --group "$runtime_group" --output "native-results/${runtime_group}-api${runtime_api_level}"
    fi
    exit 0
fi
python3 scripts/run_preservation_gate.py --native-base --group native_boot --output "native-results/api${runtime_api_level}"
if [[ "$runtime_batch" == native_update ]]; then
    python3 scripts/run_preservation_gate.py --native-base --group runtime --output "native-results/runtime-api${runtime_api_level}"
    python3 scripts/run_native_update_lifecycle.py --output "native-results/lifecycle-api${runtime_api_level}"
fi
