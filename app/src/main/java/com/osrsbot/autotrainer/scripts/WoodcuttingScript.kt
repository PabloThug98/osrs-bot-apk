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

class WoodcuttingScript(
    service: AccessibilityService,
    config: BotConfig,
    antiBan: AntiBanManager,
    detector: ObjectDetector,
) : BotScript(service, config, antiBan, detector) {

    override val id   = "woodcutting"
    override val name = "🌲 Woodcutting Bot"

    private val XP_PER_LOG = 38
    private val GP_PER_LOG = 50
    private var logsInInventory = 0

    private val STEPS = listOf(
        "Scanning for nearby trees…",
        "Walking toward tree…",
        "Right-clicking tree…",
        "Selecting 'Chop down'…",
        "Chopping… waiting for log…",
        "Log received!",
    )
    private var step = 0

    override suspend fun tick() {
        if (logsInInventory >= 27) {
            setAction("Inventory full — banking logs…")
            delay(antiBan.getActionDelay() * 3)
            setAction("Depositing logs…")
            delay(antiBan.getActionDelay())
            logsInInventory = 0
            Logger.ok("Banked logs.")
            return
        }

        setAction(STEPS[step % STEPS.size])

        // ── Use user-selected targets (trees the user tapped) ──
        val userTarget = TargetStore.nextTarget()
        if (userTarget != null) {
            val (ox, oy) = antiBan.getClickOffset()
            tap(userTarget.x + ox, userTarget.y + oy)
            Logger.action("Clicking tree '${userTarget.label}' @ (${userTarget.x.toInt()}, ${userTarget.y.toInt()})")
        } else {
            // Auto-detect trees via accessibility
            val dm = service.resources.displayMetrics
            val detected = detector.detectObjects("woodcutting")
            val nearest = if (detected.isNotEmpty())
                detector.findNearest(detected, dm.widthPixels, dm.heightPixels)
            else null

            if (nearest != null) {
                val (ox, oy) = antiBan.getClickOffset()
                tap(nearest.bounds.exactCenterX() + ox, nearest.bounds.exactCenterY() + oy)
            } else {
                tapRelative(0.5f, 0.4f)
            }
        }

        delay(antiBan.getClickDelay())
        delay(antiBan.getActionDelay())
        step++

        if (step % STEPS.size == 0) {
            logsInInventory++
            completeAction(XP_PER_LOG, GP_PER_LOG)
            Logger.ok("Log #${actions} | Inv: $logsInInventory/27 | XP: $xpGained")
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
