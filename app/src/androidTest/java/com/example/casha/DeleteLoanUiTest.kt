package com.example.casha
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.*
import com.example.casha.domain.*
import com.example.casha.ui.CashaApp
import com.example.casha.ui.theme.CashaTheme
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.util.UUID
class DeleteLoanUiTest {
 @get:Rule val compose=createComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val name="delete_ui_${UUID.randomUUID()}"
 private val store by lazy {LoanStore(context,name)}
 private fun loan()=Loan(id="mistake",party="Mistaken loan",mode=LoanMode.I_OWE,total=10000,count=1,firstDue=LocalDate.now(),frequency=Frequency.MONTHLY)
 @After fun cleanup(){context.deleteSharedPreferences(name);context.deleteSharedPreferences("finance_$name")}
 @Test fun cancelKeepsLoanAndConfirmationDeletesIt(){
  val original=loan();store.save(listOf(original))
  compose.setContent {CashaTheme {CashaApp(store,name)}}
  compose.waitUntil(5000){compose.onAllNodesWithText("Mistaken loan").fetchSemanticsNodes().isNotEmpty()}
  compose.onAllNodesWithText("Mistaken loan").onFirst().performClick()
  compose.onNodeWithTag("loan-details-list").performScrollToNode(hasText("Manage loan"));compose.onNodeWithText("Manage loan").performClick()
  compose.onNodeWithTag("loan-details-list").performScrollToNode(hasText("Delete loan"));compose.onNodeWithText("Delete loan").performClick()
  compose.onNodeWithText("Keep loan").performClick();compose.runOnIdle {assertEquals(original,store.load().single())}
  compose.onNodeWithText("Delete loan").performClick();compose.onNodeWithText("Delete this loan").performClick()
  compose.waitUntil(5000){store.load().isEmpty()}
  compose.onNodeWithTag("loan-details-list").assertDoesNotExist()
 }
 @Test fun paymentHistoryBlocksDeletionEvenAfterReversal(){
  val today=LocalDate.now();val paid=loan().pay(0,1000,"payment",today).reverse("payment",today,"Correction");store.save(listOf(paid))
  assertFalse(store.deleteUnpaid(paid));assertFalse(store.deleteUnpaid(loan()))
  compose.setContent {CashaTheme {CashaApp(store,name)}}
  compose.waitUntil(5000){compose.onAllNodesWithText("Mistaken loan").fetchSemanticsNodes().isNotEmpty()}
  compose.onAllNodesWithText("Mistaken loan").onFirst().performClick()
  compose.onNodeWithTag("loan-details-list").performScrollToNode(hasText("Manage loan"));compose.onNodeWithText("Manage loan").performClick()
  compose.onNodeWithTag("loan-details-list").performScrollToNode(hasText("Payment history is protected.",substring=true))
  compose.onNodeWithText("Delete loan").assertDoesNotExist();compose.runOnIdle {assertEquals(paid,store.load().single())}
 }
}
