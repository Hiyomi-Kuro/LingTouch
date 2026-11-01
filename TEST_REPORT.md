# 灵触 LingTouch 测试报告

生成时间：2026-11-01
应用：com.kaori.lingtouch / 灵触 1.0.0（Debug）
APK：`app-debug.apk` / `app/build/outputs/apk/debug/app-debug.apk`
SHA-256：`abfe310cad983c82f490ada9abce8620a2c00be528a08a2afff2c94df6c28e98`

## 1. 测试环境

| 项 | 实际值 |
| --- | --- |
| 设备 | vivo V2309A（真机，非模拟器） |
| 系统 | Android 14 / API 34 |
| ABI | arm64-v8a |
| 构建 | Gradle 9.0.0 + AGP 8.13.0 + Kotlin 2.1.0 + JDK 17 |
| SDK | compileSdk 36 / build-tools 36.0.0 / minSdk 30 / targetSdk 34 |
| 项目 | `/storage/emulated/0/AndroidIDEProjects/LingTouch`（非 Git 仓库） |

## 2. 构建与单元测试

执行命令：

```bash
JAVA_HOME=/data/data/com.tom.rv2ide/files/usr/lib/jvm/java-17-openjdk \
PATH=/data/data/com.tom.rv2ide/files/usr/lib/jvm/java-17-openjdk/bin:$PATH \
bash gradlew --no-daemon \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.tom.rv2ide/files/home/.androidide/aapt2 \
  :app:assembleDebug :app:testDebugUnitTest
```

结果：`BUILD SUCCESSFUL`，APK 已生成，单元测试 27 项全部通过。

| 测试类 | 数量 | 失败 | 覆盖内容 |
| --- | --- | --- | --- |
| ActionSchemaTest | 12 | 0 | JSON 动作解析、未知工具拒绝、必填参数、整数归一化、枚举与范围校验、自由文本拒绝 |
| ApiEndpointsTest | 7 | 0 | OpenCode Go / 通用 OpenAI / Anthropic / Responses 端点推导、`/v1` 不重复拼接 |
| MemoryKeywordsTest | 3 | 0 | 中文二元组、英文关键字、数量上限 |
| RiskPolicyTest | 5 | 0 | 发送 / 支付高风险识别、普通导航低风险、风险合并 |

APK 校验（`aapt2 dump badging`）：

- package：`com.kaori.lingtouch`
- versionName：`1.0.0`（versionCode 1）
- minSdkVersion：30
- targetSdkVersion：34
- application-label：灵触
- 文件大小：63,153,652 字节（约 60.2 MiB，含 ML Kit 中文 OCR 模型）

## 3. 真机测试结果

| 测试项 | 结果 | 证据 / 说明 |
| --- | --- | --- |
| APK 安装 | 通过 | `pm install -t` 成功；卸载后重新安装最终包成功 |
| 主界面启动 | 通过 | MainActivity / 权限引导页正常渲染，无崩溃（截图核对） |
| 首次权限向导 | 通过 | 5 个权限卡片与状态正常显示 |
| 底部页签切换 | 通过（修复后） | 首页 / 应用 / 记忆 / 设置均可正常渲染，无崩溃；修复了导航回调递归导致的 StackOverflowError |
| 应用扫描 | 通过 | 最终安装实测 725 个应用；早期安装曾被该 ROM 过滤为 1 个，已增加 `<queries>` 启动器 Activity 回退作为兜底 |
| 无障碍连接 | 通过 | 服务注册并连接（logcat：`[A11y] 无障碍服务已连接`），Agent 可读取 UI 快照并执行动作 |
| Shizuku 检测 / 授权 | 通过 | 检测到运行中的 Shizuku 且已授权；UserService uid=2000，能力探测 5 项（pm / am / dumpsys / settings 等） |
| 悬浮窗 | 通过 | 授予悬浮窗权限后悬浮球成功显示（截图核对） |
| 悬浮球去字 | 通过 | 悬浮球布局已移除“灵”字，保留纯色圆点与拖动 / 控制面板 |
| 后台高耗电入口 | 通过（界面渲染） | 设置页新增“后台高耗电（忽略电池优化）”状态与“去允许”按钮，页面正常渲染；系统豁免弹窗需用户手动确认 |
| 重复操作确认 | 已实现，待用户实机测试 | 连续 3 次相同动作时不再直接终止，改为弹出应用主页面确认对话框，按钮为“继续 / 停止”；不再发送通知栏确认 |
| 任务完成 / 失败提示 | 已实现，待用户实机测试 | 任务结束后自动把主页面带到前台并弹出结果对话框，可查看执行详情；不再依赖通知栏 |
| 最大执行步骤 | 已取消，待用户实机测试 | 移除执行步数上限及设置项；任务仅由完成 / 失败 / 手动停止 / 重复确认结束 |
| OpenCode API 客户端 | 通过（本地 mock） | 通过 `http://127.0.0.1:8765` 本地 OpenAI 兼容服务实测：`Authorization: Bearer ...`、模型列表、chat completions 均 200，响应被正确解析 |
| Agent 闭环 | 部分通过 | 实测连续执行 3 个任务：读取观察 → 调用 mock 模型 → 执行工具 → 写 task_steps → 达到最大步数后失败并落库 → 写入任务记忆。因测试时应用清单尚未加载完成，`launch_app` 返回“未找到应用”，该竞态已在最终代码修复（执行前按需扫描），但未再次做成功完成路径验证 |
| 任务停止 / 暂停 | 未实机验证 | 代码与 UI 已接通，未做设备交互验证 |
| 高风险确认 | 未实机验证 | 代码与通知 / 对话框已接通，未做设备交互验证 |
| 截图（无障碍） | 未实机验证 | 服务已启用 `canTakeScreenshot`，但未单独触发 |
| MediaProjection | 未测试 | 需用户在系统弹窗中授权 |
| OCR（中文 / 英文） | 未实机验证 | ML Kit 依赖已打包、构建通过；未在设备上执行识别 |
| 记忆增删改 / 导入导出 | 未实机验证 | 数据库与逻辑完成，单元测试覆盖关键字；未做 UI 交互验证 |
| 临时文件清理 | 未实机验证 | 每任务独立缓存目录与清理逻辑完成；测试任务后已重装清空数据 |
| 真实 OpenCode Go 调用 | 未验证 | 无用户 API Key；未伪造成功结果。仅验证了协议实现与本地兼容服务 |

