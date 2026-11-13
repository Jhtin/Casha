package com.example.casha

import com.example.casha.domain.*
import com.example.casha.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FinanceFeaturesTest {
    private val start=LocalDate.of(2026,11,15)
    private val next=LocalDate.of(2026,11,30)
    private fun loan(id:String="l1",amount:Long=50_000,date:LocalDate=start.plusDays(5))=Loan(id=id,party=id,mode=LoanMode.I_OWE,total=amount,count=1,firstDue=date,frequency=Frequency.MONTHLY)
    @Test fun budgetsConserveEveryCentAndProtectEssentials() {
        val profile=PaydayProfile(start,next,150_000,60_000,10_000)
        val loans=listOf(loan(amount=30_000),loan("l2",40_000,next))
        val plan=PaydayPlanner.calculate(profile,loans,mapOf("l2" to DebtPolicy(allowSplit=true)),emptyList(),start)
        assertEquals(30_000L,plan.safeToSpend)
        assertEquals(150_000L,plan.essentials+plan.emergency+plan.allocations.sumOf {it.amount}+plan.safeToSpend)
        assertEquals(20_000L,plan.allocations.first {it.sinking}.amount)
        assertEquals(2_000L,plan.daily)
    }
    @Test fun recordingAPaymentDoesNotSubtractItTwice() {
        val original=loan();val profile=PaydayProfile(start,next,100_000,0)
        val before=PaydayPlanner.calculate(profile,listOf(original),emptyMap(),emptyList(),start)
        val after=PaydayPlanner.calculate(profile,listOf(original.pay(0,10_000,"p1",start)),emptyMap(),emptyList(),start)
        assertEquals(before.safeToSpend,after.safeToSpend)
        assertEquals(90_000L,after.cash)
    }
    @Test fun negativeCashIsReportedAsShortfallAndNeverNegativeAllowance() {
        val profile=PaydayProfile(start,next,100,500)
        val plan=PaydayPlanner.calculate(profile,emptyList(),emptyMap(),listOf(CashEntry("e",false,200,start,"Food")),start)
        assertEquals(0L,plan.safeToSpend);assertEquals(0L,plan.daily);assertEquals(600L,plan.shortfall)
    }
    @Test fun highRiskCurrentDuesReceiveScarceFundsFirst() {
        val early=loan("early",10_000,start.plusDays(1));val high=loan("high",10_000,start.plusDays(5))
        val plan=PaydayPlanner.calculate(PaydayProfile(start,next,10_000,0),listOf(early,high),mapOf("high" to DebtPolicy(risk=2)),emptyList(),start)
        assertEquals("high",plan.allocations.single().loanId);assertEquals(10_000L,plan.unreservedDues)
    }
    @Test fun futureAndOutsideCutoffExpensesDoNotChangeCurrentCash() {
        val entries=listOf(CashEntry("future",false,100,start.plusDays(1),"Later"),CashEntry("previous",false,100,start.minusDays(1),"Earlier"))
        assertEquals(1000L,PaydayPlanner.calculate(PaydayProfile(start,next,1000,0),emptyList(),emptyMap(),entries,start).cash)
    }
    @Test fun receivablesOnlyCountAfterPaymentAndReversalRestoresOriginalPosition() {
        val l=loan().copy(mode=LoanMode.OWED_TO_ME).pay(0,1000,"p1",start)
        val profile=PaydayProfile(start,next,0,0)
        assertEquals(1000L,PaydayPlanner.calculate(profile,listOf(l),emptyMap(),emptyList(),start).cash)
        assertEquals(0L,PaydayPlanner.calculate(profile,listOf(l.reverse("p1",start,"Wrong entry")),emptyMap(),emptyList(),start).cash)
    }
    @Test fun avalancheAndSnowballSelectDifferentTargets() {
        val small=loan("small",1000);val expensive=loan("expensive",5000)
        val policies=mapOf("expensive" to DebtPolicy(annualRateBps=2400))
        assertEquals(expensive,PaydayPlanner.surplusTarget(listOf(small,expensive),policies,DebtStrategy.AVALANCHE))
        assertEquals(small,PaydayPlanner.surplusTarget(listOf(small,expensive),policies,DebtStrategy.SNOWBALL))
    }
    @Test fun semimonthlyCutoffsHandleLeapFebruaryAndYearEnd() {
        assertEquals(LocalDate.of(2028,2,29),PaydayPlanner.nextPayday(LocalDate.of(2028,2,15),Frequency.SEMIMONTHLY))
        assertEquals(LocalDate.of(2027,1,15),PaydayPlanner.nextPayday(LocalDate.of(2026,12,31),Frequency.SEMIMONTHLY))
    }
    @Test fun interestEstimatesHandleZeroFlatAndReducingWithoutFloatingMoney() {
        assertEquals(100_000L,InterestEstimate.total(100_000,0,12,Frequency.MONTHLY,true))
        assertEquals(112_000L,InterestEstimate.total(100_000,1200,12,Frequency.MONTHLY,false))
        val reducing=InterestEstimate.total(100_000,1200,12,Frequency.MONTHLY,true)
        assertTrue(reducing in 106_000L..107_000L)
    }
    @Test fun receiptParsingRejectsAmbiguityAndPreservesCentAmounts() {
        val parsed=PaymentDetector.parse("Amount: PHP 1,234.56\nReference No.: ABC12345\n2026-11-02")
        assertEquals(123456L,parsed.amount);assertEquals("ABC12345",parsed.reference);assertEquals(LocalDate.of(2026,11,2),parsed.date)
        val printed=PaymentDetector.parse("Total: ₱ 850.00\nRef No: GCASH998877\nNov 2, 2026")
        assertEquals(85000L,printed.amount);assertEquals("GCASH998877",printed.reference);assertEquals(LocalDate.of(2026,11,2),printed.date)
        assertNull(PaymentDetector.parse("Sent PHP 100.00 Balance PHP 500.00").amount)
        assertNull(PaymentDetector.parse("No money here").amount)
    }
    @Test fun reminderStagesRespectLeadWindow() {
        assertEquals("Overdue",ReminderPolicy.stage(start.minusDays(1),start,3))
        assertEquals("Due today",ReminderPolicy.stage(start,start,3))
        assertEquals("Due soon",ReminderPolicy.stage(start.plusDays(3),start,3))
        assertNull(ReminderPolicy.stage(start.plusDays(4),start,3))
    }
    private fun response(kind:String="PAYMENT",amount:Any=1000,alias:String="L1")=JSONObject().put("kind",kind).put("loan",alias).put("amountCentavos",amount)
        .put("party","").put("installments",0).put("firstDue","").put("frequency","MONTHLY").put("mode","I_OWE").put("explanation","Review this payment")
    @Test fun geminiActionRequiresKnownLoanAndValidAmount() {
        val original=loan()
        val proposal=GeminiProposalParser.parse(response().toString(),listOf(original))
        assertEquals(original,proposal.target);assertEquals(1000L,proposal.amount);assertEquals(0,original.payments.size)
        for(json in listOf(response(alias="L2"),response(amount=-1),response(amount=0),response(amount=1.5),response(amount=50_001),response(kind="DELETE_ALL"),response().put("execute","code")))
            assertTrue(runCatching {GeminiProposalParser.parse(json.toString(),listOf(original))}.isFailure)
    }
    @Test fun adviceCannotMutateTheLedger() {
        val proposal=GeminiProposalParser.parse(response("ADVICE",0).toString(),emptyList())
        assertNull(proposal.target);assertNull(proposal.newLoan);assertEquals("ADVICE",proposal.kind)
    }
    @Test fun interactionsStepsExtractModelOutputText() {
        val payload=response("ADVICE",0).toString()
        val interaction=JSONObject().put("id","int_1").put("status","completed").put("steps",JSONArray()
            .put(JSONObject().put("type","thought").put("signature","sig_1"))
            .put(JSONObject().put("type","model_output").put("content",JSONArray().put(JSONObject().put("type","text").put("text",payload)))))
        val text=GeminiProposalParser.extractOutputText(interaction)
        val proposal=GeminiProposalParser.parse(text,emptyList())
        assertEquals("ADVICE",proposal.kind)
    }
    @Test fun geminiContextRedactsWholeNamesAndIncludesAnonymousBudget() {
        val privateLoan=loan().copy(party="Private Lender")
        val context=GeminiPromptContext.build("Nagbayad ako sa Private Lender ng 100",listOf(privateLoan),PaydayProfile(start,next,100_000,20_000),emptyList(),emptyMap(),start)
        assertFalse(context.toString().contains("Private Lender"))
        assertTrue(context.getString("command").contains("L1"))
        assertEquals(30_000L,context.getJSONObject("budget").getLong("safeToSpendCentavos"))
    }
    @Test fun geminiContextIncludesYesterdayAndTodayTransactionsAndDeanonymizesReplies() {
        val today=start.plusDays(1)
        val yesterday=start
        val paidLoan=loan().copy(party="Home Credit").pay(0,15_000,"p1",yesterday)
        val entries=listOf(
            CashEntry("c1",false,35_000,yesterday,"Food · Groceries"),
            CashEntry("c2",true,100_000,today,"Extra Raket")
        )
        val context=GeminiPromptContext.build("What are my transactions yesterday?",listOf(paidLoan),PaydayProfile(start,next,200_000,20_000),entries,emptyMap(),today)
        assertEquals(today.toString(),context.getString("today"))
        assertEquals(yesterday.toString(),context.getString("yesterday"))
        val activities=context.getJSONObject("activities")
        val yesterdayTx=activities.getJSONArray("yesterdayTransactions")
        assertEquals(2,yesterdayTx.length())
        assertTrue(yesterdayTx.toString().contains("Food · Groceries"))
        assertTrue(yesterdayTx.toString().contains("LOAN_PAYMENT_SENT"))
        assertFalse(context.toString().contains("Home Credit"))
        val reply=GeminiProposalParser.parse(
            response("ADVICE",0).put("explanation","Yesterday you paid ₱150.00 to L1 (Loan 1) and spent ₱350.00 on Food · Groceries.").toString(),
            listOf(paidLoan)
        )
        assertTrue(reply.explanation.contains("Home Credit"))
        assertFalse(reply.explanation.contains("L1"))
        assertFalse(reply.explanation.contains("Loan 1"))
        val partialPrompt=GeminiPromptContext.build("Tell me about my Credit loan",listOf(paidLoan),PaydayProfile(start,next,200_000,20_000),entries,emptyMap(),today)
        assertTrue(partialPrompt.getString("command").contains("L1"))
    }
    @Test fun geminiRejectsAccidentalApiKeysAndAccountNumbersBeforeNetworkUse() {
        val profile=PaydayProfile(start,next,0,0)
        for(command in listOf("account 1234567890123456","my key AIza0123456789abcdefghijklmnopqrstuv","person@example.com"))
            assertTrue(runCatching {GeminiPromptContext.build(command,emptyList(),profile,emptyList(),emptyMap(),start)}.isFailure)
    }
    @Test fun payoffSimulatorAdvancesDebtFreeDateWithExtraPayments() {
        val multi=Loan(id="m1",party="Card",mode=LoanMode.I_OWE,total=30_000,count=3,firstDue=start.plusMonths(1),frequency=Frequency.MONTHLY)
        val profile=PaydayProfile(start,next,100_000,20_000)
        val base=PaydayPlanner.simulatePayoff(listOf(multi),emptyMap(),profile,0)
        val accelerated=PaydayPlanner.simulatePayoff(listOf(multi),emptyMap(),profile,15_000)
        assertEquals(multi.installments().last().due,base.scheduledDebtFreeDate)
        assertTrue(accelerated.daysSaved > 0)
        assertTrue(accelerated.simulatedDebtFreeDate!!.isBefore(base.scheduledDebtFreeDate))
    }
    @Test fun rolloverProfileAdvancesSemimonthlyDatesAndAddsCarryover() {
        val profile=PaydayProfile(start,next,100_000,20_000,5_000)
        val rolled=PaydayPlanner.rolloverProfile(profile,12_500)
        assertEquals(next,rolled.start)
        assertEquals(LocalDate.of(2026,12,15),rolled.next)
        assertEquals(112_500L,rolled.salary)
    }
    @Test fun categoryBreakdownGroupsActiveExpensesAndIgnoresReversedEntries() {
        val profile=PaydayProfile(start,next,100_000,0)
        val entries=listOf(
            CashEntry("e1",false,30_000,start,"Food · Lunch"),
            CashEntry("e2",false,10_000,start,"Transport · Grab"),
            CashEntry("e3",false,20_000,start,"Bills · Meralco"),
            CashEntry("undo_e3",true,20_000,start,"Reversal")
        )
        val breakdown=PaydayPlanner.categoryBreakdown(profile,entries,start)
        assertEquals(2,breakdown.size)
        assertEquals("Food",breakdown.first().category)
        assertEquals(75,breakdown.first().sharePercent)
    }
    @Test fun statementOfAccountIncludesTotalsPaidAndNextDue() {
        val partial=loan(id="s1",amount=50_000).pay(0,20_000,"p1",start)
        val text=PaydayPlanner.formatStatement(partial,start)
        assertTrue(text.contains("CASHA STATEMENT OF ACCOUNT"))
        assertTrue(text.contains("s1"))
        assertTrue(text.contains("Recorded Payments:"))
    }
}
