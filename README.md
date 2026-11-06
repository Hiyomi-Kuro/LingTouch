# 灵触 LingTouch

灵触是一个**本地优先**的 Android AI Agent：用户用自然语言下达任务，应用通过 OpenCode Go（OpenAI 兼容）接口调用大模型，结合无障碍服务、截图 / 端侧 OCR、Shizuku 系统能力，在真实手机上完成跨应用、多步骤操作。

- 应用名：灵触（LingTouch）
- 包名 / namespace：`com.kaori.lingtouch`
- 最低系统：Android 11（API 30）
- 目标 / 编译 SDK：34 / 36
- 形态：Kotlin + XML 视图 + Material 3 + 单 `app` 模块分包架构
- 版本：1.0（versionCode 1，对外版本保持不变）
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（根目录另有副本 `app-debug.apk`）
- 应用图标：由项目根目录 `icon.png` 生成各密度 PNG、圆形图标与自适应图标（`ic_launcher_full`）

## 0. 对话式首页（本次更新）

首页已改为类似 opencode 的对话界面：

- 顶部切换 **操控 / 对话 / 工作** 三种模式；三种模式各自保存历史与可编辑记忆。
- 模型 chip：可选「默认」（跟随设置中的默认模型）、OpenCode Go 实时 `/v1/models` 列表，或手动输入模型 ID；每个会话记住自己的选择。
- 工作目录 chip：通过系统 SAF 选择目录并持久授权；工作模式的所有读写都被限制在该目录内，`..` 会被拒绝。
- `@` 引用：工作模式引用文件内容（文本或 base64 二进制），操控模式引用应用，对话模式引用记忆。
- 消息区流式显示回答与完整 `reasoning_content` 思考过程（可折叠）；长按消息可复制。
- 历史按模式列出，可打开或删除；删除会话会级联删除消息。
- 导出当前会话为 Markdown，包含完整思考过程、工具步骤与模式记忆；任务详情页也可单独导出。
- 每个模式有一份可编辑的长期记忆，保存在 `mode_memory` 表并注入该模式的 system prompt。
- 操控模式每步自动截图并执行端侧 OCR，OCR 行返回 `bounds` 与屏幕坐标 `center`；视觉模型会附带截图。Unity/游戏/画布等无可读文字的界面，模型可直接用 OCR center 或截图坐标 `tap x/y` 定位按钮。

> 升级说明：首次启动新版会将「允许向模型发送截图」默认开启一次，以便操控模式能使用截图 + OCR 定位图标；可在设置中随时关闭。截图只在模型被模型目录标记为视觉模型（或设置了视觉模型）时才会附加，OCR 始终在端侧完成。

## 1. 已实现能力

| 模块 | 实现 |
| --- | --- |
| AI 闭环 | Goal → Decompose → Observe → Select Strategy → Act → Verify → Recover/Reflect → Complete；单步动作、严格 JSON Schema 校验、任务计划与终态校验、失败分类与循环保护；执行步数不设上限 |
| OpenCode Go API | 真实 HTTPS 调用；OpenAI Chat Completions / OpenAI Responses / Anthropic Messages / Gemini 四种协议；Bearer / x-api-key 认证；429、5xx 自动退避重试；401/403/404/400 分类错误提示；兼容 reasoning_content、SSE 与多协议返回，空响应自动提升 token 并给出结构诊断 |
| 模型目录 | 动态请求 `/v1/models`、本地缓存、搜索、切换默认 / 视觉模型、手动输入模型 ID、视觉能力识别、连接测试 |
| 密钥安全 | Android Keystore AES/GCM 加密后存入应用私有 SharedPreferences，不落日志、不导出 |
| Shizuku | 安装 / 运行 / 授权 / Binder 断开检测、授权请求、UserService 以 shell（uid 2000）或 root（uid 0）执行**白名单**系统命令（pm / am / dumpsys / settings），能力探测 |
| 无障碍 | 读取窗口与节点树、文本 / resourceId / 坐标定位、点击、长按、滑动、滚动、输入、返回 / 桌面 / 最近任务、等待界面、状态校验 |
| 截图 | 无障碍 `takeScreenshot` 优先，MediaProjection 前台服务兜底（用户显式授权） |
| OCR | ML Kit 端侧中文模型（同时识别英文），返回文本与坐标；完全离线 |
| 应用管理 | 扫描第三方与系统应用（`QUERY_ALL_PACKAGES`，仅本地使用）、图标、版本、Activity / 导出状态、按名称或包名搜索、启动应用 / Activity、安装卸载监听 |
| 记忆 | SQLite 五张表（apps / tasks / task_steps / memories / experiences）；自动学习、可信度、使用次数、应用版本与界面签名、错误类型与恢复方法、手动增删改、按应用 / 类型 / 关键字检索、JSON 导出导入；写入与导出统一脱敏 |
| 悬浮窗 | 可拖动纯色悬浮球（无文字）＋只读状态面板；不提供任何悬浮窗按钮，暂停 / 继续 / 停止统一在任务页操作 |
| 清理 | 每任务独立缓存目录，成功 / 失败 / 取消 / 超时 / 异常统一清理；设置项默认开启；立即清理缓存 |
| 权限 | 首次启动权限向导，各项独立授权；拒绝非核心权限不崩溃 |

