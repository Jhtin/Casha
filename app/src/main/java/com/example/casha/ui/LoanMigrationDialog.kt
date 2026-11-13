package com.example.casha.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.casha.data.CloudLoanRepository
import com.example.casha.data.FinanceStore
import com.example.casha.data.LoanMigrationInspector
import com.example.casha.data.LoanStore
import com.example.casha.domain.LoanMigrationStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LoanMigrationDialog(
    localStore: LoanStore,
    repository: CloudLoanRepository,
    financeStore: FinanceStore? = null,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val guestLoans = remember { localStore.load() }
    val cloudLoans = remember { repository.load() }
    val cloudQueue = remember { repository.getQueue() }
    val paymentLinks = remember { financeStore?.allPaymentLinks() ?: emptyMap() }

    val manifest = remember(guestLoans, cloudLoans, cloudQueue) {
        LoanMigrationInspector.inspect(
            guestLoans = guestLoans,
            cloudLoans = cloudLoans,
            cloudQueue = cloudQueue,
            paymentLinks = paymentLinks,
            targetUid = repository.migrationJournal.targetUid
        )
    }

    PilotModal(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Transfer device loans") },
        category = "Cloud synchronization",
        icon = "Sync",
        accent = MaterialTheme.colorScheme.primary,
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Transfer your device-only loans and payment histories into this authenticated cloud account.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "• Loans to transfer: ${manifest.loansToImport.size}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "• Historical payments to upload: ${manifest.totalPaymentsToImport}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (manifest.totalReversalsToImport > 0) {
                            Text(
                                "• Historical payment reversals: ${manifest.totalReversalsToImport}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (manifest.linkedPaymentIds.isNotEmpty()) {
                            Text(
                                "• Linked account wallet payments: ${manifest.linkedPaymentIds.size}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (manifest.loansToSkip.isNotEmpty()) {
                            Text(
                                "• Already present in cloud (skipped): ${manifest.loansToSkip.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (manifest.collidingLoans.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Conflicting loans detected:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            manifest.collidingLoans.forEach { col ->
                                Text(
                                    "• ${col.loan.party} (${col.loan.id}): exists with different terms",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                manifest.warnings.forEach { w ->
                    Text(
                        "Note: $w",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    "Device safety notice:\n• Loans are initially saved on this device's queue and confirmed in cloud as sync runs.\n• Your device originals will remain untouched as an offline backup.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && manifest.isReady && manifest.loansToImport.isNotEmpty(),
                onClick = {
                    busy = true
                    errorMessage = null
                    coroutineScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            repository.enqueueLoanMigration(manifest)
                        }
                        busy = false
                        if (result.success) {
                            onSuccess("Successfully transferred ${result.loansEnqueued} loans and ${result.paymentsEnqueued} payments to this account's device queue. Cloud synchronization will confirm them.")
                        } else {
                            errorMessage = result.errorMessage ?: "Transfer could not be enqueued."
                        }
                    }
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Text(if (busy) "Transferring…" else "Confirm transfer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, shape = MaterialTheme.shapes.small) {
                Text("Cancel")
            }
        }
    )
}
