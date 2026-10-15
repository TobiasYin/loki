# 真机暂停期间的软件验收 · 2026-10-15

按用户要求，停止 Thor 硬件探测后只使用独立 Android AVD，不复制真机凭据、不恢复硬件测试。本轮测试源码提交 `f09d10d`。原始元数据在提交完成前采集，记录基础提交 `353474d` 与工作区修改；已逐文件核对 testedSources 的 SHA-256 与 `f09d10d` 一致，没有追溯改写原始记录。

## 本轮结果

- 离线基线：59 项 JavaScript、194 项 JVM、Android lint、debug/release/test APK 构建、产物隔离检查通过。
- Android 16 / API36，`emulator-5554`：产品套件耗时 69.525 秒，报告 OK (14 tests)，其中 13 项通过、1 项实际 Shizuku 用例按条件跳过；另有 4 项 Android 解析 fixture 检查通过。
- 两种模型协议通过实际 Pi → Native HTTP → WebView 运行：图片/工具回传、中文 UTF-8 拆包、搜索、HTTP 429、取消、历史恢复与结构化显示。远端模型和搜索由本机受控服务替代，这不是新一轮真实云服务测试。
- 新增匿名 HTTP 回归：两种协议均调用 web.fetch，通过 Android 原生桥接读取回环 HTTP，跨 8877→8878 端口跳转，目标含编码路径；Native 结果包含页面标记，服务端确认没有模型 Authorization 或搜索 token。覆盖 Android JSONObject 显式 null 被转为字符串的实际缺陷。
- Skill ZIP 导入、目录仅元数据、主动读取正文、单独读取引用、停用和重启保留通过；语音模式隐藏文字输入、缺凭据时可操作提示通过。模拟器取得 PCM 数据，但没有上传音频，`cloudAsrVerified=false`；真实火山 ASR 的历史证据另见云端语音验收。
- SQLite 在独立临时库运行：10000 条历史、200 次增量保存、其他会话保持不变、重复事件事务回滚和随后完整保存通过，临时库删除。模拟器观测 1977 ms，不与真机 298 ms 混作同一性能结论。
- 逻辑坐标和 frame_pixels 两条链路在 overlay 显示 2 命中独立 Input Lab；核对原始事件前缀哈希和顺序，单次 DOWN/UP、rawCount=2、hits=1。接收端资格仍为 unqualified，不能据此宣称 Thor POC-11 通过。
- 生命周期退出不重放、renderer 终止后恢复、存储失败先撤销执行、文件写入实际批准/拒绝与 hash 冲突路径均通过。

## 测试环境修复与保留的失败

脚本现在只运行明确的软件测试类，避免把风扇、亮度、真实云服务等 androidTest 自动发现后误执行。显式指定 emulator serial，双重确认 ro.kernel.qemu=1；App 页面显式放在显示 0。升级前临时移除自身旧无障碍组件，保留其他组件并在 finally 恢复，避免 instrumentation 杀进程后旧服务被系统标记 crashed。临时 HTTP 服务和两条端口转发在结束时清理，已确认 reverse 列表为空。

首次旧服务连接失败和第二次错误显示编号均保留原日志。第二次使用模拟器自身虚拟显示 3 时，主屏 0 发出 display_changed 回调，接收端按现有规则判 inconclusive；实际点击命中并不改变该判定。随后按既有文档选择 overlay 显示 2，整套通过。没有修改或放松接收端判定来获得 pass；虚拟显示 3 的环境限制保留，不声称所有模拟器显示类型均已通过。

## 复现与证据

使用专用可丢弃 AVD；本脚本 fixture 会替换该 AVD 中的测试 App 连接资料，拒绝覆盖真实服务地址。先创建或选择 overlay，读取实际显示编号后执行：

```sh
ANDROID_HOME=path/to/android-sdk python3 scripts/emulator-verify.py --serial emulator-5554 --lab-display 2
```

不要直接复制编号到其他 AVD；文档中的 2 是本轮观测值。可提交摘要在 `tests/software-pause-evidence/summary.json`，包含 APK 指纹、源码核对、原始文件哈希、已通过与跳过范围、失败历史和清理状态；完整原始报告/日志在 `build/integration/`。真机剩余任务见 [暂停与续测清单](physical-test-resume.md)。
