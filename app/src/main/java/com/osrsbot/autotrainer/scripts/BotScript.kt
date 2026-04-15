package com.osrsbot.autotrainer.scripts

import android.accessibilityservice.AccessibilityService
import com.osrsbot.autotrainer.antiban.AntiBanManager
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.Logger

abstract class BotScript(
    protected val service: AccessibilityService,
    protected val config: BotConfig,
    protected val antiBan: AntiBanManager,
    protected val detector: ObjectDetector,
) {
    abstract val id: String
    abstract val name: String

    var actions: Int = 0
    var xpGained: Int = 0
    var gpGained: Int = 0
    var currentAction: String = "Initialising…"

    /**
     * Timestamp of the last time completeAction() was called.
     * BotService uses this to detect if the bot is stuck (no progress for > threshold).
     */
    var lastActionMs: Long = System.currentTimeMillis()
        private set

    abstract suspend fun tick()

    open fun onStart() {
        lastActionMs = System.currentTimeMillis()
        Logger.ok("Script started: $name")
    }

    open fun onStop() {
        Logger.warn("Script stopped: $name")
    }

    /**
     * Called by BotService when no action has been completed for [thresholdMs].
     * Each script should reset its state machine to a safe starting point.
     * Default: logs a warning. Subclasses should override and reset their state.
     */
    open fun onStuck() {
        Logger.warn("[$name] Stuck detected — no action for ${STUCK_THRESHOLD_MS / 1000}s. Override onStuck() to recover.")
        lastActionMs = System.currentTimeMillis() // reset timer so we don't spam
    }

    /**
     * Returns true if no action has been completed within [thresholdMs].
     * BotService polls this every tick.
     */
    fun isStuck(thresholdMs: Long = STUCK_THRESHOLD_MS): Boolean =
        System.currentTimeMillis() - lastActionMs > thresholdMs

    protected fun setAction(msg: String) {
        currentAction = msg
        Logger.action(msg)
    }

    protected fun completeAction(xp: Int = 0, gp: Int = 0) {
        actions++
        xpGained += xp
        gpGained  += gp + (-10..10).random()
        lastActionMs = System.currentTimeMillis()   // ← reset stuck timer on every success
        antiBan.onActionCompleted()
    }

    companion object {
        /** Bot is considered stuck if no action completes within 90 seconds. */
        const val STUCK_THRESHOLD_MS = 90_000L
    }
}
