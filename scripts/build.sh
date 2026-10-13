#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/integration
./gradlew :app:assembleDebug :app:assembleRelease :input-lab:assembleDebug :input-lab:assembleRelease :bridge-host:assembleDebug :diagnostic:assembleDebug
python3 scripts/check-artifacts.py
