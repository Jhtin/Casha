package com.example.casha
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.FinanceStore
import com.example.casha.domain.*
import com.example.casha.ui.PaydayScreen
import com.example.casha.ui.theme.CashaTheme
import java.time.LocalDate
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
class CashActivityHistoryTest {
 @get:Rule val compose=createComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val name="activity_history_${UUID.randomUUID()}"
 private val finance by lazy {FinanceStore(context,name)}
 @After fun cleanup(){context.deleteSharedPreferences("finance_$name")}
 @Test fun previewIsCurrentAndBoundedAndFullHistoryPagesSearchesAndReverses(){
  val today=LocalDate.now();finance.saveProfile(PaydayProfile(today.minusDays(1),today.plusDays(10),100000,0))
  for(i in 0 until 12)finance.addCash(CashEntry("current$i",false,100,today,"Current $i"))
  finance.addCash(CashEntry("old",false,200,today.minusDays(20),"Old grocery"))
  compose.setContent {CashaTheme(darkTheme=false){PaydayScreen(emptyList(),finance,Modifier.safeDrawingPadding(),openLoan={})}}
  compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Activity (12)"));compose.onNodeWithText("Activity (12)").performClick()
  compose.onNodeWithTag("payday-list").performScrollToNode(hasText("View full history",substring=true))
  compose.onNodeWithText("Old grocery").assertDoesNotExist();compose.onNodeWithText("Current 6").assertDoesNotExist()
  compose.onNodeWithText("View full history",substring=true).performClick()
  compose.onNodeWithText("Page 1 of 2").assertIsDisplayed()
  compose.onNodeWithContentDescription("Next activity page").performClick()
  compose.onNodeWithTag("cash-history-list").performScrollToNode(hasText("Old grocery"))
  compose.onNodeWithText("Old grocery").assertIsDisplayed()
  compose.onNodeWithTag("cash-history-list").performScrollToNode(hasText("Search note, account, or date"))
  compose.onNodeWithText("Search note, account, or date").performTextInput("Old grocery")
  compose.onNodeWithText("Page 1 of 1").assertIsDisplayed()
  compose.onNodeWithTag("cash-history-list").performScrollToNode(hasText("Reverse cash entry"))
  compose.onNode(hasText("Reverse cash entry") and hasAnyAncestor(hasTestTag("cash-history-list"))).performClick();compose.onNodeWithText("Confirm reversal").performClick()
  compose.waitUntil(5000){finance.cashEntries().size==14}
  compose.onNodeWithTag("cash-history-list").performScrollToNode(hasText("Reversed"));compose.onNodeWithText("Reversed").assertIsDisplayed()
  compose.onNodeWithText("Close history").performClick()
  compose.runOnIdle {assertEquals(14,finance.cashEntries().size)}
 }
}
