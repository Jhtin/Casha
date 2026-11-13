package com.example.casha

import android.graphics.Bitmap
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.domain.*
import com.example.casha.ui.PaymentCalendar
import com.example.casha.ui.theme.CashaTheme
import java.time.LocalDate
import java.time.YearMonth
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PaymentCalendarTest {
    @get:Rule val compose=createComposeRule()
    @Test fun monthGridShowsLoansAndSelectedDayOpensCorrectLoan() {
        val date=YearMonth.now().atDay(15)
        val next=YearMonth.now().plusMonths(1).atDay(1)
        val alpha=Loan(id="alpha",party="Alpha Loan",mode=LoanMode.I_OWE,total=10000,count=1,firstDue=date,frequency=Frequency.MONTHLY)
        val beta=Loan(id="beta",party="Beta Loan",mode=LoanMode.OWED_TO_ME,total=20000,count=1,firstDue=date,frequency=Frequency.MONTHLY)
        val paid=Loan(id="paid",party="Paid Loan",mode=LoanMode.I_OWE,total=5000,count=1,firstDue=date,frequency=Frequency.MONTHLY).pay(0,5000,"paid-op",LocalDate.now())
        val later=Loan(id="later",party="Next Month Loan",mode=LoanMode.I_OWE,total=7000,count=1,firstDue=next,frequency=Frequency.MONTHLY)
        var opened=""
        compose.setContent {CashaTheme(darkTheme=false){PaymentCalendar(listOf(alpha,beta,paid,later),modifier=Modifier.safeDrawingPadding(),openLoan={opened=it})}}
        compose.onNodeWithTag("calendar-day-$date").assertContentDescriptionContains("Alpha Loan",substring=true).assertContentDescriptionContains("Beta Loan",substring=true)
        compose.onNodeWithTag("calendar-day-$date").performClick()
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasTestTag("calendar-loan-beta"))
        compose.onNodeWithTag("calendar-loan-beta").performClick()
        compose.runOnIdle {assertEquals("beta",opened)}
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasText("Outstanding only"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.filesDir,"phase_previews").apply {mkdirs()}
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {bitmap->File(folder,"calendar.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
        compose.onNodeWithText("Outstanding only").performClick()
        compose.onNodeWithTag("calendar-day-$date").assertContentDescriptionContains("Paid Loan",substring=true)
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasContentDescription("Next month"))
        compose.onNodeWithContentDescription("Next month").performClick()
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasTestTag("calendar-day-$next"))
        compose.onNodeWithTag("calendar-day-$next").assertContentDescriptionContains("Next Month Loan",substring=true)
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasContentDescription("Previous month"))
        compose.onNodeWithContentDescription("Previous month").performClick()
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasTestTag("calendar-day-$date"))
        compose.onNodeWithTag("calendar-day-$date").assertExists()
    }
}
