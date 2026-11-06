# 灵触 LingTouch 测试报告

生成时间：2026-11-06（对话式首页更新）
应用：`com.kaori.lingtouch` / 灵触 1.0（Debug，versionCode 1）
APK：`app/build/outputs/apk/debug/app-debug.apk`，根目录副本 `app-debug.apk`
APK 大小：63,446,749 字节（约 61.9 MiB）
SHA-256：`371b6610d3e1fa106e28593652c1e813d8f0bc9badc24cd74ea5f46f0dfa8cfd`

## 1. 测试环境

| 项 | 实测值 |
| --- | --- |
| 构建主机 | Android Code Studio（手机内构建） |
| 项目路径 | `/storage/emulated/0/AndroidIDEProjects/LingTouch` |
| JDK | 17（`/data/data/com.tom.rv2ide/files/usr/lib/jvm/java-17-openjdk`） |
| Gradle / AGP / Kotlin | 9.0.0 / 8.13.0 / 2.1.0 |
| SDK | compileSdk 36 / build-tools 36.0.0 / minSdk 30 / targetSdk 34 |
| aapt2 | `-Pandroid.aapt2FromMavenOverride=/data/data/com.tom.rv2ide/files/home/.androidide/aapt2` |

## 2. 构建与单元测试

实际执行命令：

```bash
cd /storage/emulated/0/AndroidIDEProjects/LingTouch
JAVA_HOME=/data/data/com.tom.rv2ide/files/usr/lib/jvm/java-17-openjdk \
PATH=/data/data/com.tom.rv2ide/files/usr/lib/jvm/java-17-openjdk/bin:$PATH \
bash gradlew --no-daemon \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.tom.rv2ide/files/home/.androidide/aapt2 \
  :app:assembleDebug :app:testDebugUnitTest
```

结果：最终构建（对话式首页）`BUILD SUCCESSFUL in 1m 42s`，退出码 **0**；`:app:assembleDebug` 与 `:app:testDebugUnitTest` 同次通过，114 项单元测试全部通过。

| 测试类 | 数量 | 失败 | 覆盖内容 |
| --- | --- | --- | --- |
| ActionSchemaTest | 12 | 0 | JSON 动作解析、未知工具拒绝、必填参数、整数归一化、枚举与范围校验、自由文本拒绝 |
| ApiEndpointsTest | 7 | 0 | OpenCode Go / 通用 OpenAI / Anthropic / Responses 端点推导、`/v1` 不重复拼接 |
| ChatResponseParserTest | 16 | 0 | 标准 JSON、content 数组、reasoning-only、SSE 流、错误包裹、Responses/Anthropic/Gemini 回退、结构诊断 |
| SseStreamParserTest | 7 | 0 | OpenAI/Anthropic/Responses/Gemini SSE 增量、推理内容、usage、错误包裹、[DONE] |
| ChatExporterTest | 3 | 0 | 导出包含完整思考过程/步骤/记忆、元数据容错、文件名按模式生成 |
| WorkFileToolsTest | 4 | 0 | SAF 相对路径归一化、拒绝 `..` 逃逸、文本替换计数、二进制 NUL 检测 |
| MemoryKeywordsTest | 3 | 0 | 中文二元组、英文关键字、数量上限 |
| RiskPolicyTest | 5 | 0 | 发送 / 支付高风险识别、普通导航低风险、风险合并 |
| ScrollSemanticsTest | 6 | 0 | up→BACKWARD、down→FORWARD、左右方向、别名、大小写与空白容错 |
| CompletionGateTest | 6 | 0 | 已验证完成、失败子目标拦截、不可验证需用户确认、零动作拒绝 |
| ContextCompressorTest | 6 | 0 | 用户约束保留、旧步骤压缩、错误保留、安全规则注入、Token 估算、约束抽取 |
| LoopDetectorTest | 7 | 0 | 连续相同动作、界面停滞、页面来回切换、动作切换复位、reset |
| TaskBudgetTest | 6 | 0 | 请求计数、真实 usage、估算标记、失败类型分类、提交状态文案 |
| TaskPlanTest | 9 | 0 | 单子目标计划、状态推进、字符串/对象/嵌套计划解析、非法计划、重规划、进度文案 |
| VerifierTest | 10 | 0 | 文字/包名/Activity/选中态校验、无证据 UNKNOWN、节点树缺失 UNKNOWN、textAbsent、merge |
| RedactorTest | 7 | 0 | 输入文本隐藏、敏感键屏蔽、密码/验证码/Bearer/sk- 掩码、JSON 脱敏、敏感字段识别 |
| **合计** | **114** | **0** | 以上全部通过 |

`testDebugUnitTest` 为真实执行（非 NO-SOURCE），测试报告位于 `app/build/test-results/testDebugUnitTest/`。

## 3. APK 校验

`aapt2 dump badging` 实测：

