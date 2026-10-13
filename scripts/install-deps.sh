#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for directory in runtime native-bridge/js app; do npm ci --ignore-scripts --prefix "$directory"; done
./gradlew :app:dependencies :input-lab:dependencies
