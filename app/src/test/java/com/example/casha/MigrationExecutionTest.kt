package com.example.casha

import com.example.casha.data.FinanceStore
import com.example.casha.data.MigrationExecutionEngine
import com.example.casha.data.MigrationInspector
import com.example.casha.data.MigrationJournalStore
import com.example.casha.domain.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

class MigrationExecutionTest {

    private val today = LocalDate.of(2026, 11, 1)
    private val next = LocalDate.of(2026, 11, 15)

    @Before
    fun setUp() {
        val checkpointDir = File(System.getProperty("java.io.tmpdir", "."), "casha_checkpoints/checkpoints")
        if (checkpointDir.exists()) {
            checkpointDir.listFiles()?.forEach { it.delete() }
        }
    }

    @Test
    fun testDeviceToEmptyAccountMigration_transfersAllEntities_matchesBalances() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        // Source setup: Profile, custom GCash wallet, salary deposit, expense, payment link
        val bankWallet = MoneyAccount("acc_bank", "Bank", isSavings = false, icon = "Bank")
        val gcashWallet = MoneyAccount("acc_gcash", "GCash", isSavings = false, icon = "Wallet")
        assertTrue(srcStore.saveMoneyAccounts(listOf(bankWallet, gcashWallet)))

        val srcProfile = PaydayProfile(today, next, salary = 20_000L, essentials = 5_000L, emergency = 2_000L, salaryAccountId = "acc_bank")
        assertTrue(srcStore.saveProfile(srcProfile))

        val salaryDeposit = CashEntry("salary_cutoff_${today}_acc_bank", income = true, amount = 20_000L, date = today, note = "Salary", channel = "Bank")
        val groceryExpense = CashEntry("exp_grocery", income = false, amount = 3_000L, date = today, note = "Groceries", channel = "GCash")
        assertTrue(srcStore.addCash(salaryDeposit))
        assertTrue(srcStore.addCash(groceryExpense))

        val activeLoan = Loan(id = "l1", party = "Creditor", mode = LoanMode.I_OWE, total = 10_000L, count = 2, firstDue = today, frequency = Frequency.MONTHLY)
            .pay(0, 5_000L, "p1", today)
        val link = PaymentAccountLink(paymentId = "p1", loanId = "l1", accountId = "acc_bank", accountName = "Bank")
        assertTrue(srcStore.savePaymentLink(link))

        val preSrcBalances = PaydayPlanner.accountBalances(
            accounts = srcStore.allMoneyAccounts(),
            entries = srcStore.cashEntries(),
            profile = srcStore.profile(),
            defaultSalaryAccountId = srcStore.defaultSalaryAccountId(),
            paymentLinks = srcStore.allPaymentLinks(),
            loans = listOf(activeLoan)
        )

