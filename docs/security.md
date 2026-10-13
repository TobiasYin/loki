# 安全边界与信任模型

## 保护对象与不可信输入

保护 API Key、上屏/文件隐私、目标设备状态、游戏进度、配置恢复点和会话归属。不可信输入包括模型输出、网页内容、UI 树文本、文件内容、工具结果中的外部字符串、MCP 描述、用户配置端点返回体。

Prompt 不能形成隔离；提示注入可能诱导模型选择工具，硬边界必须由 Native 授权、资源约束和系统权限执行。经过用户启用的任意 Shell 仍然是强权限通道，此处不宣称其可被沙箱化为“只诊断”。

## 授权交集

允许一次执行必须同时满足：可信本地桥、活跃 Run、当前 Session 能力、工具在有效集合、参数合法、系统权限/后端就绪、资源范围正确、epoch 当前、未超时。UI、Runtime 和 Native 都可以早拒绝，最终决定只属于 Native。

操作输入 executor 与任意 Shell executor 分开；即使二者共用 Shizuku 服务，也不能让 screen_control 的 JS 入口传命令字符串。系统授权只是通道，不代表所有 Session 自动获得全部能力。

## 调试边界声明

ADB Shell 可截图、触摸、按键、读文件、联网。调试 ON 时，关闭感知/操作/搜索只会撤销对应工具集合与后续模型投影，不能阻止已经授权的任意 Shell 实现类似效果。这是能力模型的例外，必须在设置、首次开启和文档中一致说明。

Shell 受 shell UID、SELinux、系统版本约束，不等于 Root。取消只能尝试停止进程，已经执行的系统变更不可通过“关闭开关”撤回。结构化配置工具的备份保护不覆盖任意 Shell 的全部写入。

## WebView 与桥

只加载 APK 内可信资源，经固定本地 HTTPS origin 提供；message listener 使用精确 allowedOriginRules，校验 main frame 与连接身份，不使用 `*`。远程内容不在有桥的 WebView 中导航/渲染 iframe；外链交给无桥浏览器。

Markdown 禁止执行 HTML/script/javascript URL，远程图片默认不自动加载，避免隐式网络泄露。禁用不需要的 file/content 访问和生产调试开关，CSP 按实际依赖最小配置，不能为兼容性无条件放开远程脚本。

参考 [Android Native bridge 风险](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)。本地可信页面遭 XSS 仍可能滥用已授权能力，因此 origin 检查不能替代参数/资源/lease 校验。

## 网络与秘密

模型和搜索凭据分别绑定端点；桥不向 JS 返回明文；通用 header 替换方案由 POC-04 验证。模型请求只能访问已配置目的地和允许的方法/路径；搜索抓取需阻止内网地址及逐跳重定向逃逸，显式本地模型配置不扩展成任意内网探测权限。

诊断、错误信息、HTTP header、命令输出可包含秘密，保存/显示前脱敏并限长；不能承诺自动识别所有敏感配置，导出前由用户查看范围。模型本身可以看到被授权工具返回的数据，应用不能撤回已经发送给用户服务的内容。

## 主要威胁与测试

| 威胁 | 控制 | 验证 |
|---|---|---|
| 网页让模型调用已关闭工具 | 不注入＋Native 实时校验 | 伪造工具、旧 epoch、直接 bridge 调用 |
| A 会话请求借 B 权限执行 | Native Run/Session 绑定，不信任 JS 自报 | 交叉 ID 和切会话竞态 |
| 恶意网页/iframe 调 Shell | 精确 origin/main frame、无远程导航 | 跨源、同源子 frame、重定向 |
| API Key 传给另一个站点 | origin/path 绑定、拒绝凭据重定向 | 302 到恶意 host、日志脱敏 |
| 配置路径换成符号链接 | 规范化＋操作时重新验证＋hash/CAS | TOCTOU、文件替换、SAF grant 撤销 |
| 手柄卡住/目标切换 | 资源租约、目标校验、受限释放 | 断服务、停止、旋转、焦点改变 |
| 模型无限工具循环 | 轮次/时限/输出上限 | 无进展循环、超大结果 |
| 压缩复活旧授权或秘密 | 来源标签、有效工具覆盖、投影测试 | 关闭后摘要/旧系统段重新出现 |

## 首版明确不提供

对恶意/被攻破 OS 的完整防护；任意 Shell 的强沙箱；已经上传数据的远端删除保证；所有 side effect 的事务回滚；Root/ROM 级安全承诺。独立 APK 和不上 Play 只改变分发方式，不消除平台权限及应用自身安全责任。

## 测试 APK 的独立边界

Thor Input Lab 不持有 Agent 凭据、Shell 或注入权限，只观察本应用收到的事件。其测试控制协议限制到受信 runner，显式组件、签名权限与短 lease；不进入 Agent Tool Registry，不提供写 pass/改原始证据接口。生产包不开放测试控制入口。调试 Shell 能力很强，受控测试必须排除篡改 Lab 报告；此设计不宣称对恶意 shell/系统具备防作弊能力。详见 [测试 APK](input-lab.md)。
