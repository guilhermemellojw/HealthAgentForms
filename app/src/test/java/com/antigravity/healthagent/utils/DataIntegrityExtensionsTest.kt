package com.antigravity.healthagent.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataIntegrityExtensionsTest {

    @Test
    fun testToNumericDate_withDashes() {
        assertEquals(20260519L, "19-05-2026".toNumericDate())
        assertEquals(20260519L, "2026-05-19".toNumericDate())
    }

    @Test
    fun testToNumericDate_withSlashes() {
        assertEquals(20260519L, "19/05/2026".toNumericDate())
        assertEquals(20260519L, "2026/05/19".toNumericDate())
    }

    @Test
    fun testToNumericDate_withSpacesAndTrimming() {
        assertEquals(20260519L, "  19-05-2026  ".toNumericDate())
        assertEquals(20260519L, " 2026-05-19 ".toNumericDate())
    }

    @Test
    fun testToNumericDate_invalidInputs() {
        assertNull("invalid-date".toNumericDate())
        assertNull("19-05".toNumericDate())
        assertNull("".toNumericDate())
    }

    @Test
    fun testHealBairro() {
        // "CENTRO" should map to "CENTRO" from AppConstants
        assertEquals("CENTRO", "centro".healBairro())
        assertEquals("CENTRO", "  CENTRO  ".healBairro())
        
        // "ALTO SÃO JOSÉ I" has accents; "alto sao jose i" should heal to "ALTO SÃO JOSÉ I"
        assertEquals("ALTO SÃO JOSÉ I", "alto sao jose i".healBairro())
        assertEquals("ALTO SÃO JOSÉ I", "ALTO SAO JOSÉ I".healBairro())

        // Non-existent neighborhood should return its normalized self
        assertEquals("BAIRRO INEXISTENTE", "Bairro Inexistente".healBairro())
    }

    @Test
    fun testHealAgentName() {
        // "GUILHERME MELLO" from AppConstants
        assertEquals("GUILHERME MELLO", "guilherme mello".healAgentName())
        assertEquals("GUILHERME MELLO", "Guilherme Mello".healAgentName())
        
        // "FLÁVIO CALDEIRA" from AppConstants
        assertEquals("FLÁVIO CALDEIRA", "flavio caldeira".healAgentName())
        assertEquals("FLÁVIO CALDEIRA", "FLÁVIO CALDEIRA".healAgentName())

        // Non-existent agent should return normalized self
        assertEquals("AGENTE INEXISTENTE", "agente inexistente".healAgentName())
    }
}
