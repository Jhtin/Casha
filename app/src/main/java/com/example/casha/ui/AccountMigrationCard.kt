package com.example.casha.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.casha.data.FinanceStore
import com.example.casha.data.MigrationExecutionEngine
import com.example.casha.data.MigrationInspector
import com.example.casha.domain.ProfileTransferAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AccountMigrationCard(context: Context, accountId: String) {
    val sourceStore = remember { FinanceStore(context, "device") }
    val targetScope = remember(accountId) { FinanceStore.scopeFor(accountId) }
    val targetStore = remember(targetScope) { FinanceStore(context, targetScope) }

    val targetRevision by targetStore.changes.collectAsState()
    val sourceRevision by sourceStore.changes.collectAsState()

    // Determine if migration is eligible
    val hasDeviceData = remember(sourceRevision) {
        sourceStore.cashEntries().isNotEmpty() || sourceStore.preferences.contains("profile") || sourceStore.allMoneyAccounts().size > 3
    }
    val alreadyMigrated = remember(targetRevision) {
        targetStore.preferences.contains("migrated_from_scope")
    }
    var dismissed by remember(accountId) {
        mutableStateOf(targetStore.flag("dismissed_device_migration", false))
    }

    if (!hasDeviceData || alreadyMigrated || dismissed) return

    var showDialog by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Offline device records found", style = MaterialTheme.typography.titleMedium)
            Text(
                "You have budgets, cash logs, and wallets stored on this device. You can transfer them into your signed-in account or keep them separate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            statusMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { showDialog = true },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Review & transfer")
                }
                TextButton(
                    onClick = {
                        targetStore.setFlag("dismissed_device_migration", true)
                        dismissed = true
                    },
                    shape = MaterialTheme.shapes.small
                ) {
                    Text("Keep separate")
                }
            }
        }
    }

    if (showDialog) {
        DeviceMigrationDialog(
            sourceStore = sourceStore,
            targetStore = targetStore,
            onDismiss = { showDialog = false },
            onSuccess = { msg ->
                showDialog = false
                statusMessage = msg
            }
        )
    }
}

@Composable
fun DeviceMigrationDialog(
    sourceStore: FinanceStore,
    targetStore: FinanceStore,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Inspect dry-run manifest with source loans for dependency tracking
    val context = sourceStore.context ?: targetStore.context
    val sourceLoans = remember {
        if (context != null) {
            runCatching { com.example.casha.data.LoanStore(context, "loans.json").load() }.getOrDefault(emptyList())
        } else emptyList()
    }
    val salaryResolutions = remember { mutableStateMapOf<String, com.example.casha.domain.SalaryResolutionAction>() }

    val manifest = remember(sourceStore.inspectLedger(), targetStore.inspectLedger(), sourceLoans, salaryResolutions.toMap()) {
        MigrationInspector.inspect(
            source = sourceStore.inspectLedger(),
            target = targetStore.inspectLedger(),
            sourceLoans = sourceLoans,
            options = com.example.casha.domain.MigrationOptions(salaryResolutions = salaryResolutions.toMap())
        )
    }

    PilotModal(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Transfer device records") },
        category = "Account Migration",
        icon = "Sync",
        accent = MaterialTheme.colorScheme.primary,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Transfer your budgets and cash transactions from device storage into this signed-in account.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("• Wallets to transfer: ${manifest.walletMappingPlan.entries.size}", style = MaterialTheme.typography.bodySmall)
                        Text("• Cash transactions to import: ${manifest.cashEntriesToImport.size}", style = MaterialTheme.typography.bodySmall)
                        if (manifest.cashEntriesToSkip.isNotEmpty()) {
                            Text("• Identical duplicates skipped: ${manifest.cashEntriesToSkip.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val provisionalCount = manifest.paymentLinksToImport.count { it.amountCentavos > 0L }
                        if (manifest.paymentLinksToImport.isNotEmpty()) {
                            Text("• Linked loan payments: ${manifest.paymentLinksToImport.size} (${provisionalCount} provisional local balance effects until loan sync)", style = MaterialTheme.typography.bodySmall)
                        }
                        if (manifest.paydayHistoryToImport.isNotEmpty()) {
                            Text("• Historical payday cutoffs: ${manifest.paydayHistoryToImport.size}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (manifest.validation.warnings.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            manifest.validation.warnings.take(3).forEach { w ->
                                Text("• $w", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                    }
                }
                if (manifest.validation.ambiguousSalaryDeposits.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Duplicate salary decision required:", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                            manifest.validation.ambiguousSalaryDeposits.forEach { amb ->
                                Text("Cutoff ${amb.date}: ₱${amb.sourceAmount} in ${amb.sourceChannel} matches an existing salary deposit in this account.", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { salaryResolutions[amb.sourceEntryId] = com.example.casha.domain.SalaryResolutionAction.TREAT_AS_DUPLICATE },
                                        shape = MaterialTheme.shapes.small,
                                        border = if (salaryResolutions[amb.sourceEntryId] == com.example.casha.domain.SalaryResolutionAction.TREAT_AS_DUPLICATE) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                    ) {
                                        Text("Treat as duplicate")
                                    }
                                    OutlinedButton(
                                        onClick = { salaryResolutions[amb.sourceEntryId] = com.example.casha.domain.SalaryResolutionAction.KEEP_BOTH },
                                        shape = MaterialTheme.shapes.small,
                                        border = if (salaryResolutions[amb.sourceEntryId] == com.example.casha.domain.SalaryResolutionAction.KEEP_BOTH) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                    ) {
                                        Text("Keep both")
                                    }
                                }
                            }
                        }
                    }
                }
                Text(
                    "Device safety notice:\n• Your device originals will remain unchanged as a permanent backup.\n• Financial records and wallets reside on this device and will not automatically sync to other phones.",
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
                enabled = !busy && manifest.isReady && !manifest.hasUnresolvedSalaryAmbiguities,
                onClick = {
                    busy = true
                    errorMessage = null
                    coroutineScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            MigrationExecutionEngine.executeMigration(
                                sourceStore = sourceStore,
                                targetStore = targetStore,
                                manifest = manifest
                            )
                        }
                        busy = false
                        if (result.success) {
                            onSuccess("Successfully transferred ${result.cashEntriesImported} cash logs and ${result.walletsImported} wallets to this account.")
                        } else {
                            errorMessage = result.errorMessage ?: "Transfer could not be completed."
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
