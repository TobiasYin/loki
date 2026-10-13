# 产品整合契约

旧独立模拟约定已由本次整合授权替代。正式桥页面固定为 `https://appassets.androidplatform.net/assets/chat/index.html`，同一 `TrustedWebViewBridge` 处理通用 Fetch 和受信 host RPC；origin/main frame/view/page/connection 共同校验，CSP 与请求拦截阻止直连。

`Authority` 是唯一权限事实源，`DeviceAuthority` 是执行器适配；JS 只持有 Native 签发镜像。`ProductHost` 负责持久化及批准记录；`SqliteStore` 的事务应答失败时停止网络和工具。Runtime state + 同序 event 一起提交，context manifest/effective set 检查归属及修订，intent 写入后才能 prepare/execute。

模型 Fetch 的 wire version 为 1；Pi runtime 内部 version 0.1 由 `integration/runtime-host.ts` 转换。SDK占位头不传到服务，Native按 profile/credentialRef/精确路径装入通用模板。两种图片协议的输入/结果均由 Pi 负责，Native 只保存图片与转发 HTTP 字节。

`awaiting_user` 已连接真实的批准/拒绝；Shell approval 绑定 exact command，文件 approval 绑定 path/old hash/new hash/完整 diff，最终 commit 再查 lease/CAS。Session 能力任意变化保守停止整 Run；off/on 不能复活旧 lease。

依赖、实测范围、未完成的真实手柄/私有配置/性能资格以及最终门槛见 [集成报告](../docs/integration-report.md)。
