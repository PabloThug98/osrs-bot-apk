package com.osrsbot.autotrainer.walker

import android.accessibilityservice.AccessibilityService
import com.osrsbot.autotrainer.utils.GestureHelper
import com.osrsbot.autotrainer.utils.Logger
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Universal F2P Walker — navigates between named locations using minimap taps.
 *
 * The OSRS minimap is a circle in the top-right of the screen (portrait mode).
 * Tapping a point on the minimap makes the player walk toward that point.
 * One full-radius tap moves roughly 15–25 tiles; each step waits for the
 * walk animation to finish before taking the next.
 *
 * Supported F2P locations:
 *   Lumbridge, Draynor Village, Varrock, Falador, Edgeville, Barbarian Village
 */
class WalkerManager(private val service: AccessibilityService) {

    private val dm get() = service.resources.displayMetrics

    // ── Minimap geometry (fraction of screen size, portrait) ─────────────────
    // Top-right circle: centre ≈ 90% from left, 9% from top; radius ≈ 7.5% width
    private val mmCxF = 0.905f
    private val mmCyF = 0.095f
    private val mmRF  = 0.070f   // fraction of screenWidth, slightly inside edge

    // ── Compass angles — 0° East, 90° North (standard maths, Y-flipped for screen)
    object Dir {
        const val N   =  90f
        const val NNE =  67f
        const val NE  =  45f
        const val ENE =  22f
        const val E   =   0f
        const val ESE = -22f
        const val SE  = -45f
        const val SSE = -67f
        const val S   = -90f
        const val SSW = -112f
        const val SW  = -135f
        const val WSW = -157f
        const val W   =  180f
        const val WNW =  157f
        const val NW  =  135f
        const val NNW =  112f
    }

    // ── Named F2P locations ───────────────────────────────────────────────────
    enum class Location {
        // Lumbridge
        LUMBRIDGE_TREES,          // trees NE of Lumbridge castle
        LUMBRIDGE_BANK,           // Lumbridge castle 2nd floor bank

        // Draynor Village
        DRAYNOR_WILLOWS,          // willow trees south of Draynor bank
        DRAYNOR_BANK,             // Draynor Village bank

        // Varrock
        VARROCK_TREES_EAST,       // trees east of Varrock East bank
        VARROCK_TREES_WEST,       // trees NW of Varrock west bank
        VARROCK_EAST_BANK,        // Varrock East bank
        VARROCK_WEST_BANK,        // Varrock West bank

        // Falador
        FALADOR_PARK_TREES,       // trees in Falador park (centre of city)
        FALADOR_WEST_BANK,        // Falador West bank
        FALADOR_EAST_BANK,        // Falador East bank

        // Edgeville
        EDGEVILLE_TREES,          // trees south of Edgeville bank
        EDGEVILLE_BANK,           // Edgeville bank

        // Barbarian Village
        BARBARIAN_VILLAGE_TREES,  // trees in Barbarian Village (oaks/regulars)

        // Port Sarim
        PORT_SARIM_TREES,         // trees near Port Sarim

        // Al-Kharid
        AL_KHARID_BANK,           // Al-Kharid bank
    }

    // ── Walk step: one minimap tap with a direction, repeat count, and wait ──
    data class WalkStep(
        val angleDeg: Float,
        val repeats: Int   = 1,
        val waitMs: Long   = 4000L,
    )

