package com.example.casha.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.casha.data.CapturedPayment
import com.example.casha.data.FinanceStore
import com.example.casha.domain.Loan
import com.example.casha.domain.LoanMode
import java.text.DecimalFormat

@Composable
fun CapturedPaymentConfirmDialog(
    payment: CapturedPayment,
    finance: FinanceStore,
    totalPending: Int = 1,
    loans: List<Loan> = emptyList(),
    onConfirm: (note: String, channel: String, amountCentavos: Long) -> Unit,
    onApplyToLoan: ((loan: Loan, installmentIndex: Int, channel: String, amountCentavos: Long) -> Unit)? = null,
    onDismiss: () -> Unit,
    saveError: String? = null
) {
    val withdrawal = remember(payment.id) { finance.withdrawalDetails(payment) }
    if (withdrawal != null) {
        WithdrawalReview(payment, withdrawal, finance, onConfirm, onDismiss, saveError)
        return
    }
    var note by remember(payment.id) { mutableStateOf(payment.merchant) }
    val accounts = remember(finance) { finance.moneyAccounts().filter { !it.isSavings } }
    var selectedChannel by remember(payment.id) {
        val initial = payment.channel.ifBlank { "Cash" }
        mutableStateOf(accounts.firstOrNull { it.name.equals(initial, ignoreCase = true) }?.name ?: accounts.firstOrNull()?.name ?: "Cash")
    }

    val amountFormatted = remember(payment.amountCentavos) {
        "₱" + DecimalFormat("#,##0.00").format(payment.amountCentavos / 100.0)
    }

    val existingEntries = remember(finance) { finance.cashEntries() }
    val potentialDuplicate = remember(payment.amountCentavos, payment.date, existingEntries) {
        existingEntries.firstOrNull { entry ->
            !entry.income && entry.amount == payment.amountCentavos &&
            (entry.date == payment.date || java.time.temporal.ChronoUnit.DAYS.between(entry.date, payment.date) in -1..1)
        }
    }

    val matchingLoanInstallment = remember(loans, payment.amountCentavos, payment.date) {
        loans.filter { it.mode == LoanMode.I_OWE && it.remaining > 0 }
            .flatMap { loan ->
                loan.installments().filter { it.remaining > 0 }.map { loan to it }
            }
            .firstOrNull { (_, inst) ->
                inst.amount == payment.amountCentavos || inst.remaining == payment.amountCentavos
            }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Payment detected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (totalPending > 1) "$totalPending captured transactions pending" else "Detected from recent payment notification",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (com.example.casha.reminders.PaymentNotificationParser.isSmsApp(payment.sourcePackage)) {
                    Text("Suggested from an SMS notification. Verify the payment and wallet in your banking app before saving.", style = MaterialTheme.typography.bodySmall)
                }

                // Amount banner
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Expense Amount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = amountFormatted,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Potential Duplicate Warning
                potentialDuplicate?.let { dup ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PilotIcon("Info", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                            Text(
                                text = "Possible duplicate: A ₱${DecimalFormat("#,##0.00").format(dup.amount / 100.0)} expense was already logged on ${dup.date} (${dup.channel}).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                // Matching Loan Dues Suggestion
                if (matchingLoanInstallment != null && onApplyToLoan != null) {
                    val (matchedLoan, matchedInst) = matchingLoanInstallment
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PilotIcon("Loans", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Text("Matches scheduled loan due", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                "Matches Installment ${matchedInst.index + 1} of ${matchedLoan.party} (due ${matchedInst.due}).",
                                style = MaterialTheme.typography.bodySmall
                            )
                            FilledTonalButton(
                                onClick = {
                                    onApplyToLoan(matchedLoan, matchedInst.index, selectedChannel, payment.amountCentavos)
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Apply as payment to ${matchedLoan.party}")
                            }
                        }
                    }
                }

                // Note / Merchant field
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Description / Merchant") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Account / Wallet selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Paid from account",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        accounts.forEach { acc ->
                            FilterChip(
                                selected = selectedChannel.equals(acc.name, ignoreCase = true),
                                onClick = { selectedChannel = acc.name },
                                label = { Text(acc.name) },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }

                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                // Action buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onConfirm(note, selectedChannel, payment.amountCentavos)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Confirm & log expense", fontWeight = FontWeight.Bold)
                    }

                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Dismiss / Not my expense", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun WithdrawalReview(
    payment: CapturedPayment,
    details: com.example.casha.reminders.ParsedPaymentNotification,
    finance: FinanceStore,
    onConfirm: (String, String, Long) -> Unit,
    onDismiss: () -> Unit,
    saveError: String? = null
) {
    val destination = finance.withdrawalCashAccount()
    val accounts = finance.moneyAccounts().filter { !it.isSavings && it.id != destination?.id }
    var source by remember(payment.id) { mutableStateOf(accounts.firstOrNull { it.name.equals(payment.channel, true) }?.name ?: "") }
    val cash = details.amountCentavos - details.feeCentavos
    fun money(value: Long) = com.example.casha.data.GeminiPromptContext.formatPesos(value)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Review ATM withdrawal") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bank debit: ${money(details.amountCentavos)}")
            Text("Cash received: ${money(cash)}")
            Text("Withdrawal fee: ${money(details.feeCentavos)}")
            Text("The debit includes the fee. Confirm only if the cash received matches your ATM receipt. This moves money to ${destination?.name ?: "your cash wallet"}; only the fee counts as spending.")
            Text("Transfer to: ${destination?.name ?: "No cash wallet available"}")
            if (destination == null) Text("Add or restore a spending wallet named Cash in Settings > Accounts & Wallets, then review this capture again.", color = MaterialTheme.colorScheme.error)
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Withdrawn from")
            accounts.forEach { account -> FilterChip(selected = source == account.name, onClick = { source = account.name }, label = { Text(account.name) }) }
            Text("Already logged this withdrawal manually? Dismiss this capture to avoid counting it twice.")
        } },
        confirmButton = { TextButton(onClick = { onConfirm("ATM withdrawal", source, cash) }, enabled = destination != null && accounts.any { it.name == source } && cash > 0) { Text("Confirm transfer & fee") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Dismiss") } })
}
