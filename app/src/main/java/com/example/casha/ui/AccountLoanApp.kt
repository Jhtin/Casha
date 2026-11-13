package com.example.casha.ui

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.casha.data.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.example.casha.reminders.BackgroundWork
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AccountLoanApp(activity: Activity, reminderRequest:Pair<String,String>?=null, capturedRequest:Pair<String,String>?=null,widgetRequest:Pair<String,String>?=null,onWidgetHandled:()->Unit={}) {
    val auth = remember { FirebaseAuth.getInstance() }
    var uid by remember { mutableStateOf(auth.currentUser?.uid) }
    val local = remember { LoanStore(activity.applicationContext) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { uid = it.currentUser?.uid }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    // Changing identity destroys forms and clears the previous account's in-memory UI state.
    key(uid) {
        val accountId = uid
        LaunchedEffect(accountId) {
            val privacy=activity.getSharedPreferences("app_privacy",Context.MODE_PRIVATE)
            val nextScope=FinanceStore.scopeFor(accountId)
            val previous=privacy.getString("notificationScope",null)
            if(previous!=null&&previous!=nextScope)activity.getSystemService(NotificationManager::class.java).cancelAll()
            privacy.edit().putString("notificationScope",nextScope).apply()
            BackgroundWork.scheduleMorning(activity)
            val targetFinance = FinanceStore(activity.applicationContext, nextScope)
            com.example.casha.data.MigrationJournalStore(activity.applicationContext).recoverIfInterrupted(targetFinance)
        }
        val cloud = remember(accountId) { accountId?.let {
            CloudRepositorySessions.acquire(it) { CloudLoanRepository(activity.applicationContext, it, { auth.currentUser?.uid }, FirestoreLoanCloud(it, FirebaseFirestore.getInstance())) }
        } }
        DisposableEffect(cloud) { onDispose { if (accountId != null && cloud != null) CloudRepositorySessions.release(accountId,cloud) } }
        val financeStore = remember(accountId) { FinanceStore(activity.applicationContext, FinanceStore.scopeFor(accountId)) }
        CashaApp(cloud ?: local, FinanceStore.scopeFor(accountId), reminderRequest, true, capturedRequest,widgetRequest,onWidgetHandled) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (accountId != null) {
                    AccountMigrationCard(activity, accountId)
                }
                if (cloud != null) CloudSyncCard(cloud, local, financeStore)
                FirebaseAccountCard(activity)
            }
        }
    }
}

@Composable
private fun CloudSyncCard(repository: CloudLoanRepository, local: LoanStore, financeStore: FinanceStore? = null) {
    val state by repository.state.collectAsState()
    val repositoryRevision by repository.changes.collectAsState()
    val unresolvedCount = remember(repositoryRevision,state) { repository.reconciliationStore.loadUnresolvedRecords().size }
    var expanded by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(state.conflictLoan){if(state.conflictLoan!=null)expanded=true}
    var confirmation by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun perform(action: () -> Boolean) {
        busy = true
        scope.launch {
            val success = withContext(Dispatchers.IO) { runCatching(action).getOrDefault(false) }
            if (success) confirmation = null else message = "Could not complete this change. Existing or unreadable records may need review. Your device originals are retained."
            busy = false
        }
    }
    SettingsSection("Account synchronization",when {state.conflictLoan!=null->"Conflict needs review";state.pending>0->"${state.pending} queued • ${state.message}";else->state.message},expanded,{expanded=!expanded},icon="Sync") {
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
            if (state.pending > 0) Text("${state.pending} queued changes on this device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!state.enabled) Button({ confirmation = "enable" }, enabled = !busy,shape=MaterialTheme.shapes.medium) {
                    PilotIcon("Sync", Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Enable cloud sync")
                }
                else OutlinedButton({ repository.retry() }, enabled = !busy, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),shape=MaterialTheme.shapes.medium) {
                    PilotIcon("Refresh", Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry sync")
                }
                if (!repository.imported()) TextButton({ confirmation = "import" }, enabled = !busy,shape=MaterialTheme.shapes.small) { Text("Import device loans") }
                if (state.conflictLoan != null || unresolvedCount > 0) TextButton({ confirmation = "conflict" }, enabled = !busy,shape=MaterialTheme.shapes.small) { Text(if(unresolvedCount>0) "Review $unresolvedCount payment issues" else "Review conflict") }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmation == "import") {
        LoanMigrationDialog(
            localStore = local,
            repository = repository,
            financeStore = financeStore,
            onDismiss = { confirmation = null },
            onSuccess = { msg ->
                confirmation = null
                message = msg
            }
        )
    } else if (confirmation == "conflict") {
        val currentContext = LocalContext.current
        val fallbackFinance = remember(currentContext) { FinanceStore(currentContext, FinanceStore.scopeFor(null)) }
        ConflictResolutionDialog(
            repository = repository,
            financeStore = financeStore ?: fallbackFinance,
            onDismiss = { confirmation = null },
            onResolved = { msg ->
                confirmation = null
                message = msg
                repository.retry()
            }
        )
    } else confirmation?.let { action ->
        PilotModal(
            onDismissRequest = { if (!busy) confirmation = null },
            title = { Text(if (action == "enable") "Enable cloud sync?" else "Cloud sync") },
            category = "Cloud synchronization",
            icon = "Settings",
            accent = MaterialTheme.colorScheme.primary,
            text = { Text("Upload this account's queued changes and download its cloud loans. Device-only records will not be imported automatically.") },
            confirmButton = { Button(enabled = !busy, shape = MaterialTheme.shapes.medium, onClick = {
                perform { repository.enable() }
            }) { Text(if (busy) "Saving…" else "Enable") } },
            dismissButton = { TextButton({ confirmation = null }, enabled = !busy,shape=MaterialTheme.shapes.small) { Text("Cancel") } }
        )
    }
}
