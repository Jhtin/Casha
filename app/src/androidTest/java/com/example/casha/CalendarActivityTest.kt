package com.example.casha

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.FinanceStore
import com.example.casha.domain.*
import com.example.casha.ui.PaymentCalendar
import com.example.casha.ui.theme.CashaTheme
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.util.UUID

class CalendarActivityTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope="calendar_${UUID.randomUUID()}"
    @After fun cleanup(){context.deleteSharedPreferences("finance_$scope")}
    @Test fun selectedDayShowsCashPaymentsReversalsAndPersistsColors() {
        val today=LocalDate.now()
        val finance=FinanceStore(context,scope)
        finance.saveProfile(PaydayProfile(today,today.plusDays(15),100000,0))
        finance.addCash(CashEntry("cash-in",true,10000,today,"Freelance income"))
        finance.addCash(CashEntry("cash-out",false,2000,today,"Lunch expense"))
        val loan=Loan(id="activity",party="Activity Loan",mode=LoanMode.I_OWE,total=5000,count=1,firstDue=today,frequency=Frequency.MONTHLY).pay(0,1000,"payment",today).reverse("payment",today,"Corrected payment")
        compose.setContent {CashaTheme(darkTheme=false){PaymentCalendar(listOf(loan),Modifier.safeDrawingPadding(),finance){}}}
        compose.onNodeWithTag("calendar-day-$today").assertContentDescriptionContains("Payday",substring=true).assertContentDescriptionContains("4 recorded activities",substring=true)
        compose.onNodeWithContentDescription("Calendar colors").performClick()
        compose.onNodeWithContentDescription("Payday: Purple color").performClick()
        compose.runOnIdle {assertTrue(finance.preferences.contains("calendar_color_payday"))}
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasText("Freelance income"))
        compose.onNodeWithText("Freelance income").assertIsDisplayed()
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasText("Lunch expense"))
        compose.onNodeWithText("Lunch expense").assertIsDisplayed()
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasText("Recorded payment · Inst. 1"))
        compose.onNodeWithText("Recorded payment · Inst. 1").assertIsDisplayed()
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasText("Corrected payment"))
        compose.onNodeWithText("Corrected payment").assertIsDisplayed()
    }
}
