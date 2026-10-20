# GitHub 公开检查记录

> 下文保留清理前的检查记录和当时的验证结果。用户已授权整合并重写 `main`，最终执行范围与复核结果见 [main 历史清理记录](history-rewrite.md)。历史状态描述不代表当前待办。

检查日期：2026-10-20。基线提交：`6b5888f`。本轮检查当前工作树、所有本地 Git refs 可达历史，以及仓库内的依赖配置、文档和验收材料；没有创建 GitHub 仓库、推送或改写历史。

## 结论

公共构建源和开发机绝对路径问题已修复，主项目已按用户选择采用 MIT 许可证。本轮扫描未发现可确认的真实 API Key、私钥或已跟踪的签名密钥文件。仍有个人设备标识、提交邮箱与游戏截图需要决定是否随公开仓库发布；本轮未将这些内容自动认定为可以公开。

## 已处理

| 项目 | 处理 |
| --- | --- |
| 内部 npm 镜像 | `app/package-lock.json` 的 42 个 tarball 地址改为公共 npm registry；另外两个模块的锁文件也已核查。当前总计 161 个远端 tarball 均指向公共源。 |
| 安装入口 | 根目录、App、Native JS 和 Runtime 均有公共源 `.npmrc`；安装、完整验证脚本显式指定公共源，不修改开发者全局配置。 |
| 固定版本 | 除下述安全更新外，其余 App 依赖的版本和 integrity 均保持不变。Runtime 的本地 browser port 继续通过仓库内相对路径引用，不替换为外部包。 |
| DOMPurify | npm audit 检出旧版告警后，从 `3.3.1` 更新到 `3.4.17`；APK 内附带脚本、版本说明以及 Apache/MPL 许可证同步更新。三个 npm 模块安装审计均无已报告漏洞。 |
| 开发机路径 | 36 个文档、日志文件中的机器路径与绝对示例路径已改为相对路径；本地 skill 链接改为仓库内置资源链接。设备 `/system`、`/data`、`/sdcard` 等路径保留，避免破坏 Android 工具契约及安全测试。 |
| 历史证据 | 28 份日志经过路径处理，32 份关联导出元数据更新哈希和字节数。原始与公开文件的哈希保存在 [publication-redactions.json](../tests/publication-redactions.json)；原始接收事件、事件前缀哈希、APK 指纹、历史源码提交和源码 pins 保持原义。清理不代表重新执行历史验收。 |
| 首次构建 | 修复 `bundle-runtime.mjs` 在首次构建时写入尚不存在的 `build/integration` 目录而失败的问题。 |
| 防止再次带入 | 补充本地环境变量、Android 签名材料等忽略规则；新增 `npm run verify:public`，检查开发机路径、npm 配置与锁文件，并接入完整验证入口。 |
| 主项目许可证 | 按用户选择添加根目录 [MIT LICENSE](../LICENSE)，版权声明为 `Copyright (c) 2026 Tobias and contributors`，同步项目 npm 元数据。第三方依赖、上游代码及第三方素材保留各自许可证或权利声明。 |

## 凭据与二进制检查范围

- 使用 Gitleaks `8.30.1` 默认规则扫描当前源码快照及全部本地 refs 可达历史。Git 中有 96 个可达提交，扫描器报告扫描 88 个非 merge 提交。
- 源码和历史各有 108 个 generic-api-key 命中，逐个核对均为证据文件的 64 位十六进制哈希：105 个 `newKeyHash` / `oldKeyHash`、2 个 Native 凭据文件保留性哈希、1 个接收事件文件 SHA-256。没有把这些证据哈希删除，也没有添加宽泛的凭据扫描豁免。
- 补充检查常见 Token 前缀、私钥标记、凭据字面量、内部域名、邮箱、私网地址及敏感文件名。代码中的 Native 凭据占位值和集成 fixture 凭据属于合成测试数据；火山语音域名是产品服务端点，未按内部镜像处理。
- 仓库含 56 张 PNG/JPEG，进行了缩略图检查，并查看了语音设置等关键原图。游戏画面、对话与存档名称见下节；图像检查不构成对所有像素或素材权属的保证。WAV fixture 的元数据及生成脚本表明来源为合成 TTS。
- 自动扫描不能保证发现任意格式的凭据，也不覆盖远端未拉取 refs、不可达历史、备份、开发机忽略目录或未提供的文件。本轮扫描报告保留在本地忽略目录 `build/public-review/`，避免将报告中的历史作者信息再次提交。

## 仍需在公开前决定

| 内容 | 位置与处理建议 |
| --- | --- |
| 公司邮箱与历史路径 | Git 作者记录包含公司邮箱，旧提交也仍含清理前路径和内部镜像。工作树修改不会移除这些历史内容。公开新仓库时可使用经复核的当前快照作为首个提交；若保留历史，需先明确作者邮箱和历史清理范围，再处理全部拟公开 refs。本轮没有改写现有历史。 |
| 真机序列号 | 位于 `docs/loki-physical-validation.md`、`docs/opencode-zen.md`、`docs/physical-resume-2026-10-16.md`、`docs/physical-test-resume.md`、`docs/voice-hold-fix.md`、`tests/loki-physical-evidence/summary.json` 和 `tests/release-0.6-evidence/receiver/metadata.json`。建议公开副本使用固定别名，并记录相应证据脱敏及哈希变化。报告不再重复序列号原文。 |
| 游戏画面与个性化信息 | `tests/release-0.8-evidence/game/` 含第三方游戏画面和存档名称；部分聊天截图、对话 JSON、攻略 ZIP 也属于真实服务验收产物。需决定公开哪些内容，并确认素材来源与分发范围；可改用合成场景或只公开必要摘要。 |

