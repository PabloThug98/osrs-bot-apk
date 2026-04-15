package com.osrsbot.autotrainer.antiban

import android.accessibilityservice.AccessibilityService
import com.osrsbot.autotrainer.utils.AxeInfo
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.GestureHelper
import com.osrsbot.autotrainer.utils.Logger
import com.osrsbot.autotrainer.utils.ScreenRegions
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * AntiBanManager v3
 *
 * New in v3:
 *  - rotateCamera()    — random drag in the game world to rotate camera view
 *  - checkStatsTab()   — open Skills tab briefly, then return to Inventory
 *  - idleMouseMove()   — short random tap in a non-interactive area
 *  - randomAction()    — orchestrates all anti-ban behaviours at random intervals
 *  - misclick()        — occasional intentional mis-tap (human error simulation)
 *  - All previous v2 features retained
 */
class AntiBanManager(private val config: BotConfig) {

    private var actionsUntilBreak: Int = config.breakIntervalActions
    private var actionsUntilAntiBan: Int = nextAntiBanInterval()
    private var fatigue: Float = 1.0f
    private var totalActions: Int = 0

    // ── Action tracking ───────────────────────────────────────────────────────
    fun onActionCompleted() {
        totalActions++
        actionsUntilBreak--
        actionsUntilAntiBan--
        if (config.fatigueEnabled) {
            fatigue = (1.0f + totalActions * 0.004f).coerceAtMost(2.5f)
        }
    }

    fun shouldBreak():    Boolean = config.antiBanBreaks && actionsUntilBreak <= 0
    fun shouldAntiBan():  Boolean = actionsUntilAntiBan <= 0

    fun resetBreakCounter() {
        actionsUntilBreak = (config.breakIntervalActions + Random.nextInt(-10, 10)).coerceAtLeast(5)
        Logger.warn("Break taken. Next break in $actionsUntilBreak actions.")
    }

    fun resetAntiBanCounter() {
        actionsUntilAntiBan = nextAntiBanInterval()
    }

    // ── Timing ────────────────────────────────────────────────────────────────
    fun getClickDelay(): Long {
        val base = if (config.humanLikeMouse) Random.nextLong(200L, 550L) else 150L
        return (base * fatigue).toLong().coerceAtLeast(100L)
    }

    fun getTapDurationMs(): Long =
        if (config.humanLikeMouse) Random.nextLong(55L, 130L) else 70L

    fun getActionDelay(): Long {
        val base   = if (config.humanLikeMouse) Random.nextLong(400L, 900L) else 300L
        val jitter = (base * 0.10f * (Random.nextFloat() * 2f - 1f)).toLong()
        return ((base + jitter) * fatigue).toLong().coerceAtLeast(200L)
    }

    fun getWoodcuttingLogWaitMs(): Long {
        val lvl   = config.playerWoodcuttingLevel.coerceIn(1, 99)
        val bonus = AxeInfo.speedBonus(config.axeType)
        val chance = ((lvl * 0.8f + bonus * 100f) / 256f).coerceIn(0.05f, 0.95f)
        val baseMs = (1f / chance * 1800f).toLong().coerceIn(4_000L, 30_000L)
        val jitter = (baseMs * 0.15f * (Random.nextFloat() * 2f - 1f)).toLong()
        return ((baseMs + jitter) * fatigue).toLong().coerceAtLeast(3_000L)
    }

    fun getWoodcuttingChopDelay(): Long = getWoodcuttingLogWaitMs()

    fun getFishingWaitDelay(): Long {
        val lvl = config.playerFishingLevel.coerceIn(1, 99)
        val (minMs, maxMs) = when {
            lvl < 20 -> 10_000L to 15_000L
            lvl < 50 ->  7_000L to 11_000L
            lvl < 70 ->  5_500L to  9_000L
            else     ->  4_500L to  7_500L
        }
        val base = Random.nextLong(minMs, maxMs)
        val jitter = (base * 0.10f * (Random.nextFloat() * 2f - 1f)).toLong()
        return ((base + jitter) * fatigue).toLong().coerceAtLeast(3_000L)
    }

