package com.example.casha

import com.example.casha.data.*
import com.example.casha.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

class FakeLoanCloud : LoanCloud {
    val remoteLoans = mutableMapOf<String, Loan>()
    val remoteRevisions = mutableMapOf<String, Long>()
    var shouldRejectRules = false
    var crashAfterApply = false
    private var changeListener: ((List<Loan>, Set<String>, Boolean) -> Unit)? = null

    override fun listen(onRecords: (List<Loan>, Boolean) -> Unit, onError: (Exception) -> Unit): AutoCloseable {
        return listenChanges({ records, _, server -> onRecords(records, server) }, onError)
    }

    override fun listenChanges(
        onRecords: (List<Loan>, Set<String>, Boolean) -> Unit,
        onError: (Exception) -> Unit
    ): AutoCloseable {
        changeListener = onRecords
        onRecords(remoteLoans.values.toList(), emptySet(), true)
        return AutoCloseable { changeListener = null }
    }

    override suspend fun apply(mutation: CloudMutation): Loan {
        if (shouldRejectRules) {
            throw SecurityException("PERMISSION_DENIED: Cloud loan document rule violated")
        }

        val id = mutation.after.id
        val current = remoteLoans[id]

        if (current != null && mutation.kind != MutationKind.DELETE && mutation.alreadyApplied(current)) {
            return current
        }

        if (current != mutation.before) {
            throw CloudConflict(current)
        }

        val nextRev = (remoteRevisions[id] ?: 0L) + 1L
        remoteRevisions[id] = nextRev
        remoteLoans[id] = mutation.after

        if (crashAfterApply) {
            throw RuntimeException("Simulated process crash immediately after Firestore transaction commit")
        }

        changeListener?.invoke(remoteLoans.values.toList(), emptySet(), true)
        return mutation.after
    }
}

class LoanMigrationTest {

    private val today = LocalDate.of(2026, 11, 1)

