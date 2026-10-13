# 固定依赖与官方核对

核对日期：2026-10-13。使用官方 npm registry，并核对上游 `earendil-works/pi` 的 `v1.2.0` tag，`git ls-remote` 返回 `06a466935536f8c9bcacb2d5efc0fc021be1bdff`。`package-lock.json` 固定每个传递包 URL 和 integrity；不混用历史 `@mariozechner` scope。

| 依赖 | 本模块实际版本 | 依据 |
|---|---|---|
| @earendil-works/pi-agent-core | 1.2.0 | [官方 tag package.json](https://github.com/earendil-works/pi/blob/v1.2.0/packages/agent/package.json) 与 `npm view ...@1.2.0 --registry=https://registry.npmjs.org` |
| @earendil-works/pi-ai | 1.2.0 | [官方 tag package.json](https://github.com/earendil-works/pi/blob/v1.2.0/packages/ai/package.json) 与 npm 发布包 |
| typebox | 1.3.27 | 两个 Pi 包使用同版本；本模块直接依赖解释式 `Value.Check` |
| openai（传递依赖） | 7.19.0 | pi-ai 锁定依赖及实际 `npm ls` |
| @anthropic-ai/sdk（传递依赖） | 0.129.0 | pi-ai 锁定依赖及实际 `npm ls` |
| @earendil-works/pi-telemetry（传递依赖） | 1.2.0 | 实际 lockfile 与 `npm ls` |

编译与本地验证工具也固定：TypeScript 6.0.2、esbuild 0.25.12、Playwright 1.56.1、@types/node 22.19.1。这些不是必须跟随最新发布的产品依赖。

本实现使用 Agent 的 required `streamFn`、`prepareRequest`、`convertToLlm`、串行 tools、awaited subscribe；按发布包 `dist/*.d.ts` 与源码核对。协议入口直接从 `@earendil-works/pi-ai/api/openai-responses` 与 `.../anthropic-messages` 导入 `streamSimple`，不使用旧的顶层 provider 默认注册示例。见 [Agent 源码](https://github.com/earendil-works/pi/blob/v1.2.0/packages/agent/src/agent.ts)、[类型与钩子](https://github.com/earendil-works/pi/blob/v1.2.0/packages/agent/src/types.ts)。

核实了两 adapter 将 `fetch` 传入 SDK、SDK retry 关闭并由 Pi 的显式 maxRetries 控制。[Responses adapter](https://github.com/earendil-works/pi/blob/v1.2.0/packages/ai/src/api/openai-responses.ts) 使用基础 URL 加 `/responses`；[Anthropic adapter](https://github.com/earendil-works/pi/blob/v1.2.0/packages/ai/src/api/anthropic-messages.ts) 的发布包使用 beta messages SDK，实测请求为 `/v1/messages?beta=true`。本地 tests 断言最终 URL，兼容端点须另测该路径，不能从协议名称推断支持。

Responses 图片 tool output 和 Anthropic tool_result 图片 content 使用 Pi 自带转换，测试断言真实 SDK 请求体的 data URL / base64 source 与配对 ID。[Responses 共享转换](https://github.com/earendil-works/pi/blob/v1.2.0/packages/ai/src/api/openai-responses-shared.ts) 与 Anthropic adapter 是核对依据。

非秘密占位值为 `sk-thor-native-placeholder`：具有普通 OpenAI API key 前缀，且不会匹配 Anthropic OAuth 标志。测试断言对应 Authorization/x-api-key，并保持 credentialRef 只在受信端口信封中。真实 Native 替换是否彻底、是否绑定正确 origin/path/header 尚未验证。

浏览器打包显式令 `process` 为 undefined，消除 guarded Node/Bun 环境读取分支，不安装 Node polyfill。esbuild browser 平台生成无外部导入的 ESM。TypeBox 编译器会探测动态代码是否允许并回退到解释器；严格 CSP 下实际工具闭环通过，不需要放开 unsafe-eval。包安装中存在其他 provider 的传递依赖，不代表本模块支持它们，也不启用 OAuth、Bedrock、WebSocket 或模型目录探测。
