# 本地合成多步 UI 演练

Input Lab 0.4.0（versionCode 5）在原始 Lab 之外增加 `ScenarioActivity`，不改变 LAB case 的接收资格或历史报告。所有新闻、配置、口令均由本地 seed 合成；没有模型、网络、Shizuku 或输入注入依赖。设备系统亮度、音量等真实配置不会被本场景修改。

## 手动使用入口

从原始 Input Lab 非录制的操作行点“多步演练”，即可选择设置/游戏/新闻/长文。每次选择或“新的一组”都使用随机 seed 和新的独立 attempt；正常任务弹窗只显示公开目标，“查看任务”可随时重看。手动模式的“选择场景”或返回键会封存原 attempt，再回选择首页；原有日志不覆盖。release APK 同样提供该入口和全部交互页面，不依赖调试 runner。

手动报告固定 `evidenceMode="manual_synthetic"`，`agentRunId=""`，`testRunId="manual-<UUID>"`，`senderTraceRef="manual-scene-selector"`，不得冒称 `agent_e2e`。debug `prepare` 可以替换手动页面，替换前封存手动 attempt，给真实 Agent 新建独立目录；Agent 绑定场景隐藏全部手动重置/场景切换控件，正在执行的自动 lease 不能被新的 prepare 覆盖。原 Lab 入口与既有按钮在同一行，不增加观测区域保留高度，Arm 后隐藏且再次守卫非录制状态。

## 场景与独立接收判定

| scene | 正常用户目标 | 接收端必须观测到的结果 |
|---|---|---|
| `settings` | 从首页打开设备偏好，选择模式、切换夜间阅读、拖动音量到目标百分比，保存并查看结果 | 实际进入子页；三项保存值与任务一致；SeekBar 自己的触摸序列包含至少 2 次 MOVE、24 dp 位移及至少 2 次 `fromUser` 进度变化；保存按钮回调成功；独立 `saved-settings.json` 落盘 |
| `game` | 根据每关画面提示，选择颜色、形状、编号全部符合的目标，连过两关，回答通关口令 | Canvas 接收到每个目标上的匹配 DOWN/UP，按序完成两关；累计误触必须为 0。六个候选含干扰项，顺序随 seed 变化 |
| `news` | 在列表中找到指定新闻，实际滚动并打开详情，回答核对码 | 28 条原生 ScrollView 按钮；目标在索引 20–25，行高至少屏高的 1/8，首屏不可见；列表出现正滚动、实际打开目标条目。独立测试再核对模型答案 |
| `article` | 打开专栏，读开头码，再实际滚到文章末尾，回答两处核对码 | 长文详情的真实正滚动、滚动回调及文末控件与窗口的可见区域交集。仅 UI 树枚举到离屏末尾不算到达；独立测试再核对双码 |

新闻和长文保留完整原生 ScrollView 子树，各新闻条目有唯一静态资源 ID；这是完整树场景，不假称 RecyclerView 虚拟化。游戏只提供通用 Canvas 节点，目标提示为正常文本，候选需要画面辨识。模型任务描述不提供坐标、内部答案、seed、runner 指令或固定点击路径。

设置目标音量为 40/50/60/70%，步进 10%，当前值持续可见。点击滑块直接跳到值不足以符合“拖动”要求；原生 `SeekBar` 的真实触摸和进度回调须同时满足。语义点击/滚动可以改变正常 View 状态，但日志保留其与原始 `android_dispatch` 触摸记录的区别。

## Debug runner API

只有 debug APK 注册 `ScenarioRunnerProvider`：

- URI：`content://dev.thor.inputlab.scenario`。
- 权限：`dev.thor.inputlab.permission.CONTROL`，protectionLevel=`signature`。
- 额外调用包允许列表：`dev.thor.agent`、`dev.thor.agent.test`；shell 或其他包即使知道 URI 也不能调用。
- 所有请求以 `ContentResolver.call(uri, method, null, extras)` 发送；所有响应的 JSON 均位于 `Bundle.getString("snapshot")`。
- 请求 `version` 必须为 Int 1；`seed` 必须为 Long；scene 必须为 `settings/game/news/article`。

| method | 请求 extras | 行为 |
|---|---|---|
| `describe` | `version, scene, seed` | 纯读生成 `{scene, seed, taskPrompt}`；不创建 attempt、不打开/改变页面，不返回 expected |
| `prepare` | `version, scene, seed, testRunId, agentRunId, senderTraceRef`，可选 `evidenceMode` | 要求 ScenarioActivity 已在目标显示运行且没有未释放 lease；一次性创建绑定真实 Agent run 的 attempt，显示场景，返回 `lease` 与 snapshot |
| `report` | `version, lease, testRunId` | 要求同一调用 UID、lease 和 testRunId；同步当前原始事件前缀，读取状态/证据，不输入 |
| `release` / `finish` | `version, lease, testRunId` | 同绑定校验；追加封存事件并同步/关闭 journal，返回最终 snapshot，冻结页面并释放 lease。不修改游戏/设置值或模型答案 |

`testRunId`、`agentRunId` 为 1–128 字符，`senderTraceRef` 为 1–512 字符。证据模式默认 `agent_e2e`，其他模式沿用原 Lab 的 `RunnerEvidenceMode` 解析；声明模式和绑定 ID 不足以单独证明发送链路，仍须结合 Agent 工具日志。

