#!/bin/sh
set -eu
toolpack_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$toolpack_dir"
./gradlew --console=plain :core:test :diagnostic:assembleDebug :diagnostic:lintDebug :diagnostic:assembleRelease
python3 scripts/check-artifacts.py
