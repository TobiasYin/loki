# Thor Input Lab：独立上屏测试 APK

当前只有规划文档，没有应用源码、依赖、构建配置或 APK。

未来作为独立 applicationId/进程/UID 的上屏接收端，采集 Android 实际送达的触摸、按键、手柄轴和焦点事件，并记录可重复场景的状态变化。前期手动 API 测试与后期 Agent 测试共用场景、断言和报告。

不包含 Pi、模型调用、Shizuku、输入注入器；不成为 Thor Agent 正式运行依赖。测试准备/报告通道不暴露为模型工具，不允许直接写通过状态或伪造事件。

完整设计：[上屏测试 APK](../../docs/input-lab.md)。验证入口：[POC-11 与相关 PoC](../../docs/poc-plan.md)、[测试策略](../../docs/testing.md)。本目录暂不细分源文件，后续获准开发后再建立独立可构建模块。
