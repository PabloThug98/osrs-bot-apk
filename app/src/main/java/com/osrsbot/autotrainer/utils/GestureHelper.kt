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

object GestureHelper {
    suspend fun tap(
        service: AccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long = 80L,
    ): Boolean {
        val dm = service.resources.displayMetrics
        val safeX = x.coerceIn(1f, dm.widthPixels.toFloat() - 1f)
        val safeY = y.coerceIn(1f, dm.heightPixels.toFloat() - 1f)
        val safeDuration = durationMs.coerceIn(45L, 180L)
        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val completed = withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(safeDuration + 1_500L) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val accepted = service.dispatchGesture(
                        gesture,
                        object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(false)
                            }
                        },
                        Handler(Looper.getMainLooper()),
                    )

                    if (!accepted && cont.isActive) {
                        cont.resume(false)
                    }
                }
            } ?: false
        }

        if (!completed) {
            Logger.warn("Gesture failed/cancelled at (${safeX.toInt()}, ${safeY.toInt()})")
        }
        return completed
    }
}