#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
runtime_api_level="$1"
runtime_batch="$2"
runtime_group="${3:-native_boot}"
if [[ "$runtime_batch" == native_full ]]; then
    if [[ "$runtime_group" == upgrade_visual ]]; then
        bash scripts/run_native_upgrade_visual_gate.sh
    else
        python3 scripts/run_preservation_gate.py --native-base --group "$runtime_group" --output "native-results/${runtime_group}-api${runtime_api_level}"
    fi
    exit 0
fi
python3 scripts/run_preservation_gate.py --native-base --group native_boot --output "native-results/api${runtime_api_level}"
if [[ "$runtime_batch" == native_update ]]; then
    python3 scripts/run_native_update_lifecycle.py --output "native-results/lifecycle-api${runtime_api_level}"
fi
