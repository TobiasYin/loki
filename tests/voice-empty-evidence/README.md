# Voice silence / empty-result acceptance

The fixed App `006554347a6012671f238110458dafa00b70ccbfb14e70b78e2013f4dd73a953`, instrumentation `1c6bf788b8ae8dc22ff7667506bee9d2e50786cfaedbd4ede6263c01b8743d89` and unchanged Input Lab `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` have the following independent scopes.

| Evidence | Actual result | Scope |
| --- | --- | --- |
| [Software](software/README.md) | App JVM112 passed, including19 Native voice cases; JS73 passed; build/lint/artifact checks passed | Exact silence codes, both packet forms, malformed input, real local WebSocket, partial/null/empty handling, release/cancel/race, page state |
| [Emulator](emulator/README.md) | Existing ModernUI1 passed in23.356s | Real WebView synthetic chat interactions only, not voice-specific end-to-end |
| [Thor real cloud](physical/README.md) | 1 passed in3.679s,15 zero-PCM packets/3000ms, terminal empty | Production VoiceInput against configured VolcEngine ASR; no microphone or model task |

Normal no-speech codes are exactly20000003 and1013. Empty/null/missing text in a valid successful terminal is also normal. Each clears the current speech partial, waits for release and returns empty without submission. The page returns idle without an error or retry message, preserving the ordinary typed draft. Missing audio45000002, authentication, format, timeout, busy/unknown service codes, malformed packets and transport errors before a valid terminal remain genuine errors. The server's raw error body is never rendered.

The initial software run had a real test-fixture failure: attempting cancel on MockWebServer's server-side WebSocket has no client Call and raised NPE. The failed log/XML are retained in software. Only the failure injection changed to actual TCP server shutdown with observed IOException before the final successful run; no failed product behavior was hidden.

The data-preserving Thor install kept the original four preference files and main database bytes; accessibility was restored. No new conversation/model/game task was started. The normal launcher was reopened after the test and its current MainActivity was checked on lower display4. The cloud report contains only phases/lengths/fixed client errors, not provider response bodies/codes or credentials; exact-code branches are proven by controlled software tests. No physical microphone, acoustic accuracy or complete voice UI hold/cloud/model end-to-end result is claimed.

Behavior and official references are in [voice-empty-result.md](../../docs/voice-empty-result.md). Public files contain no API key, raw DB/XML backup, APK or recorded audio. This manifest covers all published descendants and their local manifests; itself is excluded.
