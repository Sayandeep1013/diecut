package app.diecut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test
    fun oneDoesNotTurn() = assertFalse(Timing.due(1, 30, 0, 10_000_000))

    @Test
    fun tapOnlyDoesNotTurn() = assertFalse(Timing.due(3, 0, 0, 10_000_000))

    @Test
    fun turnsAfterTheInterval() {
        assertFalse(Timing.due(3, 60, 0, 30 * 60_000L))
        assertTrue(Timing.due(3, 60, 0, 60 * 60_000L))
        // A periodic update a few seconds early still counts.
        assertTrue(Timing.due(3, 60, 0, 59 * 60_000L + 30_000L))
    }

    @Test
    fun fourAppsInAStripGoInARow() {
        val f = Grid.fit(4, 300f, 70f, 0f)
        assertEquals(4, f.cols)
        assertEquals(1, f.rows)
    }

    @Test
    fun fourAppsInASquareGoTwoByTwo() {
        val f = Grid.fit(4, 140f, 140f, 0f)
        assertEquals(2, f.cols)
        assertEquals(2, f.rows)
    }

    @Test
    fun iconsAreCapped() = assertEquals(72f, Grid.fit(1, 400f, 400f, 0f).icon, 0.01f)

    @Test
    fun stepsHaveAFloor() = assertEquals(2, Grid.steps(5f, 10f, 2))
}
