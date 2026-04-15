package com.osrsbot.autotrainer.scripts

import android.accessibilityservice.AccessibilityService
import com.osrsbot.autotrainer.antiban.AntiBanManager
import com.osrsbot.autotrainer.banking.BankInteractor
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.selector.TargetStore
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.GestureHelper
import com.osrsbot.autotrainer.utils.Logger
import com.osrsbot.autotrainer.walker.WalkerManager
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Woodcutting Bot — chops trees and banks logs.
 *
 * ── OSRS woodcutting mechanics ────────────────────────────────────────────────
 *  • Click tree once → character walks to it and starts auto-chopping.
 *  • DO NOT re-click while chopping — it cancels the action (was the "runs randomly" bug).
 *  • Logs arrive every 9–22 s depending on level/axe. We wait the full time.
 *  • When inventory hits 27 logs → walk to bank → real bank taps → walk back.
 *
 * ── State machine ─────────────────────────────────────────────────────────────
 *  WALK_TO_TREES → CLICK_TREE → CHOPPING → TREE_GONE → WALK_TO_BANK → BANKING
 *
 * ── Stuck recovery ───────────────────────────────────────────────────────────
 *  If no action completes within STUCK_THRESHOLD_MS (90 s), BotService calls
 *  onStuck(). We reset to CLICK_TREE so the bot tries again cleanly.
 */
