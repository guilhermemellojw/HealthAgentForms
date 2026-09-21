package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.VisitAddress
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CheckWorkedHouseLimitUseCaseTest {

    private lateinit var useCase: CheckWorkedHouseLimitUseCase

    @Before
    fun setup() {
        useCase = CheckWorkedHouseLimitUseCase()
    }

    private fun house(
        id: Int = 0,
        situation: Situation = Situation.EMPTY,
        data: String = "15-06-2026"
    ) = House(
        id = id,
        situation = situation,
        data = data,
        address = VisitAddress(number = "$id"),
        agentName = "AGENTE",
        agentUid = "uid_1"
    )

    @Test
    fun `invoke returns Allowed when house situation is F`() {
        val result = useCase(
            house = house(situation = Situation.F),
            original = null,
            dayHouses = emptyList(),
            currentDate = "15-06-2026",
            maxOpenHouses = 5,
            isAdmin = false,
            isManualUnlock = false
        )
        assertTrue(result is CheckWorkedHouseLimitUseCase.Result.Allowed)
    }

    @Test
    fun `invoke returns Allowed when house situation is REC`() {
        val result = useCase(
            house = house(situation = Situation.REC),
            original = null,
            dayHouses = emptyList(),
            currentDate = "15-06-2026",
            maxOpenHouses = 5,
            isAdmin = false,
            isManualUnlock = false
        )
        assertTrue(result is CheckWorkedHouseLimitUseCase.Result.Allowed)
    }

    @Test
    fun `invoke returns Allowed when house situation is V`() {
        val result = useCase(
            house = house(situation = Situation.V),
            original = null,
            dayHouses = emptyList(),
            currentDate = "15-06-2026",
            maxOpenHouses = 5,
            isAdmin = false,
            isManualUnlock = false
        )
        assertTrue(result is CheckWorkedHouseLimitUseCase.Result.Allowed)
    }

    @Test
    fun `invoke returns Allowed when original was already worked`() {
        val result = useCase(
            house = house(situation = Situation.NONE),
            original = house(id = 1, situation = Situation.NONE),
            dayHouses = listOf(house(id = 2, situation = Situation.NONE)),
            currentDate = "15-06-2026",
            maxOpenHouses = 1,
            isAdmin = false,
            isManualUnlock = false
        )
        assertTrue(result is CheckWorkedHouseLimitUseCase.Result.Allowed)
    }

    @Test
    fun `invoke returns Allowed when workedCount equals maxOpenHouses`() {
        val result = useCase(
            house = house(situation = Situation.NONE),
            original = null,
            dayHouses = listOf(house(id = 1, situation = Situation.NONE)),
            currentDate = "15-06-2026",
            maxOpenHouses = 1,
            isAdmin = false,
            isManualUnlock = false
        )
        assertTrue(result is CheckWorkedHouseLimitUseCase.Result.Allowed)
    }

    @Test
    fun `invoke returns LimitExceeded when workedCount exceeds maxOpenHouses`() {
        val result = useCase(
            house = house(situation = Situation.NONE),
            original = null,
            dayHouses = listOf(
                house(id = 1, situation = Situation.NONE),
                house(id = 2, situation = Situation.NONE)
            ),
            currentDate = "15-06-2026",
            maxOpenHouses = 1,
            isAdmin = false,
            isManualUnlock = false
        )
        assertTrue(result is CheckWorkedHouseLimitUseCase.Result.LimitExceeded)
    }

    @Test
    fun `invoke returns Allowed when maxOpenHouses is 0`() {
        val result = useCase(
            house = house(situation = Situation.NONE),
            original = null,
            dayHouses = listOf(
                house(id = 1, situation = Situation.NONE),
                house(id = 2, situation = Situation.NONE)
            ),
            currentDate = "15-06-2026",
            maxOpenHouses = 0,
            isAdmin = false,
            isManualUnlock = false
        )
        assertTrue(result is CheckWorkedHouseLimitUseCase.Result.Allowed)
    }

    @Test
    fun `invoke returns Allowed when isAdmin is true`() {
        val result = useCase(
            house = house(situation = Situation.NONE),
            original = null,
            dayHouses = listOf(
                house(id = 1, situation = Situation.NONE),
                house(id = 2, situation = Situation.NONE)
            ),
            currentDate = "15-06-2026",
            maxOpenHouses = 1,
            isAdmin = true,
            isManualUnlock = false
        )
        assertTrue(result is CheckWorkedHouseLimitUseCase.Result.Allowed)
    }

    @Test
    fun `invoke returns Allowed when isManualUnlock is true`() {
        val result = useCase(
            house = house(situation = Situation.NONE),
            original = null,
            dayHouses = listOf(
                house(id = 1, situation = Situation.NONE),
                house(id = 2, situation = Situation.NONE)
            ),
            currentDate = "15-06-2026",
            maxOpenHouses = 1,
            isAdmin = false,
            isManualUnlock = true
        )
        assertTrue(result is CheckWorkedHouseLimitUseCase.Result.Allowed)
    }

    @Test
    fun `invoke counts only NONE and EMPTY situations`() {
        val dayHouses = listOf(
            house(id = 1, situation = Situation.NONE),
            house(id = 2, situation = Situation.EMPTY),
            house(id = 3, situation = Situation.F),
            house(id = 4, situation = Situation.REC),
            house(id = 5, situation = Situation.V)
        )
        val result = useCase(
            house = house(situation = Situation.NONE),
            original = null,
            dayHouses = dayHouses,
            currentDate = "15-06-2026",
            maxOpenHouses = 1,
            isAdmin = false,
            isManualUnlock = false
        )
        assertTrue(result is CheckWorkedHouseLimitUseCase.Result.LimitExceeded)
    }

    @Test
    fun `invoke returns Allowed when house date does not match currentDate`() {
        val result = useCase(
            house = house(situation = Situation.NONE, data = "10-06-2026"),
            original = null,
            dayHouses = listOf(house(id = 1, situation = Situation.NONE, data = "15-06-2026")),
            currentDate = "15-06-2026",
            maxOpenHouses = 0,
            isAdmin = false,
            isManualUnlock = false
        )
        assertTrue(result is CheckWorkedHouseLimitUseCase.Result.Allowed)
    }

    @Test
    fun `invoke returns Allowed when dayHouses is empty`() {
        val result = useCase(
            house = house(situation = Situation.NONE),
            original = null,
            dayHouses = emptyList(),
            currentDate = "15-06-2026",
            maxOpenHouses = 5,
            isAdmin = false,
            isManualUnlock = false
        )
        assertTrue(result is CheckWorkedHouseLimitUseCase.Result.Allowed)
    }
}
