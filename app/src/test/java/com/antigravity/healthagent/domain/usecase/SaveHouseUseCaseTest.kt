package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.data.repository.StreetRepository
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.domain.model.VisitAddress
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SaveHouseUseCaseTest {

    private val repository = mockk<HouseRepository>(relaxed = true)
    private val streetRepository = mockk<StreetRepository>(relaxed = true)
    private val recalcUseCase = mockk<RecalculateVisitSegmentsUseCase>(relaxed = true)
    private lateinit var useCase: SaveHouseUseCase

    @Before
    fun setup() {
        useCase = SaveHouseUseCase(repository, streetRepository, recalcUseCase)

        // Default: runInTransaction just executes the lambda
        coEvery { repository.runInTransaction(any<suspend () -> Any>()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = args[0] as suspend () -> Any
            block()
        }

        // Default: recalculate returns input unchanged
        every { recalcUseCase.recalculateVisitSegments(any()) } answers { firstArg() }
    }

    private fun house(
        id: Int = 0,
        date: String = "25-05-2026",
        agentName: String = "GUILHERME",
        agentUid: String = "uid_1",
        streetName: String = "RUA A",
        number: String = "100",
        blockNumber: String = "001",
        situation: Situation = Situation.NONE,
        treatment: TreatmentData = TreatmentData(),
        listOrder: Long = 0
    ) = House(
        id = id,
        data = date,
        agentName = agentName,
        agentUid = agentUid,
        address = VisitAddress(
            blockNumber = blockNumber,
            blockSequence = "",
            streetName = streetName,
            number = number,
            sequence = 0,
            complement = 0,
            bairro = "CENTRO"
        ),
        propertyType = PropertyType.R,
        situation = situation,
        treatment = treatment,
        listOrder = listOrder
    )

    // --- sanitizeHouse (tested indirectly via insertHouse/updateHouse) ---

    @Test
    fun `insertHouse sanitizes and saves house`() = runBlocking {
        val h = house(number = "  100 ", streetName = "  rua teste  ")
        coEvery { repository.insertHouse(any(), any()) } returns 1L

        useCase.insertHouse(h, emptyList())

        coVerify {
            repository.insertHouse(
                match { it.address.number == "100" && it.address.streetName == "Rua Teste" },
                false
            )
        }
    }

    @Test
    fun `insertHouse marks house as unsynced`() = runBlocking {
        val h = house(isSynced = true)
        coEvery { repository.insertHouse(any(), any()) } returns 1L

        useCase.insertHouse(h, emptyList())

        coVerify {
            repository.insertHouse(match { !it.isSynced }, any())
        }
    }

    private fun house(
        isSynced: Boolean
    ) = House(
        data = "25-05-2026",
        agentName = "TEST",
        agentUid = "uid_1",
        address = VisitAddress(
            blockNumber = "001",
            blockSequence = "",
            streetName = "RUA A",
            number = "100",
            sequence = 0,
            complement = 0,
            bairro = "CENTRO"
        ),
        isSynced = isSynced,
        situation = Situation.NONE
    )

    @Test
    fun `insertHouse saves custom street`() = runBlocking {
        val h = house(streetName = "RUA NOVA")
        coEvery { repository.insertHouse(any(), any()) } returns 1L

        useCase.insertHouse(h, emptyList())

        coVerify { streetRepository.saveCustomStreet(any(), any()) }
    }

    @Test
    fun `insertHouse recalculates visit segments for the day`() = runBlocking {
        val existing = house(id = 1, listOrder = 0)
        val newHouse = house(id = 0, listOrder = 1)
        coEvery { repository.insertHouse(any(), any()) } returns 2L

        useCase.insertHouse(newHouse, listOf(existing))

        verify { recalcUseCase.recalculateVisitSegments(any()) }
    }

    // --- deleteHouse ---

    @Test
    fun `deleteHouse removes house and recalculates remaining`() = runBlocking {
        val h1 = house(id = 1, listOrder = 0)
        val h2 = house(id = 2, listOrder = 1)
        val h3 = house(id = 3, listOrder = 2)
        val allHouses = listOf(h1, h2, h3)

        useCase.deleteHouse(h2, allHouses)

        coVerify { repository.deleteHouse(h2, false) }
        verify { recalcUseCase.recalculateVisitSegments(match { it.size == 2 }) }
    }

    @Test
    fun `deleteHouse with force bypasses admin checks`() = runBlocking {
        val h = house(id = 1)

        useCase.deleteHouse(h, listOf(h), force = true)

        coVerify { repository.deleteHouse(h, true) }
    }

    // --- deleteProduction ---

    @Test
    fun `deleteProduction delegates to repository`() = runBlocking {
        useCase.deleteProduction("25-05-2026", "uid_1", force = true)

        coVerify { repository.deleteProduction("25-05-2026", "uid_1", true) }
    }

    // --- sanitizeHouse clears treatment for non-NONE situations ---

    @Test
    fun `sanitize clears treatment when situation is not NONE`() = runBlocking {
        val treatment = TreatmentData(a1 = 5, b = 3, larvicida = 10.0)
        val h = house(situation = Situation.F, treatment = treatment)
        coEvery { repository.insertHouse(any(), any()) } returns 1L

        useCase.insertHouse(h, emptyList())

        coVerify {
            repository.insertHouse(
                match {
                    it.treatment.a1 == 0 && it.treatment.b == 0 && it.treatment.larvicida == 0.0
                },
                any()
            )
        }
    }

    @Test
    fun `sanitize preserves treatment when situation is NONE`() = runBlocking {
        val treatment = TreatmentData(a1 = 5, b = 3, larvicida = 10.0)
        val h = house(situation = Situation.NONE, treatment = treatment)
        coEvery { repository.insertHouse(any(), any()) } returns 1L

        useCase.insertHouse(h, emptyList())

        coVerify {
            repository.insertHouse(
                match {
                    it.treatment.a1 == 5 && it.treatment.b == 3 && it.treatment.larvicida == 10.0
                },
                any()
            )
        }
    }

    // --- sanitize normalizes date format ---

    @Test
    fun `sanitize normalizes slash dates to dashes`() = runBlocking {
        val h = house(date = "25/05/2026")
        coEvery { repository.insertHouse(any(), any()) } returns 1L

        useCase.insertHouse(h, emptyList())

        coVerify {
            repository.insertHouse(match { it.data == "25-05-2026" }, any())
        }
    }

    // --- updateHouses ---

    @Test
    fun `updateHouses delegates to repository`() = runBlocking {
        val houses = listOf(house(id = 1), house(id = 2))

        useCase.updateHouses(houses, force = true)

        coVerify { repository.updateHouses(houses, true) }
    }
}
