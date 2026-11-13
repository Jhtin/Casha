package com.example.casha

import com.example.casha.data.*
import com.example.casha.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TakeoverSafetyTest {
    private val day = LocalDate.of(2026, 11, 4)
    private fun finance(prefs: FakeSharedPreferences = FakeSharedPreferences(), scope: String = UUID.randomUUID().toString()) =
        FinanceStore(null, scope, prefs)
    private fun loan() = Loan(id="portable_loan", party="Test lender", mode=LoanMode.I_OWE,
        total=10000, count=1, firstDue=day, frequency=Frequency.MONTHLY).pay(0, 2500, "portable_payment", day)
    private fun repository(loans: List<Loan>) = object : LoanRepository {
        override val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
        override fun load() = loans
        override fun save(loans: List<Loan>, expected: List<Loan>?) = false
    }
    private fun populated(): Pair<FinanceStore, Loan> {
        val f = finance()
        f.saveMoneyAccounts(MoneyAccount.defaultAccounts())
        f.addCash(CashEntry("deposit", true, 20000, day, "Funding", MoneyAccount.DEFAULT_BANK))
        val loan = loan()
        f.savePaymentLink(PaymentAccountLink("portable_payment", loan.id, "acc_bank", MoneyAccount.DEFAULT_BANK, false, 2500, day))
        return f to loan
    }

    @Test fun portableBackupPreservesWalletBalanceAcrossScopes() {
        val (source, loan) = populated()
        val backup = PortableBackup.encode(repository(listOf(loan)), source)
        val target = finance()
        assertTrue(PortableBackup.financeIsEmpty(target))
        val restored = PortableBackup.validate(backup, target)
        assertTrue(target.restoreMetadata(target.validatedBackupMetadata(backup.getJSONObject("finance"))))
        fun balances(f: FinanceStore, loans: List<Loan>) = PaydayPlanner.accountBalances(
            f.allMoneyAccounts(), f.cashEntries(), paymentLinks=f.allPaymentLinks(), loans=loans)
        assertEquals(balances(source, listOf(loan)), balances(target, restored))
        assertEquals(17500L, balances(target, restored)["acc_bank"])
    }

    @Test fun exportOmitsDeviceAndSensitiveOperationalState() {
        val f = finance()
        f.preferences.edit().putString("cachedLoans", "private-cache")
            .putString("captured_payments", "[]").putBoolean("has_key", true)
            .putString("secret", "not-portable").putLong("dedupe_payment", 42L)
            .putString("sent_loan", "today").putBoolean("reminders", true).commit()
        val keys = f.exportLocal().getJSONObject("localFinance").keys().asSequence().toSet()
        assertTrue(keys.isEmpty())
    }

    @Test fun orphanedPaymentLinksBlockExport() {
        val (f, _) = populated()
        assertTrue(runCatching { PortableBackup.encode(repository(emptyList()), f) }.isFailure)
    }

    @Test fun tamperedWalletLinkBlocksImport() {
        val (f, loan) = populated()
        val data = PortableBackup.encode(repository(listOf(loan)), f)
        val metadata = data.getJSONObject("finance").getJSONObject("localFinance")
        val key = "payment_account_portable_payment"
        val link = JSONObject(metadata.getString(key)).put("isReceivable", true)
        metadata.put(key, link.toString())
        assertTrue(runCatching { PortableBackup.validate(data, finance()) }.isFailure)
    }

    @Test fun legacyBackupRetainsScopeRestriction() {
        val (f, loan) = populated()
        val data = PortableBackup.encode(repository(listOf(loan)), f).put("schemaVersion", 1)
        assertEquals(listOf(loan), PortableBackup.validate(data, f))
        assertTrue(runCatching { PortableBackup.validate(data, finance()) }.isFailure)
    }

    @Test fun unknownBackupVersionFailsClosed() {
        val f = finance()
        val data = PortableBackup.encode(repository(emptyList()), f).put("schemaVersion", 3)
        assertTrue(runCatching { PortableBackup.validate(data, f) }.isFailure)
    }

    @Test fun customWalletsMakeDestinationNonempty() {
        val f = finance()
        f.saveMoneyAccounts(listOf(MoneyAccount("custom", "My wallet")))
        assertFalse(PortableBackup.financeIsEmpty(f))
    }

    @Test fun concurrentRepeatedCaptureConfirmationProducesOneExpense() {
        val prefs = FakeSharedPreferences()
        val scope = UUID.randomUUID().toString()
        val a = finance(prefs, scope); val b = finance(prefs, scope)
        assertTrue(a.addCapturedPayment(CapturedPayment("capture_test", 5200, "Test merchant", "test", MoneyAccount.DEFAULT_BANK, day)))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (0 until 20).map { i -> pool.submit<Boolean> { (if (i % 2 == 0) a else b).confirmCapturedPayment("capture_test") } }
            assertTrue(results.all { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1, a.cashEntries().size)
            assertEquals(5200L, a.cashEntries().single().amount)
            assertTrue(a.capturedPayments().isEmpty())
        } finally { pool.shutdownNow() }
    }

    @Test fun failedConfirmationRetainsCaptureAndCash() {
        val prefs = FakeSharedPreferences()
        val f = finance(prefs)
        f.addCapturedPayment(CapturedPayment("invalid", -1, "Merchant", "test", MoneyAccount.DEFAULT_BANK, day))
        assertTrue(runCatching { f.confirmCapturedPayment("invalid") }.isFailure)
        assertTrue(f.cashEntries().isEmpty())
        assertEquals(1, f.capturedPayments().size)
    }

    @Test fun restoreCheckpointCannotRollBackAnotherScope() {
        val a = finance(); val b = finance()
        try {
            a.addCash(CashEntry("original", true, 5000, day, "Original"))
            assertTrue(a.createRestoreCheckpoint())
            a.addCash(CashEntry("later", false, 1000, day, "Later"))
            assertFalse(b.recoverFromRestoreCheckpointIfNeeded())
            assertTrue(b.cashEntries().isEmpty())
            assertTrue(a.recoverFromRestoreCheckpointIfNeeded())
            assertEquals(listOf("original"), a.cashEntries().map { it.id })
        } finally { a.completeRestoreCheckpoint(); b.completeRestoreCheckpoint() }
    }

    @Test fun discardedPaymentCannotReappearThroughLaterQueueSnapshot() {
        val base = loan().copy(payments=emptyList())
        val first = base.pay(0,2500,"discard_me",day)
        val second = first.pay(0,1000,"keep_me",day)
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("journal",JSONObject().put("base",LoanCodec.encode(listOf(base)))
            .put("queue",CloudQueueCodec.encode(listOf(CloudMutation(base,first),CloudMutation(first,second))))
            .put("enabled",false).toString()).commit()
        val repo = CloudLoanRepository(null,"safety_queue",{"safety_queue"},FakeLoanCloud(),online={false},preferencesOverride=prefs)
        try {
            assertTrue(repo.discardConflictingMutation(base.id,"discard_me"))
            assertEquals(listOf("keep_me"),repo.load().single().payments.map { it.id })
            assertEquals(base,repo.getQueue().single().before)
            assertEquals(MutationKind.PAYMENT,repo.getQueue().single().kind)
        } finally { repo.close() }
    }

    @Test fun settlementRetriesCannotDoubleDebitOrSwitchDecision() {
        val (f, _) = populated()
        val cash = CashEntry("reconciled_cash_portable_payment",false,2500,day,"External",MoneyAccount.DEFAULT_BANK)
        assertTrue(f.settleProvisionalPayment("portable_payment",cash))
        assertTrue(f.settleProvisionalPayment("portable_payment",cash))
        assertFalse(f.settleProvisionalPayment("portable_payment",null))
        assertNull(f.paymentLink("portable_payment"))
        assertEquals(1,f.cashEntries().count { it.id==cash.id })
        assertEquals(17500L,PaydayPlanner.accountBalances(f.allMoneyAccounts(),f.cashEntries())["acc_bank"])
    }

    @Test fun reassignmentValidatesEntireQueueWithoutPartialWrites() {
        val base = Loan(id="reassign_safety",party="Test lender",mode=LoanMode.I_OWE,total=10000,count=2,firstDue=day,frequency=Frequency.MONTHLY)
        val first = base.pay(0,4000,"move_me",day)
        val second = first.pay(1,3000,"keep_me",day)
        val prefs = FakeSharedPreferences()
        val journal = JSONObject().put("base",LoanCodec.encode(listOf(base)))
            .put("queue",CloudQueueCodec.encode(listOf(CloudMutation(base,first),CloudMutation(first,second))))
            .put("enabled",false).toString()
        prefs.edit().putString("journal",journal).commit()
        val repo = CloudLoanRepository(null,"reassign_safety",{"reassign_safety"},FakeLoanCloud(),online={false},preferencesOverride=prefs)
        try {
            assertFalse(repo.reassignQueuedPaymentInstallment(base.id,"move_me",1))
            assertEquals(second,repo.load().single())
            assertEquals(journal,repo.journalSnapshot())
        } finally { repo.close() }
    }

    @Test fun conflictingCreateAndDeleteRequireExplicitReview() {
        val base = loan().copy(payments=emptyList())
        assertEquals(ThreeWayMergeStatus.CONFLICT_UNRESOLVED,LoanThreeWayMerger.merge(emptyList(),
            listOf(CloudMutation(null,base)),base.copy(party="Other lender")).status)
        assertEquals(ThreeWayMergeStatus.CONFLICT_UNRESOLVED,LoanThreeWayMerger.merge(listOf(base),
            listOf(CloudMutation(base,base,deleting=true)),base.pay(0,1000,"remote",day)).status)
    }

    @Test fun mergePreservesReversalBeforeReplacementPayment() {
        val base = loan().copy(payments=emptyList()).pay(0,10000,"old",day)
        val reversed = base.reverse("old",day,"Correction")
        val replacement = reversed.pay(0,9000,"new",day)
        val remote = base.copy(party="Updated lender")
        val result = LoanThreeWayMerger.merge(listOf(base),listOf(CloudMutation(base,reversed),CloudMutation(reversed,replacement)),remote)
        assertEquals(ThreeWayMergeStatus.AUTO_REBASED,result.status)
        assertEquals(listOf(MutationKind.REVERSAL,MutationKind.PAYMENT),result.rebasedMutations.map { it.kind })
        assertEquals(1000L,result.rebasedMutations.last().after.remaining)
    }

    @Test fun sameReversalIdWithDifferentAuditDetailsIsAConflict() {
        val base = loan()
        val local = base.reverse("portable_payment",day,"Local correction")
        val remote = base.reverse("portable_payment",day,"Remote correction")
        assertEquals(ThreeWayMergeStatus.CONFLICT_UNRESOLVED,LoanThreeWayMerger.merge(listOf(base),
            listOf(CloudMutation(base,local)),remote).status)
    }

    @Test fun restoreUploadGateSurvivesRestartUntilExplicitResume() = kotlinx.coroutines.runBlocking {
        val uid = "gate_${UUID.randomUUID()}"
        val prefs = FakeSharedPreferences(); val cloud = FakeLoanCloud()
        fun repository() = CloudLoanRepository(null,uid,{uid},cloud,online={true},preferencesOverride=prefs)
        var repo = repository()
        try {
            assertTrue(repo.pauseBackupUploads())
            assertTrue(repo.enable())
            assertTrue(repo.restoreBackup(listOf(loan())))
            assertTrue(cloud.remoteLoans.isEmpty())
            repo.close(); repo = repository()
            assertTrue(cloud.remoteLoans.isEmpty())
            assertTrue(repo.resumeBackupUploads())
            kotlinx.coroutines.withTimeout(10_000) {
                while(repo.state.value.pending>0) kotlinx.coroutines.delay(10)
            }
            assertEquals(loan(),cloud.remoteLoans[loan().id])
        } finally { repo.close() }
    }

    @Test fun foregroundAndBackgroundShareWriterUntilBothRelease() {
        val uid = "session_${UUID.randomUUID()}"; var created = 0
        fun acquire() = CloudRepositorySessions.acquire(uid) {
            created++
            CloudLoanRepository(null,uid,{uid},FakeLoanCloud(),online={false},preferencesOverride=FakeSharedPreferences())
        }
        val foreground = acquire(); val background = acquire()
        assertSame(foreground,background); assertEquals(1,created)
        CloudRepositorySessions.release(uid,background)
        assertTrue(foreground.save(listOf(loan().copy(payments=emptyList()))))
        CloudRepositorySessions.release(uid,foreground)
        assertTrue(runCatching { foreground.load() }.isFailure)
        val reopened = acquire()
        try { assertNotSame(foreground,reopened); assertEquals(2,created) }
        finally { CloudRepositorySessions.release(uid,reopened) }
    }
}
