package com.osrsbot.autotrainer.detector

import android.graphics.Color
import com.osrsbot.autotrainer.capture.ScreenCaptureManager
import com.osrsbot.autotrainer.utils.Logger
import com.osrsbot.autotrainer.utils.ScreenRegions
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * HpMonitor
 *
 * Estimates the player's current HP percentage by reading the HP orb colour.
 *
 * The OSRS HP orb is a circular element in the top-left. Its fill colour cycles:
 *   100%      → bright green   (H:100-140, S:0.6-1.0, V:0.5-1.0)
 *    50-99%   → yellow-green   (H:70-100,  S:0.5-0.9, V:0.5-1.0)
 *    25-49%   → orange         (H:20-50,   S:0.7-1.0, V:0.6-1.0)
 *     1-24%   → red            (H:0-15,    S:0.7-1.0, V:0.5-1.0)
 *
 * We sample pixels in a grid over the orb area and compute the dominant colour.
 * Returns a Float in 0.0..1.0 representing estimated HP%.
 *
 * Note: This is an approximation. Use it to trigger eat actions, not for precision.
 */
class HpMonitor(private val capture: ScreenCaptureManager) {

    companion object {
        private const val CACHE_TTL_MS = 1_000L
        private const val SAMPLE_STEP  = 3
    }

    private var cachedHpPercent: Float = 1.0f
    private var cacheTime: Long = 0L

    /**
     * Returns estimated HP as a fraction (0.0 = dead, 1.0 = full health).
     * Cached for CACHE_TTL_MS.
     */
    fun getHpFraction(forceRefresh: Boolean = false): Float {
        val now = System.currentTimeMillis()
        if (!forceRefresh && (now - cacheTime) < CACHE_TTL_MS) return cachedHpPercent

        val bmp = capture.latestBitmap ?: return cachedHpPercent
        val rect = ScreenRegions.hpOrbRect()
        val cx   = rect.exactCenterX().toInt()
        val cy   = rect.exactCenterY().toInt()
        val r    = (rect.width() / 2).coerceAtLeast(1)

        var green  = 0; var yellow = 0; var orange = 0; var red = 0; var total = 0
        val hsv = FloatArray(3)

        for (y in (cy - r)..(cy + r) step SAMPLE_STEP) {
            for (x in (cx - r)..(cx + r) step SAMPLE_STEP) {
                // Only sample inside the circle
                val dist = sqrt(((x - cx).toFloat().pow(2) + (y - cy).toFloat().pow(2)))
                if (dist > r) continue
                val px = x.coerceIn(0, bmp.width - 1)
                val py = y.coerceIn(0, bmp.height - 1)
                Color.colorToHSV(bmp.getPixel(px, py), hsv)
                val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
                if (s < 0.3f || v < 0.25f) continue  // grey/black — skip
                total++
                when {
                    h in 95f..145f  && s > 0.55f && v > 0.45f -> green++
                    h in 60f..95f   && s > 0.45f && v > 0.45f -> yellow++
                    h in 18f..60f   && s > 0.60f && v > 0.50f -> orange++
                    (h <= 18f || h >= 345f) && s > 0.60f && v > 0.45f -> red++
                }
            }
        }

        val hp = when {
            total == 0 -> cachedHpPercent  // no readable pixels — keep last
            green  > total * 0.35f -> 1.00f
            yellow > total * 0.35f -> 0.65f
            orange > total * 0.35f -> 0.35f
            red    > total * 0.20f -> 0.15f
            else -> cachedHpPercent
        }

        Logger.info("HpMonitor: ~${(hp * 100).toInt()}% HP (g=$green y=$yellow o=$orange r=$red t=$total)")
        cachedHpPercent = hp
        cacheTime = System.currentTimeMillis()
        return hp
    }

    /** Returns true if HP is at or below the given fraction (e.g. 0.50 = 50%). */
    fun isBelowThreshold(fraction: Float): Boolean = getHpFraction() <= fraction

    fun invalidateCache() { cacheTime = 0L }
}
