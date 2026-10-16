# 双文档执行架构：最终软件快照

本目录固定停止入口微修后的双文档安装包构建时点，不覆盖旧115／429、后续139错误优先级补充或共享清单。独立的 typed-target availability 正在这份固定包之后开发，**不属于本目录139项和安装包的验证范围**。

| 检查 | 实际结果 | 范围与缓存边界 |
| --- | --- | --- |
| 最终统一 Gradle | 成功，17秒；506任务：29执行、477 UP-TO-DATE | 停止入口先Native撤权，再尽力投递JS取消；不等于Android instrumentation已运行 |
| JVM XML | 57套件、387项，0failure／error／skip | App85、device-core174、bridge core41、authority13、Input Lab74；App本轮执行，其余4模块测试UP-TO-DATE |
| Runtime Node | 139/139 | 真实Pi／SDK、受控SSE和合成Runtime端口；含后429的Native读屏错误优先级修复 |
| Chat JS Node | 66/66 | JSDOM界面与受限Chat代理；不等于真实WebView验收 |
| Bridge JS Node | 13/13 | 受控传输适配器回归 |
| 固定事件relay Node | 3/3 | 字段投影、错误过滤、无确认仍不积压Promise、不重试已失败投递 |
| integration TypeScript | 退出码0 | 成功编译无输出，日志为空 |
| 静态产物检查 | 成功 | 调试及未签名release APK的身份／权限检查；未在本目录安装或发布 |
| lint | 106个Warning，0Error／Fatal | 实际六份XML报告，部分缓存；保留警告和Gradle弃用提示 |

所有Node套件失败、取消、跳过和todo均为0。`junit-xml-hashes.json`保留逐XML属性、时间和SHA-256；缓存XML的387总数没有描述成全部新跑。软件日志来自各自owner已完成的命令，证据整理没有运行测试／Gradle／设备。

## 固定安装包身份

| 文件 | 最终SHA-256 |
| --- | --- |
| App debug | `0b68920d1e42defdc7583490a504c006696657cb1b8ec9cd5b225b1c45a62ec4` |
| Android Test | `92c91ad4cc2d833f30a97feea13cd9387aa382bb28cb794cf7a380d0b47d1d67` |
| Input Lab debug | `57278e3c3a49f8cee4e73850ed05fdcf01ae612d4e4e8dc70795542c221333f3` |

父任务固定文件来自`build/emulator/dual-document-2026-10-17/run-01/`，本目录校验了该目录真实文件的大小和哈希。首轮25秒／82执行／424缓存的成功构建与App`4864f7…`静态产物报告保留在`initial-*-before-stop-hardening.*`，它是微修前历史，不能冒充上表最终App。当前或未来测试harness／typed-target重编也不能倒填为这轮包。

`source-hashes.json`固定23份双文档关键源文件，收集在最终构建日志之后、独立typed-target修改之前；其时间和最终Runtime／runtime-host身份均已核对。这不是全部构建输入的原子归档，后续工作区文件可以正常改变。固定APK中的六份Chat／Runtime HTML及JS／CSS静态源码与当时文件逐字哈希一致；打包的runtime-host bundle另列其自身哈希，不把打包字节冒充TypeScript源码。

## 验证边界

Service执行WebView永不attach，Chat文档不持有Runtime、Native RPC、网络适配器、lease或工具入口。状态由Native原始view／publish双送，UI通知仅走固定小元数据；隐藏Chat不持续投递，执行不等待界面。详细原则和109源码说明见[后台执行文档](../../../docs/background-runtime.md#dual-document-execution-architecture-after-the-429-build)。

**本目录不声称双文档Android测试、380秒长后台测试或Thor云端／游戏任务已经通过。** 新模拟器验证在软件快照固定后开始，应另存实际安装包身份与结果。长测试设计要求HOME并销毁聊天后，在380秒内仅观察SQLite数字，且返回界面之前已取得全部逐秒标记；其完成／追问在UI返回后，长期隐藏状态下通知停止是独立覆盖边界。历史单文档短后台和双屏结果不能转用于新包。

不包含APK、数据库、凭据、模型思考／对话、原始SSE或设备私有上下文。`manifest.json`只覆盖本目录除自身以外的文件；旧证据和共享manifest没有改写。
