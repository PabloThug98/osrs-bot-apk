package com.osrsbot.autotrainer.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.WindowManager
import com.osrsbot.autotrainer.detector.DetectedObject
import com.osrsbot.autotrainer.utils.Logger

/**
 * DebugOverlay — full-screen transparent passthrough overlay.
 *
 * Draws on top of the game WITHOUT intercepting touches (FLAG_NOT_TOUCHABLE).
 *
 *  Top HUD bar:
 *    [PLAYING] HP:87%  RUN:✓  Inv:14/28  XP/hr:28400  GP/hr:52000  00:12:34
 *
 *  Per-object bounding boxes:
 *    Coloured rectangle + label ("Oak Tree 83%") drawn over every detected object.
 *    Green ≥90%  |  Yellow-green ≥70%  |  Amber ≥50%  |  Red <50%
 *
 * Usage:
 *   val dbg = DebugOverlay(context)
 *   dbg.show()
 *   dbg.update(objects, stats)   // every tick from BotService
 *   dbg.hide()
 */
class DebugOverlay(private val context: Context) {

    data class DebugStats(
        val gameState: String     = "UNKNOWN",
        val hpPercent: Int        = 100,
        val isRunning: Boolean    = true,
        val invOccupied: Int      = 0,
        val xpPerHour: Int        = 0,
        val gpPerHour: Int        = 0,
        val currentAction: String = "",
        val runtime: String       = "00:00:00",
        val actions: Int          = 0,
    )

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: DebugOverlayView? = null

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    val isShowing: Boolean get() = view != null

    fun show() {
        if (view != null) return
        try {
            val v = DebugOverlayView(context); view = v
            windowManager.addView(v, params)
            Logger.ok("DebugOverlay shown")
        } catch (e: Exception) { Logger.error("DebugOverlay.show: ${e.message}") }
    }

    fun hide() {
        view?.let { try { windowManager.removeView(it) } catch (_: Exception) {}; view = null }
        Logger.warn("DebugOverlay hidden")
    }

    fun update(objects: List<DetectedObject>, stats: DebugStats) {
        view?.apply { detectedObjects = objects; debugStats = stats; postInvalidate() }
    }

    fun updateObjects(objects: List<DetectedObject>) { view?.apply { detectedObjects = objects; postInvalidate() } }
    fun updateStats(stats: DebugStats) { view?.apply { debugStats = stats; postInvalidate() } }
}

// ─── DebugOverlayView ────────────────────────────────────────────────────────

private class DebugOverlayView(context: Context) : View(context) {

    var detectedObjects: List<DetectedObject> = emptyList()
    var debugStats: DebugOverlay.DebugStats   = DebugOverlay.DebugStats()

    // conf → colour
    private val CONF_COLS = listOf(
        0.90f to Color.rgb(0,   220, 80),
        0.70f to Color.rgb(150, 220, 0),
        0.50f to Color.rgb(255, 200, 0),
        0.00f to Color.rgb(255, 80,  40),
    )

    private val boxPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lblPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f; typeface = Typeface.MONOSPACE; setShadowLayer(3f,0f,0f,Color.BLACK) }
    private val hudPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f; typeface = Typeface.MONOSPACE; setShadowLayer(4f,0f,0f,Color.BLACK) }
    private val hudBgPaint = Paint().apply { color = Color.argb(170, 0, 0, 0) }
    private val tmpBounds = Rect()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawObjects(canvas)
        drawHud(canvas)
    }

    private fun drawObjects(canvas: Canvas) {
        for (obj in detectedObjects) {
            val col = confCol(obj.confidence)
            fillPaint.color = Color.argb(35, Color.red(col), Color.green(col), Color.blue(col))
            canvas.drawRect(obj.bounds, fillPaint)
            boxPaint.color = col
            canvas.drawRect(obj.bounds, boxPaint)
            val lbl = "${obj.name} ${(obj.confidence*100).toInt()}%"
            lblPaint.getTextBounds(lbl, 0, lbl.length, tmpBounds)
            val lx = obj.bounds.left.toFloat() + 4f
            val ly = obj.bounds.top.toFloat() - 5f
            fillPaint.color = Color.argb(140, 0, 0, 0)
            canvas.drawRect(lx-2f, ly+tmpBounds.top-2f, lx+tmpBounds.width()+4f, ly+4f, fillPaint)
            canvas.drawText(lbl, lx, ly, lblPaint)
        }
    }

    private fun drawHud(canvas: Canvas) {
        val s = debugStats
        val lineH = hudPaint.textSize + 6f
        val bgH   = lineH * 2f + 12f
        canvas.drawRect(0f, 0f, width.toFloat(), bgH, hudBgPaint)

        val stateCol = when (s.gameState) {
            "PLAYING"       -> Color.rgb(0, 220, 80)
            "BANK_OPEN"     -> Color.rgb(255, 200, 50)
            "DIALOGUE_OPEN" -> Color.rgb(100, 180, 255)
            "LEVEL_UP"      -> Color.rgb(255, 215, 0)
            "LOGIN_SCREEN",
            "LOADING"       -> Color.rgb(255, 80, 80)
            else            -> Color.WHITE
        }

        hudPaint.color = stateCol
        canvas.drawText("[${s.gameState}]  HP:${s.hpPercent}%  ${if (s.isRunning) "RUN:✓" else "RUN:✗"}  Inv:${s.invOccupied}/28  ${s.runtime}", 8f, lineH, hudPaint)
        hudPaint.color = Color.WHITE
        canvas.drawText("XP/hr:${s.xpPerHour}  GP/hr:${s.gpPerHour}  Acts:${s.actions}  ${s.currentAction.take(22)}", 8f, lineH*2f + 2f, hudPaint)
    }

    private fun confCol(c: Float): Int { for ((t,col) in CONF_COLS) if (c >= t) return col; return CONF_COLS.last().second }
}
