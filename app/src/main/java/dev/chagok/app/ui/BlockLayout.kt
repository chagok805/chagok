package dev.chagok.app.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry of the block map: a fill-up split into blocks whose areas are their share of the distance.
 * One cell of the 10 x 10 grid is 1%. Blocks are poured from the top-left, smallest first, so the
 * largest share lies at the bottom as the base the others are stacked on.
 *
 * Columns are whole grid columns; heights are continuous, so every area is exact and a small change
 * in distance moves an edge a little instead of rearranging the map. Each block is described by the
 * profile it leaves behind: for every column, how deep the map is filled once this block is in.
 *
 * Pure Kotlin on purpose (no Compose), so the layout is unit tested on the JVM.
 */
object BlockLayout {
    const val COLUMNS = 10
    private const val EPS = 1e-6f
    private const val BEAM = 5

    /** A column run of one block: columns [x0, x1), depth [y0, y1) as a fraction of the height. */
    data class Run(val x0: Int, val x1: Int, val y0: Float, val y1: Float)

    /** Profiles, one per block in the order given; the last is always the full map. */
    class Result(val profiles: List<FloatArray>) {
        fun runs(index: Int): List<Run> = runsBetween(if (index == 0) FloatArray(COLUMNS) else profiles[index - 1], profiles[index])

        /** The block's largest run in pixels, where its name and number go. */
        fun labelRun(index: Int, width: Float, height: Float): Run =
            runs(index).maxBy { (it.x1 - it.x0) * width / COLUMNS * (it.y1 - it.y0) * height }

        /** Which block a point (fractions of width and height) falls in. */
        fun blockAt(x: Float, y: Float): Int? {
            val column = (x * COLUMNS).toInt().coerceIn(0, COLUMNS - 1)
            var top = 0f
            profiles.forEachIndexed { i, profile ->
                if (y >= top - EPS && y < profile[column]) return i
                top = profile[column]
            }
            return null
        }

        /**
         * The depth above which [share] of block [index] lies: the newest part of a block is its top,
         * so proposals are drawn from there down.
         */
        fun depthHolding(index: Int, share: Float): Float {
            val runs = runs(index)
            if (share <= EPS) return runs.minOf { it.y0 }
            var lo = runs.minOf { it.y0 }
            var hi = runs.maxOf { it.y1 }
            repeat(40) {
                val mid = (lo + hi) / 2
                val above = runs.sumOf { r -> ((r.x1 - r.x0).toDouble() / COLUMNS) * (min(mid, r.y1) - r.y0).coerceAtLeast(0f) }
                if (above < share) lo = mid else hi = mid
            }
            return hi
        }
    }

    /**
     * Lays out [shares] (fractions that add up to 1, top-down order) on a map [width] x [height]
     * pixels. [previous] is the last layout with the same blocks: it is preferred, so the map keeps
     * its shape while distances change a little.
     */
    fun of(shares: List<Float>, width: Float, height: Float, previous: Result? = null): Result {
        require(shares.isNotEmpty())
        val total = shares.sum()
        val normalized = shares.map { it / total }
        val prev = previous?.profiles?.takeIf { it.size == shares.size }
        var states = listOf(State(0f, FloatArray(COLUMNS), emptyList()))
        normalized.forEachIndexed { i, area ->
            val last = i == normalized.lastIndex
            val next = mutableListOf<State>()
            for (state in states) {
                val candidates = if (last) listOf(Move(FloatArray(COLUMNS) { 1f }, 0f)) else moves(state.profile, area)
                for (move in candidates) {
                    val runs = runsBetween(state.profile, move.profile)
                    if (!connected(runs)) continue
                    var cost = move.extra + shapeCost(runs, area, width, height, last)
                    if (!last) {
                        val steps = profileRuns(move.profile)
                        cost += 1.2f * (steps.size - 1)
                        // Two edges that almost line up read as a mistake: either level or a real step.
                        cost += 8f * steps.zipWithNext().count { (a, b) -> abs(a.depth - b.depth) * height in 0.5f..64f }
                        // A sliver left under this block is too thin for the next one.
                        for (x in 0 until COLUMNS) if ((1f - move.profile[x]) * height in 0.5f..60f) cost += 5f
                        // The first block sits top-left, so the map always reads from the same corner.
                        if (i == 0) cost += 0.3f * (0 until COLUMNS).filter { move.profile[it] > EPS }.average().toFloat() / COLUMNS
                    }
                    if (prev != null && i < prev.size) {
                        cost += 4f * (0 until COLUMNS).sumOf { abs(move.profile[it] - prev[i][it]).toDouble() }.toFloat() / COLUMNS
                    }
                    next += State(state.cost + cost, move.profile, state.profiles + listOf(move.profile))
                }
            }
            states = next.sortedBy { it.cost }.take(BEAM)
            if (states.isEmpty()) return fallback(normalized)
        }
        return Result(states.first().profiles)
    }

    private class State(val cost: Float, val profile: FloatArray, val profiles: List<FloatArray>)

    private class Move(val profile: FloatArray, val extra: Float)

    private data class ProfileRun(val x0: Int, val x1: Int, val depth: Float)

