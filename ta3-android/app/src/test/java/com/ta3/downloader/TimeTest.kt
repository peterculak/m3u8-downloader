package com.ta3.downloader

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import com.google.gson.JsonParser

class TimeTest {
    @Test
    fun testParseRelativeDate() {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val referenceCal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.JULY, 19) // 2026-07-19
        }
        val refDateStr = sdf.format(referenceCal.time)
        assertEquals("2026-07-19", refDateStr)

        // Test Slovak representations
        assertEquals("2026-07-19", YouTubeScraper.parseRelativeDate("pred 8 hodinami", referenceCal.clone() as java.util.Calendar))
        assertEquals("2026-07-19", YouTubeScraper.parseRelativeDate("pred 45 minútami", referenceCal.clone() as java.util.Calendar))
        assertEquals("2026-07-18", YouTubeScraper.parseRelativeDate("včera", referenceCal.clone() as java.util.Calendar))
        assertEquals("2026-07-17", YouTubeScraper.parseRelativeDate("predvčerom", referenceCal.clone() as java.util.Calendar))
        assertEquals("2026-07-17", YouTubeScraper.parseRelativeDate("pred 2 dňami", referenceCal.clone() as java.util.Calendar))
        assertEquals("2026-07-14", YouTubeScraper.parseRelativeDate("pred 5 dňami", referenceCal.clone() as java.util.Calendar))
        assertEquals("2026-07-05", YouTubeScraper.parseRelativeDate("pred 2 týždňami", referenceCal.clone() as java.util.Calendar))
        
        // Month subtraction (19th July - 1 month = 19th June)
        assertEquals("2026-06-19", YouTubeScraper.parseRelativeDate("pred 1 mesiacom", referenceCal.clone() as java.util.Calendar))
        
        // Year subtraction
        assertEquals("2025-07-19", YouTubeScraper.parseRelativeDate("pred 1 rokom", referenceCal.clone() as java.util.Calendar))
        
        // English representations
        assertEquals("2026-07-19", YouTubeScraper.parseRelativeDate("8 hours ago", referenceCal.clone() as java.util.Calendar))
        assertEquals("2026-07-18", YouTubeScraper.parseRelativeDate("1 day ago", referenceCal.clone() as java.util.Calendar))
        assertEquals("2026-07-16", YouTubeScraper.parseRelativeDate("3 days ago", referenceCal.clone() as java.util.Calendar))
        assertEquals("2026-06-19", YouTubeScraper.parseRelativeDate("1 month ago", referenceCal.clone() as java.util.Calendar))
        assertEquals("2025-07-19", YouTubeScraper.parseRelativeDate("1 year ago", referenceCal.clone() as java.util.Calendar))
        
        // Edge cases
        assertEquals("2026-07-19", YouTubeScraper.parseRelativeDate("Streamed 3 hours ago", referenceCal.clone() as java.util.Calendar))
        assertEquals("2026-07-19", YouTubeScraper.parseRelativeDate("Premiéra pred 10 hodinami", referenceCal.clone() as java.util.Calendar))
        
        // Invalid or fallback cases should return 1970-01-01
        assertEquals("1970-01-01", YouTubeScraper.parseRelativeDate("Televízia JOJ a JOJ 24", referenceCal.clone() as java.util.Calendar))
    }
}
