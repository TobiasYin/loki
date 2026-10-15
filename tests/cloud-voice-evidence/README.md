# Live voice acceptance, 2026-10-15

Official results are `01-short-command-asr.json`, `02-numbers-settings-asr.json`, `03-game-mixed-asr.json` and `microphone-volc-tts.json`, with the corresponding instrumentation transcripts. These use the saved offline Volcengine TTS fixtures, real Thor network, real Volcengine ASR and, for the microphone case, actual speaker/microphone and real Agent. `model-services.json` summarizes the successful OpenCode Responses image/tool probe, native device-info read and Brave search.

The Flo/Tingting and `microphone-macos-tts` files preserve the earlier synthetic reference trials, including their recognition errors; they are superseded as the formal reference set by the Volcengine-generated WAV cases. No Mac-generated audio is committed here.

All texts are fixed test phrases. Keys and authenticated request/response headers are excluded. `verification.json` records artifact/evidence checksums and formal sample comparisons. See `docs/cloud-voice-acceptance.md` for exact measurement scope; the fixed-sample results do not establish general human-speech accuracy or latency.
