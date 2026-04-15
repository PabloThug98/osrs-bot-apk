package com.osrsbot.autotrainer.detector

import android.graphics.Color
import android.graphics.Rect
import com.osrsbot.autotrainer.capture.ScreenCaptureManager
import com.osrsbot.autotrainer.utils.Logger

/**
 * PixelDetector v2
 *
 * New:
 *  - minAspectRatio / maxAspectRatio — HP bars must be ≥ 3:1 wide:tall.
 *  - minDensity — matched-pixel / bounding-box area guard (drops noise lines).
 *  - secondary ColorRange — verified near the cluster (fishing splash, HP bg).
 *  - New signatures: MAGIC_TREE, ORE_ROCK_IRON, ITEM_SELECTED, MAKE_ALL_BTN.
 */
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
        val secondary: ColorRange? = null,
        val minPixels: Int    = 40,
        val maxPixels: Int    = 80_000,
        val minAspectRatio: Float = 0f,
        val maxAspectRatio: Float = 0f,
        val minDensity: Float = 0f,
    )

    companion object {
        val TREE         = ObjectSignature("Tree",         ColorRange(18f,40f,  0.30f,0.85f, 0.12f,0.52f), minPixels=60)
        val OAK_TREE     = ObjectSignature("Oak Tree",     ColorRange(20f,38f,  0.38f,0.82f, 0.14f,0.48f), minPixels=80)
        val WILLOW_TREE  = ObjectSignature("Willow Tree",  ColorRange(72f,108f, 0.18f,0.68f, 0.22f,0.68f), minPixels=100)
        val YEW_TREE     = ObjectSignature("Yew Tree",     ColorRange(25f,45f,  0.28f,0.78f, 0.10f,0.45f), minPixels=120)
        val MAGIC_TREE   = ObjectSignature("Magic Tree",   ColorRange(245f,270f,0.25f,0.70f, 0.18f,0.55f), minPixels=100)

        val HP_RED  = ObjectSignature("Monster",
            primary   = ColorRange(350f,12f, 0.70f,1.0f, 0.50f,1.0f, hWraps=true),
            secondary = ColorRange(0f,360f,  0.00f,0.2f, 0.00f,0.25f),
            minPixels=10, maxPixels=1_500, minAspectRatio=3.0f, maxAspectRatio=40f, minDensity=0.38f)
        val HP_GREEN = ObjectSignature("Monster",
            ColorRange(100f,148f,0.62f,1.0f, 0.42f,0.96f),
            minPixels=10, maxPixels=1_500, minAspectRatio=3.0f, maxAspectRatio=40f, minDensity=0.38f)

        val FISHING_SPOT = ObjectSignature("Fishing Spot",
            primary   = ColorRange(183f,228f, 0.32f,0.88f, 0.32f,0.88f),
            secondary = ColorRange(0f,360f,   0.00f,0.15f, 0.90f,1.00f),
            minPixels=45)

        val BANK          = ObjectSignature("Bank",      ColorRange(30f,54f,  0.42f,0.92f, 0.42f,0.92f), minPixels=160)
        val ORE_ROCK      = ObjectSignature("Rocks",     ColorRange(198f,245f,0.08f,0.48f, 0.22f,0.62f), minPixels=70)
        val ORE_ROCK_IRON = ObjectSignature("Iron Rocks",ColorRange(0f,20f,  0.30f,0.75f, 0.18f,0.55f), minPixels=60)
        val ITEM_TEXT     = ObjectSignature("Item",      ColorRange(52f,68f,  0.82f,1.0f,  0.82f,1.0f),  minPixels=8,  maxPixels=600)
        val ITEM_SELECTED = ObjectSignature("Selected",  ColorRange(48f,68f,  0.65f,1.0f,  0.65f,1.0f),  minPixels=15, maxPixels=2_000)
        val MAKE_ALL_BTN  = ObjectSignature("Make All",  ColorRange(20f,45f,  0.35f,0.80f, 0.18f,0.50f), minPixels=80, maxPixels=5_000)

        private const val SCAN_STEP = 3
    }

    fun detect(scriptId: String): List<DetectedObject> {
        val bmp = capture.latestBitmap ?: return emptyList()
        val sc = SCAN_STEP; val sw = bmp.width/sc; val sh = bmp.height/sc
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val results = mutableListOf<DetectedObject>()
        val hsv = FloatArray(3)
        for (sig in sigsFor(scriptId)) {
            val hit = Array(sh) { BooleanArray(sw) }
            for (sy in 0 until sh) for (sx in 0 until sw) {
                Color.colorToHSV(pixels[(sy*sc)*bmp.width+(sx*sc)], hsv)
                if (matches(hsv, sig.primary)) hit[sy][sx] = true
            }
            cluster(hit, sw, sh, sc, sig, bmp, results)
        }
        return results.sortedByDescending { it.confidence }.also {
            if (it.isNotEmpty()) Logger.info("PixelDetector [$scriptId]: ${it.size}")
        }
    }

    private fun matches(hsv: FloatArray, r: ColorRange): Boolean {
        val h=hsv[0]; val s=hsv[1]; val v=hsv[2]
        if (s !in r.sMin..r.sMax || v !in r.vMin..r.vMax) return false
        return if (r.hWraps) h>=r.hMin||h<=r.hMax else h in r.hMin..r.hMax
    }

    private fun cluster(hit: Array<BooleanArray>, w:Int, h:Int, sc:Int, sig:ObjectSignature, bmp:android.graphics.Bitmap, out:MutableList<DetectedObject>) {
        val vis = Array(h){BooleanArray(w)}; val q = ArrayDeque<Int>(); val hsv = FloatArray(3)
        for (sy in 0 until h) for (sx in 0 until w) {
            if (!hit[sy][sx]||vis[sy][sx]) continue
            var mnX=sx;var mxX=sx;var mnY=sy;var mxY=sy;var cnt=0
            q.clear(); q.add(sx+sy*w); vis[sy][sx]=true
            while (q.isNotEmpty()) {
                val i=q.removeFirst(); val cx=i%w; val cy=i/w; cnt++
                if(cx<mnX)mnX=cx; if(cx>mxX)mxX=cx; if(cy<mnY)mnY=cy; if(cy>mxY)mxY=cy
                if(cx>0   &&!vis[cy][cx-1]&&hit[cy][cx-1]){vis[cy][cx-1]=true;q.add(cx-1+cy*w)}
                if(cx<w-1 &&!vis[cy][cx+1]&&hit[cy][cx+1]){vis[cy][cx+1]=true;q.add(cx+1+cy*w)}
                if(cy>0   &&!vis[cy-1][cx]&&hit[cy-1][cx]){vis[cy-1][cx]=true;q.add(cx+(cy-1)*w)}
                if(cy<h-1 &&!vis[cy+1][cx]&&hit[cy+1][cx]){vis[cy+1][cx]=true;q.add(cx+(cy+1)*w)}
            }
            if (cnt !in sig.minPixels..sig.maxPixels) continue
            val bw=(mxX-mnX+1).coerceAtLeast(1); val bh=(mxY-mnY+1).coerceAtLeast(1)
            val asp=bw.toFloat()/bh
            if (sig.minAspectRatio>0f&&asp<sig.minAspectRatio) continue
            if (sig.maxAspectRatio>0f&&asp>sig.maxAspectRatio) continue
            if (sig.minDensity>0f&&cnt.toFloat()/(bw*bh)<sig.minDensity) continue
            if (sig.secondary!=null) {
                val sl=(mnX-4)*sc; val st=(mnY-4)*sc; val sr=(mxX+4)*sc; val sb=(mxY+4)*sc
                var sh2=0;var st2=0; var y2=st.coerceIn(0,bmp.height-1); while(y2<=sb.coerceIn(0,bmp.height-1)){var x2=sl.coerceIn(0,bmp.width-1); while(x2<=sr.coerceIn(0,bmp.width-1)){Color.colorToHSV(bmp.getPixel(x2,y2),hsv);if(matches(hsv,sig.secondary))sh2++;st2++;x2+=SCAN_STEP};y2+=SCAN_STEP}
                if(st2>0&&sh2.toFloat()/st2<0.08f) continue
            }
            out.add(DetectedObject(sig.name, Rect(mnX*sc,mnY*sc,mxX*sc,mxY*sc), (cnt.toFloat()/sig.maxPixels).coerceIn(0.05f,1.0f), true))
        }
    }

    private fun sigsFor(id: String): List<ObjectSignature> = when(id) {
        "woodcutting" -> listOf(OAK_TREE,WILLOW_TREE,YEW_TREE,MAGIC_TREE,TREE,BANK)
        "fishing"     -> listOf(FISHING_SPOT,BANK)
        "combat"      -> listOf(HP_RED,HP_GREEN,ITEM_TEXT)
        "mining"      -> listOf(ORE_ROCK,ORE_ROCK_IRON,BANK)
        "chocolate"   -> listOf(ITEM_TEXT,ITEM_SELECTED,MAKE_ALL_BTN,BANK)
        else          -> emptyList()
    }
}