推荐顺序：先正常将 `dev.thor.inputlab/.ScenarioActivity` 启动到已经确认的上屏；调用 `describe` 获得 taskPrompt；正常 Agent 会话 begin，取得真实 runId；再以相同 scene/seed 调用 `prepare`；仅把 taskPrompt 传给模型执行；模型结束后 `release` 封存并独立检查工具结果、接收结果与回答。禁止把 snapshot.expected、seed、固定坐标、runner API 或私有报告作为模型上下文。runner 本身不启动或代替 Agent，测试不得用 pendingRun 或重新 prepare 覆盖一次真实实验。

场景没有自动观察时限，不将模型推理耗时当失败条件；测试负责最终封存。配额为每 attempt 最多 20,000 事件和 8 MiB 原始日志；满额/写盘错误是 sticky incomplete，不能再产出 completed。Activity 的 keyboard/navigation 变更仅日志记录并保留当前 View；实际销毁会以 `activity_destroyed` 封存为未完成。进程被直接终止时未自动恢复场景，不能把遗留未封存日志当通过。

## 报告与原始日志

私有文件均为每次随机 UUID 的独立目录，runner 不接受任意文件路径：

```text
files/scenario-reports/<attemptId>/
  events.jsonl
  report.json
  saved-settings.json   # settings 保存成功后产生
```

顶层字段：`schemaVersion=1`、`attemptId`、`testRunId`、`agentRunId`、`senderTraceRef`、`evidenceMode`、`scene`、`seed`、`page`、`taskPrompt`、`state`、`expected`、`completed`、`interruptedReason`、`geometry`、`evidence`。`geometry` 记录实际 displayId、全屏像素宽高、rotation、densityDpi；不是人工确认或自动绑定上屏的证明。

`state` 字段：

- 设置：`settingsSaved`、`settingsValues={mode,enabled,volume}`、`settingsDraft`、`settingsDragCount`、`settingsSaveCount`、`settingNavigationCount`。
- 游戏：`gameStep`、`gameWrong`。
- 阅读：`openedArticleId`、`articleOpenCount`、`newsListScrollCount`、`detailScrollCount`、`listScrollY`、`detailScrollY`、`maxListScrollY`、`maxDetailScrollY`、`footerVisible`。

`expected` 仅供独立测试核对：`settingsValues`、`gameTargets`、`gameResult`、`newsTargetTitle`、`newsTargetId`、`newsCode`、`articleTitle`、`articleTopCode`、`articleBottomCode`。它不得传给模型。`completed` 只表示对应 UI 状态及完整日志符合本场景要求；报告固定 `agentAnswerVerified=false`、`systemInjectionProven=false`、`receiverQualificationVersion="scenario-ui-observation-v1"`，不升级原 Lab 接收器资格。

`evidence` 包含 `relativePath="scenario-reports/<UUID>/events.jsonl"`、`eventPrefixBytes`、`eventPrefixSha256`、`eventCount`、`incomplete`、`droppedSamples`、`sealed`。SHA-256 对应原始本地文件字节；封存后拒绝追加，未封存 report 只证明当时前缀。哈希用于完整性对照，不是签名认证。

每行 events.jsonl 公共字段为 `sequence`、`eventKind`、`recordOrigin`、`attemptId`、`testRunId`、`agentRunId`、`evidenceMode`、`uptimeMs`、`elapsedRealtimeMs`、`page`、`data`。主要事件：

- `prepared/scene_ready/navigation/click`：正常场景生命周期与控件回调。
- `touch`：Activity 真正 dispatch 的 actionMasked/source/deviceId/downTime/eventTime、窗口/原始坐标、历史样本数量、pointerCount。`recordOrigin="android_dispatch"`，用 dispatchToken 关联同一派发中的控件回调。
- `slider_touch/slider_progress/slider_drag_qualified/settings_save/settings_persisted`：SeekBar 自己收到的序列与原生 fromUser 进度变化，实际保存和持久化哈希。
- `game_pick`：真实 Canvas 手势命中项、正确与否、已完成关数。
- `scroll`：container=`list/detail`，实际新旧 scrollY、viewportHeight、contentHeight；`article_open` 记录打开条目；`footer_visible` 记录实际可见 bounds 和 scrollY。
- `window_focus/configuration_changed/key/sealed`：焦点、输入配置、按键及最终封存；UI 回调 origin=`receiver_ui` 不冒称原始输入。

原始触摸来源/UID/设备字段无法证明操作者身份或系统注入成功。验收须同时确认正常工具发送结果、接收端真实状态和事件、答案与 expected 相符，且前缀哈希一致、无 incomplete/drop。原始 Lab 的物理基线、手柄资格、窗口冻结与负例仍使用原页面和原报告。

## 本地软件验证

根目录执行：

```sh
./gradlew :input-lab:testDebugUnitTest :input-lab:assembleDebug :input-lab:assembleRelease :input-lab:lintDebug
```

新增 5 项纯状态机回归覆盖 seed 可复现与变化、正确配置仍须拖动和保存、游戏误触不可补救、列表/长文必须真实滚动、非法状态迁移拒绝；新增 5 项独立文件日志回归覆盖封存后不可追加与哈希、配额失败不伪通过、精确字节配额、真实保存文件与中断报告，以及手动来源不伪装 Agent 且多次选择各有独立日志。原有 64 项 JVM 用例保持，本轮共 74 项全部通过，debug/release 构建与 lint 通过。软件测试与 APK 构建不等于真实模型已完成四个场景；实机结果与各场景的独立日志见 [0.7 验收说明](../../docs/release-0.7-scenarios.md)。
