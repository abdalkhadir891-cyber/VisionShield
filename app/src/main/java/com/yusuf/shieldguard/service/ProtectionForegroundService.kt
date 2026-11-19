package com.yusuf.shieldguard.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.yusuf.shieldguard.MainActivity
import com.yusuf.shieldguard.R
import com.yusuf.shieldguard.ai.VisualContentAnalyzer
import com.yusuf.shieldguard.capture.ScreenFrameCapturer
import com.yusuf.shieldguard.data.ProtectionSettings
import com.yusuf.shieldguard.data.ProtectionStateHolder
import com.yusuf.shieldguard.data.SettingsRepository
import com.yusuf.shieldguard.data.settingsDataStore
import com.yusuf.shieldguard.overlay.OverlayWindowController
import com.yusuf.shieldguard.perf.MemoryAndThermalGovernor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ProtectionForegroundService : Service(), ComponentCallbacks2 {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "shield_guard_protection_channel"
        private const val NOTIFICATION_ID = 4101
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val governor = MemoryAndThermalGovernor()

    private var mediaProjection: MediaProjection? = null
    private var frameCapturer: ScreenFrameCapturer? = null
    private var overlayController: OverlayWindowController? = null
    private var analyzer: VisualContentAnalyzer? = null
    private var coordinator: ScreenProcessingCoordinator? = null
    private var processingJob: Job? = null

    @Volatile
    private var latestSettings: ProtectionSettings = ProtectionSettings()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseProtectionPipeline()
            stopSelf()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    ProtectionStateHolder.setScreenInteractive(false)
                    coordinator?.clearAll()
                }
                Intent.ACTION_SCREEN_ON -> {
                    ProtectionStateHolder.setScreenInteractive(true)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }

        val repo = SettingsRepository(applicationContext.settingsDataStore)
        serviceScope.launch {
            repo.settingsFlow.collect { updated ->
                latestSettings = updated
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED

        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        releaseProtectionPipeline()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = projectionManager.getMediaProjection(resultCode, resultData)
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        mp.registerCallback(projectionCallback, mainHandler)
        mediaProjection = mp

        val overlay = OverlayWindowController(applicationContext).also {
            it.attachIfPermitted()
        }
        overlayController = overlay

        val capturer = ScreenFrameCapturer(applicationContext, mp, mainHandler)
        frameCapturer = capturer

        val localAnalyzer = VisualContentAnalyzer.createDefault(applicationContext)
        analyzer = localAnalyzer
        val healthText = localAnalyzer.getHealthReport().joinToString(" | ") { h ->
            "${h.name}: ${h.status.name}"
        }
        ProtectionStateHolder.updateModelHealthSummary(healthText)

        val coord = ScreenProcessingCoordinator(
            ownPackageName = packageName,
            capturer = capturer,
            analyzer = localAnalyzer,
            overlayRenderer = overlay
        )
        coordinator = coord

        ProtectionStateHolder.setForegroundServiceRunning(true)
        startProcessingLoop(coord)
        return START_NOT_STICKY
    }

    private fun startProcessingLoop(coord: ScreenProcessingCoordinator) {
        processingJob?.cancel()
        processingJob = serviceScope.launch {
            var tickCounter = 0L
            var currentAdjustment = governor.evaluate(
                governor.sampleDeviceResources(applicationContext),
                latestSettings.performanceProfile
            )

            while (isActive) {
                val settings = latestSettings
                val runtime = ProtectionStateHolder.state.value

                if (tickCounter % 6L == 0L) {
                    val snapshot = governor.sampleDeviceResources(applicationContext)
                    currentAdjustment = governor.evaluate(snapshot, settings.performanceProfile)
                }
                tickCounter++

                val outcome = try {
                    coord.processTick(settings, runtime, currentAdjustment)
                } catch (_: Throwable) {
                    TickOutcome.ANALYSIS_FAILED
                }
                delay(coord.nextDelayMs(outcome, settings))
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        frameCapturer?.invalidateForOrientationChange()
        coordinator?.clearAll()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            coordinator?.clearAll()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        coordinator?.clearAll()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun releaseProtectionPipeline() {
        processingJob?.cancel()
        processingJob = null

        coordinator?.clearAll()
        coordinator = null

        overlayController?.detach()
        overlayController = null

        frameCapturer?.release()
        frameCapturer = null

        analyzer?.close()
        analyzer = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Throwable) {}
        mediaProjection = null

        ProtectionStateHolder.setForegroundServiceRunning(false)
    }

    override fun onDestroy() {
        releaseProtectionPipeline()
        serviceScope.cancel()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
