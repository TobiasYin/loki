# 下载与安装验收证据

本目录保存软件验收摘要、最终 APK 指纹与真实模型安装后的聊天截图。完整 Native 事件、原始工具输出、Android instrumentation 日志及每次重试存于忽略目录 build/integration/，摘要为它们记录 SHA-256。

- 设备范围：software_integration AVD / emulator-5554，Android 16；Thor 真机未恢复。
- 云服务：真实 OpenCode Go Responses / deepseek-v4.1-flash。不是 fixture 返回预设工具调用。
- 安装对象：官方 FossifyOrg/Calculator GitHub Release。版本、资产 URL 与 SHA-256 由模型在本次请求中读取，不由主机代做下载或安装。
- ADB 后端：真实 Shizuku UserService，执行 UID 2000，文件描述符传 APK，固定 pm install 参数。
- 批准交互：instrumentation 点击真实产品批准/拒绝按钮，明确属于自动化验收，不宣称用户真人点击。
- 失败保留：初次测试发现跨会话误读旧回合的测试等待条件，以及 Android 16 对末尾 `-` 的解析错误；已修复并重新验证。摘要保留错误类型和原始文件哈希。
- 真机后续：新增能力的 Thor 下载、安装、确认页与权限恢复测试仍待用户明确恢复实机测试。

复现入口与限制见 ../../docs/download-install.md。凭据由标准输入导入 Native Keystore，报告和截图不包含密钥。测试跑完后不清除用户资料，也不卸载已安装的 Calculator。
