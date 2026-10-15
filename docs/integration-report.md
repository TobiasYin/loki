# Thor Agent 中文软件集成报告 — 2026-10-13

> 历史报告：本文记录 10 月 13 日实机测试之前的软件整合。随后已在用户连接的 Thor 上执行测试并修复问题；当前实测范围、失败尝试和未验项目见 [2026-10-15 实机测试报告](physical-test-report.md)。本文当时“未接触实体设备 / 外部账户未验”等表述不代表后续状态。

## 结论与门槛

**实机前的软件整合已达到进入 Thor 验收的门槛；完整四能力产品尚未达到发布门槛。** 五个精确来源提交的历史已保留，正常 APK 已连接真实 Pi、Native Fetch、唯一原生授权、持久化、审批和正式设备适配。软件测试服务仅替代远端模型/搜索，不替代产品端口。没有安装或控制任何实体 Thor/其他实体设备，没有推送、发布、自动监控或向其他聊天发消息。

本报告与保存证据明确区分：来源任务旧证据、本轮实际软件通过、外部账户/环境未验、Thor 物理资格未验。不把模块“完成”文字或 Receiver 的模型声明当作通过证据。

## 来源与历史保留

共同基线 `c516d8b99d023a9e9fbac91098bae7fb6a2da0d4`。开始时 main 与五个来源工作区均干净，HEAD 与委托输入一致；使用精确 SHA、`git merge --no-ff` 保留历史，包含两个 detached 成果。

| 来源 | 精确提交 | 工作区 |
|---|---|---|
| Input Lab | `d33e66732a3b2128d891bcbe9dae417be1001f48` | `.codex/worktrees/6516/thor-agent` |
| Pi Runtime | `7adc961360b321c9062439861941a8d3df67a715` | `.codex/worktrees/80ad/thor-agent` |
| Native Fetch/授权 | `a4258cf3d2b58c0554eecb6d40cf69fa0e20f716` | `.codex/worktrees/1ca5/thor-agent` |
| 设备 ToolPack | `163bba07f5709ab211f70d4dd891778072e59518` | `.codex/worktrees/9de8/thor-agent` |
| Android 宿主 | `ce069669c96affd16613445fbc825e83bdbb7551` | `.codex/worktrees/4857/thor-agent` |

本轮产品代码验收提交为 `75aed94a91d2563d8ef526a42981998fb5461696`；该提交后的交付变更只添加文档/证据，产品源代码不变。

本轮先在 `feat/software-integration` 整合，完成验证后实际合并回本地主检出的 main。没有 reset、丢弃来源改动或删除来源 worktree。原模块 `verification/validation/evidence` 是历史记录，本轮结果保存在 `tests/integration-evidence/`。

## 实现判断与修复

五个输入作为独立工程有实质实现，但原先不能称为完整产品：App 使用模拟计划，Runtime/桥协议、页面路径和时基不一致，三个授权状态彼此独立，复杂 schema、配置探测、审批与设备输出尚未接通。已直接修复这些问题。

