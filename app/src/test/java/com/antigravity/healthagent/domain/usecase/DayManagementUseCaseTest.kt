package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.domain.repository.HouseRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

class DayManagementUseCaseTest {

    private val repository = mockk<HouseRepository>(relaxed = true)
    private lateinit var useCase: DayManagementUseCase
    private val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US)

    @Before
    fun setup() {
        useCase = DayManagementUseCase(repository)
    }

    // --- isDateLocked ---
    
    @Test
    fun `isDateLocked returns true when activity is closed`() {
        val activity = DayActivity(date = "25-05-2026", isClosed = true)
        assertTrue(useCase.isDateLocked(activity))
    }

    @Test
    fun `isDateLocked returns false when activity is not closed`() {
        val activity = DayActivity(date = "25-05-2026", isClosed = false)
        assertFalse(useCase.isDateLocked(activity))
    }

    @Test
    fun `isDateLocked returns false when activity is null`() {
        assertFalse(useCase.isDateLocked(null))
    }

    // --- getDayActivity ---

    @Test
    fun `getDayActivity normalizes slash dates to dashes`() = runBlocking {
        coEvery { repository.getDayActivity("25-05-2026", "uid_1") } returns DayActivity(date = "25-05-2026")

        val result = useCase.getDayActivity("25/05/2026", "uid_1")

        assertNotNull(result)
        coVerify { repository.getDayActivity("25-05-2026", "uid_1") }
    }

    @Test
    fun `getDayActivity returns null when no activity exists`() = runBlocking {
        coEvery { repository.getDayActivity(any(), any()) } returns null

        val result = useCase.getDayActivity("01-01-2026", "uid_1")

        assertNull(result)
    }

    // --- unlockDay ---

    @Test
    fun `unlockDay sets isClosed false and isManualUnlock true for existing activity`() = runBlocking {
        val existing = DayActivity(
            date = "25-05-2026",
            status = "NORMAL",
            isClosed = true,
            isManualUnlock = false,
            agentUid = "uid_1"
        )
        coEvery { repository.getDayActivity("25-05-2026", "uid_1") } returns existing

        useCase.unlockDay("25-05-2026", "uid_1")

        coVerify {
            repository.updateDayActivity(
                match {
                    !it.isClosed && it.isManualUnlock && it.agentUid == "uid_1" && it.date == "25-05-2026"
                },
                false
            )
        }
    }

    @Test
    fun `unlockDay creates new activity when none exists`() = runBlocking {
        coEvery { repository.getDayActivity("25-05-2026", "uid_1") } returns null

        useCase.unlockDay("25-05-2026", "uid_1")

        coVerify {
            repository.updateDayActivity(
                match {
                    !it.isClosed && it.isManualUnlock && it.status == "NORMAL" && it.agentUid == "uid_1"
                },
                false
            )
        }
    }

    @Test
    fun `unlockDay normalizes slash dates`() = runBlocking {
        coEvery { repository.getDayActivity("25-05-2026", null) } returns null

        useCase.unlockDay("25/05/2026")

        coVerify { repository.getDayActivity("25-05-2026", null) }
    }

    // --- closeDay ---

    @Test
    fun `closeDay sets isClosed true and resets isManualUnlock`() = runBlocking {
        val existing = DayActivity(
            date = "25-05-2026",
            status = "NORMAL",
            isClosed = false,
            isManualUnlock = true,
            agentUid = "uid_1"
        )
        coEvery { repository.getDayActivity("25-05-2026", "uid_1") } returns existing

        useCase.closeDay("25-05-2026", "uid_1")

        coVerify {
            repository.updateDayActivity(
                match {
                    it.isClosed && !it.isManualUnlock && it.agentUid == "uid_1"
                },
                false
            )
        }
    }

    @Test
    fun `closeDay creates new activity when none exists`() = runBlocking {
        coEvery { repository.getDayActivity("25-05-2026", "uid_1") } returns null

        useCase.closeDay("25-05-2026", "uid_1")

        coVerify {
            repository.updateDayActivity(
                match { it.isClosed && !it.isManualUnlock && it.date == "25-05-2026" },
                false
            )
        }
    }

    // --- canSafelyUnlock ---

    @Test
    fun `canSafelyUnlock allows admin for any date`() = runBlocking {
        assertTrue(useCase.canSafelyUnlock("01-01-2020", "uid_1", isAdmin = true))
    }

    @Test
    fun `canSafelyUnlock allows current date`() = runBlocking {
        val today = sdf.format(Date())
        assertTrue(useCase.canSafelyUnlock(today, "uid_1", isAdmin = false))
    }

    @Test
    fun `canSafelyUnlock rejects old date for non-admin`() = runBlocking {
        // Mock no activities so no "previous workday" exists
        coEvery { repository.getAllDayActivitiesOnce(any()) } returns emptyList()

        assertFalse(useCase.canSafelyUnlock("01-01-2020", "uid_1", isAdmin = false))
    }

    @Test
    fun `canSafelyUnlock allows previous workday`() = runBlocking {
        val today = sdf.format(Date())
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = sdf.format(cal.time)

        coEvery { repository.getAllDayActivitiesOnce("uid_1") } returns listOf(
            DayActivity(date = yesterday, status = "NORMAL", agentUid = "uid_1")
        )

        assertTrue(useCase.canSafelyUnlock(yesterday, "uid_1", isAdmin = false))
    }

    // --- getNextBusinessDay ---

    @Test
    fun `getNextBusinessDay skips weekends`() = runBlocking {
        // Find a Friday
        val cal = Calendar.getInstance()
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val friday = sdf.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, 3) // Monday
        val monday = sdf.format(cal.time)

        coEvery { repository.getDayActivity(any(), any()) } returns null

        val result = useCase.getNextBusinessDay(friday, "uid_1")

        assertEquals(monday, result)
    }

    @Test
    fun `getNextBusinessDay skips holidays`() = runBlocking {
        val today = sdf.format(Date())
        val cal = Calendar.getInstance()
        
        // Find next non-weekend day
        do {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        } while (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
        
        val nextDay = sdf.format(cal.time)
        
        // Make that day a FERIADO
        coEvery { repository.getDayActivity(nextDay, "uid_1") } returns DayActivity(
            date = nextDay, status = "FERIADO", agentUid = "uid_1"
        )
        // Day after should be normal
        cal.add(Calendar.DAY_OF_YEAR, 1)
        while (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val dayAfter = sdf.format(cal.time)
        coEvery { repository.getDayActivity(dayAfter, "uid_1") } returns null

        val result = useCase.getNextBusinessDay(today, "uid_1")

        assertEquals(dayAfter, result)
    }

    @Test
    fun `getNextBusinessDay returns empty string for invalid date`() = runBlocking {
        val result = useCase.getNextBusinessDay("invalid-date", "uid_1")
        assertEquals("", result)
    }
}
