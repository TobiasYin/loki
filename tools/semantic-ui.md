# 上屏语义组件树、滚动与拖动

这组工具读取 Android Accessibility 实际暴露的组件信息，不会为了“补全”树自动滚动页面或执行节点动作。Canvas、游戏画面和未实现 AccessibilityNodeProvider 的自绘界面可能只给出空节点；这种情况下应使用截图辨识。

## 读取与查找

`perception.ui_tree` 保留旧参数，并新增可选参数：

| 参数 | 默认与范围 | 用途 |
|---|---|---|
| `windowId` | 当前唯一上屏应用窗口 | 指定窗口仍须属于当前观察到的应用 |
| `maxDepth` | 5，0–20 | 精简后的语义层级；另有实际遍历深度 32 的保护 |
| `maxNodes` | 80，1–128 | 返回节点数，包括必要的祖先上下文 |
| `maxChars` | 12000，1–12000 | 文本、描述、ID 等字段的字符预算 |
| `maxVisited` | 1024，1–2048 | 本次访问的原始 Accessibility 节点上限 |
| `query` | 空，最多 200 字符 | 在当前暴露的 ID、文本、描述和值中不区分大小写查找；先定位再截取命中附近文字 |
| `visibility` | `all`，或 `visible` / `offscreen` | 按 `visibleToUser` 过滤；`offscreen` 是 false，可能包括被遮挡节点，不能由此断言原因 |
| `offset` | 0，0–2048 | 当前匹配节点列表的分页偏移 |
| `textOffset` | 0，0–1000000 | 读取单个长 TextView 后面的文字；有 query 命中时优先定位命中位置 |

返回节点包含 `ref` / `nodeRef`、`parentRef`、`scrollAncestorRef`、`role`、`id`、`text`、`description`、`value`、`checkable` / `checked`、`selected`、`enabled`、`visibleToUser`、`scrollable`、`editable`、`actions`、屏幕逻辑 `bounds`，以及应用提供的 collection、collectionItem、range 信息。没有语义的布局包装层被裁掉，父节点指向最近保留的祖先；查询和分页会带上必要祖先，`matchesQuery` 标明是否匹配。密码节点的 ID、文本、描述和值不会进入查询语料或返回真实内容。

长文本返回 `textStart`、`textLength`、`textTruncated`；描述有对应起点与长度。搜索/偏移发生在实际暴露文字上，随后才截取输出。每字段最多保留 262144 个字符窗口，总扫描文本预算 524288；query 最多搜索每字段前 1000000 个字符。达到上限会说明原因，不能把截片当成整篇内容。

`pagination.nextOffset` 仅用于后续重新观察时继续读取当前暴露节点。每次读取都是新快照，列表变化可能改变顺序。若祖先上下文已填满小预算，返回 `blocked=true`、`nextOffset=null` 和增加预算提示，不能反复请求相同偏移。完整节点 JSON 另受保守 UTF-8 大小估计约束，节点合计不超过 45000 字节，为覆盖说明和外层结果保留空间，避免下游 64 KiB 限制截坏 JSON。`truncationReasons` 分别指出 `maxNodes`、`maxChars`、`maxOutputBytes`、`maxVisited`、`maxDepth`、文本扫描等原因。

覆盖范围以 `coverage` 为准：非可见但被应用暴露的节点可以读取；虚拟列表往往只提供当前已实例化内容。collection 的总行数不是完整行内容的证据。工具不能读取未暴露、未加载或隐藏的私密内容，也不把读取非可见文字当成实际已滚动或已点击。

## 一次滚动，再观察

```json
{"nodeRef":"当前树返回的滚动容器引用","direction":"forward","expectedPackage":"example.app"}
```

`input.scroll` 接受 `forward`、`backward`、`up`、`down`、`left`、`right`。方向必须是该节点公开支持的 Accessibility 滚动动作；up/down 可以使用相应 backward/forward 动作。节点须可见、启用且属于当前上屏窗口。不要把正文叶子节点当成容器，可以使用返回的 `scrollAncestorRef`。

若节点公开 `scroll_to_position` 且 collection 给出了有效行列边界，可使用：

```json
{"nodeRef":"当前容器引用","direction":"to_position","row":12,"column":0}
```

缺少行列信息、越界、未知动作、错误应用、旧快照、旧引用都会拒绝。引用绑定 Run/Session、快照、窗口、应用及刷新后的节点签名，不能仅凭相同 child path 复用。缓存只保留最近一次树实际返回的最多 128 个节点，最迟随观察 TTL（60 秒）回收；新观察、输入、Run 结束或提交滚动会使引用失效。

动作提交前再次检查权限、目标、节点刷新签名。提交后的自身内容回调不会被当成新输入授权：结果只说明 API 是否接受，不宣称页面已经移动；必须再次调用 ui_tree 或截图观察，获得新引用后才能继续。远端 IPC 可能已执行但返回异常时，结果保守标记为 unknown。

## 拖动滑块或滚动条

```json
{"points":[[200,400],[700,400]],"durationMs":600,"coordinateSpace":"display_logical","expectedPackage":"example.app"}
```

`input.drag` 使用与 swipe 相同的显式坐标契约：2–32 点，默认 500 毫秒、最长 5000 毫秒；`frame_pixels` 必须携带同一新鲜截图的 `frameId`。所有点都须处于当前显示与可触摸窗口内。读取到非可见节点的 bounds 不代表可以点击屏幕外坐标。

拖动是一条完整、有界的 down→move→up 手势，适用于原生滑块、可拖动滚动条等；它不提供 startHoldMs，也不建立持续手指链，不能保证依赖长按激活的拖放。取消或回调不确定时沿用既有触摸守卫，禁止把未确认释放写成成功。工具回调成功后仍须观察真实控件值或位置。

## 软件回归与官方参考

`SemanticTreeTest` 覆盖布局裁剪后的父子/滚动关系、非可见证据、密码查询隔离、长文尾部命中、文本窗口偏移、节点/字符/总字节预算、分页无进展保护、节点引用身份与过期，以及滚动自身内容变化后旧观察不可用于下一输入。Android 执行器在根工程 `:device-android` 与诊断工程 `:diagnostic` 共用实现。真机效果需要独立页面状态与工具事件同时验收，不能仅凭这些软件回归判通过。

实现依据 Android 官方 [AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo) 的节点属性、公开 actions 与 performAction，以及 [AccessibilityNodeProvider](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeProvider) 的应用提供语义树机制。应用提供的节点和当前可见像素是不同证据，须分别验证。