| 问题 | 本轮最终行为与证据 |
|---|---|
| 五个孤岛工程 | 根 Gradle 编译 App、Native Core/Android、设备 Core/Android library、独立 Lab 和两个诊断宿主；App preBuild 打包真实 Pi browser bundle，无 Node/外部模块导入 |
| 模拟 RuntimePort.plan | 正常 MainActivity 使用 ProductHost 与真实 ThorRuntime/Pi 增量流；旧模拟 Coordinator/ManualTestAuthority 仅保留在旧测试/独立诊断路径 |
| 多重授权事实源 | Native Authority 唯一签发 Session/Run/connection/lease/epoch；DeviceAuthority 委托验证、发布、去重和短提交。每次持久化同步 Native 授权镜像；资源撤销也保存 epoch |
| 信封、页面、时钟差异 | Runtime 内部 v0.1 转成 Native wire v1；固定可信页面 `/assets/chat/index.html`，origin/main frame/view/page/connection 共同验证。Fetch 使用 Native 签发的 elapsedRealtime deadline；工具墙钟剩余时间转换并夹紧到租约，观测审计单独用墙钟 |
| scalar 网关与复杂参数 | 产品使用真实 tools.Gateway + DeviceAuthority + bundled InputSchemas；region、points、路径等在 Runtime/Native 双重校验，版本、声明资源、ID 均拒绝非法值。旧 scalar ToolGateway 只用于桥诊断，不绕过校验 |
| Key 与 SDK placeholder 不兼容 | 验证 SDK 占位头后移除，Native 按不可变 profile/credentialRef/精确端点安装通用 `{{credential}}` 模板；正确处理 Request 的 header 合并。真实秘密只由 Native 控件/Keystore/传输持有，不作为 API 返回给 JS |
| 配置测试可由字段假通过 | 保存先使修订探测失效，再写 Key/资料；Native 私有 token 才能签发 configuration_test Run。要求当前修订、实际成功 HTTP 完成、Native 图像工具/图像读取和完整 Runtime 结果；UI 无 setPassed 方法。合成探测不依赖屏幕权限，普通 Run 不发布探测工具、不回放探测历史 |
| 审批只在诊断内存 | Native 保存绑定 Run/调用/epoch 的具体命令或 path/old hash/new hash/完整差异，批准记录提交后才放行；拒绝、过期、停止、hash 冲突均不能执行。没有模型参数 approved 捷径 |
| Shell 提交与等待混合 | AIDL 拆分短 startShell 与 awaitShell，短提交位于 Native 原子授权检查内，等待/输出位于锁外。UserService 检查 shell UID、caller、token、deadline、owner binder；实际 AVD 执行 UID 2000，非 Root |
| 数据和生命周期 | Native SQLite 保存领域 Message/Run/Event/Turn/ContextSnapshot/附件。intent 在执行前提交，state/event 事务应答失败立即停止。创建 Run 时也保存未完成用户回合；恢复标 interrupted，不重放。旧宿主关闭存储后新宿主才接管，避免旧全量快照覆盖新状态 |
| UI 过早恢复发送 | Native Run 收尾前仍保留 activeRunId，阻止下一发送；awaiting_user 连接真实批准/拒绝。过期/撤销附件返回不可用；renderer 退出后 Native 提示并提供恢复按钮 |
| 截图后 frame_pixels 总被拒 | 保留仍有效的截图快照身份，只有失效/过期/目标变化时更换；显示监听和提交前显示事实复核阻止旧坐标。实际 JPEG 截图→图片工具回传→frame_pixels 触摸→独立 Lab 判定通过 |
| 搜索/网页未闭环 | Brave 独立凭据/目的地；无配置不发布搜索。公开 HTTPS 网页读取走 Native DNS/逐跳重定向/私网过滤。真实 Brave 账户仍未验，不捏造搜索结果 |
| Lab 缺受信控制与关联 | debug-only signature provider 限定 caller UID/包、版本、lease/时间窗；describe 仅给几何，report 必须有效 lease。不能写 pass/注入事件，不在模型工具集合。报告关联 Native Run/发送轨迹，原始事件仍只由 Android dispatch 产生 |

只读/执行结果保留 code、source、UTC 观测、truncated、outcome 和备份/原生事件 evidenceRef。确认未提交的网关拒绝不标未知副作用；提交后的取消仍保守标 unknown。输出和模型错误不保存原始服务错误体/鉴权 header。没有把“系统回调完成”直接当作游戏效果成功。

## 构建与接口决策

- JDK 17；本机 Node 23.7.0，入口要求 Node ≥22.19。Gradle 8.14.3、AGP 8.10.1、Kotlin 2.2.10、compile/target 35、产品 minSdk 30、WebKit 1.14.0；Pi agent-core/pi-ai 1.2.0、TypeScript 6.0.2、TypeBox 1.3.27。
- 根 dependency locks + SHA-256 verification metadata，npm integrity locks；不使用动态版本。Native Android library只打包 Fetch adapter，不把 JS 测试/npm 目录放入 APK。
- 正式 APK `dev.thor.agent`；Lab `dev.thor.inputlab`，独立 UID/进程。保留 diagnostics/bridge-host debug APK。正式 App 的旧独立模拟构建入口明确要求改用根工程。
- 正式页面本地 HTTPS + CSP `connect-src/frame-src none` 与资源拦截，生产禁 WebView 调试及 file/content 访问。产品当前走受限 base64 流；binary framing 仍保留契约测试，不宣称已测 Thor 的复制/内存峰值。
- Native transport 不解析 Responses/Anthropic JSON/SSE；协议、UTF-8、图像与工具消息由锁定 Pi/JS 处理。模型无自动重试/副作用重放。
- release 无 HTTP localhost 例外、Lab 控制权限或 runner provider；unsigned release尚未发布签名。debug 的明文例外仅 `127.0.0.1`，仍须 Native 显式 profile/目的地授权。
- 上下文预算是用户可填的本地保护值，不冒充模型真实窗口/精确 tokenizer/价格。ContextSnapshot 保存来源与有效集合，不保存带 Key 的 provider payload；Native 校验 manifest 的有效工具集合。

## 实际验证与复现

### 关键基线