    private fun profileRuns(p: FloatArray): List<ProfileRun> {
        val out = mutableListOf<ProfileRun>()
        var start = 0
        for (c in 1..COLUMNS) {
            if (c == COLUMNS || abs(p[c] - p[start]) > EPS) {
                out += ProfileRun(start, c, p[start])
                start = c
            }
        }
        return out
    }

    internal fun runsBetween(p: FloatArray, q: FloatArray): List<Run> {
        val out = mutableListOf<Run>()
        var start = -1
        for (c in 0..COLUMNS) {
            val live = c < COLUMNS && q[c] - p[c] > EPS
            if (start >= 0 && (!live || abs(p[c] - p[start]) > EPS || abs(q[c] - q[start]) > EPS)) {
                out += Run(start, c, p[start], q[start])
                start = -1
            }
            if (live && start < 0) start = c
        }
        return out
    }

    private fun connected(runs: List<Run>): Boolean {
        if (runs.isEmpty()) return false
        return runs.zipWithNext().all { (a, b) -> b.x0 == a.x1 && min(a.y1, b.y1) - max(a.y0, b.y0) >= 0.02f }
    }

    private fun shapeCost(runs: List<Run>, share: Float, width: Float, height: Float, last: Boolean): Float {
        var cost = when (runs.size) {
            1 -> 0f
            2 -> 1.5f
            3 -> 5f
            else -> 9f + 3f * (runs.size - 4)
        }
        if (!last && runs.size > 3) cost += 20f
        fun w(r: Run) = (r.x1 - r.x0) * width / COLUMNS - 8f
        fun h(r: Run) = (r.y1 - r.y0) * height - 8f
        for (r in runs) {
            if (h(r) < 90f) cost += (90f - h(r)) / 10f
            if (w(r) < 100f) cost += (100f - w(r)) / 12f
        }
        val best = runs.maxBy { w(it) * h(it) }
        val bw = w(best)
        val bh = h(best)
        if (bw <= 0f || bh <= 0f) return 99f
        cost += 0.5f * min(3f, max(bw, bh) / min(bw, bh) - 1f)
        if (share >= 0.05f && min(bw, bh) < 120f) cost += 3f
        return cost
    }

    /** Ways to pour [area] into profile [p]: an optional levelling step, then one or two rectangles. */
    private fun moves(p: FloatArray, area: Float): List<Move> {
        val out = mutableListOf<Move>()
        finals(p, area, 0f, out)
        val runs = profileRuns(p)
        val shallow = runs.minBy { it.depth }
        val neighbours = listOfNotNull(
            if (shallow.x0 > 0) p[shallow.x0 - 1] else null,
            if (shallow.x1 < COLUMNS) p[shallow.x1] else null,
        )
        if (neighbours.isNotEmpty()) {
            val target = neighbours.min()
            val cap = (shallow.x1 - shallow.x0).toFloat() / COLUMNS * (target - shallow.depth)
            if (cap < area - 0.01f) {
                val levelled = p.copyOf()
                for (x in shallow.x0 until shallow.x1) levelled[x] = target
                finals(levelled, area - cap, 0.5f, out)
            }
        }
        return out
    }

    private fun finals(p: FloatArray, area: Float, extra: Float, out: MutableList<Move>) {
        val runs = profileRuns(p)
        val depth = runs.minOf { it.depth }
        val shallow = runs.first { abs(it.depth - depth) < EPS }
        val lo = shallow.x0
        val hi = shallow.x1
        val width = hi - lo
        // One rectangle over k columns at either end of the shallowest run.
        for (k in 1..width) {
            val bottom = depth + area / (k.toFloat() / COLUMNS)
            if (bottom > 1f + EPS) continue
            for (start in setOf(lo, hi - k)) {
                val q = p.copyOf()
                for (x in start until start + k) q[x] = min(1f, bottom)
                out += Move(q, extra)
            }
        }
        // A tower down to the deeper neighbour, and a shelf beside it.
        val sides = listOf(
            true to (if (lo > 0) p[lo - 1] else null),
            false to (if (hi < COLUMNS) p[hi] else null),
        )
        for ((left, target) in sides) {
            if (target == null || target <= depth + EPS) continue
            for (k1 in 1 until width) {
                val rest = area - k1.toFloat() / COLUMNS * (target - depth)
                if (rest <= EPS) continue
                for (k2 in 1..(width - k1)) {
                    val shelf = rest / (k2.toFloat() / COLUMNS)
                    if (depth + shelf >= target - 0.02f) continue
                    val q = p.copyOf()
                    val towerStart = if (left) lo else hi - k1
                    for (x in towerStart until towerStart + k1) q[x] = target
                    val shelfStart = if (left) towerStart + k1 else towerStart - k2
                    for (x in shelfStart until shelfStart + k2) q[x] = depth + shelf
                    out += Move(q, extra + 0.5f)
                }
            }
        }
    }

    /** Full-width bands, if nothing else fits (it never should). */
    private fun fallback(shares: List<Float>): Result {
        var depth = 0f
        return Result(shares.mapIndexed { i, s -> depth = if (i == shares.lastIndex) 1f else depth + s; FloatArray(COLUMNS) { depth } })
    }
}
