package com.osrsbot.autotrainer.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Logger v2
 *
 * Upgrades over v1:
 *  - Timestamped entries  [HH:mm:ss LEVEL] message
 *  - Actions-per-minute rate tracker (APM)
 *  - Session start time for elapsed tracking
 *  - XP/hr and GP/hr helpers
 *  - getFormatted(n) — returns last N entries as a single string for overlay
 *  - Thread-safe action counter via AtomicLong
 */
object Logger {
    private const val TAG      = "OSRSBot"
    private const val MAX_ENTRIES = 800
    private val entries        = ArrayDeque<String>(MAX_ENTRIES)
    private val fmt            = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val sessionStart   = System.currentTimeMillis()
    private val actionCount    = AtomicLong(0)
    private val lastActionMs   = AtomicLong(System.currentTimeMillis())

    var onNewEntry: ((String) -> Unit)? = null

    enum class Level(val prefix: String) {
        INFO("·"), OK("✓"), WARN("⚠"), ERROR("✗"), ACTION("→")
    }

    fun log(msg: String, level: Level = Level.INFO) {
        val ts   = fmt.format(Date())
        val line = "[$ts ${level.prefix}] $msg"
        Log.d(TAG, line)
        synchronized(entries) {
            entries.addLast(line)
            if (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        onNewEntry?.invoke(line)
    }

    fun info(msg: String)   = log(msg, Level.INFO)
    fun ok(msg: String)     = log(msg, Level.OK)
    fun warn(msg: String)   = log(msg, Level.WARN)
    fun error(msg: String)  = log(msg, Level.ERROR)
    fun action(msg: String) {
        actionCount.incrementAndGet()
        lastActionMs.set(System.currentTimeMillis())
        log(msg, Level.ACTION)
    }

    // ── Rate tracking ─────────────────────────────────────────────────────────

    /** Returns elapsed session time in seconds. */
    fun elapsedSeconds(): Long = (System.currentTimeMillis() - sessionStart) / 1_000L

    /** Returns elapsed time as HH:MM:SS string. */
    fun elapsedFormatted(): String {
        val e = elapsedSeconds()
        return "%02d:%02d:%02d".format(e / 3600, (e % 3600) / 60, e % 60)
    }

    /** Returns actions per hour based on session totals. */
    fun actionsPerHour(totalActions: Int): Int {
        val hrs = elapsedSeconds().toFloat() / 3600f
        return if (hrs > 0f) (totalActions / hrs).toInt() else 0
    }

    /** Returns XP per hour. */
    fun xpPerHour(totalXp: Int): Int = actionsPerHour(totalXp)

    /** Returns GP per hour. */
    fun gpPerHour(totalGp: Int): Int = actionsPerHour(totalGp)

    /** Returns last N log entries joined by newline. */
    fun getFormatted(n: Int = 12): String =
        synchronized(entries) { entries.takeLast(n).joinToString("\n") }

    fun getAll(): List<String> = synchronized(entries) { entries.toList() }
    fun clear()  { synchronized(entries) { entries.clear() }; actionCount.set(0) }

    /** Returns full log as a single string (for export/share). */
    fun exportLog(): String = getAll().joinToString("\n")
}
