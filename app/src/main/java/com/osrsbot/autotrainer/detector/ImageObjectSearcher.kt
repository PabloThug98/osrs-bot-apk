package com.osrsbot.autotrainer.detector

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import com.osrsbot.autotrainer.utils.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.abs

data class ImageTarget(
    val label: String,
    val x: Float,
    val y: Float,
    val bounds: Rect,
    val confidence: Float,
)

class ImageObjectSearcher(private val service: AccessibilityService) {

    private val dm get() = service.resources.displayMetrics

    suspend fun findTree(): ImageTarget? {
        val bitmap = captureBitmap() ?: return null
        return bitmap.useForSearch {
            findGreenCluster(bitmap, "Tree")
        }
    }

    suspend fun findNpc(): ImageTarget? {
        val bitmap = captureBitmap() ?: return null
        return bitmap.useForSearch {
            findMovingObjectCluster(bitmap, "NPC")
        }
    }

    suspend fun findBank(): ImageTarget? {
        val bitmap = captureBitmap() ?: return null
        return bitmap.useForSearch {
            findBankLikeCluster(bitmap)
        }
    }

    private suspend fun captureBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Logger.warn("Image search requires Android 11+ screenshot support; falling back to accessibility detection.")
            return null
        }

        return suspendCancellableCoroutine { cont ->
            val executor = Executor { runnable -> runnable.run() }
            service.takeScreenshot(
                0,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val hardware = screenshot.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
                        val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        hardware.close()
                        if (cont.isActive) cont.resume(copy)
                    }

                    override fun onFailure(errorCode: Int) {
                        Logger.warn("Image search screenshot failed: $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }
    }

    private fun findGreenCluster(bitmap: Bitmap, label: String): ImageTarget? {
        val top = (bitmap.height * 0.18f).toInt()
        val bottom = (bitmap.height * 0.78f).toInt()
        val left = (bitmap.width * 0.04f).toInt()
        val right = (bitmap.width * 0.96f).toInt()
        val cell = 36
        var best: ScoredCell? = null

        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val scored = scoreCell(bitmap, x, y, cell) { r, g, b ->
                    g > 74 && g > r + 16 && g > b + 12 && r in 25..150
                }
                if (scored.count > 24) {
                    val centerPenalty = distancePenalty(scored.cx, scored.cy, bitmap.width, bitmap.height)
                    val score = scored.count * 1.25f + scored.contrast * 0.08f - centerPenalty
                    if (best == null || score > best!!.score) best = scored.copy(score = score)
                }
                x += cell
            }
            y += cell
        }

        val winner = best ?: return null
        val confidence = (winner.count / 260f).coerceIn(0.45f, 0.96f)
        val targetY = (winner.cy + 18f).coerceAtMost(bitmap.height * 0.80f)
        Logger.info("Image search tree: x=${winner.cx.toInt()} y=${targetY.toInt()} conf=${"%.2f".format(confidence)}")
        return ImageTarget(
            label,
            winner.cx,
            targetY,
            Rect(winner.x, winner.y, winner.x + cell, winner.y + cell),
            confidence,
        )
    }

    private fun findMovingObjectCluster(bitmap: Bitmap, label: String): ImageTarget? {
        val top = (bitmap.height * 0.20f).toInt()
        val bottom = (bitmap.height * 0.76f).toInt()
        val left = (bitmap.width * 0.08f).toInt()
        val right = (bitmap.width * 0.92f).toInt()
        val cell = 34
        var best: ScoredCell? = null

        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val scored = scoreCell(bitmap, x, y, cell) { r, g, b ->
                    val max = maxOf(r, g, b)
                    val min = minOf(r, g, b)
                    val saturated = max - min > 34
                    val notGrass = !(g > r + 18 && g > b + 14)
                    val notUiWhite = !(r > 210 && g > 210 && b > 210)
                    saturated && notGrass && notUiWhite && max > 70
                }
                if (scored.count > 18) {
                    val centerPenalty = distancePenalty(scored.cx, scored.cy, bitmap.width, bitmap.height)
                    val score = scored.count + scored.contrast * 0.12f - centerPenalty
                    if (best == null || score > best!!.score) best = scored.copy(score = score)
                }
                x += cell
            }
            y += cell
        }

        val winner = best ?: return null
        val confidence = (winner.count / 210f).coerceIn(0.40f, 0.90f)
        Logger.info("Image search NPC: x=${winner.cx.toInt()} y=${winner.cy.toInt()} conf=${"%.2f".format(confidence)}")
        return ImageTarget(
            label,
            winner.cx,
            winner.cy,
            Rect(winner.x, winner.y, winner.x + cell, winner.y + cell),
            confidence,
        )
    }

    private fun findBankLikeCluster(bitmap: Bitmap): ImageTarget? {
        val top = (bitmap.height * 0.18f).toInt()
        val bottom = (bitmap.height * 0.72f).toInt()
        val left = (bitmap.width * 0.05f).toInt()
        val right = (bitmap.width * 0.95f).toInt()
        val cell = 42
        var best: ScoredCell? = null

        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val scored = scoreCell(bitmap, x, y, cell) { r, g, b ->
                    val grayStone = abs(r - g) < 18 && abs(g - b) < 18 && r in 78..185
                    val warmWood = r in 90..185 && g in 55..135 && b in 30..105 && r > b + 22
                    val bankYellow = r > 145 && g > 115 && b < 95
                    grayStone || warmWood || bankYellow
                }
                if (scored.count > 28) {
                    val score = scored.count + scored.contrast * 0.06f - distancePenalty(scored.cx, scored.cy, bitmap.width, bitmap.height)
                    if (best == null || score > best!!.score) best = scored.copy(score = score)
                }
                x += cell
            }
            y += cell
        }

        val winner = best ?: return null
        val confidence = (winner.count / 300f).coerceIn(0.38f, 0.82f)
        Logger.info("Image search bank: x=${winner.cx.toInt()} y=${winner.cy.toInt()} conf=${"%.2f".format(confidence)}")
        return ImageTarget(
            "Bank",
            winner.cx,
            winner.cy,
            Rect(winner.x, winner.y, winner.x + cell, winner.y + cell),
            confidence,
        )
    }

    private fun scoreCell(
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        size: Int,
        predicate: (r: Int, g: Int, b: Int) -> Boolean,
    ): ScoredCell {
        var count = 0
        var totalContrast = 0
        val endX = minOf(startX + size, bitmap.width)
        val endY = minOf(startY + size, bitmap.height)
        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                if (predicate(r, g, b)) {
                    count++
                    totalContrast += maxOf(r, g, b) - minOf(r, g, b)
                }
                x += 3
            }
            y += 3
        }
        return ScoredCell(
            startX,
            startY,
            (startX + endX) / 2f,
            (startY + endY) / 2f,
            count,
            if (count == 0) 0f else totalContrast.toFloat() / count,
            0f,
        )
    }

    private fun distancePenalty(x: Float, y: Float, width: Int, height: Int): Float {
        val cx = width / 2f
        val cy = height / 2f
        val dx = (x - cx) / width
        val dy = (y - cy) / height
        return (dx * dx + dy * dy) * 120f
    }

    private inline fun <T> Bitmap.useForSearch(block: () -> T): T {
        try {
            return block()
        } finally {
            recycle()
        }
    }

    private data class ScoredCell(
        val x: Int,
        val y: Int,
        val cx: Float,
        val cy: Float,
        val count: Int,
        val contrast: Float,
        val score: Float,
    )
}