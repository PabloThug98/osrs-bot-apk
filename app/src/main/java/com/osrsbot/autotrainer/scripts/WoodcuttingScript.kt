package com.osrsbot.autotrainer.scripts

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.osrsbot.autotrainer.antiban.AntiBanManager
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.selector.TargetStore
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.Logger
import com.osrsbot.autotrainer.walker.WalkerManager
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Woodcutting Bot — chops trees and banks logs.
 *
 * ── How OSRS woodcutting actually works ──────────────────────────────────────
 * 1. You click a tree → character walks to it (0–5 s depending on distance).
 * 2. Once adjacent, the character auto-chops. Each "chop attempt" = 3 game
 *    ticks = 1.8 s. Low levels need many attempts before getting a log.
 * 3. You do NOT need to re-click while chopping — the character keeps going.
 *    Re-clicking cancels the action and makes the character walk elsewhere,
 *    which was the "runs randomly" bug in the previous version.
 * 4. After receiving a log the character continues on the same tree.
 *    The tree may fall at any time; character becomes idle when it does.
 * 5. When idle (tree gone) → find the next tree.
 *
 * ── State machine ─────────────────────────────────────────────────────────────
 *  WALK_TO_TREES → use WalkerManager to reach tree area after banking
 *  CLICK_TREE    → single tap on the tree, then wait for walk
 *  CHOPPING      → wait silently for the full log time; NEVER click during this
 *  TREE_GONE     → tree fell; wait for respawn or rotate target
 *  WALK_TO_BANK  → use WalkerManager to reach the bank
 *  BANKING       → simulate deposit and withdraw
 */
