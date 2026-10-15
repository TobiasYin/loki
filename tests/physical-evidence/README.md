# Thor 真机证据索引

完整说明见 [实机测试报告](../../docs/physical-test-report.md)。产品源码冻结于 `3a8ad910c350f4d52408380529c4e1e2cf8dd1bf`；[最终构建清单](artifacts-final.json) 记录该版 APK。后续仅有文档、证据与离线核对脚本的派生输出排除修复。

| 目录/文件 | 实际证据与阶段 |
|---|---|
| `physical-live-services.json` | 真实 OpenCode Go Responses、图片/工具回传、device_info、Brave 通过；密钥仅 Native |
| `cloud-input-success/` | 功能修复阶段真实云模型的逻辑坐标/截图坐标两条闭环通过；在触摸区域元数据由 VisibleBounds 改名 TouchableBounds 之前，不能冒认为最终 APK 的精确重复验收 |
| `auto-accessibility-final/` | 冻结源码版连续两次自动开启并实际连接，保留其他四个无障碍服务；会话 control/debug OFF |
| `accessibility-denied/` | 通过管理器撤销自身授权的实际负例，以及原授权恢复读回 |
| `native-guards-final/` | 冻结源码版真实 AndroidBackend/Gateway + 显式 ManualTestAuthority 组件控制；边缘/10秒旧快照/换目标均 not_submitted，接收输入 0；不运行模型或声称产品授权资格 |
| `receiver-negatives/` | 标明 manual_platform_api；no-input、wrong-target 预期 fail，上屏应用切换预期 inconclusive |
| `finger-v2/`、`button-v2/`、`axis-v2-retest/` | 用户实体操作基线，0.2.0/scenario2；分别 pass；轴窗口实际 60 秒 |
| `axis-v2/` | 5 秒观察窗不足的 inconclusive 原始尝试 |
| `held-v2/` | 长按 pass；重配置发生在松手之后，未覆盖持有中重配置负例 |
| `no-input-v2/` | 用户不操作，预期 fail，诊断通知不能制造成功 |
| `finger-baseline/` 等旧 baseline | v1 无条件设备通知失效的历史 inconclusive |
| `cloud-input-rejected/`、`cloud-input-statusbar/`、`cloud-input-fullscreen-first/` | 定位目标快照、SystemUI 内容事件和触摸边界问题时保留的拒绝记录 |
| `receiver-edge-not-revealed/` | 平台边缘 swipe 没有实际改变系统栏，不能算作系统栏变化负例通过 |
| `ime-settled.json` | 完整键盘 707 px，输入框与发送/停止可见，真实回复 IME_OK |
| `network-loss-final-attempt/` | 冻结版最后外网重复验收失败：Wi-Fi 离线，UnknownHostException，无 HTTP/工具执行；没有改成通过 |
| `device-final-state.json` | 正常启动留在下屏，Shizuku UID2000、无障碍实际 bound、其他服务保留；网络离线、probe failed |
| `software-verify.log` | 49 JS + 164 JVM、TypeScript、lint、debug/release、APK 隔离检查 |

[manifest.json](manifest.json) 给每个保存文件的字节数和 SHA-256；[integrity-check.json](integrity-check.json) 是离线核对结果。运行 `python3 scripts/check-physical-evidence.py` 可重新检查文件集合、接收报告的原始事件前缀、连续序号和完整性。派生核对输出不递归包含自己。

这些 hash 用于检测意外损坏，不能认证恶意系统/任意 shell。没有收录数据库、Native 凭据存储、配置密码或用户游戏存档。旧报告和资格 pending 状态保留；本目录不构成全部固件、原生手柄注入或完整 POC-11 的通过声明。
