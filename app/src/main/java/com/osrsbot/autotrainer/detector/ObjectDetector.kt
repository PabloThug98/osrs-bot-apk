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
)

class ObjectDetector(private val service: AccessibilityService) {

    private val TREE_KEYWORDS    = listOf("tree", "oak", "willow", "maple", "yew", "magic tree")
    private val FISH_KEYWORDS    = listOf("fishing spot", "rod", "net", "cage")
    private val MONSTER_KEYWORDS = listOf("goblin", "cow", "imp", "rat", "spider", "guard")
    private val ITEM_KEYWORDS    = listOf("chocolate bar", "knife", "chocolate dust",
                                          "logs", "fish", "bones", "coins", "gold")
    private val BANK_KEYWORDS    = listOf("bank", "chest", "booth")

    fun detectObjects(scriptId: String): List<DetectedObject> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<DetectedObject>()
        val keywords = when (scriptId) {
            "chocolate"   -> ITEM_KEYWORDS + BANK_KEYWORDS
            "woodcutting" -> TREE_KEYWORDS + BANK_KEYWORDS
            "fishing"     -> FISH_KEYWORDS + BANK_KEYWORDS
            "combat"      -> MONSTER_KEYWORDS + ITEM_KEYWORDS
            else          -> emptyList()
        }
        traverseNode(root, keywords, results)
        Logger.info("Detector: found ${results.size} object(s) for script=$scriptId")
        return results
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        results: MutableList<DetectedObject>
    ) {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val label = "$desc $text"

        keywords.forEach { kw ->
            if (kw in label) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    results.add(DetectedObject(kw.replaceFirstChar { it.uppercase() }, bounds, node))
                    Logger.action("Detected: ${kw.replaceFirstChar { it.uppercase() }} at $bounds")
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, keywords, results)
            child.recycle()
        }
    }

    /** Find nearest object to screen centre */
    fun findNearest(objects: List<DetectedObject>, screenW: Int, screenH: Int): DetectedObject? {
        val cx = screenW / 2f
        val cy = screenH / 2f
        return objects.minByOrNull { obj ->
            val dx = obj.bounds.exactCenterX() - cx
            val dy = obj.bounds.exactCenterY() - cy
            dx * dx + dy * dy
        }
    }
}
