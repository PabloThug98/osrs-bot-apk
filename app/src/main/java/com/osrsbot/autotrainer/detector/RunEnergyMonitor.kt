package com.osrsbot.autotrainer.detector

import android.graphics.Color
import com.osrsbot.autotrainer.capture.ScreenCaptureManager
import com.osrsbot.autotrainer.utils.GestureHelper
import android.accessibilityservice.AccessibilityService
import com.osrsbot.autotrainer.utils.Logger
import com.osrsbot.autotrainer.utils.ScreenRegions
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * RunEnergyMonitor
 *
 * Reads the run orb pixel colour to determine if run energy is available,
 * and automatically re-enables run when energy recovers.
 *
 * Run orb HSV signatures:
 *   Run ON  (has energy): amber/yellow  H:35-60, S:0.55-1.0, V:0.50-1.0
 *   Run OFF (toggled off): pale/blue-grey, lower saturation
 *   Run depleted (0 energy): dark grey, near-black
 *
 * Usage:
 *   Call checkAndEnableRun() every N ticks — it re-taps the orb if run is off.
 */
class RunEnergyMonitor(private val capture: ScreenCaptureManager) {

    companion object {
        private const val CACHE_TTL_MS = 2_000L
        private const val SAMPLE_STEP  = 2
    }

    enum class RunState { RUNNING, WALKING, DEPLETED, UNKNOWN }

    private var cachedState: RunState = RunState.UNKNOWN
    private var cacheTime: Long = 0L

    fun getRunState(forceRefresh: Boolean = false): RunState {
        val now = System.currentTimeMillis()
        if (!forceRefresh && (now - cacheTime) < CACHE_TTL_MS) return cachedState

        val bmp  = capture.latestBitmap ?: return RunState.UNKNOWN
        val rect = ScreenRegions.hpOrbRect().let {
            val dm = ScreenRegions.getDisplayMetrics()
            // Run orb is below HP orb — shift Y down by ~2.5 orb diameters
            val orbH = it.height()
            android.graphics.Rect(
                (ScreenRegions.RUN_ORB_CX_F * dm.widthPixels - orbH / 2).toInt(),
                (ScreenRegions.RUN_ORB_CY_F * dm.heightPixels - orbH / 2).toInt(),
                (ScreenRegions.RUN_ORB_CX_F * dm.widthPixels + orbH / 2).toInt(),
                (ScreenRegions.RUN_ORB_CY_F * dm.heightPixels + orbH / 2).toInt(),
            )
        }
        val cx = rect.exactCenterX().toInt(); val cy = rect.exactCenterY().toInt()
        val r  = (rect.width() / 2).coerceAtLeast(1)
        val hsv = FloatArray(3)

        var amber = 0; var grey = 0; var dark = 0; var total = 0

        for (y in (cy - r)..(cy + r) step SAMPLE_STEP) {
            for (x in (cx - r)..(cx + r) step SAMPLE_STEP) {
                val dist = sqrt((x - cx).toFloat().pow(2) + (y - cy).toFloat().pow(2))
                if (dist > r) continue
                Color.colorToHSV(bmp.getPixel(x.coerceIn(0, bmp.width-1), y.coerceIn(0, bmp.height-1)), hsv)
                total++
                when {
                    hsv[0] in 32f..65f  && hsv[1] > 0.50f && hsv[2] > 0.45f -> amber++
                    hsv[1] < 0.25f      && hsv[2] in 0.25f..0.70f            -> grey++
                    hsv[2] < 0.15f                                            -> dark++
                }
            }
        }

        val state = when {
            total == 0                         -> RunState.UNKNOWN
            dark  > total * 0.50f              -> RunState.DEPLETED
            grey  > total * 0.40f              -> RunState.WALKING
            amber > total * 0.25f              -> RunState.RUNNING
            else                               -> RunState.UNKNOWN
        }

        Logger.info("RunEnergy: $state (amber=$amber grey=$grey dark=$dark total=$total)")
        cachedState = state
        cacheTime = System.currentTimeMillis()
        return state
    }

    /** Returns true if the player is currently running. */
    fun isRunning(): Boolean = getRunState() == RunState.RUNNING

    /** Returns true if run energy is depleted (no amber pixels). */
    fun isDepleted(): Boolean = getRunState() == RunState.DEPLETED

    /**
     * If run is off or walking, tap the run orb to enable it.
     * Skips if already running or depleted.
     */
    suspend fun checkAndEnableRun(service: AccessibilityService) {
        val state = getRunState(forceRefresh = true)
        if (state == RunState.RUNNING || state == RunState.DEPLETED) return
        val dm = ScreenRegions.getDisplayMetrics()
        val x  = ScreenRegions.RUN_ORB_CX_F * dm.widthPixels
        val y  = ScreenRegions.RUN_ORB_CY_F * dm.heightPixels
        Logger.info("RunEnergy: enabling run (state was $state)")
        GestureHelper.tapHuman(service, x, y)
        invalidateCache()
    }

    fun invalidateCache() { cacheTime = 0L }
}
