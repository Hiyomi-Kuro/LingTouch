package com.kaori.lingtouch.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.kaori.lingtouch.BuildConfig
import com.kaori.lingtouch.util.Logx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

data class ShellResult(
    val ok: Boolean,
    val exitCode: Int,
    val output: String,
    val error: String? = null
)

data class ShizukuState(
    val installed: Boolean = false,
    val running: Boolean = false,
    val permission: Boolean = false,
    val version: Int = 0,
    val uid: Int = -1,
    val root: Boolean = false,
    val shellReady: Boolean = false,
    val capabilities: List<String> = emptyList(),
    val message: String = "",
    val sui: Boolean = false
)

/**
 * Real Shizuku integration: binder lifecycle, runtime permission, UserService binding
 * with shell identity, and capability probing. Nothing is reported as granted unless
 * Shizuku itself confirms it.
 */
class ShizukuManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(readInitial())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val bindMutex = Mutex()
    @Volatile private var shell: LingShellClient? = null
    @Volatile private var started = false
    private var connection: ServiceConnection? = null

    fun start() {
        if (started) return
        started = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() = refresh()
            })
            Shizuku.addBinderDeadListener(object : Shizuku.OnBinderDeadListener {
                override fun onBinderDead() = handleBinderDead()
            })
            Shizuku.addRequestPermissionResultListener(object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) { refresh() }
            })
        }.onFailure { Logx.w("Shizuku", "注册监听失败: ${it.message}") }
        refresh()
    }

    fun refresh() {
        scope.launch {
            val ping = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            var version = 0
            var granted = false
            if (ping) {
                version = runCatching { Shizuku.getVersion() }.getOrDefault(0)
                granted = runCatching {
                    !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
            }
            val installed = isPackageInstalled(SHIZUKU_PACKAGE) || isPackageInstalled(SUI_PACKAGE) || ping
            _state.update {
                it.copy(
                    installed = installed,
                    running = ping,
                    permission = granted,
                    version = version,
                    message = when {
                        !installed -> "未安装 Shizuku / Sui"
                        !ping -> "Shizuku 服务未运行，请先启动 Shizuku"
                        !granted -> "Shizuku 已运行，尚未授权灵触"
                        else -> "Shizuku 已授权"
                    }
                )
            }
            if (ping && granted) {
                if (ensureService()) probeCapabilities()
            }
        }
    }

    fun requestPermission(): String {
        val ping = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!ping) return "Shizuku 服务未运行，请先启动 Shizuku"
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) return "Shizuku 版本过低，请升级"
        return runCatching {
            Shizuku.requestPermission(REQUEST_CODE)
            "已发送授权请求，请在 Shizuku 弹窗中允许"
        }.getOrElse { "请求授权失败：${it.message}" }
    }

    fun shouldShowRationale(): Boolean =
        runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)

    suspend fun ensureService(): Boolean {
        val existing = shell
        if (existing != null && existing.isAlive()) return true
        return bindMutex.withLock {
            val current = shell
            if (current != null && current.isAlive()) {
                return@withLock true
            }
            val ping = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            if (!ping) return@withLock false
            val granted = runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (!granted) return@withLock false

            val deferred = CompletableDeferred<Boolean>()
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder == null) {
                        if (!deferred.isCompleted) deferred.complete(false)
                        return
                    }
                    shell = LingShellClient(binder)
                    if (!deferred.isCompleted) deferred.complete(true)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    shell = null
                    if (!deferred.isCompleted) deferred.complete(false)
                    _state.update { it.copy(shellReady = false, capabilities = emptyList()) }
                }
            }
            connection = conn
            try {
                Shizuku.bindUserService(userServiceArgs(), conn)
                val ok = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() } ?: false
                if (!ok) {
                    Logx.w("Shizuku", "UserService 绑定失败或超时")
                    runCatching { connection?.let { Shizuku.unbindUserService(userServiceArgs(), it, true) } }
                }
                ok
            } catch (t: Throwable) {
                Logx.e("Shizuku", "绑定 UserService 异常: ${t.message}")
                false
            }
        }
    }

    suspend fun exec(command: String): ShellResult {
        if (command.length > 4096) return ShellResult(false, -1, "", "命令过长")
        if (!ensureService()) return ShellResult(false, -1, "", "Shizuku 未连接或未授权")
        val service = shell ?: return ShellResult(false, -1, "", "UserService 未连接")
        return withContext(Dispatchers.IO) {
            try {
                parse(service.exec(command))
            } catch (t: Throwable) {
                shell = null
                Logx.w("Shizuku", "执行命令失败: ${t.message}")
                ShellResult(false, -1, "", "执行失败：${t.message}")
            }
        }
    }

    suspend fun startActivity(pkg: String, className: String): ShellResult {
        val command = ShellCommand.amStart(pkg, className)
            ?: return ShellResult(false, -1, "", "包名或 Activity 名不合法")
        return exec(command)
    }

    suspend fun dumpsysPackage(pkg: String): ShellResult {
        val command = ShellCommand.dumpsysPackage(pkg)
            ?: return ShellResult(false, -1, "", "包名不合法")
        return exec(command)
    }

    fun isReady(): Boolean = shell != null && _state.value.permission

    private suspend fun probeCapabilities() {
        val uidResult = exec(ShellCommand.id())
        val uid = uidResult.output.trim().toIntOrNull() ?: -1
        val capabilities = mutableListOf<String>()
        if (uid == 0) capabilities.add("Root 身份（uid 0）") else if (uid > 0) capabilities.add("ADB Shell 身份（uid $uid）")
        if (exec("command -v pm").ok) capabilities.add("应用包管理（pm）")
        if (exec("command -v am").ok) capabilities.add("启动 Activity（am）")
        if (exec("command -v dumpsys").ok) capabilities.add("系统与 Activity 查询（dumpsys）")
        if (exec("command -v settings").ok) capabilities.add("系统设置读写（settings）")
        _state.update {
            it.copy(
                uid = uid,
                root = uid == 0,
                shellReady = uid >= 0,
                capabilities = capabilities,
                message = if (uid >= 0) "Shizuku 已连接（uid $uid）" else it.message
            )
        }
        Logx.i("Shizuku", "能力探测完成 uid=$uid ${capabilities.size} 项")
    }

    private fun handleBinderDead() {
        shell = null
        _state.update {
            it.copy(
                running = false,
                permission = false,
                shellReady = false,
                capabilities = emptyList(),
                uid = -1,
                message = "Shizuku 连接已断开"
            )
        }
        Logx.w("Shizuku", "Binder 已断开")
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(context.packageName, LingTouchShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private fun parse(raw: String): ShellResult {
        val newline = raw.indexOf('\n')
        val head = if (newline >= 0) raw.substring(0, newline) else raw
        val body = if (newline >= 0) raw.substring(newline + 1) else ""
        return when {
            head.startsWith("EXIT:") -> {
                val code = head.removePrefix("EXIT:").trim().toIntOrNull() ?: -1
                ShellResult(code == 0, code, body, if (code == 0) null else "exit=$code")
            }
            head.startsWith("ERROR:") -> ShellResult(false, -1, body, head.removePrefix("ERROR:"))
            else -> ShellResult(true, 0, raw)
        }
    }

    private fun readInitial(): ShizukuState {
        val ping = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val installed = isPackageInstalled(SHIZUKU_PACKAGE) || isPackageInstalled(SUI_PACKAGE) || ping
        return ShizukuState(
            installed = installed,
            running = ping,
            message = when {
                !installed -> "未安装 Shizuku / Sui"
                !ping -> "Shizuku 服务未运行"
                else -> "Shizuku 已运行"
            }
        )
    }

    private fun isPackageInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SUI_PACKAGE = "rikka.sui"
        const val REQUEST_CODE = 4101
        const val BIND_TIMEOUT_MS = 20_000L
    }
}
