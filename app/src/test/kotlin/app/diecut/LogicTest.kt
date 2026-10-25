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

class UpcomingTest {
    private val oct2 = java.time.LocalDate.of(2026, 10, 2)

    @Test
    fun countsDownToTheNextOne() {
        val g = Upcoming.groups(listOf(Day("a", "Priya", 10, 5), Day("b", "Sam", 12, 1)), oct2, 3)
        assertEquals(3, g[0].daysAway)
        assertEquals("Priya", g[0].people[0].name)
        assertEquals("3 days to go", Upcoming.countdown(g[0].daysAway))
    }

    @Test
    fun aDayGoneByIsNextYear() {
        val g = Upcoming.groups(listOf(Day("a", "Priya", 10, 1)), oct2, 1)
        assertEquals(java.time.LocalDate.of(2027, 10, 1), g[0].date)
    }

    @Test
    fun todayIsToday() {
        val g = Upcoming.groups(listOf(Day("a", "Priya", 10, 2)), oct2, 1)
        assertEquals(0, g[0].daysAway)
        assertEquals("Today", Upcoming.countdown(0))
    }

    @Test
    fun sharedDaysGoTogether() {
        val g = Upcoming.groups(listOf(Day("a", "Rahul", 10, 5), Day("b", "Priya", 10, 5), Day("c", "Sam", 11, 1)), oct2, 3)
        assertEquals(2, g.size)
        assertEquals("Priya & Rahul's birthdays", Upcoming.occasion(g[0].people))
    }

    @Test
    fun threeNames() = assertEquals("Priya, Rahul & Sam", Upcoming.names(listOf("Priya", "Rahul", "Sam")))

    @Test
    fun aLeapDayFallsOnThe28th() {
        val d = Upcoming.next(Day("a", "Leap", 2, 29), java.time.LocalDate.of(2027, 1, 1))
        assertEquals(java.time.LocalDate.of(2027, 2, 28), d)
    }

    @Test
    fun aSpecialDayIsItsName() =
        assertEquals("Trip to Goa", Upcoming.occasion(listOf(Day("a", "Trip to Goa", 12, 20, Day.Kind.SPECIAL))))
}
