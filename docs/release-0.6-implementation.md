# 0.6.0 实现说明与验收边界

本文件说明本轮八项需求的实现、软件回归和已知限制。最终主 APK／测试 APK 指纹、固件、真机结果及失败重跑见 [验收记录](release-0.6-acceptance.md) 和 [可提交证据](../tests/release-0.6-evidence/README.md)。 0.4～0.5.1 的历史报告保留原版本、时效和资格，不覆盖为本轮结果。

## 八项需求对应

| 需求 | 本轮实现 | 主要位置 |
| --- | --- | --- |
| 1. 上屏 APP 信息、截图和界面读取 | 启动时发现 Thor 上下屏；恢复用户保存的角色提示，不复用旧数字 ID 或旧输入观察。`app_info/window_info/ui_tree/capture_screen` 统一建立新观察；已观察到的包即使版本元数据不可见，也能返回包名及不可见原因。只读工具不因模型等待超过旧观察时效而永久卡住 | `DisplayRolePolicy`、`DisplayRoles`、`ProductHost`、`Gateway`、`AndroidBackend` |
| 2. Markdown 与简洁回答 | Agent 正文支持标题、列表、强调、引用、代码块、表格和链接；本地打包 marked 15.0.12、DOMPurify 3.3.1 和许可证。原始 HTML 按文本显示，Markdown 图片不自动联网加载，HTTP(S) 链接交 Native 打开。System Prompt 要求先给结论、简洁清晰、不输出原始协议／事件 JSON | `chat/vendor`、`ui.js`、`runtime/src/context.ts` |
| 3. 按住说话 UI | 默认主行“按住说话”及底部控制；按住显示蓝色渐变面板。取消阈值为 `max(120, min(240, 视口高度×0.4))` CSS px；上滑仅标记，松手才取消，滑回仍发送。按钮节点与位置不移动；成功后恢复默认，不显示识别文本。失败可主动恢复文字草稿 | `index.html`、`style.css`、`ui.js` |
| 4. 任务启动慢 | 修复已验证会话仍等待其他模型探测的全局队列；仅未验证配置排队，并在排队后重查，避免重复探测。准备阶段在输入框内可见、可停止。会话元数据采用独立事务，流式运行增量保存；事件索引、一次性标题回填及 keyed DOM 减少历史扫描和重建。记录 Native 准备、响应头、首字节、首正文时序，区分本地与服务等待 | `StartupCoordinator`、`ProductHost`、`Storage`、`EventIndex`、`ChatSnapshot`、`ui.js` |
| 5. 看屏点击与手柄控制 | 保留按显示逻辑坐标／frame_pixels 的触控和坐标变换；手柄按钮和轴都使用 shell UID 2000 下限定 Odin Controller 的固定 evdev 后端，核验范围、中立值、设备身份和真实焦点；每次持有最长 5000 ms，结束释放／归中。操作说明按需挂载至内置 adb Skill | `AndroidBackend`、`NativeGamepad`、`EvdevGamepad`、`GamepadLeaseGuard`、`references/thor-gamepad.md` |
| 6. 默认下屏启动 | 独立 `LauncherActivity` 在构建 WebView／打开存储前解析下屏，以 `ActivityOptions` 指定显示。正常桌面入口使用该路由；未知设备／无法确认映射时不猜下屏 | `AndroidManifest.xml`、`LauncherActivity`、`DisplayRolePolicy` |
| 7. 高效话题列表 | 首次普通用户消息生成前 10 个 Unicode 码点标题，合并空白、不截断 emoji；回填原空白／“新会话”标题，保留自定义标题。列表默认仅标题；长按、右键或 Shift+F10 打开重命名／归档／删除菜单，删除再确认；滚动取消长按 | `PromptTitle`、`ProductHost`、`ui.js` |
| 8. 设置效率与连接测试 | Native 全页分区设置，只构建当前分区；模型／搜索草稿支持测试，空 Key 可复用保存值，测试不会自动保存。Go 模型资料提供模型、协议、上下文及思考强度，刷新失败保留缓存；自定义服务可手填。高级字段折叠；模型状态使用轻量缓存，不周期读取完整会话历史 | `MainActivity`、`ModelConnectionTest`、`SearchConnectionTest`、`ModelCatalog` |

## 状态与资源语义

- 普通模型任务仍无总运行时间上限，失败／取消后的上下文和后续追问继续沿用 Pi 会话机制；切换会话不停止后台会话任务。页面离开前台的生命周期中断行为没有改造成后台常驻服务。
- `Snapshots.OBSERVATION_TTL_MS=60_000` 是观察资源的有效期，**不是模型任务时限**；单次工具 deadline 没有延长。新输入、图像和 UI 树仍检查应用、窗口、布局、角色／epoch；过期 frame 和旧输入 ID 不自动改绑。读取可以建立新 ID，下一轮模型请求才采用它。
- 对已经提交的 bounded 手柄持有，独立 `layoutRevision` 区分窗口结构和自身引发的内容回调。持有期间允许纯内容反馈；换包、窗口／焦点／根边界变化、epoch、到期或撤权仍停止并清理。**新的输入仍使用严格完整 revision**，不能用旧观察继续操作。
- 子 Checkpoint 的部分提交标记立即传播到父级：远端可能已经写入、随后失败时返回 `unknown`；明确没有提交的失败才返回 `not_submitted`。
- 会话元数据更新使用父行 UPDATE，不替换父行、不级联删除历史。事件仍以完整持久记录保留，界面只投影聚合结果；事件索引返回隔离列表，避免读线程拿到可变内部列表。

