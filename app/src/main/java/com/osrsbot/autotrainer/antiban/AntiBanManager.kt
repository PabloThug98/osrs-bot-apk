package com.osrsbot.autotrainer.antiban

  import com.osrsbot.autotrainer.utils.AxeInfo
  import com.osrsbot.autotrainer.utils.BotConfig
  import com.osrsbot.autotrainer.utils.Logger
  import kotlin.random.Random

  /**
   * AntiBanManager v2
   *
   * Improvements:
   *  - Level-aware timing for woodcutting, fishing, and combat
   *  - Axe-type awareness reduces log-wait times for better axes
   *  - Fatigue scaling applies multiplicatively on top of level-based timing
   *  - Proportional jitter (10-15% of base) instead of fixed +/-1s
   *  - Gaussian-like click offsets cluster near target centre
   */
  class AntiBanManager(private val config: BotConfig) {

      private var actionsUntilBreak: Int = config.breakIntervalActions
      private var fatigue: Float         = 1.0f
      private var totalActions: Int      = 0

      fun onActionCompleted() {
          totalActions++
          actionsUntilBreak--
          if (config.fatigueEnabled) {
              fatigue = (1.0f + totalActions * 0.004f).coerceAtMost(2.5f)
          }
      }

      fun shouldBreak(): Boolean = config.antiBanBreaks && actionsUntilBreak <= 0

      fun resetBreakCounter() {
          val variance = Random.nextInt(-10, 10)
          actionsUntilBreak = (config.breakIntervalActions + variance).coerceAtLeast(5)
          Logger.warn("Break taken. Next break in " + actionsUntilBreak + " actions.")
      }

      /** Short pre-click pause — simulates human reaction time (200-600 ms). */
      fun getClickDelay(): Long {
          val base = if (config.humanLikeMouse) Random.nextLong(200L, 550L) else 150L
          return (base * fatigue).toLong().coerceAtLeast(100L)
      }

      fun getTapDurationMs(): Long =
          if (config.humanLikeMouse) Random.nextLong(55L, 130L) else 70L

      /** Between-action pause — short UI interaction delay (400-900 ms). */
      fun getActionDelay(): Long {
          val base   = if (config.humanLikeMouse) Random.nextLong(400L, 900L) else 300L
          val jitter = (base * 0.10f * (Random.nextFloat() * 2f - 1f)).toLong()
          return ((base + jitter) * fatigue).toLong().coerceAtLeast(200L)
      }

      /**
       * Log acquisition wait — level and axe-aware.
       *
       * OSRS success chance per tick (1.8s):
       *   chance = (level * 0.8 + axeBonus * 100) / 256
       *   expected wait = (1 / chance) * 1800 ms
       *
       * Approximate brackets:
       *   level  1-20, bronze/iron : 18-28 s
       *   level 20-40, steel/mithril: 12-20 s
       *   level 40-70, adamant/rune : 7-13 s
       *   level 70-99, dragon/crystal: 4-9 s
       */
      fun getWoodcuttingLogWaitMs(): Long {
          val lvl   = config.playerWoodcuttingLevel.coerceIn(1, 99)
          val bonus = AxeInfo.speedBonus(config.axeType)
          val successChance = ((lvl * 0.8f + bonus * 100f) / 256f).coerceIn(0.05f, 0.95f)
          val expectedChops = 1f / successChance
          val baseMs        = (expectedChops * 1800f).toLong().coerceIn(4_000L, 30_000L)
          val jitter        = (baseMs * 0.15f * (Random.nextFloat() * 2f - 1f)).toLong()
          return ((baseMs + jitter) * fatigue).toLong().coerceAtLeast(3_000L)
      }

      fun getWoodcuttingChopDelay(): Long = getWoodcuttingLogWaitMs()

      /**
       * Fishing catch wait — level-aware.
       *   level  1-20: 10-15 s
       *   level 20-50:  7-11 s
       *   level 50-70: 5.5-9 s
       *   level 70-99: 4.5-7.5 s
       */
      fun getFishingWaitDelay(): Long {
          val lvl = config.playerFishingLevel.coerceIn(1, 99)
          val (minMs, maxMs) = when {
              lvl < 20  -> 10_000L to 15_000L
              lvl < 50  ->  7_000L to 11_000L
              lvl < 70  ->  5_500L to  9_000L
              else      ->  4_500L to  7_500L
          }
          val base   = Random.nextLong(minMs, maxMs)
          val jitter = (base * 0.10f * (Random.nextFloat() * 2f - 1f)).toLong()
          return ((base + jitter) * fatigue).toLong().coerceAtLeast(3_000L)
      }

      /**
       * Combat kill wait — level-aware.
       *   level   3-30: 10-18 s
       *   level  30-60:  7-13 s
       *   level  60-90:  5-10 s
       *   level  90-126: 4-8 s
       */
      fun getCombatKillDelay(): Long {
          val lvl = config.playerCombatLevel.coerceIn(3, 126)
          val (minMs, maxMs) = when {
              lvl < 30  -> 10_000L to 18_000L
              lvl < 60  ->  7_000L to 13_000L
              lvl < 90  ->  5_000L to 10_000L
              else      ->  4_000L to  8_000L
          }
          val base   = Random.nextLong(minMs, maxMs)
          val jitter = (base * 0.12f * (Random.nextFloat() * 2f - 1f)).toLong()
          return ((base + jitter) * fatigue).toLong().coerceAtLeast(3_000L)
      }

      /** Banking walk + deposit + walk back (~10-18 s). */
      fun getBankingDelay(): Long = Random.nextLong(10_000L, 18_000L)

      /**
       * Gaussian-like click offset — clusters near target centre,
       * with occasional natural-looking outliers.
       */
      fun getClickOffset(): Pair<Int, Int> {
          if (!config.humanLikeMouse) return 0 to 0
          fun gaussInt(range: Int): Int =
              ((Random.nextInt(-range, range) +
                Random.nextInt(-range, range) +
                Random.nextInt(-range, range)) / 3)
          return gaussInt(12) to gaussInt(12)
      }

      fun checkAutoStop(startTimeMs: Long, actions: Int): StopReason? {
          if (config.stopAfterTime) {
              val elapsedMin = (System.currentTimeMillis() - startTimeMs) / 60_000
              if (elapsedMin >= config.stopAfterMinutes) return StopReason.TIME_LIMIT
          }
          if (config.stopAfterActions && actions >= config.stopAfterActionCount) {
              return StopReason.ACTION_LIMIT
          }
          return null
      }

      fun getFatigue(): Float         = fatigue
      fun getActionsUntilBreak(): Int = actionsUntilBreak
  }

  enum class StopReason { TIME_LIMIT, ACTION_LIMIT, PLAYER_NEARBY, MANUAL }
  