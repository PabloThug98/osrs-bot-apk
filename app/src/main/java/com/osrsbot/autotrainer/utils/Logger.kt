package com.osrsbot.autotrainer.utils

import android.util.Log

object Logger {
    private const val TAG = "OSRSBot"
    private val entries = ArrayDeque<String>(500)
    var onNewEntry: ((String) -> Unit)? = null

    enum class Level { INFO, OK, WARN, ERROR, ACTION }

    fun log(msg: String, level: Level = Level.INFO) {
        val line = "[${level.name}] $msg"
        Log.d(TAG, line)
        entries.addLast(line)
        if (entries.size > 500) entries.removeFirst()
        onNewEntry?.invoke(line)
    }

    fun info(msg: String)   = log(msg, Level.INFO)
    fun ok(msg: String)     = log(msg, Level.OK)
    fun warn(msg: String)   = log(msg, Level.WARN)
    fun error(msg: String)  = log(msg, Level.ERROR)
    fun action(msg: String) = log(msg, Level.ACTION)

    fun getAll(): List<String> = entries.toList()
    fun clear() = entries.clear()
}