历史验收文件的数量较多，本轮保留其现有目录和证据链。若后续精简为公开仓库，应一起调整文档链接和校验清单，避免只删除图片或 JSON 后留下失效的证据引用。

## 历史清理范围复核

2026-10-20 补充对当前 `HEAD`、全部本地引用及提交作者/提交者元数据进行只读复核，未执行历史重写。

- 当前 `HEAD` 可达 96 个提交，其中 78 个提交的作者或提交者邮箱为公司域名。邮箱属于公开身份选择，建议公开前统一确认；本报告不记录完整地址。
- 当前提交历史中的开发机绝对路径涉及 32 个文件、34 个不同 blob 版本；内部 npm 镜像涉及 `app/package-lock.json` 的 2 个 blob 版本。当前工作树的修复不会替换这些历史 blob。
- 当前提交历史中的真机序列号涉及上表所列 7 个文件、17 个不同 blob 版本。若保留历史，应同时处理这些历史版本和当前文件。
- 全部本地引用的对象检查还发现 `refs/codex/turn-diffs/` 下的树快照，包含 `marketing/xiaohongshu-2026-10-19/` 的宣传视频、ZIP、录屏中间文件及日志。该目录不在当前 `HEAD`、本地普通分支或 remote-tracking 分支的可达对象中；它属于本地工具检查点，不应误算为普通分支的提交内容。明确推送选定分支不会自动包含这些树引用；公开迁移时不要镜像推送所有引用或分发整个本地 Git 数据目录。本轮未删除这些可恢复检查点。
- 补充对象检查覆盖 3401 个可达 blob，其中 2939 个为无 NUL 字节的候选文本；未发现私钥标记或除已知合成占位值外的常见凭据前缀。这不替代对视频、图片、压缩包内容或任意凭据格式的专项检查。

若公开仓库不需要旧开发历史，建议先完成当前文件中的设备标识和媒体清理，再以经复核的当前工作树建立新的公开仓库，原仓库保留私有历史。若需保留提交历史，应在独立副本中先备份并清理拟公开分支/标签的内容和身份元数据，再复核全部拟公开 refs；历史清理会改变提交 SHA，现有证据中的源码提交引用也需说明其与新历史的对应关系。

## 本轮验证

| 验证 | 结果 |
| --- | --- |
| 公共源安装 | Runtime、Native JS、App 使用单独的新 npm cache 完成 `npm ci --ignore-scripts`，实际获取依赖并校验锁文件 integrity；App 安全更新后再次执行 `npm ci` 成功。 |
| npm 审计 | 三模块均为 0 个已报告漏洞；App 更新后的 `npm audit` 再次核对为 0。 |
| JS / TypeScript | Runtime 405、Native JS 13、App Web 135，共 553 项通过；App 安全更新后重跑 135 项通过。集成 TypeScript 检查通过。 |
| 公开源检查 | `npm run verify:public` 通过；临时合成的开发机路径、内部 registry 和非公共 tarball 均被拒绝，随后恢复文件。该检查只负责源码可移植性，不替代历史、媒体和凭据审查。 |
| 证据完整性 | `scripts/check-physical-evidence.py` 通过：99 个文件、28 份接收报告的前缀哈希及连续事件序列通过。路径脱敏记录的原始/公开哈希已核对。 |
| Android 构建 | 根工程 `:app:assembleDebug :app:assembleDebugAndroidTest` 成功。 |
| Android 设备 | 专用 `thor_agent_dual_api36`，`emulator-5560`，Android 16 / API 36 / ARM64；上屏 display 0 / 1920×1080，下屏 display 4 / 1240×1080，均 369 dpi。 |
| WebView 仪器测试 | `ChatMarkdownDeviceTest`、`ChatToolImagePreviewTest` 共 2 项通过。Markdown 测试以 display 4 Context 加载真实 APK 资产/CSP，检查正常排版及恶意脚本/属性/远程图像；工具图片懒加载与标记回归通过。两项均只使用合成数据，不访问模型或产品数据库。 |
| 数据保留 | APK 使用 `install -r` 更新，没有清空应用数据或重建 AVD；仪器测试前后 Native shared preferences 的文件哈希一致。未对 Thor 真机执行安装或测试。 |
| 补丁检查 | `git diff --check` 通过；上游许可证文件保留其原始字节及自身空白格式。 |

本轮安装包：

| 文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 7969812 | `9306c665328124d9c815a2edddb5962dc12b7708d05d630c01aea504cc8191b2` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 4395557 | `3fd5c39d779da554e32e60ef0236b8b6593cc1929ad693b2ebf337fd39775845` |
