package com.osrsbot.autotrainer.selector

import android.graphics.PointF

data class GameTarget(
    val label: String,
    val x: Float,
    val y: Float,
    val color: Int = 0,
    val radiusPx: Float = 30f,
) {
    fun toPoint() = PointF(x, y)

    override fun toString(): String = "$label @ (${x.toInt()}, ${y.toInt()})"
}

object TargetStore {
    private val targets = mutableListOf<GameTarget>()

    fun add(target: GameTarget) { targets.add(target) }
    fun remove(index: Int) { if (index in targets.indices) targets.removeAt(index) }
    fun clear() { targets.clear() }
    fun getAll(): List<GameTarget> = targets.toList()
    fun isEmpty(): Boolean = targets.isEmpty()
    fun count(): Int = targets.size

    /** Returns the next target to click in round-robin */
    private var cursor = 0
    fun nextTarget(): GameTarget? {
        if (targets.isEmpty()) return null
        val t = targets[cursor % targets.size]
        cursor = (cursor + 1) % targets.size
        return t
    }
}
