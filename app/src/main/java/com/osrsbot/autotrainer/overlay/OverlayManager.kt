package com.osrsbot.autotrainer.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.osrsbot.autotrainer.R
import com.osrsbot.autotrainer.selector.TargetSelectorOverlay
import com.osrsbot.autotrainer.selector.TargetStore
import com.osrsbot.autotrainer.utils.Logger

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var isMinimised = false
    private var selectorOverlay: TargetSelectorOverlay? = null

    private val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else
        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // Flags: not focusable (doesn't steal keyboard) + not touch modal (touches outside pass through)
    // Do NOT use FLAG_LAYOUT_IN_SCREEN — it clips the overlay in fullscreen games
    private val overlayFlags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType,
        overlayFlags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 24
        y = 200
        // Show over display cutout (notch) on Android P+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    fun show(
        onStart: () -> Unit,
        onStop: () -> Unit,
    ) {
        if (overlayView != null) return
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_controls, null)
        overlayView = view

        view.findViewById<ImageButton>(R.id.btnOverlayStart).setOnClickListener {
            Logger.ok("Start tapped from overlay")
            onStart()
        }
        view.findViewById<ImageButton>(R.id.btnOverlayStop).setOnClickListener {
            Logger.warn("Stop tapped from overlay")
            onStop()
        }
        view.findViewById<ImageButton>(R.id.btnMinimize).setOnClickListener {
            toggleMinimise()
        }
        view.findViewById<Button>(R.id.btnSelectTarget).setOnClickListener {
            openTargetSelector()
        }
        view.findViewById<Button>(R.id.btnClearTargets).setOnClickListener {
            TargetStore.clear()
            updateTargetCount()
            Toast.makeText(context, "Targets cleared.", Toast.LENGTH_SHORT).show()
        }

        setupDrag(view.findViewById(R.id.dragHandle), view)
        setupDrag(view.findViewById(R.id.tvOverlayTitle), view)

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            overlayView = null
            Logger.error("Overlay failed to launch: ${e.message}")
            Toast.makeText(context, "Overlay failed: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        updateTargetCount()
        Logger.ok("Overlay shown.")
    }

    private fun openTargetSelector() {
        val script = context.getSharedPreferences("osrsbot", Context.MODE_PRIVATE)
            .getString("script", "") ?: ""
        val label = when (script) {
            "woodcutting" -> "Tree"
            "fishing"     -> "Fishing Spot"
            "combat"      -> "Monster"
            "chocolate"   -> "Item"
            "mining"      -> "Rock"
            else          -> "Target"
        }
        selectorOverlay = TargetSelectorOverlay(context) { savedTargets ->
            selectorOverlay = null
            updateTargetCount()
            val count = savedTargets.size
            Logger.ok("$count target(s) selected.")
            Toast.makeText(context, "$count target(s) saved! Bot will click them.", Toast.LENGTH_LONG).show()
        }
        selectorOverlay?.show(label, script)
    }

    private fun updateTargetCount() {
        val v = overlayView ?: return
        val count = TargetStore.count()
        val tv = v.findViewById<TextView>(R.id.tvOvTargets)
        if (count == 0) {
            tv?.text = "None set"
            tv?.setTextColor(context.getColor(R.color.orange))
        } else {
            tv?.text = "$count target(s)"
            tv?.setTextColor(context.getColor(R.color.green))
        }
    }

    private fun setupDrag(handle: View, overlay: View) {
        var startRawX = 0f
        var startRawY = 0f
        var initX = 0
        var initY = 0
        var dragging = false

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    startRawX = event.rawX
                    startRawY = event.rawY
                    initX = params.x
                    initY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    if (dx * dx + dy * dy > 16) dragging = true
                    if (dragging) {
                        params.x = (initX + dx).coerceAtLeast(0)
                        params.y = (initY + dy).coerceAtLeast(0)
                        try {
                            windowManager.updateViewLayout(overlay, params)
                        } catch (e: Exception) {
                            Logger.error("Overlay drag error: ${e.message}")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleMinimise() {
        val body = overlayView?.findViewById<View>(R.id.overlayBody) ?: return
        isMinimised = !isMinimised
        body.visibility = if (isMinimised) View.GONE else View.VISIBLE
        overlayView?.findViewById<ImageButton>(R.id.btnMinimize)?.setImageDrawable(
            context.getDrawable(
                if (isMinimised) android.R.drawable.arrow_down_float
                else android.R.drawable.arrow_up_float
            )
        )
    }

    fun hide() {
        selectorOverlay?.dismiss()
        selectorOverlay = null
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Logger.error("Overlay hide error: ${e.message}")
            }
            overlayView = null
        }
        Logger.warn("Overlay hidden.")
    }

    fun updateStats(
        script: String,
        actions: Int,
        xp: Int,
        gpHr: Int,
        runtime: String,
        status: String,
        currentAction: String,
    ) {
        val v = overlayView ?: return
        v.post {
            v.findViewById<TextView>(R.id.tvOvScript)?.text  = script
            v.findViewById<TextView>(R.id.tvOvActions)?.text = actions.toString()
            v.findViewById<TextView>(R.id.tvOvXP)?.text      = xp.toString()
            v.findViewById<TextView>(R.id.tvOvGP)?.text      = "$gpHr/hr"
            v.findViewById<TextView>(R.id.tvOvRuntime)?.text = runtime
            v.findViewById<TextView>(R.id.tvOvStatus)?.text  = status
            v.findViewById<TextView>(R.id.tvOvAction)?.text  = currentAction
            updateTargetCount()
        }
    }
}
