package com.osrsbot.autotrainer.scripts

import android.accessibilityservice.AccessibilityService
import com.osrsbot.autotrainer.antiban.AntiBanManager
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.utils.BotConfig
import com.osrsbot.autotrainer.utils.Logger

abstract class BotScript(
    protected val service: AccessibilityService,
    protected val config: BotConfig,
    protected val antiBan: AntiBanManager,
    protected val detector: ObjectDetector,
) {
    abstract val id: String
    abstract val name: String

    var actions: Int = 0
    var xpGained: Int = 0
    var gpGained: Int = 0
    var currentAction: String = "Initialising…"

    abstract suspend fun tick()
    open fun onStart() { Logger.ok("Script started: $name") }
    open fun onStop()  { Logger.warn("Script stopped: $name") }

    protected fun setAction(msg: String) {
        currentAction = msg
        Logger.action(msg)
    }

    protected fun completeAction(xp: Int = 0, gp: Int = 0) {
        actions++
        xpGained += xp
        gpGained  += gp + (-10..10).random()
        antiBan.onActionCompleted()
    }
}
