package com.ta3.downloader

import org.junit.Test
import org.junit.Assert.*

class YouTubeScraperTest {

    @Test
    fun parseA11yDuration() {
        val label1 = "Do živého | Keď civilizácia kolabuje 1 hour, 5 minutes"
        val label2 = "7 dní v kocke: Hlavným problémom nie je Trump, ale Izrael 17 minutes"
        val label3 = "16 minutes, 58 seconds"
        val label4 = "1 hour, 21 minutes, 35 seconds"
        val label5 = "45 seconds"

        assertEquals(3900, parseA11yDurationToSeconds(label1))
        assertEquals(1020, parseA11yDurationToSeconds(label2))
        assertEquals(1018, parseA11yDurationToSeconds(label3))
        assertEquals(4895, parseA11yDurationToSeconds(label4))
        assertEquals(45, parseA11yDurationToSeconds(label5))
    }

    private fun parseA11yDurationToSeconds(text: String): Int {
        var total = 0
        val hMatch = Regex("""(\d+)\s+hour""").find(text)
        if (hMatch != null) total += hMatch.groupValues[1].toInt() * 3600
        
        val mMatch = Regex("""(\d+)\s+minute""").find(text)
        if (mMatch != null) total += mMatch.groupValues[1].toInt() * 60
        
        val sMatch = Regex("""(\d+)\s+second""").find(text)
        if (sMatch != null) total += sMatch.groupValues[1].toInt()
        
        return total
    }
}
