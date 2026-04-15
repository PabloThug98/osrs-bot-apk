package com.osrsbot.autotrainer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.osrsbot.autotrainer.antiban.AntiBanManager
import com.osrsbot.autotrainer.antiban.StopReason
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.overlay.OverlayManager
import com.osrsbot.autotrainer.scripts.*
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.Logger
import com.osrsbot.autotrainer.utils.ScriptInfo
import kotlinx.coroutines.*
import kotlin.random.Random

class BotService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService(): BotService = this@BotService
    }

    private val binder = LocalBinder()
    private var overlay: OverlayManager? = null
    private var botJob: Job? = null
    private var config = BotConfig()
    private var startTimeMs = 0L
    private var onBreak = false

    var isRunning = false
        private set

    var statusListener: ((String, Boolean) -> Unit)? = null

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification("Idle"))
        overlay = OverlayManager(applicationContext)
        Logger.ok("BotService created.")
    }

    fun updateConfig(newConfig: BotConfig) {
        config = newConfig
    }

    fun showOverlay() {
        overlay?.show(onStart = { startBot() }, onStop = { stopBot() })
    }

    fun hideOverlay() {
        overlay?.hide()
    }

    fun startBot() {
        if (isRunning) return
        val accessService = OSRSAccessibilityService.instance
        if (accessService == null) {
            Logger.error("Accessibility service not connected.")
            statusListener?.invoke("Accessibility service not enabled", false)
            return
        }
        isRunning = true
        onBreak = false
        startTimeMs = System.currentTimeMillis()
        Logger.ok("Bot starting: ${ScriptInfo.name(config.scriptId)}")
        statusListener?.invoke("Running", true)

        val antiBan = AntiBanManager(config)
        val detector = ObjectDetector(accessService)

        val script: BotScript = when (config.scriptId) {
            "woodcutting" -> WoodcuttingScript(accessService, config, antiBan, detector)
            "fishing"     -> FishingScript(accessService, config, antiBan, detector)
            "combat"      -> CombatScript(accessService, config, antiBan, detector)
            else          -> ChocolateDustScript(accessService, config, antiBan, detector)
        }

        script.onStart()

        botJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {

                val stopReason = antiBan.checkAutoStop(startTimeMs, script.actions)
                if (stopReason != null) {
                    withContext(Dispatchers.Main) {
                        Logger.warn("Auto-stop triggered: $stopReason")
                        stopBot()
                    }
                    break
                }

                if (antiBan.shouldBreak()) {
                    onBreak = true
                    val jitter = Random.nextLong(-5000L, 5000L)
                    val breakMs = config.breakDurationMs + jitter
                    withContext(Dispatchers.Main) {
                        updateOverlayStats(script, antiBan, "Break")
                        statusListener?.invoke("On Break", true)
                    }
                    delay(breakMs.coerceAtLeast(1000L))
                    antiBan.resetBreakCounter()
                    onBreak = false
                    withContext(Dispatchers.Main) { statusListener?.invoke("Running", true) }
                }

                if (!onBreak) {
                    script.tick()
                    withContext(Dispatchers.Main) {
                        updateOverlayStats(script, antiBan, "Running")
                        updateNotification(script)
                    }
                }
            }
        }
    }

    fun stopBot() {
        if (!isRunning) return
        isRunning = false
        botJob?.cancel()
        botJob = null
        Logger.warn("Bot stopped.")
        statusListener?.invoke("Idle", false)
        updateNotification(null)
    }

    private fun updateOverlayStats(script: BotScript, antiBan: AntiBanManager, status: String) {
        val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000L
        val h = elapsed / 3600; val m = (elapsed % 3600) / 60; val s = elapsed % 60
        val runtime = "%02d:%02d:%02d".format(h, m, s)
        val gpHr = if (elapsed > 0) (script.gpGained / (elapsed / 3600.0)).toInt() else 0
        overlay?.updateStats(
            script = ScriptInfo.name(config.scriptId),
            actions = script.actions,
            xp = script.xpGained,
            gpHr = gpHr,
            runtime = runtime,
            status = status,
            currentAction = script.currentAction,
        )
    }

    private fun updateNotification(script: BotScript?) {
        val text = if (script != null)
            "${ScriptInfo.name(config.scriptId)} | ${script.actions} actions"
        else "Idle"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("OSRS Bot")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "OSRS Bot Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Running status" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onDestroy() {
        stopBot()
        overlay?.hide()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "osrs_bot_channel"
    }
}
