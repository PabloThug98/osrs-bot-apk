package com.osrsbot.autotrainer.antiban

import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.Logger
import kotlin.random.Random

class AntiBanManager(private val config: BotConfig) {

    private var actionsUntilBreak: Int = config.breakIntervalActions
    private var fatigue: Float = 1.0f
    private var totalActions: Int = 0

    fun onActionCompleted() {
        totalActions++
        actionsUntilBreak--
        if (config.fatigueEnabled) {
            fatigue = (1.0f + totalActions * 0.004f).coerceAtMost(2.5f)
        }
    }

    fun shouldBreak(): Boolean {
        return config.antiBanBreaks && actionsUntilBreak <= 0
    }

    fun resetBreakCounter() {
        val variance = Random.nextInt(-10, 10)
        actionsUntilBreak = (config.breakIntervalActions + variance).coerceAtLeast(5)
        Logger.warn("Break taken. Next break in $actionsUntilBreak actions.")
    }

    /** Returns the delay in ms before next action (simulates human reaction time) */
    fun getActionDelay(): Long {
        val baseMs = when {
            !config.humanLikeMouse -> 300L
            else -> Random.nextLong(450, 900)
        }
        val fatigueMs = (baseMs * fatigue).toLong()
        val jitter = Random.nextLong(-80, 120)
        return (fatigueMs + jitter).coerceAtLeast(150L)
    }

    /** Adds a small random pre-click pause */
    fun getClickDelay(): Long = if (config.humanLikeMouse) Random.nextLong(80, 250) else 0L

    /** Random offset so clicks aren't always perfectly centred on the target */
    fun getClickOffset(): Pair<Int, Int> {
        if (!config.humanLikeMouse) return 0 to 0
        return Random.nextInt(-8, 8) to Random.nextInt(-8, 8)
    }

    fun checkAutoStop(startTimeMs: Long, actions: Int): StopReason? {
        if (config.stopAfterTime) {
            val elapsedMin = (System.currentTimeMillis() - startTimeMs) / 60_000
            if (elapsedMin >= config.stopAfterMinutes) return StopReason.TIME_LIMIT
        }
        if (config.stopAfterActions && actions >= config.stopAfterActionCount) {
            return StopReason.ACTION_LIMIT
        }
        return null
    }

    fun getFatigue(): Float = fatigue
    fun getActionsUntilBreak(): Int = actionsUntilBreak
}

enum class StopReason { TIME_LIMIT, ACTION_LIMIT, PLAYER_NEARBY, MANUAL }
