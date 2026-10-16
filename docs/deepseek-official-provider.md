# DeepSeek 官方模型服务

核对日期：2026-10-16。设置 → 模型服务 → **DeepSeek 官方**，填写官方平台申请的 API Key，并从下拉框选择模型即可。地址与协议由应用填写；无需手输模型 ID 或凭据头。全新未配置安装默认显示此服务，已有 OpenCode Go / 自定义配置仍按原服务打开。

## 已核对的官方契约

| 项目 | 当前配置 | 官方依据 |
| --- | --- | --- |
| 基础地址 | `https://api.deepseek.com` | [首次调用 API](https://api-docs.deepseek.com/) |
| 生成协议 | `POST /responses`，Bearer API Key，语义 SSE | [Responses API](https://api-docs.deepseek.com/api/create-response/)、[Responses 使用说明](https://api-docs.deepseek.com/guides/responses_api/) |
| 模型目录 | `GET /models`，Bearer API Key；包含模型 ID、上下文、输出上限、输入类型与推理强度 | [模型列表](https://api-docs.deepseek.com/api/list-models/) |
| 思考模式 | 关闭 → `reasoning.effort=none`；低 / 高 / 最大 → `low` / `high` / `max`，默认高 | [思考模式](https://api-docs.deepseek.com/guides/thinking_mode/) |
| 会话连续性 | API 无服务端会话；后续请求发送完整本地会话历史，不依赖 `previous_response_id` | [Responses 使用说明](https://api-docs.deepseek.com/guides/responses_api/) |

内置目录来自核对当日的官方 `/models` 文档，不是实际持有官方 Key 的在线查询结果：

| 调用 ID | 展示名 | 上下文总容量 | 服务端最大输出 | 输入 |
| --- | --- | ---: | ---: | --- |
| `deepseek-flash` | DeepSeek-V4.1-Flash | 1,048,576 tokens | 393,216 tokens | 文字、图片 |
| `deepseek-v4-pro` | DeepSeek-V4-Pro | 1,048,576 tokens | 393,216 tokens | 文字 |

不要把 OpenCode Go 的 `deepseek-v4.1-flash` ID 复制到官方配置。官方文档要求调用 `deepseek-flash`。应用的一次请求输出预算与服务端允许的最大输出是两个值，默认仍使用应用的输出预算。

刷新列表使用当前 Native 表单的 Key，结果只缓存公开模型资料；Key 不进入 WebView、模型元数据或日志。认证/网络失败不覆盖已有目录。对未来新增、只有 ID 却缺失能力资料的模型，不猜测上下文或图片能力；当前已知 ID 的旧式目录响应可回退到已核对资料。远端有效元数据会更新上下文、模态和思考强度。

## 设置和凭据行为

- 官方服务只显示模型选择和 API Key。高级选项可查看目录提供的上下文与图片能力，并设置本次输出预算；官方地址、协议和 Bearer 凭据格式保持固定。
- 从 Go / 自定义切换到 DeepSeek，或离开 DeepSeek 切换到其他服务时，清空尚未保存的 Key 输入，不将旧服务的已保存 Key 自动用于新服务。跨此边界必须填入所选服务自己的 Key；同一服务留空仍保留已有 Key。
- “测试当前填写的连接”使用当前草稿，不自动保存；它只证明收到文本流式增量。测试的 low 思考强度仅从所选模型明确支持的资料取得。官方草稿不请求未支持的 `include` / reasoning summary。
- “刷新 DeepSeek 官方模型列表”调用官方 `/models`，不会保存模型连接草稿。为保护 Key，刷新和连接测试不向重定向目的地址转发认证信息。
- 保存仍由 Native 凭据库加密当前模型 Key，普通配置和 WebView 只包含公开信息。切换服务不复制历史数据，也不把一次测试成功当作图片/工具实测通过。已核实的官方模型按公开能力直接启动，不强制执行配置探针，也不伪造 `lastProbe` 或“已实测通过”状态。

## 验证范围

`DeepSeekCatalogTest` 使用受控 HTTP 服务验证 GET 路径、Bearer、目录元数据处理、无凭据重定向、损坏数据拒绝和跨服务凭据边界；`ModelConnectionTestTest` 核对官方 Responses URL、请求字段和已声明的 low 强度，同时保留 Go 的既有会话头与契约回归。

`EmulatorDeepSeekSettingsTest` 只允许运行在 Android 模拟器且无已保存模型 Key 的环境。它验证未配置时默认官方服务，操作真实 Native 下拉框、验证模型/上下文/文字能力、跨服务留空 Key 拒绝、保存重开及 Key 不进入 WebView；另外对 Go 和官方 DeepSeek 执行 Native `profile.check`、begin、end，但不启动模型，验证无需探针且不伪造探针通过；通过模拟器回环 SSE 服务验证草稿成功和 HTTP 401 错误展示。所用密钥均为明确的无效 fixture，不请求官方云服务。该测试不构成 DeepSeek 官方认证、计费、真实模型能力或真机控制验收。

实际官方 API Key 尚未提供时，官方云端认证与生成留待用户配置后通过页面测试确认；不拿 Go Key 代替官方 Key，也不将受控服务结果标成真云通过。

2026-10-16 整合验证：上述 17 项相关 JVM 测试全部通过；在独立 Android API 36 模拟器运行 `EmulatorDeepSeekSettingsTest` 的 2 项测试全部通过（3.925 秒）。包括默认官方配置、下拉与保存重开、Go / DeepSeek 官方 Native 任务准入不要求探针且不伪造探针通过、Key 边界、受控 SSE 文本和 HTTP 401。原始脱敏报告、汇总和截图保存在本地忽略目录 `build/emulator/deepseek-provider-2026-10-16/`，本轮日志为 `build/deepseek-emulator-settings-03.log`。测试结束已删除 fixture Key 并恢复原模拟器配置，保留测试会话记录。

对应整合包 SHA-256：App `5c6017d6b03c78c0715afb4b9c7e2538dfb96b1677bd36d43ebd9b6f584bd9e6`；AndroidTest `a4f55067844ff885cb54a6070fdb1fa7893d1fb251ed0cf818db138f53c6ff95`。这些校验值标识这一轮已安装并验证的包，后续重新构建须重新标识。
