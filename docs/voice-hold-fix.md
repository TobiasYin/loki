# 0.5.1 语音长按误结束修复 · 2026-10-16

用户反馈“按住就自动松开”后，在已连接的 Thor 上排查并安装 0.5.1-integration / versionCode 6。只修改触摸归属、录音按钮布局和验收程序，模型、火山配置、加密凭据与用户历史保留。

## 修复

- 旧页面将任何触点的 `pointercancel` / `lostpointercapture` 都当作当前录音取消。现在只响应发起录音的触点；另一个手指的取消不再结束它。
- `lostpointercapture` 只表示捕获归属结束，不等于手指实际抬起。现在在 document 监听当前触点的抬起、滑动和取消，捕获不可用或中途丢失后仍可在按钮外松手提交；实际 `pointercancel`、窗口失焦、页面隐藏、上滑和明确取消仍停止录音。键盘与触摸释放互不混用。相关语义见 [W3C Pointer Events](https://www.w3.org/TR/pointerevents/)。
- 原“取消本次”按钮出现时会挤占空间，把按住目标上移约 28 CSS 像素。改为覆盖在按住区域内，并固定目标为 44 CSS 像素高，录音文案/取消入口出现时位置不再变化。

没有忽略真正的系统触摸取消，没有把取消操作当作松手发送。

## 验证与边界

旧版本的单点静止长按、轻微移动长按均通过约 5 秒测试，因此没有声称捕获到了用户本人那次原始手势。新增回归在旧处理下确认另一个触点取消会误结束录音。修复后 22 项页面测试通过，覆盖其他触点、捕获丢失、按钮外抬起、键盘/触摸混用、实际取消和失焦。

修复后的 Thor 测试使用平台真实触摸事件，没有用 JavaScript 伪造按下/抬起：

1. 连续按住 5 秒并向下移动；约 1.5 秒时主动释放指针捕获，浏览器发出 trusted `lostpointercapture`。录音保持，直到按钮外的 trusted `pointerup` 才发送一次 `voice.finish`，没有 `voice.cancel`；目标 top 全程为约 361.30 CSS 像素。这个触摸测试使用真实麦克风与服务，但屏蔽 Agent 提交，避免环境音发起任务。
2. 使用之前离线生成的火山 TTS 短句，经设备扬声器 → 真实麦克风 → 火山流式识别。按住 7.5 秒，播放期间没有用户消息，实际松手后才提交“请只回复语音测试成功，不要使用任何工具。”，真实模型回复“语音测试成功”。第二次按住后明确取消，没有新增用户消息。套件 21.855 秒通过，触摸事件均 trusted。

同时通过 App 的 43 项 JVM 检查、Lint、debug/release/test APK 构建、三个视口布局检查与产物权限隔离。安装到设备的 APK SHA-256 回读为 `43d90b359e67530bbeb60483a410ea9564a286939a20b0f877ce6b626e04d3bb`。真实麦克风测试前后 profiles、voice 和 native-credentials 文件哈希一致，测试音量在 finally 中恢复。

## 复现入口

```sh
python3 scripts/physical-resume.py --serial THOR_DEVICE --adb-port 5041 \
  --suite voice-hold --drop-pointer-capture --output build/physical/new-hold-attempt
python3 scripts/physical-voice.py --serial THOR_DEVICE --adb-port 5041 \
  --chat-display 4 --reference-wav tests/fixtures/volc-voice/01-short-command.wav \
  --phase microphone --output build/physical/new-microphone-attempt
```

使用设备已保存的火山与模型配置，不传入密钥；输出目录须为新目录，不能覆盖旧失败报告。第一项为触摸捕获故障测试，第二项为真实声学/云服务/Agent 闭环，证据等级分别记录。

可公开证据见 [语音长按验收](../tests/voice-hold-evidence/README.md)，完整报告、固定 APK 与基线保存在本机忽略目录 `build/physical/voice-hold-2026-10-16/`。这次修复不改变此前 120 Hz、手柄注入和完整输入资格矩阵的未完成状态。
