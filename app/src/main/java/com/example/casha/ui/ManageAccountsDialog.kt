package com.example.casha.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.casha.data.FinanceStore
import com.example.casha.domain.MoneyAccount
import com.example.casha.domain.PaydayPlanner
import com.example.casha.ui.theme.FinanceDesign
import java.util.UUID

@Composable
fun ManageAccountsDialog(
    finance: FinanceStore,
    loans: List<com.example.casha.domain.Loan> = emptyList(),
    onDismiss: () -> Unit
) {
    val revision by finance.changes.collectAsState()
    val allAccounts = remember(revision) { finance.allMoneyAccounts() }
    val accounts = remember(allAccounts) { allAccounts.filter { !it.isArchived } }
    val archivedAccounts = remember(allAccounts) { allAccounts.filter { it.isArchived } }
    val entries = remember(revision) { finance.cashEntries() }
    val profile = remember(revision) { runCatching { finance.profile() }.getOrNull() }
    val defaultSalaryId = remember(revision) { finance.defaultSalaryAccountId() }
    val paymentLinks = remember(revision) { finance.allPaymentLinks() }
    val balances = remember(allAccounts, entries, profile, defaultSalaryId, paymentLinks, loans) {
        PaydayPlanner.accountBalances(
            accounts = allAccounts,
            entries = entries,
            profile = profile,
            defaultSalaryAccountId = defaultSalaryId,
            paymentLinks = paymentLinks,
            loans = loans
        )
    }
    var editingAccount by remember { mutableStateOf<MoneyAccount?>(null) }
    var creatingAccount by remember { mutableStateOf(false) }
    var deletingAccount by remember { mutableStateOf<MoneyAccount?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }

    DetailDialog(
        onDismissRequest = onDismiss,
        title = { Text("Accounts & Wallets") },
        eyebrow = "Payment methods & savings",
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manage-accounts-column")
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                statusMessage?.let { msg ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = msg,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                Text(
                    "Categorize cash by channel. Accounts marked as 'Savings' protect your money and are excluded from the Safe to Spend allowance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PilotIcon("Payday", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Text("Default salary wallet", style = MaterialTheme.typography.titleSmall)
                            }
                            val currentDefault = accounts.find { it.id == defaultSalaryId }
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = currentDefault?.name ?: "None",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            "Payday salary is automatically routed to this wallet unless customized for a specific cutoff.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            accounts.forEach { acc ->
                                FilterChip(
                                    selected = acc.id == defaultSalaryId,
                                    onClick = {
                                        val ok = finance.setDefaultSalaryAccountId(acc.id)
                                        if (!ok) statusMessage = "Could not set default salary wallet."
                                    },
                                    label = { Text(acc.name) },
                                    leadingIcon = { PilotIcon(acc.icon, Modifier.size(14.dp)) }
                                )
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        accounts.forEachIndexed { index, acc ->
                            val bal = balances[acc.id] ?: 0L
                            Row(
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    color = if (acc.isSavings) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        PilotIcon(
                                            name = acc.icon,
                                            modifier = Modifier.size(20.dp),
                                            tint = if (acc.isSavings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = acc.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (acc.id == defaultSalaryId) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                                shape = RoundedCornerShape(50)
                                            ) {
                                                Text(
                                                    text = "Default Salary",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Balance: ${money(bal)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (bal < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Surface(
                                            color = if (acc.isSavings) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = if (acc.isSavings) "Savings · Safe" else "Spending",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (acc.isSavings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { editingAccount = acc; statusMessage = null },
                                        modifier = Modifier.size(36.dp).semantics { contentDescription = "Edit ${acc.name}" }
                                    ) {
                                        PilotIcon("Edit", Modifier.size(16.dp))
                                    }
                                    if (accounts.size > 1) {
                                        IconButton(
                                            onClick = { deletingAccount = acc; statusMessage = null },
                                            modifier = Modifier.size(36.dp).semantics { contentDescription = "Archive ${acc.name}" }
                                        ) {
                                            PilotIcon("Archive", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            if (index < accounts.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = { creatingAccount = true; statusMessage = null },
                    modifier = Modifier.fillMaxWidth().heightIn(min = FinanceDesign.TouchTarget),
                    shape = MaterialTheme.shapes.medium
                ) {
                    PilotIcon("Plus", Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add new account")
                }

                if (archivedAccounts.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showArchived = !showArchived },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PilotIcon("Archive", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "Archived Wallets (${archivedAccounts.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                PilotIcon(
                                    name = if (showArchived) "ChevronUp" else "ChevronDown",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (showArchived) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                                archivedAccounts.forEachIndexed { archIndex, arch ->
                                    val bal = balances[arch.id] ?: 0L
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(arch.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text("Historical balance: ${money(bal)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                        }
                                        TextButton(
                                            onClick = {
                                                val ok = finance.unarchiveMoneyAccount(arch.id)
                                                if (!ok) statusMessage = "Could not restore account."
                                            }
                                        ) {
                                            PilotIcon("Refresh", Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Restore")
                                        }
                                    }
                                    if (archIndex < archivedAccounts.lastIndex) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                PilotIcon("Close", Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Close")
            }
        },
        dismissButton = {}
    )

    // Create Account Dialog
    if (creatingAccount) {
        AccountEditDialog(
            initial = null,
            onDismiss = { creatingAccount = false },
            onSave = { newAcc ->
                val ok = finance.addMoneyAccount(newAcc)
                if (ok) {
                    creatingAccount = false
                    statusMessage = null
                } else {
                    statusMessage = "Could not add account. Name may already exist."
                }
            }
        )
    }

    // Edit Account Dialog
    editingAccount?.let { acc ->
        AccountEditDialog(
            initial = acc,
            onDismiss = { editingAccount = null },
            onSave = { updated ->
                val ok = finance.updateMoneyAccount(updated)
                if (ok) {
                    editingAccount = null
                    statusMessage = null
                } else {
                    statusMessage = "Could not update account. Name may conflict with another account."
                }
            }
        )
    }

    // Archive Confirmation Dialog
    deletingAccount?.let { acc ->
        PilotModal(
            onDismissRequest = { deletingAccount = null },
            title = { Text("Archive ${acc.name}?") },
            category = "Account archival",
            icon = "Archive",
            accent = MaterialTheme.colorScheme.primary,
            text = {
                Text("Are you sure you want to archive this account? It will be hidden from new payment selections, but all past transactions and ledger history will be safely preserved without altering your financial records.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ok = finance.archiveMoneyAccount(acc.id)
                        if (ok) {
                            deletingAccount = null
                            statusMessage = null
                        } else {
                            statusMessage = "Could not archive account. At least one active account must remain."
                            deletingAccount = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = MaterialTheme.shapes.medium
                ) {
                    PilotIcon("Archive", Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Archive account")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingAccount = null }, shape = MaterialTheme.shapes.small) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AccountEditDialog(
    initial: MoneyAccount?,
    onDismiss: () -> Unit,
    onSave: (MoneyAccount) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var isSavings by rememberSaveable { mutableStateOf(initial?.isSavings ?: false) }
    var icon by rememberSaveable { mutableStateOf(initial?.icon ?: if (initial?.isSavings == true) "Savings" else "Wallet") }
    var localError by remember { mutableStateOf<String?>(null) }

    PilotModal(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New account / wallet" else "Edit account") },
        category = "Account settings",
        icon = icon,
        accent = if (isSavings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40); localError = null },
                    label = { Text("Account name") },
                    placeholder = { Text("e.g. Cash on hand, GCash, BPI, Emergency Fund") },
                    singleLine = true,
                    isError = localError != null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
                localError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Icon", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Cash", "Wallet", "Bank", "Savings").forEach { iconOption ->
                            FilterChip(
                                selected = icon == iconOption,
                                onClick = { icon = iconOption },
                                label = { Text(iconOption) },
                                leadingIcon = { PilotIcon(iconOption, Modifier.size(16.dp)) }
                            )
                        }
                    }
                }

                Surface(
                    color = if (isSavings) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (isSavings) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Savings account", style = MaterialTheme.typography.titleSmall)
                                Text("Money here is safe and excluded from Safe to Spend", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = isSavings,
                                onCheckedChange = {
                                    isSavings = it
                                    if (it && icon == "Wallet") icon = "Savings"
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = name.trim()
                    if (clean.isBlank()) {
                        localError = "Please enter an account name."
                        return@Button
                    }
                    val targetId = initial?.id ?: UUID.randomUUID().toString()
                    onSave(MoneyAccount(id = targetId, name = clean, isSavings = isSavings, icon = icon))
                },
                enabled = name.isNotBlank(),
                shape = MaterialTheme.shapes.medium
            ) {
                PilotIcon("Check", Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (initial == null) "Create account" else "Save changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = MaterialTheme.shapes.small) {
                Text("Cancel")
            }
        }
    )
}
