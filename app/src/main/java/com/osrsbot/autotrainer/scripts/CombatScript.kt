package com.osrsbot.autotrainer.scripts

import android.accessibilityservice.AccessibilityService
import com.osrsbot.autotrainer.antiban.AntiBanManager
import com.osrsbot.autotrainer.detector.HpMonitor
import com.osrsbot.autotrainer.detector.InventoryDetector
import com.osrsbot.autotrainer.capture.ScreenCaptureManager
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.GestureHelper
import com.osrsbot.autotrainer.utils.Logger
import com.osrsbot.autotrainer.utils.ScreenRegions
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * CombatScript v2
 *
 * Upgrades:
 *  - Real HP monitoring via HpMonitor (reads HP orb pixels).
 *  - Eats from a real inventory slot instead of a hardcoded screen fraction.
 *  - Anti-ban hook on every tick.
 *  - tapHuman() for all interactions.
 *  - Loot scan after kill (looks for items on ground via PixelDetector).
 */
class CombatScript(
    service: AccessibilityService,
    config: BotConfig,
    antiBan: AntiBanManager,
    detector: ObjectDetector,
) : BotScript(service, config, antiBan, detector) {

    override val id   = "combat"
    override val name = "Combat Trainer"

    private val XP_PER_KILL = 60
    private val GP_PER_KILL = 20

    /** Eat when HP drops to or below this fraction. */
    private val EAT_THRESHOLD  = 0.50f
    /** Emergency eat threshold. */
    private val PANIC_THRESHOLD = 0.25f

    private val PRIORITY_MONSTERS = listOf(
        "goblin", "chicken", "cow", "imp", "rat", "spider",
        "man", "woman", "barbarian", "guard", "zombie", "skeleton",
        "rock crab", "sand crab", "moss giant", "hill giant"
    )

    private enum class State { FIND_MONSTER, IN_COMBAT, LOOTING, EATING }
    private var state      = State.FIND_MONSTER
    private var missStreak = 0

    private val dm       get() = service.resources.displayMetrics
    private val capture: ScreenCaptureManager? = detector.pixelDetector?.capture
    private val hpMon    = capture?.let { HpMonitor(it) }
    private val invDetect = capture?.let { InventoryDetector(it) }

    override fun onStuck() {
        Logger.warn("[$name] Stuck — resetting to FIND_MONSTER")
        state = State.FIND_MONSTER
        missStreak = 0
        detector.invalidateCache()
        super.onStuck()
    }

    override suspend fun tick() {
        if (antiBan.shouldAntiBan()) antiBan.runRandomAntiBanAction(service)

        // HP check runs every tick — safety first
        val hp = hpMon?.getHpFraction() ?: 0.99f
        if (hp <= PANIC_THRESHOLD && state != State.EATING) {
            Logger.warn("PANIC EAT — HP at ${(hp * 100).toInt()}%")
            state = State.EATING
        }

        when (state) {

            State.FIND_MONSTER -> {
                if (hp <= EAT_THRESHOLD) { state = State.EATING; return }
                setAction("Looking for monster... (HP: ${(hp * 100).toInt()}%)")

                val detected = detector.detectObjects("combat", missStreak >= 3)
                    .filter { it.confidence >= config.detectConfidenceMin }

                val monster = PRIORITY_MONSTERS.firstNotNullOfOrNull { kw ->
                    detector.findBestMatch(detected, kw)
                } ?: detector.findNearest(detected, dm.widthPixels, dm.heightPixels)

                if (monster != null) {
                    delay(antiBan.getClickDelay())
                    val (ox, oy) = antiBan.getClickOffset()
                    GestureHelper.tapHuman(service, monster.bounds.exactCenterX() + ox, monster.bounds.exactCenterY() + oy)
                    Logger.action("${monster.name} @ (${monster.bounds.centerX()},${monster.bounds.centerY()}) conf=${"%.2f".format(monster.confidence)}")
                    state = State.IN_COMBAT; missStreak = 0
                } else {
                    missStreak++
                    setAction("No monster found (miss $missStreak)")
                    delay(2_000L + Random.nextLong(0, 500))
                }
            }

            State.IN_COMBAT -> {
                val killMs = antiBan.getCombatKillDelay()
                setAction("Fighting... (${killMs / 1000}s) HP: ${(hp * 100).toInt()}%")
                // Poll HP during combat — eat if drops low mid-fight
                var rem = killMs
                while (rem > 0) {
                    val slice = minOf(rem, 1_500L)
                    delay(slice); rem -= slice
                    val curHp = hpMon?.getHpFraction(forceRefresh = true) ?: 1f
                    if (curHp <= EAT_THRESHOLD) {
                        Logger.warn("HP dropped to ${(curHp * 100).toInt()}% mid-fight — eating")
                        state = State.EATING; return
                    }
                }
                completeAction(XP_PER_KILL, GP_PER_KILL)
                hpMon?.invalidateCache()
                Logger.ok("Kill #$actions | XP: $xpGained | HP: ${(hp * 100).toInt()}%")
                state = State.LOOTING
            }

            State.LOOTING -> {
                setAction("Checking for loot...")
                delay(antiBan.getActionDelay())
                // Try to pick up any detected item
                val loot = detector.detectObjects("combat", true)
                    .filter { it.name.equals("Item", ignoreCase = true) && it.confidence > 0.4f }
                    .maxByOrNull { it.confidence }
                if (loot != null) {
                    delay(antiBan.getClickDelay())
                    GestureHelper.tapHuman(service, loot.bounds.exactCenterX(), loot.bounds.exactCenterY())
                    Logger.action("Loot tap @ (${loot.bounds.centerX()}, ${loot.bounds.centerY()})")
                    delay(800L)
                }
                detector.invalidateCache()
                state = State.FIND_MONSTER
            }

            State.EATING -> {
                setAction("Eating food... HP: ${(hp * 100).toInt()}%")
                val ate = eatFood()
                if (ate) {
                    hpMon?.invalidateCache()
                    delay(1_800L + Random.nextLong(0, 400))
                    val newHp = hpMon?.getHpFraction(forceRefresh = true) ?: 1f
                    Logger.ok("Ate food — HP now ~${(newHp * 100).toInt()}%")
                    completeAction()
                } else {
                    Logger.warn("No food found in inventory!")
                    delay(2_000L)
                }
                state = State.FIND_MONSTER
            }
        }
    }

    /**
     * Finds and clicks food in the inventory.
     * Looks for a red/orange coloured item (most OSRS food is reddish).
     * Returns true if a food tap was attempted.
     */
    private suspend fun eatFood(): Boolean {
        val bmp     = capture?.latestBitmap ?: return fallbackEat()
        val invDet  = invDetect ?: return fallbackEat()
        // Scan for a red/orange item (food) — hue 0-35 or 340-360
        val foodSlot = invDet.findSlotByColor(bmp, 0f, 35f)
            .takeIf { it >= 0 }
            ?: invDet.findSlotByColor(bmp, 340f, 360f).takeIf { it >= 0 }
            ?: return fallbackEat()

        val (cx, cy) = ScreenRegions.inventorySlotCenter(foodSlot)
        GestureHelper.tapHuman(service, cx, cy)
        Logger.action("Ate from inventory slot $foodSlot @ (${"%.0f".format(cx)}, ${"%.0f".format(cy)})")
        return true
    }

    /** Fallback: tap a hardcoded area when pixel detection is unavailable. */
    private suspend fun fallbackEat(): Boolean {
        val cx = dm.widthPixels  * 0.62f + Random.nextInt(-15, 15)
        val cy = dm.heightPixels * 0.815f + Random.nextInt(-15, 15)
        GestureHelper.tapHuman(service, cx, cy)
        Logger.warn("Fallback eat at (${"%.0f".format(cx)}, ${"%.0f".format(cy)})")
        return true
    }
}
