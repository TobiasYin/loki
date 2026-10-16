# Native 读屏错误优先级：后续源码补充

本目录是 `429ffa7d…` App 已构建／安装之后的独立源码验证，**未进入当前正在执行 game12 的安装包**。原停止边界115项、Gradle02／381项JVM和429构建身份保持历史原样，没有替换其哈希或共享清单。

完整 Runtime **139/139** 通过，0失败／取消／跳过／todo；其中新增24项，Responses、Chat Completions和Anthropic每种协议8项。集成TypeScript检查退出码0，成功日志为空。使用真实Pi／provider SDK、受控SSE和合成Native端口，没有运行Gradle、设备、云端模型或Android instrumentation。

测试覆盖安全拒绝与来源替换组合、拒绝附件完全不读取／正文不进入上下文、Native TARGET_CHANGED仍恢复、成功但陈旧的正文仍丢弃、非读屏COMMAND_BLOCKED结构反馈保留、用户停止／epoch撤销／能力关闭仍优先。receipt和unknown事实保持，不自动重复工具。先前隔离复现确认的是分类覆盖问题，没有证明权限绕过或正文泄漏。

[设计说明](../../../docs/native-read-error-priority.md)列明最小修正顺序。summary.json固定最终runtime、新测试、provider-stop、integration和依赖锁哈希，记录旧115源码身份及本次139结果。runtime-node.log和integration-tsc.log仅包含软件命令／测试名／结果，不含模型正文、凭据或设备数据。

manifest.json仅覆盖本目录除自身外的文件。后续新包构建与设备验收必须另记；这次错误优先级修复不解释独立的后台消息／定时器等待问题。
