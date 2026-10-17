# 模拟器键盘与文字组件验证

最终第三轮键盘组件通过（149.781 秒）；同一产物的动态内容回归也通过。原始两轮失败分别保留，没有合并成通过结果。

- attempt-01：只读测试页面假设有误，原 EditText 仍声明可写；随后换成真正普通 TextView。
- attempt-02：文字阶段全部通过；ENTER、CTRL+A 已到达接收端，但 ENTER 导致的焦点变化未处理，组合键没有选中预期字段，整体失败。
- attempt-03：补充真实 focused 感知，通过正式 ui_tree → input.activate → focused 确认重新聚焦，再验证 CTRL+A 和 BACKSPACE 的实际效果。全部文字、Unicode/选区/清空、不自动提交及 password/disabled/readonly/控件移动/自然过期/跨 Run 防护均通过。

运行使用正式 Native RPC、真实 Accessibility 与 Shizuku 后端、普通 Activity 和独立 Android 回调记录，不调用模型或云端，不涉及 Thor 或 Citra。BACKSPACE 实际删除由独立 TextWatcher 证明；Activity 中未观察其原始 DOWN/UP，因此只明确记录 ENTER 和 CTRL+A 的 6 条原始键事件及 CTRL 完整释放。

最终保留原始 120 个 Run 及所有原会话五表 payload，原选中会话和四份原始偏好字节完全恢复；新增 6 个组件 Run 全部终态，总计 126。授权、无障碍和原先停止的 Shizuku 状态恢复，数据库 quick_check 为 ok。内置 Skill 的预期升级单独记录，没有声称整个 files 树字节不变。

每次尝试都有独立产物指纹、实际组件日志、独立接收端数字记录及保留核验。顶级 manifest 由主任务生成。
