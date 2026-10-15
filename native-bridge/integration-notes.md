> 2026-10-13 整合状态：已进入根工程正式软件集成；以下独立任务证据保留为历史参考。当前产品接线、复现入口与本次验证见 [中文集成报告](../docs/integration-report.md)，不能将旧模块通过直接当作集成通过。

# Integration notes — 独立桥 v0.1

本模块独立验收，不代表已经与 Pi、正式 App、存储或 Thor 设备工具集成。公共仓库文档中的“仅文档”是历史阶段；本任务的代码仅在 `native-bridge/`。

## 线上边界仍归 Native

`Authority` 是唯一能力事实源。宿主负责创建 Session、签发连接/Run、登记允许的 `(purpose, profileRef)` 集合和最终 Native 工具描述；这些 mutation API 不通过 WebView 暴露。JS 字段只与内存绑定匹配，不能自行声明权限。每设备一个 Run，重开产生新随机 lease/connection/run ID；本模块没有恢复旧执行的路径。

Session 默认为感知/搜索 ON、操作/调试 OFF。每次开关变化或资源授权失效递增 epoch；关闭后再开启仍拒绝旧 epoch。`effectiveTools(binding)` 是宿主发布工具列表的入口，Native 记录该 Run/epoch 的已发布集合。若后端从不可用恢复，一个未注入工具仍不能执行，直到宿主重新发布集合。每次模型请求前宿主必须读取 `currentBinding()`、发布 `effectiveTools()` 并更新 Runtime 的 context 镜像。当前最小宿主在 hello 时发布，无产品开关 UI；变更和竞态通过 JVM API 测试验收。

网络 start 都需要最新 epoch。**已开始的 model 网络**只继续检查活动 Run/lease、固定 purpose/profile 和 deadline，不因搜索 toggle 结束；search/fetch 网络继续检查当前 epoch/搜索能力并即时取消。宿主如需防止模型在能力变化后继续生成过期工具，应另调用停止/取消，Runtime 下一轮使用新镜像。独立的模型 profile 永远不能用于 `web_search`/`web_fetch`，反过来也一样。

本模块 Session、Run 与 fixture 凭据使用内存实现。正式 App 应负责持久化 enabled/epoch、Keystore 支持的私有秘密存储、配置测试授权、事件审计及重启后 interrupted 状态；不要持久化或恢复执行 lease。它们属于后续对接，当前没有数据库/Keystore/真实凭据兼容声明。

## 信封与流

控制消息为 JSON；HTTP 信封为：

```json
{
  "bridgeVersion": 1,
  "requestId": "unique_per_run",
  "binding": {
    "sessionId": "native_session",
    "runId": "native_run",
    "lease": "native_nonce",
    "connectionId": "native_connection",
    "capabilityEpoch": 1
  },
  "purpose": "model",
  "profileRef": "profile@revision",
  "deadlineMs": 123456,
  "type": "request.start",
  "url": "https://configured.example/v1/endpoint",
  "method": "POST",
  "headers": {"content-type": "application/json"},
  "credentialRef": "private-reference",
  "hasBody": true
}
```

`deadlineMs` / `observedAt` 使用 Native 单调毫秒时基；JS 不计算跨时钟 deadline，直接使用宿主 bootstrap 中签发的值，自己的 `timeoutMs` 仅作为额外取消保护。正式审计需要另外添加墙钟时间。运行内 request/call ID 必须独立唯一，不因 error/abort 后重用。

- `request.start` body 为已经序列化的字节；不接受 body 文件引用、模型对象或流式上传。base64 模式把字节放在 `bytes` 字符串，二进制模式放在 frame 尾部。
- 二进制 frame：4 字节 big-endian header UTF-8 长度、JSON header、剩余原始字节。header 最大 64 KiB；请求 body 最大 16 MiB，响应块最大 32 KiB。探针确认前不用二进制。
- `response.head` 包含 `status/statusText/headers/url`。header 名称小写、多值数组；剔除 cookie/鉴权响应头并遮蔽已知 secret。HTTP 4xx/5xx 保留 Response 和 body。
- `response.chunk` 从 sequence=0 开始连续递增，字节不可当作字符边界；UTF-8/SSE/模型事件全部由 JS 解析。
- `stream.credit.bytes` 为**追加可发送字节额度**，不是累计 ACK；Native 可用额度不得超过 256 KiB。首个 body pull 才授予 32 KiB；无需求不交付 body。HTTP codec 的小缓冲及一个 EOF lookahead 字节不属于桥交付窗口。
- `request.abort` 幂等取消底层 call；Native 与 JS 都拒绝迟到交付。HTTP 流只产生一次 end/error。Android 在实际 UI 队列交付时再次校验权限并过滤 aborted ID；独立 DeliveryBudget 将待交付数据限制到每请求 256 KiB / 总计 512 KiB，不依赖 renderer 声称已消费。额度耗尽时仅允许小型 terminal error 继续交付，512 个请求 ID 上限也限制了这部分控制消息。
- ReadableStream cancel、AbortSignal、deadline、总时限、连接失效都会结束流。没有 provider 重试或 POST 重放。

JS `createNativeFetch` 支持 string/URL/Request、method/header 合并、已序列化 string/Uint8Array/ArrayBuffer、AbortSignal、status/headers/url/body、text/json/arrayBuffer/bodyUsed/clone。Request body 先按上限读取为字节；显式流、FormData/Blob、表单 Request、credentials/mode/cache/redirect 等额外 Fetch 选项拒绝。clone 每分支缓冲最大 256 KiB，慢分支超过上限时两分支明确失败并取消 Native 请求。整体 text/arrayBuffer 仍受 32 MiB 响应总限。

