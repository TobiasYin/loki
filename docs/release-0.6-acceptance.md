# Thor Agent 0.6 验收记录

本轮针对上屏感知与输入、消息显示、语音手势、启动延迟、下屏默认启动、话题列表和设置页进行集成。验收设备为 AYN Thor，Android 13；上屏逻辑 display 0，下屏 display 4。显示 ID 仅代表本次设备枚举，产品使用显示角色解析，不把其他设备的 ID 写死。

## 证据口径

- `agent_e2e`：实际已保存的 OpenCode Go / DeepSeek V4.1 Flash，产品会话、原生权限链和独立 Input Lab 接收端。模型看到截图后自行定位目标；视觉用例不向模型提供点击坐标。
- `manual_platform_api`：明确标注的组件测试，验证原生输入及停止清理。使用测试权限，不宣称模型或产品任务验收。
- 语音手势组件测试模拟 ASR 状态并截获发送；真实麦克风测试另外执行，使用离线生成的火山 TTS 音频经扬声器播放、物理麦克风采集、火山流式识别和真实模型回复。
- 任何接收端 `pass` 均同时核对原始事件前缀的 SHA-256、完整性及原始设备身份；不以工具返回“已发送”代替接收成功。

## 已修复的集成问题

1. 普通重启后没有恢复显示角色，导致已具备截图权限但无法选择上屏。现在启动时从实时显示列表解析角色，配置冲突或缺失则明确要求重新选择。
2. 模型思考超过原 10 秒观察资源期限时，点击被过早拒绝。观察资源期限改为 60 秒，应用、窗口、布局和显示 epoch 校验仍保留；模型任务没有总时限。
3. 应用的正常按键反馈曾使持续按键被误认为目标切换。已经提交的短时持有校验独立窗口结构，新输入仍要求最新完整观察。
4. Android 会把合成 KeyEvent 和摇杆 MotionEvent 的设备 ID 改为 -1，不能冒充真实手柄验收。按钮和轴输入改用动态发现且核验身份的 Odin Controller evdev 节点，只开放固定按钮及六个经过固件映射验证的轴。
5. 每次任务启动和切换重复扫描、写入全部历史及排队探查。现在使用当前会话/运行索引、局部事务更新；已验证配置直接启动，未验证配置才排队检查。
6. 语音控件重绘和事件清理可能提前结束持有。现在固定目标节点和几何位置，长距离上滑只标记待取消，松手才执行取消；滑回再松手正常发送。

## 范围与限制

本轮控制验收针对独立接收应用和有界的按键/六轴动作，不代表全部游戏、模拟器和组合输入兼容性。按钮和轴输入跟随物理手柄焦点，产品核对焦点应用和上屏角色；不会修改焦点锁来强行路由。Shizuku 服务有独立释放定时器，但服务进程被系统强杀时不能承诺内核按键或轴一定释放。

图片/工具能力完整探查与设置页的文本连接测试分开显示。文本连接成功不自动提升图片或工具能力认证。历史真机手指基线、手柄基线及重新配置报告继续保留，不因本次自动测试而改写其资格结论。

本轮原始工作目录：`build/physical/release-0.6-2026-10-16/`。此前失败目录保留，最终成功记录另建目录；私有数据备份、凭据和完整用户数据库不进入提交的验收证据。

## 最终验收结果（2026-10-16）

最终安装版本为 **0.6.0-integration / versionCode 7**。独立 Input Lab 为 0.3.1 / code 4；Shizuku UserService version 10。正常桌面冷启动实际进入下屏 display 4，没有执行手动校准；稳定后界面正确显示已保存模型和语音测试会话。

| 项目 | 最终结果与证据 |
| --- | --- |
| 真实模型看屏操作 | `receiver-06` 四项全部 pass，Run 均 completed、工具 error 均为 null。读取上屏 APP 信息、完整截图、自主定位 START 并点击；另有逻辑坐标点击、A 键及六轴。见 [模型汇总](../tests/release-0.6-evidence/receiver/physical-cloud-input.json) |
| 真实控制器身份 | 按钮与轴均为 Odin Controller deviceId 30；按钮 source 1281，轴 source 16777232。没有用虚拟 -1 身份替代，六轴最后全部为 0。见 [手柄说明](release-0.6-gamepad.md) |
| 停止后释放 | 四轮严格组件全部通过：A 实际 200 ms；六轴实际 300 ms；原计划 3000 ms 的 A 和六轴在实际持有 102、103 ms 后被停止并释放／归零。清理确认后没有新按下或非零事件。见 [组件汇总](../tests/release-0.6-evidence/components/gamepad/physical-gamepad-component.json) |
| 目标保护 | 越出可触区域、60 秒观察过期、切换上屏 APP 均在提交前拒绝，接收原始输入数为 0。见 [守卫报告](../tests/release-0.6-evidence/components/target-guards/physical-target-guards.json)；其接收端 fail/inconclusive 是测试预期 |
| 语音手势 | 真实平台触摸完成小幅上滑、长滑待取消、滑回松手发送、长滑松手取消；目标位置不变、无提前松手、无重复发送。此项模拟 ASR，仅验证 UI。见 [手势报告](../tests/release-0.6-evidence/components/voice-gesture/physical-voice-gesture.json) |
| 真麦克风和 ASR | 最终包通过“设备扬声器 → 物理麦克风 → 火山流式 ASR → 松手发送 → 真实模型回复”；模型返回“语音测试成功”，取消未发送。离线火山 TTS 音频未打入 APK。见 [语音报告](../tests/release-0.6-evidence/voice/physical-voice-agent.json) |
| 设置当前草稿 | 真实 Go Responses 文本流成功；无效模型正确失败；Brave 实际搜索成功；没有自动保存草稿或改动凭据、会话数。见 [设置报告](../tests/release-0.6-evidence/components/settings/physical-settings-draft.json) |
| 软件回归 | **252 项 JVM、92 项 JS 全部通过**，所有本轮构建与指定 lint 通过；Chrome 三种布局检查通过；SQLite 隔离临时库事务回归另有 3 项通过。见 [软件证据](../tests/release-0.6-evidence/software/) |
| 历史与凭据 | 原有 119 个会话、220 条消息、25,848 条事件及 26 个附件均保留；原有消息、事件、附件、Run 内容未改写。仅自动回填原通用会话标题。模型／语音配置、凭据文件字节一致，原用户导入 Skill 的元数据和文件一致。见 [保留核对](../tests/release-0.6-evidence/preservation.json) |

任务开始到原生准备完成为 19–21 ms，到首次请求授权为 1,313–1,331 ms，到响应头为 3,826–4,067 ms。工具回合与模型生成仍需服务端时间，截图用例首次正文在 21.6 秒出现；这包含 APP 信息、图片观察和点击三个工具回合，不代表本地停滞。普通模型任务仍无总时间上限，观察资源和连接测试有独立的有效期／等待预算。

最终 debug APK SHA-256：`2bdaa96e7382ad48e13529699db731c155d306d67eb98f18f2689a6502d9ba88`。已安装 APK 的 SHA-256 与构建产物一致，见 [冷启动及安装核对](../tests/release-0.6-evidence/cold-launch.json)。release 产物为 unsigned，尚不具有发布签名；全部产物指纹见 [artifacts.json](../tests/release-0.6-evidence/software/artifacts.json)。

所有可提交证据见 [目录说明](../tests/release-0.6-evidence/README.md)。本轮修复了按钮／轴身份和停止链路，并未把接收端升级为全游戏兼容证明。未继续探查亮度、风扇和刷新率等硬件设置，原有 120 Hz 生效限制与完整物理基线资格按历史专项记录保留。