- package：`com.kaori.lingtouch`
- versionCode：`1`
- versionName：`1.0`
- minSdkVersion：`30`
- targetSdkVersion：`34`
- 文件存在且非空：63,446,749 字节
- 根目录副本 `app-debug.apk` 与构建产物 SHA-256 一致

### 3.1 应用图标

- 图标来源：项目根目录 `icon.png`（1254×1254，不透明方形图）。
- 已生成并打包：`mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi` 的 `ic_launcher.png`（48/72/96/144/192）、`ic_launcher_round.png`（圆形裁剪）、`ic_launcher_full.png`（自适应背景 108/162/216/324/432）。
- 自适应图标：`mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml` 使用全幅 `ic_launcher_full` 作为 background、透明 foreground，兼容圆形/圆角/方形启动器遮罩。
- 校验：APK 内 `res/mipmap-xxxhdpi-v4/ic_launcher*.png` 的 SHA-256 与生成源文件逐一一致；`aapt2 dump badging` 的 `application-icon-*` 均指向 `mipmap-anydpi-v26/ic_launcher.xml`。
- 应用名称仍为“灵触”，包名、versionName 1.0、versionCode 1 均未变化。

## 4. 数据库与现有功能兼容性

- 数据库版本从 1 升级到 2，仅使用**增量迁移**：为 `experiences` 增加 `error_type`、`recovery`、`ui_signature`、`app_version` 四列；迁移前先检查列是否存在，不删除、不清空任何旧数据。
- 旧表 `apps` / `tasks` / `task_steps` / `memories` 结构不变；旧任务、步骤、记忆、配置、API Key 与任务历史全部保留。
- 设置项新增 `tool_timeout`（默认 45 秒），旧 SharedPreferences 无需迁移，读取时使用默认值。
- 既有 OpenCode Go 协议、模型目录、Shizuku、无障碍、OCR、截图、悬浮球、应用扫描、权限引导均保留；悬浮窗只删除按钮，服务与状态球仍在。
- 任务步骤中不再明文保存输入文本与密码类参数；历史记录不做破坏性改写，导出时统一脱敏。

## 5. 本次实现与修复摘要

1. **P0 滚动方向**：`scroll(up)` 与 `scroll(down)` 原都映射到 `ACTION_SCROLL_BACKWARD`；现 up/backward→BACKWARD，down/forward→FORWARD，左右仍使用对应 Action，并回读界面是否变化。
2. **P0 完成校验**：删除“模型自述 evidence 即成功”的逻辑；`finish_task` 必须给出 `expect_text` / `expect_package` / `expect_activity` / `expect_checked` 等可验证条件，由 `Verifier` 读取真实 UI 校验；无法验证时进入用户确认或拒绝结束。
3. **P0 循环检测**：由“整任务累计次数”改为连续相同动作、界面长期不变、两页面来回切换三类检测；首次要求模型改变策略，再次明确询问用户继续或停止，并给出原因。
4. **P0 取消与风险**：并发启动保护、暂停后不再提交新模型请求/手势、停止释放协程与临时资源；用户拒绝高风险操作后严格终止该任务，不允许换路径绕过。
5. **任务计划**：新增 `TaskPlan/SubGoal` 与 PENDING/RUNNING/SUCCEEDED/FAILED/BLOCKED/SKIPPED；支持模型附加 `plan` 并重规划，简单任务保持单步执行。
6. **ScreenState 与验证**：每步重建前台包名、Activity、尺寸/旋转、界面签名、关键文字、弹窗/键盘等；动作提交状态分为 DISPATCHED / OBSERVED_CHANGED / VERIFIED_SUCCESS / UNCHANGED / FAILED / UNKNOWN。
7. **失败恢复**：新增结构化错误类型与恢复策略（等待、重观察、换定位、重新规划、询问用户、中止），网络/限流带可取消退避。
8. **预算与上下文**：新增 API 请求超时、单步工具超时配置；记录真实 usage Token 与估算 Token；分层压缩上下文（约束、计划、最近步骤、错误、界面），压缩不丢失用户禁止事项。
9. **记忆增强**：经验记录应用版本、界面签名、错误类型与恢复方法；版本变化时降低权重并提示重新确认；写入/导出统一脱敏。
10. **UI**：删去悬浮窗按钮（仅保留状态球与只读状态面板）；任务页显示计划、Token 预算、新的状态机状态；设置页新增单步工具超时。
11. **版本**：对外 versionName 固定为 `1.0`，versionCode 保持 `1`。

### 5.1 OpenCode Go 空响应（HTTP 200）修复

本次针对 DeepSeek V4.1 Flash 测试连接报“模型返回为空（HTTP 200）”做了真实修复：