    fun getCombatKillDelay(): Long {
        val lvl = config.playerCombatLevel.coerceIn(3, 126)
        val (minMs, maxMs) = when {
            lvl < 30  -> 10_000L to 18_000L
            lvl < 60  ->  7_000L to 13_000L
            lvl < 90  ->  5_000L to 10_000L
            else      ->  4_000L to  8_000L
        }
        val base   = Random.nextLong(minMs, maxMs)
        val jitter = (base * 0.12f * (Random.nextFloat() * 2f - 1f)).toLong()
        return ((base + jitter) * fatigue).toLong().coerceAtLeast(3_000L)
    }

    fun getBankingDelay(): Long = Random.nextLong(10_000L, 18_000L)

    fun getClickOffset(): Pair<Int, Int> {
        if (!config.humanLikeMouse) return 0 to 0
        fun gaussInt(r: Int) = ((Random.nextInt(-r, r) + Random.nextInt(-r, r) + Random.nextInt(-r, r)) / 3)
        return gaussInt(12) to gaussInt(12)
    }

    fun checkAutoStop(startTimeMs: Long, actions: Int): StopReason? {
        if (config.stopAfterTime) {
            val elapsed = (System.currentTimeMillis() - startTimeMs) / 60_000
            if (elapsed >= config.stopAfterMinutes) return StopReason.TIME_LIMIT
        }
        if (config.stopAfterActions && actions >= config.stopAfterActionCount) return StopReason.ACTION_LIMIT
        return null
    }

    fun getFatigue(): Float         = fatigue
    fun getActionsUntilBreak(): Int = actionsUntilBreak

    // ── Anti-ban actions ──────────────────────────────────────────────────────

    /**
     * Runs a random anti-ban action. Called by BotService when shouldAntiBan() is true.
     * Resets the anti-ban counter automatically.
     */
    suspend fun runRandomAntiBanAction(service: AccessibilityService) {
        if (!config.humanLikeMouse) return
        resetAntiBanCounter()
        when (Random.nextInt(100)) {
            in 0..24  -> rotateCamera(service)
            in 25..44 -> checkStatsTab(service)
            in 45..59 -> idleMouseMove(service)
            in 60..69 -> misclick(service)
            in 70..79 -> moveMinimap(service)
            else      -> {
                // Random short pause — bot "spaces out"
                val pause = Random.nextLong(800L, 3_500L)
                Logger.info("AntiBan: spacing out for ${pause}ms")
                delay(pause)
            }
        }
    }

    /**
     * Random drag in the game viewport to rotate the camera.
     * Short swipe ~30-80px in a random direction.
     */
    suspend fun rotateCamera(service: AccessibilityService) {
        val dm    = service.resources.displayMetrics
        val gameH = (ScreenRegions.GAME_BOTTOM_F * dm.heightPixels).toInt()
        // Pick a random point in the top two-thirds of the game world
        val cx = Random.nextInt(dm.widthPixels / 4, dm.widthPixels * 3 / 4).toFloat()
        val cy = Random.nextInt(dm.heightPixels / 8, gameH * 2 / 3).toFloat()
        val angle  = Random.nextFloat() * 2f * Math.PI.toFloat()
        val dist   = Random.nextInt(30, 80).toFloat()
        val ex = cx + dist * cos(angle)
        val ey = cy + dist * sin(angle)
        val dur = Random.nextLong(150L, 350L)
        Logger.info("AntiBan: camera rotate swipe (${"%.0f".format(cx)}, ${"%.0f".format(cy)}) → (${"%.0f".format(ex)}, ${"%.0f".format(ey)})")
        GestureHelper.swipeHuman(service, cx, cy, ex, ey, dur)
        delay(Random.nextLong(300L, 700L))
    }

