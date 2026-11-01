package com.kaori.lingtouch.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.kaori.lingtouch.accessibility.AccessibilityBridge
import com.kaori.lingtouch.memory.AppEntity
import com.kaori.lingtouch.memory.LingTouchDb
import com.kaori.lingtouch.memory.MemoryRepository
import com.kaori.lingtouch.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Installed-application and launchable-activity inventory. Uses QUERY_ALL_PACKAGES
 * (declared in the manifest with justification) so system apps are included.
 */
class AppRepository(
    private val context: Context,
    private val db: LingTouchDb,
    private val memory: MemoryRepository
) {
    private val pm: PackageManager = context.packageManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()
    private val activityCache = mutableMapOf<String, List<ActivityEntry>>()
    private val iconCache = mutableMapOf<String, Bitmap?>()
    private var receiver: BroadcastReceiver? = null

    fun cached(): List<InstalledApp> = _apps.value

    suspend fun scan(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val result = ArrayList<InstalledApp>(256)
        val flags = PackageManager.GET_META_DATA
        val installed = runCatching { pm.getInstalledApplications(flags) }.getOrDefault(emptyList())
        // Some OEM ROMs filter getInstalledApplications despite QUERY_ALL_PACKAGES.
        // Launcher activities declared in <queries> remain visible, so merge them in.
        val launcherApps = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).mapNotNull { it.activityInfo?.applicationInfo }
        }.getOrDefault(emptyList())
        val merged = LinkedHashMap<String, android.content.pm.ApplicationInfo>()
        installed.forEach { merged[it.packageName] = it }
        launcherApps.forEach { merged.putIfAbsent(it.packageName, it) }
        Logx.i("Apps", "PM installed=${installed.size} launcher=${launcherApps.size} merged=${merged.size}")
        val packages = merged.values
        packages.forEach { info ->
            val pkg = info.packageName
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
            val isSystem = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val version = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull()
            val icon = iconCache.getOrPut(pkg) {
                runCatching { info.loadIcon(pm).toBitmap(96, 96) }.getOrNull()
            }
            result.add(InstalledApp(pkg, label, isSystem, version, icon))
        }
        result.sortWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
        _apps.value = result
        runCatching {
            db.upsertApps(result.map { AppEntity(packageName = it.packageName, label = it.label, isSystem = it.isSystem, versionName = it.versionName) })
        }
        Logx.i("Apps", "扫描到 ${result.size} 个应用（${System.currentTimeMillis() - start}ms）")
        result
    }

    fun resolve(query: String): InstalledApp? {
        val q = query.trim()
        if (q.isBlank()) return null
        val apps = _apps.value
        return apps.firstOrNull { it.packageName.equals(q, ignoreCase = true) }
            ?: apps.firstOrNull { it.label.equals(q, ignoreCase = true) }
            ?: apps.firstOrNull { it.packageName.contains(q, ignoreCase = true) }
            ?: apps.firstOrNull { it.label.contains(q, ignoreCase = true) }
    }

    fun findByPackage(pkg: String): InstalledApp? = _apps.value.firstOrNull { it.packageName == pkg }

    suspend fun activities(pkg: String): List<ActivityEntry> = withContext(Dispatchers.IO) {
        activityCache[pkg]?.let { return@withContext it }
        val list = ArrayList<ActivityEntry>()
        runCatching {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            info.activities?.forEach { activity ->
                val name = activity.name ?: return@forEach
                list.add(
                    ActivityEntry(
                        packageName = pkg,
                        className = name,
                        label = runCatching { activity.loadLabel(pm).toString() }.getOrDefault(name.substringAfterLast('.')),
                        exported = activity.exported,
                        launcher = isLauncherActivity(pkg, name),
                        permission = activity.permission
                    )
                )
            }
        }.onFailure { Logx.w("Apps", "读取 $pkg Activity 失败: ${it.message}") }
        list.sortWith(compareByDescending<ActivityEntry> { it.launcher }.thenByDescending { it.exported }.thenBy { it.label })
        activityCache[pkg] = list
        list
    }

    private fun isLauncherActivity(pkg: String, cls: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        return runCatching {
            pm.queryIntentActivities(intent, 0).any { it.activityInfo.name == cls }
        }.getOrDefault(false)
    }

    suspend fun launchApp(query: String): LaunchOutcome = withContext(Dispatchers.Main) {
        val app = resolve(query) ?: return@withContext LaunchOutcome(false, "未找到应用：$query")
        val intent = pm.getLaunchIntentForPackage(app.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val outcome = start(intent)
            return@withContext outcome.copy(message = if (outcome.success) "已启动 ${app.label}" else outcome.message)
        }
        val launcher = activities(app.packageName).firstOrNull { it.launcher } ?: activities(app.packageName).firstOrNull { it.exported }
        if (launcher == null) return@withContext LaunchOutcome(false, "${app.label} 没有可启动的 Activity")
        launchComponent(app.packageName, launcher.className)
    }

    suspend fun launchComponent(pkg: String, className: String): LaunchOutcome = withContext(Dispatchers.Main) {
        val app = findByPackage(pkg) ?: return@withContext LaunchOutcome(false, "未找到应用：$pkg")
        val entry = activities(pkg).firstOrNull { it.className == className }
        if (entry != null && !entry.exported) {
            return@withContext LaunchOutcome(false, "该 Activity 未导出，Android 不允许外部应用启动")
        }
        val intent = Intent().setComponent(ComponentName(pkg, className))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val outcome = start(intent)
        outcome.copy(message = if (outcome.success) "已启动 ${app.label} / ${className.substringAfterLast('.')}" else outcome.message)
    }

    private fun start(intent: Intent): LaunchOutcome {
        return try {
            context.startActivity(intent)
            LaunchOutcome(true, "已发送启动请求", intent.component?.flattenToShortString())
        } catch (e: SecurityException) {
            LaunchOutcome(false, "系统拒绝启动（需要额外权限）：${e.message}")
        } catch (e: android.content.ActivityNotFoundException) {
            LaunchOutcome(false, "Activity 不存在或未导出")
        } catch (e: Exception) {
            LaunchOutcome(false, "启动失败：${e.message}")
        }
    }

    fun isForeground(pkg: String): Boolean = AccessibilityBridge.foregroundPackage() == pkg

    fun startObserving() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action
                val removedPackage = intent?.data?.schemeSpecificPart
                if (action == Intent.ACTION_PACKAGE_REMOVED && removedPackage != null) {
                    val existing = _apps.value.firstOrNull { it.packageName == removedPackage }
                    if (existing != null) {
                        // Keep memories; archive the app row so history survives uninstall/reinstall.
                        scope.launch {
                            db.upsertApps(
                                listOf(
                                    AppEntity(
                                        packageName = existing.packageName,
                                        label = existing.label,
                                        isSystem = existing.isSystem,
                                        versionName = existing.versionName,
                                        archived = true
                                    )
                                )
                            )
                        }
                    }
                }
                if (intent?.getBooleanExtra(Intent.EXTRA_REPLACING, false) == true) return
                activityCache.clear()
                scope.launch { scan() }
            }
        }
        receiver = r
        runCatching {
            ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { Logx.w("Apps", "注册应用变更监听失败: ${it.message}") }
    }

    fun stopObserving() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    fun memoryFor(pkg: String) = memory.forPackage(pkg)
}
