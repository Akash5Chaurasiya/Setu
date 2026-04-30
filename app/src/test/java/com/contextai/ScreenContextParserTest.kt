package com.contextai

import com.contextai.core.accessibility.ScreenContextExtractor
import com.contextai.domain.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScreenContextParserTest {

    private lateinit var extractor: ScreenContextExtractor

    @Before
    fun setup() {
        extractor = ScreenContextExtractor()
    }

    @Test
    fun `extract returns GENERIC type for empty text`() {
        val ctx = extractor.extract(null, "com.example.app", "Example", "MainActivity")
        assertEquals(ContextType.GENERIC, ctx.detectedType)
        assertTrue(ctx.isEmpty())
    }

    @Test
    fun `detects job post by package name linkedin`() {
        val ctx = extractor.extract(null, "com.linkedin.android", "LinkedIn", "FeedActivity")
        assertEquals(ContextType.JOB_POST, ctx.detectedType)
    }

    @Test
    fun `detects food order by package name zomato`() {
        val ctx = extractor.extract(null, "com.application.zomato", "Zomato", "CartActivity")
        assertEquals(ContextType.FOOD_ORDER, ctx.detectedType)
    }

    @Test
    fun `detects travel by package makemytrip`() {
        val ctx = extractor.extract(null, "com.makemytrip", "MakeMyTrip", "FlightSearchActivity")
        assertEquals(ContextType.TRAVEL, ctx.detectedType)
    }

    @Test
    fun `email regex matches valid email addresses`() {
        val ctx = extractor.extract(null, "com.example", "Example", "")
        val emailRegex = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")
        assertTrue(emailRegex.matches("user@example.com"))
        assertTrue(emailRegex.matches("hr@google.com"))
        assertFalse(emailRegex.matches("notanemail"))
        assertFalse(emailRegex.matches("missing@"))
    }

    @Test
    fun `ScreenContext isEmpty returns true when rawText blank`() {
        val ctx = extractor.extract(null, "com.test", "Test", "")
        assertTrue(ctx.isEmpty())
    }

    @Test
    fun `toSystemPrompt contains app name and context type`() {
        val ctx = extractor.extract(null, "com.linkedin.android", "LinkedIn", "JobActivity")
        val prompt = ctx.toSystemPrompt()
        assertTrue(prompt.contains("LinkedIn"))
        assertTrue(prompt.contains(ContextType.JOB_POST.displayName))
    }
}
