package com.osrsbot.autotrainer.utils

data class BotConfig(
    val scriptId: String = "chocolate",
    val antiBanBreaks: Boolean = true,
    val breakIntervalActions: Int = 50,
    val breakDurationMs: Long = 30_000L,
    val stopOnPlayerNearby: Boolean = true,
    val humanLikeMouse: Boolean = true,
    val stopAfterTime: Boolean = false,
    val stopAfterMinutes: Int = 60,
    val stopAfterActions: Boolean = false,
    val stopAfterActionCount: Int = 500,
    val fatigueEnabled: Boolean = true,
    val randomCameraMove: Boolean = false,
)

object ScriptInfo {
    val scripts = mapOf(
        "chocolate"   to Triple("🍫 Chocolate Dust Maker", 0,    180),
        "woodcutting" to Triple("🌲 Woodcutting Bot",      38,   50),
        "fishing"     to Triple("🎣 Fishing Bot",           40,   40),
        "combat"      to Triple("⚔️ Combat Trainer",        60,   20),
    )
    fun name(id: String)  = scripts[id]?.first ?: "Unknown"
    fun xpPer(id: String) = scripts[id]?.second ?: 0
    fun gpPer(id: String) = scripts[id]?.third ?: 0
}
