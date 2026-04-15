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
     * Typically 200–600 ms.
     */
    fun getClickDelay(): Long {
        val base = if (config.humanLikeMouse) Random.nextLong(200L, 550L) else 150L
        return (base * fatigue).toLong().coerceAtLeast(100L)
    }

    /**
     * After-action delay — short pause between UI interactions.
     * Typically 400–900 ms.
     */
    fun getActionDelay(): Long {
        val base   = if (config.humanLikeMouse) Random.nextLong(400L, 900L) else 300L
        val jitter = Random.nextLong(-80L, 120L)
        return ((base + jitter) * fatigue).toLong().coerceAtLeast(200L)
    }

    /**
     * Full log acquisition wait — the time from when the character STARTS chopping
     * (already adjacent to tree) until the log drops into inventory.
     *
     * OSRS mechanics:
     *   - Each chop attempt = 3 game ticks = 1.8 s
     *   - Success chance depends on WC level + axe type (roughly 30–70%)
     *   - Low level (1–40):  many failed attempts → 10–25 s per log
     *   - Mid level (40–70): 6–15 s per log
     *   - High level (70+):  4–10 s per log
     *
     * We default to a wide mid-range with fatigue scaling. The bot has no way
     * to know the player's WC level, so we err generous (longer wait = safer,
     * because cutting short causes the "moves before chop finishes" bug).
     */
    fun getWoodcuttingLogWaitMs(): Long {
        val base   = Random.nextLong(9_000L, 22_000L)
        val jitter = Random.nextLong(-1_000L, 2_000L)
        return ((base + jitter) * fatigue).toLong().coerceAtLeast(6_000L)
    }

    /**
     * Kept for backward compatibility with other scripts that may reference it.
     * Prefer getWoodcuttingLogWaitMs() in WoodcuttingScript.
     */
    fun getWoodcuttingChopDelay(): Long = getWoodcuttingLogWaitMs()

    /**
     * Fishing wait — time from clicking spot to catching a fish.
     * Typically 6–10 seconds depending on fishing level and spot.
     */
    fun getFishingWaitDelay(): Long {
        val base   = Random.nextLong(6_000L, 10_000L)
        val jitter = Random.nextLong(-500L, 800L)
        return ((base + jitter) * fatigue).toLong().coerceAtLeast(4_000L)
    }

    /**
     * Combat kill wait — time to kill a monster (hits + death animation).
     * Varies widely by monster. Default 8–14 seconds.
     */
    fun getCombatKillDelay(): Long {
        val base   = Random.nextLong(8_000L, 14_000L)
        val jitter = Random.nextLong(-500L, 1_000L)
        return ((base + jitter) * fatigue).toLong().coerceAtLeast(5_000L)
    }

    /**
     * Banking walk + deposit + walk back.
     * Used only when the WalkerManager has no route defined.
     * Roughly 10–18 seconds.
     */
    fun getBankingDelay(): Long = Random.nextLong(10_000L, 18_000L)

    /** Random pixel offset so clicks are never perfectly centred on the target. */
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
