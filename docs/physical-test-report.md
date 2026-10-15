# Thor 真机测试记录 · 2026-10-13 至 2026-10-15

本轮在用户连接的 AYN Thor 上执行真实测试，修复了 OpenCode Go 会话路由、下屏输入法遮挡、接收端对固件设备重配置通知的误判、截图资源时效和全屏触摸区域边界误判，并加入已有 Shizuku 授权时自动开启本应用无障碍的流程。真实模型、Brave、完整输入法、实体输入接收基线，以及真实云模型的逻辑坐标/截图坐标触摸均已有通过证据。手柄注入仍未实现，完整故障矩阵尚未覆盖，不能把本轮子集当作完整四能力发布验收。

## 环境与证据边界

| 项目 | 本轮实际环境 |
|---|---|
| 设备 | AYN Thor，Android 13 / API 33 |
| 固件 | `Thor_V1.0.0.377_20260206_165408_user` |
| 上屏 | Android logical display 0，1920 × 1080 |
| 聊天下屏 | Android logical display 4，1240 × 1080 |
| WebView | `com.android.webview` 109.0.5414.123 |
| Shizuku | 管理器 13.6.0.r1086.2650830c；运行中服务 UI 标为 13.5 / adb；实际 UID 2000 |
| 模型 | 用户指定的 `deepseek-v4.1-flash`，OpenCode Go Responses |
| 接收应用 | 独立 UID / 进程的 `dev.thor.inputlab`；实体基线为 0.2.0 / scenario v2；后续全屏自动用例为 0.3.0 / scenario v3 |

保存证据在 [tests/physical-evidence](../tests/physical-evidence/)。报告前缀的字节长度、SHA-256、完整性及原始事件 sequence 均核对过；这些 hash 用于检测意外损坏，不是对恶意 shell 或系统的认证。物理基线由用户手指和实体手柄产生，采样期间没有 ADB 输入。自动化只通过正式产品工具施加被测输入；测试 provider 仅准备接收端和读取结果，不注入事件或写入判定。

产品源码冻结提交为 `3a8ad910c350f4d52408380529c4e1e2cf8dd1bf`。软件回归、`native-guards-final/` 和最终自动无障碍复验针对该冻结版本。云模型两条成功闭环记录来自功能修复完成、触摸区域元数据改名之前的构建阶段（字段名当时为 VisibleBounds，最终明确为 TouchableBounds）；不将旧阶段 APK hash 冒认为最终构建。冻结版本最后一次外网重复验收时设备 Wi-Fi 已断开、四个已保存网络均不在扫描结果中，模型和 Brave 独立检查都是 UnknownHostException，无 HTTP 响应、无模型工具执行，见 `network-loss-final-attempt/`。因此**最终 APK 的外网重复验收仍待网络恢复后重跑**；当前 Native 配置探测如实为 failed，凭据和历史保留，没有手动改回 passed。网络中断没有阻塞源码、离线保护测试或报告交付。

保留旧版 inconclusive、过短观察窗和目标变化拒绝等失败尝试。没有重写原始报告；receiver 的 `unqualified-physical-baseline-pending` 和 `routingProven=false` 也没有改成已资格认证。

## 已完成的真实服务与界面测试

