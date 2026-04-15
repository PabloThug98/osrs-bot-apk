package com.osrsbot.autotrainer.banking

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.selector.TargetStore
import com.osrsbot.autotrainer.utils.GestureHelper
import com.osrsbot.autotrainer.utils.Logger
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * BankInteractor — performs real screen taps to deposit items in the OSRS Mobile bank.
 *
 * ── Bank UI layout (OSRS Mobile, portrait, proportional to screen size) ──────
 *
 *   After the bank interface opens, the layout is consistent across phones:
 *
 *   ┌─────────────────────────────────────────┐
 *   │  [Tab 1] [Tab 2] …         [X close]   │  ≈ 22 % of screen height
 *   │─────────────────────────────────────────│
 *   │           Bank item grid                │
 *   │                                         │
 *   │─────────────────────────────────────────│
 *   │  [Deposit inventory] [Deposit worn]     │  ≈ 82 % of screen height
 *   └─────────────────────────────────────────┘
 *
 *   "Deposit inventory" button: ~50 % x, ~82 % y
 *   Close (X) button:           ~94 % x, ~22 % y
 *
 * ── Bank target ───────────────────────────────────────────────────────────────
 *   The interactor looks for a saved target whose label contains "bank"
 *   (case-insensitive).  If none is saved it falls back to a best-guess position
 *   in the centre of the game screen and logs a warning.
 *
 *   Tip: use the 🎯 target selector on the bank booth / chest and name it "Bank".
 */
class BankInteractor(
    private val service: AccessibilityService,
    private val detector: ObjectDetector,
) {

    private val dm get() = service.resources.displayMetrics

    // ── OSRS Mobile bank UI proportions ──────────────────────────────────────
    private val depositX get() = dm.widthPixels  * 0.50f
    private val depositY get() = dm.heightPixels * 0.82f
    private val closeX   get() = dm.widthPixels  * 0.94f
    private val closeY   get() = dm.heightPixels * 0.22f

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Runs the full deposit sequence:
     *   1. Tap bank booth/chest
     *   2. Wait for bank interface to open
     *   3. Tap "Deposit Inventory"
     *   4. Wait for deposit animation
     *   5. Close the bank
     *   6. Wait for close animation
     *
     * Returns true on success.  Returns false only if there is no bank target
     * AND the interactor is configured to fail rather than guess.
     *
     * Total expected duration: ~5–9 seconds.
     */
    suspend fun depositInventory(): Boolean {
        if (!openBank()) return false

        // 2. Wait for bank to open (walk-click animation + server response)
        val openWait = Random.nextLong(2_200L, 3_500L)
        Logger.info("Bank: waiting ${openWait}ms for interface to open…")
        delay(openWait)

        // 3. Tap "Deposit Inventory"
        val depositedByText = tapFirstNodeContaining(
            listOf("deposit inventory", "deposit carried", "deposit all"),
        )
        if (!depositedByText) {
            if (!tap(depositX + jitter(), depositY + jitter())) return false
            Logger.ok("Bank: tapped Deposit Inventory fallback @ (${depositX.toInt()}, ${depositY.toInt()})")
        }

        // 4. Wait for deposit animation
        delay(Random.nextLong(900L, 1_500L))

        // 5. Close the bank interface
        val closedByText = tapFirstNodeContaining(listOf("close", "exit"))
        if (!closedByText) {
            if (!tap(closeX + jitter(), closeY + jitter())) return false
            Logger.ok("Bank: closed interface fallback @ (${closeX.toInt()}, ${closeY.toInt()})")
        }

        // 6. Wait for close animation
        delay(Random.nextLong(700L, 1_200L))

        return true
    }

    /**
     * Convenience: open the bank without depositing — useful if you need
     * to withdraw something (future scripts can extend this).
     */
    suspend fun openBank(): Boolean {
        val detectedBank = detector.detectObjects("woodcutting", true)
            .filter { it.name.contains("bank", ignoreCase = true) || it.name.contains("chest", ignoreCase = true) }
            .maxByOrNull { it.confidence }
        if (detectedBank != null) {
            Logger.action("Bank: accessibility target ${detectedBank.name}")
            if (!tap(detectedBank.bounds.exactCenterX() + jitter(), detectedBank.bounds.exactCenterY() + jitter())) return false
            delay(Random.nextLong(2_200L, 3_500L))
            return true
        }

        val savedBank = TargetStore.getAll()
            .firstOrNull { it.label.contains("bank", ignoreCase = true) }
        if (savedBank != null) {
            Logger.action("Bank: calibrated target @ (${savedBank.x.toInt()}, ${savedBank.y.toInt()})")
            if (!tap(savedBank.x + jitter(), savedBank.y + jitter())) return false
            delay(Random.nextLong(2_200L, 3_500L))
            return true
        }

        Logger.warn("Bank: no reliable bank found. Refusing to tap random fallback.")
        return false
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun jitter(range: Float = 12f) = Random.nextFloat() * range * 2f - range

    private suspend fun tap(x: Float, y: Float): Boolean =
        GestureHelper.tap(service, x, y, Random.nextLong(65L, 120L))

    private suspend fun tapFirstNodeContaining(keywords: List<String>): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val match = findNode(root, keywords.map { it.lowercase() })
        if (match != null) {
            val bounds = Rect()
            match.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                Logger.action("Bank UI: tapping '${keywords.first()}' by text")
                return tap(bounds.exactCenterX() + jitter(6f), bounds.exactCenterY() + jitter(6f))
            }
        }
        return false
    }

    private fun findNode(node: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        val label = listOf(
            node.text?.toString().orEmpty(),
            node.contentDescription?.toString().orEmpty(),
        ).joinToString(" ").lowercase()

        if (node.isVisibleToUser && keywords.any { label.contains(it) }) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNode(child, keywords)
            if (found != null) return found
        }
        return null
    }
}
