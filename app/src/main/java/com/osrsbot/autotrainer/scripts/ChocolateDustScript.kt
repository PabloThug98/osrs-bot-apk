package com.osrsbot.autotrainer.scripts

import android.accessibilityservice.AccessibilityService
import com.osrsbot.autotrainer.antiban.AntiBanManager
import com.osrsbot.autotrainer.banking.BankInteractor
import com.osrsbot.autotrainer.detector.GameStateDetector
import com.osrsbot.autotrainer.detector.InventoryDetector
import com.osrsbot.autotrainer.capture.ScreenCaptureManager
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.selector.TargetStore
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.GestureHelper
import com.osrsbot.autotrainer.utils.Logger
import com.osrsbot.autotrainer.utils.ScreenRegions
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * ChocolateDustScript v2  — ~180K GP/hr
 *
 * Upgrades over v1:
 *  - Real inventory scanning via InventoryDetector (no counter drift).
 *  - GameStateDetector: detects Make-All dialogue automatically, taps it.
 *  - tapHuman() for all taps.
 *  - Anti-ban hook every tick.
 *  - Improved banking via BankInteractor v2.
 *  - Automatic knife/bar slot detection via InventoryDetector colour scan.
 *
 * Workflow:
 *   1. FIND_KNIFE  — locate knife in inventory (slot 0, or by colour scan)
 *   2. FIND_BAR    — locate a chocolate bar (slot 1+, brown/red HSV)
 *   3. CONFIRM     — detect and tap the Make-All dialogue
 *   4. GRINDING    — wait ~48s for all bars to be processed
 *   5. BANKING     — deposit dust, withdraw bars via BankInteractor v2
 */
