# 动态页面输入策略：软件验证

本目录固定动态输入修改的软件构建与测试证据，不包含模拟器、真机、云模型或游戏完成验收。

统一构建成功：52 秒，414 个任务，105 个执行、309 个 UP-TO-DATE。五模块当前 JUnit XML 合计 **441 项，失败/错误/跳过均为 0**：device-core 201、app 112、core 41、device-authority 13、input-lab 74。其中 `:core:test` 为缓存复用，其余四项测试任务本轮执行。详见 `junit-xml-hashes.json`；独立 Kotlin/JUnit 的 core 201 结果也保留，但它属于同一 441 合计，绝不再次累加。

正常文本、光标和游戏绘制更新不再让同应用、同窗口的输入目标失效。滚动或子树替换推进独立交互布局版本，使旧坐标失效，同时已提交的有界滑动可以完成。真正窗口、应用、遮挡、安全、角色和节点身份守卫仍然有效。原 sole-fullscreen canonical 条件与坐标必须处于旧、新可触矩形交集的行为保留，没有额外像素容差。读取与完成读取仍要求原有完整可触范围检查。

`sameLayout` 仍表达内容版本身份；截图 `contentChangedDuringCapture` 来自无障碍事件 revision，而非对全图像素作相等判断。

Runtime 251 和 Chat JS 73 沿用之前已通过的日志，未在本轮重跑；分别对完整 Runtime src/test 与 host 的 24 个文件、UI 与其测试文件核对字节一致。原日志的位置和哈希、源码库存于 `summary.json` / `source-hashes.json`。静态 APK 检查和固定 App/Test/Lab 的真实字节哈希均已核验，见 `artifacts.json` / `pins.json`。lint XML 的实际告警计数与哈希另存，没有把告警声称为零。

收集过程只写本 software 子目录，没有修改源码、构建、设备或父级总 manifest。设备接收端与真实模型验证应在独立证据中评估。