## 1.1 当前构建与验证状态

- 构建：`:app:assembleDebug` 与 `:app:testDebugUnitTest` 通过（BUILD SUCCESSFUL，退出码 0），114 项单元测试全部通过。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`，包名 `com.kaori.lingtouch`，versionName `1.0`，versionCode `1`，大小与 SHA-256 见 `TEST_REPORT.md`。
- 本次未做实机安装、真实 API 调用与跨应用执行测试；这些由用户本人完成（见文末清单）。
- 历史实测（上一版本）：安装、四页签、无障碍、Shizuku、悬浮窗、本地 mock API 均已验证；应用列表 725 个。
- 完整记录见 `TEST_REPORT.md`。

## 1.2 自主执行引擎（本次增强）

- 计划：`Goal → Decompose → Observe → Select Strategy → Act → Verify → Recover/Reflect → Complete`；`TaskPlan/SubGoal` 支持 PENDING / RUNNING / SUCCEEDED / FAILED / BLOCKED / SKIPPED，简单任务单步执行，复杂任务可附加 `plan` 并随时重规划。
- 观察：`ScreenState`（前台包名、Activity、尺寸 / 旋转、界面签名、关键文字、弹窗 / 键盘 / 可输入 / 可滚动）每步重建，禁止复用旧节点索引或坐标。
- 校验：`Verifier` 独立读取真实 UI 校验完成条件；`finish_task` 的 `evidence` 只作说明，无法校验时要求用户确认或拒绝结束（`CompletionGate`）。
- 提交状态：DISPATCHED / OBSERVED_CHANGED / VERIFIED_SUCCESS / UNCHANGED / FAILED / UNKNOWN 分别记录。
- 恢复：结构化错误（TARGET_NOT_FOUND、STALE_NODE、NO_UI_CHANGE、UI_TIMEOUT、APP_NOT_FOREGROUND、PERMISSION_DENIED、VISION_UNAVAILABLE、MODEL_FORMAT_ERROR、NETWORK_ERROR、RATE_LIMIT、LOOP_DETECTED、INVALID_ARGS、HIGH_RISK_DENIED 等）对应等待 / 重观察 / 换定位 / 重新规划 / 询问用户 / 中止。
- 循环保护：连续相同动作、界面长期不变、页面来回切换三类检测；首次要求改变策略，再次询问用户，避免无限消耗额度。
- 成本与上下文：API 请求超时可配，单步工具超时可配；记录真实 usage Token 与压缩后的估算 Token；分层压缩上下文（约束、计划、最近步骤、错误、界面），压缩不会丢失用户禁止事项。
- 记忆：经验记录应用版本与界面签名，版本变化时下调权重并提示重新确认；记录错误类型与恢复方法；写入 / 导出前统一脱敏（密码、验证码、Token、输入文本）。
- 悬浮窗：仅保留可拖动状态球与只读状态面板，悬浮窗按钮已删除；暂停 / 继续 / 停止统一在任务页操作。

## 2. 构建

在 Android Code Studio 的终端中：

```bash
cd /storage/emulated/0/AndroidIDEProjects/LingTouch

JAVA_HOME=/data/data/com.tom.rv2ide/files/usr/lib/jvm/java-17-openjdk \
PATH=/data/data/com.tom.rv2ide/files/usr/lib/jvm/java-17-openjdk/bin:$PATH \
bash gradlew --no-daemon \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.tom.rv2ide/files/home/.androidide/aapt2 \
  :app:assembleDebug :app:testDebugUnitTest
