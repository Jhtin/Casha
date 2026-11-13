package com.example.casha

import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.ui.*
import com.example.casha.ui.theme.CashaTheme
import com.example.casha.data.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class PetotNotificationTest {
 @get:Rule val compose=createComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private var originalSound=true
 private val name="petot_notification_${UUID.randomUUID()}"
 @Before fun setup(){originalSound=companionSoundEnabled(context);CompanionPreference.save(context,"sound",false)}
 @After fun cleanup(){CompanionPreference.save(context,"sound",originalSound);context.deleteSharedPreferences(name);context.deleteSharedPreferences("finance_$name")}
 private fun verifyFiveSeconds(mood:PetotMood){
  compose.mainClock.autoAdvance=false
  var showing by mutableStateOf(true)
  var touches by mutableIntStateOf(0)
  compose.setContent {CashaTheme {Box(Modifier.fillMaxSize()) {
   Button({touches++},Modifier.align(Alignment.BottomCenter).navigationBarsPadding()){Text("Touch elsewhere")}
   if(showing)PetotPopup(PetotReaction("Test message","A short friendly note",mood,"event-one"),false){showing=false}
  }}}
  compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
  compose.onNodeWithText("Touch elsewhere").performClick();compose.mainClock.advanceTimeByFrame()
  compose.runOnIdle {assertEquals(1,touches)}
  compose.onNodeWithTag("petot-popup").assertExists()
  compose.mainClock.advanceTimeBy(4000);compose.onNodeWithTag("petot-popup").assertExists()
  compose.mainClock.advanceTimeBy(850);compose.onNodeWithTag("petot-popup").assertExists()
  compose.mainClock.advanceTimeBy(250);compose.onNodeWithTag("petot-popup").assertDoesNotExist()
 }
 @Test fun cheerfulMessageSurvivesOutsideTouchForFiveSeconds()=verifyFiveSeconds(PetotMood.CHEER)
 @Test fun warningAlsoUsesFiveSeconds()=verifyFiveSeconds(PetotMood.CARE)
 @Test fun aNewIdenticalMessageGetsItsOwnFiveSeconds(){
  compose.mainClock.autoAdvance=false
  var reaction by mutableStateOf<PetotReaction?>(PetotReaction("Same message","Same body",PetotMood.CHEER,"one"))
  compose.setContent {CashaTheme {Box(Modifier.fillMaxSize()) {
   Button({reaction=PetotReaction("Same message","Same body",PetotMood.CHEER,"two")},Modifier.align(Alignment.BottomCenter).navigationBarsPadding()){Text("New message")}
   reaction?.let {shown->PetotPopup(shown,false){if(reaction?.eventId==shown.eventId)reaction=null}}
  }}}
  compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeBy(4000)
  compose.onNodeWithText("New message").performClick();compose.mainClock.advanceTimeByFrame()
  compose.mainClock.advanceTimeBy(1000);compose.onNodeWithTag("petot-popup").assertExists()
  compose.mainClock.advanceTimeBy(3500);compose.onNodeWithTag("petot-popup").assertExists()
  compose.mainClock.advanceTimeBy(600);compose.onNodeWithTag("petot-popup").assertDoesNotExist()
 }
 @Test fun xDismissesImmediately(){
  var showing by mutableStateOf(true)
  compose.setContent {CashaTheme {if(showing)PetotPopup(PetotReaction("Close me","Tap the X",PetotMood.CHEER,"close"),false){showing=false}}}
  compose.onNodeWithContentDescription("Dismiss Petot message").performClick();compose.onNodeWithTag("petot-popup").assertDoesNotExist()
 }
 @Test fun soundChoiceIsAvailableAndPersisted(){
  compose.setContent {CashaTheme {SettingsScreen(LoanStore(context,name),FinanceStore(context,name),accountContent={})}}
  compose.onNodeWithText("Meet Petot").performClick()
  compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Companion sounds"))
  compose.onNodeWithText("Companion sounds").performClick()
  compose.runOnIdle {assertTrue(companionSoundEnabled(context))}
  compose.onNodeWithText("Companion sounds").performClick()
  compose.runOnIdle {assertFalse(companionSoundEnabled(context))}
 }
 @Test fun moodSoundsAreDifferentPlayableShortResources(){
  val cheer=petotSoundResource(PetotMood.CHEER)!!;val care=petotSoundResource(PetotMood.CARE)!!
  assertNotEquals(cheer,care);assertNull(petotSoundResource(PetotMood.HELLO))
  for(id in listOf(cheer,care)){
   val player=MediaPlayer.create(context,id)
   assertNotNull(player)
   try {assertTrue(player.duration in 300..700)}finally {player.release()}
  }
 }
}
