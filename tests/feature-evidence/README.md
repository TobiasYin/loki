# Voice / Skill / streaming acceptance

`physical-features.json` and the instrumentation transcript come from `PhysicalFeaturesTest` on the connected AYN Thor (Android 13, logical chat display 4). The test preserves the installed product profile and encrypted credentials, imports and removes a uniquely named temporary Skill, exercises Native-backed Skill UI, reads one 200 ms microphone packet without sending it anywhere, and renders synthetic structured events in the actual WebView without persisting synthetic chat messages.

`missingVoiceConfigActionable` confirms absent ASR credentials produce a useful message. `cloudAsrVerified: false` is intentional. Voice cloud correctness requires separately configured Volcengine credentials and an online device; no fixture result is represented as a cloud success.

Software test totals, artifact hashes, and this evidence's hashes are recorded in `verification.json`. Full build logs are under ignored `build/new-*.log`; repeat standard checks with the root verification task and device acceptance with `PhysicalFeaturesTest`, never the destructive fixture reset suite on the real profile.
