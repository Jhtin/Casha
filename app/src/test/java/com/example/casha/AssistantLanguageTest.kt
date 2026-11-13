package com.example.casha

import com.example.casha.data.AssistantLanguage
import org.junit.Assert.*
import org.junit.Test

class AssistantLanguageTest {
    @Test
    fun assistantLanguagePropertiesAreConfigured() {
        assertEquals(AssistantLanguage.ENGLISH, AssistantLanguage.DEFAULT)
        assertEquals("en", AssistantLanguage.ENGLISH.code)
        assertEquals("tl", AssistantLanguage.TAGALOG.code)
        assertEquals("en-US", AssistantLanguage.ENGLISH.voiceLocale)
        assertEquals("fil-PH", AssistantLanguage.TAGALOG.voiceLocale)
        assertTrue(AssistantLanguage.ENGLISH.composerLabel.contains("English"))
        assertTrue(AssistantLanguage.TAGALOG.composerLabel.contains("Taglish"))
        assertTrue(AssistantLanguage.ENGLISH.promptInstruction.contains("English"))
        assertTrue(AssistantLanguage.TAGALOG.promptInstruction.contains("Tagalog"))
    }
}
