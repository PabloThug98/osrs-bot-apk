package com.osrsbot.autotrainer.scripts

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import com.osrsbot.autotrainer.antiban.AntiBanManager
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.selector.TargetStore
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.Logger
import kotlinx.coroutines.delay

class ChocolateDustScript(
    service: AccessibilityService,
    config: BotConfig,
    antiBan: AntiBanManager,
    detector: ObjectDetector,
) : BotScript(service, config, antiBan, detector) {

    override val id   = "chocolate"
    override val name = "🍫 Chocolate Dust Maker"

    private var barsInInventory = 27
    private val GP_PER_BAR = 180

    private val STEPS = listOf(
        "Opening inventory…",
        "Selecting Knife…",
        "Tapping Use on Knife…",
        "Tapping Chocolate Bar…",
        "Make-X dialog — confirming All…",
        "Grinding… waiting for animation…",
        "Collecting Chocolate Dust…",
    )
    private var step = 0

    override suspend fun tick() {
        if (barsInInventory <= 0) {
            setAction("No bars left — walking to bank…")
            delay(antiBan.getActionDelay() * 3)
            setAction("Banking dust, withdrawing bars…")
            delay(antiBan.getActionDelay())
            barsInInventory = 27
            Logger.ok("Banked. New trip.")
            return
        }

        setAction(STEPS[step % STEPS.size])

        // ── Use user-selected targets if available, else auto-detect ──
        val userTarget = TargetStore.nextTarget()
        if (userTarget != null) {
            val (ox, oy) = antiBan.getClickOffset()
            tap(userTarget.x + ox, userTarget.y + oy)
            Logger.action("Clicking user target '${userTarget.label}' @ (${userTarget.x.toInt()}, ${userTarget.y.toInt()})")
        } else {
            // Fall back to accessibility detection
            val detected = detector.detectObjects("chocolate")
            val target = detected.firstOrNull {
                it.name.lowercase().contains("chocolate") || it.name.lowercase().contains("knife")
            }
            if (target != null) {
                val (ox, oy) = antiBan.getClickOffset()
                tap(target.bounds.exactCenterX() + ox, target.bounds.exactCenterY() + oy)
            } else {
                tapRelative(0.5f, 0.62f)
            }
        }

        delay(antiBan.getClickDelay())
        delay(antiBan.getActionDelay())
        step++

        if (step % STEPS.size == 0) {
            barsInInventory--
            completeAction(0, GP_PER_BAR)
            Logger.ok("Bar #${actions} ground → Dust | GP: $gpGained")
        }
    }

    private fun tapRelative(fx: Float, fy: Float) {
        val dm = service.resources.displayMetrics
        val (ox, oy) = antiBan.getClickOffset()
        tap(dm.widthPixels * fx + ox, dm.heightPixels * fy + oy)
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
