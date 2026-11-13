package com.example.casha

import com.example.casha.data.*
import com.example.casha.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

class LoanConflictReconciliationTest {

    private val today = LocalDate.of(2026, 11, 1)

    @Before
    fun setUp() {
        val checkpointDir = File(System.getProperty("java.io.tmpdir", "."), "casha_checkpoints/checkpoints")
        if (checkpointDir.exists()) {
            checkpointDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun sampleLoan(
        id: String = "loan_1",
        party: String = "Alice",
        total: Long = 10_000L,
        count: Int = 2,
        payments: List<Payment> = emptyList(),
        reversals: List<PaymentReversal> = emptyList()
    ): Loan {
        return Loan(
            id = id,
            party = party,
            mode = LoanMode.I_OWE,
            total = total,
            count = count,
            firstDue = today,
            frequency = Frequency.MONTHLY,
            payments = payments,
            reversals = reversals
        )
    }

    private fun createRepoWithBase(
        uid: String,
        baseLoans: List<Loan>,
        fakeCloud: LoanCloud,
        prefs: FakeSharedPreferences = FakeSharedPreferences()
    ): CloudLoanRepository {
        val root = JSONObject()
            .put("base", LoanCodec.encode(baseLoans))
            .put("queue", JSONArray())
            .put("enabled", false)
            .put("deletedIds", JSONArray())
        prefs.edit().putString("journal", root.toString()).commit()
        return CloudLoanRepository(
            uid = uid,
            owner = { uid },
            cloud = fakeCloud,
            online = { true },
            preferencesOverride = prefs
        )
    }

    /**
     * Requirement 1: Two non-conflicting payments on different installments
     * (local on installment 0, remote on installment 1) auto-rebase cleanly without user prompt.
     */
    @Test
    fun test01_differentInstallmentPayments_autoRebaseCleanly() {
        val baseLoan = sampleLoan()
        val remoteLoan = baseLoan.pay(1, 5_000L, "p_remote", today)
        val localLoan = baseLoan.pay(0, 5_000L, "p_local", today)
        val localMutation = CloudMutation(before = baseLoan, after = localLoan)

        val result = LoanThreeWayMerger.merge(
            base = listOf(baseLoan),
            localQueue = listOf(localMutation),
            remoteLoan = remoteLoan,
            paymentLinks = emptyMap()
        )

        assertEquals(ThreeWayMergeStatus.AUTO_REBASED, result.status)
        assertTrue(result.unreconciledPayments.isEmpty())
        assertEquals(1, result.rebasedMutations.size)

        val rebased = result.rebasedMutations.first()
        assertEquals(remoteLoan, rebased.before)
        assertEquals(2, rebased.after.payments.size)
        assertTrue(rebased.after.payments.any { it.id == "p_remote" })
        assertTrue(rebased.after.payments.any { it.id == "p_local" })
        assertEquals(5_000L, rebased.after.installments()[0].paid)
        assertEquals(5_000L, rebased.after.installments()[1].paid)
    }

    /**
     * Requirement 2: Capacity conflict (local installment 0 tries to add ₱5,000 when
     * remote already has ₱6,000 of ₱10,000 installment paid) correctly triggers unreconciled
     * payment record and halts queue upload.
     */
    @Test
    fun test02_capacityConflict_createsUnreconciledRecordAndHaltsQueue() = runBlocking {
        // Loan total 20,000 across 2 installments of 10,000 each
        val baseLoan = sampleLoan(total = 20_000L, count = 2)
        val remoteLoan = baseLoan.pay(0, 6_000L, "p_remote", today)
        val localLoan = baseLoan.pay(0, 5_000L, "p_local", today)

        val fakeCloud = FakeLoanCloud()
        fakeCloud.remoteLoans[baseLoan.id] = remoteLoan

        val repo = createRepoWithBase(
            uid = "user_test",
            baseLoans = listOf(baseLoan),
            fakeCloud = fakeCloud
        )

        // Queue the local mutation
        val saved = repo.save(listOf(localLoan), listOf(baseLoan))
        assertTrue("Local mutation saved to queue", saved)
        repo.enable()

        var attempts = 0
        while (repo.state.value.conflictLoan == null && attempts < 20) {
            delay(50)
            attempts++
        }

        assertEquals(baseLoan.id, repo.state.value.conflictLoan)
        val records = repo.reconciliationStore.loadRecords()
        assertEquals(1, records.size)
        val record = records.first()
        assertEquals("p_local", record.paymentId)
        assertEquals(5_000L, record.amountCentavos)
        assertEquals(PhysicalSettlementStatus.UNKNOWN, record.physicalSettlement)
        assertTrue(record.conflictReason.contains("capacity exceeded"))
    }

    /**
     * Requirement 3: Three-way merge correctly identifies unchanged shared payments
     * vs newly introduced payments sharing IDs with the baseline.
     */
    @Test
    fun test03_threeWayMerge_identifiesUnchangedSharedPayments() {
        val baseLoan = sampleLoan(total = 20_000L, count = 2).pay(0, 3_000L, "p_shared", today)
        val remoteLoan = baseLoan.pay(1, 5_000L, "p_remote_new", today)
        val localLoan = baseLoan.pay(1, 4_000L, "p_local_new", today)
        val localMutation = CloudMutation(before = baseLoan, after = localLoan)

        val result = LoanThreeWayMerger.merge(
            base = listOf(baseLoan),
            localQueue = listOf(localMutation),
            remoteLoan = remoteLoan,
            paymentLinks = emptyMap()
        )

        assertEquals(ThreeWayMergeStatus.AUTO_REBASED, result.status)
        val rebasedLoan = result.rebasedMutations.first().after
        assertEquals(3, rebasedLoan.payments.size)
        assertEquals(listOf("p_shared", "p_remote_new", "p_local_new"), rebasedLoan.payments.map { it.id })
    }

    /**
     * Requirement 4: Duplicate payment IDs with conflicting amounts or dates trigger
     * a content conflict rather than being silently merged.
     */
    @Test
    fun test04_duplicatePaymentId_differentContent_triggersConflict() {
        val baseLoan = sampleLoan()
        val remoteLoan = baseLoan.pay(0, 5_000L, "p_dup", today)
        val localLoan = baseLoan.pay(0, 4_000L, "p_dup", today)
        val localMutation = CloudMutation(before = baseLoan, after = localLoan)

        val result = LoanThreeWayMerger.merge(
            base = listOf(baseLoan),
            localQueue = listOf(localMutation),
            remoteLoan = remoteLoan,
            paymentLinks = emptyMap()
        )

        assertEquals(ThreeWayMergeStatus.CONFLICT_UNRESOLVED, result.status)
        assertEquals(1, result.unreconciledPayments.size)
        assertTrue(result.unreconciledPayments.first().conflictReason.contains("Payment ID collision"))
    }

    /**
     * Requirement 5: Idempotent acknowledgment matching: local mutation already applied
     * on remote loan is cleared from queue without triggering error or second payment.
     */
    @Test
    fun test05_idempotentAcknowledgment_clearsQueueWithoutDuplicate() = runBlocking {
        val baseLoan = sampleLoan()
        val loanWithPayment = baseLoan.pay(0, 5_000L, "p_ack", today)

        val fakeCloud = FakeLoanCloud()
        fakeCloud.remoteLoans[baseLoan.id] = loanWithPayment // Remote already has this payment

        val repo = createRepoWithBase(
            uid = "user_test",
            baseLoans = listOf(baseLoan),
            fakeCloud = fakeCloud
        )

        // Queue local mutation for the same payment
        val saved = repo.save(listOf(loanWithPayment), listOf(baseLoan))
        assertTrue("Local mutation saved to queue", saved)
        assertEquals(1, repo.getQueue().size)

        repo.enable()

        var attempts = 0
        while (repo.getQueue().isNotEmpty() && attempts < 20) {
            delay(50)
            attempts++
        }

        assertEquals(0, repo.getQueue().size)
        assertNull(repo.state.value.conflictLoan)
        // Verify remote was not updated again with duplicate payment
        assertEquals(1, fakeCloud.remoteLoans[baseLoan.id]?.payments?.size)
    }

    /**
     * Requirement 6: Local payment rebase correctly respects intermediate remote reversals
     * that altered installment capacity.
     */
    @Test
    fun test06_remoteReversalRestoresCapacity_localPaymentRebasesCleanly() {
        val baseLoan = sampleLoan(total = 20_000L, count = 2).pay(0, 5_000L, "p_init", today)
        val remoteLoan = baseLoan.reverse("p_init", today, "Remote bounced/reversed")

        // Local queues a new payment on installment 0 (capacity intact)
        val localLoan = baseLoan.pay(0, 5_000L, "p_new", today.plusDays(1))
        val localMutation = CloudMutation(before = baseLoan, after = localLoan)

        val result = LoanThreeWayMerger.merge(
            base = listOf(baseLoan),
            localQueue = listOf(localMutation),
            remoteLoan = remoteLoan,
            paymentLinks = emptyMap()
        )

        assertEquals(ThreeWayMergeStatus.AUTO_REBASED, result.status)
        val rebasedLoan = result.rebasedMutations.first().after
        assertEquals(1, rebasedLoan.reversals.size)
        assertEquals(5_000L, rebasedLoan.installments()[0].paid)
    }

    /**
     * Requirement 7: Physical settlement resolution: converting unconfirmed payment to
     * cash entry removes payment link, creates CashEntry, and leaves wallet balance with
     * 0-centavo net change.
     */
    @Test
    fun test07_convertUnconfirmedToExternalExpense_leavesZeroNetVariance() {
        val financePrefs = FakeSharedPreferences()
        val financeStore = FinanceStore(context = null, scope = "test_user", preferences = financePrefs)

        val wallet = MoneyAccount("acc_gcash", "GCash", isSavings = false, icon = "Wallet")
        financeStore.saveMoneyAccounts(listOf(wallet))
        financeStore.addCash(CashEntry("init_deposit", income = true, amount = 20_000L, date = today, channel = "GCash", note = "Deposit"))

        // Add provisional payment link for 5,000
        val paymentLink = PaymentAccountLink(
            paymentId = "p_unconfirmed",
            loanId = "loan_1",
            accountId = "acc_gcash",
            accountName = "GCash",
            isReceivable = false,
            amountCentavos = 5_000L,
            date = today
        )
        financeStore.savePaymentLink(paymentLink)

        val balanceBefore = PaydayPlanner.accountBalances(
            accounts = financeStore.allMoneyAccounts(),
            entries = financeStore.cashEntries(),
            profile = financeStore.profile(),
            defaultSalaryAccountId = financeStore.defaultSalaryAccountId(),
            paymentLinks = financeStore.allPaymentLinks(),
            loans = emptyList()
        )["acc_gcash"]
        assertEquals(15_000L, balanceBefore)

        val reconPrefs = FakeSharedPreferences()
        val reconStore = PaymentReconciliationStore(context = null, targetUid = "test_user", preferencesOverride = reconPrefs)
        val record = UnreconciledPaymentRecord(
            reconciliationId = "recon_1",
            loanId = "loan_1",
            loanParty = "Alice",
            paymentId = "p_unconfirmed",
            installment = 0,
            amountCentavos = 5_000L,
            isReceivable = false,
            walletId = "acc_gcash",
            walletName = "GCash",
            date = today,
            conflictReason = "Rejected by remote cloud",
            physicalSettlement = PhysicalSettlementStatus.UNKNOWN
        )
        reconStore.saveRecord(record)

        val success = reconStore.resolveAsExternalExpense(record.reconciliationId, financeStore)
        assertTrue(success)

        val balanceAfter = PaydayPlanner.accountBalances(
            accounts = financeStore.allMoneyAccounts(),
            entries = financeStore.cashEntries(),
            profile = financeStore.profile(),
            defaultSalaryAccountId = financeStore.defaultSalaryAccountId(),
            paymentLinks = financeStore.allPaymentLinks(),
            loans = emptyList()
        )["acc_gcash"]

        // 0-centavo net change: 15,000 before -> 15,000 after
        assertEquals(15_000L, balanceAfter)
        assertNull(financeStore.allPaymentLinks()["p_unconfirmed"])
        assertTrue(financeStore.cashEntries().any { it.id == "reconciled_cash_p_unconfirmed" && it.amount == 5_000L && !it.income })
        assertEquals(PhysicalSettlementStatus.SETTLED_EXTERNALLY, reconStore.loadRecords().first().physicalSettlement)
    }

    /**
     * Requirement 8: Discard duplicate resolution: removing duplicate payment removes
     * payment link, restores wallet balance, and creates an audit record.
     */
    @Test
    fun test08_discardDuplicateResolution_removesLinkAndRestoresBalance() {
        val financePrefs = FakeSharedPreferences()
        val financeStore = FinanceStore(context = null, scope = "test_user", preferences = financePrefs)

        val wallet = MoneyAccount("acc_bank", "Bank", isSavings = false, icon = "Bank")
        financeStore.saveMoneyAccounts(listOf(wallet))
        financeStore.addCash(CashEntry("deposit", income = true, amount = 20_000L, date = today, channel = "Bank", note = "Deposit"))

        financeStore.savePaymentLink(
            PaymentAccountLink(
                paymentId = "p_dup",
                loanId = "loan_1",
                accountId = "acc_bank",
                accountName = "Bank",
                isReceivable = false,
                amountCentavos = 5_000L,
                date = today
            )
        )

        val balanceBefore = PaydayPlanner.accountBalances(
            accounts = financeStore.allMoneyAccounts(),
            entries = financeStore.cashEntries(),
            profile = financeStore.profile(),
            defaultSalaryAccountId = financeStore.defaultSalaryAccountId(),
            paymentLinks = financeStore.allPaymentLinks(),
            loans = emptyList()
        )["acc_bank"]
        assertEquals(15_000L, balanceBefore)

        val reconPrefs = FakeSharedPreferences()
        val reconStore = PaymentReconciliationStore(context = null, targetUid = "test_user", preferencesOverride = reconPrefs)
        val record = UnreconciledPaymentRecord(
            reconciliationId = "recon_dup",
            loanId = "loan_1",
            loanParty = "Alice",
            paymentId = "p_dup",
            installment = 0,
            amountCentavos = 5_000L,
            isReceivable = false,
            walletId = "acc_bank",
            walletName = "Bank",
            date = today,
            conflictReason = "Duplicate entry",
            physicalSettlement = PhysicalSettlementStatus.UNKNOWN
        )
        reconStore.saveRecord(record)

        val success = reconStore.resolveAsDuplicateDiscard(record.reconciliationId, financeStore)
        assertTrue(success)

        val balanceAfter = PaydayPlanner.accountBalances(
            accounts = financeStore.allMoneyAccounts(),
            entries = financeStore.cashEntries(),
            profile = financeStore.profile(),
            defaultSalaryAccountId = financeStore.defaultSalaryAccountId(),
            paymentLinks = financeStore.allPaymentLinks(),
            loans = emptyList()
        )["acc_bank"]

        // Balance restored to 20,000 (+5,000)
        assertEquals(20_000L, balanceAfter)
        assertNull(financeStore.allPaymentLinks()["p_dup"])
        assertEquals(PhysicalSettlementStatus.DUPLICATE_DISCARDED, reconStore.loadRecords().first().physicalSettlement)
    }

    /**
     * Requirement 9: Ambiguous state hold: unconfirmed payment holds in UNKNOWN state
     * without corrupting balances or re-attempting failed Firestore sync.
     */
    @Test
    fun test09_ambiguousStateHold_holdsInUnknownWithoutLooping() = runBlocking {
        val baseLoan = sampleLoan(total = 20_000L, count = 2)
        val remoteLoan = baseLoan.pay(0, 10_000L, "p_rem", today)
        val localLoan = baseLoan.pay(0, 5_000L, "p_loc", today)

        val fakeCloud = FakeLoanCloud()
        fakeCloud.remoteLoans[baseLoan.id] = remoteLoan

        val repo = createRepoWithBase(
            uid = "user_hold",
            baseLoans = listOf(baseLoan),
            fakeCloud = fakeCloud
        )

        val saved = repo.save(listOf(localLoan), listOf(baseLoan))
        assertTrue("Saved to queue", saved)
        repo.enable()

        delay(150)

        // Queue halted on conflict
        assertEquals(baseLoan.id, repo.state.value.conflictLoan)
        val unrec = repo.reconciliationStore.loadUnresolvedRecords()
        assertEquals(1, unrec.size)
        assertEquals(PhysicalSettlementStatus.UNKNOWN, unrec.first().physicalSettlement)

        // Calling retry() while conflictLoan is pending does not re-attempt apply
        repo.retry()
        delay(100)
        assertEquals(baseLoan.id, repo.state.value.conflictLoan)
    }

    /**
     * Requirement 10: Repeated conflict resolution idempotency: resolving an already
     * resolved record does not duplicate cash entries or links.
     */
    @Test
    fun test10_repeatedResolution_isIdempotent() {
        val financePrefs = FakeSharedPreferences()
        val financeStore = FinanceStore(context = null, scope = "test_user", preferences = financePrefs)
        val wallet = MoneyAccount("w_1", "Cash", isSavings = false, icon = "Wallet")
        financeStore.saveMoneyAccounts(listOf(wallet))
        financeStore.addCash(CashEntry("c_1", income = true, amount = 10_000L, date = today, channel = "Cash", note = "Deposit"))
        financeStore.savePaymentLink(
            PaymentAccountLink(
                paymentId = "p_idemp",
                loanId = "loan_1",
                accountId = "w_1",
                accountName = "Cash",
                isReceivable = false,
                amountCentavos = 2_000L,
                date = today
            )
        )

        val reconPrefs = FakeSharedPreferences()
        val reconStore = PaymentReconciliationStore(context = null, targetUid = "test_user", preferencesOverride = reconPrefs)
        val record = UnreconciledPaymentRecord(
            reconciliationId = "recon_idemp",
            loanId = "loan_1",
            loanParty = "Alice",
            paymentId = "p_idemp",
            installment = 0,
            amountCentavos = 2_000L,
            isReceivable = false,
            walletId = "w_1",
            walletName = "Cash",
            date = today,
            conflictReason = "Test",
            physicalSettlement = PhysicalSettlementStatus.UNKNOWN
        )
        reconStore.saveRecord(record)

        // First resolution
        assertTrue(reconStore.resolveAsExternalExpense(record.reconciliationId, financeStore))
        val cashCountAfterFirst = financeStore.cashEntries().size

        // Second resolution (repeated)
        assertTrue(reconStore.resolveAsExternalExpense(record.reconciliationId, financeStore))
        assertEquals(cashCountAfterFirst, financeStore.cashEntries().size)
    }

    /**
     * Requirement 11: Process death / app restart during conflict resolution recovers
     * unresolved payment record from durable storage.
     */
    @Test
    fun test11_processDeathRestart_recoversUnresolvedRecord() {
        val sharedPrefs = FakeSharedPreferences()
        val originalStore = PaymentReconciliationStore(context = null, targetUid = "persisted_user", preferencesOverride = sharedPrefs)

        val record = UnreconciledPaymentRecord(
            reconciliationId = "recon_restart",
            loanId = "loan_99",
            loanParty = "Bob",
            paymentId = "p_99",
            installment = 1,
            amountCentavos = 4_500L,
            isReceivable = false,
            walletId = "acc_bank",
            walletName = "Bank",
            date = today,
            conflictReason = "Cloud concurrency failure",
            physicalSettlement = PhysicalSettlementStatus.UNKNOWN
        )
        assertTrue(originalStore.saveRecord(record))

        // Simulate app restart by instantiating new store backed by same sharedPrefs
        val restartedStore = PaymentReconciliationStore(context = null, targetUid = "persisted_user", preferencesOverride = sharedPrefs)
        val recovered = restartedStore.loadUnresolvedRecords()

        assertEquals(1, recovered.size)
        val item = recovered.first()
        assertEquals("recon_restart", item.reconciliationId)
        assertEquals("p_99", item.paymentId)
        assertEquals(4_500L, item.amountCentavos)
        assertEquals("Bob", item.loanParty)
        assertEquals(PhysicalSettlementStatus.UNKNOWN, item.physicalSettlement)
    }

    /**
     * Requirement 12: Crash after Firestore transaction commit but before local acknowledgment
     * does not create duplicate payment on subsequent sync attempt.
     */
    @Test
    fun test12_crashAfterCommitBeforeAck_doesNotDuplicate() = runBlocking {
        val baseLoan = sampleLoan()
        val localLoan = baseLoan.pay(0, 5_000L, "p_crash", today)

        val fakeCloud = FakeLoanCloud()
        fakeCloud.remoteLoans[baseLoan.id] = baseLoan // Remote server already has baseline
        fakeCloud.crashAfterApply = true // Simulated crash immediately after commit

        val repo = createRepoWithBase(
            uid = "user_crash",
            baseLoans = listOf(baseLoan),
            fakeCloud = fakeCloud
        )

        val saved = repo.save(listOf(localLoan), listOf(baseLoan))
        assertTrue("Saved to queue", saved)
        repo.enable()

        delay(150)

        // Server has committed the loan
        assertEquals(1, fakeCloud.remoteLoans[baseLoan.id]?.payments?.size)

        // Now resume normal operation
        fakeCloud.crashAfterApply = false
        repo.retry()

        var attempts = 0
        while (repo.getQueue().isNotEmpty() && attempts < 20) {
            delay(50)
            attempts++
        }

        // Successfully reconciled via alreadyApplied without duplicate payments
        assertEquals(0, repo.getQueue().size)
        assertEquals(1, fakeCloud.remoteLoans[baseLoan.id]?.payments?.size)
    }

    /**
     * Requirement 13: Switching accounts while conflict is pending preserves conflict
     * record scoped to original account and does not leak into next account.
     */
    @Test
    fun test13_accountSwitching_scopesConflictRecordsPerAccount() {
        val userAPrefs = FakeSharedPreferences()
        val userBPrefs = FakeSharedPreferences()

        val storeA = PaymentReconciliationStore(context = null, targetUid = "user_a", preferencesOverride = userAPrefs)
        val storeB = PaymentReconciliationStore(context = null, targetUid = "user_b", preferencesOverride = userBPrefs)

        val recordA = UnreconciledPaymentRecord(
            reconciliationId = "recon_a",
            loanId = "loan_a",
            loanParty = "Alice",
            paymentId = "p_a",
            installment = 0,
            amountCentavos = 1_000L,
            isReceivable = false,
            walletId = "w_a",
            walletName = "Wallet A",
            date = today,
            conflictReason = "Conflict A",
            physicalSettlement = PhysicalSettlementStatus.UNKNOWN
        )
        storeA.saveRecord(recordA)

        assertEquals(1, storeA.loadUnresolvedRecords().size)
        // User B has zero conflict records
        assertEquals(0, storeB.loadUnresolvedRecords().size)
    }

    /**
     * Requirement 14: Complex multi-payment loan history with interleaved payments and reversals
     * creates a valid chronological sequence where reversals never precede the payment they reverse
     * and capacity is never exceeded.
     */
    @Test
    fun test14_interleavedHistory_generatesValidChronologicalSequence() {
        val p1 = Payment("p_1", installment = 0, amount = 5_000L, date = today)
        val r1 = PaymentReversal("p_1", today.plusDays(2), "Bounced")
        val p2 = Payment("p_2", installment = 0, amount = 5_000L, date = today.plusDays(4))
        val p3 = Payment("p_3", installment = 1, amount = 5_000L, date = today.plusDays(1))

        val guestLoan = sampleLoan(
            total = 10_000L,
            count = 2,
            payments = listOf(p1, p2, p3),
            reversals = listOf(r1)
        )

        val mutations = CloudMutation.importHistory(guestLoan)
        assertFalse(mutations.isEmpty())

        // Validate sequence: each mutation step must respect installment capacity
        mutations.forEach { m ->
            assertTrue(m.after.installments().all { it.paid <= it.amount })
        }

        // Validate reversal causal order: reversal for p_1 appears after p_1 and before p_2
        val p1Idx = mutations.indexOfFirst { it.after.payments.any { p -> p.id == "p_1" } }
        val r1Idx = mutations.indexOfFirst { it.after.reversals.any { r -> r.paymentId == "p_1" } }
        val p2Idx = mutations.indexOfFirst { it.after.payments.any { p -> p.id == "p_2" } }

        assertTrue(p1Idx >= 0)
        assertTrue(r1Idx > p1Idx)
        assertTrue(p2Idx > r1Idx)
    }

    /**
     * Requirement 15: Unreconciled payment record can transition to RESOLVED_TO_LOAN
     * when user chooses to reassign to an open installment, updating the local queue appropriately.
     */
    @Test
    fun test15_resolveAsReassignedInstallment_updatesQueueAndMarksResolved() = runBlocking {
        val baseLoan = sampleLoan(total = 20_000L, count = 2)
        val remoteLoan = baseLoan.pay(0, 10_000L, "p_remote", today)
        val localLoan = baseLoan.pay(0, 5_000L, "p_to_reassign", today)

        val fakeCloud = FakeLoanCloud()
        fakeCloud.remoteLoans[baseLoan.id] = remoteLoan

        val repo = createRepoWithBase(
            uid = "user_reassign",
            baseLoans = listOf(baseLoan),
            fakeCloud = fakeCloud
        )

        val saved = repo.save(listOf(localLoan), listOf(baseLoan))
        assertTrue("Saved to queue", saved)
        repo.enable()

        delay(150)
        assertEquals(baseLoan.id, repo.state.value.conflictLoan)

        val unrec = repo.reconciliationStore.loadUnresolvedRecords().first()

        // Reassign from installment 0 to installment 1 (which has 10,000 capacity open)
        val success = repo.reconciliationStore.resolveAsReassignedInstallment(
            recordId = unrec.reconciliationId,
            targetInstallment = 1,
            repository = repo
        )
        assertTrue(success)

        val updatedRecord = repo.reconciliationStore.loadRecords().first()
        assertEquals(PhysicalSettlementStatus.RESOLVED_TO_LOAN, updatedRecord.physicalSettlement)
        assertEquals(1, updatedRecord.installment)

        // Queue mutation should now target installment 1, or have been pumped to fakeCloud
        val queuedOrRemotePayment = repo.getQueue().lastOrNull()?.after?.payments?.lastOrNull()
            ?: fakeCloud.remoteLoans[baseLoan.id]?.payments?.find { it.id == "p_to_reassign" }
        assertNotNull(queuedOrRemotePayment)
        assertEquals(1, queuedOrRemotePayment?.installment)
        assertEquals(5_000L, queuedOrRemotePayment?.amount)
    }

    /**
     * Requirement 16: Invariant check: Wallet balance variance across all tested operations
     * never results in an unbacked phantom debit or double credit.
     */
    @Test
    fun test16_walletBalanceVariance_noPhantomDebitOrDoubleCredit() {
        val financePrefs = FakeSharedPreferences()
        val financeStore = FinanceStore(context = null, scope = "user_invar", preferences = financePrefs)

        val wallet = MoneyAccount("acc_main", "Main Wallet", isSavings = false, icon = "Wallet")
        financeStore.saveMoneyAccounts(listOf(wallet))
        financeStore.addCash(CashEntry("deposit", income = true, amount = 50_000L, date = today, channel = "Main Wallet", note = "Deposit"))

        fun currentBalance(): Long = PaydayPlanner.accountBalances(
            accounts = financeStore.allMoneyAccounts(),
            entries = financeStore.cashEntries(),
            profile = financeStore.profile(),
            defaultSalaryAccountId = financeStore.defaultSalaryAccountId(),
            paymentLinks = financeStore.allPaymentLinks(),
            loans = emptyList()
        )["acc_main"] ?: 0L

        // Step 1: Initial balance
        assertEquals(50_000L, currentBalance())

        // Step 2: Linked payment holds 5,000
        financeStore.savePaymentLink(
            PaymentAccountLink(
                paymentId = "p_1",
                loanId = "l_1",
                accountId = "acc_main",
                accountName = "Main Wallet",
                isReceivable = false,
                amountCentavos = 5_000L,
                date = today
            )
        )
        assertEquals(45_000L, currentBalance())

        // Step 3: Conflict encountered, unconfirmed payment held in UNKNOWN
        val reconPrefs = FakeSharedPreferences()
        val reconStore = PaymentReconciliationStore(context = null, targetUid = "user_invar", preferencesOverride = reconPrefs)
        val rec = UnreconciledPaymentRecord(
            reconciliationId = "recon_inv",
            loanId = "l_1",
            loanParty = "Alice",
            paymentId = "p_1",
            installment = 0,
            amountCentavos = 5_000L,
            isReceivable = false,
            walletId = "acc_main",
            walletName = "Main Wallet",
            date = today,
            conflictReason = "Cloud rejected",
            physicalSettlement = PhysicalSettlementStatus.UNKNOWN
        )
        reconStore.saveRecord(rec)
        // Holding in UNKNOWN leaves balance unchanged at 45,000
        assertEquals(45_000L, currentBalance())

        // Step 4: Convert to external expense
        reconStore.resolveAsExternalExpense(rec.reconciliationId, financeStore)
        // Balance remains exactly 45,000 (0-centavo net variance)
        assertEquals(45_000L, currentBalance())

        // Step 5: Add a second payment link and discard as duplicate
        financeStore.savePaymentLink(
            PaymentAccountLink(
                paymentId = "p_dup",
                loanId = "l_1",
                accountId = "acc_main",
                accountName = "Main Wallet",
                isReceivable = false,
                amountCentavos = 10_000L,
                date = today
            )
        )
        assertEquals(35_000L, currentBalance())

        val recDup = UnreconciledPaymentRecord(
            reconciliationId = "recon_dup_2",
            loanId = "l_1",
            loanParty = "Alice",
            paymentId = "p_dup",
            installment = 0,
            amountCentavos = 10_000L,
            isReceivable = false,
            walletId = "acc_main",
            walletName = "Main Wallet",
            date = today,
            conflictReason = "Accidental tap",
            physicalSettlement = PhysicalSettlementStatus.UNKNOWN
        )
        reconStore.saveRecord(recDup)
        reconStore.resolveAsDuplicateDiscard(recDup.reconciliationId, financeStore)
        // Balance restored back to 45,000
        assertEquals(45_000L, currentBalance())
    }
}
