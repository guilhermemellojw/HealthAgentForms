package com.antigravity.healthagent.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AbbreviationTest {

    @Test
    fun testFormatStreetName() {
        assertEquals("Rua RJ Centro", "RUA RJ CENTRO".formatStreetName())
        assertEquals("Joao de Souza", "JOAO DE SOUZA".formatStreetName())
        assertEquals("Avenida BR 101", "AVENIDA BR 101".formatStreetName())
        assertEquals("Rua Nicolau di Marcos", "RUA NICOLAU DI MARCOS".formatStreetName())
        assertEquals("Rua Simples", "Rua Simples".formatStreetName())
    }

    @Test
    fun testAbbreviateStreetPrefixes() {
        assertEquals("Av. Paulista", "Avenida Paulista".abbreviateStreetPrefixes())
        assertEquals("R. Augusta", "Rua Augusta".abbreviateStreetPrefixes())
        assertEquals("Dr. Arnaldo", "Doutor Arnaldo".abbreviateStreetPrefixes())
        assertEquals("Prof. Lucas", "Professor Lucas".abbreviateStreetPrefixes())
        assertEquals("Eng. Caio", "Engenheiro Caio".abbreviateStreetPrefixes())
        assertEquals("Al. Santos", "Alameda Santos".abbreviateStreetPrefixes())
        assertEquals("Est. Velha", "Estrada Velha".abbreviateStreetPrefixes())
        assertEquals("Rod. Regis", "Rodovia Regis".abbreviateStreetPrefixes())
        assertEquals("Tv. Beco", "Travessa Beco".abbreviateStreetPrefixes())
        assertEquals("Pç. Se", "Praça Se".abbreviateStreetPrefixes())
        // Case insensitive check
        assertEquals("Av. Paulista", "avenida Paulista".abbreviateStreetPrefixes())
    }

    @Test
    fun testAbbreviateMiddleNames() {
        // Without prefix
        assertEquals("Joao S. Silva", "Joao Souza Silva".abbreviateMiddleNames())
        assertEquals("Maria do Carmo", "Maria do Carmo".abbreviateMiddleNames()) // preposition handling check
        assertEquals("Maria do Carmo", "Maria do Carmo".formatStreetName().abbreviateMiddleNames()) // if formatStreetName downcases prepositions
        
        // With prefix
        assertEquals("R. Joao S. Silva", "R. Joao Souza Silva".abbreviateMiddleNames())
        assertEquals("Av. Maria do Carmo", "Av. Maria do Carmo".abbreviateMiddleNames()) // Should be Av. Maria do Carmo if preposition logic is correct

        // Edge cases
        assertEquals("Joao Silva", "Joao Silva".abbreviateMiddleNames()) // No middle name
        assertEquals("R. Joao Silva", "R. Joao Silva".abbreviateMiddleNames()) // Prefix + First + Last
        
        // Verify specific bug fix: "Rua Nicolau di Marcos" -> "R. Nicolau di Marcos" -> "R. Nicolau di Marcos" (preposition conserved)
        val formatted = "RUA NICOLAU DI MARCOS".formatStreetName()
        val abbrev = formatted.abbreviateStreetPrefixes()
        val final = abbrev.abbreviateMiddleNames()
        assertEquals("R. Nicolau di Marcos", final)
    }
}
