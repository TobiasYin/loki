#!/bin/sh
set -eu
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <explicit-adb-serial> (isolated emulator or user-selected test device)" >&2
    exit 2
fi
toolpack_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$toolpack_dir"
./gradlew :diagnostic:assembleDebug
adb -s "$1" install -r diagnostic/build/outputs/apk/debug/diagnostic-debug.apk
smoke_result=$(adb -s "$1" shell am instrument -w dev.thor.tools.diagnostic/.LocalSmokeInstrumentation)
printf '%s\n' "$smoke_result"
printf '%s\n' "$smoke_result" | rg 'PASS: 7 Android smoke checks' >/dev/null
