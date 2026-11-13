package com.example.casha

import com.example.casha.ui.*
import com.example.casha.domain.*
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate

class CompanionReactionTest {
    private val today=LocalDate.of(2026,11,2)
    private val profile=PaydayProfile(today,today.plusDays(10),100000,0)
    private fun expense(amount:Long,date:LocalDate=today)=CashEntry("expense",false,amount,date,"")
    @Test fun aboveDailyWarnsButExactAllowanceDoesNot() {
        val at=companionReaction(emptyList(),emptyList(),emptyList(),listOf(expense(10000)),profile,emptyMap(),true,today)
        val above=companionReaction(emptyList(),emptyList(),emptyList(),listOf(expense(10001)),profile,emptyMap(),true,today)
        assertEquals(PetotMood.CHEER,at?.mood);assertEquals(PetotMood.CARE,above?.mood)
    }
    @Test fun newShortfallIsPrioritizedOverDailyWarning() {
        val reaction=companionReaction(emptyList(),emptyList(),emptyList(),listOf(expense(120000)),profile,emptyMap(),true,today)
        assertEquals(PetotMood.CARE,reaction?.mood);assertTrue(reaction!!.message.contains("short"))
    }
    @Test fun unsetExpiredAndHistoricalBudgetsDoNotInventWarnings() {
        val cash=listOf(expense(120000))
        assertEquals(PetotMood.CHEER,companionReaction(emptyList(),emptyList(),emptyList(),cash,profile,emptyMap(),false,today)?.mood)
        assertEquals(PetotMood.CHEER,companionReaction(emptyList(),emptyList(),emptyList(),cash,profile,emptyMap(),true,today.plusDays(11))?.mood)
        assertEquals(PetotMood.CHEER,companionReaction(emptyList(),emptyList(),emptyList(),listOf(expense(120000,today.minusDays(1))),profile,emptyMap(),true,today)?.mood)
    }
    @Test fun finalPaymentCelebratesAndReversalDoesNot() {
        val loan=Loan(id="loan",party="Lender",mode=LoanMode.I_OWE,total=5000,count=1,firstDue=today,frequency=Frequency.MONTHLY)
        val paid=loan.pay(0,5000,"payment",today)
        assertEquals("One loan down!",companionReaction(listOf(loan),listOf(paid),emptyList(),emptyList(),profile,emptyMap(),true,today)?.title)
        assertNull(companionReaction(listOf(paid),listOf(paid.reverse("payment",today,"Correction")),emptyList(),emptyList(),profile,emptyMap(),true,today))
        assertNull(companionReaction(listOf(paid),listOf(paid),emptyList(),emptyList(),profile,emptyMap(),true,today))
    }
    @Test fun paymentBeyondCashWarnsAndImportDoesNotCelebrate() {
        val loan=Loan(id="loan",party="Lender",mode=LoanMode.I_OWE,total=120000,count=1,firstDue=today,frequency=Frequency.MONTHLY)
        val paid=loan.pay(0,120000,"payment",today)
        assertEquals(PetotMood.CARE,companionReaction(listOf(loan),listOf(paid),emptyList(),emptyList(),profile,emptyMap(),true,today)?.mood)
        assertNull(companionReaction(emptyList(),listOf(paid),emptyList(),emptyList(),profile,emptyMap(),true,today))
    }
    @Test fun undoIsSilentAndSameCashIsNotReplayed() {
        val entry=expense(1000)
        assertNull(companionReaction(emptyList(),emptyList(),listOf(entry),listOf(entry),profile,emptyMap(),true,today))
        assertNull(companionReaction(emptyList(),emptyList(),listOf(entry),listOf(entry,CashEntry("undo_expense",true,1000,today,"Undo")),profile,emptyMap(),true,today))
    }

    @Test fun walletTransfersDoNotClaimIncomeOrSpending() {
        val entries=listOf(CashEntry("transfer_out_test",false,480000,today,"ATM", "Bank"),
            CashEntry("transfer_in_test",true,480000,today,"ATM", MoneyAccount.DEFAULT_CASH))
        assertNull(companionReaction(emptyList(),emptyList(),emptyList(),entries,profile,emptyMap(),true,today))
    }
    @Test fun newIncomeUsesProudEmotionWithoutChangingItsAmount() {
        val entry=CashEntry("income_test",true,480000,today,"Funding")
        val reaction=companionReaction(emptyList(),emptyList(),emptyList(),listOf(entry),profile,emptyMap(),true,today)
        assertEquals(PetotMood.PROUD,reaction?.mood)
        assertEquals(480000L,entry.amount)
    }
}