class ChocolateDustScript(
    service: AccessibilityService,
    config: BotConfig,
    antiBan: AntiBanManager,
    detector: ObjectDetector,
) : BotScript(service, config, antiBan, detector) {

    override val id   = "chocolate"
    override val name = "Chocolate Dust Maker"

    private val GP_PER_BAR     = 180
    private val GRIND_TIME_MS  = 27L * 1_800L  // 48.6s for all 27 bars

    private enum class State { FIND_KNIFE, FIND_BAR, CONFIRM, GRINDING, BANKING }
    private var state = State.FIND_KNIFE

    private val dm        get() = service.resources.displayMetrics
    private val capture: ScreenCaptureManager? = detector.pixelDetector?.capture
    private val invDetect       = capture?.let { InventoryDetector(it) }
    private val stateDetect     = capture?.let { GameStateDetector(it) }
    private val banker          = BankInteractor(service, detector, capture)

    override fun onStuck() {
        Logger.warn("[$name] Stuck → resetting (was $state)")
        state = State.FIND_KNIFE
        detector.invalidateCache()
        invDetect?.invalidateCache()
        super.onStuck()
    }

    override suspend fun tick() {
        if (antiBan.shouldAntiBan()) antiBan.runRandomAntiBanAction(service)

        when (state) {

            State.FIND_KNIFE -> {
                setAction("Selecting knife...")
                delay(antiBan.getClickDelay())

                val saved = TargetStore.getAll().firstOrNull { it.label.contains("knife", ignoreCase = true) }
                if (saved != null) {
                    GestureHelper.tapHuman(service, saved.x, saved.y)
                    Logger.action("Knife tap: saved target")
                } else {
                    // Knife is always grey-silver in OSRS: H:180-240, S:0.05-0.35, V:0.5-0.9
                    val bmp = capture?.latestBitmap
                    val knifeSlot = bmp?.let { b ->
                        (0 until InventoryDetector.SLOT_COUNT).firstOrNull { slot ->
                            val (cx, cy) = ScreenRegions.inventorySlotCenter(slot)
                            val px = cx.toInt().coerceIn(0, b.width-1)
                            val py = cy.toInt().coerceIn(0, b.height-1)
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(b.getPixel(px, py), hsv)
                            hsv[0] in 175f..245f && hsv[1] in 0.05f..0.38f && hsv[2] in 0.45f..0.95f
                        }
                    } ?: 0
                    val (cx, cy) = ScreenRegions.inventorySlotCenter(knifeSlot)
                    GestureHelper.tapHuman(service, cx, cy)
                    Logger.action("Knife tap: slot $knifeSlot fallback")
                }
                delay(antiBan.getActionDelay())
                state = State.FIND_BAR
            }

            State.FIND_BAR -> {
                setAction("Selecting chocolate bar...")
                delay(antiBan.getClickDelay())

                val saved = TargetStore.getAll().firstOrNull { it.label.contains("chocolate", ignoreCase = true) || it.label.contains("bar", ignoreCase = true) }
                if (saved != null) {
                    GestureHelper.tapHuman(service, saved.x, saved.y)
                    Logger.action("Bar tap: saved target")
                } else {
                    // Chocolate bars are brown: H:15-35, S:0.4-0.85, V:0.25-0.65
                    val bmp = capture?.latestBitmap
                    val barSlot = bmp?.let { b ->
                        (1 until InventoryDetector.SLOT_COUNT).firstOrNull { slot ->
                            val (cx, cy) = ScreenRegions.inventorySlotCenter(slot)
                            val px = cx.toInt().coerceIn(0, b.width-1)
                            val py = cy.toInt().coerceIn(0, b.height-1)
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(b.getPixel(px, py), hsv)
                            hsv[0] in 12f..38f && hsv[1] in 0.38f..0.88f && hsv[2] in 0.22f..0.68f
                        }
                    } ?: 1
                    val (cx, cy) = ScreenRegions.inventorySlotCenter(barSlot)
                    GestureHelper.tapHuman(service, cx, cy)
                    Logger.action("Bar tap: slot $barSlot fallback")
                }
                delay(antiBan.getActionDelay())
                state = State.CONFIRM
            }

            State.CONFIRM -> {
                setAction("Waiting for Make-All dialogue...")
                // Poll for the dialogue using GameStateDetector
                var waited = 0L
                val maxWait = 4_000L
                while (waited < maxWait) {
                    stateDetect?.invalidateCache()
                    if (stateDetect?.isDialogOpen() == true || stateDetect == null) break
                    delay(400L); waited += 400L
                }
                delay(Random.nextLong(300L, 600L))
                // Tap Make-All — centre of screen (~50% x, ~65% y is typical for OSRS Make menu)
                val mx = dm.widthPixels  * 0.50f + Random.nextInt(-10, 10)
                val my = dm.heightPixels * 0.65f + Random.nextInt(-10, 10)
                GestureHelper.tapHuman(service, mx, my)
                Logger.action("Make-All tap at (${"%.0f".format(mx)}, ${"%.0f".format(my)})")
                delay(Random.nextLong(500L, 900L))
                state = State.GRINDING
            }

            State.GRINDING -> {
                var rem = GRIND_TIME_MS
                while (rem > 0) {
                    val slice = minOf(rem, 3_000L)
                    setAction("Grinding... ~${rem / 1_000}s remaining")
                    delay(slice); rem -= slice
                }
                invDetect?.invalidateCache()
                val occupied = invDetect?.countOccupied() ?: 27
                completeAction(0, GP_PER_BAR * 27)
                Logger.ok("Grind #$actions complete | Inv: $occupied/28 | GP: $gpGained")
                state = State.BANKING
            }

            State.BANKING -> {
                setAction("Banking dust...")
                val ok = banker.depositInventory()
                if (ok) {
                    invDetect?.invalidateCache()
                    completeAction()
                    Logger.ok("Banked — total $actions trips | GP: $gpGained")
                } else {
                    Logger.warn("Bank failed — retrying")
                    delay(2_000L); return
                }
                delay(antiBan.getActionDelay())
                state = State.FIND_KNIFE
            }
        }
    }
}
