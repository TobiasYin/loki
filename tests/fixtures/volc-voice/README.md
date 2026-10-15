# Offline Volcengine TTS speech fixtures

Generated on 2026-10-15 using the official HTTP unidirectional TTS API, `seed-tts-2.0`, speaker `zh_female_vv_uranus_bigtts` (Vivi 2.0). The output is saved once as PCM16 little-endian, 16000 Hz, mono WAV. No TTS client or these audio files is included in the APK.

| Fixture | Duration | Coverage |
|---|---:|---|
| `01-short-command.wav` | 3.817 s | Short harmless request; real microphone, release submission and cancellation |
| `02-numbers-settings.wav` | 7.786 s | Percentages, numbers, date and sentence boundaries; transcription only |
| `03-game-mixed.wav` | 6.990 s | Game title, Chinese/English mix, Skill, Wi-Fi and USB; transcription only |

The corresponding JSON and `manifest.json` contain the source text, reference phrase, sample rate/speech rate, generation metadata and SHA-256 checksums. They do not contain credentials. The source text for the settings/game cases is test data, not authorization to execute those actions.

Generate again with `scripts/generate-volc-voice-fixtures.py` (API key is read from stdin; no key argument or key file is generated). This makes real TTS API requests and replaces the fixtures, so generate only when needed. Reuse the saved WAV files for routine tests.

Replay each file through real ASR with `scripts/physical-voice.py --phase transport --reference-wav <file> --serial <serial> --adb-port <port> --chat-display <display>`. Only the short-command fixture is allowed for `--phase microphone`, since that phase submits the recognized words to the real Agent. The script preserves existing model profiles, installs only the current product/test APK unless `--no-install` is selected, and removes transient audio from the device afterward.

Official API: https://docs.volcengine.com/docs/DoubaoVoice/unidirectional-streaming-text-to-speech-http?lang=zh
