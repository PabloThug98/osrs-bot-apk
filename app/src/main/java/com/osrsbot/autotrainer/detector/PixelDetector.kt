package com.osrsbot.autotrainer.detector

import android.graphics.Color
import android.graphics.Rect
import com.osrsbot.autotrainer.capture.ScreenCaptureManager
import com.osrsbot.autotrainer.utils.Logger

class PixelDetector(val capture: ScreenCaptureManager) {

    data class ColorRange(
        val hMin: Float, val hMax: Float,
        val sMin: Float, val sMax: Float,
        val vMin: Float, val vMax: Float,
        val hWraps: Boolean = false,
    )

    data class ObjectSignature(
        val name: String,
        val primary: ColorRange,
        val minPixels: Int = 40,
        val maxPixels: Int = 80_000,
    )

    companion object {
        val TREE         = ObjectSignature("Tree",         ColorRange(18f,40f,  0.30f,0.85f, 0.12f,0.52f), 60)
        val OAK_TREE     = ObjectSignature("Oak Tree",     ColorRange(20f,38f,  0.38f,0.82f, 0.14f,0.48f), 80)
        val WILLOW_TREE  = ObjectSignature("Willow Tree",  ColorRange(72f,108f, 0.18f,0.68f, 0.22f,0.68f), 100)
        val YEW_TREE     = ObjectSignature("Yew Tree",     ColorRange(25f,45f,  0.28f,0.78f, 0.10f,0.45f), 120)
        val HP_RED       = ObjectSignature("Monster",      ColorRange(355f,10f, 0.75f,1.0f,  0.55f,1.0f,  hWraps=true),  12, 1_200)
        val HP_GREEN     = ObjectSignature("Monster",      ColorRange(100f,148f,0.62f,1.0f,  0.42f,0.96f),12, 1_200)
        val FISHING_SPOT = ObjectSignature("Fishing Spot", ColorRange(183f,228f,0.32f,0.88f, 0.32f,0.88f), 55)
        val BANK         = ObjectSignature("Bank",         ColorRange(30f,54f,  0.42f,0.92f, 0.42f,0.92f), 160)
        val ORE_ROCK     = ObjectSignature("Rocks",        ColorRange(198f,245f,0.08f,0.48f, 0.22f,0.62f), 70)
        val ITEM_TEXT    = ObjectSignature("Item",         ColorRange(52f,68f,  0.82f,1.0f,  0.82f,1.0f),  8, 600)
        private const val SCAN_STEP = 3
    }

    fun detect(scriptId: String): List<DetectedObject> {
        val bmp = capture.latestBitmap ?: return emptyList()
        val scale = SCAN_STEP
        val sw = bmp.width / scale
        val sh = bmp.height / scale
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val results = mutableListOf<DetectedObject>()
        val hsv = FloatArray(3)
        for (sig in signaturesFor(scriptId)) {
            val hitMap = Array(sh) { BooleanArray(sw) }
            for (sy in 0 until sh) {
                for (sx in 0 until sw) {
                    Color.colorToHSV(pixels[(sy * scale) * bmp.width + (sx * scale)], hsv)
                    if (matchesColor(hsv, sig.primary)) hitMap[sy][sx] = true
                }
            }
            extractClusters(hitMap, sw, sh, scale, sig, results)
        }
        return results.sortedByDescending { it.confidence }.also {
            if (it.isNotEmpty()) Logger.info("PixelDetector [$scriptId]: ${it.size} object(s)")
        }
    }

    private fun matchesColor(hsv: FloatArray, r: ColorRange): Boolean {
        val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
        if (s !in r.sMin..r.sMax || v !in r.vMin..r.vMax) return false
        return if (r.hWraps) h >= r.hMin || h <= r.hMax else h in r.hMin..r.hMax
    }

    private fun extractClusters(hitMap: Array<BooleanArray>, w: Int, h: Int, scale: Int, sig: ObjectSignature, out: MutableList<DetectedObject>) {
        val visited = Array(h) { BooleanArray(w) }
        val queue = ArrayDeque<Int>()
        for (sy in 0 until h) {
            for (sx in 0 until w) {
                if (!hitMap[sy][sx] || visited[sy][sx]) continue
                var minX = sx; var maxX = sx; var minY = sy; var maxY = sy; var count = 0
                queue.clear(); queue.add(sx + sy * w); visited[sy][sx] = true
                while (queue.isNotEmpty()) {
                    val idx = queue.removeFirst(); val cx = idx % w; val cy = idx / w
                    count++
                    if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
                    if (cx > 0   && !visited[cy][cx-1] && hitMap[cy][cx-1]) { visited[cy][cx-1]=true; queue.add(cx-1+cy*w) }
                    if (cx < w-1 && !visited[cy][cx+1] && hitMap[cy][cx+1]) { visited[cy][cx+1]=true; queue.add(cx+1+cy*w) }
                    if (cy > 0   && !visited[cy-1][cx] && hitMap[cy-1][cx]) { visited[cy-1][cx]=true; queue.add(cx+(cy-1)*w) }
                    if (cy < h-1 && !visited[cy+1][cx] && hitMap[cy+1][cx]) { visited[cy+1][cx]=true; queue.add(cx+(cy+1)*w) }
                }
                if (count in sig.minPixels..sig.maxPixels) {
                    out.add(DetectedObject(sig.name, Rect(minX*scale, minY*scale, maxX*scale, maxY*scale),
                        (count.toFloat()/sig.maxPixels).coerceIn(0.05f,1.0f), isClickable=true))
                }
            }
        }
    }

    private fun signaturesFor(scriptId: String): List<ObjectSignature> = when (scriptId) {
        "woodcutting" -> listOf(OAK_TREE, WILLOW_TREE, YEW_TREE, TREE, BANK)
        "fishing"     -> listOf(FISHING_SPOT, BANK)
        "combat"      -> listOf(HP_RED, HP_GREEN)
        "mining"      -> listOf(ORE_ROCK, BANK)
        "chocolate"   -> listOf(ITEM_TEXT, BANK)
        else          -> emptyList()
    }
}