1. **输出预算过低**：测试连接原 `max_tokens=24`，推理型模型可能把额度全部用于 reasoning，最终 content 为空。现测试连接使用 256，遇到 reasoning-only 或 `finish_reason=length` 时自动提升到 1024（Agent 请求 1600 → 4096）重试一次。
2. **响应格式兼容**：新增 `ChatResponseParser`，支持标准 OpenAI JSON、content 数组、`reasoning_content`、SSE `data:` 流、OpenAI Responses、Anthropic/Gemini 兼容返回、`data/response/result` 包裹与错误包裹；并显式发送 `"stream": false`。
3. **诊断信息**：HTTP 200 但仍无法取到最终文本时，错误信息包含 `keys`、`message` 字段名与 `finish_reason`（不含正文），便于判断是长度截断、推理模型还是服务端错误包裹。
4. **单测**：新增 `ChatResponseParserTest` 16 项，覆盖上述格式；总测试数 114，全部通过。

> 仍无法绕过账号/地区限制：DeepSeek 需在 OpenCode Console → Settings → Privacy → Regions 选择 Global；若返回错误包裹，应用现在会直接显示服务端错误信息。

## 6. 本次验证边界（如实说明）

| 类别 | 状态 | 说明 |
| --- | --- | --- |
| 编译通过 | 是 | `:app:assembleDebug` 成功，APK 真实存在且非空 |
| 自动测试通过 | 是 | 114 项 JVM 单元测试全部通过；无设备/仪器测试 |
| 真实 API 调用 | 未验证 | 未使用用户 API Key，未向真实 OpenCode Go 发送请求 |
| 实机安装 / 授权 | 未验证 | 按要求由用户本人完成，本次未安装 APK、未改动任何系统权限 |
| 跨应用任务执行 | 未验证 | 未运行真实跨应用任务；计划/校验/恢复逻辑仅通过纯逻辑单测 |
| OCR / 截图 / Shizuku | 未验证 | 依赖实机授权与真实屏幕，属于用户实测项 |

## 7. 用户实机测试清单

1. 安装 `app-debug.apk`（覆盖安装，不要卸载，以保留旧数据）；确认设置页显示版本 `1.0`。
2. 打开灵触 → 设置 → 填入真实 OpenCode Go API Key → 加载模型 → 测试连接。
3. 在系统设置中手动启用“灵触手机操作”无障碍服务（应用不会自动改动其他无障碍服务）。
4. 授权悬浮窗、通知，按需授权屏幕录制。
5. 依次执行安全任务：
   - `打开系统设置`
   - `打开设置并找到蓝牙页面`（验证 scroll up/down 方向与页面校验）
   - `打开任意应用并读取当前页面`
6. 验证任务页：计划进度、步骤的“已提交/已观察变化/已验证成功”状态、Token 显示、暂停/继续/停止。
7. 验证高风险确认：触发一次发送/删除类操作，拒绝后任务必须停止，不得继续执行同一操作。
8. 验证完成校验：让模型声称完成但界面不匹配时，应提示“完成验证未通过”，而不是直接成功。
9. 验证循环保护：构造重复点击场景，第一次应要求改变策略，再次应询问继续/停止。
10. 验证悬浮窗：只有状态球与只读状态面板，没有任何悬浮窗按钮；轻触球展开/收起状态。
11. 验证记忆：任务结束后查看记忆是否新增；检查导出 JSON 中不含密码、验证码、Token 或输入原文。
12. 验证数据库兼容：覆盖安装后旧任务、旧记忆、API Key 仍在。

## 8. 临时文件与清理

- 本次构建日志与退出码位于 `.tmp/logs/`，属于临时文件，可整体删除。
- 未在项目根目录或源码目录留下临时脚本、截图或实验 JSON。
- 正式源码、APK、README、AGENTS.md 与本报告均保留在项目内。

## 9. 结论

- **编译通过**：`:app:assembleDebug` 成功，APK 已生成并校验。
- **自动测试通过**：114 项单元测试 0 失败。
- **待用户实机验证**：真实 API、权限授权、OCR/截图、跨应用任务、暂停/停止与高风险确认。

## 10. 对话式首页更新（2026-11-06）

- 首页改为 opencode 风格对话界面：操控 / 对话 / 工作三模式，模式独立历史与可编辑记忆。
- 模型选择：默认（跟随设置）、OpenCode Go 实时模型列表、手动输入；会话记住模型。
- 工作目录：SAF 持久授权；工作模式所有文件操作限制在授权目录，拒绝 `..`。
- @ 引用：工作模式文件、操控模式应用、对话模式记忆。
- 思考过程：流式 `reasoning_content` 实时显示并可折叠；导出 Markdown 包含完整思路、工具步骤与记忆。
- 历史：按模式列出、打开、删除（级联删除消息）。
- 操控模式视觉定位：每步自动截图 + OCR；OCR 返回屏幕坐标 center；视觉模型附带截图，Unity/游戏可用坐标点击。
- 本报告仅记录构建与 JVM 单测；安装、真实 API、无障碍/截图/OCR、跨应用执行仍需用户实机验证。
