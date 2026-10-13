# 2026-10-13 本地验证记录

范围：本 worktree 的独立 `runtime/` 工程。使用合成模型 SSE 和无副作用本地工具。没有真实 API Key、真实模型调用、Android 编译、Native bridge 或 Thor 设备。不是 POC-03/04/05 真机通过报告。

## 环境与复现

- macOS arm64；Node.js 23.7.0；npm 10.9.2。
- 精确 Pi / SDK / TypeBox 依赖见 dependency-notes.md 和 package-lock.json。
- `npm ci` 从本模块锁文件独立重新安装成功；本次 audit 0 vulnerabilities（仅是当次 npm audit 结果）。
- `npm test` 成功：TypeScript 类型/声明生成、esbuild 两个 browser ESM bundle、29 tests / 29 pass / 0 fail / 0 skipped。
- `THOR_CHROME_EXECUTABLE='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' npm run test:browser` 成功；实际 Chromium/Chrome 版本 154.0.8037.99。
- Playwright 管理的 Chromium 安装曾因下载超时失败，因此本次浏览器证据采用已有 Chrome。该浏览器版本是测试环境记录，不是支持 Android WebView 的最低版本声明。

## 契约测试证据

两协议各自测试真实 Pi adapter/SDK 发出的请求，fake 只替代 Fetch 返回的字节和受信工具端口：

| 断言 | 本地结果 |
|---|---|
| 中文文本多次增量，1-byte SSE chunk，完整响应结束前已有 text_delta | 两协议通过 |
| 用户图片进入协议 payload | Responses data URL / Anthropic base64 source 匹配合成 PNG |
| 工具图片→下一轮模型请求→最终文本 | 两协议2次请求、1次工具执行；call/result ID 与图片内容匹配 |
| 有效定义、唯一 L0、准确 URL / auth 占位、model purpose | 每个 request 断言；未配置 search、默认 control/debug 不注入 |
| 残缺 JSON、断流、零参数块、未知/未注入工具、非法参数 | 非法调用0执行；Anthropic 完成的零参数块正确接受 |
| 401/403/429/500 | failed，1次请求，无叠加重试，不保存原错误 body |
| OFF→ON、intent 后改变 epoch、迟到图片结果 | 旧调用0执行或不交付；新 lease 可执行 |
| 停止、开放流、bootstrap 取消、工具 deadline、迟到结果 | 单终态；停止等待；迟到结果不进入事件或模型上下文 |
| 单设备并发、lease 重用、跨Session/过期 lease | 第二Run拒绝、重用拒绝、错绑定无模型请求 |
| Run 内配置修改、图片过期、L0 替换 | 模型配置固定；每轮重载图片；历史 L0 不回放 |
| 有界历史、配对、来源过滤、必要内容过大、12轮等上限 | 整轮裁剪，当前目标/约束/effects 保留；超限停止 |
| intent 持久化失败、恢复 | 无工具执行，返回 PERSISTENCE_ERROR；恢复为 interrupted，无重放 |

完整可执行断言在 test/runtime.test.ts；fixture 的 HTTPS `.invalid` URL 不会被实际请求，因为 provider 的 Fetch 已被注入的本地函数接管。图片是固定1×1合成 PNG。

## 浏览器 bundle 证据

页面 CSP：仅允许 self script，connect-src none，没有 unsafe-eval。页面覆写 globalThis.fetch 为抛错函数，缺少 Node process/Buffer；浏览器观察到的网络请求仅为本地 index.html 和 harness.js。ESM 输出无 external import，离线加载无 CDN。

harnessResult.ok=true，六个场景实际结果：

```json
[
  {"protocol":"openai-responses","state":"completed","requests":2,"tools":1,"text":"浏览器图片工具回传完成中文","snapshots":2},
  {"protocol":"anthropic-messages","state":"completed","requests":2,"tools":1,"text":"浏览器图片工具回传完成中文","snapshots":2},
  {"scenario":"revoke/off/on","state":"cancelled","oldEpochExecutions":0},
  {"scenario":"cancel","state":"cancelled"},
  {"scenario":"HTTP 500","state":"failed","requests":1},
  {"scenario":"bounded compression","droppedTurns":18,"estimatedTokens":3860}
]
```

截图、真实识图语义、设备授权、Native 字节桥、API key 替换、SSRF/origin防护、Android生命周期/系统取消效果、内存/性能与 Thor 上屏路由均没有本次证据，保持未验证。独立验收通过不代表跨模块集成完成。
