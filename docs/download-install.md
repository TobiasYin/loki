# curl 请求、文件下载与 ADB 安装

新增能力用于按用户要求从网页/API 查询发布版本，下载文件，安装 APK 并回读验证。真机保持暂停；本轮验收目标为 `software_integration` Android 16 AVD（`emulator-5554`）。

## 工具与授权

| 模型工具 | 作用 | 会话能力 / 确认 |
|---|---|---|
| `web_request` | curl 等价的结构化 HTTP 请求，支持 GET/HEAD/POST/PUT/PATCH/DELETE/OPTIONS、显式请求头和 UTF-8 请求体；返回状态码、响应头和限长正文 | 联网，无额外确认 |
| `files_download` | 流式保存二进制，返回文件编号、来源/重定向地址、大小、SHA-256 和 APK 信息 | 联网，无额外确认 |
| `files_download_info` | 按编号校验持久下载；不传编号列出最近 20 个下载 | 联网，无额外确认 |
| `apps_install` | 以已下载文件编号和 SHA-256 安装独立 APK | ADB 调试 + 已授权 Shizuku UID 2000 + 本次 UI 确认 |
| `apps_package_info` | 核验指定应用的安装路径和实际版本编号 | ADB 调试，无额外确认 |

请求使用系统网络、DNS、代理和正常 TLS 校验，可访问 HTTP/HTTPS、内网与 loopback。调用者可以显式提供普通 API 的认证请求头；模型/Brave 的 Native 设置密钥不会被借用，Native 会拒绝将这些密钥写入工具参数或 URL。跨来源重定向清除调用者请求头，Cookie 与连接控制头由传输控制，响应不返回 Set-Cookie。

HTTP 正文读取上限 64 KiB、请求体上限 64 KiB、请求头合计上限 16 KiB，响应头合计保留 4 KiB；结果在编码后再次限长，保留完整结构并标记 truncated，不把半截 JSON 交给界面或模型。非 2xx 仍有 status 供模型判断；下载要求 2xx、完整 Content-Length（存在时）和非空文件。普通网页概要仍用 web_fetch，Brave 搜索仍用 web_search。

单文件默认 128 MiB、最高 256 MiB，整次工具调用最长 60 秒，持久下载总预算 512 MiB；空间不足时明确失败，不自动删除其他文件。二进制不经过 JS/base64 或模型上下文，未完成下载/校验失败会删除本次文件。文件保存在 Native 私有目录，使用不可猜路径的 UUID 编号，跨会话可复用；文件不能直接写入任意系统路径。当前没有面向用户的下载清理页面，大文件下载耗尽预算时需后续增加管理入口；当前不支持续传及 APK split/AAB 安装。

## 安装执行与安全边界

Native 在生成批准页时检查完整文件 SHA-256，解析 APK 的包名、版本、签名指纹与声明权限。批准页显示人可读的具体来源和文件身份。执行时重新检查下载文件，再以只读文件描述符传给真实 Shizuku UserService；UID 2000 进程独立复制并核对 SHA-256，短提交阶段只接受固定 `pm install --user <current-user> --restrict-permissions -r -S <size>`，通过标准输入传 APK，不接受调用者提供的路径或安装参数。

安装不允许 Agent 自更新、不请求降级、不自动授予权限，不开放任意 pm/sh 命令。现有抹除、格式化、刷机、提权、清数据、卸载黑名单仍然有效；即使输入正确批准哈希，debug_shell 也不能安装任意路径的 APK。系统继续检查签名与升级兼容性。安装成功后还需 apps_package_info 与 APK versionCode 一致才报告完成。

取消、到期、能力撤销或失联会终止传输和当前进程；已经提交给 Android 的安装可能完成，结果应标记 unknown 并重新查询。网络工具通过独立工作池执行，下载不占设备操作锁；设备输入、设置与安装继续串行，等待 UI 批准不占设备执行线程。

内置 `adb-operations` 目录只在上下文加载名称和描述。用户要求网络或安装操作时，Agent 主动读取 SKILL.md，再读取 `references/download-install.md`，完整手册不会自动塞入每个回合。

## 可复现的模拟器验收

`scripts/emulator-live-install.py` 只接受 emulator-*，并再次断言 ro.kernel.qemu=1。它安装 Agent/测试 APK，读取标准输入中的 modelKey 并导入 Native Keystore；不在主机代替 Agent 下载或安装目标应用。真实测试直接从聊天页面发任务，要求模型查询官方 GitHub Release、按实际资产 URL 与 digest 下载、请求安装、回读验证。安装确认由 instrumentation 点击实际产品 UI，证据明确标注自动点击，不声称真人确认。

首次运行将形如 `{"modelKey":"用户提供的服务密钥"}` 的 JSON 从标准输入传入（避免把密钥放在命令参数、终端历史或版本库）：

```sh
python3 scripts/emulator-live-install.py --serial emulator-5554 \
  --reject-install --output build/integration/live-install-rejection
python3 scripts/emulator-live-install.py --serial emulator-5554 \
  --reuse-profile --output build/integration/live-install-final
```

复用真实配置后不要在同一安装上直接运行会重置配置的 fixture 测试；原有 emulator-verify.py 会拒绝覆盖真实服务配置。真实密钥只留在此测试 AVD 的加密凭据区，不进入报告或 APK。

最终结果与证据摘要见 [验收记录](../tests/download-install-evidence/summary.json)。原始日志、完整报告和 APK 指纹保留在忽略的 build/integration/ 对应目录中；早期重试证据不覆盖最终记录。真实模型正向安装结果在验收结束后写入该摘要。
