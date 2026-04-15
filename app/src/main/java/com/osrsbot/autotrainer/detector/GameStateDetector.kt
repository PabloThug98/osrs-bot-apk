package com.osrsbot.autotrainer.detector

import android.graphics.Color
import com.osrsbot.autotrainer.capture.ScreenCaptureManager
import com.osrsbot.autotrainer.utils.Logger
import com.osrsbot.autotrainer.utils.ScreenRegions

/**
 * GameStateDetector
 *
 * Reads the screen to determine the current OSRS game state without clicking anything.
 * The bot should check this every tick and pause if the game is not in a playable state.
 *
 * Detected states:
 *   PLAYING         — Normal gameplay, scripts can run.
 *   BANK_OPEN       — Bank interface is visible (gold/tan header in top-third of screen).
 *   DIALOGUE_OPEN   — NPC/chat dialogue overlay is visible (dark modal with text).
 *   LEVEL_UP        — Level-up popup is on screen (golden/white flash).
 *   LOGIN_SCREEN    — Login or world-select screen (blue/dark background, no game world).
 *   LOADING         — Map loading or hourglass indicator (dark screen with spinner).
 *   UNKNOWN         — Cannot determine state (e.g. capture not yet started).
 */
class GameStateDetector(private val capture: ScreenCaptureManager) {

    enum class GameState {
        PLAYING, BANK_OPEN, DIALOGUE_OPEN, LEVEL_UP, LOGIN_SCREEN, LOADING, UNKNOWN
    }

    companion object {
        private const val CACHE_TTL_MS = 700L
        private const val SAMPLE_STEP  = 6

        // Bank header: golden/tan  H:30-55, S:0.4-0.85, V:0.45-0.90
        private val BANK_H = 30f..55f;   private val BANK_S = 0.40f..0.85f; private val BANK_V = 0.45f..0.90f

        // Dialogue: dark grey overlay  H:any, S:0-0.2, V:0.05-0.30
        private val DIAL_S = 0.00f..0.20f; private val DIAL_V = 0.05f..0.30f

        // Level-up popup: bright gold  H:45-65, S:0.7-1.0, V:0.7-1.0
        private val LVL_H = 45f..65f;    private val LVL_S = 0.70f..1.00f; private val LVL_V = 0.70f..1.00f

        // Login screen: deep blue  H:200-250, S:0.4-0.9, V:0.1-0.55
        private val LOGIN_H = 200f..250f; private val LOGIN_S = 0.40f..0.90f; private val LOGIN_V = 0.10f..0.55f

        // Loading: very dark  H:any, S:any, V:0-0.12
        private val LOAD_V = 0.00f..0.12f
    }

    private var cachedState: GameState = GameState.UNKNOWN
    private var cacheTime: Long = 0L

    /** Returns the current game state, cached for CACHE_TTL_MS. */
    fun getState(forceRefresh: Boolean = false): GameState {
        val now = System.currentTimeMillis()
        if (!forceRefresh && (now - cacheTime) < CACHE_TTL_MS) return cachedState

        val bmp = capture.latestBitmap ?: return GameState.UNKNOWN.also { cachedState = it }
        val w   = bmp.width; val h = bmp.height
        val hsv = FloatArray(3)

        // Sample specific screen regions for each state
        var bankHits = 0; var dialogHits = 0; var levelHits = 0
        var loginHits = 0; var loadHits = 0; var total = 0

        // Bank header region: top 25% of screen, full width
        val bankRegionBottom = (h * 0.25f).toInt()
        // Dialogue region: middle 60% of screen (modal overlay)
        val dialTop = (h * 0.20f).toInt(); val dialBottom = (h * 0.80f).toInt()
        // Level-up region: centre of screen
        val lvlLeft = (w * 0.25f).toInt(); val lvlRight = (w * 0.75f).toInt()
        val lvlTop  = (h * 0.25f).toInt(); val lvlBottom = (h * 0.55f).toInt()

        for (y in 0 until bankRegionBottom step SAMPLE_STEP) {
            for (x in 0 until w step SAMPLE_STEP) {
                Color.colorToHSV(bmp.getPixel(x.coerceIn(0,w-1), y.coerceIn(0,h-1)), hsv)
                total++
                if (hsv[0] in BANK_H && hsv[1] in BANK_S && hsv[2] in BANK_V) bankHits++
                if (hsv[2] in LOAD_V) loadHits++
            }
        }
        for (y in dialTop until dialBottom step SAMPLE_STEP) {
            for (x in (w*0.05f).toInt() until (w*0.95f).toInt() step SAMPLE_STEP) {
                Color.colorToHSV(bmp.getPixel(x.coerceIn(0,w-1), y.coerceIn(0,h-1)), hsv)
                if (hsv[1] in DIAL_S && hsv[2] in DIAL_V) dialogHits++
            }
        }
        for (y in lvlTop until lvlBottom step SAMPLE_STEP) {
            for (x in lvlLeft until lvlRight step SAMPLE_STEP) {
                Color.colorToHSV(bmp.getPixel(x.coerceIn(0,w-1), y.coerceIn(0,h-1)), hsv)
                if (hsv[0] in LVL_H && hsv[1] in LVL_S && hsv[2] in LVL_V) levelHits++
            }
        }
        // Login: sample full screen middle band
        val loginSamples = 200
        for (i in 0 until loginSamples) {
            val lx = (w * 0.1f + (w * 0.8f) * (i.toFloat() / loginSamples)).toInt()
            val ly = (h * 0.3f + (h * 0.4f) * (i.toFloat() / loginSamples)).toInt()
            Color.colorToHSV(bmp.getPixel(lx.coerceIn(0,w-1), ly.coerceIn(0,h-1)), hsv)
            if (hsv[0] in LOGIN_H && hsv[1] in LOGIN_S && hsv[2] in LOGIN_V) loginHits++
        }

        val state = when {
            total > 0 && loadHits.toFloat() / total > 0.70f -> GameState.LOADING
            loginHits > 120                                  -> GameState.LOGIN_SCREEN
            bankHits > 40                                    -> GameState.BANK_OPEN
            levelHits > 60                                   -> GameState.LEVEL_UP
            dialogHits > 80                                  -> GameState.DIALOGUE_OPEN
            else                                             -> GameState.PLAYING
        }

        Logger.info("GameStateDetector: $state (bank=$bankHits dial=$dialogHits lvl=$levelHits login=$loginHits load=$loadHits)")
        cachedState = state
        cacheTime = System.currentTimeMillis()
        return state
    }

    fun isPlaying()     = getState() == GameState.PLAYING
    fun isBankOpen()    = getState() == GameState.BANK_OPEN
    fun isDialogOpen()  = getState() == GameState.DIALOGUE_OPEN
    fun isLevelUp()     = getState() == GameState.LEVEL_UP
    fun isLoginScreen() = getState() == GameState.LOGIN_SCREEN
    fun isLoading()     = getState() == GameState.LOADING

    fun invalidateCache() { cacheTime = 0L }
}
