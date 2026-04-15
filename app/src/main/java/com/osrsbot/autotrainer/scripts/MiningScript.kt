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
import kotlin.math.abs
import kotlin.random.Random

/**
 * MiningScript — mines ores and banks them.
 *
 * Supports all F2P rocks detected by PixelDetector (ORE_ROCK / ORE_ROCK_IRON).
 *
 * State machine:
 *   WALK_TO_ROCKS → FIND_ROCK → MINING → ROCK_DEPLETED → WALK_TO_BANK → BANKING
 */
class MiningScript(
    service: AccessibilityService,
    config: BotConfig,
    antiBan: AntiBanManager,
    detector: ObjectDetector,
) : BotScript(service, config, antiBan, detector) {

    override val id   = "mining"
    override val name = "Mining Bot"

    private val XP_TABLE = mapOf(
        "copper" to 17, "tin" to 17, "iron" to 35, "coal" to 50,
        "gold" to 65, "mithril" to 80, "adamantite" to 95, "runite" to 125
    )
    private val GP_PER_ORE = 80

    private enum class State { WALK_TO_ROCKS, FIND_ROCK, MINING, ROCK_DEPLETED, WALK_TO_BANK, BANKING }
    private var state          = State.FIND_ROCK
    private var depletedStreak = 0
    private var sameStreak     = 0
    private var lastCx         = -1
    private var lastCy         = -1

    private val dm        get() = service.resources.displayMetrics
    private val capture         = detector.pixelDetector?.capture
    private val invDetect       = capture?.let { InventoryDetector(it) }
    private val walker          = WalkerManager(service)
    private val banker          = BankInteractor(service, detector, capture)

    var rockLocation: WalkerManager.Location? = null
    var bankLocation: WalkerManager.Location? = null

    override fun onStuck() {
        Logger.warn("[$name] Stuck → FIND_ROCK (was $state)")
        state = State.FIND_ROCK; depletedStreak = 0; sameStreak = 0; lastCx = -1; lastCy = -1
        detector.invalidateCache(); invDetect?.invalidateCache(); super.onStuck()
    }

    override suspend fun tick() {
        if (antiBan.shouldAntiBan()) antiBan.runRandomAntiBanAction(service)

        when (state) {

            State.WALK_TO_ROCKS -> {
                val rL = rockLocation; val bL = bankLocation
                if (rL != null && bL != null) { setAction("Walking to rocks..."); if (!walker.walkTo(bL, rL)) delay(1_500L) }
                else delay(800L)
                state = State.FIND_ROCK
            }

            State.FIND_ROCK -> {
                val occ = invDetect?.countOccupied() ?: 0
                if (occ >= 27) { state = State.WALK_TO_BANK; return }
                setAction("Finding ore rock... (inv: $occ/28)")

                val rocks = detector.detectObjects("mining", sameStreak >= 3)
                    .filter { it.confidence >= config.detectConfidenceMin && !it.name.contains("bank", ignoreCase = true) }
                val rock  = detector.findNearest(rocks, dm.widthPixels, dm.heightPixels)

                if (rock != null) {
                    currentDetectedObjects = rocks
                    val cx = rock.bounds.centerX(); val cy = rock.bounds.centerY()
                    sameStreak = if (abs(cx - lastCx) < 30 && abs(cy - lastCy) < 30) sameStreak + 1 else 0
                    delay(antiBan.getClickDelay())
                    val (ox, oy) = antiBan.getClickOffset()
                    GestureHelper.tapHuman(service, rock.bounds.exactCenterX() + ox, rock.bounds.exactCenterY() + oy)
                    Logger.action("${rock.name} @ ($cx,$cy) conf=${"%.2f".format(rock.confidence)} streak=$sameStreak")
                    lastCx = cx; lastCy = cy
                    delay(2_000L + Random.nextLong(-300, 800))
                    state = State.MINING
                } else {
                    val saved = TargetStore.nextTargetWhere { it.label.contains("rock", ignoreCase = true) || it.label.contains("ore", ignoreCase = true) }
                    if (saved != null) {
                        delay(antiBan.getClickDelay())
                        GestureHelper.tapHuman(service, saved.x, saved.y)
                        Logger.action("Saved rock: ${saved.label}")
                        delay(2_000L); state = State.MINING
                    } else {
                        setAction("No rock found — set TARGET or move closer"); delay(2_500L + Random.nextLong(0, 500))
                    }
                }
            }

            State.MINING -> {
                val mineMs = getMiningDelay()
                var rem = mineMs
                while (rem > 0) { val sl = minOf(rem, 2_000L); setAction("Mining... ~${rem/1_000}s"); delay(sl); rem -= sl }
                if (sameStreak >= 2) { state = State.ROCK_DEPLETED; return }
                invDetect?.invalidateCache()
                val occ = invDetect?.countOccupied() ?: 0
                val xp = XP_TABLE[config.oreType] ?: 35
                completeAction(xp, GP_PER_ORE)
                depletedStreak = 0
                Logger.ok("Ore #$actions (${config.oreType}) | Inv:$occ/28 | XP:$xpGained")
                delay(antiBan.getActionDelay())
                state = if (occ >= 27) State.WALK_TO_BANK else State.FIND_ROCK
            }

            State.ROCK_DEPLETED -> {
                depletedStreak++
                val respawn = getRespawnMs()
                setAction("Rock depleted — waiting ${respawn/1_000}s ($depletedStreak)...")
                if (depletedStreak >= 5) { detector.invalidateCache(); depletedStreak = 0 }
                delay(respawn); sameStreak = 0; state = State.FIND_ROCK
            }

            State.WALK_TO_BANK -> {
                setAction("Walking to bank...")
                val rL = rockLocation; val bL = bankLocation
                if (rL != null && bL != null) { if (!walker.walkTo(rL, bL)) delay(antiBan.getBankingDelay()) }
                else delay(antiBan.getBankingDelay())
                state = State.BANKING
            }

            State.BANKING -> {
                setAction("Banking ore...")
                val ok = banker.depositInventory()
                if (ok) { invDetect?.invalidateCache(); completeAction(); Logger.ok("Banked — $actions ores | $xpGained XP") }
                else { Logger.warn("Bank failed — retry"); delay(2_000L); return }
                delay(antiBan.getActionDelay()); state = State.WALK_TO_ROCKS
            }
        }
    }

    private fun getMiningDelay(): Long {
        val lvl = config.playerMiningLevel.coerceIn(1, 99)
        val (mn, mx) = when { lvl < 20 -> 7_000L to 14_000L; lvl < 40 -> 5_500L to 10_000L; lvl < 60 -> 4_000L to 8_000L; lvl < 80 -> 3_000L to 6_500L; else -> 2_500L to 5_000L }
        val base = Random.nextLong(mn, mx)
        return (base + (base * 0.12f * (Random.nextFloat() * 2f - 1f)).toLong()).coerceAtLeast(2_000L)
    }

    private fun getRespawnMs(): Long = when (config.oreType.lowercase()) {
        "copper", "tin"  -> Random.nextLong(40_000L, 60_000L)
        "iron"           -> Random.nextLong(45_000L, 65_000L)
        "coal"           -> Random.nextLong(55_000L, 80_000L)
        "gold"           -> Random.nextLong(65_000L, 90_000L)
        "mithril"        -> Random.nextLong(90_000L, 110_000L)
        "adamantite"     -> Random.nextLong(100_000L, 120_000L)
        "runite"         -> Random.nextLong(1_200_000L, 1_320_000L)
        else             -> Random.nextLong(50_000L, 70_000L)
    }
}
