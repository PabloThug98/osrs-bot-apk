package com.osrsbot.autotrainer.detector

import android.graphics.Bitmap
import android.graphics.Color
import com.osrsbot.autotrainer.capture.ScreenCaptureManager
import com.osrsbot.autotrainer.utils.Logger
import com.osrsbot.autotrainer.utils.ScreenRegions

/**
 * InventoryDetector
 *
 * Reads inventory state directly from screen pixels via ScreenCaptureManager.
 * Scans each of the 28 inventory slots for non-background pixel density to
 * determine which slots are occupied.
 *
 * OSRS inventory background colour: very dark brown (~RGB 60, 50, 40 in HSV ~25°, 0.33, 0.24).
 * A slot is considered OCCUPIED when > OCCUPIED_PIXEL_RATIO of its sampled pixels
 * differ significantly from the background colour.
 *
 * Usage:
 *   val inv = InventoryDetector(screenCapture)
 *   if (inv.isFull()) { ... bank ... }
 *   val count = inv.countOccupied()
 */
class InventoryDetector(private val capture: ScreenCaptureManager) {

    companion object {
        /** Empty-slot background: dark brown (OSRS bank/inventory background). */
        private val EMPTY_HSV_RANGE = Triple(
            15f..40f,   // Hue
            0.15f..0.55f, // Sat
            0.12f..0.35f, // Val
        )
        /** Fraction of sampled pixels that must be non-background for a slot to count as occupied. */
        private const val OCCUPIED_PIXEL_RATIO = 0.30f
        /** Sample every Nth pixel in a slot for speed. */
        private const val SAMPLE_STEP = 4
        /** Number of inventory slots. */
        const val SLOT_COUNT = 28
        /** Cached result TTL. */
        private const val CACHE_TTL_MS = 600L
    }

    private var cachedState: BooleanArray? = null
    private var cacheTime: Long = 0L

    /**
     * Returns array of 28 booleans — true = slot occupied, false = empty.
     * Result is cached for CACHE_TTL_MS ms.
     */
    fun scanSlots(forceRefresh: Boolean = false): BooleanArray {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedState != null && (now - cacheTime) < CACHE_TTL_MS) {
            return cachedState!!
        }

        val bmp = capture.latestBitmap
        if (bmp == null) {
            Logger.warn("InventoryDetector: no frame available yet")
            return BooleanArray(SLOT_COUNT) // assume empty
        }

        val result = BooleanArray(SLOT_COUNT)
        val hsv = FloatArray(3)

        for (slot in 0 until SLOT_COUNT) {
            val rect = ScreenRegions.inventorySlotRect(slot)
            // Clamp to bitmap bounds
            val left   = rect.left.coerceIn(0, bmp.width  - 1)
            val top    = rect.top.coerceIn(0,  bmp.height - 1)
            val right  = rect.right.coerceIn(0,  bmp.width  - 1)
            val bottom = rect.bottom.coerceIn(0, bmp.height - 1)
            if (right <= left || bottom <= top) continue

            var total = 0
            var occupied = 0

            for (y in top until bottom step SAMPLE_STEP) {
                for (x in left until right step SAMPLE_STEP) {
                    total++
                    Color.colorToHSV(bmp.getPixel(x, y), hsv)
                    if (!isEmptyBackground(hsv)) occupied++
                }
            }
            result[slot] = total > 0 && (occupied.toFloat() / total) > OCCUPIED_PIXEL_RATIO
        }

        cachedState = result
        cacheTime = System.currentTimeMillis()

        val count = result.count { it }
        Logger.info("InventoryDetector: $count/28 slots occupied")
        return result
    }

    /** Returns true when all 28 slots are occupied. */
    fun isFull(forceRefresh: Boolean = false): Boolean =
        scanSlots(forceRefresh).all { it }

    /** Returns the number of occupied inventory slots. */
    fun countOccupied(forceRefresh: Boolean = false): Int =
        scanSlots(forceRefresh).count { it }

    /** Returns the index of the first empty slot, or -1 if inventory is full. */
    fun firstEmptySlot(forceRefresh: Boolean = false): Int =
        scanSlots(forceRefresh).indexOfFirst { !it }

    /**
     * Finds the first occupied slot whose position matches the given food colour.
     * Useful for auto-eat: scan for a red/orange food item in inventory.
     *
     * @param hsvRange hue range to look for (e.g. 0f..30f for red food)
     * @return slot index or -1 if not found
     */
    fun findSlotByColor(bmp: Bitmap, hMin: Float, hMax: Float): Int {
        val hsv = FloatArray(3)
        for (slot in 0 until SLOT_COUNT) {
            val (cx, cy) = ScreenRegions.inventorySlotCenter(slot)
            val px = cx.toInt().coerceIn(0, bmp.width - 1)
            val py = cy.toInt().coerceIn(0, bmp.height - 1)
            Color.colorToHSV(bmp.getPixel(px, py), hsv)
            if (hsv[0] in hMin..hMax && hsv[1] > 0.3f && hsv[2] > 0.3f) return slot
        }
        return -1
    }

    fun invalidateCache() { cacheTime = 0L; cachedState = null }

    private fun isEmptyBackground(hsv: FloatArray): Boolean {
        val (hRange, sRange, vRange) = EMPTY_HSV_RANGE
        return hsv[0] in hRange && hsv[1] in sRange && hsv[2] in vRange
    }
}
