package com.osrsbot.autotrainer.scripts

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import com.osrsbot.autotrainer.antiban.AntiBanManager
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.Logger
import kotlinx.coroutines.delay

class ChocolateDustScript(
    service: AccessibilityService,
    config: BotConfig,
    antiBan: AntiBanManager,
    detector: ObjectDetector,
) : BotScript(service, config, antiBan, detector) {

    override val id = "chocolate"
    override val name = "🍫 Chocolate Dust Maker"

    private var barsInInventory = 27
    private val XP_PER_ACTION = 0
    private val GP_PER_ACTION = 180

    private val STEPS = listOf(
        "Opening inventory…",
        "Selecting Knife from inventory…",
        "Tapping Use on Knife…",
        "Tapping Chocolate Bar…",
        "Grinding bar — Make-X dialog…",
        "Confirming Make-All…",
        "Waiting for grinding animation…",
        "Picking up Chocolate Dust…",
    )

    private var step = 0

    override suspend fun tick() {
        if (barsInInventory <= 0) {
            setAction("No bars left — walking to bank…")
            delay(antiBan.getActionDelay() * 2)
            setAction("Depositing dust, withdrawing bars…")
            delay(antiBan.getActionDelay())
            barsInInventory = 27
            Logger.ok("Banked. New trip started.")
            return
        }

        val stepMsg = STEPS[step % STEPS.size]
        setAction(stepMsg)

        val detected = detector.detectObjects("chocolate")
        val target = detected.firstOrNull {
            it.name.lowercase().contains("chocolate") || it.name.lowercase().contains("knife")
        }

        if (target != null) {
            performTap(target.bounds)
        } else {
            performTapRelative(0.5f, 0.6f)
        }

        delay(antiBan.getClickDelay())
        delay(antiBan.getActionDelay())

        step++

        if (step % STEPS.size == 0) {
            barsInInventory--
            completeAction(XP_PER_ACTION, GP_PER_ACTION)
            Logger.ok("Bar ground → Chocolate Dust. Total actions: $actions | GP earned: $gpGained")
        }
    }

    private fun performTap(bounds: Rect) {
        val (ox, oy) = antiBan.getClickOffset()
        val x = (bounds.exactCenterX() + ox).coerceAtLeast(1f)
        val y = (bounds.exactCenterY() + oy).coerceAtLeast(1f)
        tap(x, y)
    }

    private fun performTapRelative(fx: Float, fy: Float) {
        val dm = service.resources.displayMetrics
        val (ox, oy) = antiBan.getClickOffset()
        tap(dm.widthPixels * fx + ox, dm.heightPixels * fy + oy)
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }
}
