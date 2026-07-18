package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.domain.model.VisitAddress
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DayReopenEditTest {

    private val repository = mockk<com.antigravity.healthagent.domain.repository.HouseRepository>(relaxed = true)
    private val streetRepository = mockk<com.antigravity.healthagent.data.repository.StreetRepository>(relaxed = true)
    private val recalcUseCase = mockk<RecalculateVisitSegmentsUseCase>(relaxed = true)
    private val dayLockEnforcerUseCase = mockk<DayLockEnforcerUseCase>(relaxed = true)
    private lateinit var saveHouseUseCase: SaveHouseUseCase
    private lateinit var clashDetector: ClashDetector
    private lateinit var validationUseCase: HouseValidationUseCase

    private fun house(
        id: Int = 0,
        streetName: String = "RUA A",
        number: String = "10",
        sequence: Int = 0,
        complement: Int = 0,
        visitSegment: Int = 0,
        date: String = "25-05-2026",
        situation: Situation = Situation.NONE,
        treatment: TreatmentData = TreatmentData(),
        propertyType: PropertyType = PropertyType.R,
        listOrder: Long = 0,
        lastUpdated: Long = 1000L
    ) = House(
        id = id,
        data = date,
        agentName = "GUILHERME",
        agentUid = "uid_1",
        address = VisitAddress(
            blockNumber = "01",
            blockSequence = "",
            streetName = streetName,
            number = number,
            sequence = sequence,
            complement = complement,
            bairro = "CENTRO"
        ),
        propertyType = propertyType,
        situation = situation,
        treatment = treatment,
        listOrder = listOrder,
        visitSegment = visitSegment,
        lastUpdated = lastUpdated
    )

    @Before
    fun setup() {
        saveHouseUseCase = SaveHouseUseCase(repository, streetRepository, recalcUseCase, dayLockEnforcerUseCase)
        clashDetector = ClashDetector()
        validationUseCase = HouseValidationUseCase()

        coEvery { repository.runInTransaction(any<suspend () -> Any>()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = args[0] as suspend () -> Any
            block()
        }
        every { recalcUseCase.recalculateVisitSegments(any()) } answers { firstArg() }
    }

    @Test
    fun `editing house after day unlock does not throw`() = runBlocking {
        val house = house(id = 1, situation = Situation.NONE)

        coEvery { repository.getHousesByDateAndAgent(any(), any()) } returns listOf(house)
        coEvery { repository.updateHouse(any(), any()) } just Runs

        dayLockEnforcerUseCase.ensureDayNotLocked(house.data, house.agentUid, false)

        saveHouseUseCase.updateHouse(house, listOf(house))

        coVerify { repository.updateHouse(any(), false) }
    }

    @Test
    fun `editing house preserves situation after reopen`() {
        val original = house(id = 1, situation = Situation.NONE, treatment = TreatmentData(a1 = 2, larvicida = 5.0))
        val edited = house(id = 1, situation = Situation.NONE, treatment = TreatmentData(a1 = 3, larvicida = 7.0))

        val sanitized = saveHouseUseCase.sanitizeHouse(edited)

        assertEquals(Situation.NONE, sanitized.situation)
        assertEquals(3, sanitized.treatment.a1)
        assertEquals(7.0, sanitized.treatment.larvicida, 0.001)
    }

    @Test
    fun `closed situation clears treatment on sanitize`() {
        val house = house(id = 1, situation = Situation.F, treatment = TreatmentData(a1 = 5, larvicida = 10.0))

        val sanitized = saveHouseUseCase.sanitizeHouse(house)

        assertEquals(Situation.F, sanitized.situation)
        assertEquals(0, sanitized.treatment.a1)
        assertEquals(0.0, sanitized.treatment.larvicida, 0.001)
    }

    @Test
    fun `validation passes for reopened day with valid houses`() {
        val houses = listOf(
            house(id = 1, situation = Situation.NONE, treatment = TreatmentData(a1 = 1, larvicida = 2.0)),
            house(id = 2, situation = Situation.F, number = "20")
        )

        val result = validationUseCase.validateCurrentDay("25-05-2026", houses)
        assertTrue("Valid reopened day should pass validation", result.isValid)
    }

    @Test
    fun `validation catches treatment inconsistency after reopen edit`() {
        val houses = listOf(
            house(
                id = 1,
                situation = Situation.F,
                treatment = TreatmentData(a1 = 5, larvicida = 10.0)
            )
        )

        val result = validationUseCase.validateCurrentDay("25-05-2026", houses)
        assertFalse("Closed house with treatment should fail validation", result.isValid)
    }

    @Test
    fun `clash detection works correctly after reopen with different segments`() {
        val existing = listOf(house(id = 1, number = "10", visitSegment = 0))
        val candidate = house(id = 2, number = "10", visitSegment = 2)

        val clash = clashDetector.findClash(candidate, existing)
        assertNotNull("Same address should clash even with different segments after reopen", clash)
    }

    @Test
    fun `sanitize is idempotent for reopened house edits`() {
        val house = house(id = 1, streetName = "  rua teste  ", number = " 123a ")

        val first = saveHouseUseCase.sanitizeHouse(house)
        val second = saveHouseUseCase.sanitizeHouse(first)

        assertEquals(first.address.streetName, second.address.streetName)
        assertEquals(first.address.number, second.address.number)
        assertEquals(first.address.blockNumber, second.address.blockNumber)
        assertEquals(first.situation, second.situation)
    }
}
