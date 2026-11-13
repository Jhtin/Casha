package com.example.casha
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.LoanStore
import com.example.casha.domain.*
import com.example.casha.ui.CashaApp
import com.example.casha.ui.theme.CashaTheme
import java.io.File
import java.time.LocalDate
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
class LoanPaymentDialogTest {
 @get:Rule val compose=createComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val name="payment_ui_${UUID.randomUUID()}"
 private val store by lazy {LoanStore(context,name)}
 private fun loan()=Loan(id="payment-loan",party="Atome First Cash Loan",mode=LoanMode.I_OWE,total=340000,count=2,firstDue=LocalDate.now().plusDays(13),frequency=Frequency.MONTHLY)
 @After fun cleanup(){context.deleteSharedPreferences(name);context.deleteSharedPreferences("finance_$name")}
 private fun open(){compose.waitUntil(5000){compose.onAllNodesWithText("Atome First Cash Loan").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag("loan-list").performScrollToNode(hasText("Atome First Cash Loan"));compose.onAllNodesWithText("Atome First Cash Loan").onFirst().performClick();compose.waitUntil(5000){compose.onAllNodesWithText("Record payment").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("Record payment").performClick()}
 private fun picture(name:String){compose.waitForIdle();val folder=File(context.filesDir,"phase_previews").apply {mkdirs()};InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {b->File(folder,name).outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}}}
 @Test fun reviewDoesNotSaveAndInvalidDatesCanBeCorrected(){
  store.save(listOf(loan()));compose.setContent {CashaTheme(darkTheme=false){CashaApp(store,name)}};open()
  compose.onNodeWithText("Edit loan").assertDoesNotExist();compose.onNodeWithText("Delete loan").assertDoesNotExist();picture("payment-entry.png")
  compose.onNodeWithText("Payment (PHP)").performTextInput("100")
  compose.onNodeWithText("Payment date").performScrollTo().performTextReplacement(LocalDate.now().plusDays(1).toString())
  compose.onNodeWithText("Review payment").performClick()
  compose.onNodeWithText("Save payment").assertDoesNotExist()
  compose.runOnIdle {assertTrue(store.load().single().payments.isEmpty())}
  compose.onNodeWithText("Payment date").performScrollTo().performTextReplacement(LocalDate.now().toString())
  compose.onNodeWithText("Review payment").performClick()
  compose.onNodeWithText("Save payment").assertIsDisplayed();compose.runOnIdle {assertTrue(store.load().single().payments.isEmpty())}
  picture("payment-review.png")
  compose.onNodeWithText("Edit payment").performClick();compose.onNodeWithText("Payment (PHP)").assertTextContains("100")
  compose.onNodeWithText("Review payment").performClick();compose.onNodeWithText("Save payment").performClick()
  compose.waitUntil(5000){store.load().single().payments.size==1}
  compose.onNodeWithTag("loan-details-list").assertExists()
  compose.onNodeWithText("Record payment").performClick();compose.onNodeWithText("Cancel payment").performClick()
  compose.runOnIdle {assertEquals(1,store.load().single().payments.size);assertEquals(330000L,store.load().single().remaining)}
 }
 @Test fun aChangedLoanMustBeReviewedAgain(){
  val original=loan();store.save(listOf(original));compose.setContent {CashaTheme(darkTheme=false){CashaApp(store,name)}};open()
  compose.onNodeWithText("Payment (PHP)").performTextInput("100");compose.onNodeWithText("Review payment").performClick()
  compose.runOnIdle {assertTrue(store.save(listOf(original.copy(party="Updated lender")),listOf(original)))}
  compose.waitUntil(5000){compose.onAllNodesWithText("This loan changed.",substring=true).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Save payment").assertDoesNotExist();compose.runOnIdle {assertTrue(store.load().single().payments.isEmpty())}
  compose.onNodeWithText("Cancel payment").performClick()
 }
 @Test fun narrowDarkLargeTextKeepsReviewActionAndBalancesAccessible(){
  store.save(listOf(loan()));compose.setContent {CompositionLocalProvider(LocalDensity provides Density(3.375f,1.3f)){CashaTheme(darkTheme=true){CashaApp(store,name)}}};open()
  compose.onNodeWithText("Use full installment",substring=true).performScrollTo().performClick()
  compose.onNodeWithText("Review payment").assertIsDisplayed().performClick();compose.onNodeWithText("Save payment").assertIsDisplayed()
  compose.onNodeWithText("Loan balance after payment").performScrollTo().assertIsDisplayed();picture("payment-review-dark-large.png")
  compose.onNodeWithText("Edit payment").performClick();compose.onNodeWithText("Cancel payment").performClick();compose.runOnIdle {assertTrue(store.load().single().payments.isEmpty())}
 }
}
