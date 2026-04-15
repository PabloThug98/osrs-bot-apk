package com.osrsbot.autotrainer.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * GestureHelper v2
 *
 * All gestures now travel along Bézier curves with humanised timing:
 *   - tapHuman()   — short random micro-movement before landing, variable press duration
 *   - swipeHuman() — quadratic Bézier arc with randomised control point + duration
 *   - swipePath()  — multi-point swipe (e.g. minimap drag for camera)
 *   - scroll()     — vertical scroll with natural ease
 *
 * tap() remains for backward compatibility (no curve, just a point press).
 */
object GestureHelper {

    // ── Simple point tap (backward-compatible) ───────────────────────────────
    suspend fun tap(
        service: AccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long = 80L,
    ): Boolean {
        val dm = service.resources.displayMetrics
        val sx = x.coerceIn(1f, dm.widthPixels  - 1f)
        val sy = y.coerceIn(1f, dm.heightPixels - 1f)
        val d  = durationMs.coerceIn(45L, 200L)
        val path = Path().apply { moveTo(sx, sy) }
        return dispatch(service, GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, d))
            .build(), d)
    }

    // ── Human-like tap: micro-jitter approach + randomised duration ───────────
    suspend fun tapHuman(
        service: AccessibilityService,
        x: Float,
        y: Float,
        radiusPx: Float = 6f,
    ): Boolean {
        val dm    = service.resources.displayMetrics
        // Land slightly off-centre — humans rarely tap exactly on the centre
        val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
        val dist  = Random.nextFloat() * radiusPx
        val lx = (x + dist * cos(angle)).coerceIn(1f, dm.widthPixels  - 1f)
        val ly = (y + dist * sin(angle)).coerceIn(1f, dm.heightPixels - 1f)
        val duration = gauss(80f, 25f).toLong().coerceIn(55L, 180L)

        // Quadratic approach: start 4-12px away, arc to landing spot
        val startDist  = Random.nextFloat() * 8f + 4f
        val startAngle = angle + Math.PI.toFloat() + gaussFloat(0f, 0.4f)
        val sx = (lx + startDist * cos(startAngle)).coerceIn(1f, dm.widthPixels  - 1f)
        val sy = (ly + startDist * sin(startAngle)).coerceIn(1f, dm.heightPixels - 1f)

        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(lx, ly)
        }
        return dispatch(service, GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build(), duration)
    }

    // ── Humanised swipe along a quadratic Bézier arc ─────────────────────────
    suspend fun swipeHuman(
        service: AccessibilityService,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = 300L,
    ): Boolean {
        val dm       = service.resources.displayMetrics
        // Control point: midpoint + random perpendicular offset
        val midX  = (x1 + x2) / 2f
        val midY  = (y1 + y2) / 2f
        val dx    = x2 - x1; val dy = y2 - y1
        val perpX = -dy; val perpY = dx   // perpendicular
        val len   = Math.sqrt((perpX * perpX + perpY * perpY).toDouble()).toFloat().coerceAtLeast(1f)
        val off   = gauss(0f, 30f)
        val cpx   = (midX + off * perpX / len).coerceIn(0f, dm.widthPixels.toFloat())
        val cpy   = (midY + off * perpY / len).coerceIn(0f, dm.heightPixels.toFloat())

        val dur   = durationMs + gauss(0f, 40f).toLong().coerceIn(-80L, 120L)

        val path = Path().apply {
            moveTo(x1, y1)
            quadTo(cpx, cpy, x2, y2)
        }
        return dispatch(service, GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, dur.coerceIn(80L, 600L)))
            .build(), dur)
    }

    // ── Multi-segment path swipe (used for camera rotation) ──────────────────
    suspend fun swipePath(
        service: AccessibilityService,
        points: List<Pair<Float, Float>>,
        durationMs: Long = 400L,
    ): Boolean {
        if (points.size < 2) return false
        val dm = service.resources.displayMetrics
        val path = Path()
        path.moveTo(
            points[0].first.coerceIn(0f, dm.widthPixels.toFloat()),
            points[0].second.coerceIn(0f, dm.heightPixels.toFloat())
        )
        for (i in 1 until points.size) {
            path.lineTo(
                points[i].first.coerceIn(0f, dm.widthPixels.toFloat()),
                points[i].second.coerceIn(0f, dm.heightPixels.toFloat())
            )
        }
        return dispatch(service, GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(80L, 800L)))
            .build(), durationMs)
    }

    // ── Vertical scroll ───────────────────────────────────────────────────────
    suspend fun scroll(
        service: AccessibilityService,
        x: Float, startY: Float, endY: Float,
        durationMs: Long = 350L,
    ): Boolean = swipeHuman(service, x, startY, x, endY, durationMs)

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun dispatch(
        service: AccessibilityService,
        gesture: GestureDescription,
        durationMs: Long,
    ): Boolean {
        val completed = withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(durationMs + 2_000L) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val accepted = service.dispatchGesture(
                        gesture,
                        object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(g: GestureDescription?) {
                                if (cont.isActive) cont.resume(true)
                            }
                            override fun onCancelled(g: GestureDescription?) {
                                if (cont.isActive) cont.resume(false)
                            }
                        },
                        Handler(Looper.getMainLooper()),
                    )
                    if (!accepted && cont.isActive) cont.resume(false)
                }
            } ?: false
        }
        if (!completed) Logger.warn("Gesture failed/cancelled")
        return completed
    }

    /** Box-Muller Gaussian sample with given mean and stddev. */
    private fun gauss(mean: Float, std: Float): Float {
        val u1 = Random.nextFloat().coerceAtLeast(1e-6f)
        val u2 = Random.nextFloat()
        val z  = Math.sqrt(-2.0 * Math.log(u1.toDouble())).toFloat() *
                 cos((2.0 * Math.PI * u2).toFloat())
        return mean + z * std
    }

    private fun gaussFloat(mean: Float, std: Float): Float = gauss(mean, std)
}
