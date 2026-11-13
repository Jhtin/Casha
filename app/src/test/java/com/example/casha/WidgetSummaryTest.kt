package com.example.casha

import com.example.casha.widget.*
import com.example.casha.data.*
import com.example.casha.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate

class WidgetSummaryTest {
    private val today=LocalDate.of(2026,11,5)
    private fun loan(id:String,date:LocalDate,mode:LoanMode=LoanMode.I_OWE)=Loan(id,id,mode,20001,2,date,Frequency.WEEKLY)
    @Test fun countsInstallmentsInNextSevenDaysAndKeepsCentavos() {
        val loans=listOf(loan("old",today.minusDays(1)),loan("today",today),loan("last",today.plusDays(6)),loan("outside",today.plusDays(7)))
        val summary=widgetSummary(loans,today)
        assertEquals(1,summary.overdue)
        assertEquals(3,summary.thisWeek) // old's second installment is also due on day six.
        assertEquals("old",summary.next!!.loanId);assertEquals(10001L,summary.next.amount)
    }
    @Test fun paidAndReceivableLoansDoNotAppearAndPartialPaymentsUseRemaining() {
        val partial=loan("partial",today).pay(0,100,"pay",today)
        val paid=loan("paid",today).pay(0,10001,"p1",today).pay(1,10000,"p2",today)
        val summary=widgetSummary(listOf(partial,paid,loan("receivable",today.minusDays(5),LoanMode.OWED_TO_ME)),today)
        assertEquals(0,summary.overdue);assertEquals(1,summary.thisWeek);assertEquals(9901L,summary.next!!.amount)
        val reversed=partial.reverse("pay",today,"Correction")
        assertEquals(10001L,widgetSummary(listOf(reversed),today).next!!.amount)
    }
    @Test fun queuedPaymentsAndDeletesMatchOfflineAccountProjection() {
        val original=loan("one",today)
        val paid=original.pay(0,10001,"pending-payment",today)
        val remove=loan("remove",today.minusDays(2))
        val raw=JSONObject().put("base",LoanCodec.encode(listOf(original,remove)))
            .put("queue",CloudQueueCodec.encode(listOf(CloudMutation(original,paid),CloudMutation(remove,remove,true))))
        val projection=widgetJournalLoans(raw.toString())
        assertEquals(listOf(paid),projection)
        assertEquals(today.plusDays(7),widgetSummary(projection,today).next!!.date)
    }
    @Test fun emptyScheduleDoesNotInventDebtAndMalformedCacheFailsClosed() {
        assertNull(widgetSummary(emptyList(),today).next)
        assertTrue(runCatching {widgetJournalLoans("broken")}.isFailure)
        assertTrue(widgetJournalLoans("{}").isEmpty())
    }
}