| 测试 | 结果与实际证据 |
|---|---|
| OpenCode Go Responses | 通过真实 Pi → Native Fetch → HTTPS `/zen/go/v1/responses`；文字、图片输入、工具调用和图片工具回传完整探测通过；不以单个 HTTP 200 替代配置判定 |
| Native 设备工具 | 真实模型调用 device_info 并完成回答；见 `physical-live-services.json` |
| Brave 搜索 | 真实 Agent web.search 调用和 Brave HTTP 200 返回通过；凭据独立绑定搜索端点 |
| 下屏完整输入法 | display 4 的键盘稳定到 707 px，WebView 可用高度 162 CSS px，输入框和发送/停止按钮完整位于可见区；真实模型回复 `IME_OK`，Run completed；见 `ime-settled.json` 与 suite log |
| Shizuku 自动无障碍 | 连续两次从本应用服务关闭的状态启动，已有授权自动绑定并启用固定组件；确认实际 service connected，原有四个服务全部保留；新 Session 的 screen_control / debug 均为 false；见 `auto-accessibility/` |
| Shizuku 无授权分支 | 在管理器内仅关闭 Thor Agent 授权后，应用确认 permission_missing，无障碍列表不变、没有弹权限请求；授权随后恢复且 UI 读回验证；见 `accessibility-denied/`。只撤销 Android 权限位不会撤销管理器授权，早期准备失败不能算无授权测试 |
| Shizuku Shell | 正式产品审批、拒绝、停止路径已执行；真实 UserService UID 2000；见 `integrated-shell-result.json` 与 `runner-shell.log` |

OpenCode 基础 URL 为 `https://opencode.ai/zen/go/v1`。真实请求最初收到 `MissingSessionID`，已增加仅对该精确端点生效的 Native `x-opencode-session`：取当前授权 Session ID 并覆盖调用方同名头。密钥继续由 Native Keystore 持有，没有进入 JS、证据或提交。

Shizuku 自动无障碍是应用自身的设置操作，不是 Agent 任意 Shell 工具；它只接受固定操作，验证 shell UID、caller、owner 和短 lease，保留其他服务并读回校验。已启用但未连接时可只重连自身组件；状态区分设置已启用和实际连接。未授权时不自动弹出权限请求，保留手动入口。会话能力开关与 Shell 审批规则不变。

测试框架会强制替换被测应用进程；若恰好杀死正在绑定的服务，此固件可留下 Android `Binding services` 旧状态。自动化先关闭自身组件，再覆盖安装相同包以清除测试遗留绑定，然后由产品自己开启；没有清空数据库、模型资料或凭据。不能把测试框架清理当作应用具有修复 Android 内部任意绑定故障的能力。

## 输入重配置问题与实体基线

固件反复产生 `InputReader ... changes=DISPLAY_INFO` 和 input-device changed 回调，涉及上屏触摸 id 7、下屏触摸 id 6、虚拟鼠标 id 11。原实现把每个回调无条件判为设备重建，导致没有实际公开配置变化的实体输入也 inconclusive。

修复在 ARM 时冻结完整公开设备配置、设备集合、显示、窗口、焦点和生命周期。只有 affected device 没有 held input、配置和环境全部相同、且没有先前故障时，通知才作为 diagnostic_only 记录。实际 add/remove、未知设备、范围或窗口变化、affected device 持有输入时的通知仍 sticky inconclusive；不会合成 UP 或归零，也不会更新冻结配置来迁就观测。额外省略了没有可见状态变化的诊断重绘，原始记录照常追加；尚无证据将该重绘认定为发送端目标版本变化的根因。

Thor Odin Controller 的冻结元数据声明 axes `[0,1,11,14,23,22]`，对应两摇杆和 BRAKE/GAS 扳机；不声明通用 profile 的 17/18。v2 根据设备制造商、型号、唯一非虚拟 Odin Controller 的 vendor/product 和 advertised axes，在输入发生前选择该 profile。其他设备仍保留原通用 profile。

| 用户实体操作 | 接收结果 | 证据与限制 |
|---|---|---|
| 手指单击 START，LAB-02 | pass | 实际窗口 5 秒，完整 DOWN/UP、hits=1；`finger-v2/` |
| 实体 A 按下/松开，LAB-06 | pass | Odin Controller id 30，keyCode 96，source 含 GAMEPAD；3 次 diagnostic_only；`button-v2/` |
| 两摇杆/两扳机，首次 LAB-07 | inconclusive | 实际窗口仍为 5 秒，事件超过 deadline；保留 `axis-v2/`，不称通过 |
| 两摇杆/两扳机，60 秒重测 | pass | 六个必需轴均有足够 excursion 并最终回到 0；139 个原始输入事件、39 次 diagnostic_only；`axis-v2-retest/` |
| 完全不操作，LAB-14 | 预期 fail | raw=0、hits=0，诊断通知不能制造成功；`no-input-v2/` |
| 手指长按后松开，LAB-04-LONG | pass | 约 9.4 秒 hold；三次重配置都在松手后发生，**没有实测到按住期间的重配置负例**；`held-v2/` |

