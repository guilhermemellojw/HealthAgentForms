package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DayErrorTrackerTest {

    private val validator = mockk<HouseValidationUseCase>()
    private lateinit var tracker: DayErrorTracker

    private val d1 = "25-05-2026"
    private val d2 = "26-05-2026"
    private val d3 = "27-05-2026"

    private fun house(id: Int, data: String, ts: Long) = House(id = id, data = data, lastUpdated = ts)

    @Before
    fun setup() {
        every { validator.validateCurrentDay(d1, any(), any()) } returns
            HouseValidationUseCase.ValidationResult(isValid = false)
        every { validator.validateCurrentDay(d2, any(), any()) } returns
            HouseValidationUseCase.ValidationResult(isValid = true)
        every { validator.validateCurrentDay(d3, any(), any()) } returns
            HouseValidationUseCase.ValidationResult(isValid = false)
        every { validator.isHouseValid(any(), any()) } returns false
        tracker = DayErrorTracker(validator)
    }

    @Test
    fun `first pass validates each day and reports only invalid ones`() {
        val result = tracker.compute(
            listOf(
                house(1, d1, 10L),
                house(2, d1, 10L),
                house(3, d2, 10L)
            )
        )

        assertEquals(1, result.size)
        assertEquals(DayErrorSummary(d1, 2), result[0])
        verify(exactly = 1) { validator.validateCurrentDay(d1, any(), true) }
        verify(exactly = 1) { validator.validateCurrentDay(d2, any(), true) }
    }

    @Test
    fun `edit on one day only revalidates that day`() {
        tracker.compute(
            listOf(
                house(1, d1, 10L),
                house(2, d1, 10L),
                house(3, d2, 10L)
            )
        )

        val result = tracker.compute(
            listOf(
                house(1, d1, 10L),
                house(2, d1, 11L),
                house(3, d2, 10L)
            )
        )

        assertEquals(DayErrorSummary(d1, 2), result[0])
        verify(exactly = 2) { validator.validateCurrentDay(d1, any(), true) }
        verify(exactly = 1) { validator.validateCurrentDay(d2, any(), true) }
    }

    @Test
    fun `unchanged emission does not revalidate any day`() {
        tracker.compute(
            listOf(
                house(1, d1, 10L),
                house(3, d2, 10L)
            )
        )
        tracker.compute(
            listOf(
                house(1, d1, 10L),
                house(3, d2, 10L)
            )
        )

        verify(exactly = 1) { validator.validateCurrentDay(d1, any(), true) }
        verify(exactly = 1) { validator.validateCurrentDay(d2, any(), true) }
    }

    @Test
    fun `removal from a day revalidates only that day`() {
        tracker.compute(
            listOf(
                house(1, d1, 10L),
                house(2, d1, 10L),
                house(3, d2, 10L)
            )
        )

        val result = tracker.compute(
            listOf(
                house(1, d1, 10L),
                house(3, d2, 10L)
            )
        )

        assertEquals(DayErrorSummary(d1, 1), result[0])
        verify(exactly = 2) { validator.validateCurrentDay(d1, any(), true) }
        verify(exactly = 1) { validator.validateCurrentDay(d2, any(), true) }
    }

    @Test
    fun `new day appears it is validated and sorted newest first`() {
        tracker.compute(
            listOf(
                house(1, d1, 10L)
            )
        )

        val result = tracker.compute(
            listOf(
                house(1, d1, 10L),
                house(4, d3, 20L),
                house(5, d3, 20L)
            )
        )

        assertEquals(2, result.size)
        assertTrue("Newest day must come first", result[0].date == d3)
        assertEquals(DayErrorSummary(d3, 2), result[0])
        assertEquals(DayErrorSummary(d1, 1), result[1])
        verify(exactly = 1) { validator.validateCurrentDay(d3, any(), true) }
        verify(exactly = 1) { validator.validateCurrentDay(d1, any(), true) }
    }

    @Test
    fun `day that becomes valid is removed from results`() {
        every { validator.validateCurrentDay(d1, any(), any()) } returns
            HouseValidationUseCase.ValidationResult(isValid = false)
        every { validator.validateCurrentDay(d2, any(), any()) } returns
            HouseValidationUseCase.ValidationResult(isValid = false)

        tracker.compute(
            listOf(
                house(1, d1, 10L),
                house(3, d2, 10L)
            )
        )

        every { validator.validateCurrentDay(d1, any(), any()) } returns
            HouseValidationUseCase.ValidationResult(isValid = true)

        val result = tracker.compute(
            listOf(
                house(1, d1, 11L),
                house(3, d2, 10L)
            )
        )

        assertEquals(1, result.size)
        assertEquals(DayErrorSummary(d2, 1), result[0])
    }
}