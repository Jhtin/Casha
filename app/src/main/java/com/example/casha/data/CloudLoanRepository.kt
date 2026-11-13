package com.example.casha.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.casha.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class CloudSyncState(val enabled: Boolean = false, val pending: Int = 0, val message: String = "Cloud sync is off.", val conflictLoan: String? = null)

/** Disk journal and cache are namespaced by account. No cloud write is submitted before consent. */
class CloudLoanRepository(context: Context? = null, private val uid: String, private val owner: () -> String?,
                          private val cloud: LoanCloud, namespace: String = "cloud_loans",
                          private val online: (() -> Boolean)? = null,
                          preferencesOverride: android.content.SharedPreferences? = null) : LoanRepository, AutoCloseable {
    private val appContext=context?.applicationContext
    private val backgroundEnabled=namespace=="cloud_loans" && context != null
    private val hash = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }
    private val prefs = preferencesOverride ?: context!!.getSharedPreferences("${namespace}_$hash", Context.MODE_PRIVATE)
    val migrationJournal = LoanMigrationJournalStore(context, uid)
    val reconciliationStore = PaymentReconciliationStore(context, uid, preferencesOverride = preferencesOverride)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var base = emptyList<Loan>()
    private var queue = emptyList<CloudMutation>()
    private var deletedIds=emptySet<String>()
    private var enabled = false
    private var healthy = true
    private var worker: Job? = null
    private var listener: AutoCloseable? = null
    private var closed = false
    private var paused = false
    private var retryAfterWorker = false
    override val changes = MutableStateFlow(0L)
    val state = MutableStateFlow(CloudSyncState())
    val receivedServer = MutableStateFlow(false)
    private val manager = context?.getSystemService(ConnectivityManager::class.java)
    private fun canConnect(): Boolean = online?.invoke() ?: (manager?.activeNetwork?.let {
        manager?.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } == true)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { retry() }
    }
    init {
        try {
            val root = JSONObject(prefs.getString("journal", "{}")!!)
            base = LoanCodec.decode(root.optString("base", "[]"))
            val deleted=root.optJSONArray("deletedIds")?:JSONArray()
            deletedIds=(0 until deleted.length()).map {deleted.getString(it)}.toSet()
            val pending = root.optJSONArray("queue") ?: JSONArray()
            queue = CloudQueueCodec.decode(pending)
            enabled = root.optBoolean("enabled", false)
        } catch (_: Exception) { healthy = false; state.value = CloudSyncState(message = "Account cache could not be read. Changes are disabled to protect it.") }
        if (healthy) state.value = CloudSyncState(enabled, queue.size, if (enabled) "Account cache loaded; waiting for cloud." else "Cloud sync is off. Account changes stay on this device.")
        manager?.registerDefaultNetworkCallback(networkCallback)
        if (healthy && enabled) retry()
    }
    @Synchronized override fun load(): List<Loan> {
        check(healthy && !closed && owner() == uid)
        val projection = base.filterNot {it.id in deletedIds}.associateBy { it.id }.toMutableMap()
        queue.forEach {if(it.kind==MutationKind.DELETE)projection.remove(it.after.id) else projection[it.after.id] = it.after}
        return projection.values.toList().sortedBy { it.id }
    }
    fun getQueue(): List<CloudMutation> = synchronized(this) { queue.toList() }
    fun getBase(): List<Loan> = synchronized(this) { base.toList() }
    private fun persist(newBase: List<Loan> = base, newQueue: List<CloudMutation> = queue, newEnabled: Boolean = enabled, newDeleted:Set<String> = deletedIds): Boolean {
        val pending = CloudQueueCodec.encode(newQueue)
        val root = JSONObject().put("base", LoanCodec.encode(newBase)).put("queue", pending).put("enabled", newEnabled).put("deletedIds",JSONArray(newDeleted.toList()))
        if (!prefs.edit().putString("journal", root.toString()).commit()) return false
        base = newBase; queue = newQueue; enabled = newEnabled; deletedIds=newDeleted; changes.value++
        state.value = state.value.copy(enabled = enabled, pending = queue.size)
        return true
    }
    @Synchronized override fun save(loans: List<Loan>, expected: List<Loan>?): Boolean {
        if (!healthy || closed || owner() != uid) return false
        val current = load()
        if (expected != null && current != expected) return false
        if (loans.any {it.id in deletedIds||queue.any {m->m.kind==MutationKind.DELETE&&m.after.id==it.id}})return false
        if (!loans.map { it.id }.containsAll(current.map { it.id })) return false
        val old = current.associateBy { it.id }
        val changed = loans.filter { old[it.id] != it }
        if (changed.size != 1 || loans.map { it.id }.distinct().size != loans.size) return false
        val mutation = CloudMutation(old[changed.single().id], changed.single())
        try { mutation.kind; CloudLoanDocument.encode(mutation.after, 1, mutation.kind.name) } catch (_: Exception) { return false }
        if (!persist(newQueue = queue + mutation)) return false
        state.value = state.value.copy(message = if (enabled) "Saved on device; waiting to sync." else "Saved for this account on this device. Enable sync to upload.")
        pump()
        if(enabled && backgroundEnabled && appContext != null) com.example.casha.reminders.BackgroundWork.requestSync(appContext)
        return true
    }
    @Synchronized override fun deleteUnpaid(loan:Loan):Boolean {
        if(!healthy||closed||owner()!=uid||load().find {it.id==loan.id}!=loan||loan.payments.isNotEmpty()||loan.reversals.isNotEmpty())return false
        val mutation=CloudMutation(loan,loan,deleting=true)
        mutation.kind
        if(!persist(newQueue=queue+mutation))return false
        state.value=state.value.copy(message=if(enabled)"Loan removed on device; deletion waiting to sync." else "Loan removed on device. Enable sync to upload deletion.")
        pump()
        if(enabled&&backgroundEnabled&&appContext!=null)com.example.casha.reminders.BackgroundWork.requestSync(appContext)
        return true
    }
    @Synchronized fun enable(): Boolean {
        if (!healthy || closed || owner() != uid || !persist(newEnabled = true)) return false
        retry(); return true
    }
    @Synchronized fun pauseBackupUploads(): Boolean {
        if (!healthy || closed || owner() != uid || queue.isNotEmpty() || worker != null) return false
        return prefs.edit().putBoolean("restorePaused",true).commit()
    }
    @Synchronized fun resumeBackupUploads(): Boolean {
        if (closed || owner() != uid || !prefs.edit().putBoolean("restorePaused",false).commit()) return false
        retry()
        return true
    }
    @Synchronized fun importLocal(loans: List<Loan>): Boolean = enqueueImport(loans,true)
    @Synchronized fun restoreBackup(loans: List<Loan>): Boolean = enqueueImport(loans,false)
    private fun enqueueImport(loans:List<Loan>,markImported:Boolean):Boolean {
        if (!healthy || closed || owner() != uid || (markImported && prefs.getBoolean("imported", false))) return false
        val existingIds = load().map { it.id }.toSet()
        // No ID remapping: retrying an import cannot create a duplicate loan.
        if (loans.any { it.id in existingIds||it.id in deletedIds||queue.any {m->m.kind==MutationKind.DELETE&&m.after.id==it.id} }) return false
        val mutations = try { loans.flatMap { CloudMutation.importHistory(it) }.also { entries ->
            entries.forEach { CloudLoanDocument.encode(it.after, 1, it.kind.name) }
        } } catch (_: Exception) { return false }
        // Import marker and journal must be committed atomically.
        val pending = CloudQueueCodec.encode(queue + mutations)
        val root = JSONObject().put("base", LoanCodec.encode(base)).put("queue", pending).put("enabled", enabled).put("deletedIds",JSONArray(deletedIds.toList()))
        if (!prefs.edit().putString("journal", root.toString()).putBoolean("imported", markImported||prefs.getBoolean("imported",false)).commit()) return false
        queue += mutations; changes.value++; state.value = state.value.copy(pending = queue.size, message = "Imported to this account's device queue. Original device records are retained.")
        pump()
        if(enabled && backgroundEnabled && appContext != null) com.example.casha.reminders.BackgroundWork.requestSync(appContext)
        return true
    }
    fun imported(): Boolean = prefs.getBoolean("imported", false)

    @Synchronized
    fun enqueueLoanMigration(
        manifest: LoanMigrationManifest,
        journalStore: LoanMigrationJournalStore = migrationJournal
    ): LoanMigrationResult {
        if (!healthy || closed || owner() != uid) {
            return LoanMigrationResult(
                success = false,
                stage = LoanMigrationStage.FAILED,
                loansEnqueued = 0,
                paymentsEnqueued = 0,
                reversalsEnqueued = 0,
                loansSkipped = 0,
                errorMessage = "Repository is not ready or user authentication mismatch."
            )
        }

        if (!manifest.isReady) {
            return LoanMigrationResult(
                success = false,
                stage = LoanMigrationStage.FAILED,
                loansEnqueued = 0,
                paymentsEnqueued = 0,
                reversalsEnqueued = 0,
                loansSkipped = 0,
                errorMessage = "Manifest contains unresolved conflicts or validation errors: ${manifest.errors.joinToString("; ")}"
            )
        }

        val currentCloudFingerprint = LoanMigrationInspector.computeLoansFingerprint(load())
        if (currentCloudFingerprint != manifest.targetCloudFingerprint) {
            return LoanMigrationResult(
                success = false,
                stage = LoanMigrationStage.FAILED,
                loansEnqueued = 0,
                paymentsEnqueued = 0,
                reversalsEnqueued = 0,
                loansSkipped = 0,
                errorMessage = "Stale migration manifest: Cloud account loans changed concurrently. Please refresh the preview."
            )
        }

        if (journalStore.hasPendingMigration()) {
            return LoanMigrationResult(
                success = false,
                stage = LoanMigrationStage.FAILED,
                loansEnqueued = 0,
                paymentsEnqueued = 0,
                reversalsEnqueued = 0,
                loansSkipped = 0,
                errorMessage = "An existing loan migration is currently pending or in progress for this account."
            )
        }

        val allMutations = mutableListOf<CloudMutation>()
        val perLoanStates = mutableMapOf<String, PerLoanJournalState>()

        for (loan in manifest.loansToImport) {
            val loanMutations = try {
                CloudMutation.importHistory(loan).also { entries ->
                    entries.forEach { CloudLoanDocument.encode(it.after, 1, it.kind.name) }
                }
            } catch (e: Exception) {
                return LoanMigrationResult(
                    success = false,
                    stage = LoanMigrationStage.FAILED,
                    loansEnqueued = 0,
                    paymentsEnqueued = 0,
                    reversalsEnqueued = 0,
                    loansSkipped = 0,
                    errorMessage = "Validation failure on loan '${loan.id}': ${e.message}"
                )
            }

            allMutations.addAll(loanMutations)
            perLoanStates[loan.id] = PerLoanJournalState(
                loanId = loan.id,
                totalMutations = loanMutations.size,
                confirmedMutations = 0,
                status = LoanCloudSyncState.PENDING_LOCAL
            )
        }

        val journalEntry = LoanMigrationJournalEntry(
            operationId = "loan_mig_${System.currentTimeMillis()}_${manifest.manifestId.take(8)}",
            manifestId = manifest.manifestId,
            timestampMs = System.currentTimeMillis(),
            schemaVersion = LoanMigrationJournalStore.CURRENT_SCHEMA_VERSION,
            stage = LoanMigrationStage.LOCAL_ENQUEUED,
            sourceScope = manifest.sourceScope,
            targetUid = uid,
            perLoanStates = perLoanStates
        )

        if (!journalStore.saveJournal(journalEntry)) {
            return LoanMigrationResult(
                success = false,
                stage = LoanMigrationStage.FAILED,
                loansEnqueued = 0,
                paymentsEnqueued = 0,
                reversalsEnqueued = 0,
                loansSkipped = 0,
                errorMessage = "Failed to write durable loan migration journal. Enqueue aborted."
            )
        }

        val newQueue = queue + allMutations
        val pending = CloudQueueCodec.encode(newQueue)
        val root = JSONObject()
            .put("base", LoanCodec.encode(base))
            .put("queue", pending)
            .put("enabled", enabled)
            .put("deletedIds", JSONArray(deletedIds.toList()))

        if (!prefs.edit().putString("journal", root.toString()).putBoolean("imported", true).commit()) {
            journalStore.clearJournal()
            return LoanMigrationResult(
                success = false,
                stage = LoanMigrationStage.FAILED,
                loansEnqueued = 0,
                paymentsEnqueued = 0,
                reversalsEnqueued = 0,
                loansSkipped = 0,
                errorMessage = "Failed to commit enqueued mutations to account storage."
            )
        }

        queue = newQueue
        changes.value++
        state.value = state.value.copy(
            pending = queue.size,
            message = "Transferred ${manifest.loansToImport.size} loans to device queue. ${if (enabled) "Syncing with cloud..." else "Enable cloud sync to upload."}"
        )

        pump()
        if (enabled && backgroundEnabled && appContext != null) com.example.casha.reminders.BackgroundWork.requestSync(appContext)

        return LoanMigrationResult(
            success = true,
            stage = LoanMigrationStage.LOCAL_ENQUEUED,
            loansEnqueued = manifest.loansToImport.size,
            paymentsEnqueued = manifest.totalPaymentsToImport,
            reversalsEnqueued = manifest.totalReversalsToImport,
            loansSkipped = manifest.loansToSkip.size
        )
    }
    @Synchronized fun retry() {
        if (!healthy || closed || !enabled || owner() != uid) return
        if (!canConnect()) { state.value = state.value.copy(message = "Offline. Changes are saved on this device and will retry when connected."); return }
        if (worker != null) retryAfterWorker = true
        paused = false
        if (listener == null) listener = cloud.listenChanges({ records, removed, fromServer ->
            synchronized(this) {
                if (!closed && owner() == uid) {
                    val updated = if (fromServer) records else (records + base).associateBy { it.id }.values.toList()
                    if (!persist(newBase = updated,newDeleted=deletedIds+removed)) state.value = state.value.copy(message = "Could not save the cloud cache. Retry when storage is available.")
                    else {
                        if(fromServer)receivedServer.value=true
                        if(queue.isEmpty())state.value = CloudSyncState(true, 0, if (fromServer) "Synced" else "Showing cached cloud records; waiting for connection.")
                    }
                }
            }
        }, { error -> synchronized(this) {
            if (!closed && owner() == uid) {
                state.value = state.value.copy(message = syncFailureMessage(error))
                listener?.close(); listener = null
            }
        } })
        pump()
    }
    @Synchronized private fun pump() {
        if (prefs.getBoolean("restorePaused",false)) return
        if (closed || !enabled || !healthy || paused || worker != null || owner() != uid || state.value.conflictLoan != null || !canConnect()) return
        worker = scope.launch(start = CoroutineStart.LAZY) {
            while (isActive) {
                val mutation = synchronized(this@CloudLoanRepository) {
                    if (closed || owner() != uid) null else queue.firstOrNull()
                } ?: break
                try {
                    val accepted = cloud.apply(mutation)
                    synchronized(this@CloudLoanRepository) {
                        if (closed || owner() != uid) return@launch
                        val existing = base.find { it.id == accepted.id }
                        // A newer server snapshot may arrive before this transaction's completion callback.
                        val updated = if (existing == null || existing == mutation.before || existing == accepted)
                            base.filterNot { it.id == accepted.id } + accepted else base
                        val deleting=mutation.kind==MutationKind.DELETE
                        val resolved=if(deleting)updated.filterNot {it.id==mutation.after.id} else updated
                        if (!persist(resolved, queue.drop(1),newDeleted=if(deleting)deletedIds+mutation.after.id else deletedIds)) throw IllegalStateException("Journal save failed")
                        migrationJournal.recordMutationConfirmed(accepted.id, (accepted.payments.size + accepted.reversals.size + 1).toLong())
                        val jEntry = migrationJournal.loadJournal()
                        if (jEntry != null && jEntry.stage == LoanMigrationStage.CLOUD_ACKNOWLEDGED) {
                            migrationJournal.verifyAndComplete(load())
                        }
                        state.value = CloudSyncState(true, queue.size, if (queue.isEmpty()) "Synced" else "Syncing ${queue.size} changes…")
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    synchronized(this@CloudLoanRepository) {
                        if (closed || owner() != uid) return@launch
                        paused = true
                        val conflict = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<CloudConflict>().firstOrNull()
                        if (conflict != null && conflict.current != null) {
                            val remoteLoan = conflict.current
                            val mergeResult = LoanThreeWayMerger.merge(base, queue, remoteLoan, appContext?.let { FinanceStore(it, hash).allPaymentLinks() } ?: emptyMap())
                            when (mergeResult.status) {
                                ThreeWayMergeStatus.ALREADY_SATISFIED -> {
                                    val updatedBase = base.filterNot { it.id == remoteLoan.id } + remoteLoan
                                    val remainingQueue = queue.filterNot { it.after.id == remoteLoan.id }
                                    persist(newBase = updatedBase, newQueue = remainingQueue)
                                    paused = false
                                    retryAfterWorker = true
                                    state.value = CloudSyncState(true, remainingQueue.size, "Changes on ${remoteLoan.party} already satisfied in cloud.")
                                }
                                ThreeWayMergeStatus.AUTO_REBASED -> {
                                    val updatedBase = base.filterNot { it.id == remoteLoan.id } + remoteLoan
                                    val otherQueue = queue.filterNot { it.after.id == remoteLoan.id }
                                    val rebasedQueue = otherQueue + mergeResult.rebasedMutations
                                    persist(newBase = updatedBase, newQueue = rebasedQueue)
                                    paused = false
                                    retryAfterWorker = true
                                    state.value = CloudSyncState(true, rebasedQueue.size, "Automatically rebased non-conflicting payments on ${remoteLoan.party}.")
                                }
                                ThreeWayMergeStatus.CONFLICT_UNRESOLVED -> {
                                    val updated = base.filterNot { it.id == mutation.after.id } + remoteLoan
                                    persist(newBase = updated)
                                    mergeResult.unreconciledPayments.forEach { rec ->
                                        reconciliationStore.saveRecord(rec)
                                    }
                                    migrationJournal.loadJournal()?.let { j ->
                                        val loanState = j.perLoanStates[mutation.after.id]
                                        if (loanState != null) {
                                            val updatedStates = j.perLoanStates + (mutation.after.id to loanState.copy(status = LoanCloudSyncState.CONFLICT))
                                            migrationJournal.saveJournal(j.copy(stage = LoanMigrationStage.CONFLICT_PAUSED, perLoanStates = updatedStates))
                                        }
                                    }
                                    state.value = CloudSyncState(true, queue.size, "Conflicting payments on ${mutation.after.party}. Local payment retained for reconciliation.", mutation.after.id)
                                }
                            }
                        } else if (conflict != null) {
                            val updated = base.filterNot { it.id == mutation.after.id }
                            persist(newBase = updated)
                            state.value = CloudSyncState(true, queue.size, "Loan was removed or marked deleted on cloud.", mutation.after.id)
                        } else {
                            migrationJournal.markLoanFailed(mutation.after.id, e.message ?: "Sync failure")
                            state.value = state.value.copy(message = syncFailureMessage(e))
                        }
                    }
                    break
                }
            }
        }
        worker!!.invokeOnCompletion {
            synchronized(this) {
                worker = null
                if (retryAfterWorker) { retryAfterWorker = false; paused = false }
                if (queue.isNotEmpty()) pump()
            }
        }
        worker!!.start()
    }
    @Synchronized
    fun discardConflictingMutation(loanId: String, paymentId: String): Boolean {
        if (!healthy || closed || owner() != uid) return false
        val original = base.find { it.id == loanId }
        if (original?.payments?.any { it.id == paymentId } == true) return false
        val target = queue.any { it.after.id == loanId && it.kind == MutationKind.PAYMENT && it.after.payments.last().id == paymentId }
        if (!target) return true
        // Rebuild later commands so an old after-snapshot cannot resurrect the discarded payment.
        val filtered = runCatching {
            var current = original
            val result = mutableListOf<CloudMutation>()
            queue.forEach { mutation ->
                if (mutation.after.id != loanId) result += mutation
                else {
                    val next = when (mutation.kind) {
                        MutationKind.PAYMENT -> {
                            val payment = mutation.after.payments.last()
                            if (payment.id == paymentId) null else current!!.pay(payment.installment,payment.amount,payment.id,payment.date)
                        }
                        MutationKind.REVERSAL -> {
                            val reversal = mutation.after.reversals.last()
                            require(reversal.paymentId != paymentId) { "A reversal depends on this payment; review the history first." }
                            current!!.reverse(reversal.paymentId,reversal.date,reversal.reason)
                        }
                        MutationKind.EDIT -> current!!.edit(mutation.after.party,mutation.after.total,mutation.after.count,mutation.after.firstDue,mutation.after.frequency)
                        MutationKind.CREATE -> mutation.after
                        MutationKind.DELETE -> error("Deletion cannot be reconciled as a payment.")
                    }
                    if (next != null) {
                        if (next != current) result += CloudMutation(current,next).also { CloudLoanDocument.encode(next,1,it.kind.name) }
                        current = next
                    }
                }
            }
            result.toList()
        }.getOrElse { return false }
        val success = persist(newQueue = filtered)
        if (success) {
            if (state.value.conflictLoan == loanId && filtered.none { it.after.id == loanId }) {
                state.value = state.value.copy(conflictLoan = null, message = "Conflict resolved.")
                paused = false
                pump()
            }
        }
        return success
    }

    @Synchronized
    fun reassignQueuedPaymentInstallment(loanId: String, paymentId: String, newInstallment: Int): Boolean {
        if (!healthy || closed || owner() != uid) return false
        val loanBase = base.find { it.id == loanId } ?: return false
        if (newInstallment !in 0 until loanBase.count) return false
        val remainingInTarget = loanBase.installments()[newInstallment].remaining

        val loanMutations = queue.filter { it.after.id == loanId }
        val targetMutation = loanMutations.find { it.kind == MutationKind.PAYMENT && it.after.payments.lastOrNull()?.id == paymentId } ?: return false
        val p = targetMutation.after.payments.last()

        if (p.amount > remainingInTarget) return false

        val rebasedPayment = p.copy(installment = newInstallment)
        val otherMutations = queue.filterNot { it.after.id == loanId }

        var cur = loanBase
        val newLoanMutations = mutableListOf<CloudMutation>()
        val validReplay = runCatching {
        loanMutations.forEach { m ->
            if (m.kind == MutationKind.PAYMENT && m.after.payments.lastOrNull()?.id == paymentId) {
                val next = cur.pay(rebasedPayment.installment, rebasedPayment.amount, rebasedPayment.id, rebasedPayment.date)
                newLoanMutations += CloudMutation(cur, next)
                cur = next
            } else if (m.kind == MutationKind.PAYMENT) {
                val pay = m.after.payments.last()
                val next = cur.pay(pay.installment, pay.amount, pay.id, pay.date)
                newLoanMutations += CloudMutation(cur, next)
                cur = next
            } else if (m.kind == MutationKind.REVERSAL) {
                val rev = m.after.reversals.last()
                val next = cur.reverse(rev.paymentId, rev.date, rev.reason)
                newLoanMutations += CloudMutation(cur, next)
                cur = next
            } else if (m.kind == MutationKind.EDIT) {
                val next = cur.edit(m.after.party,m.after.total,m.after.count,m.after.firstDue,m.after.frequency)
                newLoanMutations += CloudMutation(cur,next)
                cur = next
            } else {
                error("Review this non-payment mutation before reassigning an installment.")
            }
        }
        newLoanMutations.forEach { CloudLoanDocument.encode(it.after,1,it.kind.name) }
        }.isSuccess
        if (!validReplay) return false

        val success = persist(newQueue = otherMutations + newLoanMutations)
        if (success) {
            if (state.value.conflictLoan == loanId) {
                state.value = state.value.copy(conflictLoan = null, message = "Reassigned installment. Resuming cloud sync...")
                paused = false
                pump()
            }
        }
        return success
    }

    @Synchronized
    fun useCloudVersion(): Boolean {
        if (owner() != uid || closed) return false
        val id = state.value.conflictLoan ?: return false
        val affectedMutations = queue.filter { it.after.id == id }
        val remoteLoan = base.find { it.id == id }
        val links = appContext?.let { FinanceStore(it, hash).allPaymentLinks() } ?: emptyMap()
        affectedMutations.forEach { m ->
            if (m.kind == MutationKind.PAYMENT) {
                val p = m.after.payments.last()
                if (remoteLoan == null || remoteLoan.payments.none { it.id == p.id }) {
                    val link = links[p.id]
                    if (!reconciliationStore.saveRecord(
                        UnreconciledPaymentRecord(
                            reconciliationId = "recon_discard_" + p.id,
                            loanId = id,
                            loanParty = m.after.party,
                            paymentId = p.id,
                            installment = p.installment,
                            amountCentavos = p.amount,
                            isReceivable = m.after.mode == LoanMode.OWED_TO_ME,
                            walletId = link?.accountId ?: "unresolved_wallet",
                            walletName = link?.accountName ?: "Unresolved Wallet",
                            date = p.date,
                            conflictReason = "Local queued mutation removed when adopting cloud version; preserved in audit log.",
                            physicalSettlement = PhysicalSettlementStatus.UNKNOWN
                        )
                    )) return false
                }
            }
        }
        if (!persist(newQueue = queue.filterNot { it.after.id == id })) return false
        state.value = CloudSyncState(enabled, queue.size, "Using the cloud version. Local unconfirmed payments preserved in reconciliation store.")
        paused = false
        pump()
        return true
    }
    override fun isReady(): Boolean {
        if (!healthy || closed || owner() != uid) return false
        if (enabled && !receivedServer.value) return false
        return true
    }
    override fun journalSnapshot(): String? = prefs.getString("journal", null)
    @Synchronized override fun restoreSnapshot(snapshotJson: String): Boolean {
        if (closed || owner() != uid) return false
        val ok = prefs.edit().putString("journal", snapshotJson).commit()
        if (ok) {
            val root = JSONObject(snapshotJson)
            base = LoanCodec.decode(root.optString("base", "[]"))
            val deleted = root.optJSONArray("deletedIds") ?: JSONArray()
            deletedIds = (0 until deleted.length()).map { deleted.getString(it) }.toSet()
            val pending = root.optJSONArray("queue") ?: JSONArray()
            queue = CloudQueueCodec.decode(pending)
            enabled = root.optBoolean("enabled", false)
            changes.value++
            state.value = CloudSyncState(enabled, queue.size, "Restored journal snapshot.")
        }
        return ok
    }
    @Synchronized override fun rollbackRestore(addedLoans: List<Loan>): Boolean {
        if (!healthy || closed || owner() != uid) return false
        val addedIds = addedLoans.map { it.id }.toSet()
        val filteredQueue = queue.filterNot { it.after.id in addedIds }
        return persist(newQueue = filteredQueue)
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; listener?.close(); listener = null; scope.cancel()
        manager?.unregisterNetworkCallback(networkCallback)
    }
}
