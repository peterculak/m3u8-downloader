package com.ta3.downloader

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class TyzdenScraperTest {

    @Test
    fun testFetchEpisodes() = runBlocking {
        // Find Bezpecnostny radar from models
        val show = TYZDEN_SHOWS.first { it.name == "bezpecnostny-radar" }
        
        // Fetch 1 page of episodes
        val episodes = TyzdenScraper.fetchEpisodes(show, maxPages = 1)
        
        assertTrue("Should fetch at least 1 episode", episodes.isNotEmpty())
        
        // Print and assert dates
        episodes.forEach { episode ->
            println("Title: ${episode.title}")
            println("Date: ${episode.date}")
            println("URL: ${episode.url}")
            println("---")
            assertTrue("Date should not be empty", episode.date.isNotEmpty())
            assertTrue("Date should match YYYY-MM-DD", episode.date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        }
    }
}
