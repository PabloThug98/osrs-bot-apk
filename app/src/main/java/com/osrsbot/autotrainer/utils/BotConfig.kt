package com.osrsbot.autotrainer.utils

/**
 * BotConfig — all user-configurable settings for the bot.
 *
 * New in v2:
 *  - playerWoodcuttingLevel / playerFishingLevel / playerCombatLevel
 *    Used by AntiBanManager to produce level-appropriate action timings.
 *  - axeType — affects woodcutting log acquisition rate.
 *  - detectConfidenceMin — minimum detection confidence to act on.
 */
data class BotConfig(
      val scriptId: String = "chocolate",

      // ── Antiban ──────────────────────────────────────────────────────────────
      val antiBanBreaks: Boolean      = true,
      val breakIntervalActions: Int   = 50,
      val breakDurationMs: Long       = 30_000L,
      val stopOnPlayerNearby: Boolean = true,
      val humanLikeMouse: Boolean     = true,
      val fatigueEnabled: Boolean     = true,
      val randomCameraMove: Boolean   = false,

      // ── Auto-stop ────────────────────────────────────────────────────────────
      val stopAfterTime: Boolean    = false,
      val stopAfterMinutes: Int     = 60,
      val stopAfterActions: Boolean = false,
      val stopAfterActionCount: Int = 500,

      // ── Walker ────────────────────────────────────────────────────────────────
      // Values: "none", "lumbridge", "draynor", "varrock_west", "varrock_east",
      //         "falador", "edgeville", "barbarian"
      val walkerArea: String = "none",

      // ── Player skill levels (1-99) — used for smarter timing ─────────────────
      val playerWoodcuttingLevel: Int = 1,
      val playerFishingLevel: Int     = 1,
      val playerCombatLevel: Int      = 3,
    val playerMiningLevel: Int     = 1,

      // ── Woodcutting axe type — affects log rate ───────────────────────────────
      // Values: "bronze", "iron", "steel", "black", "mithril", "adamant", "rune", "dragon", "crystal"
      val axeType: String = "iron",
    val oreType: String = "iron",

      // ── Detection — minimum confidence score (0.0-1.0) to act on ─────────────
      val detectConfidenceMin: Float = 0.50f,
)

object ScriptInfo {
      val scripts = mapOf(
          "chocolate"   to Triple("Chocolate Dust Maker", 0,  180),
          "woodcutting" to Triple("Woodcutting Bot",     38,   50),
          "fishing"     to Triple("Fishing Bot",          40,   40),
          "combat"      to Triple("Combat Trainer",       60,   20),
        "mining"      to Triple("Mining Bot",           35,   80),
      )
      fun name(id: String)  = scripts[id]?.first ?: "Unknown"
      fun xpPer(id: String) = scripts[id]?.second ?: 0
      fun gpPer(id: String) = scripts[id]?.third ?: 0
}

/** Axe tier → (minLevel, successChanceBonus) */
object AxeInfo {
      data class AxeTier(val minLevel: Int, val speedBonus: Float)
      val tiers = mapOf(
          "bronze"  to AxeTier(1,   0.00f),
          "iron"    to AxeTier(1,   0.05f),
          "steel"   to AxeTier(6,   0.10f),
          "black"   to AxeTier(11,  0.13f),
          "mithril" to AxeTier(21,  0.16f),
          "adamant" to AxeTier(31,  0.20f),
          "rune"    to AxeTier(41,  0.25f),
          "dragon"  to AxeTier(61,  0.32f),
          "crystal" to AxeTier(71,  0.40f),
      )
      fun speedBonus(axeType: String): Float = tiers[axeType]?.speedBonus ?: 0f
}
