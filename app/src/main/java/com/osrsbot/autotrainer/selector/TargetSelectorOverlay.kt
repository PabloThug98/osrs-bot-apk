package com.osrsbot.autotrainer.selector

import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.*
import android.widget.Toast

/**
 * A fullscreen transparent overlay drawn over the game.
 * User taps on trees/rocks/objects to register click targets.
 * Each tap adds a coloured circle marker.
 */
class TargetSelectorOverlay(
    private val context: Context,
    private val onDone: (List<GameTarget>) -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: SelectorView? = null

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    fun show(label: String = "Target", scriptId: String = "") {
        if (overlayView != null) return
        val view = SelectorView(context, label, scriptId)
        overlayView = view

        view.onFinish = { targets ->
            dismiss()
            onDone(targets)
        }
        view.onCancel = { dismiss() }

        windowManager.addView(view, params)
        Toast.makeText(context, "TAP on the objects you want the bot to click.\nTap DONE when finished.", Toast.LENGTH_LONG).show()
    }

    fun dismiss() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }

    inner class SelectorView(
        context: Context,
        private val defaultLabel: String,
        private val scriptId: String,
    ) : View(context) {

        var onFinish: ((List<GameTarget>) -> Unit)? = null
        var onCancel: (() -> Unit)? = null

        private val tappedTargets = mutableListOf<GameTarget>()
        private val markerColors = listOf(
            Color.parseColor("#F44336"),
            Color.parseColor("#4CAF50"),
            Color.parseColor("#2196F3"),
            Color.parseColor("#FF9800"),
            Color.parseColor("#9C27B0"),
            Color.parseColor("#00BCD4"),
        )

        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#55000000")
            style = Paint.Style.FILL
        }
        private val instructionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val btnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        // Button rects computed in onDraw
        private val doneRect   = RectF()
        private val clearRect  = RectF()
        private val cancelRect = RectF()

        init { isClickable = true }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()

            // Dim overlay
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            // Instruction bar at top
            canvas.drawRect(0f, 0f, w, 100f, Paint().apply { color = Color.parseColor("#CC000000") })
            val count = tappedTargets.size
            canvas.drawText(instructionText(count), w / 2, 65f, instructionPaint)

            // Draw each marked target
            tappedTargets.forEachIndexed { i, target ->
                val color = markerColors[i % markerColors.size]
                // Outer ring
                circlePaint.color = color
                canvas.drawCircle(target.x, target.y, 38f, circlePaint)
                // Inner fill (semi-transparent)
                fillPaint.color = Color.argb(80, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawCircle(target.x, target.y, 36f, fillPaint)
                // Number label
                textPaint.color = color
                canvas.drawText("${i + 1}", target.x, target.y + 10f, textPaint)
                // Name below
                val namePaint = Paint(textPaint).apply {
                    textSize = 22f
                    setShadowLayer(4f, 0f, 2f, Color.BLACK)
                }
                canvas.drawText(target.label, target.x, target.y + 62f, namePaint)
            }

            // Bottom button bar
            val barH = 120f
            canvas.drawRect(0f, h - barH, w, h, Paint().apply { color = Color.parseColor("#CC000000") })

            val btnW = (w - 40f) / 3
            val btnTop = h - barH + 16f
            val btnBot = h - 16f
            val radius = 16f

            // DONE button
            doneRect.set(10f, btnTop, 10f + btnW, btnBot)
            btnPaint.color = Color.parseColor("#4CAF50")
            canvas.drawRoundRect(doneRect, radius, radius, btnPaint)
            canvas.drawText("✓ DONE", doneRect.centerX(), doneRect.centerY() + 13f, btnTextPaint)

            // CLEAR button
            clearRect.set(10f + btnW + 10f, btnTop, 10f + 2 * btnW + 10f, btnBot)
            btnPaint.color = Color.parseColor("#FF9800")
            canvas.drawRoundRect(clearRect, radius, radius, btnPaint)
            canvas.drawText("CLEAR", clearRect.centerX(), clearRect.centerY() + 13f, btnTextPaint)

            // CANCEL button
            cancelRect.set(10f + 2 * btnW + 20f, btnTop, w - 10f, btnBot)
            btnPaint.color = Color.parseColor("#F44336")
            canvas.drawRoundRect(cancelRect, radius, radius, btnPaint)
            canvas.drawText("✕ CANCEL", cancelRect.centerX(), cancelRect.centerY() + 13f, btnTextPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_DOWN) return true
            val x = event.x
            val y = event.y

            when {
                doneRect.contains(x, y) -> {
                    if (tappedTargets.isEmpty()) {
                        Toast.makeText(context, "Tap at least one target first!", Toast.LENGTH_SHORT).show()
                    } else {
                        TargetStore.clear()
                        tappedTargets.forEach { TargetStore.add(it) }
                        Toast.makeText(context, "${tappedTargets.size} target(s) saved!", Toast.LENGTH_SHORT).show()
                        onFinish?.invoke(tappedTargets.toList())
                    }
                }
                clearRect.contains(x, y) -> {
                    tappedTargets.clear()
                    invalidate()
                    Toast.makeText(context, "Targets cleared.", Toast.LENGTH_SHORT).show()
                }
                cancelRect.contains(x, y) -> {
                    onCancel?.invoke()
                }
                y > 110f && y < height - 130f -> {
                    val existingIndex = tappedTargets.indexOfFirst { target ->
                        val dx = target.x - x
                        val dy = target.y - y
                        dx * dx + dy * dy <= target.radiusPx * target.radiusPx
                    }
                    if (existingIndex >= 0) {
                        tappedTargets.removeAt(existingIndex)
                        relabelTargets()
                        invalidate()
                        Toast.makeText(context, "Target removed.", Toast.LENGTH_SHORT).show()
                    } else {
                        val target = GameTarget(
                            label = defaultLabel,
                            x = x, y = y,
                        )
                        tappedTargets.add(target)
                        relabelTargets()
                        invalidate()
                        Toast.makeText(context, "${tappedTargets.last().label} saved at (${x.toInt()}, ${y.toInt()})", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return true
        }

        private fun relabelTargets() {
            for (i in tappedTargets.indices) {
                val target = tappedTargets[i]
                tappedTargets[i] = target.copy(label = labelFor(i, tappedTargets.size))
            }
        }

        private fun labelFor(index: Int, total: Int): String = when (scriptId) {
            "woodcutting" -> if (total > 1 && index == total - 1) "Bank" else "Tree ${index + 1}"
            "fishing" -> if (total > 1 && index == total - 1) "Bank" else "Fishing Spot ${index + 1}"
            "chocolate" -> when (index) {
                0 -> "Knife"
                1 -> "Chocolate Bar"
                2 -> "Bank"
                else -> "Item ${index + 1}"
            }
            "combat" -> "Monster ${index + 1}"
            else -> "$defaultLabel ${index + 1}"
        }

        private fun instructionText(count: Int): String = when (scriptId) {
            "woodcutting" -> if (count <= 1) "Tap tree(s), then tap bank last  [$count saved]" else "Last target is Bank  [$count saved]"
            "fishing" -> if (count <= 1) "Tap fishing spot(s), bank last optional  [$count saved]" else "Last target is Bank  [$count saved]"
            "chocolate" -> "Tap Knife, Chocolate Bar, optional Bank  [$count saved]"
            "combat" -> "Tap monster target(s)  [$count saved]"
            else -> "Tap targets on screen  [$count saved]"
        }
    }
}