```

构建环境实际版本：Gradle 9.0.0、AGP 8.13.0、Kotlin 2.1.0、JDK 17、compileSdk 36、build-tools 36.0.0。

## 3. 安装

1. 用文件管理器打开 `app-debug.apk`（或项目根目录 `app-debug.apk`）安装。
2. 首次安装需允许“安装未知应用”。
3. 打开灵触，按权限向导逐项授权。

## 4. 配置 OpenCode Go

设置 → **OpenCode Go API**：

- Provider：`OpenCode Go`
- Base URL：`https://opencode.ai/inference/go`
- API Key：在 [OpenCode Console](https://opencode.ai/console) 订阅 Go 后创建的 Key。密钥只保存在本机 Keystore 加密存储中。
- 协议：`OpenAI Chat Completions`（OpenCode Go 对文本模型做了协议转换，Chat Completions 可用）
- 模型：点击「加载模型」从 `/v1/models` 实时获取，或手动填写，例如 `glm-5.3-flash`、`deepseek-v4.1-flash`、`kimi-k3`。
- 视觉模型：选择支持图片输入的模型后，可打开「允许向模型发送截图」。

实际请求：

```
POST https://opencode.ai/inference/go/openai/v1/chat/completions
Authorization: Bearer <key>
x-opencode-session: <stable session id>
User-Agent: LingTouch/1.0 (Android AI agent)
```

模型列表：`GET https://opencode.ai/inference/go/v1/models`。

> 模型 ID 与可用性会随官方调整；应用不写死模型列表，始终以实时接口为准。部分模型有地区限制（如 DeepSeek 需在 OpenCode 隐私设置中选择 Global）。

> 若「测试连接」提示“模型返回为空（HTTP 200）”：应用已兼容推理模型（`reasoning_content`）、SSE 流与 Anthropic/Responses 兼容返回，并在输出被截断时自动提升 token 上限重试；错误信息会附带 `finish_reason` 与响应字段名。DeepSeek 仍受地区限制，需在 OpenCode Console → Settings → Privacy → Regions 选择 Global。

## 5. 权限说明

| 权限 | 用途 | 拒绝影响 |
| --- | --- | --- |
| 无障碍服务 | 读取 UI、点击、滑动、输入、截图 | 无法执行任何界面操作 |
| 悬浮窗 | 跨应用悬浮球 | 仅影响悬浮助手 |
| 通知 | 任务进度、停止按钮 | 无法从通知栏停止任务 |
| MediaProjection | 无障碍截图失败时的兜底 | 安全窗口仍无法截图 |
| Shizuku | 启动未导出 Activity、查询系统信息 | 回退到普通应用权限 |
| 后台高耗电 / 忽略电池优化 | 保证长任务不被系统省电策略中断 | 系统可能随时冻结后台任务 |
| `QUERY_ALL_PACKAGES` | 枚举系统应用与第三方应用 | 只能看到部分应用 |

**QUERY_ALL_PACKAGES 说明**：灵触的核心功能是“用户指定任意应用执行任务”，必须完整枚举本机应用（含系统应用）。该权限仅用于本机应用列表、启动与记忆归类，不上传、不分发、不用于广告。若需上架 Google Play，需按该权限政策单独申报或改用 `<queries>` 白名单。

## 6. 使用示例

- `打开系统设置`
- `打开设置并找到蓝牙页面`
- `打开微信，读取当前页面内容`
- `在设置里找到关于手机，查看 Android 版本`

执行过程中：

- 首页 / 任务页实时显示步骤、结果与错误；
- 高风险操作（删除、支付、发送消息、安装卸载等）会弹出应用主页面确认对话框；确认与任务完成提示均不在通知栏显示；
- 悬浮球可随时暂停、继续或停止；
- 停止后立即释放截图、手势与网络请求，任务结束自动清理临时文件。

## 7. 架构

```
com.kaori.lingtouch
├── ui/             Activity、Controller、RecyclerView Adapter
├── agent/          AgentEngine 闭环、Prompt、观察构建、确认、通知、前台服务
├── api/            OpenCode Go 客户端、协议、模型目录、端点推导、错误映射
├── tools/          动作 Schema、校验、执行器
├── accessibility/  无障碍服务、UI 树、节点匹配、手势、截图
├── shizuku/        Shizuku 状态、UserService Binder、白名单命令
├── vision/         截图管线、MediaProjection 服务与授权页
├── ocr/            OCR 接口与 ML Kit 实现
├── apps/           应用 / Activity 清单与启动
├── memory/         SQLite 实体、DAO、记忆学习与检索
├── overlay/        悬浮球与控制面板
├── storage/        任务临时资源生命周期
├── security/       Keystore 加密存储
├── settings/       配置与 StateFlow
└── util/           日志脱敏、JSON、格式化
```

### 执行循环

1. `AgentEngine` 校验 API Key、模型与无障碍状态；
2. 创建任务记录，启动前台服务；
3. 每一步读取 UI 树（必要时截图 / OCR），构建 JSON 观察；
4. 调用模型得到**一个** JSON 动作；
5. `ActionParser` + `ToolRegistry` 校验动作与参数；
6. `RiskPolicy` 判定风险，高风险挂起等待用户确认；
7. `ToolExecutor` 调用真实 Android API，记录结果与耗时；
8. 失败可重试一次；连续 3 次相同动作会弹出应用主页面询问“继续 / 停止”，由你决定；
9. `finish_task` 必须提供可验证证据（前台包名 / 界面文字 / evidence）；
10. 结束：写数据库、提炼记忆、清理临时文件、停止前台服务。

### 数据库

`lingtouch.db`（版本 1，显式迁移钩子）：

- `apps`：包名、名称、是否系统应用、版本、最后发现时间、归档标记
- `tasks`：目标、状态、计划、总结、起止时间、步数
- `task_steps`：动作、参数、结果、耗时
- `memories`：类型（APP/TASK/GENERAL）、包名、标题、内容、是否验证、使用 / 成功 / 失败次数
- `experiences`：触发条件、方法、步骤、可信度、成功 / 失败次数

记忆学习会**移除输入文本与用户隐私内容**，只保留动作名与结构性目标。

## 8. 隐私与安全

- API Key 使用 Android Keystore 不可导出密钥加密；日志统一脱敏（Bearer / sk- / Authorization）。
- 截图与 OCR 只在应用私有缓存中处理；任务结束删除。
- 不绕过锁屏、系统确认、身份验证或应用保护；不隐藏操作、不持续监视。
- 模型只能调用白名单工具；不存在“模型输出 shell 命令直接执行”的通道。
- Shizuku 命令由 `ShellCommand` 构造并校验包名 / 类名 / 设置键，模型无法注入任意参数。
- 记忆导出为本地 JSON，由用户通过系统文件选择器决定保存位置。

## 9. 已知限制

- 依赖模型能力：复杂任务的成功率取决于所选模型的规划与 JSON 遵循能力；默认使用 `glm-5.3-flash`。
- 安全窗口（银行、部分支付 / 密码界面）禁止截图，应用会返回明确失败原因，不会绕过。
- Shizuku 需用户自行安装并启动服务；ADB 模式重启后需要重新启动 Shizuku。
- MediaProjection 授权通常在进程重启后失效，需要重新授权。
- ML Kit 中文模型随 APK 打包，APK 体积较大（约 30–40 MB）。
- 未实现原生函数调用（function calling），统一使用 JSON 动作协议以兼容更多模型。
- 未做 Release 签名与混淆；当前交付 Debug APK。
- 已取消最大执行步骤数：任务只会在完成、失败、API 错误、你手动停止或重复操作确认时结束；长时间任务会持续消耗 API 额度，请随时停止。
- APK SHA-256：`371b6610d3e1fa106e28593652c1e813d8f0bc9badc24cd74ea5f46f0dfa8cfd`。
- 不同厂商 ROM 的权限与无障碍实现差异会影响个别控件；若 ROM 过滤 `QUERY_ALL_PACKAGES`，应用列表会自动使用启动器 Activity 回退。
- 应用不会修改或关闭用户已有的其他无障碍服务；灵触的无障碍服务需用户在系统设置中手动启用。
- 英文界面覆盖主要静态文案，部分动态提示仍为中文。

## 10. 参考与许可

- [Shizuku API](https://github.com/RikkaApps/Shizuku-API)（Apache-2.0）：授权、UserService 机制；本项目使用其公开 API，未复制其示例代码。
- [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)：端侧 OCR。
- [OkHttp](https://square.github.io/okhttp/)（Apache-2.0）、[Material Components](https://github.com/material-components/material-components-android)（Apache-2.0）、AndroidX（Apache-2.0）。
- [OpenCode Go 文档](https://opencode.ai/v2/docs/console/go/)：接口地址、模型 ID、认证方式以此为准。

本项目代码为独立实现。参考项目的 README、演示截图或功能未被视为本项目已实现功能。

## 11. 目录约定

- `AGENTS.md`：项目管理规范（工作目录、临时文件、构建与安全规则）。
- `.tmp/`：开发期临时日志 / 脚本 / 截图，可随时整体删除；不参与构建。
- `app/src/main/`：正式源码与资源。
- `app/build/outputs/apk/debug/`：构建产物。

详细测试记录见 `TEST_REPORT.md`。
