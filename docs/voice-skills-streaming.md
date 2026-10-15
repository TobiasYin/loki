# 语音输入、Skill 与流式展示（0.3.0）

聊天输入区的“语音”按钮切换到语音面板，普通输入框隐藏。按住开始采集，界面显示实时识别文字；新识别结果替换前一个完整转写，纠正未定稿文字。松手停止录音、发送结束帧，收到服务端最终结果后仅发送一次聊天消息。上滑 70 CSS 像素、点击取消、切换模式/会话或离开前台都会取消，迟到的结果不会发送到其他会话。键盘 Space / Enter 也支持按住和松开操作。识别失败时保留可见文字，可“转为文字编辑”；发送失败会把文字恢复到输入框。

在“语音设置”单独配置火山/豆包流式识别：新版控制台 API Key，或旧版 App ID 与 Access Token；选择实际开通的 1.0/2.0 小时或并发资源。密钥通过 Android Keystore 加密，不回填 WebView，不复用模型凭据。语音设置独立于模型资料，保存语音配置不会使模型探测失效。首次切换语音模式会请求麦克风权限。

采用官方推荐的双向流式优化接口 `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`，Native AudioRecord 采集 PCM16 LE、16 kHz、单声道，按 200 ms 分包。建立连接时的短暂音频缓存在内存中（最多 10 秒），连接后发送；不保存音频文件。请求与音频使用 SAUC v3 二进制帧和 Gzip，正确识别序列号、末包标识与错误帧。设置 `enable_nonstream`，兼容 2.0 的二遍修正。连接超时 10 秒，松手后最多等待最终结果 12 秒；整轮上限 65 秒。没有自动重试或后台持续录音。

侧栏新增“Skill 管理”，支持导入单个带 YAML frontmatter 的 `SKILL.md`，或包含一个 Skill 目录及其资源的 ZIP。目录可以包在 ZIP 的多层父目录中，但不接受同一 ZIP 的多个 Skill。页面支持查看正文与资源、启用、停用和删除。内置 `skill-creator` 和 `skill-manager` 可停用，不能覆盖或删除；同名导入不会静默覆盖。修改现有内容通过 Agent 的保存工具和版本检查进行。

Skill 采用 Agent Skills 格式，必填 `name` 与 `description`。Native 目录保存名称、描述、启用状态、来源和版本 hash，绝不把正文放进发现目录。模型的系统上下文只接收启用技能的名称和简介（最多 4096 字符，且不超过模型上下文预算的 8%）；超出部分通过 `skills_list(query, offset)` 查询，每页最多 8 条。只有模型主动调用 `skills_read(name, path)` 才返回正文或单个资源。管理页面查看正文不会自动将内容交给模型。已经读取过的内容可随正常对话历史保留；停用不会追溯抹除历史，可新建会话获得独立上下文。

Agent 工具为 `skills_list`、`skills_read`、`skills_save`、`skills_enable`、`skills_delete`。工具均由构建时登记，导入的 Skill 不会注册工具、授予设备权限或自动执行脚本。只有用户要求时才创建/管理；Native 写入前显示具体内容并请求确认，单次变更审阅预算 64 KiB。更新、启停和删除检查当前 hash，拒绝覆盖过时版本。正文引用资料按相对路径单独读取。资源可随 ZIP 保存，但模型当前仅能读取 UTF-8 文本，不能执行包内脚本。

持久化使用私有目录中的不可变修订和原子替换索引；导入失败不会影响现有 Skill。单文件 64 KiB、单包 2 MiB、最多 128 个文件、最多 64 个 Skill；拒绝目录穿越、绝对/隐藏路径、重复 ZIP 文件、无效 UTF-8 入口、异常 YAML 标签、重复元数据键与 YAML 引用扩张。使用 SnakeYAML 2.5 SafeConstructor 解析真实 YAML（包括多行简介），依赖版本和校验和已锁定。

流式展示使用 Pi 1.2.0 已解包的 `AssistantMessageEvent`，分别处理 text、thinking 和 tool-call 更新；保留内容块标识，思考与回答分开存储。UI 聚合思考增量，将同一 toolCallId 的准备/参数/执行/结果合并为一张工具卡。参数与嵌套结果按字段展示，JSON 文本结果先解析再显示，丢弃逐条 token 的事件噪声。副作用未知、失败与需要确认的操作仍可直接看到。所有外部内容以文本节点渲染；不创建脚本、远程图片或 HTML。超过预算的非完整结构化结果显示说明，不把残缺 JSON 当作正文。

## 验证与边界

- 完整软件校验已通过，最终共 57 项 JavaScript、177 项 JVM 测试通过；最终增量校验覆盖 Runtime、DOM、Android 单测、Lint、debug/release 与测试 APK。
- 自动测试覆盖两种模型协议的思考解包、正文流、工具卡聚合，Skill 读取前后请求内容隔离、过大目录预算，YAML/ZIP 校验、原子更新与版本冲突，ASR 二进制/Gzip/末包、旧新鉴权，以及本地 WebSocket 的临时结果纠正、松手单次发送、取消和断线。
- Thor 实机验收测试使用现有安装，无模型 fixture 重置：ZIP 导入、独立读取正文/引用文件、停用后重启保留、Native WebView 展示、首次麦克风权限弹窗和授权后界面存活、实际麦克风 6400 字节采集均通过。原消息数、模型设置和加密凭据不变，临时验收 Skill 已删除，未上传音频。
- 0.3.0 首次交付时尚无火山凭据、设备 Wi-Fi 断开；该轮证据保留原结论。2026-10-15 的 0.3.1 已在配置凭据后完成火山真实 ASR、离线火山 TTS 样本及真机麦克风到真实 Agent 的验收，详见 [云端语音验收](cloud-voice-acceptance.md)。固定样本结果不代表任意真人语音的准确率。

参考：[火山大模型流式语音识别 API](https://docs.volcengine.com/docs/DoubaoVoice/LargemodelstreamingautomaticspeechrecognitionAPI?lang=zh)、[Agent Skills 规范](https://agentskills.io/specification)、[Pi 的 Skill 发现与目录生成实现](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/src/core/skills.ts)。内置创建指南按本地 [内置 skill-creator 技能](../app/src/main/assets/skills/skill-creator/SKILL.md) 的渐进披露原则编写。
