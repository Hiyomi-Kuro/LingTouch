# AGENTS.md — 灵触 LingTouch 项目管理规范

本文件是本项目唯一的长期开发契约。任何自动化代理、开发者或工具在本项目中执行任务前，必须先完整阅读本文件。

## 1. 项目身份

- 中文名称：灵触
- 英文名称：LingTouch
- Application ID / namespace：`com.kaori.lingtouch`
- 项目根目录（唯一允许的正式开发目录）：`/storage/emulated/0/AndroidIDEProjects/LingTouch/`
- 开发环境：Android 手机上的 Android Code Studio（ACS）
- 语言与形态：Kotlin、原生 Android SDK、XML 视图、Material 风格、单 `app` 模块 + 分包架构
- 构建工具：Gradle Wrapper（版本以 `gradle/wrapper/gradle-wrapper.properties` 为准）
- 最低支持系统：Android 10（API 29）；编译 SDK 以 `app/build.gradle` 为准
- 最终产物：`app/build/outputs/apk/debug/app-debug.apk`，并复制一份到项目根目录 `app-debug.apk`

项目路径不得迁移。禁止在其它目录建立影子项目替代正式项目。

## 2. 工作目录限制

1. 默认所有开发操作只能在本项目根目录及其子目录内进行。
2. 未经用户明确授权，不得修改其它项目、其它应用源码或手机上无关文件。
3. 允许读取构建依赖、官方文档等必要外部资源，但不得自行修改无关目录。
4. 若用户明确指定其它工作目录或项目内子目录，以该次任务的明确授权范围为准。
5. 不得用外部副本（含桌面/Ubuntu 副本）替代本机项目进行修改后再回拷。

## 3. 临时文件管理

- 临时目录：`/storage/emulated/0/AndroidIDEProjects/LingTouch/.tmp/`
- 以下内容必须放入 `.tmp/`，不得堆在项目根目录或源码目录：
  - 临时脚本、临时下载文件、构建/运行日志与退出码
  - 调试截图、OCR 中间图、临时测试输出
  - 中间 JSON、网络请求调试结果、临时分析报告
  - 一次性代码实验、失败产物、备份文件
- 建议按任务建立独立子目录（如 `.tmp/logs/`、`.tmp/scripts/`、`.tmp/captures/`），任务结束可整体清理。
- 允许保留构建系统约定目录：Gradle 缓存、`build/` 中间产物、IDE 必需文件；不得为“整洁”强行搬迁而损坏项目。
- 正式源码、配置、文档、数据库结构、测试代码、最终 APK 不属于垃圾，不得放入 `.tmp`。
- `.gitignore` 必须忽略 `.tmp/`，但本文件的临时文件规则仍然有效。
- 不得把用户数据、源码、正式 APK、密钥或需要保留的测试证据当作垃圾删除。

## 4. 文件修改原则

- 修改前必须先读取现有内容，确认其职责与调用方。
- 不随意覆盖正常代码；优先修复已有模块，而不是重复创建新模块。
- 不建立大量重复、无用的文件；不保留未使用的依赖。
- 删除文件前判断是否为项目必需文件（Manifest、Gradle、资源、AIDL、测试等）。
- 每次修改后保持 Gradle 项目结构完整、可编译。
- 不得修改用户未授权的其它文件。
- 不得伪造构建结果、运行结果、权限状态、API 响应或测试结论。

## 5. 开发执行流程

每次开发任务按以下顺序执行：

1. 读取本文件。
2. 检查项目当前状态（目录、Git 状态、相关源码、构建配置）。
3. 明确本次修改涉及的模块与影响面（搜索全部调用点）。
4. 实施真实代码修改。
5. 执行必要验证：先做针对性编译，最后执行完整 Debug 构建与单元测试。
6. 读取真实错误日志，针对性修复，重复构建直到通过或确认外部阻塞。
7. 清理本次产生的临时垃圾（保留必要的测试证据）。
8. 总结完成情况、未完成项与已知限制。

