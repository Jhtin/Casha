package com.example.casha

import com.example.casha.ui.*
import com.example.casha.domain.*
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.*

class SafeSpendPresentationTest {
    @Test fun exactThresholdsAndOneCentBoundaries() {
        assertEquals(SpendBand.GREEN,safeSpendBand(5001,10000))
        assertEquals(SpendBand.YELLOW,safeSpendBand(5000,10000))
        assertEquals(SpendBand.YELLOW,safeSpendBand(2000,10000))
        assertEquals(SpendBand.RED,safeSpendBand(1999,10000))
        assertEquals(SpendBand.RED,safeSpendBand(0,10000))
        assertEquals(SpendBand.GREEN,safeSpendBand(Long.MAX_VALUE,Long.MAX_VALUE))
    }
    @Test fun unknownOrExpiredFundingIsNeutral() {
        assertEquals(SpendBand.UNSET,safeSpendBand(0,0))
        assertEquals(SpendBand.UNSET,safeSpendBand(100,100,false))
        assertEquals(SpendBand.UNSET,safeSpendBand(0,-1))
    }
    @Test fun cashReversalsDoNotInflateFundingAndFutureEntriesExcluded() {
        val today=LocalDate.of(2026,11,2)
        val profile=PaydayProfile(today,today.plusDays(10),10000,0)
        val cash=listOf(CashEntry("income",true,5000,today,""),CashEntry("undo_income",false,5000,today,""),CashEntry("expense",false,2000,today,""),CashEntry("undo_expense",true,2000,today,""),CashEntry("future",true,9999,today.plusDays(1),""))
        assertEquals(10000L,cutoffFunding(profile,emptyList(),cash,today))
    }
    @Test fun collectionsAreNetOfReversalsButPayablesAreNotFunding() {
        val today=LocalDate.of(2026,11,2)
        val profile=PaydayProfile(today,today.plusDays(10),10000,0)
        val collectible=Loan(id="collection",party="Borrower",mode=LoanMode.OWED_TO_ME,total=5000,count=1,firstDue=today,frequency=Frequency.MONTHLY).pay(0,3000,"receipt",today)
        val payable=Loan(id="debt",party="Lender",mode=LoanMode.I_OWE,total=5000,count=1,firstDue=today,frequency=Frequency.MONTHLY).pay(0,2000,"pay",today)
        assertEquals(13000L,cutoffFunding(profile,listOf(collectible,payable),emptyList(),today))
        assertEquals(10000L,cutoffFunding(profile,listOf(collectible.reverse("receipt",today,"Correction")),emptyList(),today))
    }
}
