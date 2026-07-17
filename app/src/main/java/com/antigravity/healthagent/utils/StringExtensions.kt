package com.antigravity.healthagent.utils

import java.util.Locale
import android.graphics.Paint

/**
 * Formats a street name according to the following rules:
 * - Each word starts with an uppercase letter (Title Case).
 * - Words with 2 letters are fully uppercased (e.g., RJ), EXCEPT prepositions.
 * - Prepositions (de, da, do, das, dos, e) are always lowercase.
 * 
 * Example: "RUA RJ CENTRO" -> "Rua RJ Centro"
 * Example: "JOAO DE SOUZA" -> "Joao de Souza"
 * Example: "AVENIDA BR 101" -> "Avenida BR 101"
 */
private val LOWER_CASE_PREPOSITIONS = setOf("de", "da", "do", "das", "dos", "e", "di")

fun String.formatStreetName(): String {
    if (this.isBlank()) return this
    
    return this.lowercase()
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.split("-").joinToString("-") { part ->
                when {
                    part in LOWER_CASE_PREPOSITIONS -> part
                    part.length == 2 -> part.uppercase(Locale.getDefault())
                    else -> part.replaceFirstChar { 
                        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() 
                    }
                }
            }
        }
}

fun String.abbreviateStreetPrefixes(): String {
    var result = this
    val replacements = mapOf(
        "Avenida" to "Av.",
        "Rua" to "R.",
        "Doutor" to "Dr.",
        "Professor" to "Prof.",
        "Engenheiro" to "Eng.",
        "Alameda" to "Al.",
        "Estrada" to "Est.",
        "Rodovia" to "Rod.",
        "Travessa" to "Tv.",
        "Praça" to "Pç.",
        "Coronel" to "Cel.",
        "General" to "Gen.",
        "Major" to "Maj.",
        "Capitão" to "Cap.",
        "Tenente" to "Ten.",
        "Sargento" to "Sgt."
    )

    replacements.forEach { (full, abbr) ->
        // Replace full word match only, Case Insensitive
        result = result.replace(Regex("\\b$full\\b", RegexOption.IGNORE_CASE), abbr)
    }
    return result
}

fun String.abbreviateMiddleNames(): String {
    val words = this.split(" ").filter { it.isNotBlank() }
    if (words.size <= 2) return this // Nothing to abbreviate if only First and Last name or less

    // Check if the first word is a known prefix (full or abbreviated)
    // We include common ones used in abbreviateStreetPrefixes
    val prefixes = setOf(
        "Av.", "R.", "Dr.", "Prof.", "Eng.", "Al.", "Est.", "Rod.", "Tv.", "Pç.", 
        "Cel.", "Gen.", "Maj.", "Cap.", "Ten.", "Sgt.",
        "Avenida", "Rua", "Doutor", "Professor", "Engenheiro", "Alameda", "Estrada", 
        "Rodovia", "Travessa", "Praça", "Coronel", "General", "Major", "Capitão", 
        "Tenente", "Sargento"
    )

    val firstWord = words.first()
    // Normalize check (remove dot if present just in case, though set has both)
    // Actually exact match is safer given the set
    val isPrefix = prefixes.any { it.equals(firstWord, ignoreCase = true) } || firstWord.endsWith(".")

    // Determine where the "Name" starts.
    // Index 0 is Prefix if isPrefix is true.
    // If Size > 2 and isPrefix is true:
    // e.g. "R. Name Last" (size 3) -> Prefix=R., First=Name, Last=Last. Middle empty.
    // e.g. "R. Name Middle Last" (size 4) -> Prefix=R., First=Name, Middle=Middle, Last=Last.
    
    var startIndex = 0
    if (isPrefix && words.size > 2) {
        startIndex = 1
    }

    val firstName = words[startIndex]
    val lastName = words.last()
    
    // If we only have [Prefix, First, Last] or [First, Last], there are no middle names to abbreviate.
    // indices: startIndex (First), size-1 (Last).
    // Middle is (startIndex + 1) until (size - 1)
    
    if (startIndex + 1 >= words.size - 1) {
        // No middle names
        return if (startIndex > 0) {
            "${words[0]} $firstName $lastName" 
        } else {
            "$firstName $lastName"
        }
    }

    val middle = words.subList(startIndex + 1, words.size - 1)

    val abbreviatedMiddle = middle.joinToString(" ") { word ->
        if (LOWER_CASE_PREPOSITIONS.contains(word.lowercase())) {
            word // Keep prepositions as is
        } else {
            "${word.first()}." // Abbreviate others
        }
    }

    return if (startIndex > 0) {
        "${words[0]} $firstName $abbreviatedMiddle $lastName"
    } else {
        "$firstName $abbreviatedMiddle $lastName"
    }
}

