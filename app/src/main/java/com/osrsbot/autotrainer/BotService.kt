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
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.overlay.OverlayManager
import com.osrsbot.autotrainer.scripts.*
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.Logger
import com.osrsbot.autotrainer.utils.ScriptInfo
import com.osrsbot.autotrainer.walker.WalkerManager
import kotlinx.coroutines.*
import kotlin.random.Random

class BotService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService(): BotService = this@BotService
    }

    private val binder    = LocalBinder()
    private var overlay: OverlayManager? = null
    private var botJob: Job? = null
    private var config    = BotConfig()
    private var startTimeMs = 0L
    private var onBreak   = false
    private var recoveryCount = 0

    var isRunning = false
        private set

    var statusListener: ((String, Boolean) -> Unit)? = null

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    /**
     * Return START_STICKY so the OS automatically restarts BotService
     * if it is killed under memory pressure while the bot is running.
     * LifecycleService does not guarantee this without an explicit override.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
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
        getSharedPreferences("osrsbot", MODE_PRIVATE)
            .edit()
            .putString("script", newConfig.scriptId)
            .apply()
    }

    fun showOverlay() {
        overlay?.show(onStart = { startBot() }, onStop = { stopBot() })
    }

    fun hideOverlay() { overlay?.hide() }

    // ── Start bot ─────────────────────────────────────────────────────────────
    fun startBot() {
        if (isRunning) return
        val accessService = OSRSAccessibilityService.instance
        if (accessService == null) {
            Logger.error("Accessibility service not connected.")
            statusListener?.invoke("Accessibility service not enabled", false)
            return
        }
        isRunning   = true
        onBreak     = false
        recoveryCount = 0
        startTimeMs = System.currentTimeMillis()
        Logger.ok("Bot starting: ${ScriptInfo.name(config.scriptId)}")
        statusListener?.invoke("Running", true)

        val antiBan  = AntiBanManager(config)
        val detector = ObjectDetector(accessService)

        val script: BotScript = when (config.scriptId) {
            "woodcutting" -> {
                val wc = WoodcuttingScript(accessService, config, antiBan, detector)
                val (treeL, bankL) = walkerLocationsFor(config.walkerArea)
                wc.treeLocation = treeL
                wc.bankLocation = bankL
                if (treeL != null) Logger.ok("Walker: ${bankL} → ${treeL}")
                wc
            }
            "fishing"     -> FishingScript(accessService, config, antiBan, detector)
            "combat"      -> CombatScript(accessService, config, antiBan, detector)
            else          -> ChocolateDustScript(accessService, config, antiBan, detector)
        }

        script.onStart()

        botJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {

                // ── Auto-stop check ──────────────────────────────────────────
                val stopReason = antiBan.checkAutoStop(startTimeMs, script.actions)
                if (stopReason != null) {
                    withContext(Dispatchers.Main) {
                        Logger.warn("Auto-stop triggered: $stopReason")
                        stopBot()
                    }
                    break
                }

                // ── Break check ──────────────────────────────────────────────
                if (antiBan.shouldBreak()) {
                    onBreak = true
                    val breakMs = config.breakDurationMs + Random.nextLong(-5_000L, 5_000L)
                    withContext(Dispatchers.Main) {
                        updateOverlayStats(script, antiBan, "On Break")
                        statusListener?.invoke("On Break", true)
                    }
                    delay(breakMs.coerceAtLeast(1_000L))
                    antiBan.resetBreakCounter()
                    onBreak = false
                    withContext(Dispatchers.Main) { statusListener?.invoke("Running", true) }
                }

                if (!onBreak) {
                    // ── Stuck detection ──────────────────────────────────────
                    // Runs BEFORE tick() so we can recover before the next tick.
                    if (script.isStuck()) {
                        withContext(Dispatchers.Main) {
                            Logger.warn("BotService: calling onStuck() for ${script.name}")
                            statusListener?.invoke("Stuck — recovering…", true)
                        }
                        recoveryCount++
                        script.onStuck()
                        delay(1_000L)   // brief pause so recovery doesn't loop instantly
                        if (recoveryCount >= MAX_RECOVERIES_PER_RUN) {
                            withContext(Dispatchers.Main) {
                                Logger.error("Too many recoveries in one run — stopping bot to prevent bad clicks.")
                                statusListener?.invoke("Stopped after repeated recovery", false)
                                stopBot()
                            }
                            break
                        }
                    }

                    // ── Normal tick ──────────────────────────────────────────
                    try {
                        script.tick()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        recoveryCount++
                        withContext(Dispatchers.Main) {
                            Logger.error("Script error in ${script.name}: ${e.message ?: e::class.java.simpleName}")
                            statusListener?.invoke("Script error — recovering…", true)
                        }
                        script.onStuck()
                        delay(1_500L)
                        if (recoveryCount >= MAX_RECOVERIES_PER_RUN) {
                            withContext(Dispatchers.Main) {
                                Logger.error("Too many script errors in one run — stopping bot.")
                                statusListener?.invoke("Stopped after repeated errors", false)
                                stopBot()
                            }
                            break
                        }
                    }
                    withContext(Dispatchers.Main) {
                        updateOverlayStats(script, antiBan, "Running")
                        updateNotification(script)
                    }
                }
            }
        }
    }

    // ── Stop bot ──────────────────────────────────────────────────────────────
    fun stopBot() {
        if (!isRunning) return
        isRunning = false
        botJob?.cancel()
        botJob = null
        Logger.warn("Bot stopped.")
        statusListener?.invoke("Idle", false)
        updateNotification(null)
    }

    // ── Walker area resolver ──────────────────────────────────────────────────
    private fun walkerLocationsFor(area: String):
            Pair<WalkerManager.Location?, WalkerManager.Location?> = when (area) {
        "lumbridge"    -> WalkerManager.Location.LUMBRIDGE_TREES    to WalkerManager.Location.LUMBRIDGE_BANK
        "draynor"      -> WalkerManager.Location.DRAYNOR_WILLOWS    to WalkerManager.Location.DRAYNOR_BANK
        "varrock_west" -> WalkerManager.Location.VARROCK_TREES_WEST to WalkerManager.Location.VARROCK_WEST_BANK
        "varrock_east" -> WalkerManager.Location.VARROCK_TREES_EAST to WalkerManager.Location.VARROCK_EAST_BANK
        "falador"      -> WalkerManager.Location.FALADOR_PARK_TREES to WalkerManager.Location.FALADOR_WEST_BANK
        "edgeville"    -> WalkerManager.Location.EDGEVILLE_TREES    to WalkerManager.Location.EDGEVILLE_BANK
        "barbarian"    -> WalkerManager.Location.BARBARIAN_VILLAGE_TREES to WalkerManager.Location.EDGEVILLE_BANK
        else           -> null to null
    }

    // ── Stats & notifications ─────────────────────────────────────────────────
    private fun updateOverlayStats(script: BotScript, antiBan: AntiBanManager, status: String) {
        val elapsed = (System.currentTimeMillis() - startTimeMs) / 1_000L
        val h = elapsed / 3600; val m = (elapsed % 3600) / 60; val s = elapsed % 60
        val runtime = "%02d:%02d:%02d".format(h, m, s)
        val gpHr    = if (elapsed > 0) (script.gpGained / (elapsed / 3600.0)).toInt() else 0
        overlay?.updateStats(
            script        = ScriptInfo.name(config.scriptId),
            actions       = script.actions,
            xp            = script.xpGained,
            gpHr          = gpHr,
            runtime       = runtime,
            status        = status,
            currentAction = script.currentAction,
        )
    }

    private fun updateNotification(script: BotScript?) {
        val text = if (script != null)
            "${ScriptInfo.name(config.scriptId)} | ${script.actions} actions"
        else "Idle"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
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
        val ch = NotificationChannel(CHANNEL_ID, "OSRS Bot Service",
            NotificationManager.IMPORTANCE_LOW).apply { description = "Running status" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onDestroy() {
        stopBot()
        overlay?.hide()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "osrs_bot_channel"
        private const val MAX_RECOVERIES_PER_RUN = 5
    }
}
