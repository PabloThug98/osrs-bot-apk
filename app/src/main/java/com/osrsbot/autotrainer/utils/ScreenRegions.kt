package com.osrsbot.autotrainer.utils

import android.content.res.Resources
import android.graphics.Rect

/**
 * ScreenRegions
 *
 * Defines the pixel positions of every fixed OSRS Mobile UI element as fractions
 * of screen size so they work on any device resolution.
 *
 * OSRS Mobile portrait layout (verified on 1080x2340 and 1080x1920):
 *
 *   ┌──────────────────────────────┐
 *   │  Minimap (top-right circle)  │
 *   │                              │
 *   │         GAME WORLD           │
 *   │                              │
 *   │ HP ○  Prayer ○  Run ○        │
 *   ├──────────────────────────────┤
 *   │   [CHAT]  [INV]  [SKILLS]    │  ← tab bar
 *   ├──────────────────────────────┤
 *   │  INVENTORY  (4×7 grid)       │
 *   │  28 slots, each ~60×60 px    │
 *   └──────────────────────────────┘
 */
object ScreenRegions {

    // ── Minimap ───────────────────────────────────────────────────────────────
    /** Centre of minimap circle (fraction of screen width/height). */
    const val MINIMAP_CX_F = 0.905f
    const val MINIMAP_CY_F = 0.095f
    /** Radius of minimap as fraction of screen width. */
    const val MINIMAP_R_F  = 0.070f

    // ── Orbs (top-left) ───────────────────────────────────────────────────────
    const val HP_ORB_CX_F      = 0.050f;  const val HP_ORB_CY_F      = 0.580f
    const val PRAYER_ORB_CX_F  = 0.050f;  const val PRAYER_ORB_CY_F  = 0.640f
    const val RUN_ORB_CX_F     = 0.050f;  const val RUN_ORB_CY_F     = 0.700f
    /** Radius of each orb in fraction of screen width. */
    const val ORB_R_F = 0.028f

    // ── Bottom panel tabs ─────────────────────────────────────────────────────
    /** Y of the tab icon row (fraction of screen height). */
    const val TAB_ROW_Y_F = 0.730f
    /** Inventory tab X position. */
    const val TAB_INV_X_F     = 0.665f
    const val TAB_STATS_X_F   = 0.720f
    const val TAB_QUESTS_X_F  = 0.775f
    const val TAB_EQUIP_X_F   = 0.830f
    const val TAB_PRAYER_X_F  = 0.885f
    const val TAB_MAGIC_X_F   = 0.940f

    // ── Inventory grid ────────────────────────────────────────────────────────
    /** Top-left corner of the inventory grid. */
    const val INV_LEFT_F   = 0.524f
    const val INV_TOP_F    = 0.748f
    /** Bottom-right corner. */
    const val INV_RIGHT_F  = 0.978f
    const val INV_BOTTOM_F = 0.978f
    /** Number of columns and rows. */
    const val INV_COLS = 4
    const val INV_ROWS = 7

    // ── Chat area ─────────────────────────────────────────────────────────────
    const val CHAT_LEFT_F   = 0.006f
    const val CHAT_TOP_F    = 0.730f
    const val CHAT_RIGHT_F  = 0.510f
    const val CHAT_BOTTOM_F = 0.978f

    // ── Game world (excludes fixed UI elements) ────────────────────────────────
    const val GAME_LEFT_F   = 0.000f
    const val GAME_TOP_F    = 0.000f
    const val GAME_RIGHT_F  = 1.000f
    const val GAME_BOTTOM_F = 0.720f

    // ────────────────────────────────────────────────────────────────────────

    fun getDisplayMetrics(): android.util.DisplayMetrics =
        Resources.getSystem().displayMetrics

    /** Pixel rect for the full inventory panel. */
    fun inventoryRect(): Rect {
        val dm = getDisplayMetrics()
        return Rect(
            (INV_LEFT_F   * dm.widthPixels).toInt(),
            (INV_TOP_F    * dm.heightPixels).toInt(),
            (INV_RIGHT_F  * dm.widthPixels).toInt(),
            (INV_BOTTOM_F * dm.heightPixels).toInt(),
        )
    }

    /** Returns the centre pixel of inventory slot [0..27] (row-major, 0=top-left). */
    fun inventorySlotCenter(slot: Int): Pair<Float, Float> {
        require(slot in 0..27) { "Slot must be 0-27, got $slot" }
        val dm   = getDisplayMetrics()
        val col  = slot % INV_COLS
        val row  = slot / INV_COLS
        val rect = inventoryRect()
        val slotW = rect.width().toFloat()  / INV_COLS
        val slotH = rect.height().toFloat() / INV_ROWS
        val cx = rect.left + slotW * col + slotW / 2f
        val cy = rect.top  + slotH * row + slotH / 2f
        return cx to cy
    }

    /** Pixel rect for a single inventory slot. */
    fun inventorySlotRect(slot: Int): Rect {
        require(slot in 0..27) { "Slot must be 0-27, got $slot" }
        val dm   = getDisplayMetrics()
        val col  = slot % INV_COLS
        val row  = slot / INV_COLS
        val rect = inventoryRect()
        val slotW = rect.width() / INV_COLS
        val slotH = rect.height() / INV_ROWS
        return Rect(
            rect.left + col * slotW,
            rect.top  + row * slotH,
            rect.left + (col + 1) * slotW,
            rect.top  + (row + 1) * slotH,
        )
    }

    /** Pixel centre of an orb. */
    fun hpOrbCenter():     Pair<Float, Float> { val dm = getDisplayMetrics(); return HP_ORB_CX_F * dm.widthPixels to HP_ORB_CY_F * dm.heightPixels }
    fun prayerOrbCenter(): Pair<Float, Float> { val dm = getDisplayMetrics(); return PRAYER_ORB_CX_F * dm.widthPixels to PRAYER_ORB_CY_F * dm.heightPixels }
    fun runOrbCenter():    Pair<Float, Float> { val dm = getDisplayMetrics(); return RUN_ORB_CX_F * dm.widthPixels to RUN_ORB_CY_F * dm.heightPixels }

    /** Rect of the game world (where objects are rendered). */
    fun gameWorldRect(): Rect {
        val dm = getDisplayMetrics()
        return Rect(0, 0, dm.widthPixels, (GAME_BOTTOM_F * dm.heightPixels).toInt())
    }

    /** Pixel rect of the HP orb. */
    fun hpOrbRect(): Rect {
        val dm = getDisplayMetrics()
        val cx = (HP_ORB_CX_F * dm.widthPixels).toInt()
        val cy = (HP_ORB_CY_F * dm.heightPixels).toInt()
        val r  = (ORB_R_F     * dm.widthPixels).toInt()
        return Rect(cx - r, cy - r, cx + r, cy + r)
    }
}
