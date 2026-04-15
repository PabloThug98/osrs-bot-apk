package com.osrsbot.autotrainer.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import com.osrsbot.autotrainer.R
import com.osrsbot.autotrainer.utils.Logger

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var isMinimised = false

    private var startX = 0f
    private var startY = 0f
    private var initX = 0
    private var initY = 0
    private var isDragging = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE,
        // KEY FIX: FLAG_NOT_FOCUSABLE allows touches to pass through to game
        // but the overlay itself IS still touchable (no FLAG_NOT_TOUCHABLE)
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 20
        y = 120
    }

    fun show(
        onStart: () -> Unit,
        onStop: () -> Unit,
    ) {
        if (overlayView != null) return
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_controls, null)
        overlayView = view

        // ── Buttons — these must work, not be click-through ──
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

        // ── Drag logic on header ──
        val header = view.findViewById<View>(R.id.overlayHeader)
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    startX = event.rawX
                    startY = event.rawY
                    initX = params.x
                    initY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startX).toInt()
                    val dy = (event.rawY - startY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) isDragging = true
                    if (isDragging) {
                        params.x = initX + dx
                        params.y = initY + dy
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, params)
        Logger.ok("Overlay shown.")
    }

    fun hide() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
        Logger.warn("Overlay hidden.")
    }

    private fun toggleMinimise() {
        val body = overlayView?.findViewById<View>(R.id.overlayBody) ?: return
        isMinimised = !isMinimised
        body.visibility = if (isMinimised) View.GONE else View.VISIBLE
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
        }
    }
}
