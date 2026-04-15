package com.osrsbot.autotrainer.scripts

import android.accessibilityservice.AccessibilityService
import com.osrsbot.autotrainer.antiban.AntiBanManager
import com.osrsbot.autotrainer.banking.BankInteractor
import com.osrsbot.autotrainer.detector.InventoryDetector
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.selector.TargetStore
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.GestureHelper
import com.osrsbot.autotrainer.utils.Logger
import com.osrsbot.autotrainer.walker.WalkerManager
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * WoodcuttingScript v2
 *
 * Upgrades over v1:
 *  - Real inventory check via InventoryDetector (pixel-based slot scanning)
 *    instead of a counter that drifts out of sync.
 *  - tapHuman() replaces tap() for more human-like clicks.
 *  - Anti-ban hook: calls antiBan.runRandomAntiBanAction() when shouldAntiBan().
 *  - Bank detection: also uses PixelDetector to spot the bank booth.
 *
 * State machine:
 *   WALK_TO_TREES → CLICK_TREE → CHOPPING → TREE_GONE → WALK_TO_BANK → BANKING
 */
class WoodcuttingScript(
    service: AccessibilityService,
    config: BotConfig,
    antiBan: AntiBanManager,
    detector: ObjectDetector,
) : BotScript(service, config, antiBan, detector) {

    override val id   = "woodcutting"
    override val name = "Woodcutting Bot"

    private val XP_PER_LOG      = 38
    private val GP_PER_LOG      = 50
    private val WALK_TO_TREE_MS = 3_000L

    private enum class State {
        WALK_TO_TREES, CLICK_TREE, CHOPPING, TREE_GONE, WALK_TO_BANK, BANKING
    }

    private var state          = State.CLICK_TREE
    private var treeGoneStreak = 0
    private val dm get() = service.resources.displayMetrics

    private val walker    = WalkerManager(service)
    private val banker    = BankInteractor(service, detector)
    private val invDetect = InventoryDetector(
        (detector.pixelDetector?.capture) ?: run {
            Logger.warn("WoodcuttingScript: no ScreenCaptureManager — inventory pixel detection unavailable")
            null
        }.let { return@let it }!!
    )

    var treeLocation: WalkerManager.Location? = null
    var bankLocation: WalkerManager.Location? = null

    override fun onStuck() {
        Logger.warn("[$name] STUCK — resetting to CLICK_TREE (was $state)")
        state          = State.CLICK_TREE
        treeGoneStreak = 0
        detector.invalidateCache()
        invDetect.invalidateCache()
        super.onStuck()
    }

    override suspend fun tick() {
        // Anti-ban hook — fires every 8-22 actions
        if (antiBan.shouldAntiBan()) {
            antiBan.runRandomAntiBanAction(service)
            antiBan.resetAntiBanCounter()
        }

        when (state) {
            // ── WALK_TO_TREES ──────────────────────────────────────────────
            State.WALK_TO_TREES -> {
                val tLoc = treeLocation; val bLoc = bankLocation
                if (tLoc != null && bLoc != null) {
                    setAction("Walking to trees...")
                    if (!walker.walkTo(bLoc, tLoc)) {
                        Logger.warn("Walker: no route — staying put"); delay(1_500L)
                    }
                } else { delay(800L) }
                state = State.CLICK_TREE
            }

            // ── CLICK_TREE ────────────────────────────────────────────────
            State.CLICK_TREE -> {
                // Real inventory check
                val occupied = invDetect.countOccupied()
                if (occupied >= 27) { state = State.WALK_TO_BANK; return }

                setAction("Finding tree... (inv: $occupied/28)")
                val detected = detector.detectObjects("woodcutting", treeGoneStreak >= 3)
                    .filter { it.confidence >= config.detectConfidenceMin
                           && !it.name.contains("bank", ignoreCase = true) }
                val nearest = detector.findNearest(detected, dm.widthPixels, dm.heightPixels)

                if (nearest != null) {
                    delay(antiBan.getClickDelay())
                    val (ox, oy) = antiBan.getClickOffset()
                    GestureHelper.tapHuman(service, nearest.bounds.exactCenterX() + ox, nearest.bounds.exactCenterY() + oy)
                    Logger.action("Tree @ (${nearest.bounds.centerX()}, ${nearest.bounds.centerY()}) conf=${"%.2f".format(nearest.confidence)}")
                } else {
                    val saved = TargetStore.nextTargetWhere { it.label.contains("tree", ignoreCase = true) }
                    if (saved == null) {
                        setAction("No tree found — set a TARGET or move closer")
                        delay(2_500L + Random.nextLong(0L, 500L)); return
                    }
                    delay(antiBan.getClickDelay())
                    val (ox, oy) = antiBan.getClickOffset()
                    GestureHelper.tapHuman(service, saved.x + ox, saved.y + oy)
                    Logger.action("Calibrated tree @ (${saved.x.toInt()}, ${saved.y.toInt()})")
                }

                setAction("Walking to tree...")
                delay(WALK_TO_TREE_MS + Random.nextLong(-500L, 1_500L))
                state = State.CHOPPING
            }

            // ── CHOPPING ──────────────────────────────────────────────────
            State.CHOPPING -> {
                val chopMs = antiBan.getWoodcuttingLogWaitMs()
                var rem = chopMs
                while (rem > 0) {
                    val slice = minOf(rem, 2_000L)
                    setAction("Chopping... ~${rem / 1_000}s")
                    delay(slice); rem -= slice
                }
                completeAction(XP_PER_LOG, GP_PER_LOG)
                treeGoneStreak = 0
                invDetect.invalidateCache()
                val occupied = invDetect.countOccupied()
                Logger.ok("Log #$actions | Inv: $occupied/28 | XP: $xpGained")
                delay(antiBan.getActionDelay())

                state = when {
                    occupied >= 27             -> State.WALK_TO_BANK
                    Random.nextFloat() < 0.25f -> State.TREE_GONE
                    else                       -> State.CLICK_TREE
                }
            }

            // ── TREE_GONE ─────────────────────────────────────────────────
            State.TREE_GONE -> {
                treeGoneStreak++
                setAction("Tree gone — waiting for respawn ($treeGoneStreak)...")
                if (treeGoneStreak >= 5) { detector.invalidateCache(); treeGoneStreak = 0 }
                delay(Random.nextLong(10_000L, 18_000L))
                state = State.CLICK_TREE
            }

            // ── WALK_TO_BANK ──────────────────────────────────────────────
            State.WALK_TO_BANK -> {
                setAction("Walking to bank...")
                val tLoc = treeLocation; val bLoc = bankLocation
                if (tLoc != null && bLoc != null) {
                    if (!walker.walkTo(tLoc, bLoc)) {
                        Logger.warn("Walker: no bank route — timed delay")
                        delay(antiBan.getBankingDelay())
                    }
                } else { delay(antiBan.getBankingDelay()) }
                state = State.BANKING
            }

            // ── BANKING ───────────────────────────────────────────────────
            State.BANKING -> {
                setAction("Banking logs...")
                val ok = banker.depositInventory()
                if (ok) {
                    invDetect.invalidateCache()
                    completeAction()
                    Logger.ok("Banked — total $actions logs | $xpGained XP")
                } else {
                    Logger.warn("Bank deposit failed — retrying")
                    delay(2_000L); return
                }
                delay(antiBan.getActionDelay())
                state = State.WALK_TO_TREES
            }
        }
    }
}
