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
      override val name = "Fishing Bot"

      private val XP_PER_FISH = 40
      private val GP_PER_FISH = 40
      private var fishInInventory = 0
      private var missStreak = 0

      private enum class State { FIND_SPOT, FISHING, BANKING }
      private var state = State.FIND_SPOT

      override fun onStuck() {
          Logger.warn("[" + name + "] Stuck — resetting to FIND_SPOT")
          state = State.FIND_SPOT
          fishInInventory = 0
          missStreak = 0
          detector.invalidateCache()
          super.onStuck()
      }

      override suspend fun tick() {
          when (state) {
              State.FIND_SPOT -> {
                  if (fishInInventory >= 27) { state = State.BANKING; return }
                  setAction("Looking for fishing spot…")

                  val userTarget = TargetStore.nextTarget()
                  if (userTarget != null) {
                      delay(antiBan.getClickDelay())
                      val (ox, oy) = antiBan.getClickOffset()
                      tap(userTarget.x + ox.toFloat(), userTarget.y + oy.toFloat())
                      Logger.action("Clicking saved spot: " + userTarget.label)
                      state = State.FISHING
                      missStreak = 0
                      return
                  }

                  // Use forceRefresh after 3 misses so we don't keep re-clicking stale results
                  val forceRefresh = missStreak >= 3
                  val dm = service.resources.displayMetrics
                  val detected = detector.detectObjects("fishing", forceRefresh)
                      .filter { it.confidence >= config.detectConfidenceMin }

                  val spot = detector.findNearest(detected, dm.widthPixels, dm.heightPixels)
                  if (spot != null) {
                      delay(antiBan.getClickDelay())
                      val (ox, oy) = antiBan.getClickOffset()
                      tap(spot.bounds.exactCenterX() + ox, spot.bounds.exactCenterY() + oy)
                      Logger.action("Detected spot: " + spot.name + " conf=" + "%.2f".format(spot.confidence))
                      state = State.FISHING
                      missStreak = 0
                  } else {
                      missStreak++
                      setAction("No spot found (miss " + missStreak + "). Tap target to set spot.")
                      delay(2_000L + Random.nextLong(0, 500))
                  }
              }

              State.FISHING -> {
                  val waitMs = antiBan.getFishingWaitDelay()
                  setAction("Fishing… (" + (waitMs / 1000) + "s)")
                  delay(waitMs)

                  fishInInventory++
                  completeAction(XP_PER_FISH, GP_PER_FISH)
                  Logger.ok("Fish #" + actions + " | Inv: " + fishInInventory + "/27 | XP: " + xpGained)
                  delay(antiBan.getActionDelay())
                  state = if (fishInInventory >= 27) State.BANKING else State.FIND_SPOT
                  detector.invalidateCache()
              }

              State.BANKING -> {
                  setAction("Inventory full — banking fish…")
                  delay(antiBan.getBankingDelay())
                  fishInInventory = 0
                  Logger.ok("Banked. Total catches: " + actions)
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
  