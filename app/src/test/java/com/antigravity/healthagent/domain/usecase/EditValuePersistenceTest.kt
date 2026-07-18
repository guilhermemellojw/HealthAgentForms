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

class EditValuePersistenceTest {

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

    private fun baseHouse() = House(
        id = 1,
        data = "25-05-2026",
        agentName = "GUILHERME",
        agentUid = "uid_1",
        address = VisitAddress(
            blockNumber = "001",
            blockSequence = "A",
            streetName = "RUA TESTE",
            number = "100",
            sequence = 1,
            complement = 0,
            bairro = "CENTRO"
        ),
        propertyType = PropertyType.R,
        situation = Situation.NONE,
        treatment = TreatmentData(),
        context = DailyContext(
            municipio = "Bom Jardim",
            categoria = "BRR",
            zona = "URB",
            tipo = 2,
            ciclo = "1/2026",
            atividade = 4
        )
    )

    @Test
    fun `sanitizeHouse preserves number value`() {
        val house = baseHouse().copy(
            address = baseHouse().address.copy(number = "123")
        )
        val result = useCase.sanitizeHouse(house)
        assertEquals("123", result.address.number)
    }

    @Test
    fun `sanitizeHouse uppercases number`() {
        val house = baseHouse().copy(
            address = baseHouse().address.copy(number = "abc")
        )
        val result = useCase.sanitizeHouse(house)
        assertEquals("ABC", result.address.number)
    }

    @Test
    fun `sanitizeHouse preserves sequence value`() {
        val house = baseHouse().copy(
            address = baseHouse().address.copy(sequence = 5)
        )
        val result = useCase.sanitizeHouse(house)
        assertEquals(5, result.address.sequence)
    }

    @Test
    fun `sanitizeHouse preserves complement value`() {
        val house = baseHouse().copy(
            address = baseHouse().address.copy(complement = 3)
        )
        val result = useCase.sanitizeHouse(house)
        assertEquals(3, result.address.complement)
    }

    @Test
    fun `sanitizeHouse formats streetName to Title Case`() {
        val house = baseHouse().copy(
            address = baseHouse().address.copy(streetName = "RUA DAS FLORES")
        )
        val result = useCase.sanitizeHouse(house)
        assertEquals("Rua das Flores", result.address.streetName)
    }

    @Test
    fun `sanitizeHouse normalizes blockNumber`() {
        val house = baseHouse().copy(
            address = baseHouse().address.copy(blockNumber = "001a")
        )
        val result = useCase.sanitizeHouse(house)
        assertEquals("001A", result.address.blockNumber)
    }

    @Test
    fun `sanitizeHouse preserves propertyType`() {
        val house = baseHouse().copy(propertyType = PropertyType.C)
        val result = useCase.sanitizeHouse(house)
        assertEquals(PropertyType.C, result.propertyType)
    }

    @Test
    fun `sanitizeHouse preserves treatment values when situation is NONE`() {
        val treatment = TreatmentData(a1 = 3, a2 = 2, b = 1, c = 4, d1 = 0, d2 = 1, e = 0, eliminados = 5, larvicida = 15.0, comFoco = true)
        val house = baseHouse().copy(treatment = treatment, situation = Situation.NONE)
        val result = useCase.sanitizeHouse(house)

        assertEquals(3, result.treatment.a1)
        assertEquals(2, result.treatment.a2)
        assertEquals(1, result.treatment.b)
        assertEquals(4, result.treatment.c)
        assertEquals(0, result.treatment.d1)
        assertEquals(1, result.treatment.d2)
        assertEquals(0, result.treatment.e)
        assertEquals(5, result.treatment.eliminados)
        assertEquals(15.0, result.treatment.larvicida, 0.001)
        assertTrue(result.treatment.comFoco)
    }

    @Test
    fun `sanitizeHouse clears treatment when situation is closed`() {
        val treatment = TreatmentData(a1 = 3, b = 1, larvicida = 10.0)
        val house = baseHouse().copy(treatment = treatment, situation = Situation.F)
        val result = useCase.sanitizeHouse(house)

        assertEquals(0, result.treatment.a1)
        assertEquals(0, result.treatment.b)
        assertEquals(0.0, result.treatment.larvicida, 0.001)
    }

    @Test
    fun `sanitizeHouse normalizes context fields`() {
        val house = baseHouse().copy(
            context = DailyContext(
                municipio = "bom jardim",
                categoria = "brr",
                zona = "urb",
                ciclo = "1/2026",
                tipo = 2,
                atividade = 4
            )
        )
        val result = useCase.sanitizeHouse(house)

        assertEquals("BOM JARDIM", result.context.municipio)
        assertEquals("BRR", result.context.categoria)
        assertEquals("URB", result.context.zona)
    }

    @Test
    fun `sanitizeHouse normalizes bairro with accent healing`() {
        val house = baseHouse().copy(
            address = baseHouse().address.copy(bairro = "centro")
        )
        val result = useCase.sanitizeHouse(house)

        assertEquals("CENTRO", result.address.bairro)
    }

    @Test
    fun `sanitizeHouse preserves observation as-is`() {
        val house = baseHouse().copy(observation = "Test note with accent: ã ç é")
        val result = useCase.sanitizeHouse(house)

        assertEquals("Test note with accent: ã ç é", result.observation)
    }
}
