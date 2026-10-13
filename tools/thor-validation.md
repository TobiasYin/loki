# Thor 手动验收与未支持能力验证

以下用例全部 **待执行**。先使用合成配置和独立测试接收 APK，不触碰游戏存档；不需要等待其他开发聊天或合并其代码。保留设备型号、Android/API、Thor 固件、APK SHA-256、Shizuku 版本/实际 UID、工具输入和结构化结果。测试报告区分 API 提交、接收端真实事件和场景效果。

## display / perception / touch

1. 下屏打开 ToolPack，上屏启动用户明确的测试应用。人工核对两个 display ID、名字、几何/旋转和角色，拒绝单屏/同 ID 校准；重启、旋转、显示重建后重新确认。
2. 下屏输入法显示/隐藏、聊天焦点变化和上屏弹窗状态下，分别调用 L0、app_info、window_info；核对包名、版本与实际上屏窗口。验证查版本不生成截图，未知/ambiguous 不取下屏包名。
3. UI tree 测原生控件、密码字段、自绘 SurfaceView、null root 与预算；安全窗口截图拒绝，普通截图记录实际尺寸/region/缩放变换/rotation/frameId。
4. 指定 display 的 tap/long_press/swipe，在 Input Lab 的真实接收日志中核对坐标/时间/target display。分别测 display_logical 与裁剪缩放后的 frame_pixels；变更包、布局、旋转、等 10s 后重用旧 frame，必须拒绝。
5. 手势提交前撤销操作应零分发；提交后 STOP 必须阻止后续动作，报告系统已提交且即时取消不可确认；未收完成/取消回调时继续操作 unavailable。检查接收端，无接收证据不报告 injected/observed game effect。

## Shizuku / shell / config

1. 未安装/未启动/未授权/未绑定分别显示真实 availability。以 ADB 启动并绑定后，审批 `id`，核对 UserService UID 2000；Root UID 0 被拒绝，解绑/服务死亡结束 lease。
2. `printf` 与 stderr、非零 exit、输出截断、timeout、STOP、关闭调试/再开启旧 epoch、host 被杀全部检查。输出含合成敏感 token 时显示脱敏。没有进程组终止保证，检查已脱离子进程并将结果标 unknown，必要时人工停止 Shizuku。
3. 只在用户创建的 `/storage/emulated/0/Download/ThorToolPack` 做 Shizuku list/read。路径越界、符号链接、目录不存在/无权限、UTF-8/二进制、64KiB 截断都不能误报成功或完整 hash。其他应用私有路径应被拒绝。
4. 先在 `synthetic-config/sample.json` 验证 read→backup→批准无害变更→write→readback→批准restore，保留每个 hash、backupId 和恢复前副本。制造并发 hash 冲突、修改备份字节、审批内容变化、权限撤销、存储不足、quota 满额；不得写入错误目标或撤销后自动恢复。
5. Android Keystore 与真实文件系统路径可先用 Debug `run-smoke.sh SERIAL` 做 7 项通用检查。合成目录通过仍不证明真实模拟器路径/权限；SAF 和跨 UID 事务写入此版保持 unsupported。

## 原生手柄的高风险门槛

本版 `press_button/set_axes` 应返回 UNSUPPORTED。后续如果尝试受限 Shizuku 非 SDK backend，先对实际固件审查 INJECT_EVENTS、接口签名、shell UID、SELinux、device/source/axis 范围和目标 display 支持，保持固定按钮/轴方法；screen_control 与 adb_debug 分离，不能加入命令字符串逃生口。

至少收集：SOURCE_GAMEPAD/SOURCE_JOYSTICK、deviceId/设备身份、down/up 时间、左/右摇杆和扳机 axes、组合输入、焦点、目标 display、归零/释放。独立接收端必须区分 keyboard/touch/gamepad；source/deviceId 本身不能认证发送者，也不携带 toolCallId。

覆盖下屏聊天及键盘、上屏触摸后、物理手柄并发、旋转、目标切换、宿主进程死亡、UserService 失联、权限撤销和租约到期。按钮成功、轴不成立，或只送达下屏都不算通过。租约释放需独立于宿主计时；服务自身死亡导致无法证明归零时标 unknown、阻止继续，并检查卡键。

在经过接收端校准后，再到至少两个用户指定模拟器/游戏验证。若无 Root 受限路径不能满足全部门槛，继续报告 unavailable，提交范围决策；不转用键盘/触摸作为真实手柄，不自动采用 Root。
