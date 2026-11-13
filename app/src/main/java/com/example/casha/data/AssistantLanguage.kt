package com.example.casha.data

enum class AssistantLanguage(
    val code: String,
    val label: String,
    val description: String,
    val voiceLocale: String,
    val composerLabel: String,
    val composerPlaceholder: String,
    val promptInstruction: String
) {
    ENGLISH(
        code = "en",
        label = "English",
        description = "Direct and clear English responses",
        voiceLocale = "en-US",
        composerLabel = "English command or question",
        composerPlaceholder = "Ask Petot or log an expense…",
        promptInstruction = "Write the 'explanation' field entirely in clear, natural English. Do not use Tagalog or Filipino words."
    ),
    TAGALOG(
        code = "tl",
        label = "Tagalog / Taglish",
        description = "Conversational Tagalog & Taglish as spoken in the Philippines",
        voiceLocale = "fil-PH",
        composerLabel = "Taglish command or question",
        composerPlaceholder = "Magtanong kay Petot o mag-log ng gastos…",
        promptInstruction = "Write the 'explanation' field in warm, natural Tagalog or Taglish as commonly spoken in the Philippines."
    );

    companion object {
        val DEFAULT = ENGLISH
    }
}
