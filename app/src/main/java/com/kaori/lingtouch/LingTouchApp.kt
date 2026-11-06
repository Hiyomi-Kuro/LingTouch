package com.kaori.lingtouch

import android.app.Application
import android.content.Context
import com.kaori.lingtouch.agent.AgentEngine
import com.kaori.lingtouch.agent.ConfirmationBroker
import com.kaori.lingtouch.agent.Notifications
import com.kaori.lingtouch.agent.ObservationBuilder
import com.kaori.lingtouch.api.ModelCatalog
import com.kaori.lingtouch.api.OpenCodeClient
import com.kaori.lingtouch.apps.AppRepository
import com.kaori.lingtouch.memory.LingTouchDb
import com.kaori.lingtouch.memory.MemoryRepository
import com.kaori.lingtouch.ocr.MlKitOcrEngine
import com.kaori.lingtouch.ocr.OcrEngine
import com.kaori.lingtouch.security.SecureStore
import com.kaori.lingtouch.settings.SettingsRepository
import com.kaori.lingtouch.shizuku.ShizukuManager
import com.kaori.lingtouch.storage.TempResourceManager
import com.kaori.lingtouch.tools.ToolExecutor
import com.kaori.lingtouch.util.Logx
import com.kaori.lingtouch.vision.ScreenCaptureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LingTouchApp : Application() {

    lateinit var secureStore: SecureStore
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var db: LingTouchDb
        private set
    lateinit var memory: MemoryRepository
        private set
    lateinit var temp: TempResourceManager
        private set
    lateinit var api: OpenCodeClient
        private set
    lateinit var catalog: ModelCatalog
        private set
    lateinit var apps: AppRepository
        private set
    lateinit var ocr: OcrEngine
        private set
    lateinit var capture: ScreenCaptureManager
        private set
    lateinit var shizuku: ShizukuManager
        private set
    lateinit var confirmation: ConfirmationBroker
        private set
    lateinit var tools: ToolExecutor
        private set
    lateinit var observations: ObservationBuilder
        private set
    lateinit var agent: AgentEngine
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        secureStore = SecureStore(this)
        settings = SettingsRepository(this, secureStore)
        db = LingTouchDb(this)
        memory = MemoryRepository(db)
        temp = TempResourceManager(this)
        api = OpenCodeClient(settings)
        catalog = ModelCatalog(settings, api)
        apps = AppRepository(this, db, memory)
        ocr = MlKitOcrEngine()
        capture = ScreenCaptureManager(this, temp)
        shizuku = ShizukuManager(this)
        confirmation = ConfirmationBroker()
        tools = ToolExecutor(this, apps, shizuku, ocr, capture, temp, settings, confirmation)
        observations = ObservationBuilder(this, capture, ocr, settings, catalog)
        agent = AgentEngine(
            this, settings, db, memory, apps, shizuku, api, catalog,
            capture, ocr, temp, tools, observations, confirmation
        )
        Notifications.createChannels(this)
        shizuku.start()
        apps.startObserving()
        scope.launch {
            runCatching { apps.scan() }.onFailure { Logx.w("App", "启动扫描应用失败: ${it.message}") }
            runCatching { temp.pruneOrphans(emptySet()) }
            runCatching { markInterruptedTasks() }
        }
    }

    private fun markInterruptedTasks() {
        db.recentTasks(50).filter { it.status == "RUNNING" }.forEach { task ->
            Logx.w("App", "任务 ${task.id} 在上次进程结束时未完成，标记为失败")
            db.finishTask(task.id, "FAILED", "上次执行被系统中断（进程被回收）", false, task.stepCount)
        }
    }

    companion object {
        lateinit var instance: LingTouchApp
            private set

        fun get(context: Context): LingTouchApp = context.applicationContext as LingTouchApp
    }
}
