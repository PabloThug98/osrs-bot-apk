package com.osrsbot.autotrainer.detector

import android.graphics.Color
import android.accessibilityservice.AccessibilityService
import com.osrsbot.autotrainer.capture.ScreenCaptureManager
import com.osrsbot.autotrainer.utils.GestureHelper
import com.osrsbot.autotrainer.utils.Logger
import com.osrsbot.autotrainer.utils.ScreenRegions
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * PrayerMonitor — reads prayer orb pixel colour to estimate remaining prayer points.
 *
 * HSV signatures:
 *   ACTIVE  — teal/cyan   H:165-205, S:0.45-1.0, V:0.45-1.0
 *   LOW     — pale/grey   H:any,     S:0.00-0.30, V:0.25-0.72
 *   DEPLETED— very dark   H:any,     S:any,       V:<0.15
 */
class PrayerMonitor(private val capture: ScreenCaptureManager) {

    enum class PrayerState { ACTIVE, LOW, DEPLETED, UNKNOWN }

    companion object {
        private const val CACHE_TTL_MS = 2_000L
        private const val SAMPLE_STEP  = 2
    }

    private var cached: PrayerState = PrayerState.UNKNOWN
    private var cacheTime: Long = 0L

    fun getPrayerState(forceRefresh: Boolean = false): PrayerState {
        val now = System.currentTimeMillis()
        if (!forceRefresh && (now - cacheTime) < CACHE_TTL_MS) return cached

        val bmp = capture.latestBitmap ?: return PrayerState.UNKNOWN
        val dm  = ScreenRegions.getDisplayMetrics()
        val cx  = (ScreenRegions.PRAYER_ORB_CX_F * dm.widthPixels).toInt()
        val cy  = (ScreenRegions.PRAYER_ORB_CY_F * dm.heightPixels).toInt()
        val r   = (ScreenRegions.ORB_R_F * dm.widthPixels).toInt().coerceAtLeast(1)
        val hsv = FloatArray(3)

        var teal = 0; var pale = 0; var dark = 0; var total = 0

        for (y in (cy - r)..(cy + r) step SAMPLE_STEP) {
            for (x in (cx - r)..(cx + r) step SAMPLE_STEP) {
                if (sqrt((x-cx).toFloat().pow(2)+(y-cy).toFloat().pow(2)) > r) continue
                Color.colorToHSV(bmp.getPixel(x.coerceIn(0,bmp.width-1), y.coerceIn(0,bmp.height-1)), hsv)
                total++
                when {
                    hsv[0] in 165f..205f && hsv[1] > 0.42f && hsv[2] > 0.42f -> teal++
                    hsv[1] < 0.30f       && hsv[2] in 0.22f..0.75f            -> pale++
                    hsv[2] < 0.15f                                             -> dark++
                }
            }
        }

        val state = when {
            total == 0                        -> PrayerState.UNKNOWN
            dark  > total * 0.55f             -> PrayerState.DEPLETED
            pale  > total * 0.45f             -> PrayerState.LOW
            teal  > total * 0.18f             -> PrayerState.ACTIVE
            else                              -> PrayerState.UNKNOWN
        }

        Logger.info("PrayerMonitor: $state (teal=$teal pale=$pale dark=$dark / $total)")
        cached = state; cacheTime = System.currentTimeMillis()
        return state
    }

    fun isActive():   Boolean = getPrayerState() == PrayerState.ACTIVE
    fun isLow():      Boolean = getPrayerState() == PrayerState.LOW
    fun isDepleted(): Boolean = getPrayerState() == PrayerState.DEPLETED

    suspend fun togglePrayer(service: AccessibilityService) {
        val dm = ScreenRegions.getDisplayMetrics()
        GestureHelper.tapHuman(service, ScreenRegions.PRAYER_ORB_CX_F * dm.widthPixels, ScreenRegions.PRAYER_ORB_CY_F * dm.heightPixels)
        Logger.info("PrayerMonitor: toggled"); invalidateCache()
    }

    fun invalidateCache() { cacheTime = 0L }
}