    // ── Route table: from → to → ordered list of steps ───────────────────────
    private val routes: Map<Pair<Location, Location>, List<WalkStep>> = buildMap {

        fun route(from: Location, to: Location, vararg steps: WalkStep) {
            put(from to to, steps.toList())
        }

        // ── Lumbridge ──────────────────────────────────────────────────────
        // Bank is inside castle (SW), trees are NE outside castle
        route(LUMBRIDGE_BANK,   LUMBRIDGE_TREES, WalkStep(Dir.NE, 2, 3800L))
        route(LUMBRIDGE_TREES,  LUMBRIDGE_BANK,  WalkStep(Dir.SW, 2, 3800L))

        // ── Draynor Village ────────────────────────────────────────────────
        // Bank is near centre, willows are ~15 tiles south
        route(DRAYNOR_BANK,     DRAYNOR_WILLOWS, WalkStep(Dir.S,  1, 3200L))
        route(DRAYNOR_WILLOWS,  DRAYNOR_BANK,    WalkStep(Dir.N,  1, 3200L))

        // ── Varrock East ───────────────────────────────────────────────────
        route(VARROCK_EAST_BANK,   VARROCK_TREES_EAST,
            WalkStep(Dir.NE, 1, 3500L))
        route(VARROCK_TREES_EAST,  VARROCK_EAST_BANK,
            WalkStep(Dir.SW, 1, 3500L))

        // ── Varrock West ───────────────────────────────────────────────────
        route(VARROCK_WEST_BANK,   VARROCK_TREES_WEST,
            WalkStep(Dir.NW, 1, 3500L))
        route(VARROCK_TREES_WEST,  VARROCK_WEST_BANK,
            WalkStep(Dir.SE, 1, 3500L))

        // ── Falador (park is in the centre, banks are west and east) ───────
        route(FALADOR_WEST_BANK,   FALADOR_PARK_TREES,
            WalkStep(Dir.E,  2, 4000L), WalkStep(Dir.NE, 1, 3500L))
        route(FALADOR_PARK_TREES,  FALADOR_WEST_BANK,
            WalkStep(Dir.SW, 1, 3500L), WalkStep(Dir.W,  2, 4000L))

        route(FALADOR_EAST_BANK,   FALADOR_PARK_TREES,
            WalkStep(Dir.W,  2, 4000L), WalkStep(Dir.NW, 1, 3500L))
        route(FALADOR_PARK_TREES,  FALADOR_EAST_BANK,
            WalkStep(Dir.SE, 1, 3500L), WalkStep(Dir.E,  2, 4000L))

        // ── Edgeville ──────────────────────────────────────────────────────
        // Trees are SE of the bank, ~15 tiles
        route(EDGEVILLE_BANK,   EDGEVILLE_TREES,
            WalkStep(Dir.SE, 2, 3500L))
        route(EDGEVILLE_TREES,  EDGEVILLE_BANK,
            WalkStep(Dir.NW, 2, 3500L))

        // ── Barbarian Village → Edgeville bank (long walk N along river) ──
        route(BARBARIAN_VILLAGE_TREES, EDGEVILLE_BANK,
            WalkStep(Dir.N,  3, 4500L), WalkStep(Dir.NE, 1, 4000L))
        route(EDGEVILLE_BANK, BARBARIAN_VILLAGE_TREES,
            WalkStep(Dir.SW, 1, 4000L), WalkStep(Dir.S,  3, 4500L))

        // ── Port Sarim ─────────────────────────────────────────────────────
        // Trees NE of Port Sarim dock; nearest bank is Draynor (~60 tiles NE)
        route(DRAYNOR_BANK,      PORT_SARIM_TREES,
            WalkStep(Dir.S,  2, 4200L), WalkStep(Dir.SW, 2, 4200L))
        route(PORT_SARIM_TREES,  DRAYNOR_BANK,
            WalkStep(Dir.NE, 2, 4200L), WalkStep(Dir.N,  2, 4200L))

        // ── Al-Kharid bank → Lumbridge (convenient shortcut via gate) ─────
        route(AL_KHARID_BANK,    LUMBRIDGE_BANK,
            WalkStep(Dir.S,  2, 4000L), WalkStep(Dir.SW, 1, 3800L))
        route(LUMBRIDGE_BANK,    AL_KHARID_BANK,
            WalkStep(Dir.NE, 1, 3800L), WalkStep(Dir.N,  2, 4000L))
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Walk from one named location to another.
     * Returns true if the route exists and was executed, false otherwise.
     */
    suspend fun walkTo(from: Location, to: Location): Boolean {
        if (from == to) return true
        val steps = routes[from to to]
        if (steps == null) {
            Logger.warn("Walker: no route defined from $from → $to")
            return false
        }
        Logger.ok("Walker: starting route $from → $to (${steps.sumOf { it.repeats }} tap(s))")
        executeRoute(steps)
        return true
    }

    /**
     * Tap the minimap in a direction to move a short distance.
     * stepDelayMs is how long to wait for the character to finish walking per tap.
     */
    suspend fun stepToward(angleDeg: Float, count: Int = 1, stepDelayMs: Long = 4000L) {
        repeat(count) {
            tapMinimap(angleDeg)
            delay(stepDelayMs + Random.nextLong(-300L, 500L))
        }
    }

    /**
     * Walk back toward trees from anywhere using a known compass bearing.
     * Used as a fallback when the full route system is not configured.
     */
    suspend fun returnToTrees(bearing: Float, steps: Int = 1) {
        Logger.ok("Walker: returning to trees — bearing ${bearing}°, $steps step(s)")
        stepToward(bearing, steps, 4500L)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun executeRoute(steps: List<WalkStep>) {
        for (step in steps) {
            repeat(step.repeats) {
                tapMinimap(step.angleDeg)
                delay(step.waitMs + Random.nextLong(-300L, 500L))
            }
        }
    }

    /**
     * Tap the minimap at the given compass angle from its centre.
     * angle 0° = east, 90° = north, -90° = south, 180°/-180° = west
     */
    suspend fun tapMinimap(angleDeg: Float) {
        val w  = dm.widthPixels.toFloat()
        val h  = dm.heightPixels.toFloat()
        val cx = w * mmCxF
        val cy = h * mmCyF
        val r  = w * mmRF

        val rad = Math.toRadians(angleDeg.toDouble())
        val jx  = Random.nextDouble(-3.0, 3.0)
        val jy  = Random.nextDouble(-3.0, 3.0)
        val tx  = (cx + r * cos(rad) + jx).toFloat().coerceIn(0f, w)
        val ty  = (cy - r * sin(rad) + jy).toFloat().coerceIn(0f, h)

        if (tap(tx, ty)) {
            Logger.action("Walker: tap minimap angle=${angleDeg.toInt()}° → (${tx.toInt()}, ${ty.toInt()})")
        }
    }

    private suspend fun tap(x: Float, y: Float): Boolean =
        GestureHelper.tap(service, x, y, Random.nextLong(55L, 110L))

    // Aliases for convenience inside this file
    private val LUMBRIDGE_BANK            = Location.LUMBRIDGE_BANK
    private val LUMBRIDGE_TREES           = Location.LUMBRIDGE_TREES
    private val DRAYNOR_BANK             = Location.DRAYNOR_BANK
    private val DRAYNOR_WILLOWS          = Location.DRAYNOR_WILLOWS
    private val VARROCK_EAST_BANK        = Location.VARROCK_EAST_BANK
    private val VARROCK_WEST_BANK        = Location.VARROCK_WEST_BANK
    private val VARROCK_TREES_EAST       = Location.VARROCK_TREES_EAST
    private val VARROCK_TREES_WEST       = Location.VARROCK_TREES_WEST
    private val FALADOR_WEST_BANK        = Location.FALADOR_WEST_BANK
    private val FALADOR_EAST_BANK        = Location.FALADOR_EAST_BANK
    private val FALADOR_PARK_TREES       = Location.FALADOR_PARK_TREES
    private val EDGEVILLE_BANK           = Location.EDGEVILLE_BANK
    private val EDGEVILLE_TREES          = Location.EDGEVILLE_TREES
    private val BARBARIAN_VILLAGE_TREES  = Location.BARBARIAN_VILLAGE_TREES
    private val PORT_SARIM_TREES         = Location.PORT_SARIM_TREES
    private val AL_KHARID_BANK           = Location.AL_KHARID_BANK
}