## 4. 测试中发现并修复的问题

1. **AIDL 工具链不可用**：ACS SDK 自带 `aidl` 为 x86 二进制，在 arm64 设备上报 `Syntax error: "(" unexpected`。改为手写 Binder `onTransact` 协议，Shizuku UserService 不再依赖 AIDL 编译。
2. **`QUERY_ALL_PACKAGES` 在 vivo ROM 被过滤**：`getInstalledApplications()` 只返回本应用。增加 `<queries>` MAIN/LAUNCHER 的 `queryIntentActivities` 合并回退，实测 1 → 347 个应用。
3. **启动竞态**：Agent 可能在应用清单扫描完成前启动，导致 `launch_app` 找不到目标。已在 `launch_app` / `launch_activity` 执行前按需扫描。
4. **UI 编译问题**：Service 无 `layoutInflater`、AppCompat 与平台 `AlertDialog` 类型不一致、缺少 `Bundle` 导入等，均已修复。
5. **无障碍状态不刷新**：原先仅在恢复时读取，现改为 `AccessibilityBridge.connected` StateFlow 实时刷新首页 / 设置页状态。
6. **底部导航递归崩溃**：`showTab()` 在导航回调中再次 `setSelectedItemId()`，形成无限递归并触发 `StackOverflowError`，切换应用 / 记忆 / 设置必闪退；已加入回调抑制标志并实测四个页签正常。
7. **重复动作与空 matcher 保护**：补上空 matcher 防护与 nodeId 的索引回退，避免误点第一个节点。

## 5. 需要用户手动验证的项目

1. 打开灵触 → 设置 → 填入真实 OpenCode Go API Key → 加载模型 → 测试连接。
2. 在系统设置中手动启用“灵触手机操作”无障碍服务（应用不会、也不应自动改动你的其他无障碍服务）。
3. 授权悬浮窗、通知、按需授权屏幕录制。
4. 依次执行安全任务：`打开系统设置`、`打开设置并找到蓝牙页面`、`打开任意应用并读取当前页面`。
5. 验证高风险确认、停止 / 暂停、记忆编辑与导出、任务结束后的缓存清理。

## 6. 测试影响与清理

- 测试期间曾临时启用灵触的无障碍服务以便自动化验证；**用户原有的无障碍服务（SmartIsland、SlideIndex、GKD）均已保留并启用**，系统随后仍可能保留灵触自身的无障碍条目，如不需要可在系统设置中直接关闭；后续不再通过脚本改动该设置。
- 临时调试入口（`debug_api_key` / `debug_goal`）已在最终 APK 前移除，源码中已确认不存在。
- 设备上的测试包已卸载并安装最终 APK；应用数据不包含 mock 地址或测试密钥。
- 本地 mock 服务与临时文件位于开发机 `/tmp`，不在项目内，测试后已停止。