    /**
     * Open the Skills tab briefly, pause, then return to the Inventory tab.
     * Simulates a player glancing at their XP.
     */
    suspend fun checkStatsTab(service: AccessibilityService) {
        val dm = service.resources.displayMetrics
        val tabY = ScreenRegions.TAB_ROW_Y_F * dm.heightPixels
        val statsX = ScreenRegions.TAB_STATS_X_F * dm.widthPixels
        val invX   = ScreenRegions.TAB_INV_X_F   * dm.widthPixels
        Logger.info("AntiBan: opening Skills tab")
        GestureHelper.tapHuman(service, statsX, tabY)
        delay(Random.nextLong(1_200L, 3_000L))  // "read" the stats
        GestureHelper.tapHuman(service, invX, tabY)
        delay(Random.nextLong(200L, 500L))
    }

    /**
     * Short random tap in a non-interactive area (idle mouse movement).
     * Targets the chat area or the game world edge where nothing is clickable.
     */
    suspend fun idleMouseMove(service: AccessibilityService) {
        val dm = service.resources.displayMetrics
        // Random tap in chat area (bottom-left, generally safe)
        val x = Random.nextInt(
            (ScreenRegions.CHAT_LEFT_F   * dm.widthPixels).toInt(),
            (ScreenRegions.CHAT_RIGHT_F  * dm.widthPixels / 2).toInt()
        ).toFloat()
        val y = Random.nextInt(
            (ScreenRegions.CHAT_TOP_F    * dm.heightPixels).toInt(),
            (ScreenRegions.CHAT_BOTTOM_F * dm.heightPixels).toInt()
        ).toFloat()
        Logger.info("AntiBan: idle move tap at (${"%.0f".format(x)}, ${"%.0f".format(y)})")
        GestureHelper.tapHuman(service, x, y)
        delay(Random.nextLong(200L, 600L))
    }

    /**
     * Intentional mis-tap — click somewhere on screen that does nothing meaningful.
     * Humans misclick all the time.
     */
    suspend fun misclick(service: AccessibilityService) {
        val dm = service.resources.displayMetrics
        // Tap near but not on the target area — game world fringe
        val x = Random.nextInt(10, dm.widthPixels / 4).toFloat()
        val y = Random.nextInt(
            (ScreenRegions.GAME_BOTTOM_F * dm.heightPixels * 0.7f).toInt(),
            (ScreenRegions.GAME_BOTTOM_F * dm.heightPixels * 0.95f).toInt()
        ).toFloat()
        Logger.info("AntiBan: misclick at (${"%.0f".format(x)}, ${"%.0f".format(y)})")
        GestureHelper.tapHuman(service, x, y)
        delay(Random.nextLong(150L, 400L))
    }

    /**
     * Random minimap tap — simulates player checking nearby area.
     * Taps near minimap centre without moving far.
     */
    suspend fun moveMinimap(service: AccessibilityService) {
        val dm  = service.resources.displayMetrics
        val mmX = ScreenRegions.MINIMAP_CX_F * dm.widthPixels
        val mmY = ScreenRegions.MINIMAP_CY_F * dm.heightPixels
        val r   = ScreenRegions.MINIMAP_R_F  * dm.widthPixels * 0.6f
        val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
        val dist  = Random.nextFloat() * r
        val tx = mmX + dist * cos(angle)
        val ty = mmY + dist * sin(angle)
        Logger.info("AntiBan: minimap look at (${"%.0f".format(tx)}, ${"%.0f".format(ty)})")
        GestureHelper.tapHuman(service, tx, ty)
        delay(Random.nextLong(200L, 500L))
    }

    // ── Private ───────────────────────────────────────────────────────────────
    private fun nextAntiBanInterval(): Int = Random.nextInt(8, 22) // every 8-22 actions
}

enum class StopReason { TIME_LIMIT, ACTION_LIMIT, PLAYER_NEARBY, MANUAL }