    @Before
    fun setUp() {
        val checkpointDir = File(System.getProperty("java.io.tmpdir", "."), "casha_checkpoints/checkpoints")
        if (checkpointDir.exists()) {
            checkpointDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun sampleLoan(id: String = "loan_1", party: String = "Banker", payments: List<Payment> = emptyList(), reversals: List<PaymentReversal> = emptyList()): Loan {
        return Loan(
            id = id,
            party = party,
            mode = LoanMode.I_OWE,
            total = 10_000L,
            count = 2,
            firstDue = today,
            frequency = Frequency.MONTHLY,
            payments = payments,
            reversals = reversals
        )
    }

    @Test
    fun test01_importIntoEmptyAccount_enqueuesAndAcknowledges() = runBlocking {
        val fakeGuestPrefs = FakeSharedPreferences()
        val guestStore = LoanStore(context = null, preferencesOverride = fakeGuestPrefs)

        val guestPayment = Payment("pay_1", 0, 5_000L, today)
        val guestLoan = sampleLoan("loan_guest_1", "Banker", listOf(guestPayment))
        guestStore.save(listOf(guestLoan))

        val fakeCloud = FakeLoanCloud()
        val fakeCloudPrefs = FakeSharedPreferences()
        val repo = CloudLoanRepository(
            context = null,
            uid = "user_123",
            owner = { "user_123" },
            cloud = fakeCloud,
            online = { true },
            preferencesOverride = fakeCloudPrefs
        )

        val manifest = LoanMigrationInspector.inspect(
            guestLoans = guestStore.load(),
            cloudLoans = repo.load(),
            cloudQueue = repo.getQueue(),
            targetUid = "user_123"
        )

        assertTrue(manifest.isReady)
        assertEquals(1, manifest.loansToImport.size)
        assertEquals(1, manifest.totalPaymentsToImport)
        assertEquals(0, manifest.loansToSkip.size)

        val result = repo.enqueueLoanMigration(manifest)
        assertTrue(result.success)
        assertEquals(LoanMigrationStage.LOCAL_ENQUEUED, result.stage)
        assertEquals(1, result.loansEnqueued)
        assertEquals(1, result.paymentsEnqueued)

        // Guest store remains 100% untouched
        assertEquals(1, guestStore.load().size)
        assertEquals("loan_guest_1", guestStore.load()[0].id)

        // Enable sync and allow pump to upload
        repo.enable()
        // Wait briefly for coroutine pump
        kotlinx.coroutines.delay(100)

        // Verified on cloud
        val remote = fakeCloud.remoteLoans["loan_guest_1"]
        assertNotNull(remote)
        assertEquals(1, remote!!.payments.size)
        assertEquals(5_000L, remote.payments[0].amount)

        repo.close()
    }

    @Test
    fun test02_identicalExistingLoan_skipsWithoutDuplicateMutations() {
        val payment = Payment("pay_1", 0, 5_000L, today)
        val loan = sampleLoan("loan_1", "Banker", listOf(payment))

        val manifest = LoanMigrationInspector.inspect(
            guestLoans = listOf(loan),
            cloudLoans = listOf(loan),
            cloudQueue = emptyList(),
            targetUid = "user_123"
        )

        assertTrue(manifest.isReady)
        assertEquals(0, manifest.loansToImport.size)
        assertEquals(1, manifest.loansToSkip.size)
        assertTrue(manifest.warnings.any { it.contains("already confirmed in cloud") })
    }

    @Test
    fun test03_conflictingLoanId_blocksMigration() {
        val guestLoan = sampleLoan("loan_1", "Banker A", emptyList())
        val cloudLoan = sampleLoan("loan_1", "Banker B", emptyList()) // Different party

        val manifest = LoanMigrationInspector.inspect(
            guestLoans = listOf(guestLoan),
            cloudLoans = listOf(cloudLoan),
            cloudQueue = emptyList(),
            targetUid = "user_123"
        )

        assertFalse(manifest.isReady)
        assertEquals(1, manifest.collidingLoans.size)
        assertEquals(LoanCollisionType.DIFFERENT_CONFLICT, manifest.collidingLoans[0].collisionType)
        assertTrue(manifest.errors.any { it.contains("Conflicting loan ID collision") })
    }

    @Test
    fun test04_loanContainingMultiplePayments_uploadsInSequentialRevisions() = runBlocking {
        val p1 = Payment("pay_1", 0, 5_000L, today)
        val p2 = Payment("pay_2", 1, 5_000L, today.plusMonths(1))
        val multiPayLoan = sampleLoan("loan_multi", "Banker", listOf(p1, p2))

        val fakeCloud = FakeLoanCloud()
        val fakeCloudPrefs = FakeSharedPreferences()
        val repo = CloudLoanRepository(
            context = null,
            uid = "user_123",
            owner = { "user_123" },
            cloud = fakeCloud,
            online = { true },
            preferencesOverride = fakeCloudPrefs
        )

        val manifest = LoanMigrationInspector.inspect(
            guestLoans = listOf(multiPayLoan),
            cloudLoans = emptyList(),
            cloudQueue = emptyList(),
            targetUid = "user_123"
        )

        assertEquals(2, manifest.totalPaymentsToImport)
        val result = repo.enqueueLoanMigration(manifest)
        assertTrue(result.success)

        repo.enable()
        kotlinx.coroutines.withTimeout(10_000) {
            while (repo.state.value.pending > 0) kotlinx.coroutines.delay(10)
        }

        val remote = fakeCloud.remoteLoans["loan_multi"]
        assertNotNull(remote)
        assertEquals(2, remote!!.payments.size)
        assertEquals(3L, fakeCloud.remoteRevisions["loan_multi"]) // CREATE (1) + P1 (2) + P2 (3)

        repo.close()
    }

    @Test
    fun test05_loanContainingHistoricalReversals_preservesDependencyOrder() = runBlocking {
        val p1 = Payment("pay_1", 0, 5_000L, today)
        val r1 = PaymentReversal("pay_1", today, "Overpayment correction")
        val p2 = Payment("pay_2", 0, 4_000L, today) // Re-paid installment 0

        val reversedLoan = sampleLoan("loan_rev", "Banker", listOf(p1, p2), listOf(r1))

        val fakeCloud = FakeLoanCloud()
        val fakeCloudPrefs = FakeSharedPreferences()
        val repo = CloudLoanRepository(
            context = null,
            uid = "user_123",
            owner = { "user_123" },
            cloud = fakeCloud,
            online = { true },
            preferencesOverride = fakeCloudPrefs
        )

        val manifest = LoanMigrationInspector.inspect(
            guestLoans = listOf(reversedLoan),
            cloudLoans = emptyList(),
            cloudQueue = emptyList(),
            targetUid = "user_123"
        )

        assertEquals(2, manifest.totalPaymentsToImport)
        assertEquals(1, manifest.totalReversalsToImport)

        val result = repo.enqueueLoanMigration(manifest)
        assertTrue(result.success)

        repo.enable()
        kotlinx.coroutines.withTimeout(10_000) {
            while (repo.state.value.pending > 0) kotlinx.coroutines.delay(10)
        }

        val remote = fakeCloud.remoteLoans["loan_rev"]
        assertNotNull(remote)
        assertEquals(2, remote!!.payments.size)
        assertEquals(1, remote.reversals.size)
        assertEquals("pay_1", remote.reversals[0].paymentId)

        repo.close()
    }

    @Test
    fun test06_partialCloudAcknowledgment_tracksPerLoanProgress() = runBlocking {
        val l1 = sampleLoan("loan_a", "Banker A", listOf(Payment("pay_a", 0, 5_000L, today)))
        val l2 = sampleLoan("loan_b", "Banker B", listOf(Payment("pay_b", 0, 5_000L, today)))

        val fakeCloud = FakeLoanCloud()
        val fakeCloudPrefs = FakeSharedPreferences()
        val repo = CloudLoanRepository(
            context = null,
            uid = "user_123",
            owner = { "user_123" },
            cloud = fakeCloud,
            online = { false }, // Start offline
            preferencesOverride = fakeCloudPrefs
        )

        val manifest = LoanMigrationInspector.inspect(
            guestLoans = listOf(l1, l2),
            cloudLoans = emptyList(),
            cloudQueue = emptyList(),
            targetUid = "user_123"
        )

        repo.enqueueLoanMigration(manifest)
        val journalStore = repo.migrationJournal
        val jEntry = journalStore.loadJournal()
        assertNotNull(jEntry)
        assertEquals(LoanMigrationStage.LOCAL_ENQUEUED, jEntry?.stage)
        assertEquals(LoanCloudSyncState.PENDING_LOCAL, jEntry?.perLoanStates?.get("loan_a")?.status)
        assertEquals(LoanCloudSyncState.PENDING_LOCAL, jEntry?.perLoanStates?.get("loan_b")?.status)

        // Manually simulate 1 loan acknowledged
        journalStore.recordMutationConfirmed("loan_a", 1L)
        journalStore.recordMutationConfirmed("loan_a", 2L)

        val updated = journalStore.loadJournal()
        assertEquals(LoanCloudSyncState.ACKNOWLEDGED, updated?.perLoanStates?.get("loan_a")?.status)
        assertEquals(LoanCloudSyncState.PENDING_LOCAL, updated?.perLoanStates?.get("loan_b")?.status)
        assertEquals(LoanMigrationStage.UPLOADING, updated?.stage) // Not complete because loan_b is pending

        repo.close()
    }

    @Test
    fun test07_firestoreAckFollowedByProcessDeath_recoversWithoutDuplicate() = runBlocking {
        val payment = Payment("pay_1", 0, 5_000L, today)
        val loan = sampleLoan("loan_crash", "Banker", listOf(payment))

        val fakeCloud = FakeLoanCloud()
        fakeCloud.crashAfterApply = true // Simulates crash right after Firestore writes

        val fakeCloudPrefs = FakeSharedPreferences()
        val repo = CloudLoanRepository(
            context = null,
            uid = "user_123",
            owner = { "user_123" },
            cloud = fakeCloud,
            online = { true },
            preferencesOverride = fakeCloudPrefs
        )

        val manifest = LoanMigrationInspector.inspect(
            guestLoans = listOf(loan),
            cloudLoans = emptyList(),
            targetUid = "user_123"
        )
        repo.enqueueLoanMigration(manifest)
        repo.enable()

        kotlinx.coroutines.withTimeout(10_000) {
            while (fakeCloud.remoteLoans["loan_crash"] == null) kotlinx.coroutines.delay(10)
        }

        // Remote has received the loan
        assertNotNull(fakeCloud.remoteLoans["loan_crash"])

        // Second startup: restart repository
        repo.close()
        fakeCloud.crashAfterApply = false
        val repoRestarted = CloudLoanRepository(
            context = null,
            uid = "user_123",
            owner = { "user_123" },
            cloud = fakeCloud,
            online = { true },
            preferencesOverride = fakeCloudPrefs
        )
        repoRestarted.enable()
        kotlinx.coroutines.withTimeout(10_000) {
            while (repoRestarted.state.value.pending > 0) kotlinx.coroutines.delay(10)
        }

        // Verified remote still has exactly 1 loan with 1 payment, not duplicated
        assertEquals(1, fakeCloud.remoteLoans.size)
        assertEquals(1, fakeCloud.remoteLoans["loan_crash"]!!.payments.size)

        repo.close()
        repoRestarted.close()
    }

    @Test
    fun test08_offlineRetry_projectsImmediatelyAndUploadsOnReconnect() = runBlocking {
        var isOnline = false
        val fakeCloud = FakeLoanCloud()
        val fakeCloudPrefs = FakeSharedPreferences()
        val repo = CloudLoanRepository(
            context = null,
            uid = "user_123",
            owner = { "user_123" },
            cloud = fakeCloud,
            online = { isOnline },
            preferencesOverride = fakeCloudPrefs
        )
        repo.enable()

        val loan = sampleLoan("loan_offline", "Banker", listOf(Payment("pay_1", 0, 5_000L, today)))
        val manifest = LoanMigrationInspector.inspect(listOf(loan), repo.load(), repo.getQueue(), targetUid = "user_123")

        repo.enqueueLoanMigration(manifest)

        // Offline visibility: Projected immediately
        val projected = repo.load()
        assertEquals(1, projected.size)
        assertEquals("loan_offline", projected[0].id)
        assertNull(fakeCloud.remoteLoans["loan_offline"]) // Not on remote yet

        // Reconnect
        isOnline = true
        repo.retry()
        kotlinx.coroutines.withTimeout(10_000) {
            while (repo.state.value.pending > 0) kotlinx.coroutines.delay(10)
        }

        // Successfully uploaded on reconnect
        assertNotNull(fakeCloud.remoteLoans["loan_offline"])

        repo.close()
    }

    @Test
    fun test09_duplicateImportRetry_isIdempotent() {
        val loan = sampleLoan("loan_1", "Banker", listOf(Payment("pay_1", 0, 5_000L, today)))
        val manifest1 = LoanMigrationInspector.inspect(listOf(loan), emptyList(), emptyList(), targetUid = "user_123")
        assertEquals(1, manifest1.loansToImport.size)

        // Second inspection after loan is in cloud base
        val manifest2 = LoanMigrationInspector.inspect(listOf(loan), listOf(loan), emptyList(), targetUid = "user_123")
        assertEquals(0, manifest2.loansToImport.size)
        assertEquals(1, manifest2.loansToSkip.size)

        // Third inspection when loan is already in local queue
        val mutations = CloudMutation.importHistory(loan)
        val manifest3 = LoanMigrationInspector.inspect(listOf(loan), emptyList(), mutations, targetUid = "user_123")
        assertEquals(0, manifest3.loansToImport.size)
        assertEquals(1, manifest3.loansToSkip.size)
    }

    @Test
    fun test10_accountSwitching_isolatesJournalsAndQueues() {
        val journalStoreA = LoanMigrationJournalStore(context = null, targetUid = "user_A")
        val journalStoreB = LoanMigrationJournalStore(context = null, targetUid = "user_B")

        val entryA = LoanMigrationJournalEntry(
            operationId = "mig_A",
            manifestId = "man_A",
            timestampMs = System.currentTimeMillis(),
            schemaVersion = 1,
            stage = LoanMigrationStage.LOCAL_ENQUEUED,
            sourceScope = "manual_loans",
            targetUid = "user_A",
            perLoanStates = mapOf("l1" to PerLoanJournalState("l1", 2, 0, LoanCloudSyncState.PENDING_LOCAL))
        )
        journalStoreA.saveJournal(entryA)

        assertTrue(journalStoreA.hasPendingMigration())
        assertFalse(journalStoreB.hasPendingMigration())

        assertEquals("user_A", journalStoreA.loadJournal()?.targetUid)
        assertNull(journalStoreB.loadJournal())
    }

    @Test
    fun test11_provisionalToAuthoritativeAccounting_preservesExactWalletBalances() {
        val fakePrefs = FakeSharedPreferences()
        val financeStore = FinanceStore(context = null, scope = "account_user_123", preferences = fakePrefs)

        val bankWallet = MoneyAccount("acc_bank", "Bank")
        financeStore.saveMoneyAccounts(listOf(bankWallet))

        val salary = CashEntry("salary_cutoff_${today}_acc_bank", income = true, amount = 20_000L, date = today, note = "Salary", channel = "Bank")
        financeStore.addCash(salary)

        val link = PaymentAccountLink(
            paymentId = "pay_1",
            loanId = "loan_guest",
            accountId = "acc_bank",
            accountName = "Bank",
            amountCentavos = 5_000L,
            date = today
        )
        financeStore.savePaymentLink(link)

        // Stage 1: Phase 02.2 Migrated State (Provisional link exists, but authoritative loan is absent)
        val balStage1 = PaydayPlanner.accountBalances(
            accounts = financeStore.allMoneyAccounts(),
            entries = financeStore.cashEntries(),
            paymentLinks = financeStore.allPaymentLinks(),
            loans = emptyList() // Authoritative loan absent
        )
        assertEquals("Stage 1 provisional balance is ₱15,000", 15_000L, balStage1["acc_bank"])

        // Stage 2: Phase 02.3A Guest Loan Enqueued into CloudLoanRepository
        val guestLoan = sampleLoan("loan_guest", "Banker", listOf(Payment("pay_1", 0, 5_000L, today)))
        val fakeCloud = FakeLoanCloud()
        val repo = CloudLoanRepository(
            context = null,
            uid = "user_123",
            owner = { "user_123" },
            cloud = fakeCloud,
            online = { false }, // Keep queued
            preferencesOverride = FakeSharedPreferences()
        )
        val manifest = LoanMigrationInspector.inspect(listOf(guestLoan), repo.load(), repo.getQueue(), financeStore.allPaymentLinks(), targetUid = "user_123")
        repo.enqueueLoanMigration(manifest)

        val balStage2 = PaydayPlanner.accountBalances(
            accounts = financeStore.allMoneyAccounts(),
            entries = financeStore.cashEntries(),
            paymentLinks = financeStore.allPaymentLinks(),
            loans = repo.load() // Projected from queue!
        )
        assertEquals("Stage 2 transition to authoritative retains exact ₱15,000 balance", 15_000L, balStage2["acc_bank"])

        // Stage 3: Acknowledged on Cloud
        fakeCloud.remoteLoans["loan_guest"] = guestLoan
        val balStage3 = PaydayPlanner.accountBalances(
            accounts = financeStore.allMoneyAccounts(),
            entries = financeStore.cashEntries(),
            paymentLinks = financeStore.allPaymentLinks(),
            loans = fakeCloud.remoteLoans.values.toList()
        )
        assertEquals("Stage 3 cloud acknowledged retains exact ₱15,000 balance", 15_000L, balStage3["acc_bank"])

        repo.close()
    }

    @Test
    fun test12_reversalPreservation_restoresWalletBalanceCorrectly() {
        val fakePrefs = FakeSharedPreferences()
        val financeStore = FinanceStore(context = null, scope = "account_user_123", preferences = fakePrefs)

        val bankWallet = MoneyAccount("acc_bank", "Bank")
        financeStore.saveMoneyAccounts(listOf(bankWallet))

        val salary = CashEntry("salary_cutoff_${today}_acc_bank", income = true, amount = 20_000L, date = today, note = "Salary", channel = "Bank")
        financeStore.addCash(salary)

        val link = PaymentAccountLink("pay_1", "loan_1", "acc_bank", "Bank", isReceivable = false, amountCentavos = 5_000L, date = today)
        financeStore.savePaymentLink(link)

        val reversedLoan = sampleLoan(
            id = "loan_1",
            party = "Banker",
            payments = listOf(Payment("pay_1", 0, 5_000L, today)),
            reversals = listOf(PaymentReversal("pay_1", today, "Disputed payment"))
        )

        val bal = PaydayPlanner.accountBalances(
            accounts = financeStore.allMoneyAccounts(),
            entries = financeStore.cashEntries(),
            paymentLinks = financeStore.allPaymentLinks(),
            loans = listOf(reversedLoan)
        )

        // When payment is reversed in authoritative loan, debit is suppressed and balance is restored to ₱20,000
        assertEquals(20_000L, bal["acc_bank"])
    }

    @Test
    fun test13_invalidHistoricalPaymentSequence_failsInspectorValidation() {
        // Loan with payment date out of bounds (year 1850 violates Firestore rule year in 1900..2200)
        val invalidPayment = Payment("pay_bad", 0, 5_000L, LocalDate.of(1850, 1, 1))
        val invalidLoan = sampleLoan("loan_bad", "Banker", listOf(invalidPayment))

        val manifest = LoanMigrationInspector.inspect(
            guestLoans = listOf(invalidLoan),
            cloudLoans = emptyList(),
            targetUid = "user_123"
        )

        assertFalse(manifest.isReady)
        assertTrue(manifest.errors.any { it.contains("Payment dates must be between 1900 and 2200") })
    }

    @Test
    fun test14_firestoreRuleRejection_marksLoanFailedWithoutCorruptingStorage() = runBlocking {
        val loan = sampleLoan("loan_reject", "Banker", listOf(Payment("pay_1", 0, 5_000L, today)))

        val fakeCloud = FakeLoanCloud()
        fakeCloud.shouldRejectRules = true // Will throw SecurityException on apply

        val fakeCloudPrefs = FakeSharedPreferences()
        val repo = CloudLoanRepository(
            context = null,
            uid = "user_123",
            owner = { "user_123" },
            cloud = fakeCloud,
            online = { true },
            preferencesOverride = fakeCloudPrefs
        )

        val manifest = LoanMigrationInspector.inspect(listOf(loan), emptyList(), targetUid = "user_123")
        repo.enqueueLoanMigration(manifest)
        repo.enable()

        kotlinx.coroutines.withTimeout(10_000) {
            while (repo.migrationJournal.loadJournal()?.stage != LoanMigrationStage.FAILED) kotlinx.coroutines.delay(10)
        }

        val jEntry = repo.migrationJournal.loadJournal()
        assertNotNull(jEntry)
        assertEquals(LoanMigrationStage.FAILED, jEntry?.stage)
        assertEquals(LoanCloudSyncState.FAILED, jEntry?.perLoanStates?.get("loan_reject")?.status)
        assertTrue(jEntry?.errorMessage!!.contains("Cloud loan document rule violated"))

        repo.close()
    }

    @Test
    fun test15_corruptedJournalFile_handlesGracefully() {
        val journalStore = LoanMigrationJournalStore(context = null, targetUid = "user_corrupt")
        val entry = LoanMigrationJournalEntry(
            operationId = "op_1",
            manifestId = "man_1",
            timestampMs = System.currentTimeMillis(),
            schemaVersion = 1,
            stage = LoanMigrationStage.LOCAL_ENQUEUED,
            sourceScope = "manual_loans",
            targetUid = "user_corrupt",
            perLoanStates = emptyMap()
        )
        journalStore.saveJournal(entry)

        // Corrupt file contents
        val baseDir = File(System.getProperty("java.io.tmpdir", "."), "casha_checkpoints/checkpoints")
        val file = File(baseDir, "loan_migration_journal_${LoanMigrationJournalStore.hashUid("user_corrupt")}.json")
        file.writeText("NOT_VALID_JSON{{{")

        assertNull("Corrupt journal returns null safely", journalStore.loadJournal())
        assertFalse("Corrupt journal does not block as pending", journalStore.hasPendingMigration())
    }

    @Test
    fun test16_hardenedLocalCommittedVerification_validatesExpectedCounts() {
        val fakeTgtPrefs = FakeSharedPreferences()
        val tgtStore = FinanceStore(context = null, scope = "account_target", preferences = fakeTgtPrefs)
        val journalStore = MigrationJournalStore(context = null)

        val bankWallet = MoneyAccount("acc_bank", "Bank")
        tgtStore.saveMoneyAccounts(listOf(bankWallet))
        tgtStore.addCash(CashEntry("entry_1", income = true, amount = 10_000L, date = today, note = "Salary", channel = "Bank"))
        fakeTgtPrefs.data["migrated_from_scope"] = "device"

        // Journal expecting 5 accounts, but target only has 1 committed
        val entryWithHigherExpectation = MigrationJournalEntry(
            migrationId = "mig_strict",
            timestampMs = System.currentTimeMillis(),
            schemaVersion = 1,
            stage = MigrationStage.LOCAL_COMMITTED,
            sourceScope = "device",
            targetScope = "account_target",
            manifestId = "man_strict",
            preTargetSnapshotJson = "{}",
            expectedAccountsCount = 5,
            expectedCashCount = 10
        )
        journalStore.saveJournal(entryWithHigherExpectation)

        // Recovery must fail because expected accounts count (5) > actual accounts count (1)
        val recoveredFail = journalStore.recoverIfInterrupted(tgtStore)
        assertFalse("Recovery fails when expected accounts count is not satisfied", recoveredFail)
        assertNotNull(journalStore.loadJournal())

        // Save journal matching actual counts
        val entryMatching = entryWithHigherExpectation.copy(
            expectedAccountsCount = 1,
            expectedCashCount = 1,
            expectedTargetFingerprint = tgtStore.inspectLedger().computeFingerprint()
        )
        journalStore.saveJournal(entryMatching)

        // Recovery succeeds when exact expected metrics match
        val recoveredSuccess = journalStore.recoverIfInterrupted(tgtStore)
        assertTrue("Recovery succeeds when exact metrics match", recoveredSuccess)
        assertNull(journalStore.loadJournal())
    }
}
