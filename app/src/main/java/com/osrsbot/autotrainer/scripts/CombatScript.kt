package com.osrsbot.autotrainer.scripts

  import android.accessibilityservice.AccessibilityService
  import com.osrsbot.autotrainer.antiban.AntiBanManager
  import com.osrsbot.autotrainer.detector.ObjectDetector
  import com.osrsbot.autotrainer.selector.TargetStore
  import com.osrsbot.autotrainer.utils.BotConfig
  import com.osrsbot.autotrainer.utils.GestureHelper
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
      override val name = "Combat Trainer"

      private val XP_PER_KILL = 60
      private val GP_PER_KILL = 20

      private var hp = 99
      private val EAT_HP_THRESHOLD = 45
      private var missStreak = 0

      // Priority monster keywords - checked in order, first match wins
      private val PRIORITY_MONSTERS = listOf(
          "goblin", "chicken", "cow", "imp", "rat", "spider",
          "man", "woman", "barbarian", "guard", "zombie", "skeleton",
          "rock crab", "sand crab", "moss giant", "hill giant"
      )

      private enum class State { FIND_MONSTER, IN_COMBAT, LOOTING, EATING }
      private var state = State.FIND_MONSTER

      override fun onStuck() {
          Logger.warn("[" + name + "] Stuck — resetting to FIND_MONSTER")
          state = State.FIND_MONSTER
          missStreak = 0
          detector.invalidateCache()
          super.onStuck()
      }

      override suspend fun tick() {
          when (state) {

              State.FIND_MONSTER -> {
                  hp -= Random.nextInt(1, 4)
                  if (hp <= EAT_HP_THRESHOLD) { state = State.EATING; return }

                  setAction("Looking for monster…")
                  val userTarget = TargetStore.nextTargetWhere {
                      !it.label.contains("bank", ignoreCase = true)
                  }

                  if (userTarget != null) {
                      delay(antiBan.getClickDelay())
                      val (ox, oy) = antiBan.getClickOffset()
                      if (!tap(userTarget.x + ox.toFloat(), userTarget.y + oy.toFloat())) return
                      Logger.action("Attacking saved target: " + userTarget.label)
                      state = State.IN_COMBAT
                      missStreak = 0
                      return
                  }

                  val forceRefresh = missStreak >= 3
                  val dm = service.resources.displayMetrics
                  val detected = detector.detectObjects("combat", forceRefresh)
                      .filter { it.confidence >= config.detectConfidenceMin }

                  // Try priority list first, then fall back to nearest
                  val monster = PRIORITY_MONSTERS.firstNotNullOfOrNull { kw ->
                      detector.findBestMatch(detected, kw)
                  } ?: detector.findNearest(detected, dm.widthPixels, dm.heightPixels)

                  if (monster != null) {
                      delay(antiBan.getClickDelay())
                      val (ox, oy) = antiBan.getClickOffset()
                      if (!tap(monster.bounds.exactCenterX() + ox, monster.bounds.exactCenterY() + oy)) return
                      Logger.action("Attacking: " + monster.name + " conf=" + "%.2f".format(monster.confidence))
                      state = State.IN_COMBAT
                      missStreak = 0
                  } else {
                      missStreak++
                      setAction("No monster found (miss " + missStreak + "). Set targets via overlay.")
                      delay(2_000L + Random.nextLong(0, 500))
                  }
              }

              State.IN_COMBAT -> {
                  val killMs = antiBan.getCombatKillDelay()
                  setAction("Fighting… (" + (killMs / 1000) + "s)")
                  delay(killMs)
                  completeAction(XP_PER_KILL, GP_PER_KILL)
                  Logger.ok("Kill #" + actions + " | XP: " + xpGained + " | HP: " + hp)
                  state = State.LOOTING
              }

              State.LOOTING -> {
                  setAction("Looting drops…")
                  val userTarget = TargetStore.peekCurrent()
                  if (userTarget != null) {
                      val (ox, oy) = antiBan.getClickOffset()
                      if (!tap(
                          userTarget.x + ox + Random.nextInt(-15, 15),
                          userTarget.y + oy + Random.nextInt(20, 40).toFloat()
                      )) return
                  }
                  delay(antiBan.getActionDelay() * 2)
                  detector.invalidateCache()
                  state = State.FIND_MONSTER
              }

              State.EATING -> {
                  setAction("Low HP — eating food…")
                  val dm = service.resources.displayMetrics
                  if (!tap(
                      dm.widthPixels * 0.82f + Random.nextInt(-10, 10),
                      dm.heightPixels * 0.80f + Random.nextInt(-10, 10)
                  )) return
                  delay(1_500L + Random.nextLong(0, 300))
                  hp = (hp + Random.nextInt(8, 22)).coerceAtMost(99)
                  Logger.ok("Ate food — HP ~" + hp)
                  completeAction()
                  state = State.FIND_MONSTER
              }
          }
      }

      private suspend fun tap(x: Float, y: Float): Boolean =
          GestureHelper.tap(service, x, y, antiBan.getTapDurationMs())
  }
  