旧版三项实体报告仍在 `finger-baseline/`、`controller-button-baseline/`、`controller-axis-baseline/`。本轮证明接收端能识别这些物理输入及完全相同的空闲配置回调；不证明 Agent 已能注入真实 gamepad。

## 自动输入、目标资源修复与负例

最终真实 OpenCode Go 模型 → Pi → Native → Android dispatch → 独立 Lab 链路两项均通过，见 `cloud-input-success/`：逻辑坐标用例恰好一次 input.tap；截图用例恰好一次真实 JPEG 裁剪截图和一次 frame_pixels tap。两个 Run 均 completed、两份接收报告均 pass、每份只有完整一次 DOWN/UP 和一次目标命中。截图用例还核对了新的拍摄快照 ID 不等于请求前旧 ID，且下一轮模型上下文使用该拍摄 ID。

保留失败链路：`cloud-input-rejected/`、`cloud-input-statusbar/`、`cloud-input-fullscreen-first/`。这些尝试中 Native 在分发前拒绝，接收端 raw=0 / fail；它们不是成功输入证据。排查分出三个独立问题：

1. 普通窗口上屏 SystemUI window 11 每约两秒发送内容事件，使保守目标版本失效。v3 Lab 新增标准全屏模式，在 ARM 前冻结实际几何、insets 和栏可见性；观察中的实际 insets/布局变化仍 inconclusive。未忽略系统栏内容事件，普通窗口限制仍保留。
2. 截图曾继承模型请求开始前的 10 秒快照，消耗云模型往返时间。截图现在在相同已校准 display/role 下创建新的实际拍摄快照，图片回调及返回前复核该新目标。下一轮模型请求才接收新 ID；旧响应中的输入参数不重绑。旧 ID、旧 frame 及 10 秒输入时限不被刷新或复活。目标/app 在拍摄期间改变仍丢弃图片；显示、旋转、角色或来源 ID 不明时拒绝。模型看到实际观察包名与新资源 ID。
3. 此固件中窗口边界可在 `0..1920×1080` 与少 1 像素的边界间变化，根视图仍固定 `0..1920×1080`，内容版本不变。Android 的 [getRegionInScreen](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo#getRegionInScreen(android.graphics.Region)) 表示可触摸区域；getBoundsInScreen 是该区域的包围框，不能把它等同于根布局。仅在上屏恰好一个应用窗口、刷新成功的根视图严格等于全屏、可触摸区域为根内非空矩形时，布局身份使用根几何，区域独立冻结。每次触摸在最终 dispatch 前要求全部路径顶点同时位于观察时与当前矩形区域的交集；矩形凸性保证直线段也在区域内。没有像素容差或坐标补偿。多窗口、未知/非矩形区域及资格不完整时不采用此分离；包名、根布局、窗口身份/层级/焦点、内容事件与输入过期检查保留。

自动负例另作精确区分，不能把手工 TEST authority 当作正式产品授权证明：

| 控制 | 结果 | 证据范围 |
|---|---|---|
| 平台 no-input / wrong-target | 预期 fail | 原始输入分别 0 和 2，命中均 0；`receiver-negatives/`，mode=manual_platform_api |
| 观察中切换上屏应用 | 预期 inconclusive | receiver 记录 activity_paused；同上，不运行云模型 |
| 新截图中不可触摸的边缘坐标 | TARGET_CHANGED / not_submitted | 实际 AndroidBackend + Gateway，显式 ManualTestAuthority 组件控制；消息明确为观察/当前触摸区域限制，接收端输入 0；`native-guards-final/` |
| 超过 10 秒旧快照 | TARGET_CHANGED / not_submitted | 明确 expired，接收端输入 0；同上 |
| 换上屏应用后复用旧坐标 | TARGET_CHANGED / not_submitted | 明确 windows/app/layout changed，接收端输入 0、receiver inconclusive；同上 |

冻结版本组件测试实际取得可触摸区域 `0,0–1920,1079`，从图片提出 display 点 `(960,1079.5)`，精确触发 observed/current touchable region 拒绝；随后等超过 10 秒，旧快照精确触发 expired 拒绝。不是仅靠任意 TARGET_CHANGED 文字判负例成功。

早期边缘 swipe 没有实际唤出系统栏，不能算作系统栏变化负例，记录保留于 `receiver-edge-not-revealed/`。该硬件上的瞬时系统栏变化完整矩阵仍未覆盖。

仍未完成：完整 POC-11 接收端资格矩阵、持有输入时重配置的实体负例、原生 gamepad 注入、至少两个指定模拟器/游戏、真实模拟器私有目录配置权限，以及所有焦点/显示/服务失联/租约/进程死亡组合。无 Root 承诺、跨应用私有文件和 Shell 后代进程完全终止保证均不增加。

## 软件回归与复现入口

根 `npm run verify` 通过：Runtime 30、Native Fetch JS 12、DOM 7，共 49 项 JS；Native Core 33、设备 Core 44、设备授权 7、保留的模拟 Coordinator 16、Input Lab 64，共 164 项 JVM。模拟 Coordinator 不是实体设备证据。TypeScript、各模块 lint、debug/release APK 及 release 测试入口隔离检查均通过。新增测试覆盖旧输入不复活、未知/变更显示拒绝、拍摄后目标变化丢弃、根布局/可触摸区域资格，以及交集边缘与 10 秒过期。

设备脚本必须指定 serial、ADB server port 和实际 display：

```sh
python3 scripts/physical-accessibility.py --serial SERIAL --adb-port PORT --chat-display LOWER
python3 scripts/physical-live.py --serial SERIAL --adb-port PORT --chat-display LOWER --reuse-profile
python3 scripts/physical-device.py --serial SERIAL --adb-port PORT --upper-display UPPER --chat-display LOWER
python3 scripts/physical-device.py --serial SERIAL --adb-port PORT --upper-display UPPER --chat-display LOWER --guard-controls
python3 scripts/physical-receiver.py --serial SERIAL --adb-port PORT --upper-display UPPER
python3 scripts/physical-accessibility-denied.py --serial SERIAL --adb-port PORT --chat-display LOWER
```

无授权脚本通过 Shizuku 管理器 UI 仅切换 Thor Agent 自身条目，读取实际控件位置、核对前后状态，并在 finally 恢复；其管理器 UI 使用本机已确认的 default display 0。所有平台刺激与组件 TEST authority 均明确标注，不作为云模型输入成功证据。不同阶段 APK 不混算：实体初次 v2 使用 `9db59d901fddc8915844092ea5c2c0b54d7209b0bdae41e01d85746291be14a6`，60 秒轴/负例重测使用 `1ad423ec66d239820a4ce5b40752d33ebf0314b4b6a09a87bb4d72e1ed27df2f`，v3 全屏自动用例使用 `4712c6482bf630c0a59cd89cdfdea12e24b2e032ad5138faf93215ec6fd657ae`。最终构建和证据文件 hash 见证据清单。

`physical-live.py` 的真实凭据首次导入只走 stdin/Native 私有缓存并清理；再次运行使用已保存的资料。`physical-verify.py` 和受控 WebView fixture 会重置测试安装，**不能对已有真实资料的安装运行**；它们有 disposable 标记和现有非 fixture 资料拒绝检查。