分别复现 app 单元/构建/lint、Native Core/Android/host 构建、设备 Core/diagnostic 构建、Lab 单元/构建/lint，Runtime 29 项与 Fetch 12 项测试。基线日志为 `build/integration/baseline-*.log`；这些不能替代最终产品验证。

### 最终软件验证

验收源码、APK 校验值、设备/WebView 指纹与证据哈希以 [verification.json](../tests/integration-evidence/verification.json) 为准。保存的日志不是来源任务的旧日志。

| 验证 | 实际结果 |
|---|---|
| 全项目静态/锁定依赖/构建 | 根 verify 成功；Runtime 与整合适配 TypeScript、DOM、各 Android lint、debug/unsigned release、两个诊断宿主、manifest/权限/assets 检查通过 |
| JVM | Native 32、设备 31、正式授权适配 7、旧 App Coordinator 16、Lab 44，共 130；0 failure/error/skip。旧 Coordinator 的 16 项不冒充 ProductHost 测试 |
| JS | Runtime 30、Fetch 12、DOM 7，共 49；包含协议、UTF-8、HTTP 错误/不重试、取消/迟到、去重、来源过滤、压缩、过期图片、存储失败等行为断言 |
| 授权竞态 | Native Authority + DeviceAuthority 的排队后最终提交边界重复 100 次，off/on 后零副作用；其他边界由针对性契约测试覆盖，不冒充所有 Thor 竞态各 100 次 |
| Android 产品集成 | 10 项实际通过：两协议图片/工具回传、真实设备工具、Brave 受控搜索、429/取消、Native 存储/恢复/失败、renderer 退出、具体文件审批/冲突、Origin/CSP/直连拒绝、真正 Input Lab 系统分发、真实截图与 frame_pixels、真实 Shizuku Shell 批准/拒绝/取消 |
| 接收器平台解析 | 4 项 fixture_replay 检查通过；明确不是系统输入或物理资格 |
| 接收器证据 | display_logical 与 frame_pixels 的 LAB-02 均由独立接收器记录完整 DOWN/UP 与命中，判 pass；原始 sequence 连续、无 drop，冻结 prefix hash 在导出脱敏前校验 |
| APK 检查 | 独立包、无 shared UID；Agent INTERNET、Accessibility/Shizuku权限；Lab无网络；release 无测试 IPC/控制权限/明文配置，无外部 runtime imports/node_modules/JS tests |

环境是本任务明确选定的 `software_integration` AVD、`emulator-5680`、Android 16/API36 arm64、WebView `com.google.android.webview 133.0.6943.137`。实际 overlay display 3 为 Lab，display 0 为聊天；这是虚拟显示的软件路径，不是 Thor 双屏资格。所有本任务 ADB 操作均带 `-s emulator-5680`，runner 检查 `ro.kernel.qemu=1` 后才安装或操作。

官方 Shizuku `13.6.0.r1086.2650830c` 只安装在该 AVD；下载 APK SHA-256 `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f`，签名校验结果保存。Native 权限实际由 Shizuku 授权对话框批准，Android pm grant 不被当作 Shizuku 服务授权成功。服务实际 UID 2000，UserService执行和取消实际跑过。

```sh
export JAVA_HOME=path/to/jdk-17
export ANDROID_HOME=path/to/android-sdk
npm run install:deps
npm run verify
# 显式 AVD 已启动；使用 dumpsys display 返回的真实 overlay ID。
python3 scripts/emulator-verify.py --serial emulator-5680 --lab-display 3
# 如已按官方流程安装/启动/授权 Shizuku，要求真实 Shell 子项不得跳过：
python3 scripts/emulator-verify.py --serial emulator-5680 --lab-display 3 --require-shizuku
python3 scripts/export-evidence.py
```

本机本轮最后运行使用 `--fixture-running` 复用本任务已启动的本地服务；一般复现省略该参数，由 runner 启动/关闭服务。受控服务在 `127.0.0.1:8877`，以 3 字节块切中文 SSE，核验真正 Native 替换的测试凭据与两协议图片/工具 payload。没有真实模型/Brave Key，因此仅证明锁定协议的软件链，不宣称外部服务语义识图/配额可用。

`tests/integration-evidence/` 保存通过日志、Native 调用、Lab 报告/raw、截图、APK 校验与来源 SHA。descriptor 对外散列；receiver prefix hash指私有原文，导出清单的 hash指脱敏证据，不能混用。model-requests 日志包含本任务调试阶段的请求，最终通过结论以保存的产品 suite 和 sourceCommit为准。原始失败均已修复，最终 suite 不带 skip（要求 Shizuku 的本次运行）。

## 剩余限制与实机清单

