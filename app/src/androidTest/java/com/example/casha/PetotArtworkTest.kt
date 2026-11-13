package com.example.casha

import android.animation.ValueAnimator
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.ui.*
import com.example.casha.ui.theme.CashaTheme
import org.junit.*
import org.junit.Assert.*
import java.io.File

class PetotArtworkTest {
    @get:Rule val compose = createComposeRule()
    private fun pictureHash(): Int {
        val bitmap = compose.onNodeWithContentDescription("Petot, your little money companion").captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.contentHashCode()
    }
    @Test fun spriteAnimatesAndMotionOffStaysStill() {
        Assume.assumeTrue(ValueAnimator.areAnimatorsEnabled())
        compose.mainClock.autoAdvance = false
        var motion by mutableStateOf(true)
        compose.setContent { CashaTheme { Surface { PetotCharacter(PetotMood.HELLO, motion) } } }
        compose.mainClock.advanceTimeByFrame()
        val first = pictureHash()
        compose.mainClock.advanceTimeBy(350)
        assertNotEquals(first, pictureHash())
        compose.runOnIdle { motion = false }
        compose.mainClock.advanceTimeBy(32)
        val still = pictureHash()
        compose.mainClock.advanceTimeBy(800)
        assertEquals(still, pictureHash())
    }
    @Test fun approvedPopupIsReadableInLightAndDarkThemes() {
        compose.mainClock.autoAdvance = false
        var dark by mutableStateOf(false)
        compose.setContent { CashaTheme(darkTheme=dark) { Surface(Modifier.fillMaxSize()) {
            PetotPopup(PetotReaction("Hi, I'm Petot!", "Your little money companion", PetotMood.CHEER, "artwork-test"), true) {}
        } } }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "phase_previews").apply { mkdirs() }
        for (theme in listOf("light", "dark")) {
            compose.runOnIdle { dark = theme == "dark" }
            compose.mainClock.advanceTimeBy(350)
            compose.onNodeWithText("Hi, I'm Petot!").assertIsDisplayed()
            compose.onNodeWithContentDescription("Dismiss Petot message").assertIsDisplayed()
            val bitmap = compose.onNodeWithTag("petot-popup").captureToImage().asAndroidBitmap()
            File(folder, "petot-approved-$theme.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun everyEmotionHasDistinctStillArtwork() {
        compose.mainClock.autoAdvance=false
        var mood by mutableStateOf(PetotMood.HELLO)
        compose.setContent {CashaTheme {Surface {PetotCharacter(mood,false)}}}
        val hashes=mutableSetOf<Int>()
        PetotMood.entries.forEach {next->
            compose.runOnIdle {mood=next}
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithContentDescription("Petot, your little money companion").assertExists()
            hashes+=pictureHash()
        }
        assertEquals(PetotMood.entries.size,hashes.size)
    }

    @Test fun savesEmotionGalleryForVisualReview() {
        compose.setContent {CashaTheme(darkTheme=true) {Surface {
            Column(Modifier.testTag("petot-emotion-gallery").padding(12.dp)) {
                Text("Petot's moods")
                PetotMood.entries.chunked(3).forEach { moods ->
                    Row {moods.forEach { mood ->
                        Column(Modifier.width(90.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            PetotCharacter(mood,false)
                            Text(mood.label)
                        }
                    }}
                }
            }
        }}}
        compose.onNodeWithText("Petot's moods").assertIsDisplayed()
        val bitmap=compose.onNodeWithTag("petot-emotion-gallery").captureToImage().asAndroidBitmap()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.filesDir,"phase_previews").apply {mkdirs()}
        File(folder,"petot-emotions-gallery.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun settingsCanPreviewLoveWithoutChangingFinancialRecords() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val scope="emotion_preview_${java.util.UUID.randomUUID()}"
        val finance=com.example.casha.data.FinanceStore(context,scope)
        val originalMotion=CompanionPreference.read(context).second
        CompanionPreference.save(context,"motion",false)
        try {
            compose.setContent {CashaTheme {SettingsScreen(com.example.casha.data.LoanStore(context,scope),finance)}}
            compose.onNodeWithText("Meet Petot").performClick()
            compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Love"))
            compose.onNodeWithText("Love").performClick()
            compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Preview Petot"))
            compose.onNodeWithText("Preview Petot").performClick()
            compose.onNodeWithText("Petot feels love").assertIsDisplayed()
            compose.onNodeWithContentDescription("Dismiss Petot message").performClick()
            compose.runOnIdle {assertTrue(finance.cashEntries().isEmpty())}
        } finally {
            CompanionPreference.save(context,"motion",originalMotion)
            context.deleteSharedPreferences("finance_$scope");context.deleteSharedPreferences(scope)
        }
    }
}
