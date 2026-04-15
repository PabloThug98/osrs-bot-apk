package com.osrsbot.autotrainer.scripts

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.osrsbot.autotrainer.antiban.AntiBanManager
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.Logger
import kotlinx.coroutines.delay

class FishingScript(
    service: AccessibilityService,
    config: BotConfig,
    antiBan: AntiBanManager,
    detector: ObjectDetector,
) : BotScript(service, config, antiBan, detector) {

    override val id = "fishing"
    override val name = "🎣 Fishing Bot"

    private val XP_PER_FISH = 40
    private val GP_PER_FISH = 40
    private var fishInInventory = 0

    private val STEPS = listOf(
        "Scanning for fishing spot…",
        "Walking to nearest spot…",
        "Clicking fishing spot…",
        "Waiting for a bite…",
        "Fish caught! Inventory updated.",
        "Re-clicking spot…",
    )
    private var step = 0

    override suspend fun tick() {
        if (fishInInventory >= 27) {
            setAction("Inventory full — banking fish…")
            delay(antiBan.getActionDelay() * 2)
            fishInInventory = 0
            Logger.ok("Fish banked. Resuming.")
            return
        }

        setAction(STEPS[step % STEPS.size])
        val detected = detector.detectObjects("fishing")
        val spot = detected.firstOrNull { it.name.lowercase().contains("fishing") }

        if (spot != null) {
            val (ox, oy) = antiBan.getClickOffset()
            tap(spot.bounds.exactCenterX() + ox, spot.bounds.exactCenterY() + oy)
        } else {
            val dm = service.resources.displayMetrics
            tap(dm.widthPixels * 0.5f, dm.heightPixels * 0.45f)
        }

        delay(antiBan.getClickDelay())
        delay(antiBan.getActionDelay())
        step++

        if (step % STEPS.size == 0) {
            fishInInventory++
            completeAction(XP_PER_FISH, GP_PER_FISH)
            Logger.ok("Fish caught. Inventory: $fishInInventory/27 | XP: $xpGained")
        }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
