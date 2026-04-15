package com.osrsbot.autotrainer.scripts

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.osrsbot.autotrainer.antiban.AntiBanManager
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.selector.TargetStore
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.Logger
import kotlinx.coroutines.delay
import kotlin.random.Random

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

    private enum class State { FIND_TREE, CHOPPING, BANKING }
    private var state = State.FIND_TREE

    // How many chop attempts failed in a row (tree may have been cut by another player)
    private var failedAttempts = 0

    override suspend fun tick() {
        when (state) {

            State.FIND_TREE -> {
                if (logsInInventory >= 27) {
                    state = State.BANKING
                    return
                }
                setAction("Looking for tree…")

                val userTarget = TargetStore.nextTarget()
                if (userTarget != null) {
                    // User tapped a specific tree — click it exactly once
                    delay(antiBan.getClickDelay())
                    val (ox, oy) = antiBan.getClickOffset()
                    tap(userTarget.x + ox.toFloat(), userTarget.y + oy.toFloat())
                    Logger.action("Clicking '${userTarget.label}' @ (${userTarget.x.toInt()}, ${userTarget.y.toInt()})")
                    state = State.CHOPPING
                } else {
                    // No saved targets — try accessibility detection, fall back to centre-screen
                    val dm = service.resources.displayMetrics
                    val detected = detector.detectObjects("woodcutting")
                    val nearest = if (detected.isNotEmpty())
                        detector.findNearest(detected, dm.widthPixels, dm.heightPixels)
                    else null

                    if (nearest != null) {
                        delay(antiBan.getClickDelay())
                        val (ox, oy) = antiBan.getClickOffset()
                        tap(nearest.bounds.exactCenterX() + ox, nearest.bounds.exactCenterY() + oy)
                        Logger.action("Detected tree — clicking")
                        state = State.CHOPPING
                    } else {
                        // Nothing found — wait a moment and retry
                        setAction("No tree found. Tap 🎯 to set targets!")
                        delay(2000L + Random.nextLong(0, 500))
                    }
                }
            }

            State.CHOPPING -> {
                // Wait for the chop to complete — ONE click per action
                // OSRS: normal tree ~3-5 game ticks (1.8-3s), oak ~4-7 ticks (2.4-4.2s)
                // Add human overhead → 4.5-6.5s total
                val chopMs = antiBan.getWoodcuttingChopDelay()
                setAction("Chopping… (${chopMs / 1000}s)")
                delay(chopMs)

                // Occasionally a tree is already cut by another player — re-find after 3 fails
                failedAttempts = 0
                logsInInventory++
                completeAction(XP_PER_LOG, GP_PER_LOG)
                Logger.ok("Log #$actions | Inv: $logsInInventory/27 | XP: $xpGained")

                // Small human pause before next click
                delay(antiBan.getActionDelay())
                state = if (logsInInventory >= 27) State.BANKING else State.FIND_TREE
            }

            State.BANKING -> {
                setAction("Inventory full — banking…")
                delay(antiBan.getBankingDelay())
                logsInInventory = 0
                Logger.ok("Banked ${actions} logs total.")
                delay(antiBan.getActionDelay())
                state = State.FIND_TREE
            }
        }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
