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

class CombatScript(
    service: AccessibilityService,
    config: BotConfig,
    antiBan: AntiBanManager,
    detector: ObjectDetector,
) : BotScript(service, config, antiBan, detector) {

    override val id   = "combat"
    override val name = "⚔️ Combat Trainer"

    private val XP_PER_KILL = 60
    private val GP_PER_KILL = 20
    private var hp = 99
    private val EAT_HP_THRESHOLD = 45

    private enum class State { FIND_MONSTER, IN_COMBAT, LOOTING, EATING }
    private var state = State.FIND_MONSTER

    override suspend fun tick() {
        when (state) {

            State.FIND_MONSTER -> {
                // Simulate gradual HP loss during combat
                hp -= Random.nextInt(1, 4)
                if (hp <= EAT_HP_THRESHOLD) {
                    state = State.EATING
                    return
                }

                setAction("Looking for monster…")
                val userTarget = TargetStore.nextTarget()

                if (userTarget != null) {
                    delay(antiBan.getClickDelay())
                    val (ox, oy) = antiBan.getClickOffset()
                    tap(userTarget.x + ox.toFloat(), userTarget.y + oy.toFloat())
                    Logger.action("Attacking '${userTarget.label}'")
                    state = State.IN_COMBAT
                } else {
                    val dm = service.resources.displayMetrics
                    val detected = detector.detectObjects("combat")
                    val monster = detected.firstOrNull {
                        listOf("goblin", "cow", "imp", "rat", "spider", "chicken")
                            .any { m -> it.name.lowercase().contains(m) }
                    }

                    if (monster != null) {
                        delay(antiBan.getClickDelay())
                        val (ox, oy) = antiBan.getClickOffset()
                        tap(monster.bounds.exactCenterX() + ox, monster.bounds.exactCenterY() + oy)
                        Logger.action("Attacking detected monster")
                        state = State.IN_COMBAT
                    } else {
                        setAction("No monster found. Tap 🎯 to set targets!")
                        delay(2000L + Random.nextLong(0, 500))
                    }
                }
            }

            State.IN_COMBAT -> {
                // Wait for the kill — OSRS combat: varies by monster HP and player stats
                // Typical lowbie monster: 8-14 seconds
                val killMs = antiBan.getCombatKillDelay()
                setAction("Fighting… (${killMs / 1000}s)")
                delay(killMs)

                completeAction(XP_PER_KILL, GP_PER_KILL)
                Logger.ok("Kill #$actions | XP: $xpGained | HP: $hp")

                // Loot drops
                state = State.LOOTING
            }

            State.LOOTING -> {
                setAction("Looting drops…")
                // Click slightly below kill position to loot (drops fall near where monster died)
                val userTarget = TargetStore.peekCurrent()
                if (userTarget != null) {
                    val (ox, oy) = antiBan.getClickOffset()
                    // Loot is roughly where the monster was, slight downward offset
                    tap(userTarget.x + ox + Random.nextInt(-15, 15),
                        userTarget.y + oy + Random.nextInt(20, 40).toFloat())
                }
                delay(antiBan.getActionDelay() * 2)
                state = State.FIND_MONSTER
            }

            State.EATING -> {
                setAction("HP low ($hp) — eating food…")
                delay(antiBan.getActionDelay())
                hp = Random.nextInt(80, 99)
                Logger.warn("Ate food — HP: $hp")
                delay(antiBan.getClickDelay())
                state = State.FIND_MONSTER
            }
        }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
