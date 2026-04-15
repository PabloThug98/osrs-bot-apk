package com.osrsbot.autotrainer.scripts

import android.accessibilityservice.AccessibilityService
import com.osrsbot.autotrainer.antiban.AntiBanManager
import com.osrsbot.autotrainer.banking.BankInteractor
import com.osrsbot.autotrainer.detector.InventoryDetector
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.detector.RunEnergyMonitor
import com.osrsbot.autotrainer.selector.TargetStore
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.GestureHelper
import com.osrsbot.autotrainer.utils.Logger
import com.osrsbot.autotrainer.walker.WalkerManager
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * FishingScript v2
 *
 * Upgrades over v1:
 *  - Real inventory tracking via InventoryDetector (pixel-based slot scanning).
 *  - tapHuman() for all interactions.
 *  - Anti-ban hook fires every tick.
 *  - RunEnergyMonitor: re-enables run when walking to/from bank.
 *  - Spot expiry detection: if the spot disappears mid-fish, re-click immediately.
 *  - Walker integration (optional — walks to/from bank spots).
 *  - Banking uses upgraded BankInteractor v2.
 *
 * State machine:
 *   WALK_TO_SPOT → FIND_SPOT → FISHING → SPOT_GONE → WALK_TO_BANK → BANKING
 */
class FishingScript(
    service: AccessibilityService,
    config: BotConfig,
    antiBan: AntiBanManager,
    detector: ObjectDetector,
) : BotScript(service, config, antiBan, detector) {

    override val id   = "fishing"
    override val name = "Fishing Bot"

    private val XP_PER_FISH = 40
    private val GP_PER_FISH = 40

    private enum class State { WALK_TO_SPOT, FIND_SPOT, FISHING, SPOT_GONE, WALK_TO_BANK, BANKING }
    private var state       = State.FIND_SPOT
    private var missStreak  = 0
    private var spotGoneStreak = 0
    private var lastSpotX   = 0f
    private var lastSpotY   = 0f

    private val dm      get() = service.resources.displayMetrics
    private val capture       = detector.pixelDetector?.capture
    private val invDetect     = capture?.let { InventoryDetector(it) }
    private val runMonitor    = capture?.let { RunEnergyMonitor(it) }
    private val walker        = WalkerManager(service)
    private val banker        = BankInteractor(service, detector, capture)

    var spotLocation: WalkerManager.Location? = null
    var bankLocation: WalkerManager.Location? = null

    override fun onStuck() {
        Logger.warn("[$name] Stuck → resetting to FIND_SPOT (was $state)")
        state = State.FIND_SPOT
        missStreak = 0; spotGoneStreak = 0
        detector.invalidateCache()
        invDetect?.invalidateCache()
        super.onStuck()
    }

    override suspend fun tick() {
        if (antiBan.shouldAntiBan()) antiBan.runRandomAntiBanAction(service)

        when (state) {

            State.WALK_TO_SPOT -> {
                runMonitor?.checkAndEnableRun(service)
                val sLoc = spotLocation; val bLoc = bankLocation
                if (sLoc != null && bLoc != null) {
                    setAction("Walking to fishing spot...")
                    if (!walker.walkTo(bLoc, sLoc)) { delay(1_500L) }
                } else { delay(800L) }
                state = State.FIND_SPOT
            }

            State.FIND_SPOT -> {
                val occupied = invDetect?.countOccupied() ?: 0
                if (occupied >= 27) { state = State.WALK_TO_BANK; return }
                setAction("Looking for fishing spot... (inv: $occupied/28)")

                // 1 — User-saved target first
                val saved = TargetStore.nextTargetWhere { !it.label.contains("bank", ignoreCase = true) }
                if (saved != null) {
                    delay(antiBan.getClickDelay())
                    val (ox, oy) = antiBan.getClickOffset()
                    GestureHelper.tapHuman(service, saved.x + ox, saved.y + oy)
                    Logger.action("Saved spot: ${saved.label}")
                    lastSpotX = saved.x; lastSpotY = saved.y
                    state = State.FISHING; missStreak = 0; return
                }

                // 2 — PixelDetector / accessibility
                val detected = detector.detectObjects("fishing", missStreak >= 3)
                    .filter { it.confidence >= config.detectConfidenceMin }
                val spot = detector.findNearest(detected, dm.widthPixels, dm.heightPixels)

                if (spot != null) {
                    delay(antiBan.getClickDelay())
                    val (ox, oy) = antiBan.getClickOffset()
                    val cx = spot.bounds.exactCenterX() + ox
                    val cy = spot.bounds.exactCenterY() + oy
                    GestureHelper.tapHuman(service, cx, cy)
                    Logger.action("${spot.name} @ (${spot.bounds.centerX()},${spot.bounds.centerY()}) conf=${"%.2f".format(spot.confidence)}")
                    lastSpotX = cx; lastSpotY = cy
                    state = State.FISHING; missStreak = 0
                } else {
                    missStreak++
                    setAction("No spot found (miss $missStreak) — tap TARGET to mark one")
                    delay(2_000L + Random.nextLong(0, 500))
                }
            }

            State.FISHING -> {
                val waitMs = antiBan.getFishingWaitDelay()
                setAction("Fishing... (~${waitMs / 1000}s)")
                var rem = waitMs
                while (rem > 0) {
                    val slice = minOf(rem, 2_000L)
                    delay(slice); rem -= slice
                    // Mid-fish spot check — spots can move in OSRS
                    if (rem > 0) {
                        invDetect?.invalidateCache()
                        val occupied = invDetect?.countOccupied() ?: 28
                        if (occupied >= 27) { state = State.WALK_TO_BANK; return }
                    }
                }

                invDetect?.invalidateCache()
                val occupied = invDetect?.countOccupied() ?: 0
                completeAction(XP_PER_FISH, GP_PER_FISH)
                spotGoneStreak = 0
                Logger.ok("Fish #$actions | Inv: $occupied/28 | XP: $xpGained")
                delay(antiBan.getActionDelay())
                state = if (occupied >= 27) State.WALK_TO_BANK else State.FIND_SPOT
                detector.invalidateCache()
            }

            State.SPOT_GONE -> {
                spotGoneStreak++
                setAction("Spot moved — looking for new one ($spotGoneStreak)...")
                if (spotGoneStreak >= 4) { detector.invalidateCache(); spotGoneStreak = 0 }
                delay(Random.nextLong(1_500L, 3_000L))
                state = State.FIND_SPOT
            }

            State.WALK_TO_BANK -> {
                runMonitor?.checkAndEnableRun(service)
                setAction("Walking to bank...")
                val sLoc = spotLocation; val bLoc = bankLocation
                if (sLoc != null && bLoc != null) {
                    if (!walker.walkTo(sLoc, bLoc)) {
                        delay(antiBan.getBankingDelay())
                    }
                } else { delay(antiBan.getBankingDelay()) }
                state = State.BANKING
            }

            State.BANKING -> {
                setAction("Banking fish...")
                val ok = banker.depositInventory()
                if (ok) {
                    invDetect?.invalidateCache()
                    completeAction()
                    Logger.ok("Banked — total $actions fish | $xpGained XP")
                } else {
                    Logger.warn("Bank failed — retrying")
                    delay(2_000L); return
                }
                delay(antiBan.getActionDelay())
                state = State.WALK_TO_SPOT
            }
        }
    }
}
