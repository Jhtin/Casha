package com.example.casha.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.casha.data.CloudLoanRepository
import com.example.casha.data.FinanceStore
import com.example.casha.domain.Loan
import com.example.casha.domain.LoanMode
import com.example.casha.domain.MutationKind
import com.example.casha.domain.PhysicalSettlementStatus
import com.example.casha.domain.UnreconciledPaymentRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

private enum class ResolutionChoice {
    REASSIGN,
    EXTERNAL_EXPENSE,
    DISCARD_DUPLICATE
}

@Composable
fun ConflictResolutionDialog(
    repository: CloudLoanRepository,
    financeStore: FinanceStore,
    onDismiss: () -> Unit,
    onResolved: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val conflictLoanId = repository.state.collectAsState().value.conflictLoan
    val baseLoans = remember { repository.getBase() }
    val queue = remember { repository.getQueue() }
    val remoteLoan = remember(conflictLoanId, baseLoans) {
        baseLoans.find { it.id == conflictLoanId }
    }

    val unresolvedRecords = remember(conflictLoanId) {
        val existing = repository.reconciliationStore.loadUnresolvedRecords().filter {
            conflictLoanId == null || it.loanId == conflictLoanId
        }
        if (existing.isNotEmpty()) {
            existing
        } else if (conflictLoanId != null) {
            val queuedPayment = queue.lastOrNull {
                it.after.id == conflictLoanId && it.kind == MutationKind.PAYMENT
            }?.after?.payments?.lastOrNull()

            if (queuedPayment != null) {
                val link = financeStore.allPaymentLinks()[queuedPayment.id]
                val rec = UnreconciledPaymentRecord(
                    reconciliationId = "recon_auto_${queuedPayment.id}",
                    loanId = conflictLoanId,
                    loanParty = remoteLoan?.party ?: "Loan",
                    paymentId = queuedPayment.id,
                    installment = queuedPayment.installment,
                    amountCentavos = queuedPayment.amount,
                    isReceivable = remoteLoan?.mode == LoanMode.OWED_TO_ME,
                    walletId = link?.accountId ?: "unknown",
                    walletName = link?.accountName ?: "Linked wallet",
                    date = queuedPayment.date,
                    conflictReason = "Installment capacity conflict with remote cloud updates",
                    physicalSettlement = PhysicalSettlementStatus.UNKNOWN
                )
                repository.reconciliationStore.saveRecord(rec)
                listOf(rec)
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    val record = unresolvedRecords.firstOrNull()

    if (record == null) {
        PilotModal(
            onDismissRequest = onDismiss,
            title = { Text(if (conflictLoanId == null) "No conflicts pending" else "Review loan conflict") },
            category = "Cloud synchronization",
            icon = "Sync",
            text = { Column {
                Text(if (conflictLoanId == null) "There are no unresolved payment conflicts requiring manual review."
                    else "The cloud agreement differs from the queued change. Using the cloud version discards this loan's queued local edits, deletion or reversals. Cloud records remain unchanged.")
                errorMessage?.let { Text(it, color=MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { Button(enabled=!busy,onClick = {
                if (conflictLoanId == null) onDismiss() else {
                    busy=true
                    coroutineScope.launch {
                        val ok=withContext(Dispatchers.IO) { runCatching { repository.useCloudVersion() }.getOrDefault(false) }
                        busy=false
                        if(ok) onResolved("Cloud version retained; queued local changes discarded.")
                        else errorMessage="Could not resolve the conflict. Local records are retained."
                    }
                }
            }) { Text(if(conflictLoanId==null) "OK" else "Use cloud version") } },
            dismissButton = { if(conflictLoanId!=null) TextButton(onClick=onDismiss,enabled=!busy) { Text("Keep for review") } }
        )
        return
    }

    val remoteInstallments = remoteLoan?.installments() ?: emptyList()
    val openInstallments = remember(remoteLoan, record) {
        remoteInstallments.mapIndexedNotNull { idx, inst ->
            val remaining = inst.amount - inst.paid
            if (idx != record.installment && remaining >= record.amountCentavos) idx else null
        }
    }

    var selectedChoice by remember {
        mutableStateOf(
            if (openInstallments.isNotEmpty()) ResolutionChoice.REASSIGN else ResolutionChoice.EXTERNAL_EXPENSE
        )
    }

    var selectedTargetInstallment by remember(openInstallments) {
        mutableStateOf(openInstallments.firstOrNull() ?: 0)
    }

    fun formatPesos(centavos: Long): String =
        "₱" + DecimalFormat("#,##0.00").format(centavos / 100.0)

    PilotModal(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Review Payment Conflict") },
        category = "Cloud reconciliation",
        icon = "Sync",
        accent = MaterialTheme.colorScheme.error,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "A payment queued on this device conflicts with recent payments confirmed in the cloud. Review the discrepancy and choose how to reconcile your ledger.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Comparison Cards
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Cloud State (${remoteLoan?.party ?: "Loan"})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        val totalPaid = remoteInstallments.sumOf { it.paid }
                        val remaining = (remoteLoan?.total ?: 0L) - totalPaid
                        Text("• Total Paid: ${formatPesos(totalPaid)} of ${formatPesos(remoteLoan?.total ?: 0L)}", style = MaterialTheme.typography.bodySmall)
                        Text("• Outstanding: ${formatPesos(remaining)}", style = MaterialTheme.typography.bodySmall)
                        val contestedInst = remoteInstallments.getOrNull(record.installment)
                        if (contestedInst != null) {
                            Text(
                                "• Installment #${record.installment + 1}: Paid ${formatPesos(contestedInst.paid)} of ${formatPesos(contestedInst.amount)} (Capacity reached)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Local Device Payment", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text("• Amount: ${formatPesos(record.amountCentavos)}", style = MaterialTheme.typography.bodySmall)
                        Text("• Target: Installment #${record.installment + 1} (${record.date})", style = MaterialTheme.typography.bodySmall)
                        Text("• Linked Wallet: ${record.walletName}", style = MaterialTheme.typography.bodySmall)
                        Text("• Discrepancy: ${record.conflictReason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text("Choose Reconciliation Action:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                // Option A: Reassign Installment
                ResolutionOptionRow(
                    selected = selectedChoice == ResolutionChoice.REASSIGN,
                    enabled = openInstallments.isNotEmpty() && !busy,
                    title = "Reassign to another installment",
                    description = if (openInstallments.isNotEmpty()) {
                        "Apply this payment to open Installment #${selectedTargetInstallment + 1}. Maintains bank debit."
                    } else {
                        "No other open installments have sufficient capacity for this payment."
                    },
                    balanceImpact = "Predicted balance impact: ₱0.00 (Wallet deduction stays intact)",
                    onClick = { selectedChoice = ResolutionChoice.REASSIGN }
                )

                // Option B: External Cash Expense
                ResolutionOptionRow(
                    selected = selectedChoice == ResolutionChoice.EXTERNAL_EXPENSE,
                    enabled = !busy,
                    title = "Convert to independent cash expense",
                    description = "Keep the funds deducted from ${record.walletName} as an external cash transaction. Marks payment settled externally without recording it on the cloud loan.",
                    balanceImpact = "Predicted balance impact: ₱0.00 (Zero net variance on wallet)",
                    onClick = { selectedChoice = ResolutionChoice.EXTERNAL_EXPENSE }
                )

                // Option C: Discard Duplicate
                ResolutionOptionRow(
                    selected = selectedChoice == ResolutionChoice.DISCARD_DUPLICATE,
                    enabled = !busy,
                    title = "Discard duplicate local entry",
                    description = "Remove local payment queue entry and release provisional wallet hold. Use if money was never actually transferred.",
                    balanceImpact = "Predicted balance impact: +${formatPesos(record.amountCentavos)} (Restores wallet balance)",
                    onClick = { selectedChoice = ResolutionChoice.DISCARD_DUPLICATE }
                )

                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    errorMessage = null
                    coroutineScope.launch {
                        val success = withContext(Dispatchers.IO) {
                            when (selectedChoice) {
                                ResolutionChoice.REASSIGN -> {
                                    repository.reconciliationStore.resolveAsReassignedInstallment(
                                        recordId = record.reconciliationId,
                                        targetInstallment = selectedTargetInstallment,
                                        repository = repository
                                    )
                                }
                                ResolutionChoice.EXTERNAL_EXPENSE -> {
                                    repository.reconciliationStore.resolveAsExternalExpense(
                                        recordId = record.reconciliationId,
                                        financeStore = financeStore,
                                        repository = repository
                                    )
                                }
                                ResolutionChoice.DISCARD_DUPLICATE -> {
                                    repository.reconciliationStore.resolveAsDuplicateDiscard(
                                        recordId = record.reconciliationId,
                                        financeStore = financeStore,
                                        repository = repository
                                    )
                                }
                            }
                        }
                        busy = false
                        if (success) {
                            onResolved(
                                when (selectedChoice) {
                                    ResolutionChoice.REASSIGN -> "Reassigned payment to installment #${selectedTargetInstallment + 1}."
                                    ResolutionChoice.EXTERNAL_EXPENSE -> "Converted payment into external cash expense."
                                    ResolutionChoice.DISCARD_DUPLICATE -> "Discarded duplicate payment and restored wallet balance."
                                }
                            )
                        } else {
                            errorMessage = "Could not apply resolution. Please verify state and try again."
                        }
                    }
                }
            ) {
                Text(if (busy) "Applying..." else "Confirm Resolution")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ResolutionOptionRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    description: String,
    balanceImpact: String,
    onClick: () -> Unit
) {
    Card(
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadioButton(
                selected = selected,
                onClick = if (enabled) onClick else null,
                enabled = enabled
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
                Text(
                    text = balanceImpact,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                )
            }
        }
    }
}