| 分类 | 尚未完成/明确禁用 | 验收动作 |
|---|---|---|
| Thor 物理设备 | 型号/固件/真实上下屏、下屏启动与 IME、焦点、旋转、休眠及目标切换 | POC-01/02/06：记录显示/窗口/权限，两个屏幕/实际游戏正反例 |
| 原生手柄后端 | press_button/set_axes 为 unsupported；没有键盘或触摸冒充 gamepad | POC-07/11：设备身份/source、全部轴/按钮、组合、释放、服务断开；两个真实模拟器 |
| 私有配置权限 | 结构化写仅合成目录；Shizuku共享诊断根仅 read/list；未开放其他应用私有目录/SAF 事务 | POC-08：两目标模拟器路径、UID/SELinux、真实备份写恢复、并发/断电/编码/SAF授权；不可达则保持不可用 |
| 真实模型/搜索 | 无外部账户/Key，不从 localhost 成功推断所有兼容服务 | 两协议真实图片输入/工具图片回传、中文英文搜索、鉴权/限额/redirect/断流 |
| 物理 Receiver 资格 | qualification 始终 `unqualified-physical-baseline-pending` | Thor实体手指/手柄正反例、轴范围、历史样本、漏样/重启/污染；通过前不认定 POC-11 |
| 性能/长期运行 | 未测游戏帧率、电量、峰值图片内存、长历史 Native性能；只保证前台 | POC-10 200消息/50会话完整数据、游戏并行、后台/renderer/进程退出及功耗 |
| Shell 取消 | 直接子进程处理，后代/已发生外部变更可能未知；不是任意 Shell 沙箱 | 实机独立服务死亡、进程树、租约/释放/重连；unknown 不伪报清理完成 |
| 扩展/分发 | 语义摘要/精确 tokenizer、MCP、完整数据导出/保留、真实配置导入、发布签名未做 | 独立需求/ADR 和后续验收；本轮不发布 |

完整 REQ-11 与相关 POC 未通过，不能发布为完整四能力首版；本轮没有删去这些要求，也没有把 unsupported改成成功。当前软件实现可用于受控 Thor 验收，实机安装和后续监控不属于本次结束动作。

## 官方接口核对

锁定依赖源码与实际构建/运行共同作为依据；官方 API 的存在不等于 Thor 可行性证明。

- [Pi 官方仓库](https://github.com/earendil-works/pi)：使用锁定 npm 1.2.0 入口，并检查 SDK Fetch/header/provider paths，不按当前 main 猜锁定版本行为。
- [WebViewCompat](https://developer.android.com/reference/androidx/webkit/WebViewCompat)：origin/main frame/消息桥/feature probe，与真实 WebView证据对照。
- [SystemClock](https://developer.android.com/reference/android/os/SystemClock)：uptime/elapsedRealtime/墙钟分开。
- [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)：指定显示手势与截图实际调用。
- [Brave Web Search](https://api-dashboard.search.brave.com/app/documentation/web-search/get-started)：独立 token与结果字段；仅实现相应固定适配，不假造服务可用性。
- [Shizuku release](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0)、[官方启动指南](https://shizuku.rikka.app/guide/setup/)：只在选定模拟器安装和启动，实际授权/UID 另外验证。

## 冻结的验收 APK

验收 APK 已复制到 `build/integration/deliverables/`，由下表给出确切字节校验值；对应上述产品验收源码。根工程的常规 outputs 也保留。AGP 的 release VCS metadata 会写入当前 Git revision，因此最终 main 再构建的 unsigned release 校验值可能变化；当次真实结果始终由 `build/integration/artifacts.json` 给出，不能把仅由文档/merge SHA 导致的变化误当代码未经验证。

| 产物 | 路径 | 字节 | SHA-256 |
|---|---|---:|---|
| agent-debug | `build/integration/deliverables/agent-debug.apk` | 6098153 | `b10014a30593b9cc6df0d81fc8347e7404b9a49c7b0d878f138fe1fdf4b6cab7` |
| agent-release-unsigned | `build/integration/deliverables/agent-release-unsigned.apk` | 4565696 | `eb27b36ac9778e2215ef05a1cc750263259e383ab1f16f8c3e55110e34199cec` |
| input-lab-debug | `build/integration/deliverables/input-lab-debug.apk` | 2719211 | `2a77c1b33e112bac17b2d033bae9ba1ad7d5ae3337eb1069ea7ea3e6d0569bc3` |
| input-lab-release-unsigned | `build/integration/deliverables/input-lab-release-unsigned.apk` | 2206512 | `2901d36b765646ba0b080272cc5684c544bb4a9713ab86fc95e44b4ded0e470b` |
