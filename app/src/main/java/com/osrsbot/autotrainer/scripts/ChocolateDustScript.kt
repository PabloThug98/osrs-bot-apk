package com.osrsbot.autotrainer.scripts

import android.accessibilityservice.AccessibilityService
import com.osrsbot.autotrainer.antiban.AntiBanManager
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.selector.TargetStore
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.GestureHelper
import com.osrsbot.autotrainer.utils.Logger
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Chocolate Dust Maker — ~180K GP/hr
 *
 * Workflow per trip:
 *   1. SETUP  — tap knife, tap chocolate bar, confirm Make-All dialog (~3 clicks)
 *   2. GRINDING — wait ~50s for all 27 bars to be ground automatically
 *   3. BANKING — bank dust, withdraw 27 bars (~12s walk + deposit)
 *
 * User should tap 🎯 to mark: [0] Knife slot, [1] Chocolate Bar slot
 * in their inventory so the bot knows exactly where to click.
 */
class ChocolateDustScript(
    service: AccessibilityService,
    config: BotConfig,
    antiBan: AntiBanManager,
    detector: ObjectDetector,
) : BotScript(service, config, antiBan, detector) {

    override val id   = "chocolate"
    override val name = "🍫 Chocolate Dust Maker"

    private var barsInInventory = 27
    private val GP_PER_BAR = 180

    // Each grind trip processes all 27 bars in one Make-All session
    private val GRIND_TIME_MS = 27L * 1800L  // ~48.6 seconds (1.8s per bar)

    private enum class State { SETUP_USE_KNIFE, SETUP_USE_BAR, CONFIRM_DIALOG, GRINDING, BANKING }
    private var state = State.SETUP_USE_KNIFE

    // Inventory positions (fallback when no targets saved)
    // OSRS mobile: knife is typically in slot 0 (top-left), bars fill rest
    private val dm get() = service.resources.displayMetrics

    override fun onStuck() {
        Logger.warn("[" + name + "] Stuck — resetting to SETUP_USE_KNIFE")
        state = State.SETUP_USE_KNIFE
        barsInInventory = 27
        detector.invalidateCache()
        super.onStuck()
    }

    override suspend fun tick() {
        when (state) {

            State.SETUP_USE_KNIFE -> {
                if (barsInInventory <= 0) {
                    state = State.BANKING
                    return
                }
                setAction("Use knife on chocolate bar…")
                delay(antiBan.getClickDelay())

                // Target 0 = knife, Target 1 = chocolate bar (user should set these)
                val targets = TargetStore.getAll()
                val knifeTarget = targets.firstOrNull {
                    it.label.contains("knife", ignoreCase = true)
                } ?: targets.getOrNull(0)

                if (knifeTarget != null) {
                    val (ox, oy) = antiBan.getClickOffset()
                    if (!tap(knifeTarget.x + ox.toFloat(), knifeTarget.y + oy.toFloat())) return
                    Logger.action("Tapping knife slot")
                } else {
                    // Fallback: knife is usually top-left of inventory
                    if (!tapRelative(0.30f, 0.62f)) return
                    Logger.action("Tapping knife (fallback position)")
                }

                delay(antiBan.getActionDelay())
                state = State.SETUP_USE_BAR
            }

            State.SETUP_USE_BAR -> {
                setAction("Tapping chocolate bar…")
                delay(antiBan.getClickDelay())

                val targets = TargetStore.getAll()
                val barTarget = targets.firstOrNull {
                    it.label.contains("chocolate", ignoreCase = true) ||
                            it.label.contains("bar", ignoreCase = true)
                } ?: targets.getOrNull(1) ?: targets.getOrNull(0)

                if (barTarget != null) {
                    val (ox, oy) = antiBan.getClickOffset()
                    // If only one target set, offset slightly to hit bar slot next to knife
                    val offsetX = if (targets.size < 2) 80f else 0f
                    if (!tap(barTarget.x + ox.toFloat() + offsetX, barTarget.y + oy.toFloat())) return
                    Logger.action("Tapping bar slot")
                } else {
                    // Fallback: bar is usually slot next to knife
                    if (!tapRelative(0.46f, 0.62f)) return
                    Logger.action("Tapping bar (fallback position)")
                }

                delay(antiBan.getActionDelay())
                state = State.CONFIRM_DIALOG
            }

            State.CONFIRM_DIALOG -> {
                setAction("Confirming Make-All…")
                // Make-X dialog appears — tap "Make All" button (bottom-centre of dialog)
                delay(antiBan.getClickDelay())
                if (!tapRelative(0.5f, 0.88f)) return
                Logger.action("Tapping Make-All in dialog")

                delay(antiBan.getActionDelay())
                state = State.GRINDING
            }

            State.GRINDING -> {
                // All 27 bars grind automatically — just wait for the full animation
                val waitMs = GRIND_TIME_MS + Random.nextLong(-1000L, 2000L)
                setAction("Grinding all 27 bars… (${waitMs / 1000}s)")
                delay(waitMs)

                val bars = barsInInventory
                repeat(bars) { completeAction(0, GP_PER_BAR) }
                Logger.ok("Trip done: $bars bars → dust | GP this trip: ${bars * GP_PER_BAR}")
                barsInInventory = 0

                delay(antiBan.getActionDelay())
                state = State.BANKING
            }

            State.BANKING -> {
                setAction("Banking dust & restocking bars…")
                delay(antiBan.getBankingDelay())
                barsInInventory = 27
                Logger.ok("Restocked. Total GP: $gpGained")
                delay(antiBan.getActionDelay())
                state = State.SETUP_USE_KNIFE
            }
        }
    }

    private suspend fun tapRelative(fx: Float, fy: Float): Boolean {
        val (ox, oy) = antiBan.getClickOffset()
        return tap(dm.widthPixels * fx + ox.toFloat(), dm.heightPixels * fy + oy.toFloat())
    }

    private suspend fun tap(x: Float, y: Float): Boolean =
        GestureHelper.tap(service, x, y, antiBan.getTapDurationMs())
}
