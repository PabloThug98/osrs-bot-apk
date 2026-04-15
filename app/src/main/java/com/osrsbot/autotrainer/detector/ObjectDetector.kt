package com.osrsbot.autotrainer.detector

  import android.accessibilityservice.AccessibilityService
  import android.graphics.Rect
  import android.view.accessibility.AccessibilityNodeInfo
  import com.osrsbot.autotrainer.utils.Logger

  data class DetectedObject(
      val name: String,
      val bounds: Rect,
      val node: AccessibilityNodeInfo?,
      val confidence: Float = 1.0f,
      val isClickable: Boolean = false,
  )

  /**
   * ObjectDetector v2
   *
   * Improvements over v1:
   *  - Greatly expanded keyword lists covering far more OSRS content
   *  - Exact-match vs partial-match confidence scoring (1.0 vs 0.65)
   *  - Clickable-node bonus: nodes with click actions score +0.10
   *  - Deduplication: overlapping rects (>60% overlap) merged, keeping highest confidence
   *  - Result caching: reuses last scan within CACHE_TTL_MS
   *  - Minimum bounds guard: ignores 0-size or tiny rects
   *  - Sorted output: highest confidence first
   */
  class ObjectDetector(private val service: AccessibilityService) {

      companion object {
          private const val CACHE_TTL_MS       = 800L
          private const val OVERLAP_THRESHOLD  = 0.60f
          private const val EXACT_CONFIDENCE   = 1.00f
          private const val PARTIAL_CONFIDENCE = 0.65f
          private const val CLICKABLE_BONUS    = 0.10f
      }

      // Each Pair: keyword to isExact (true = whole-label match, false = contains)
      private val TREE_KEYWORDS = listOf(
          "tree" to false,
          "oak" to true, "oak tree" to true,
          "willow" to true, "willow tree" to true,
          "maple" to true, "maple tree" to true,
          "yew" to true, "yew tree" to true,
          "magic tree" to true,
          "teak" to true, "mahogany" to true,
          "pine tree" to false, "dead tree" to false,
          "achey tree" to true, "jungle tree" to false,
          "hollow tree" to true, "dramen tree" to true,
          "evergreen" to false,
      )

      private val FISH_KEYWORDS = listOf(
          "fishing spot" to true,
          "rod fishing spot" to true, "net fishing spot" to true,
          "cage/harpoon" to true, "bait fishing spot" to true,
          "lure fishing spot" to true,
          "fly fishing spot" to true,
          "karambwan vessel" to true,
          "rod" to false, "net" to false, "cage" to false, "harpoon" to false,
          "dark fishing bait" to false,
      )

      private val MONSTER_KEYWORDS = listOf(
          "goblin" to true, "cow" to true, "imp" to true,
          "rat" to true, "spider" to true, "guard" to true,
          "man" to true, "woman" to true, "chicken" to true,
          "giant rat" to true, "giant spider" to true,
          "barbarian" to true, "dark wizard" to true,
          "moss giant" to true, "hill giant" to true,
          "lesser demon" to true, "greater demon" to true,
          "black knight" to true, "white knight" to true,
          "mugger" to true, "thug" to true,
          "wolf" to true, "bear" to true,
          "zombie" to true, "skeleton" to true,
          "ghost" to true, "banshee" to true,
          "cave bug" to true, "cave crawler" to true,
          "rock crab" to true, "sand crab" to true,
          "slayer monster" to false,
      )

      private val ITEM_KEYWORDS = listOf(
          "chocolate bar" to true, "knife" to true,
          "chocolate dust" to true,
          "logs" to false, "raw fish" to false, "fish" to false,
          "bones" to false, "big bones" to true,
          "coins" to true, "gold" to false,
          "herb" to false, "ore" to false,
          "gem" to false, "seed" to false,
          "arrow" to false, "rune" to false,
          "bar" to false, "hide" to false,
      )

      private val BANK_KEYWORDS = listOf(
          "bank" to false, "bank chest" to true,
          "bank booth" to true, "bank counter" to true,
          "deposit box" to true, "chest" to false,
      )

      private val ORE_KEYWORDS = listOf(
          "rocks" to false, "ore rocks" to false,
          "copper rocks" to true, "tin rocks" to true,
          "iron rocks" to true, "coal rocks" to true,
          "gold rocks" to true, "mithril rocks" to true,
          "adamantite rocks" to true, "runite rocks" to true,
          "gem rocks" to true, "clay rocks" to true,
          "silver rocks" to true,
      )

      // Cache fields
      private var cachedResults: List<DetectedObject> = emptyList()
      private var cacheScriptId: String = ""
      private var cacheTimestamp: Long  = 0L

      /** Returns detected OSRS objects. Results cached for CACHE_TTL_MS. */
      fun detectObjects(scriptId: String, forceRefresh: Boolean = false): List<DetectedObject> {
          val now = System.currentTimeMillis()
          if (!forceRefresh
              && scriptId == cacheScriptId
              && (now - cacheTimestamp) < CACHE_TTL_MS) {
              return cachedResults
          }

          val root = service.rootInActiveWindow ?: return emptyList()
          val pairs = keywordsFor(scriptId)
          val raw   = mutableListOf<DetectedObject>()
          traverseNode(root, pairs, raw)

          val deduped = deduplicate(raw)
          val sorted  = deduped.sortedByDescending { it.confidence }

          cachedResults  = sorted
          cacheScriptId  = scriptId
          cacheTimestamp = System.currentTimeMillis()

          Logger.info("Detector: " + sorted.size + " unique object(s) for script=" + scriptId +
                      " (raw=" + raw.size + ", deduped=" + deduped.size + ")")
          return sorted
      }

      /** Find nearest object to screen centre, weighted by confidence. */
      fun findNearest(objects: List<DetectedObject>, screenW: Int, screenH: Int): DetectedObject? {
          val cx = screenW / 2f
          val cy = screenH / 2f
          return objects.minByOrNull { obj ->
              val dx = obj.bounds.exactCenterX() - cx
              val dy = obj.bounds.exactCenterY() - cy
              val dist = dx * dx + dy * dy
              dist / (obj.confidence + 0.1f)
          }
      }

      /** Find best match for a specific keyword among detected objects. */
      fun findBestMatch(objects: List<DetectedObject>, keyword: String): DetectedObject? =
          objects.filter { it.name.lowercase().contains(keyword.lowercase()) }
                 .maxByOrNull { it.confidence }

      /** Force next detectObjects() call to do a fresh scan. */
      fun invalidateCache() { cacheTimestamp = 0L }

      private fun keywordsFor(scriptId: String): List<Pair<String, Boolean>> = when (scriptId) {
          "chocolate"   -> ITEM_KEYWORDS + BANK_KEYWORDS
          "woodcutting" -> TREE_KEYWORDS + BANK_KEYWORDS
          "fishing"     -> FISH_KEYWORDS + BANK_KEYWORDS
          "combat"      -> MONSTER_KEYWORDS + ITEM_KEYWORDS
          "mining"      -> ORE_KEYWORDS + BANK_KEYWORDS
          else          -> emptyList()
      }

      private fun traverseNode(
          node: AccessibilityNodeInfo,
          keywords: List<Pair<String, Boolean>>,
          results: MutableList<DetectedObject>,
      ) {
          val desc  = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
          val text  = node.text?.toString()?.lowercase()?.trim() ?: ""
          val label = if (desc.isNotEmpty()) desc else text

          if (label.isNotEmpty()) {
              val bounds = Rect()
              node.getBoundsInScreen(bounds)
              if (!bounds.isEmpty && bounds.width() >= 4 && bounds.height() >= 4) {
                  val isClickable = node.isClickable || node.isLongClickable
                  keywords.forEach { (kw, exact) ->
                      val matches = if (exact)
                          label == kw || label.startsWith(kw + " ") || label.endsWith(" " + kw)
                      else
                          label.contains(kw)
                      if (matches) {
                          val base = if (exact) EXACT_CONFIDENCE else PARTIAL_CONFIDENCE
                          val conf = (base + if (isClickable) CLICKABLE_BONUS else 0f).coerceAtMost(1.0f)
                          val name = kw.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                          results.add(DetectedObject(name, Rect(bounds), node, conf, isClickable))
                          Logger.action("Detected: " + name + " conf=" + "%.2f".format(conf) + " at " + bounds)
                      }
                  }
              }
          }

          for (i in 0 until node.childCount) {
              val child = node.getChild(i) ?: continue
              traverseNode(child, keywords, results)
              child.recycle()
          }
      }

      private fun deduplicate(items: List<DetectedObject>): List<DetectedObject> {
          val result  = mutableListOf<DetectedObject>()
          val visited = BooleanArray(items.size)
          for (i in items.indices) {
              if (visited[i]) continue
              var best = items[i]
              for (j in i + 1 until items.size) {
                  if (visited[j]) continue
                  if (overlapRatio(best.bounds, items[j].bounds) > OVERLAP_THRESHOLD) {
                      visited[j] = true
                      if (items[j].confidence > best.confidence) best = items[j]
                  }
              }
              result.add(best)
          }
          return result
      }

      private fun overlapRatio(a: Rect, b: Rect): Float {
          val inter = Rect()
          if (!inter.setIntersect(a, b)) return 0f
          val interArea   = inter.width().toLong() * inter.height()
          val smallerArea = minOf(a.width().toLong() * a.height(), b.width().toLong() * b.height())
          if (smallerArea == 0L) return 0f
          return interArea.toFloat() / smallerArea.toFloat()
      }
  }
  