class WoodcuttingScript(
    service: AccessibilityService,
    config: BotConfig,
    antiBan: AntiBanManager,
    detector: ObjectDetector,
) : BotScript(service, config, antiBan, detector) {

    override val id   = "woodcutting"
    override val name = "🌲 Woodcutting Bot"

    // ── Constants ─────────────────────────────────────────────────────────────
    private val XP_PER_LOG = 38
    private val GP_PER_LOG = 50

    // After clicking a tree, wait this long for the character to WALK to it
    // before the chop timer starts.
    private val WALK_TO_TREE_MS = 3_000L

    // ── State machine ─────────────────────────────────────────────────────────
    private enum class State {
        WALK_TO_TREES,  // walk from bank back to tree area
        CLICK_TREE,     // tap the tree once
        CHOPPING,       // wait silently — DO NOT click anything
        TREE_GONE,      // tree fell; wait for respawn
        WALK_TO_BANK,   // walk to bank
        BANKING,        // deposit logs
    }

    private var state = State.CLICK_TREE
    private var logsInInventory = 0
    private var treeGoneStreak  = 0

    // ── Walker setup ──────────────────────────────────────────────────────────
    private val walker = WalkerManager(service)

    // Set these to enable automatic walking between tree spot and bank.
    // Leave null to skip walking (bot stays wherever it is).
    var treeLocation: WalkerManager.Location? = null
    var bankLocation: WalkerManager.Location? = null

    private val dm get() = service.resources.displayMetrics

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
                    if (!ok) {
                        Logger.warn("Walker: no route defined — staying put")
                        delay(1500L)
                    }
                } else {
                    // No location configured; assume already near trees
                    delay(800L)
                }
                state = State.CLICK_TREE
            }

            // ── CLICK_TREE ───────────────────────────────────────────────────
            State.CLICK_TREE -> {
                if (logsInInventory >= 27) {
                    state = State.WALK_TO_BANK
                    return
                }
                setAction("Finding tree…")

                val target = TargetStore.nextTarget()
                if (target != null) {
                    delay(antiBan.getClickDelay())
                    val (ox, oy) = antiBan.getClickOffset()
                    tap(target.x + ox.toFloat(), target.y + oy.toFloat())
                    Logger.action("Clicking '${target.label}' @ (${target.x.toInt()}, ${target.y.toInt()})")
                } else {
                    // Try accessibility detection
                    val detected = detector.detectObjects("woodcutting")
                    val nearest  = detector.findNearest(detected, dm.widthPixels, dm.heightPixels)
                    if (nearest != null) {
                        delay(antiBan.getClickDelay())
                        val (ox, oy) = antiBan.getClickOffset()
                        tap(nearest.bounds.exactCenterX() + ox, nearest.bounds.exactCenterY() + oy)
                        Logger.action("Detected tree — clicking @ (${nearest.bounds.centerX()}, ${nearest.bounds.centerY()})")
                    } else {
                        setAction("No tree visible — tap 🎯 to save a target!")
                        delay(2500L + Random.nextLong(0L, 500L))
                        return  // retry CLICK_TREE on next tick
                    }
                }

                // Wait for character to walk to the tree before starting chop timer.
                // This prevents the timer from counting walk time as chop time.
                setAction("Walking to tree…")
                delay(WALK_TO_TREE_MS + Random.nextLong(-500L, 1500L))
                state = State.CHOPPING
            }

            // ── CHOPPING ─────────────────────────────────────────────────────
            // The character is now auto-chopping.
            // DO NOT tap or move the character — any click cancels the chop and
            // causes the character to run, which was the "runs randomly" bug.
            // Just wait out the full log-acquisition time.
            State.CHOPPING -> {
                val chopMs = antiBan.getWoodcuttingLogWaitMs()
                var remaining = chopMs
                while (remaining > 0) {
                    val slice = minOf(remaining, 2_000L)
                    setAction("Chopping… ~${remaining / 1000}s")
                    delay(slice)
                    remaining -= slice
                }

                logsInInventory++
                completeAction(XP_PER_LOG, GP_PER_LOG)
                treeGoneStreak = 0
                Logger.ok("Log #$actions | Inv: $logsInInventory/27 | XP: $xpGained")

                delay(antiBan.getActionDelay())

                state = when {
                    logsInInventory >= 27 -> State.WALK_TO_BANK
                    // ~25% chance tree fell after a log — simulate random tree depletion
                    Random.nextFloat() < 0.25f -> State.TREE_GONE
                    else -> State.CLICK_TREE  // tree still there — click again
                }
            }

            // ── TREE_GONE ────────────────────────────────────────────────────
            State.TREE_GONE -> {
                treeGoneStreak++
                setAction("Tree gone — waiting for respawn… ($treeGoneStreak)")
                if (treeGoneStreak >= 5) {
                    Logger.warn("Tree hasn't respawned — rotating to next target")
                    treeGoneStreak = 0
                }
                // Regular trees: ~10 s respawn. Oaks: ~15 s. Use 10–18 s range.
                val wait = Random.nextLong(10_000L, 18_000L)
                delay(wait)
                state = State.CLICK_TREE
            }

            // ── WALK_TO_BANK ─────────────────────────────────────────────────
            State.WALK_TO_BANK -> {
                setAction("Walking to bank…")
                val tLoc = treeLocation
                val bLoc = bankLocation
                if (tLoc != null && bLoc != null) {
                    val ok = walker.walkTo(tLoc, bLoc)
                    if (!ok) {
                        Logger.warn("Walker: no bank route — using timed delay")
                        delay(antiBan.getBankingDelay())
                    }
                } else {
                    delay(antiBan.getBankingDelay())
                }
                state = State.BANKING
            }

            // ── BANKING ──────────────────────────────────────────────────────
            State.BANKING -> {
                setAction("Banking logs…")
                // Simulate: tap bank → deposit all → close → brief pause
                val bankActionMs = 5_500L + Random.nextLong(-500L, 1_500L)
                delay(bankActionMs)
                logsInInventory = 0
                Logger.ok("Banked — total $actions logs | $xpGained XP")
                delay(antiBan.getActionDelay())
                state = State.WALK_TO_TREES
            }
        }
    }

    // ── Tap helper ────────────────────────────────────────────────────────────
    private fun tap(x: Float, y: Float) {
        val path   = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        service.dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(), null, null
        )
    }
}
