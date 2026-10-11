package org.schabi.newpipe.util.dearrow

import java.util.Locale

/**
 * Turns a SHOUTING community title into sentence case, leaving acronyms and ordinary titles
 * alone.
 *
 * Only fires when at least half of the eligible words are already in caps, so "The truth about
 * NASA" is untouched while "THE TRUTH ABOUT NASA" becomes "The truth about NASA". A word stays
 * capitalised if it is a known acronym or has no vowel (GPU, HTML, NFL); an unlisted acronym is
 * merely lowercased.
 */
object DeArrowText {
    private val ACRONYMS = hashSetOf(
        "AI", "API", "AR", "VR", "CEO", "CPU", "GPU", "RAM", "SSD", "USB", "HDMI",
        "NASA", "ESA", "FBI", "CIA", "NSA", "UN", "EU", "UK", "USA", "US", "UAE",
        "NFL", "NBA", "MLB", "NHL", "FIFA", "UFC", "NCAA", "NATO",
        "HD", "FPS", "RPG", "FPV", "DIY", "ASMR", "IRL", "POV", "TV",
        "HTML", "CSS", "SQL", "JSON", "HTTP", "HTTPS", "PC", "OS", "SDK",
        "DNA", "RNA", "LED", "UFO", "GPS", "PDF", "USSR", "EV",
    )
    private const val MAX_ACRONYM_LENGTH = 5
    private const val SHOUTING_THRESHOLD = 0.5

    @JvmStatic
    fun autoFormat(title: String): String {
        val words = title.split(" ")
        var eligible = 0
        var shouting = 0
        for (word in words) {
            val core = coreOf(word)
            if (core.length < 2) continue
            eligible++
            if (core == core.uppercase(Locale.ROOT)) shouting++
        }
        if (eligible == 0 || shouting.toDouble() / eligible < SHOUTING_THRESHOLD) return title

        val out = StringBuilder(title.length)
        var firstWordWritten = false
        for (i in words.indices) {
            if (i > 0) out.append(' ')
            val word = words[i]
            val core = coreOf(word)
            if (core.isEmpty() || core != core.uppercase(Locale.ROOT)) {
                out.append(word)
                firstWordWritten = firstWordWritten || core.isNotEmpty()
                continue
            }
            if (isAcronym(core)) {
                // Keep the acronym, but lowercase a possessive: NASA'S -> NASA's.
                out.append(word.replace("'S", "'s").replace("\u2019S", "\u2019s"))
                firstWordWritten = true
                continue
            }
            val lowered = word.lowercase(Locale.ROOT)
            out.append(
                if (firstWordWritten) lowered
                else lowered[0].uppercaseChar() + lowered.substring(1)
            )
            firstWordWritten = true
        }
        return out.toString()
    }

    /** Strips surrounding punctuation and a possessive, so "NASA'S and NASA are judged alike. */
    private fun coreOf(word: String): String {
        var start = 0
        var end = word.length
        while (start < end && !word[start].isLetter()) start++
        while (end > start && !word[end - 1].isLetter()) end--
        val trimmed = word.substring(start, end)
        return if (trimmed.length > 2 && (
                trimmed.endsWith("'S") || trimmed.endsWith("\u2019S")
                    || trimmed.endsWith("'s") || trimmed.endsWith("\u2019s")
                )
        ) {
            trimmed.substring(0, trimmed.length - 2)
        } else {
            trimmed
        }
    }

    private fun isAcronym(core: String): Boolean {
        if (core in ACRONYMS) return true
        if (core.length > MAX_ACRONYM_LENGTH) return false
        // No vowels means it cannot be read as an English word: GPU, HTML, NFL, TV.
        return core.none { it in "AEIOUY" }
    }
}
