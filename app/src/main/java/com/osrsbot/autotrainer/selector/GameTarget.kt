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
    fun remove(index: Int) {
        if (index in targets.indices) {
            targets.removeAt(index)
            if (targets.isEmpty()) cursor = 0 else cursor %= targets.size
        }
    }
    fun clear() {
        targets.clear()
        cursor = 0
    }
    fun getAll(): List<GameTarget> = targets.toList()
    fun isEmpty(): Boolean = targets.isEmpty()
    fun count(): Int = targets.size

    /** Returns the next target in round-robin without consuming it */
    private var cursor = 0
    fun nextTarget(): GameTarget? {
        if (targets.isEmpty()) return null
        val t = targets[cursor % targets.size]
        cursor = (cursor + 1) % targets.size
        return t
    }

    fun nextTargetWhere(predicate: (GameTarget) -> Boolean): GameTarget? {
        if (targets.isEmpty()) return null
        repeat(targets.size) {
            val t = targets[cursor % targets.size]
            cursor = (cursor + 1) % targets.size
            if (predicate(t)) return t
        }
        return null
    }

    /** Returns the most recently accessed target without advancing the cursor */
    fun peekCurrent(): GameTarget? {
        if (targets.isEmpty()) return null
        val idx = if (cursor == 0) targets.size - 1 else cursor - 1
        return targets[idx % targets.size]
    }
}
