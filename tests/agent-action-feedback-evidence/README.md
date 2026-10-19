# Agent 操作光标与操作后自动截图

2026-10-19，Android 16 模拟器（emulator-5554）原生组件验证。使用离线 Input Lab 页面，不调用云端模型、不操作真实用户业务数据。

已通过：

- 实际 Accessibility overlay 在主屏显示，窗口存在且不改变目标识别。
- 同一 overlay 可路由到独立第二显示器；通过 WindowManager 的真实 displayId 验证。
- 关闭偏好立即移除 overlay，后续 show 不再创建窗口。
- 开启和关闭展示时，真实 dispatchGesture 点击均完成并自动返回 JPEG、新 frameId 和新目标快照。
- 直接使用点击返回的 frameId 与新快照执行 frame_pixels 滑动；无需中间截图调用；滑动后再次自动截图。
- 自动截图时 overlay 已移除。
- 截图权限检查失败时动作仍返回 submitted，截图单独返回 CAPABILITY_DISABLED；不把已完成动作报成未执行。
- 测试恢复原无障碍服务列表、开关，移除临时显示器和临时文件。

动效更新：滑动不绘制轨迹线，圆点沿路径缓入缓出移动，结束时轻微形变回弹、淡出；点击放大缩小，长按按住后释放回弹。动效在原展示等待内完成。

`overlay.png` 是更新后的滑动圆点展示中的主屏画面；`post-action.jpg` 是操作工具自动生成、不含光标的 JPEG。`emulator-instrumentation.txt` 为最终通过记录（1 test，0 failures）。

软件检查：应用 250 项单元测试、设备核心 284 项 JVM 测试通过；Debug APK 与 AndroidTest APK 构建通过；device-android lintDebug 通过。Thor 实机未执行本次功能验收，第二屏证据来自模拟器临时显示器。
