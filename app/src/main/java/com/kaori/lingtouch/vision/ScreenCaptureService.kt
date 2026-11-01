package com.kaori.lingtouch.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kaori.lingtouch.ui.MainActivity
import com.kaori.lingtouch.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * MediaProjection fallback used only after the user grants screen-capture consent.
 * A single foreground service owns the projection so the agent can capture while
 * other apps are in front.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var width = 0
    private var height = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWithType()
        val resultCode = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data != null && projection == null) {
            initProjection(resultCode, data)
        }
        instance = this
        return START_NOT_STICKY
    }

    private fun startForegroundWithType() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initProjection(resultCode: Int, data: Intent) {
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val newProjection = manager.getMediaProjection(resultCode, data) ?: run {
                Logx.w("Capture", "getMediaProjection 返回 null")
                stopSelf()
                return
            }
            val metrics = resources.displayMetrics
            width = metrics.widthPixels
            height = metrics.heightPixels
            val density = metrics.densityDpi
            newProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Logx.i("Capture", "MediaProjection 已停止")
                    release()
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))

            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = newProjection.createVirtualDisplay(
                "lingtouch-capture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                null
            )
            reader = imageReader
            projection = newProjection
            Logx.i("Capture", "MediaProjection 已就绪 ${width}x$height")
        } catch (t: Throwable) {
            Logx.e("Capture", "初始化屏幕录制失败: ${t.message}")
            release()
            stopSelf()
        }
    }

    suspend fun captureBitmap(): Bitmap? = withContext(Dispatchers.IO) {
        val imageReader = reader ?: return@withContext null
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline) {
            val image = runCatching { imageReader.acquireLatestImage() }.getOrNull()
            if (image != null) return@withContext toBitmap(image)
            delay(90)
        }
        Logx.w("Capture", "MediaProjection 截图超时")
        null
    }

    private fun toBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            val result = Bitmap.createBitmap(padded, 0, 0, width, height)
            if (result !== padded) padded.recycle()
            result
        } catch (t: Throwable) {
            Logx.w("Capture", "转换截图失败: ${t.message}")
            null
        } finally {
            image.close()
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(com.kaori.lingtouch.R.string.settings_screenshot))
            .setContentText(getString(com.kaori.lingtouch.R.string.app_name))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, getString(com.kaori.lingtouch.R.string.settings_screenshot), NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun release() {
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        reader = null
        projection = null
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "lt_capture"
        private const val NOTIFICATION_ID = 4102
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "com.kaori.lingtouch.STOP_CAPTURE"

        @Volatile private var instance: ScreenCaptureService? = null

        val isReady: Boolean get() = instance?.projection != null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }

        suspend fun capture(context: Context): Bitmap? {
            val service = instance ?: return null
            if (service.projection == null) return null
            return service.captureBitmap()
        }
    }
}
