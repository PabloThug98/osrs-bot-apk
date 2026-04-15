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

    /**
     * Short pre-action pause — simulates human reaction time before clicking.
     * Typically 200–600ms.
     */
    fun getClickDelay(): Long {
        val base = if (config.humanLikeMouse) Random.nextLong(200L, 550L) else 150L
        return (base * fatigue).toLong().coerceAtLeast(100L)
    }

    /**
     * After-action delay — used for short waits between UI interactions.
     * Typically 400–900ms.
     */
    fun getActionDelay(): Long {
        val base = if (config.humanLikeMouse) Random.nextLong(400L, 900L) else 300L
        val jitter = Random.nextLong(-80L, 120L)
        return ((base + jitter) * fatigue).toLong().coerceAtLeast(200L)
    }

    /**
     * Woodcutting chop wait — time between clicking a tree and getting the log.
     * OSRS tick = 0.6s. Regular trees ~3-5 ticks, Oak/Willow ~4-7 ticks.
     * Returns 4500–6500ms with fatigue scaling.
     */
    fun getWoodcuttingChopDelay(): Long {
        val base = Random.nextLong(4500L, 6500L)
        val jitter = Random.nextLong(-400L, 600L)
        return ((base + jitter) * fatigue).toLong().coerceAtLeast(3000L)
    }

    /**
     * Fishing wait — time from clicking spot to catching a fish.
     * Typically 6–10 seconds depending on fishing level and spot.
     */
    fun getFishingWaitDelay(): Long {
        val base = Random.nextLong(6000L, 10000L)
        val jitter = Random.nextLong(-500L, 800L)
        return ((base + jitter) * fatigue).toLong().coerceAtLeast(4000L)
    }

    /**
     * Combat kill wait — time to kill a monster (hits + death animation).
     * Varies widely by monster. Default 8–14 seconds.
     */
    fun getCombatKillDelay(): Long {
        val base = Random.nextLong(8000L, 14000L)
        val jitter = Random.nextLong(-500L, 1000L)
        return ((base + jitter) * fatigue).toLong().coerceAtLeast(5000L)
    }

    /**
     * Banking wait — walk + deposit + walk back. Roughly 10–18 seconds.
     */
    fun getBankingDelay(): Long = Random.nextLong(10000L, 18000L)

    /** Random offset so clicks aren't always perfectly centred on the target */
    fun getClickOffset(): Pair<Int, Int> {
        if (!config.humanLikeMouse) return 0 to 0
        return Random.nextInt(-10, 10) to Random.nextInt(-10, 10)
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