/**
 * Fits a string into a given width by trying progressively shorter abbreviations.
 * 
 * Strategy:
 * 1. Original Text
 * 2. Prefix Abbreviation (Av., R., etc.)
 * 3. Middle Name Abbreviation (Av. J. Silva)
 * 4. Truncation with Ellipsis (Av. J. Si...)
 */
fun String.fitToWidth(paint: android.graphics.Paint, maxWidth: Float): String {
    val original = this
    
    // 1. Check Original
    if (paint.measureText(original) <= maxWidth) return original
    
    // 2. Check Prefix Abbreviation
    val prefixAbbr = original.abbreviateStreetPrefixes()
    if (paint.measureText(prefixAbbr) <= maxWidth) return prefixAbbr
    
    // 3. Check Middle Name Abbreviation
    val middleAbbr = prefixAbbr.abbreviateMiddleNames()
    if (paint.measureText(middleAbbr) <= maxWidth) return middleAbbr
    
    // 4. Fallback: Truncate with Ellipsis
    val ellipsis = "..."
    val availForText = maxWidth - paint.measureText(ellipsis)
    
    if (availForText <= 0) {
        // Extremely narrow space, return ellipsis or just first char
        return if (maxWidth > paint.measureText(".")) "." else ""
    }
    
    // Measure how many chars fit from the shortest candidate (middleAbbr)
    // We use middleAbbr as the base heavily abbreviated string to truncate from
    val measuredCount = paint.breakText(middleAbbr, true, availForText, null)
    
    return if (measuredCount < middleAbbr.length) {
        middleAbbr.substring(0, measuredCount) + ellipsis
    } else {
        middleAbbr // Should have been caught by check 3, but safe fallback
    }
}

private val NORMALIZE_WHITESPACE_REGEX = Regex("\\s+")
private val NORMALIZE_DASH_REGEX = Regex("-+")

fun String.normalize(): String {
    // SURGICAL FIX: Stop stripping accents! We only want to standardize separators and casing.
    return this.trim()
        .replace("/", "-")
        .replace(".", "-")
        .replace(NORMALIZE_WHITESPACE_REGEX, " ")
        .replace(NORMALIZE_DASH_REGEX, "-")
        .uppercase()
}

/**
 * Strips diacritical marks (accents) from a string.
 * Use ONLY for accent-insensitive comparisons or search filters.
 */
fun String.removeAccents(): String {
    val normalized = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
    return Regex("\\p{InCombiningDiacriticalMarks}+").replace(normalized, "")
}

/**
 * Parses a date string supporting both dd-MM-yyyy and yyyy-MM-dd (with slash or dash)
 * into a comparable numeric representation (yyyyMMdd).
 */
fun String.toNumericDate(): Long? {
    if (this.isBlank()) return null
    val clean = this.replace("/", "-").trim()
    val parts = clean.split("-")
    if (parts.size != 3) return null
    return try {
        val part0 = parts[0].toInt()
        val part1 = parts[1].toInt()
        val part2 = parts[2].toInt()
        if (part0 > 1000) {
            // yyyy-MM-dd
            part0.toLong() * 10000 + part1.toLong() * 100 + part2.toLong()
        } else if (part2 > 1000) {
            // dd-MM-yyyy
            part2.toLong() * 10000 + part1.toLong() * 100 + part0.toLong()
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Resolves small neighborhood name discrepancies (spelling, casing, accents) by matching
 * against master lists.
 */
fun String.healBairro(): String {
    val normalized = this.normalize()
    return com.antigravity.healthagent.utils.AppConstants.BAIRROS.find {
        it.removeAccents().equals(normalized.removeAccents(), ignoreCase = true)
    } ?: normalized
}

/**
 * Resolves small agent name discrepancies by matching against master constants.
 */
fun String.healAgentName(): String {
    val normalized = this.normalize()
    return com.antigravity.healthagent.utils.AppConstants.AGENT_NAMES.find {
        it.removeAccents().equals(normalized.removeAccents(), ignoreCase = true)
    } ?: normalized
}