## 已运行的软件检查

本次独立复跑 `cd app && npm test`：**33／33 通过**，包含 29 项界面回归、3 项启动探测协调、1 项网页正文提取。覆盖 Markdown／危险 HTML 与链接、200 条历史／50 个话题、流式旧 DOM 复用、长按删除确认、准备停止、语音长滑标记／回滑提交／释放取消、捕获丢失及重复终态去重。它们是 JS 组件测试，不是真实 ASR 或游戏证据。

真实 Chrome 的 `scripts/ui-layout-check.mjs`、`scripts/chat-interaction-check.mjs` 已在本轮运行通过：620×540、360×640、620×180，无横向溢出，操作按钮至少 44 px；前两布局包含完整长滑／回滑路径，按住前后目标矩形一致。180 px 视口无法容纳 120 px 上滑路径，未把该布局记成完整取消通过。截图／结果在忽略的 `build/ui-preview/`。

补充状态回归：`SessionUpdateStorageTest` 在独立模拟器随机临时库 **3／3 通过**，验证反复元数据保存保留历史、附件、外键与 rowid，并验证后续 JSON 行异常、metadata 写入异常的整笔回滚。索引隔离测试先复现旧快照随追加变化的问题，修复后通过。设备 core 的 60 秒边界、16 秒推理后静态点击、fresh read、持有结构校验及部分提交错误归因测试均已通过；全仓最终数量、构建和 lint 以主流程最终 verify 为准。

`PhysicalVoiceGestureTest` 使用真实平台触摸和产品 WebView，但拦截语音及发送、模拟 ASR 状态，报告必须标注 UI 组件归因；`PhysicalCloudVoiceTest` 才验证真实麦克风／火山网络识别。前者修正了 helper 启动及读流清理，旧卡住记录保留；最终真实手势及麦克风／ASR 两类证据分别通过，见验收记录。

## 已知限制与合并前核对

1. 设置中的“测试当前填写的连接”只验证真实文本流；图片与工具闭环要检查已保存配置。首次未验证配置仍需探测，服务端排队／推理延迟不会因本地优化消失。连接测试有独立等待预算，不代表普通模型任务被设置时间上限。首次打开仍需读取持久历史，选中会话首屏未做分页／虚拟列表，超长单会话还需独立压测。
2. 上屏窗口源于 Accessibility，不是完整系统任务列表；Activity 无可靠来源时为 null，包版本受 Android 可见性限制，自绘 Surface UI 树可能为空。安全窗口不绕过，截图失败不自动改用 Shell。
3. 默认映射针对 Thor 已知拓扑；保存提示基于当前唯一显示名称，不能证明同名外接屏的硬件身份。已保存映射缺失／歧义时拒绝默认回退。最终 APK 已通过普通桌面冷启动下屏检查；同名外接屏的硬件身份仍属限制。
4. 手柄工具返回 submitted／released 不等于游戏接受；六轴后端遵循真实控制器焦点，不自动修改焦点锁，不接受任意节点／轴码。服务计时器能执行有界清理，但服务本身被 SIGKILL 没有内核保证的自动归零。最终独立接收端已核对按键 down/up、source/deviceId、六轴偏转、中立及中途停止；完整游戏／固件组合矩阵仍未覆盖。
5. 波形是界面装饰动画，未宣称跟随实际麦克风振幅；Markdown 未提供远程图片加载、数学公式或代码语法高亮。真机长滑 UI、真实麦克风和网络识别是不同证据，不能互相替代。
6. 本轮没有解决历史 120 Hz 实际生效限制，也没有完成完整 POC-11／物理基线资格。不得因为单项接收报告 pass 将 `receiverQualificationVersion` 或 `systemInjectionProven` 提升为完整证明。

## 最终结果

252 项 JVM 测试、92 项 JS 测试、构建与 lint 均通过。真实模型的四轮上屏输入、模型／搜索草稿、语音手势、物理麦克风／ASR、四轮严格手柄组件和下屏冷启动均通过。目标越界、过期、切换应用三个负向守卫按预期拒绝且原始输入为零。历史消息、事件、附件与原有 Run 字节内容保持，保存的配置／凭据及用户导入的 Skill 保持；自动标题回填属于本轮有意迁移。具体版本、时序、资格边界和完整指纹以验收记录为准。
