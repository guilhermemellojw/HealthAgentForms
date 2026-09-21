package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.DailyContext
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.domain.model.VisitAddress
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.data.repository.StreetRepository
import com.antigravity.healthagent.ui.home.HouseQueryHelper
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EndToEndEditingLifecycleTest {

    private val repository = mockk<HouseRepository>(relaxed = true)
    private val streetRepository = mockk<StreetRepository>(relaxed = true)
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
        observation: String = "",
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
        observation = observation,
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
    fun `full lifecycle insert edit validate close`() = runBlocking {
        // 1. INSERT
        val newHouse = house(id = 0, streetName = "RUA NOVA", number = "50", treatment = TreatmentData(a1 = 2, larvicida = 5.0))
        coEvery { repository.insertHouse(any(), any()) } returns 1L
        coEvery { repository.getHousesByDateAndAgent(any(), any()) } returns listOf(newHouse.copy(id = 1))

        val insertResult = saveHouseUseCase.insertHouse(newHouse, emptyList())
        assertEquals(1L, insertResult)

        // 2. EDIT (change number)
        val inserted = newHouse.copy(id = 1)
        val edited = inserted.copy(
            address = inserted.address.copy(number = "55"),
            treatment = TreatmentData(a1 = 3, larvicida = 7.0)
        )
        val sanitizedEdit = saveHouseUseCase.sanitizeHouse(edited)
        assertEquals("55", sanitizedEdit.address.number)
        assertEquals(3, sanitizedEdit.treatment.a1)

        // 3. VALIDATE
        val allHouses = listOf(sanitizedEdit)
        val result = validationUseCase.validateCurrentDay("25-05-2026", allHouses)
        assertTrue("Edited house should pass validation", result.isValid)
    }

    @Test
    fun `full lifecycle insert edit detect clash resolve`() {
        // 1. INSERT two houses
        val h1 = house(id = 1, number = "10")
        val h2 = house(id = 2, number = "20")

        // 2. EDIT h2 to same address as h1
        val edited = h2.copy(address = h2.address.copy(number = "10"))

        // 3. CLASH should be detected
        val allHouses = listOf(h1, edited)
        val clash = clashDetector.findClash(edited, allHouses.filter { it.id != edited.id })
        assertNotNull("Editing to same address should detect clash", clash)

        // 4. RESOLVE via autoIncrement
        val resolved = clashDetector.autoIncrementToAvoidClash(edited, allHouses.filter { it.id != edited.id })
        assertNotEquals("complement should be incremented", edited.address.complement, resolved.address.complement)

        // 5. VALIDATE resolved
        val finalHouses = listOf(h1, resolved)
        val result = validationUseCase.validateCurrentDay("25-05-2026", finalHouses)
        assertTrue("Resolved houses should pass validation", result.isValid)
    }

    @Test
    fun `full lifecycle insert move to new date validate both dates`() {
        // 1. Source date has 2 houses
        val h1 = house(id = 1, date = "25-05-2026", number = "10")
        val h2 = house(id = 2, date = "25-05-2026", number = "20")

        // 2. Move h1 to target date
        val moved = h1.copy(data = "26-05-2026")

        // 3. Source date: only h2 remains
        val sourceRemaining = listOf(h2)
        val sourceResult = validationUseCase.validateCurrentDay("25-05-2026", sourceRemaining)
        assertTrue("Source date should be valid", sourceResult.isValid)

        // 4. Target date: only moved h1
        val targetHouses = listOf(moved)
        val targetResult = validationUseCase.validateCurrentDay("26-05-2026", targetHouses)
        assertTrue("Target date should be valid", targetResult.isValid)
    }

    @Test
    fun `full lifecycle sanitize then generateHouseKey produces stable key`() {
        val raw = house(streetName = "  rua das flores  ", number = " 123a ")
        val sanitized = saveHouseUseCase.sanitizeHouse(raw)

        val key1 = HouseQueryHelper.generateHouseKey(sanitized)
        val key2 = HouseQueryHelper.generateHouseKey(sanitized)

        assertEquals("House key should be stable after sanitization", key1, key2)
    }

    @Test
    fun `full lifecycle two houses different complement pass validation`() {
        val h1 = house(id = 1, number = "10", complement = 0)
        val h2 = house(id = 2, number = "10", complement = 1)

        val result = validationUseCase.validateCurrentDay("25-05-2026", listOf(h1, h2))
        assertTrue("Different complements should pass validation", result.isValid)
    }

    @Test
    fun `full lifecycle edit preserves listOrder`() {
        val original = house(id = 1, listOrder = 42)
        val edited = original.copy(
            address = original.address.copy(number = "99"),
            lastUpdated = System.currentTimeMillis()
        )

        assertEquals(42L, edited.listOrder)
    }

    @Test
    fun `full lifecycle edit then clash check consistent behavior`() {
        val existing = listOf(
            house(id = 1, number = "10", streetName = "RUA A"),
            house(id = 2, number = "20", streetName = "RUA A"),
            house(id = 3, number = "10", streetName = "RUA B")
        )

        // Each house should not clash with itself
        existing.forEach { h ->
            val others = existing.filter { it.id != h.id }
            assertNull("House ${h.id} should not clash with others", clashDetector.findClash(h, others))
        }

        // New house at RUA A, 10 should clash
        val newHouse = house(id = 0, number = "10", streetName = "RUA A")
        assertNotNull(clashDetector.findClash(newHouse, existing))

        // New house at RUA C, 10 should not clash
        val newHouse2 = house(id = 0, number = "10", streetName = "RUA C")
        assertNull(clashDetector.findClash(newHouse2, existing))
    }

    @Test
    fun `full lifecycle closed day edit clears treatment correctly`() {
        val closedHouse = house(
            id = 1,
            situation = Situation.F,
            treatment = TreatmentData(a1 = 5, b = 3, larvicida = 10.0)
        )

        val sanitized = saveHouseUseCase.sanitizeHouse(closedHouse)

        assertEquals(0, sanitized.treatment.a1)
        assertEquals(0, sanitized.treatment.b)
        assertEquals(0.0, sanitized.treatment.larvicida, 0.001)
        assertEquals(Situation.F, sanitized.situation)
    }
}
