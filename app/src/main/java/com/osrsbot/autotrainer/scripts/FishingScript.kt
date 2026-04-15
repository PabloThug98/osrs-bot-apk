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

class FishingScript(
    service: AccessibilityService,
    config: BotConfig,
    antiBan: AntiBanManager,
    detector: ObjectDetector,
) : BotScript(service, config, antiBan, detector) {

    override val id   = "fishing"
    override val name = "🎣 Fishing Bot"

    private val XP_PER_FISH = 40
    private val GP_PER_FISH = 40
    private var fishInInventory = 0

    private enum class State { FIND_SPOT, FISHING, BANKING }
    private var state = State.FIND_SPOT

    override suspend fun tick() {
        when (state) {

            State.FIND_SPOT -> {
                if (fishInInventory >= 27) {
                    state = State.BANKING
                    return
                }
                setAction("Looking for fishing spot…")

                val userTarget = TargetStore.nextTarget()
                if (userTarget != null) {
                    delay(antiBan.getClickDelay())
                    val (ox, oy) = antiBan.getClickOffset()
                    tap(userTarget.x + ox.toFloat(), userTarget.y + oy.toFloat())
                    Logger.action("Clicking spot '${userTarget.label}'")
                    state = State.FISHING
                } else {
                    val dm = service.resources.displayMetrics
                    val detected = detector.detectObjects("fishing")
                    val spot = detected.firstOrNull()

                    if (spot != null) {
                        delay(antiBan.getClickDelay())
                        val (ox, oy) = antiBan.getClickOffset()
                        tap(spot.bounds.exactCenterX() + ox, spot.bounds.exactCenterY() + oy)
                        Logger.action("Detected fishing spot — clicking")
                        state = State.FISHING
                    } else {
                        setAction("No spot found. Tap 🎯 to set spot!")
                        delay(2000L + Random.nextLong(0, 500))
                    }
                }
            }

            State.FISHING -> {
                // OSRS fishing: 1 catch every 6-10 seconds depending on level & spot
                val waitMs = antiBan.getFishingWaitDelay()
                setAction("Fishing… (${waitMs / 1000}s)")
                delay(waitMs)

                // Fishing spots move every ~60-90 seconds — the script re-clicks the saved coord
                // which is usually close enough. User can set multiple spots for redundancy.
                fishInInventory++
                completeAction(XP_PER_FISH, GP_PER_FISH)
                Logger.ok("Fish #$actions | Inv: $fishInInventory/27 | XP: $xpGained")

                delay(antiBan.getActionDelay())
                // Re-click the spot after each catch (spot may drift slightly)
                state = if (fishInInventory >= 27) State.BANKING else State.FIND_SPOT
            }

            State.BANKING -> {
                setAction("Inventory full — banking fish…")
                delay(antiBan.getBankingDelay())
                fishInInventory = 0
                Logger.ok("Banked ${actions} fish total.")
                delay(antiBan.getActionDelay())
                state = State.FIND_SPOT
            }
        }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
