package com.example.casha.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.casha.domain.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

internal data class LoanPaymentInput(val amount:Long,val date:LocalDate)
internal fun validateLoanPayment(amount:String,date:String,maximum:Long,today:LocalDate=LocalDate.now()):LoanPaymentInput? {
    val value=parseMoney(amount)?:return null
    val day=runCatching {LocalDate.parse(date)}.getOrNull()?:return null
    if(value>maximum||day.isAfter(today)||day.year !in 1900..2200)return null
    return LoanPaymentInput(value,day)
}
private data class PaymentReview(val loan:Loan,val installment:Int,val input:LoanPaymentInput,val accountId:String,val accountName:String)

@Composable internal fun LoanPaymentDialog(loan:Loan,busy:Boolean,prefillFull:Boolean,saveError:String?,dismiss:()->Unit,finance:com.example.casha.data.FinanceStore?=null,save:(Loan)->Unit) {
    val installment=loan.installments().firstOrNull {it.remaining>0}?:return
    val accounts = remember(finance) { finance?.moneyAccounts()?.filter { !it.isArchived && !it.isSavings } ?: emptyList() }
    var selectedAccountId by rememberSaveable(loan.id, installment.index) {
        mutableStateOf(accounts.firstOrNull { it.id == "acc_bank" }?.id ?: accounts.firstOrNull()?.id ?: "acc_cash")
    }
    var amount by rememberSaveable(loan.id,installment.index){mutableStateOf(if(prefillFull)java.math.BigDecimal.valueOf(installment.remaining,2).toPlainString() else "")}
    var paidOn by rememberSaveable(loan.id,installment.index){mutableStateOf(LocalDate.now().toString())}
    val operation=rememberSaveable(loan.id,installment.index){UUID.randomUUID().toString()}
    var error by remember {mutableStateOf<String?>(null)}
    var review by remember {mutableStateOf<PaymentReview?>(null)}
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    val scroll=rememberScrollState()
    LaunchedEffect(review,saveError){scroll.scrollTo(0)}
    val format=DateTimeFormatter.ofPattern("MMM d, yyyy")
    LaunchedEffect(loan) {
        if(review?.loan!=null&&review?.loan!=loan){review=null;error="This loan changed. Please review the updated installment before saving."}
    }
    PilotModal(onDismissRequest={if(!busy){keyboard?.hide();dismiss()}},title={Text(if(review==null)"Payment details" else "Review payment")},text={
        Column(Modifier.heightIn(max=460.dp).verticalScroll(scroll).testTag("loan-payment-form"),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Text(loan.party,style=MaterialTheme.typography.titleMedium)
            saveError?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            if(review==null) {
                Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Remaining on installment ${installment.index+1} of ${loan.count}",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Due ${installment.due.format(format)}",style=MaterialTheme.typography.bodyMedium)
                        Text(money(installment.remaining),style=MaterialTheme.typography.headlineMedium)
                    }
                }
                OutlinedTextField(amount,{amount=it;error=null},label={Text("Payment (PHP)")},modifier=Modifier.fillMaxWidth(),textStyle=MaterialTheme.typography.headlineSmall,shape=MaterialTheme.shapes.large,singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),isError=error!=null)
                error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                OutlinedButton({amount=java.math.BigDecimal.valueOf(installment.remaining,2).toPlainString();error=null},enabled=!busy,modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium){
                    PilotIcon("Check", Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Use full installment (${money(installment.remaining)})")
                }
                DateEntryField(paidOn,{paidOn=it;error=null},"Payment date",enabled=!busy)

                if (accounts.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (loan.mode == LoanMode.I_OWE) "Paid from wallet" else "Receive into wallet",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            accounts.forEach { acc ->
                                FilterChip(
                                    selected = selectedAccountId == acc.id,
                                    onClick = { selectedAccountId = acc.id },
                                    label = { Text(acc.name) },
                                    leadingIcon = { PilotIcon(acc.icon, Modifier.size(16.dp)) }
                                )
                            }
                        }
                    }
                }

                Text(if(loan.mode==LoanMode.I_OWE)"For payments already made. Partial amounts are supported." else "For money already received. Partial amounts are supported.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val checked=review!!
                PaydayTotal(if(loan.mode==LoanMode.I_OWE)"Payment amount" else "Amount received",checked.input.amount)
                Text("${checked.input.date.format(format)} · Installment ${checked.installment+1} of ${loan.count}",style=MaterialTheme.typography.bodyMedium)
                Text("Wallet: ${checked.accountName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                MoneyLine("Installment left after payment",checked.loan.installments()[checked.installment].remaining-checked.input.amount)
                MoneyLine("Loan balance after payment",checked.loan.remaining-checked.input.amount)
                if(loan.remaining==checked.input.amount)Text("This completes the loan. It will move to Show archived.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
                Text("Check the amount and date, then save to your records.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    },confirmButton={Button(shape=MaterialTheme.shapes.medium,enabled=!busy&&amount.isNotBlank(),onClick={
        val input=validateLoanPayment(amount,paidOn,installment.remaining)
        val selectedAcc = accounts.firstOrNull { it.id == selectedAccountId } ?: accounts.firstOrNull()
        val accId = selectedAcc?.id ?: "acc_cash"
        val accName = selectedAcc?.name ?: "Cash on hand"
        if(input==null){review=null;error="Enter a positive amount up to ${money(installment.remaining)} and a valid date no later than today."}
        else if(review==null){focus.clearFocus();keyboard?.hide();error=null;review=PaymentReview(loan,installment.index,input,accId,accName)}
        else {
            val checked=review!!
            if(checked.loan!=loan||checked.input!=input){review=null;error="Payment details changed. Please review them again."}
            else {
                if (finance != null) {
                    val intent = com.example.casha.data.PendingPaymentIntent(
                        intentId = operation,
                        paymentId = operation,
                        loanId = loan.id,
                        installmentIndex = checked.installment,
                        accountId = checked.accountId,
                        accountName = checked.accountName,
                        amountCentavos = checked.input.amount,
                        date = checked.input.date,
                        isReceivable = (loan.mode == LoanMode.OWED_TO_ME)
                    )
                    finance.savePaymentIntent(intent)
                }
                save(loan.pay(checked.installment,checked.input.amount,operation,checked.input.date))
            }
        }
    }){
        PilotIcon(if (review == null) "Arrow" else "Check", Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if(busy)"Saving…" else if(review==null)"Review payment" else "Save payment")
    }},dismissButton={
        TextButton({if(review!=null){review=null;error=null}else {focus.clearFocus();keyboard?.hide();dismiss()}},enabled=!busy,shape=MaterialTheme.shapes.small){Text(if(review==null)"Cancel payment" else "Edit payment")}
    })
}