返回的是受限 Response facade，**不是完整浏览器 Fetch，也不保证 `instanceof Response`、blob/formData 或未列出的接口**。尚未对锁定 Pi/pi-ai 的实际源码执行兼容测试；后续 SDK 适配必须保留这些限制，不能为了兼容开放 WebSocket/浏览器原生 fetch。当前 `json()` 是 JS Response 自带解码，Native 没有 JSON/SSE/model 事件分支。

## 网络 profile 与秘密

Native profile 是深复制后固定的配置：origin 精确 scheme/host/port、**精确 encoded path 集合**、method、purpose、credentialRef、通用 header 模板。相对于文档的“允许 path 前缀”，这里采用更严格的精确路径；provider 构造出的全部路径需由 Native 配置审查登记。`profileRef` 应含配置修订，Run 的允许集合不能因设置修改偷偷替换。Native 不按 OpenAI/Anthropic 品牌分支。

模板使用 `{{credential}}`，例如 `Authorization: Bearer {{credential}}` 或 `X-Token: Token {{credential}}`。JS 可省略模板头，或提交与 Native 模板完全一致的 placeholder；Native 从端点绑定的 credentialRef 替换。错误 ref、方法、目的地、原始秘密值、危险 header 或伪造鉴权头均拒绝。profile/store 没有 JS get-secret 方法；异常只暴露错误 code，既不把异常 message 返回 JS，也不打印 URL、header、payload。

按用户最新要求，HTTP/HTTPS、LAN/回环/保留地址、代理 fake-IP 与混合 DNS 结果均交给系统网络连接，不限制为公网；默认使用系统 DNS 和代理。HTTPS 仍执行正常证书验证。模型/搜索凭据绑定配置端点、方法与路径；带凭据重定向作为 3xx 返回，不跨目标转发凭据。无凭据 GET/HEAD 最多 5 跳，逐跳校验用途和配置范围；跨 origin 清除 caller header 后继续访问，POST 不重放。web_fetch 无服务凭据。超时、字节预算、撤销和停止仍生效。

HTTP body 是不透明字节，**不会为了脱敏去重写协议内容**。宿主给 ToolGateway 注入同一 vault redactor，用它拒绝含已知凭据的 string 参数并遮蔽工具结果。请求服务本来就会接收凭据；恶意服务可能在 body 反射凭据或其他敏感内容，原始 body 不应作为日志或导出。已知 secret 的 headers/异常/工具结果脱敏不是任意远端内容的数据防泄漏保证。正式 Runtime 的可见错误摘要、存储和导出仍需独立的通用脱敏与范围确认。

## 工具执行器对接

`ToolDescriptor` 是本模块局部的 Native 契约，不复制整个 ToolPack 注册系统；schemaVersion=1 只实现严格的 string/integer/boolean 字段、必填、拒绝未知字段、长度/数值范围。复杂 JSON Schema、pack/provenance 元数据和 Pi 错误通道由后续适配层接入；不能将复杂参数绕过这个校验。

`resource` 必须属于 Native 工具允许集合，`snapshotId` 必须匹配宿主当前资源快照。后端 readiness 与资源快照在副作用前再次核对；改变 display/权限/backend 时宿主应在 Authority 事务中更新快照并调用 `invalidateResources`。所有本模块工具串行，先不做并行读工具。

执行器只接受 `ExecutionContext` 与已经校验的 scalar 参数。**每次平台读取、输入、写入或子动作都必须经 `context.step`；单次 step 是短且有界的提交，不能在授权锁内执行等待或整段长任务。**等待发生在 step 外，再在下一个 step 重验；结果交付前同样检查 lease/epoch。必须另为未来的真实平台 executor 提供释放/终止句柄和独立租约看门狗；本任务只用 mock，没有 Shell、设备感知、真实输入、文件提交或 Shizuku 实现，因此不声称真实设备取消/释放已经可靠。

`ToolResult` 包含 code、observedAt、source、truncated 与 outcome；无错误 code=null。执行前拒绝为 observed（无已提交副作用），提交后撤销为 submitted，执行器在提交中异常为 unknown。结果预算按 UTF-8 字节裁剪且不切字符。正式 executor 必须根据系统证据补充 submitted/observed/unknown，不能从 mock 成功推断平台已经送达。

## WebView 接入约束与待验

只允许 `https://appassets.androidplatform.net/assets/index.html` 这个当前主文档；精确 origin、main-frame、同一 WebView 对象、当前 URL、Native connection 都检查。file/content/mixed-content/远程子资源/iframe 导航被禁，CSP 禁内联与远程脚本、connect、frame、图片、对象、表单。外链应由正式 App 转交无桥浏览器，当前宿主直接阻挡。不得给该 WebView 加远程广告、任意 HTML 或 untrusted `loadDataWithBaseURL`。

页面 reload/导航/renderer 退出/宿主离开前台使旧连接无效；`close()` 清理 transport 与 gateway。正式 App 应让清理 owner 与连接生命周期一致，不把桥 view 假设为后台存活服务。唯一受信文档遭 XSS 时仍可使用当前已授权能力；参数/资源/lease 检查不能被 origin 检查替代。

本地纯 JVM 的 bridge metadata policy 测试不等于 WebView 中的恶意 iframe 隔离验收。需在真实 WebView 记录 provider/version、ArrayBuffer 探针、base64 fallback、frame/origin/导航拒绝、renderer 退出、主线程队列撤销、背压内存峰值。Thor 的 WebView 支持、双屏/焦点、实际输入与系统权限全部待验。最小宿主的自有 JS 页面可用于这些测试，但本次没有设备执行证据。
