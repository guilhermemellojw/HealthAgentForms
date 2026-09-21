package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.DailyContext
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.domain.model.VisitAddress
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.data.repository.StreetRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SaveHouseUseCaseSanitizationTest {

    private val repository = mockk<HouseRepository>(relaxed = true)
    private val streetRepository = mockk<StreetRepository>(relaxed = true)
    private val recalcUseCase = mockk<RecalculateVisitSegmentsUseCase>(relaxed = true)
    private val dayLockEnforcerUseCase = mockk<DayLockEnforcerUseCase>(relaxed = true)
    private lateinit var useCase: SaveHouseUseCase

    @Before
    fun setup() {
        useCase = SaveHouseUseCase(repository, streetRepository, recalcUseCase, dayLockEnforcerUseCase)
        coEvery { repository.runInTransaction(any<suspend () -> Any>()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = args[0] as suspend () -> Any
            block()
        }
        every { recalcUseCase.recalculateVisitSegments(any()) } answers { firstArg() }
    }

    private fun baseHouse(
        id: Int = 0,
        streetName: String = "RUA TESTE",
        number: String = "100",
        blockNumber: String = "001",
        blockSequence: String = "",
        bairro: String = "CENTRO",
        agentName: String = "GUILHERME",
        agentUid: String = "uid_1",
        date: String = "25-05-2026",
        propertyType: PropertyType = PropertyType.R,
        situation: Situation = Situation.NONE,
        treatment: TreatmentData = TreatmentData(),
        observation: String = ""
    ) = House(
        id = id,
        data = date,
        agentName = agentName,
        agentUid = agentUid,
        address = VisitAddress(
            blockNumber = blockNumber,
            blockSequence = blockSequence,
            streetName = streetName,
            number = number,
            sequence = 0,
            complement = 0,
            bairro = bairro
        ),
        propertyType = propertyType,
        situation = situation,
        treatment = treatment,
        observation = observation
    )

    @Test
    fun `sanitizeHouse formats street name to Title Case`() {
        val house = baseHouse(streetName = "RUA DAS FLORES")
        val result = useCase.sanitizeHouse(house)
        assertEquals("Rua das Flores", result.address.streetName)
    }

    @Test
    fun `sanitizeHouse uppercases number`() {
        val house = baseHouse(number = "123a")
        val result = useCase.sanitizeHouse(house)
        assertEquals("123A", result.address.number)
    }

    @Test
    fun `sanitizeHouse normalizes blockNumber to UPPERCASE`() {
        val house = baseHouse(blockNumber = "001a")
        val result = useCase.sanitizeHouse(house)
        assertEquals("001A", result.address.blockNumber)
    }

    @Test
    fun `sanitizeHouse heals EMPTY situation to NONE`() {
        val house = baseHouse(situation = Situation.EMPTY)
        val result = useCase.sanitizeHouse(house)
        assertEquals(Situation.NONE, result.situation)
    }

    @Test
    fun `sanitizeHouse clears treatment when situation is not NONE`() {
        val house = baseHouse(
            situation = Situation.F,
            treatment = TreatmentData(a1 = 5, b = 3, larvicida = 10.0)
        )
        val result = useCase.sanitizeHouse(house)
        assertEquals(0, result.treatment.a1)
        assertEquals(0, result.treatment.b)
        assertEquals(0.0, result.treatment.larvicida, 0.001)
    }

    @Test
    fun `sanitizeHouse preserves treatment when situation is NONE`() {
        val house = baseHouse(
            situation = Situation.NONE,
            treatment = TreatmentData(a1 = 5, b = 3, larvicida = 10.0)
        )
        val result = useCase.sanitizeHouse(house)
        assertEquals(5, result.treatment.a1)
        assertEquals(3, result.treatment.b)
        assertEquals(10.0, result.treatment.larvicida, 0.001)
    }

    @Test
    fun `sanitizeHouse normalizes date to dash format`() {
        val house = baseHouse(date = "25/05/2026")
        val result = useCase.sanitizeHouse(house)
        assertEquals("25-05-2026", result.data)
    }

    @Test
    fun `sanitizeHouse marks as unsynced`() {
        val house = baseHouse().copy(isSynced = true)
        val result = useCase.sanitizeHouse(house)
        assertFalse(result.isSynced)
    }

    @Test
    fun `sanitizeHouse is idempotent - double sanitize produces same result`() {
        val house = baseHouse(streetName = "  rua das flores  ", number = " 123a ")
        val first = useCase.sanitizeHouse(house)
        val second = useCase.sanitizeHouse(first)
        assertEquals(first.address.streetName, second.address.streetName)
        assertEquals(first.address.number, second.address.number)
        assertEquals(first.address.blockNumber, second.address.blockNumber)
    }
}
