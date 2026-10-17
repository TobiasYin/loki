# Keyboard input — DEBUG fixture correction

This supplement preserves the original [software bundle](../software/) and records one test-fixture correction. It does **not** claim a new device acceptance pass.

The receiver labelled read-only originally used `EditText` with `keyListener=null`. Android still exposed it as editable and advertising `ACTION_SET_TEXT`; the first component attempt accepted text there and correctly failed its receiver assertion. The fixture now uses an ordinary `TextView` with the same resource id and report fields. Platform capability metadata and test assertions were not overridden.

That first attempt remains **failed**. Unicode replacement without implicit submit, selection insertion and empty replacement passed before the failure; real `input.key_press` was never reached. The bounded facts and private report hash are retained in [summary.json](summary.json).

Rebuild passed in **5 s**, 281 tasks: **18 executed / 263 up-to-date**. Input Lab's existing 74 tests executed again; this is the same 74-test part of the original 474-test inventory, not 74 additional tests. Runtime 254 and chat 73 were retained rather than rerun. App and Android test assembly were up-to-date.

[Source hashes](source-hashes.json) show the sole changed DEBUG fixture file; the remaining 30 source hashes exactly match the original selected inventory. All [artifact entries](artifacts.json) and [three fixed APKs](pins.json) were checked against actual bytes. App and Android test APK hashes are unchanged; only Input Lab debug changed to `b8c2944f9e73b131d0da2a7e34f01758305fd12ac8000a8bc5c1caa395549f34`.

The [manifest](manifest.json) covers every published file in this supplement except itself. Raw device reports, APK binaries and full build logs remain in ignored build directories. No cloud, physical-device or game completion is implied.
