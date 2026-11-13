package com.example.casha.data

import com.example.casha.domain.*
import java.time.LocalDate

/** Use the same recoverable wallet intent as manual payments, including on retry after interruption. */
internal fun recordReviewedReceiptPayment(store:LoanRepository,finance:FinanceStore,snapshot:List<Loan>,suggestion:PaymentSuggestion,loanId:String,amount:Long,date:LocalDate,account:MoneyAccount,reference:String?) = synchronized(FinanceStore.receiptReviewLock(finance.scope)) {
    require(amount>0 && !date.isAfter(LocalDate.now()))
    check(!finance.cashEntries().any {it.id=="receipt_${suggestion.id}"}) {"This receipt is already an expense. Do not record it again."}
    val id="suggestion_${suggestion.id}"
    val current=store.load()
    val loan=current.single {it.id==loanId}
    val existing=current.flatMap {it.payments}.find {it.id==id}
    if(existing==null) {
        check(current==snapshot) {"Records changed. Reopen review."}
        val installment=loan.installments().firstOrNull {it.remaining>0} ?: error("This loan is already paid.")
        require(amount<=installment.remaining)
        val pending=finance.pendingPaymentIntents().find {it.intentId==id}
        val intent=PendingPaymentIntent(id,id,loan.id,installment.index,account.id,account.name,amount,date,loan.mode==LoanMode.OWED_TO_ME)
        check(pending==null || pending.copy(timestampMs=intent.timestampMs)==intent) {"A previous save is pending. Reopen review with the original details."}
        check(finance.savePaymentIntent(pending?:intent)) {"Could not prepare wallet tracking. Nothing was recorded."}
        check(store.save(current.map {if(it.id==loan.id)loan.pay(installment.index,amount,id,date) else it},current)) {"Could not save payment. Reopen review before retrying."}
    } else {
        check(loan.payments.any {it.id==id} && existing.amount==amount && existing.date==date) {"This receipt already recorded a different payment. Do not record it again."}
    }
    if(finance.paymentLink(id)==null) check(finance.promotePaymentIntent(id)) {"Payment saved; wallet tracking is pending. Reopen review to finish without paying again."}
    check(finance.completeReceiptReview(suggestion,id,reference)) {"Payment saved; receipt review is pending. Reopen review to finish without paying again."}
}
