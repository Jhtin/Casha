package com.example.casha

import com.example.casha.data.*
import com.example.casha.domain.*
import com.example.casha.ui.cashActivityEntries
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReceiptReviewRegressionTest {
    private val day=LocalDate.now().minusDays(20)
    private val receipt=PaymentSuggestion("scan1",18400,day,"REF1","Receipt OCR","receipt.jpg")
    private val account=MoneyAccount("acc_cash","Cash on hand")
    private fun finance()=FinanceStore(null,"receipt-test",FakeSharedPreferences())
    private fun loan(mode:LoanMode=LoanMode.I_OWE)=Loan(id="loan",party="Sample",mode=mode,total=40000,count=2,firstDue=day,frequency=Frequency.MONTHLY)
    private class Repo(var loans:List<Loan>):LoanRepository {
        override val changes=MutableStateFlow(0L)
        var reject=false
        override fun load()=loans
        override fun save(loans:List<Loan>,expected:List<Loan>?):Boolean {
            if(reject || expected!=this.loans)return false
            this.loans=loans;changes.value++;return true
        }
    }
    @Test fun backdatedExpenseIsDurableVisibleAndDeductedOnce() {
        val f=finance();f.addSuggestion(receipt)
        val entry=CashEntry("receipt_scan1",false,18400,day,"Receipt expense",account.name)
        assertTrue(f.recordReceiptExpense(receipt,entry,"REF1"))
        assertTrue(f.recordReceiptExpense(receipt,entry,"REF1"))
        val reopened=FinanceStore(null,"receipt-test",f.preferences)
        assertEquals(listOf(entry),reopened.cashEntries());assertTrue(reopened.suggestions().isEmpty())
        assertEquals("receipt.jpg",reopened.proof(entry.id)!!.getString("receipt"))
        val current=PaydayProfile(LocalDate.now().minusDays(1),LocalDate.now().plusDays(10),100000,0)
        assertEquals(entry,cashActivityEntries(reopened.cashEntries(),current,true).single())
        assertTrue(cashActivityEntries(reopened.cashEntries(),current,false).isEmpty())
        assertEquals(-18400L,PaydayPlanner.accountBalances(listOf(account),reopened.cashEntries(),current.copy(start=day.minusDays(1),salary=0))[account.id])
    }
    @Test fun reviewedPaymentHasRecoverableWalletLinkAndNoCashDoubleCounting() {
        val f=finance();val r=Repo(listOf(loan()));val original=r.loans;f.addSuggestion(receipt)
        repeat(2){recordReviewedReceiptPayment(r,f,original,receipt,"loan",18400,day,account,"REF1")}
        assertEquals(1,r.loans.single().payments.size)
        assertEquals(21600L,r.loans.single().remaining)
        assertTrue(f.pendingPaymentIntents().isEmpty());assertTrue(f.cashEntries().isEmpty())
        assertEquals(account.id,f.paymentLink("suggestion_scan1")!!.accountId)
        assertEquals("receipt.jpg",f.proof("suggestion_scan1")!!.getString("receipt"))
        val profile=PaydayProfile(day.minusDays(1),day.plusDays(10),0,0)
        assertEquals(-18400L,PaydayPlanner.accountBalances(listOf(account),f.cashEntries(),profile,paymentLinks=f.allPaymentLinks(),loans=r.loans)[account.id])
    }
    @Test fun rejectedLedgerWriteDoesNotDeductWalletAndRetryRecovers() {
        val f=finance();val r=Repo(listOf(loan()));r.reject=true;f.addSuggestion(receipt)
        assertTrue(runCatching {recordReviewedReceiptPayment(r,f,r.loans,receipt,"loan",18400,day,account,null)}.isFailure)
        assertTrue(f.allPaymentLinks().isEmpty());assertEquals(1,f.suggestions().size)
        assertEquals(1,f.pendingPaymentIntents().size)
        r.reject=false
        recordReviewedReceiptPayment(r,f,r.loans,receipt,"loan",18400,day,account,null)
        assertEquals(1,r.loans.single().payments.size);assertTrue(f.pendingPaymentIntents().isEmpty())
    }
    @Test fun receiptCannotBeBothExpenseAndLoanPayment() {
        val f=finance();val r=Repo(listOf(loan()))
        assertTrue(f.recordReceiptExpense(receipt,CashEntry("receipt_scan1",false,18400,day,"Receipt"),null))
        assertTrue(runCatching {recordReviewedReceiptPayment(r,f,r.loans,receipt,"loan",18400,day,account,null)}.isFailure)
        assertTrue(r.loans.single().payments.isEmpty())
    }
    @Test fun receiptPaymentCannotThenBeExpense() {
        val f=finance();val r=Repo(listOf(loan()))
        recordReviewedReceiptPayment(r,f,r.loans,receipt,"loan",18400,day,account,null)
        assertFalse(f.recordReceiptExpense(receipt,CashEntry("receipt_scan1",false,18400,day,"Receipt"),null))
        assertTrue(f.cashEntries().isEmpty())
    }
    @Test fun receivablePaymentCreditsWallet() {
        val f=finance();val r=Repo(listOf(loan(LoanMode.OWED_TO_ME)))
        recordReviewedReceiptPayment(r,f,r.loans,receipt,"loan",18400,day,account,null)
        assertTrue(f.paymentLink("suggestion_scan1")!!.isReceivable)
        assertEquals(18400L,PaydayPlanner.accountBalances(listOf(account),emptyList(),PaydayProfile(day,day.plusDays(30),0,0),paymentLinks=f.allPaymentLinks(),loans=r.loans)[account.id])
    }
    @Test fun interruptedReceiptCompletionCanFinishFullyPaidLoanWithoutAnotherDebit() {
        val base=FakeSharedPreferences()
        var failCompletion=true
        val prefs=object:android.content.SharedPreferences by base {
            override fun edit():android.content.SharedPreferences.Editor {
                val editor=base.edit();var proof=false
                return object:android.content.SharedPreferences.Editor by editor {
                    override fun putString(key:String?,value:String?):android.content.SharedPreferences.Editor {
                        if(key?.startsWith("proof_")==true)proof=true
                        editor.putString(key,value);return this
                    }
                    override fun putBoolean(key:String?,value:Boolean):android.content.SharedPreferences.Editor {
                        editor.putBoolean(key,value);return this
                    }
                    override fun commit():Boolean = if(proof && failCompletion)false else editor.commit()
                }
            }
        }
        val f=FinanceStore(null,"interrupted-review",prefs)
        val r=Repo(listOf(loan().copy(total=18400,count=1)))
        f.addSuggestion(receipt)
        assertTrue(runCatching {recordReviewedReceiptPayment(r,f,r.loans,receipt,"loan",18400,day,account,null)}.isFailure)
        assertEquals(0L,r.loans.single().remaining)
        assertNotNull(f.paymentLink("suggestion_scan1"));assertEquals(1,f.suggestions().size)
        failCompletion=false
        val reopened=FinanceStore(null,"interrupted-review",prefs)
        recordReviewedReceiptPayment(r,reopened,r.loans,receipt,"loan",18400,day,account,null)
        assertEquals(1,r.loans.single().payments.size)
        assertTrue(reopened.suggestions().isEmpty());assertEquals(1,reopened.allPaymentLinks().size)
    }

}
