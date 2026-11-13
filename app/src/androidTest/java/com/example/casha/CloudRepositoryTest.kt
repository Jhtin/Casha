package com.example.casha

import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.*
import com.example.casha.domain.*
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CloudRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "cloud_test_${java.util.UUID.randomUUID()}"
    private val repositories = mutableListOf<CloudLoanRepository>()
    private val accounts = mutableSetOf<String>()
    private fun loan() = Loan(id = "loan-1", party = "Test lender", mode = LoanMode.I_OWE, total = 10_000,
        count = 1, firstDue = LocalDate.of(2026,1,1), frequency = Frequency.MONTHLY)
    private fun repo(uid: String = "alice", remote: FakeCloud = FakeCloud(), owner: () -> String? = {uid}): CloudLoanRepository {
        accounts += uid
        return CloudLoanRepository(context,uid,owner,remote,namespace).also { repositories += it }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue("Timed out", condition())
    }
    @After fun cleanup() {
        repositories.forEach { it.close() }
        accounts.forEach { uid ->
            val hash = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }
            context.deleteSharedPreferences("${namespace}_$hash")
        }
    }
    @Test fun offlineJournalSurvivesRestartAndAccountsAreIsolated() {
        val a = repo(); assertTrue(a.save(listOf(loan()))); a.close()
        val restored = repo(); assertEquals(listOf(loan()), restored.load()); assertEquals(1,restored.state.value.pending)
        val b = repo("bob"); assertTrue(b.load().isEmpty())
    }
    @Test fun staleScreenCannotOverwriteMoreRecentLocalChange() {
        val a = repo(); val initial=loan(); assertTrue(a.save(listOf(initial)))
        assertFalse(a.save(listOf(initial.copy(party="Changed")), emptyList()))
        assertEquals(initial,a.load().single())
    }
    @Test fun queueRequiresConsentAndRetriesAfterConnectionReturns() {
        val remote=FakeCloud(); remote.offline=true
        val a=repo(remote=remote); assertTrue(a.save(listOf(loan())))
        assertEquals(0,remote.calls); assertTrue(a.enable())
        await { a.state.value.message.contains("Cloud sync failed") }; assertEquals(1,a.state.value.pending)
        remote.offline=false; a.retry(); await { a.state.value.pending==0 }
        assertEquals(loan(),remote.record)
    }
    @Test fun conflictKeepsQueuedVersionUntilExplicitResolution() {
        val remote=FakeCloud(); remote.record=loan().copy(party="Other device")
        val a=repo(remote=remote); assertTrue(a.save(listOf(loan())))
        a.enable(); await { a.state.value.conflictLoan!=null }
        assertEquals("Test lender",a.load().single().party)
        assertEquals(1,a.state.value.pending)
        assertTrue(a.useCloudVersion()); assertEquals("Other device",a.load().single().party)
    }
    @Test fun changingIdentityPreventsLoadsSavesAndUploads() {
        var uid:String?="alice"; val remote=FakeCloud(); val a=repo(remote=remote,owner={uid})
        a.save(listOf(loan())); uid="bob"
        assertFalse(a.enable()); assertFalse(a.save(listOf(loan().copy(party="Wrong account"))))
        assertTrue(runCatching { a.load() }.isFailure); assertEquals(0,remote.calls)
    }
    @Test fun importPreservesIdsAndHistoryAndCannotBeRepeated() {
        val date=LocalDate.of(2026,1,1)
        val original=loan().pay(0,10_000,"p1",date).reverse("p1",date,"Incorrect").pay(0,500,"p2",date)
        val remote=FakeCloud(); val a=repo(remote=remote)
        assertTrue(a.importLocal(listOf(original))); assertFalse(a.importLocal(listOf(original)))
        assertEquals(original,a.load().single()); assertEquals(0,remote.calls)
        a.close()
        val restored=repo(remote=remote); assertEquals(original,restored.load().single()); assertTrue(restored.imported())
        restored.enable(); await { restored.state.value.pending==0 }; assertEquals(original,remote.record)
    }
    @Test fun offlineDeletionSurvivesRestartSyncsAndCannotReappear() {
        val remote=FakeCloud();val a=repo(remote=remote);val original=loan()
        assertTrue(a.save(listOf(original)));assertTrue(a.deleteUnpaid(original));assertTrue(a.load().isEmpty());a.close()
        val restored=repo(remote=remote);assertTrue(restored.load().isEmpty());assertEquals(2,restored.state.value.pending)
        assertTrue(restored.enable());await {restored.state.value.pending==0}
        assertNull(remote.record);assertTrue(restored.load().isEmpty());restored.close()
        val again=repo(remote=remote);assertTrue(again.load().isEmpty())
        assertFalse(again.save(listOf(original)));assertFalse(again.importLocal(listOf(original)))
    }
    @Test fun concurrentRemotePaymentStopsDeletionAndCanBeRecovered() {
        val remote=FakeCloud();val a=repo(remote=remote);val original=loan()
        assertTrue(a.save(listOf(original)));a.enable();await {a.state.value.pending==0}
        val paid=original.pay(0,1000,"remote-payment",LocalDate.of(2026,1,1));remote.record=paid
        assertTrue(a.deleteUnpaid(original));await {a.state.value.conflictLoan!=null}
        assertEquals(paid,remote.record);assertTrue(a.useCloudVersion());assertEquals(paid,a.load().single())
        assertFalse(a.deleteUnpaid(paid));assertFalse(a.deleteUnpaid(original))
    }
    class FakeCloud : LoanCloud {
        @Volatile var record:Loan?=null
        @Volatile var offline=false
        private val deleted=mutableSetOf<String>()
        @Volatile var calls=0
        override fun listen(onRecords:(List<Loan>,Boolean)->Unit,onError:(Exception)->Unit):AutoCloseable {
            onRecords(listOfNotNull(record),true)
            return AutoCloseable { }
        }
        override suspend fun apply(mutation:CloudMutation):Loan {
            calls++
            if(offline) throw IOException("Offline")
            if(mutation.after.id in deleted){if(mutation.kind==MutationKind.DELETE)return mutation.after else throw CloudConflict(null)}
            record?.let { if(mutation.alreadyApplied(it)) return it }
            if(record!=mutation.before) throw CloudConflict(record)
            if(mutation.kind==MutationKind.DELETE){deleted+=mutation.after.id;record=null}else record=mutation.after; return mutation.after
        }
    }
}
