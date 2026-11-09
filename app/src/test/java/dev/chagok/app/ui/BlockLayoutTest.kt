package dev.chagok.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockLayoutTest {

    private fun shares(vararg km: Int): List<Float> = km.map { it / km.sum().toFloat() }

    private fun area(result: BlockLayout.Result, i: Int): Float =
        result.runs(i).sumOf { (it.x1 - it.x0).toDouble() / BlockLayout.COLUMNS * (it.y1 - it.y0) }.toFloat()

    private fun shape(result: BlockLayout.Result): List<List<Pair<Int, Int>>> =
        result.profiles.dropLast(1).map { p ->
            val out = mutableListOf<Pair<Int, Int>>()
            var start = 0
            for (c in 1..BlockLayout.COLUMNS) if (c == BlockLayout.COLUMNS || p[c] != p[start]) { out += start to c; start = c }
            out
        }

    @Test
    fun everyBlockHasExactlyItsShare() {
        val cases = listOf(
            shares(42, 96, 190, 272), shares(42, 96, 190, 284), shares(42, 228, 330), shares(42, 558),
            shares(12, 30, 80, 150, 300), shares(10, 40, 60, 100, 200), shares(20, 20, 20, 20, 20), shares(3, 97), shares(100),
        )
        for (s in cases) {
            val result = BlockLayout.of(s, 724f, 768f)
            s.forEachIndexed { i, share -> assertEquals("share $i of $s", share, area(result, i), 0.002f) }
            for (i in s.indices) assertTrue("block $i of $s has runs", result.runs(i).isNotEmpty())
        }
    }

    @Test
    fun theFirstBlockSitsTopLeft() {
        val result = BlockLayout.of(shares(42, 96, 190, 284), 724f, 768f)
        assertEquals(0, result.blockAt(0.01f, 0.01f))
        assertEquals(3, result.blockAt(0.5f, 0.99f))
    }

    @Test
    fun smallChangesKeepTheShape() {
        var previous: BlockLayout.Result? = null
        var last: List<List<Pair<Int, Int>>>? = null
        var flips = 0
        for (km in 150..420 step 3) {
            val result = BlockLayout.of(shares(42, 96, 190, km), 724f, 768f, previous)
            val now = shape(result)
            if (last != null && now != last) flips++
            last = now
            previous = result
        }
        // From 31% to 56% the map may restructure, but only a couple of times, not at every step.
        assertTrue("flips $flips", flips <= 2)
    }

    @Test
    fun theStageDriveKeepsTheSeededShape() {
        // 600km seeded, then the 12km drive on stage.
        val seeded = BlockLayout.of(shares(42, 96, 190, 272), 724f, 768f)
        val after = BlockLayout.of(shares(42, 96, 190, 284), 724f, 768f, seeded)
        assertEquals(shape(seeded), shape(after))
    }

    @Test
    fun proposalsAreTheTopOfTheirBlock() {
        val result = BlockLayout.of(shares(42, 96, 190, 284), 724f, 768f)
        val runs = result.runs(1)
        val top = runs.minOf { it.y0 }
        val cut = result.depthHolding(1, 48f / 612f)
        assertTrue(cut > top)
        assertTrue(cut < runs.maxOf { it.y1 })
        assertNotNull(result.blockAt(0.5f, 0.5f))
    }
}
