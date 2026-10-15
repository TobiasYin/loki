> 2026-10-13 整合状态：已进入根工程正式软件集成；以下独立任务证据保留为历史参考。当前产品接线、复现入口与本次验证见 [中文集成报告](../docs/integration-report.md)，不能将旧模块通过直接当作集成通过。

# Thor Runtime 0.1

独立、无聊天 UI 的 TypeScript Pi Agent 运行时。只依赖本目录的 npm 工程；不导入 Android 宿主、其他任务目录或仓库根 workspace。模型 JSON、SSE、图片编码和工具循环全部由 JS/Pi 完成。真实网络、设备工具、授权事实和持久化由调用方注入。

## 安装、构建与运行

开发工具要求 Node.js >=22.19.0，本次实测 Node.js 23.7.0 / npm 10.9.2。包版本和传递依赖由 `package-lock.json` 固定。

```sh
cd runtime
npm ci
npm test
npx playwright install chromium
npm run test:browser
npm run demo
```

`npm test` 先做 TypeScript 类型检查/声明生成及 browser bundle 构建，再执行 29 项 Node 契约测试。`npm run demo` 默认监听 `http://127.0.0.1:4173`，用浏览器打开即可看到本地验证 JSON；它是测试 harness，不是产品聊天界面。可用 `THOR_RUNTIME_PORT` 改端口。

浏览器下载受限时可显式使用已安装 Chrome：

```sh
THOR_CHROME_EXECUTABLE='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' npm run test:browser
```

构建产物：`dist/src/index.js` 和类型声明；可离线加载的 `dist/browser/thor-runtime.js`；浏览器测试 `dist/browser/index.html` / `harness.js`；两个 esbuild metafile。生成文件和依赖不提交，重新构建即可恢复。

## 公共接口与已实现行为

从 `src/index.ts`（构建后包的默认 ESM export）导入 `ThorRuntime`、`ToolRegistry`、领域类型及 `defaultCapabilities()`。调用方先取得 Native 签发的 Run lease，再调用：

```ts
const runtime = new ThorRuntime(ports, registry);
const unsubscribe = runtime.subscribe(event => updateLocalProjection(event));
const result = await runtime.run(lease, [
  { type: 'text', text: '用户本轮目标' },
  { type: 'attachment', attachmentId: '用户选择的本地图片引用' },
], domainHistory);
// 用户停止：runtime.stop()
// Native 授权通知：runtime.authorizationChanged(sessionId, newEpoch)
// 退出时清理订阅：unsubscribe()
```

完整可运行的 fake ports、ToolPack 和 SSE 服务见 `test/fixtures.ts`，浏览器入口见 `demo/harness.ts`。

2026-10-16：产品为每个聊天持有一个 `RuntimeSession(sessionId)`，作为 `ThorRuntime` 构造器第四个参数传入，复用其中的 Pi Agent。新 Run 重新绑定权限与工具，`run(..., domainHistory)` 仍接收 Native 持久历史，宿主重启可重建会话。失败回合保留消息和工具结果检查点，并关闭未知工具结果的配对，避免恢复时重放。`runTimeoutMs: 0`（默认）不设模型/整轮时间上限，单工具时限仍有效。Pi 1.2.0 的 SDK 会把 0 当成立即超时，因此 `provider-timeouts.ts` 仅为标记的 Native Fetch、显式零超时适配 SDK 传输钩子；两个 SDK 版本显式锁定，有限时调用与其他 SDK 客户端保持原行为。升级依赖必须重跑三协议延迟响应、停止、失败继续和宿主恢复测试。最新验收见 [Skill 与会话验收](../docs/skill-agent-acceptance.md)。

- `SessionConfig` 包含模型修订、四能力意愿和 Native 单调 epoch。默认感知/搜索/操作/调试依次为 ON/ON/OFF/OFF；enabled 与 availability 分离。搜索未配置时不注入搜索工具。
- Run 经过 queued、preparing、generating、executing_tools 等状态，完成/失败/取消各有终态。单个实例只接受一个活动 Run；同 lease 不重用。恢复非终态为 interrupted，不执行旧调用。`awaiting_user` 是预留状态，首版没有确认恢复流程。
- 注册工具按 pack/version、toolId/modelName/schemaVersion 验证；未知能力、重名、任意 Shell 缺 adb_debug、非法 schema 或预算会拒绝注册。工具串行执行，所有资源检查/锁和最终副作用授权仍由 Native 实现。
- 每次请求重建唯一当前 L0 与有效工具定义；不回放历史 system/tool 声明。上下文 manifest 有模型修订、epoch、注册表版本、来源/附件引用、预算、SHA-256 和裁剪记录。
- 预算以配置上下文的 70% 减最大输出为限。图片有独立 token 估计，文本用保守估计；不声称精确 tokenizer。完整历史回合按最旧优先裁剪，当前目标与工具配对不可拆；显式 constraints 和来源过滤后的副作用 ledger 保留，仍超限则停止。原历史不会删除。
- 工具结果只持久化附件引用；每轮重新载入图片，删除、过期或权限来源关闭后用明确不可用文字替代。能力关闭会移除派生回答/原始结果；用户输入保留。L0 派生回答也标记感知来源。
- 工具 intent 先持久化；在执行前与交付结果前复核 lease/epoch、能力、可用性和目标快照。OFF→ON 不复活旧调用。工具超时或取消停止 JS 等待且拒绝迟到结果，不能据此保证 Native/OS 已撤销副作用。
- 注入的 Fetch 是唯一模型 HTTP 通道，固定 SSE、`maxRetries: 0`，只允许配置模型路径；请求/响应上限、lease 总时限、取消和重定向拒绝在 JS 中有边界。真实 API Key 不进入此模块，SDK 仅看到非秘密占位值。
- Pi 对部分工具 JSON 有修复行为；本模块额外检查原始协议结束事件与严格 JSON，残缺参数、无结束事件、冲突/重复 ID 或非法 schema 不执行工具。

## 验证范围

本地 fake/nativeFetch 覆盖两个实际 Pi adapter 的文本增量、中文跨字节分块、用户图片、工具图片回传、工具循环、401/403/429/500、不完整流、撤销/重开、取消、失败、预算和压缩。浏览器 harness 在无 Node `process`/`Buffer`、无直接 Fetch、无远程连接、无 `unsafe-eval` 的环境中执行真实 bundle。

这不是 Thor WebView 真机结论，也不代表实际模型端点兼容、Native Fetch 桥兼容或跨模块集成完成。版本依据见 [dependency-notes.md](dependency-notes.md)，端口接入与差异见 [integration-notes.md](integration-notes.md)，本次本地证据见 [verification.md](verification.md)。
