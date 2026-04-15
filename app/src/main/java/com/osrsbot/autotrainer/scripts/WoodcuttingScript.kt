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

class WoodcuttingScript(
    service: AccessibilityService,
    config: BotConfig,
    antiBan: AntiBanManager,
    detector: ObjectDetector,
) : BotScript(service, config, antiBan, detector) {

    override val id = "woodcutting"
    override val name = "🌲 Woodcutting Bot"

    private val XP_PER_LOG = 38
    private val GP_PER_LOG = 50
    private var logsInInventory = 0

    private val STEPS = listOf(
        "Scanning for nearby trees…",
        "Tree detected — walking to it…",
        "Right-clicking tree…",
        "Selecting 'Chop down'…",
        "Chopping… (waiting for log)…",
        "Log received! Inventory updated.",
        "Checking inventory space…",
    )
    private var step = 0

    override suspend fun tick() {
        if (logsInInventory >= 27) {
            setAction("Inventory full — walking to bank…")
            delay(antiBan.getActionDelay() * 2)
            setAction("Depositing logs…")
            delay(antiBan.getActionDelay())
            logsInInventory = 0
            Logger.ok("Banked logs. Returning to trees.")
            return
        }

        val stepMsg = STEPS[step % STEPS.size]
        setAction(stepMsg)

        val detected = detector.detectObjects("woodcutting")
        val tree = detected.firstOrNull { it.name.lowercase().contains("tree") }

        if (tree != null) {
            val dm = service.resources.displayMetrics
            val nearest = detector.findNearest(detected, dm.widthPixels, dm.heightPixels)
            nearest?.let { performTap(it.bounds) } ?: performTapRelative(0.5f, 0.4f)
        } else {
            performTapRelative(0.5f, 0.4f)
        }

        delay(antiBan.getClickDelay())
        delay(antiBan.getActionDelay())
        step++

        if (step % STEPS.size == 0) {
            logsInInventory++
            completeAction(XP_PER_LOG, GP_PER_LOG)
            Logger.ok("Log chopped! Inventory: $logsInInventory/27 | XP: $xpGained")
        }
    }

    private fun performTap(bounds: Rect) {
        val (ox, oy) = antiBan.getClickOffset()
        tap(bounds.exactCenterX() + ox, bounds.exactCenterY() + oy)
    }

    private fun performTapRelative(fx: Float, fy: Float) {
        val dm = service.resources.displayMetrics
        tap(dm.widthPixels * fx, dm.heightPixels * fy)
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