        // Inspect and Execute
        val manifest = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger(), listOf(activeLoan))
        assertTrue("Manifest ready", manifest.isReady)

        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)
        assertTrue("Migration succeeded", result.success)
        assertEquals(MigrationStage.COMPLETED, result.stage)
        assertEquals(1, result.walletsImported) // GCash added
        assertEquals(2, result.cashEntriesImported)
        assertEquals(1, result.paymentLinksImported)
        assertTrue(result.profileAdopted)

        // Verify destination ledger matches source balances exactly
        val postTgtBalances = PaydayPlanner.accountBalances(
            accounts = tgtStore.allMoneyAccounts(),
            entries = tgtStore.cashEntries(),
            profile = tgtStore.profile(),
            defaultSalaryAccountId = tgtStore.defaultSalaryAccountId(),
            paymentLinks = tgtStore.allPaymentLinks(),
            loans = emptyList() // Target repository does not yet contain activeLoan!
        )
        assertEquals(preSrcBalances["acc_bank"], postTgtBalances["acc_bank"])
        assertEquals(15_000L, postTgtBalances["acc_bank"])
        assertEquals(preSrcBalances["acc_gcash"], postTgtBalances["acc_gcash"])

        // Source store is 100% unchanged
        assertEquals(2, srcStore.cashEntries().size)
        assertEquals(20_000L, srcStore.profile().salary)
    }

    @Test
    fun testDeviceToPopulatedAccountMigration_preservesExistingData_mergesCleanly() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        // Source has custom Maya wallet and cash entry
        val bankWallet = MoneyAccount("acc_bank", "Bank")
        val mayaWallet = MoneyAccount("acc_maya", "Maya")
        srcStore.saveMoneyAccounts(listOf(bankWallet, mayaWallet))
        srcStore.addCash(CashEntry("ce_dev_1", income = true, amount = 1_500L, date = today, note = "Side gig", channel = "Maya"))

        // Target already has custom Crypto wallet, existing entry, and configured budget
        val cryptoWallet = MoneyAccount("acc_crypto", "Crypto")
        tgtStore.saveMoneyAccounts(listOf(bankWallet, cryptoWallet))
        tgtStore.addCash(CashEntry("ce_tgt_1", income = false, amount = 2_000L, date = today, note = "Bill", channel = "Bank"))
        val tgtProfile = PaydayProfile(today, next, salary = 35_000L, essentials = 10_000L)
        tgtStore.saveProfile(tgtProfile)

        val manifest = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger())
        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)

        assertTrue(result.success)
        assertEquals(1, result.walletsImported) // Maya imported
        assertEquals(1, result.cashEntriesImported) // ce_dev_1 imported
        assertFalse("Existing account profile preserved", result.profileAdopted)

        // Target has BOTH Crypto and Maya
        val targetWalletIds = tgtStore.allMoneyAccounts().map { it.id }.toSet()
        assertTrue(targetWalletIds.contains("acc_crypto"))
        assertTrue(targetWalletIds.contains("acc_maya"))

        // Target has BOTH cash entries
        val targetCashIds = tgtStore.cashEntries().map { it.id }.toSet()
        assertTrue(targetCashIds.contains("ce_tgt_1"))
        assertTrue(targetCashIds.contains("ce_dev_1"))
        assertEquals(35_000L, tgtStore.profile().salary)
    }

    @Test
    fun testRepeatedMigrationIdempotency_executesTwice_zeroDuplicates() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        srcStore.addCash(CashEntry("ce_1", income = true, amount = 10_000L, date = today, note = "Income", channel = "Bank"))

        // First run
        val manifest1 = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger())
        val result1 = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest1)
        assertTrue(result1.success)
        assertEquals(1, result1.cashEntriesImported)

        // Second run on same stores
        val manifest2 = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger())
        val result2 = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest2)
        assertTrue(result2.success)
        assertEquals(0, result2.cashEntriesImported)
        assertEquals(0, result2.walletsImported)
        assertEquals(1, tgtStore.cashEntries().size)
    }

    @Test
    fun testConflictingWalletIds_remappedAndRewritesReferences() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        // Colliding wallet ID with conflicting metadata
        val srcWallet = MoneyAccount("acc_custom_1", "Maya", isSavings = true, icon = "Savings")
        val tgtWallet = MoneyAccount("acc_custom_1", "Maya Business", isSavings = false, icon = "Wallet")
        srcStore.saveMoneyAccounts(listOf(srcWallet))
        tgtStore.saveMoneyAccounts(listOf(tgtWallet))

        srcStore.addCash(CashEntry("ce_maya", income = true, amount = 4_000L, date = today, note = "Cash in", channel = "Maya"))
        val link = PaymentAccountLink(paymentId = "p_maya", loanId = "l1", accountId = "acc_custom_1", accountName = "Maya")
        srcStore.savePaymentLink(link)

        val manifest = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger())
        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)

        assertTrue(result.success)
        val remappedId = manifest.walletMappingPlan.remappedWalletIds["acc_custom_1"]!!
        assertTrue(remappedId.startsWith("acc_remap_acc_custom_1_"))

        // Imported cash channel is remapped
        val importedCash = tgtStore.cashEntries().find { it.id == "ce_maya" }!!
        assertEquals("Maya (Imported)", importedCash.channel)

        // Imported payment link accountId is remapped
        val importedLink = tgtStore.allPaymentLinks()["p_maya"]!!
        assertEquals(remappedId, importedLink.accountId)
        assertEquals("Maya (Imported)", importedLink.accountName)
    }

    @Test
    fun testAmbiguousLegacyWalletNames_flaggedAndPreserved() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        srcStore.addCash(CashEntry("ce_ambiguous", income = false, amount = 500L, date = today, note = "Coffee", channel = "PocketCash2022"))

        val manifest = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger())
        assertEquals(1, manifest.validation.ambiguousLegacyChannelsCount)

        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)
        assertTrue(result.success)
        assertEquals(1, result.cashEntriesImported)
        assertEquals("PocketCash2022", tgtStore.cashEntries().first().channel)
    }

    @Test
    fun testSameDateLegitimateSalaryDeposits_preservedNotSkipped() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        // Source has deposit of ₱15,000 in Bank on 'today'
        val srcDeposit = CashEntry("salary_cutoff_${today}_job1", income = true, amount = 15_000L, date = today, note = "Job 1", channel = "Bank")
        srcStore.addCash(srcDeposit)

        // Target already has deposit on 'today' of ₱5,000 in GCash (different amount, different account)
        val tgtDeposit = CashEntry("salary_cutoff_${today}_job2", income = true, amount = 5_000L, date = today, note = "Job 2", channel = "GCash")
        tgtStore.addCash(tgtDeposit)

        val manifest = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger())
        // Legitimate separate deposit must NOT be skipped!
        assertEquals(1, manifest.cashEntriesToImport.size)
        assertEquals(0, manifest.cashEntriesToSkip.size)

        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)
        assertTrue(result.success)
        assertEquals(2, tgtStore.cashEntries().size)
    }

    @Test
    fun testDuplicateSalaryDeposits_identicalCutoff_skipped() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        val deposit = CashEntry("salary_cutoff_${today}_acc_bank", income = true, amount = 20_000L, date = today, note = "Salary", channel = "Bank")
        srcStore.addCash(deposit)
        tgtStore.addCash(deposit)

        val manifest = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger())
        assertEquals(1, manifest.cashEntriesToSkip.size)
        assertEquals(0, manifest.cashEntriesToImport.size)

        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)
        assertTrue(result.success)
        assertEquals(0, result.cashEntriesImported)
        assertEquals(1, tgtStore.cashEntries().size)
    }

    @Test
    fun testSalaryAdjustmentAndReversalPreservation_intactNetIntegrity() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        val adj = CashEntry("salary_adj_${today}_100", income = true, amount = 5_000L, date = today, note = "Adjustment", channel = "Bank")
        val undoAdj = CashEntry("undo_salary_adj_${today}_100", income = false, amount = 5_000L, date = today, note = "Reversal", channel = "Bank")
        srcStore.addCash(adj)
        srcStore.addCash(undoAdj)

        val manifest = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger())
        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)

        assertTrue(result.success)
        assertEquals(2, result.cashEntriesImported)

        // Invariant: PaydayPlanner.calculate excludes salary-related from spendable cash
        val plan = PaydayPlanner.calculate(tgtStore.profile(), emptyList(), emptyMap(), tgtStore.cashEntries(), today)
        assertEquals(0L, plan.cash)
    }

    @Test
    fun testPendingPaymentWalletLinks_importedAndMapped() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        val loan = Loan(id = "loan_pending", party = "Fintech", mode = LoanMode.I_OWE, total = 6_000L, count = 2, firstDue = today, frequency = Frequency.MONTHLY)
            .pay(0, 3_000L, "pay_pending_1", today)
        val link = PaymentAccountLink(paymentId = "pay_pending_1", loanId = "loan_pending", accountId = "acc_bank", accountName = "Bank")
        srcStore.savePaymentLink(link)

        val manifest = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger(), listOf(loan))
        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)

        assertTrue(result.success)
        assertEquals(1, result.paymentLinksImported)
        assertNotNull(tgtStore.paymentLink("pay_pending_1"))
    }

    @Test
    fun testIncompleteCloudSynchronization_localMigrationSucceedsWithoutFirestore() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        srcStore.addCash(CashEntry("c_offline", income = true, amount = 1_000L, date = today, note = "Offline item", channel = "Bank"))

        // Unready loan repo (simulating disconnected Firestore)
        val unreadyStore = FakeLoanRepository(currentLoans = emptyList(), isReadyFlag = false)

        val manifest = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger(), unreadyStore.load())
        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)

        assertTrue("Local financial migration succeeds even when cloud is unready", result.success)
        assertEquals(1, tgtStore.cashEntries().size)
    }

    @Test
    fun testStaleMigrationManifest_sourceChangedConcurrently_rejectsExecution() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        srcStore.addCash(CashEntry("c_orig", income = true, amount = 1_000L, date = today, note = "Orig", channel = "Bank"))
        val manifest = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger())

        // Concurrent write to source store after manifest inspection
        srcStore.addCash(CashEntry("c_concurrent", income = false, amount = 500L, date = today, note = "Concurrent", channel = "Bank"))

        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)
        assertFalse("Must reject stale manifest", result.success)
        assertTrue(result.errorMessage?.contains("source device records changed concurrently") == true)
        assertEquals(0, tgtStore.cashEntries().size)
    }

    @Test
    fun testStaleMigrationManifest_targetChangedConcurrently_rejectsExecution() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        val manifest = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger())

        // Concurrent write to target store after manifest inspection
        tgtStore.addCash(CashEntry("c_target_concurrent", income = true, amount = 999L, date = today, note = "Concurrent", channel = "Bank"))

        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)
        assertFalse("Must reject stale manifest", result.success)
        assertTrue(result.errorMessage?.contains("target account records changed concurrently") == true)
        assertEquals(1, tgtStore.cashEntries().size) // only original target entry preserved
    }

    @Test
    fun testCrashRecovery_beforeLocalCommit_restoresPreStateCleanly() {
        val fakeTgtPrefs = FakeSharedPreferences()
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)
        tgtStore.addCash(CashEntry("c_initial", income = true, amount = 5_000L, date = today, note = "Initial", channel = "Bank"))

        val journalStore = MigrationJournalStore(context = null)
        val preSnapshot = tgtStore.exportPreferencesSnapshot()

        val entry = MigrationJournalEntry(
            migrationId = "mig_crash_pre",
            timestampMs = System.currentTimeMillis(),
            schemaVersion = 1,
            stage = MigrationStage.READY_TO_COMMIT,
            sourceScope = "device",
            targetScope = "account_tgt",
            manifestId = "man_crash",
            preTargetSnapshotJson = preSnapshot
        )
        journalStore.saveJournal(entry)

        // Simulate interrupted dirty write in target preferences
        fakeTgtPrefs.data["corrupted_field"] = "bad"
        fakeTgtPrefs.data["cash"] = "[]"

        val recovered = journalStore.recoverIfInterrupted(tgtStore)
        assertTrue("Recovered", recovered)
        assertFalse(fakeTgtPrefs.data.containsKey("corrupted_field"))
        assertEquals(1, tgtStore.cashEntries().size)
        assertEquals("c_initial", tgtStore.cashEntries().first().id)
        assertNull(journalStore.loadJournal())
    }

    @Test
    fun testCrashRecovery_afterLocalCommit_doesNotRollbackVerifiedCommit() {
        val fakeTgtPrefs = FakeSharedPreferences()
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        val journalStore = MigrationJournalStore(context = null)
        val entry = MigrationJournalEntry(
            migrationId = "mig_committed",
            timestampMs = System.currentTimeMillis(),
            schemaVersion = 1,
            stage = MigrationStage.LOCAL_COMMITTED,
            sourceScope = "device",
            targetScope = "account_tgt",
            manifestId = "man_committed",
            preTargetSnapshotJson = "{}"
        )
        journalStore.saveJournal(entry)

        // Recovery check: Must NOT roll back a verified local commit
        val recovered = journalStore.recoverIfInterrupted(tgtStore)
        assertFalse("Verified local commit is preserved", recovered)
        assertNotNull(journalStore.loadJournal())
    }

    @Test
    fun testCorruptedRecoveryJournal_defensiveHandling() {
        val journalStore = MigrationJournalStore(context = null)
        val file = File(System.getProperty("java.io.tmpdir", "."), "casha_checkpoints/checkpoints/migration_journal.json")
        file.parentFile?.mkdirs()
        file.writeText("{ corrupted_json !!!")

        val recovered = journalStore.recoverIfInterrupted(null)
        assertFalse(recovered)
    }

    @Test
    fun testAccountSwitchingDuringMigration_boundToTargetScope() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtAPrefs = FakeSharedPreferences()
        val fakeTgtBPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStoreA = FinanceStore(context = null, scope = "account_A", preferences = fakeTgtAPrefs)
        val tgtStoreB = FinanceStore(context = null, scope = "account_B", preferences = fakeTgtBPrefs)

        srcStore.addCash(CashEntry("c_scoped", income = true, amount = 1_000L, date = today, note = "Scoped", channel = "Bank"))

        val manifestA = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStoreA.inspectLedger())
        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStoreA, manifestA)
        assertTrue(result.success)

        // Account A received records; Account B is completely untouched
        assertEquals(1, tgtStoreA.cashEntries().size)
        assertEquals(0, tgtStoreB.cashEntries().size)
    }

    @Test
    fun testRollbackWithoutLosingUnrelatedTransactions_preservesSourceAndTargetPreState() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        srcStore.addCash(CashEntry("c_src", income = true, amount = 10_000L, date = today, note = "Source", channel = "Bank"))
        tgtStore.addCash(CashEntry("c_existing_tgt", income = false, amount = 2_000L, date = today, note = "Existing", channel = "Bank"))

        val journalStore = MigrationJournalStore(context = null)
        val preSnapshot = tgtStore.exportPreferencesSnapshot()

        val entry = MigrationJournalEntry(
            migrationId = "mig_fail",
            timestampMs = System.currentTimeMillis(),
            schemaVersion = 1,
            stage = MigrationStage.FAILED,
            sourceScope = "device",
            targetScope = "account_tgt",
            manifestId = "man_fail",
            preTargetSnapshotJson = preSnapshot
        )
        journalStore.saveJournal(entry)

        journalStore.recoverIfInterrupted(tgtStore)

        assertEquals(1, tgtStore.cashEntries().size)
        assertEquals("c_existing_tgt", tgtStore.cashEntries().first().id)
        assertEquals(1, srcStore.cashEntries().size)
    }

    @Test
    fun testPaymentAccounting_reproduces20kVs15kDiscrepancy_andVerifiesProvisionalAccounting() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        val bankWallet = MoneyAccount("acc_bank", "Bank")
        srcStore.saveMoneyAccounts(listOf(bankWallet))

        val salaryDeposit = CashEntry("salary_cutoff_${today}_acc_bank", income = true, amount = 20_000L, date = today, note = "Salary", channel = "Bank")
        srcStore.addCash(salaryDeposit)

        val guestLoan = Loan(id = "loan_guest", party = "Banker", mode = LoanMode.I_OWE, total = 10_000L, count = 2, firstDue = today, frequency = Frequency.MONTHLY)
            .pay(0, 5_000L, "pay_1", today)
        val link = PaymentAccountLink(paymentId = "pay_1", loanId = "loan_guest", accountId = "acc_bank", accountName = "Bank", amountCentavos = 5_000L, date = today)
        srcStore.savePaymentLink(link)

        // 1. Source balance before migration: ₱15,000 (₱20k salary - ₱5k loan payment)
        val srcBalances = PaydayPlanner.accountBalances(
            accounts = srcStore.allMoneyAccounts(),
            entries = srcStore.cashEntries(),
            profile = null,
            defaultSalaryAccountId = null,
            paymentLinks = srcStore.allPaymentLinks(),
            loans = listOf(guestLoan)
        )
        assertEquals(15_000L, srcBalances["acc_bank"])

        // 2. Perform Migration
        val manifest = MigrationInspector.inspect(
            source = srcStore.inspectLedger(),
            target = tgtStore.inspectLedger(),
            sourceLoans = listOf(guestLoan),
            targetLoans = emptyList() // Target account does not have guestLoan yet!
        )
        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)
        assertTrue(result.success)

        // 3. Flawed baseline: if paymentLinks did not carry provisional effect, balance would be ₱20,000
        val balanceWithoutProvisional = PaydayPlanner.accountBalances(
            accounts = tgtStore.allMoneyAccounts(),
            entries = tgtStore.cashEntries(),
            profile = null,
            defaultSalaryAccountId = null,
            paymentLinks = emptyMap(),
            loans = emptyList()
        )
        assertEquals("Without payment link context, balance inflates to ₱20,000", 20_000L, balanceWithoutProvisional["acc_bank"])

        // 4. Corrected baseline: provisional accounting correctly produces ₱15,000 even without target loans!
        val balanceWithProvisional = PaydayPlanner.accountBalances(
            accounts = tgtStore.allMoneyAccounts(),
            entries = tgtStore.cashEntries(),
            profile = null,
            defaultSalaryAccountId = null,
            paymentLinks = tgtStore.allPaymentLinks(),
            loans = emptyList() // Authoritative loan record is absent from target
        )
        assertEquals("Provisional accounting resolves to exact ₱15,000 balance", 15_000L, balanceWithProvisional["acc_bank"])
    }

    @Test
    fun testPaymentAccounting_lifecycleArrivalReversalRejectionRepeatedSync() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        val bankWallet = MoneyAccount("acc_bank", "Bank")
        srcStore.saveMoneyAccounts(listOf(bankWallet))

        val salaryDeposit = CashEntry("salary_cutoff_${today}_acc_bank", income = true, amount = 20_000L, date = today, note = "Salary", channel = "Bank")
        srcStore.addCash(salaryDeposit)

        val guestLoan = Loan(id = "loan_guest", party = "Banker", mode = LoanMode.I_OWE, total = 10_000L, count = 2, firstDue = today, frequency = Frequency.MONTHLY)
            .pay(0, 5_000L, "pay_1", today)
        val link = PaymentAccountLink(paymentId = "pay_1", loanId = "loan_guest", accountId = "acc_bank", accountName = "Bank", amountCentavos = 5_000L, date = today)
        srcStore.savePaymentLink(link)

        val manifest = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger(), sourceLoans = listOf(guestLoan))
        MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)

        // Stage 0: Provisional effect alone produces ₱15,000
        val balStage0 = PaydayPlanner.accountBalances(
            accounts = tgtStore.allMoneyAccounts(),
            entries = tgtStore.cashEntries(),
            paymentLinks = tgtStore.allPaymentLinks(),
            loans = emptyList()
        )
        assertEquals(15_000L, balStage0["acc_bank"])

        // Stage 1: Authoritative loan arrives in destination (via cloud sync or Phase 02.3 import)
        val balStage1 = PaydayPlanner.accountBalances(
            accounts = tgtStore.allMoneyAccounts(),
            entries = tgtStore.cashEntries(),
            paymentLinks = tgtStore.allPaymentLinks(),
            loans = listOf(guestLoan)
        )
        assertEquals("Authoritative arrival transitions seamlessly without double deduction", 15_000L, balStage1["acc_bank"])

        // Stage 2: Payment is reversed on the authoritative loan
        val reversedLoan = guestLoan.reverse("pay_1", today, "Customer dispute reversal")
        val balStage2 = PaydayPlanner.accountBalances(
            accounts = tgtStore.allMoneyAccounts(),
            entries = tgtStore.cashEntries(),
            paymentLinks = tgtStore.allPaymentLinks(),
            loans = listOf(reversedLoan)
        )
        assertEquals("Reversal suppresses both authoritative and provisional debits: restores to ₱20,000", 20_000L, balStage2["acc_bank"])

        // Stage 3: Payment link is removed upon rejection/purge
        tgtStore.removePaymentLink("pay_1")
        val balStage3 = PaydayPlanner.accountBalances(
            accounts = tgtStore.allMoneyAccounts(),
            entries = tgtStore.cashEntries(),
            paymentLinks = tgtStore.allPaymentLinks(),
            loans = emptyList()
        )
        assertEquals("Rejected/removed link restores balance to ₱20,000", 20_000L, balStage3["acc_bank"])

        // Stage 4: Repeated sync remains idempotent
        val balStage4 = PaydayPlanner.accountBalances(
            accounts = tgtStore.allMoneyAccounts(),
            entries = tgtStore.cashEntries(),
            paymentLinks = mapOf("pay_1" to link),
            loans = listOf(guestLoan, guestLoan) // simulated repeated sync query
        )
        assertEquals(15_000L, balStage4["acc_bank"])
    }

    @Test
    fun testWalletArchivalPreservation_migratedArchivedWalletsRetainArchivedState() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        val activeWallet = MoneyAccount("acc_active", "Active Bank", isArchived = false)
        val archivedWallet = MoneyAccount("acc_archived", "Old BDO", isArchived = true)
        srcStore.saveMoneyAccounts(listOf(activeWallet, archivedWallet))

        val manifest = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger())
        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)
        assertTrue(result.success)

        val allTgtWallets = tgtStore.allMoneyAccounts()
        val importedArchived = allTgtWallets.find { it.id == "acc_archived" }
        assertNotNull(importedArchived)
        assertTrue("Archived wallet retains isArchived=true flag", importedArchived!!.isArchived)

        // Active wallet list in UI must NOT include archived wallet
        val activeTgtWallets = tgtStore.moneyAccounts()
        assertTrue(activeTgtWallets.any { it.id == "acc_active" })
        assertFalse(activeTgtWallets.any { it.id == "acc_archived" })
    }

    @Test
    fun testJournalRecoveryLifecycle_crashesAtEachStateTransition() {
        val fakeTgtPrefs = FakeSharedPreferences()
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)
        val journalStore = MigrationJournalStore(context = null)

        val preSnapshot = tgtStore.exportPreferencesSnapshot()

        // 1. Crash during READY_TO_COMMIT: must rollback target dirty writes
        val entryReady = MigrationJournalEntry("mig_1", System.currentTimeMillis(), 1, MigrationStage.READY_TO_COMMIT, "device", "account_tgt", "man_1", preSnapshot)
        journalStore.saveJournal(entryReady)
        fakeTgtPrefs.data["dirty_key"] = "bad_val"
        val rec1 = journalStore.recoverIfInterrupted(tgtStore)
        assertTrue("Ready-to-commit recovery succeeds", rec1)
        assertFalse(fakeTgtPrefs.data.containsKey("dirty_key"))
        assertNull(journalStore.loadJournal())

        // 2. Crash during LOCAL_COMMITTED: local commit is verified and must NOT be rolled back
        val entryCommitted = MigrationJournalEntry("mig_2", System.currentTimeMillis(), 1, MigrationStage.LOCAL_COMMITTED, "device", "account_tgt", "man_2", preSnapshot)
        journalStore.saveJournal(entryCommitted)
        fakeTgtPrefs.data["valid_migrated_key"] = "good_val"
        val rec2 = journalStore.recoverIfInterrupted(tgtStore)
        assertFalse("Local-committed recovery does not roll back", rec2)
        assertTrue(fakeTgtPrefs.data.containsKey("valid_migrated_key"))
        assertNotNull(journalStore.loadJournal())

        // 3. Crash during BALANCES_VERIFIED: finalized cleanly
        val entryVerified = MigrationJournalEntry("mig_3", System.currentTimeMillis(), 1, MigrationStage.BALANCES_VERIFIED, "device", "account_tgt", "man_3", preSnapshot)
        journalStore.saveJournal(entryVerified)
        val rec3 = journalStore.recoverIfInterrupted(tgtStore)
        assertTrue("Balances-verified recovery finalizes cleanly", rec3)
        assertNull(journalStore.loadJournal())
    }

    @Test
    fun testConcurrentWriteProtection_interleavedWriteRejected() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        val manifest = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger())

        // Simulate concurrent write occurring to target store right after manifest inspection
        tgtStore.addCash(CashEntry("concurrent_cash", income = true, amount = 1_000L, date = today, note = "Concurrent", channel = "Bank"))

        // Execution must reject stale manifest
        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)
        assertFalse(result.success)
        assertEquals(MigrationStage.FAILED, result.stage)
        assertTrue(result.errorMessage!!.contains("target account records changed concurrently"))
    }

    @Test
    fun testExplicitAmbiguousSalaryResolution_unresolvedBlocksExecution_explicitOptionsSucceed() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        val srcSalary = CashEntry("salary_cutoff_${today}_acc_bank", income = true, amount = 20_000L, date = today, note = "Salary Bank", channel = "Bank")
        val tgtSalary = CashEntry("salary_cutoff_${today}_acc_payroll", income = true, amount = 20_000L, date = today, note = "Salary Payroll", channel = "Bank")
        srcStore.addCash(srcSalary)
        tgtStore.addCash(tgtSalary)

        // Case A: Unresolved ambiguity (default / empty resolution map)
        val manifestUnresolved = MigrationInspector.inspect(
            source = srcStore.inspectLedger(),
            target = tgtStore.inspectLedger(),
            options = MigrationOptions(salaryResolutions = emptyMap())
        )
        assertTrue("Ambiguity flagged in validation", manifestUnresolved.hasUnresolvedSalaryAmbiguities)
        assertEquals(1, manifestUnresolved.validation.ambiguousSalaryDeposits.size)
        assertNull(manifestUnresolved.validation.ambiguousSalaryDeposits[0].resolution)

        // Attempting to execute migration without explicit resolution must fail
        val resultBlocked = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifestUnresolved)
        assertFalse("Migration blocked while ambiguities unresolved", resultBlocked.success)
        assertEquals(MigrationStage.FAILED, resultBlocked.stage)
        assertTrue(resultBlocked.errorMessage!!.contains("Unresolved ambiguous salary deposits require an explicit user choice"))

        // Case B: Explicit user choice "Treat as duplicate"
        val fakeTgtPrefsDup = FakeSharedPreferences()
        val tgtStoreDup = FinanceStore(context = null, scope = "account_tgt_dup", preferences = fakeTgtPrefsDup)
        tgtStoreDup.addCash(tgtSalary)

        val manifestDuplicate = MigrationInspector.inspect(
            source = srcStore.inspectLedger(),
            target = tgtStoreDup.inspectLedger(),
            options = MigrationOptions(salaryResolutions = mapOf(srcSalary.id to SalaryResolutionAction.TREAT_AS_DUPLICATE))
        )
        assertFalse("Resolution resolved ambiguity", manifestDuplicate.hasUnresolvedSalaryAmbiguities)
        assertEquals(1, manifestDuplicate.cashEntriesToSkip.size)
        assertEquals(0, manifestDuplicate.cashEntriesToImport.size)

        val resultDup = MigrationExecutionEngine.executeMigration(srcStore, tgtStoreDup, manifestDuplicate)
        assertTrue(resultDup.success)
        assertEquals("Target contains only original deposit, avoiding double count", 1, tgtStoreDup.cashEntries().size)

        // Case C: Explicit user choice "Keep both"
        val fakeTgtPrefsBoth = FakeSharedPreferences()
        val tgtStoreBoth = FinanceStore(context = null, scope = "account_tgt_both", preferences = fakeTgtPrefsBoth)
        tgtStoreBoth.addCash(tgtSalary)

        val manifestKeepBoth = MigrationInspector.inspect(
            source = srcStore.inspectLedger(),
            target = tgtStoreBoth.inspectLedger(),
            options = MigrationOptions(salaryResolutions = mapOf(srcSalary.id to SalaryResolutionAction.KEEP_BOTH))
        )
        assertFalse(manifestKeepBoth.hasUnresolvedSalaryAmbiguities)
        assertEquals(0, manifestKeepBoth.cashEntriesToSkip.size)
        assertEquals(1, manifestKeepBoth.cashEntriesToImport.size)

        val resultBoth = MigrationExecutionEngine.executeMigration(srcStore, tgtStoreBoth, manifestKeepBoth)
        assertTrue(resultBoth.success)
        assertEquals("Target contains both distinct deposits", 2, tgtStoreBoth.cashEntries().size)

        // Case D: Canceled review (user dismissed review without making a choice)
        val manifestCanceled = MigrationInspector.inspect(
            source = srcStore.inspectLedger(),
            target = tgtStore.inspectLedger(),
            options = MigrationOptions(salaryResolutions = emptyMap())
        )
        assertTrue("Canceled review leaves ambiguities unresolved", manifestCanceled.hasUnresolvedSalaryAmbiguities)
        val resultCanceled = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifestCanceled)
        assertFalse("Canceled review blocks migration execution", resultCanceled.success)
    }

    @Test
    fun testExplicitAmbiguousSalaryResolution_manifestInvalidation_whenFinancialDataMutates() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)

        val srcSalary = CashEntry("salary_cutoff_${today}_acc_bank", income = true, amount = 20_000L, date = today, note = "Salary Bank", channel = "Bank")
        val tgtSalary = CashEntry("salary_cutoff_${today}_acc_payroll", income = true, amount = 20_000L, date = today, note = "Salary Payroll", channel = "Bank")
        srcStore.addCash(srcSalary)
        tgtStore.addCash(tgtSalary)

        val manifest = MigrationInspector.inspect(
            source = srcStore.inspectLedger(),
            target = tgtStore.inspectLedger(),
            options = MigrationOptions(salaryResolutions = mapOf(srcSalary.id to SalaryResolutionAction.KEEP_BOTH))
        )
        assertFalse(manifest.hasUnresolvedSalaryAmbiguities)

        // Concurrently mutate target financial data after manifest creation
        tgtStore.addCash(CashEntry("extra_cash", income = false, amount = 500L, date = today, note = "Snack", channel = "Bank"))

        // Execution must reject stale manifest
        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)
        assertFalse(result.success)
        assertEquals(MigrationStage.FAILED, result.stage)
        assertTrue(result.errorMessage!!.contains("target account records changed concurrently"))
    }

    @Test
    fun testResumeIncompleteMigrationVerification_validCommittedTarget_transitionsToCompleted() {
        val fakeTgtPrefs = FakeSharedPreferences()
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)
        val journalStore = MigrationJournalStore(context = null)

        val bankWallet = MoneyAccount("acc_bank", "Bank")
        tgtStore.saveMoneyAccounts(listOf(bankWallet))
        tgtStore.addCash(CashEntry("entry_1", income = true, amount = 10_000L, date = today, note = "Deposit", channel = "Bank"))
        fakeTgtPrefs.data["migrated_from_scope"] = "device"

        val entryCommitted = MigrationJournalEntry(
            migrationId = "mig_recovery_valid",
            timestampMs = System.currentTimeMillis(),
            schemaVersion = MigrationJournalStore.CURRENT_SCHEMA_VERSION,
            stage = MigrationStage.LOCAL_COMMITTED,
            sourceScope = "device",
            targetScope = "account_tgt",
            manifestId = "man_recovery_valid",
            preTargetSnapshotJson = "{}"
        )
        journalStore.saveJournal(entryCommitted)

        // Startup recovery resumes balance and reference validation against the committed target
        val recovered = journalStore.recoverIfInterrupted(tgtStore)
        assertTrue("Recovery successfully completes valid committed target", recovered)
        assertNull("Journal is cleared after transitioning through BALANCES_VERIFIED and COMPLETED", journalStore.loadJournal())
    }

    @Test
    fun testResumeIncompleteMigrationVerification_validationFails_retainsJournalAcrossRepeatedCrashes() {
        val fakeTgtPrefs = FakeSharedPreferences()
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)
        val journalStore = MigrationJournalStore(context = null)

        // Target store is missing "migrated_from_scope" marker and money accounts, failing invariants
        val entryCommitted = MigrationJournalEntry(
            migrationId = "mig_recovery_invalid",
            timestampMs = System.currentTimeMillis(),
            schemaVersion = MigrationJournalStore.CURRENT_SCHEMA_VERSION,
            stage = MigrationStage.LOCAL_COMMITTED,
            sourceScope = "device",
            targetScope = "account_tgt",
            manifestId = "man_recovery_invalid",
            preTargetSnapshotJson = "{}"
        )
        journalStore.saveJournal(entryCommitted)

        // First recovery attempt (initial startup crash recovery)
        val recovered1 = journalStore.recoverIfInterrupted(tgtStore)
        assertFalse("Validation failure prevents false completion", recovered1)
        val retainedJournal1 = journalStore.loadJournal()
        assertNotNull("Journal retained when validation fails", retainedJournal1)
        assertEquals(MigrationStage.LOCAL_COMMITTED, retainedJournal1?.stage)

        // Second recovery attempt (app crashed or restarted again without resolution)
        val recovered2 = journalStore.recoverIfInterrupted(tgtStore)
        assertFalse("Subsequent recovery attempt also fails safely", recovered2)
        val retainedJournal2 = journalStore.loadJournal()
        assertNotNull("Journal safely retained across repeated restarts", retainedJournal2)
        assertEquals(MigrationStage.LOCAL_COMMITTED, retainedJournal2?.stage)
    }

    @Test
    fun testOverlappingMigration_blockedWhileUnresolvedJournalExists() {
        val fakeSrcPrefs = FakeSharedPreferences()
        val fakeTgtPrefs = FakeSharedPreferences()

        val srcStore = FinanceStore(context = null, scope = "device", preferences = fakeSrcPrefs)
        val tgtStore = FinanceStore(context = null, scope = "account_tgt", preferences = fakeTgtPrefs)
        val journalStore = MigrationJournalStore(context = null)

        val entryPending = MigrationJournalEntry(
            migrationId = "mig_pending",
            timestampMs = System.currentTimeMillis(),
            schemaVersion = MigrationJournalStore.CURRENT_SCHEMA_VERSION,
            stage = MigrationStage.LOCAL_COMMITTED,
            sourceScope = "device",
            targetScope = "account_tgt",
            manifestId = "man_pending",
            preTargetSnapshotJson = "{}"
        )
        journalStore.saveJournal(entryPending)

        assertTrue(journalStore.hasUnresolvedJournal("account_tgt"))

        val manifest = MigrationInspector.inspect(srcStore.inspectLedger(), tgtStore.inspectLedger())

        // Executing migration while unresolved journal exists must be rejected
        val result = MigrationExecutionEngine.executeMigration(srcStore, tgtStore, manifest)
        assertFalse(result.success)
        assertEquals(MigrationStage.FAILED, result.stage)
        assertTrue(result.errorMessage!!.contains("Overlapping migrations are blocked"))
    }
}
