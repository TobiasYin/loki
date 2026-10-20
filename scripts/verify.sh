#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check-public-source.py
mkdir -p build/integration
for directory in runtime native-bridge/js app; do npm ci --ignore-scripts --registry=https://registry.npmjs.org/ --prefix "$directory"; done
npm test --prefix runtime
npm test --prefix native-bridge/js
npm test --prefix app
./runtime/node_modules/.bin/tsc -p integration/tsconfig.json
./gradlew :core:test :device-core:test :device-authority:test :app:testDebugUnitTest :input-lab:testDebugUnitTest :android:lintDebug :app:lintDebug :input-lab:lintDebug :device-android:lintDebug :diagnostic:lintDebug :bridge-host:lintDebug :app:assembleDebug :app:assembleRelease :input-lab:assembleDebug :input-lab:assembleRelease :app:assembleDebugAndroidTest :input-lab:assembleDebugAndroidTest :diagnostic:assembleDebug :bridge-host:assembleDebug
python3 scripts/check-artifacts.py