class WoodcuttingScript(
    service: AccessibilityService,
    config: BotConfig,
    antiBan: AntiBanManager,
    detector: ObjectDetector,
) : BotScript(service, config, antiBan, detector) {

    override val id   = "woodcutting"
    override val name = "🌲 Woodcutting Bot"

    private val XP_PER_LOG     = 38
    private val GP_PER_LOG     = 50
    private val WALK_TO_TREE_MS = 3_000L

    // ── State machine ─────────────────────────────────────────────────────────
    private enum class State {
        WALK_TO_TREES,
        CLICK_TREE,
        CHOPPING,
        TREE_GONE,
        WALK_TO_BANK,
        BANKING,
    }

    private var state          = State.CLICK_TREE
    private var logsInInventory = 0
    private var treeGoneStreak  = 0

    // ── Walker + banker ───────────────────────────────────────────────────────
    private val walker = WalkerManager(service)
    private val banker = BankInteractor(service, detector)

    var treeLocation: WalkerManager.Location? = null
    var bankLocation: WalkerManager.Location? = null

    private val dm get() = service.resources.displayMetrics

    // ── Stuck recovery ────────────────────────────────────────────────────────
    override fun onStuck() {
        Logger.warn("[$name] STUCK — no action for ${STUCK_THRESHOLD_MS / 1_000}s. " +
                    "Was in state: $state → resetting to CLICK_TREE")
        state          = State.CLICK_TREE
        treeGoneStreak = 0
        detector.invalidateCache()
        super.onStuck()     // resets lastActionMs so we don't spam this
    }

    // ── Main loop ─────────────────────────────────────────────────────────────
    override suspend fun tick() {
        when (state) {

            // ── WALK_TO_TREES ────────────────────────────────────────────────
            State.WALK_TO_TREES -> {
                val tLoc = treeLocation
                val bLoc = bankLocation
                if (tLoc != null && bLoc != null) {
                    setAction("Walking to trees via minimap…")
                    val ok = walker.walkTo(bLoc, tLoc)
                    if (!ok) { Logger.warn("Walker: no route — staying put"); delay(1_500L) }
                } else {
                    delay(800L)
                }
                state = State.CLICK_TREE
            }

            // ── CLICK_TREE ───────────────────────────────────────────────────
            State.CLICK_TREE -> {
                if (logsInInventory >= 27) { state = State.WALK_TO_BANK; return }
                setAction("Finding tree…")

                val forceRefresh = treeGoneStreak >= 3
                val detected = detector.detectObjects("woodcutting", forceRefresh)
                    .filter {
                        it.confidence >= config.detectConfidenceMin &&
                                !it.name.contains("bank", ignoreCase = true) &&
                                !it.name.contains("chest", ignoreCase = true)
                    }
                val nearest = detector.findNearest(detected, dm.widthPixels, dm.heightPixels)

                if (nearest != null) {
                    delay(antiBan.getClickDelay())
                    val (ox, oy) = antiBan.getClickOffset()
                    if (!tap(nearest.bounds.exactCenterX() + ox, nearest.bounds.exactCenterY() + oy)) return
                    Logger.action("Tree detected by accessibility @ (${nearest.bounds.centerX()}, ${nearest.bounds.centerY()})")
                } else {
                    val calibratedTree = TargetStore.nextTargetWhere {
                        it.label.contains("tree", ignoreCase = true)
                    }
                    if (calibratedTree == null) {
                        setAction("No reliable tree found — set one Tree target or move closer")
                        delay(2_500L + Random.nextLong(0L, 500L))
                        return
                    }
                    delay(antiBan.getClickDelay())
                    val (ox, oy) = antiBan.getClickOffset()
                    if (!tap(calibratedTree.x + ox, calibratedTree.y + oy)) return
                    Logger.action("Using calibrated tree target @ (${calibratedTree.x.toInt()}, ${calibratedTree.y.toInt()})")
                }

                setAction("Walking to tree…")
                delay(WALK_TO_TREE_MS + Random.nextLong(-500L, 1_500L))
                state = State.CHOPPING
            }

            // ── CHOPPING ─────────────────────────────────────────────────────
            // DO NOT tap anything here — re-clicking cancels OSRS auto-chop.
            State.CHOPPING -> {
                val chopMs = antiBan.getWoodcuttingLogWaitMs()
                var remaining = chopMs
                while (remaining > 0) {
                    val slice = minOf(remaining, 2_000L)
                    setAction("Chopping… ~${remaining / 1_000}s")
                    delay(slice)
                    remaining -= slice
                }

                logsInInventory++
                completeAction(XP_PER_LOG, GP_PER_LOG)
                treeGoneStreak = 0
                Logger.ok("Log #$actions | Inv: $logsInInventory/27 | XP: $xpGained")
                delay(antiBan.getActionDelay())

                state = when {
                    logsInInventory >= 27      -> State.WALK_TO_BANK
                    Random.nextFloat() < 0.25f -> State.TREE_GONE
                    else                       -> State.CLICK_TREE
                }
            }

            // ── TREE_GONE ────────────────────────────────────────────────────
            State.TREE_GONE -> {
                treeGoneStreak++
                setAction("Tree gone — waiting for respawn… ($treeGoneStreak)")
                if (treeGoneStreak >= 5) {
                    Logger.warn("Tree hasn't respawned after $treeGoneStreak checks — refreshing detector")
                    detector.invalidateCache()
                    treeGoneStreak = 0
                }
                delay(Random.nextLong(10_000L, 18_000L))
                state = State.CLICK_TREE
            }

            // ── WALK_TO_BANK ─────────────────────────────────────────────────
            State.WALK_TO_BANK -> {
                setAction("Walking to bank…")
                val tLoc = treeLocation
                val bLoc = bankLocation
                if (tLoc != null && bLoc != null) {
                    val ok = walker.walkTo(tLoc, bLoc)
                    if (!ok) { Logger.warn("Walker: no bank route — timed delay"); delay(antiBan.getBankingDelay()) }
                } else {
                    delay(antiBan.getBankingDelay())
                }
                state = State.BANKING
            }

            // ── BANKING ──────────────────────────────────────────────────────
            // Real taps: booth → deposit-all → close. No more blind delay!
            State.BANKING -> {
                setAction("Banking logs…")
                val ok = banker.depositInventory()
                if (ok) {
                    logsInInventory = 0
                    completeAction()    // resets stuck timer — counts as a completed action
                    Logger.ok("Banked — total $actions logs | $xpGained XP")
                } else {
                    Logger.warn("Bank deposit failed — retrying next tick")
                    delay(2_000L)
                    return              // stay in BANKING; try again next tick
                }
                delay(antiBan.getActionDelay())
                state = State.WALK_TO_TREES
            }
        }
    }

    // ── Tap helper ────────────────────────────────────────────────────────────
    private suspend fun tap(x: Float, y: Float): Boolean =
        GestureHelper.tap(service, x, y, antiBan.getTapDurationMs())
}
