package com.osrsbot.autotrainer.scripts

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.osrsbot.autotrainer.antiban.AntiBanManager
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.Logger
import kotlinx.coroutines.delay

class CombatScript(
    service: AccessibilityService,
    config: BotConfig,
    antiBan: AntiBanManager,
    detector: ObjectDetector,
) : BotScript(service, config, antiBan, detector) {

    override val id = "combat"
    override val name = "⚔️ Combat Trainer"

    private val XP_PER_KILL = 60
    private val GP_PER_KILL = 20
    private var hp = 100
    private val EAT_HP_THRESHOLD = 50

    private val STEPS = listOf(
        "Scanning for monsters…",
        "Walking to target…",
        "Attacking monster…",
        "In combat… (dealing damage)",
        "Monster dead — looting…",
        "Picking up drops…",
    )
    private var step = 0

    override suspend fun tick() {
        hp -= (5..15).random()
        if (hp < EAT_HP_THRESHOLD) {
            setAction("HP low ($hp) — eating food…")
            hp = (80..99).random()
            delay(antiBan.getActionDelay())
            Logger.warn("Ate food. HP restored to $hp.")
            return
        }

        setAction(STEPS[step % STEPS.size])
        val detected = detector.detectObjects("combat")
        val monster = detected.firstOrNull {
            listOf("goblin","cow","imp","rat","spider").any { m -> it.name.lowercase().contains(m) }
        }

        if (monster != null) {
            val (ox, oy) = antiBan.getClickOffset()
            tap(monster.bounds.exactCenterX() + ox, monster.bounds.exactCenterY() + oy)
        } else {
            val dm = service.resources.displayMetrics
            tap(dm.widthPixels * 0.5f, dm.heightPixels * 0.4f)
        }

        delay(antiBan.getClickDelay())
        delay(antiBan.getActionDelay())
        step++

        if (step % STEPS.size == 0) {
            completeAction(XP_PER_KILL, GP_PER_KILL)
            Logger.ok("Kill #$actions | XP: $xpGained | GP: $gpGained")
        }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