除非遇到无法自行解决的权限、凭据或用户交互限制，否则不得只停留在规划文档阶段。

## 6. 架构与工程约定

包结构（`com.kaori.lingtouch`）：

- `ui/`：界面（Activity、Adapter、ViewModel）
- `agent/`：AI Agent 闭环（Observe → Plan → Act → Verify → Reflect）
- `api/`：OpenCode Go / OpenAI 兼容 API 客户端与模型目录
- `tools/`：动作定义、校验、执行与结果
- `accessibility/`：无障碍服务、UI 树、手势、截图
- `shizuku/`：Shizuku 授权、UserService、系统能力
- `vision/`：截图、图像处理、坐标映射
- `ocr/`：OCR 抽象与 ML Kit 实现
- `apps/`：应用与 Activity 管理
- `memory/`：SQLite 数据库、实体、DAO、经验检索
- `overlay/`：悬浮窗与前台服务
- `storage/`：临时资源管理与清理
- `security/`：Keystore 加密与敏感数据
- `settings/`：应用配置

要求：

1. 尽量减少不必要依赖；不引入 ACS 无法编译的原生依赖。
2. 避免过度设计与庞大单文件；单文件建议不超过约 600 行，超长按职责拆分。
3. 网络与数据库操作不得阻塞主线程，统一使用协程。
4. 正确处理 Activity/Service 生命周期，避免泄漏；注册与反注册成对出现。
5. 处理配置变化、权限撤销、网络断开、用户中止、进程回收。
6. 关键错误必须可定位：结构化日志，禁止输出完整 API Key 或用户隐私。
7. 防止无限循环：最大步骤数、最大重试次数、重复动作检测、超时。
8. 核心能力应可独立验证，优先提供纯逻辑单元测试。

## 7. 安全与隐私

- API Key 仅保存在应用私有存储，使用 Android Keystore 加密；禁止硬编码、禁止提交 Git、禁止完整写入日志/截图/导出。
- 高风险动作（删除数据、支付转账、发送消息、卸载安装、修改安全设置）必须获得用户明确确认。
- 禁止绕过锁屏、系统安全确认、身份验证或应用保护；禁止用悬浮窗遮盖系统安全提示。
- 禁止把模型自由文本当作 shell 命令执行；所有动作必须经过 Schema 校验与白名单。
- 任务截图/OCR 中间产物只存应用私有缓存，任务结束自动清理。
- 记忆保存前去除无关隐私数据；卸载应用时记忆归档而非删除。

## 8. 构建与验证

- 优先使用 `bash gradlew ...`（`/storage/emulated/0` 上可能无执行权限）。
- JDK 17：`/data/data/com.tom.rv2ide/files/usr/lib/jvm/java-17-openjdk`。
- 标准最终验证命令：

```bash
JAVA_HOME=/data/data/com.tom.rv2ide/files/usr/lib/jvm/java-17-openjdk \
PATH=/data/data/com.tom.rv2ide/files/usr/lib/jvm/java-17-openjdk/bin:$PATH \
bash gradlew --no-daemon \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.tom.rv2ide/files/home/.androidide/aapt2 \
  :app:assembleDebug :app:testDebugUnitTest
```

- 长构建必须后台执行并写入 `.tmp/logs/`，通过退出码与日志判断结果；禁止并发运行同一项目的多个 Gradle 构建。
- 构建成功后必须确认 APK 文件真实存在且非空，并记录大小、包名、版本、最低 API。
- `testDebugUnitTest NO-SOURCE` 表示没有匹配测试源，不等于测试通过，必须如实报告。

## 9. 交付

- 最终交付：可安装 Debug APK、完整源码、README、测试报告、本文件。
- 报告必须如实区分：已实测、仅构建验证、需用户实机验证。
- 不提交、不推送 Git，除非用户明确要求。
- 任务完成后提醒用户 `.tmp/` 可整体删除。
