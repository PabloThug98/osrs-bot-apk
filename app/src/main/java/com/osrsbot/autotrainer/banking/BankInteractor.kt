package com.osrsbot.autotrainer.banking

import android.accessibilityservice.AccessibilityService
import com.osrsbot.autotrainer.capture.ScreenCaptureManager
import com.osrsbot.autotrainer.detector.GameStateDetector
import com.osrsbot.autotrainer.detector.ObjectDetector
import com.osrsbot.autotrainer.selector.TargetStore
import com.osrsbot.autotrainer.utils.GestureHelper
import com.osrsbot.autotrainer.utils.Logger
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * BankInteractor v2
 *
 * Upgrades over v1:
 *  - Uses GameStateDetector to CONFIRM the bank is open before tapping Deposit.
 *    v1 would blindly tap the deposit coords after a fixed delay and often missed.
 *  - Retries opening the bank up to MAX_OPEN_ATTEMPTS times if it doesn't open.
 *  - Detects bank already open (no need to tap booth again).
 *  - All taps use GestureHelper.tapHuman() for realism.
 *  - Dedicated closeBank() polls GameStateDetector to confirm bank closed.
 */
class BankInteractor(
    private val service: AccessibilityService,
    private val detector: ObjectDetector,
    captureManager: ScreenCaptureManager? = null,
) {
    private val dm          get() = service.resources.displayMetrics
    private val stateDetect = captureManager?.let { GameStateDetector(it) }

    private val depositX get() = dm.widthPixels  * 0.50f
    private val depositY get() = dm.heightPixels * 0.82f
    private val closeX   get() = dm.widthPixels  * 0.94f
    private val closeY   get() = dm.heightPixels * 0.22f

    companion object {
        private const val MAX_OPEN_ATTEMPTS = 4
        private const val OPEN_POLL_INTERVAL = 600L
        private const val OPEN_MAX_WAIT_MS   = 5_000L
    }

    /**
     * Full deposit sequence with state-aware polling:
     *  1. Open bank (retry up to MAX_OPEN_ATTEMPTS).
     *  2. Poll GameStateDetector until BANK_OPEN confirmed.
     *  3. Tap Deposit Inventory.
     *  4. Wait for deposit.
     *  5. Close bank.
     *  6. Poll until bank closed.
     *
     * Returns true on success.
     */
    suspend fun depositInventory(): Boolean {
        // Skip opening if bank is already open
        val alreadyOpen = stateDetect?.isBankOpen() ?: false
        if (!alreadyOpen && !openBank()) return false

        // Wait for bank interface with polling (up to OPEN_MAX_WAIT_MS)
        val opened = waitForBankOpen()
        if (!opened) {
            Logger.warn("BankInteractor: bank did not open after ${OPEN_MAX_WAIT_MS}ms — giving up")
            return false
        }
        Logger.ok("BankInteractor: bank open ✓")

        // Deposit inventory
        delay(Random.nextLong(350L, 650L))
        val depositedByText = tapNodeContaining(listOf("deposit inventory", "deposit carried", "deposit all"))
        if (!depositedByText) {
            Logger.info("BankInteractor: falling back to pixel deposit tap")
            GestureHelper.tapHuman(service, depositX + Random.nextInt(-8, 8), depositY + Random.nextInt(-8, 8))
        }
        delay(Random.nextLong(800L, 1_400L))

        // Close bank
        closeBank()
        return true
    }

    /** Opens the bank by tapping the booth. Retries on failure. */
    private suspend fun openBank(): Boolean {
        for (attempt in 1..MAX_OPEN_ATTEMPTS) {
            val bankTarget = TargetStore.nextTargetWhere { it.label.contains("bank", ignoreCase = true) }
            if (bankTarget != null) {
                Logger.info("BankInteractor: tapping saved bank target (attempt $attempt)")
                delay(antiBanDelay())
                GestureHelper.tapHuman(service, bankTarget.x, bankTarget.y)
            } else {
                val detected = detector.detectObjects("woodcutting", true)
                    .filter { it.name.contains("bank", ignoreCase = true) }
                    .maxByOrNull { it.confidence }
                if (detected != null) {
                    Logger.info("BankInteractor: tapping detected bank @ (${detected.bounds.centerX()},${detected.bounds.centerY()}) (attempt $attempt)")
                    delay(antiBanDelay())
                    GestureHelper.tapHuman(service, detected.bounds.exactCenterX(), detected.bounds.exactCenterY())
                } else {
                    Logger.warn("BankInteractor: no bank target — using accessibility node")
                    val nodeFound = tapNodeContaining(listOf("bank booth", "bank chest", "bank counter", "bank"))
                    if (!nodeFound) {
                        Logger.warn("BankInteractor: no bank found at all (attempt $attempt)")
                        delay(1_500L); continue
                    }
                }
            }
            // Short wait then check if open
            delay(OPEN_POLL_INTERVAL)
            if (stateDetect?.isBankOpen() == true) return true
            Logger.info("BankInteractor: bank not open yet, retrying...")
            delay(800L)
        }
        return stateDetect?.isBankOpen() ?: true // if no detector, assume success
    }

    /** Polls GameStateDetector until bank is open or timeout. */
    private suspend fun waitForBankOpen(): Boolean {
        if (stateDetect == null) {
            delay(Random.nextLong(2_200L, 3_500L))
            return true
        }
        val deadline = System.currentTimeMillis() + OPEN_MAX_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            stateDetect.invalidateCache()
            if (stateDetect.isBankOpen()) return true
            delay(OPEN_POLL_INTERVAL)
        }
        return false
    }

    /** Closes the bank and polls until it's gone from screen. */
    private suspend fun closeBank() {
        Logger.info("BankInteractor: closing bank")
        GestureHelper.tapHuman(service, closeX + Random.nextInt(-6, 6), closeY + Random.nextInt(-6, 6))
        delay(500L)
        if (stateDetect == null) { delay(700L); return }
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            stateDetect.invalidateCache()
            if (!stateDetect.isBankOpen()) {
                Logger.ok("BankInteractor: bank closed ✓")
                return
            }
            delay(400L)
        }
        Logger.warn("BankInteractor: bank still showing after close tap")
    }

    private suspend fun tapNodeContaining(keywords: List<String>): Boolean {
        val root = service.rootInActiveWindow ?: return false
        fun search(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            val text = listOf(
                node.contentDescription?.toString(),
                node.text?.toString()
            ).filterNotNull().joinToString(" ").lowercase()
            if (keywords.any { text.contains(it) }) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    kotlinx.coroutines.runBlocking {
                        delay(antiBanDelay())
                        GestureHelper.tapHuman(service, bounds.exactCenterX(), bounds.exactCenterY())
                    }
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (search(child)) { child.recycle(); return true }
                child.recycle()
            }
            return false
        }
        return search(root)
    }

    private fun antiBanDelay(): Long = Random.nextLong(180L, 420L)
}
