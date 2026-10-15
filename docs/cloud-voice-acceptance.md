# 0.3.1 真实语音与离线火山 TTS 验收

2026-10-15，在用户提供通用语音 API Key 并连接 Wi-Fi 后完成。凭据通过 Native 私有临时输入导入 Android Keystore 加密存储，临时输入随即删除；未写入代码、WebView、报告或音频清单。语音设置为新版 `X-Api-Key`、2.0 小时版 `volc.seedasr.sauc.duration`。模型和搜索凭据保持各自独立。

新文档将 `X-Api-Request-Id` 标为必填，因此 0.3.1 在原有连接标识外补上该请求头，并增加了相应断言。ASR 仍使用双向流式优化端点 `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`。

按用户要求，在电脑端调用火山 TTS 2.0 预先生成三条测试语音，保存于 `tests/fixtures/volc-voice`。TTS 仅为离线测试素材生成工具，不集成到 APK。打包检查同时验证样本文件和 TTS 端点均未进入 APK。

| 火山 TTS 样本 | 时长 | 从开始请求到首个非空文本 | 松手后最终结果 | 最终转写 |
|---|---:|---:|---:|---|
| 短句指令 | 3.817 s | 2409 ms | 185 ms | 与原文一致 |
| 数字、百分比和日期 | 7.786 s | 991 ms | 708 ms | 与原文一致 |
| 游戏名称与中英混合 | 6.990 s | 1303 ms | 138 ms | 仅 `Wi-Fi` 被规范为 `WiFi` |

三个样本均在 Thor 上通过产品同一个 Native `VoiceInput`、真实设备网络、真实火山 ASR 服务逐包发送；没有向解析器伪造云端响应。测试确认中间结果、松手前零提交、最终结果单次提交和录音源关闭。这里的首字/最终结果延迟是单次测试观测值，包含启动/网络时间，不是稳定性能指标；这些固定清晰样本也不代表任意真人语音的准确率。

另用短句 WAV 完成实际声学路径：Thor 扬声器播放 → 物理麦克风采集 → 火山 ASR → WebView 实时文字 → 松手后新增一条用户消息 → OpenCode Go / DeepSeek V4.1 Flash 真实回复“语音测试成功”。同一页面再按住并取消，消息仍为一条，未发生误发。测试结束释放播放器与录音并恢复媒体音量，删除设备端的临时音频。

网络恢复后，现有模型图片/工具探测返回两个 HTTP 200 并通过；真实设备信息读取与 Brave 搜索也通过，模型探测状态已恢复为 passed。

初始 Flo / Tingting 合成语音的连通性对照也保留在证据目录：曾分别将开头“请只”识别成“停止”和“请知”，声学采集还曾漏掉尾部词。后续全部采用火山 TTS 生成的离线样本复测，以上表和 `microphone-volc-tts.json` 为本轮正式结果。

软件验证：App 29 项 JVM 单测通过、Android lint 通过、debug/release 和 instrumentation APK 构建通过；原有 57 项 JavaScript 与共 177 项 JVM 的完整通过记录见前一轮功能验收。本轮 Native 的代码变更为必填请求头及版本号，新增测试工具均位于 host scripts 或 androidTest 中。

可复现工具：`scripts/generate-volc-voice-fixtures.py` 从 stdin 获取 TTS Key、生成有原文与校验和的 WAV；`scripts/physical-voice.py` 从 stdin 可选导入 ASR Key，也可复用已保存的 Native 凭据。两者不把 Key 作为命令行参数，不输出响应头或原始错误体。

原始转写、增量事件、时间和测试输出在 `tests/cloud-voice-evidence`；WAV、生成清单与校验和在 `tests/fixtures/volc-voice`。早期 `tests/feature-evidence` 的 `cloudAsrVerified: false` 是 0.3.0 尚未获得凭据时的历史记录，不作追溯改写。

参考：[火山 ASR 文档](https://docs.volcengine.com/docs/DoubaoVoice/LargemodelstreamingautomaticspeechrecognitionAPI?lang=zh)、[火山 TTS HTTP 文档](https://docs.volcengine.com/docs/DoubaoVoice/unidirectional-streaming-text-to-speech-http?lang=zh)。
