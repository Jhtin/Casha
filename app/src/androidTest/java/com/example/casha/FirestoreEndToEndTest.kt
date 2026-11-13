package com.example.casha

import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.*
import com.example.casha.domain.*
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Named, synthetic Firebase app: these tests cannot target the user's production project. */
class FirestoreEndToEndTest {
    private fun withEmulators(test:suspend (FirebaseAuth,FirebaseFirestore)->Unit) {
        assumeTrue("Start local Firebase emulators and pass firebaseEmulators=true", InstrumentationRegistry.getArguments().getString("firebaseEmulators")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=FirebaseApp.initializeApp(context,FirebaseOptions.Builder().setProjectId("demo-casha")
            .setApplicationId("1:123456789:android:emulatortest").setApiKey("fake-api-key").build(),"emulator-${java.util.UUID.randomUUID()}")
        val auth=FirebaseAuth.getInstance(app);auth.useEmulator("10.0.2.2",9098)
        val db=FirebaseFirestore.getInstance(app);db.useEmulator("10.0.2.2",8087)
        db.firestoreSettings=FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
        try {
            Tasks.await(auth.signInAnonymously(),20,TimeUnit.SECONDS)
            runBlocking { test(auth,db) }
        } finally { Tasks.await(db.terminate(),10,TimeUnit.SECONDS); app.delete() }
    }
    private fun loan()=Loan(party="Emulator lender",mode=LoanMode.I_OWE,total=10_000,count=3,firstDue=LocalDate.of(2026,1,31),frequency=Frequency.MONTHLY)

    @Test fun historicalMigrationReplaysReversalsAndRejectsOtherAccount()=withEmulators { auth,db ->
        val uid=auth.currentUser!!.uid
        val cloud=FirestoreLoanCloud(uid,db)
        val day=LocalDate.of(2026,1,1)
        val original=loan().pay(0,1000,"history_p1",day)
            .reverse("history_p1",day.plusDays(1),"Corrected entry")
            .pay(1,2000,"history_p2",day.plusDays(2))
        CloudMutation.importHistory(original).forEach { mutation ->
            cloud.apply(mutation)
            assertEquals(mutation.after,cloud.apply(mutation))
        }
        val ref=db.collection("users").document(uid).collection("loans").document(original.id)
        assertEquals(4L,Tasks.await(ref.get(Source.SERVER),10,TimeUnit.SECONDS).getLong("revision"))
        auth.signOut()
        Tasks.await(auth.signInAnonymously(),20,TimeUnit.SECONDS)
        assertNotEquals(uid,auth.currentUser!!.uid)
        assertTrue(runCatching { Tasks.await(ref.get(Source.SERVER),10,TimeUnit.SECONDS) }.isFailure)
        assertTrue(runCatching { cloud.apply(CloudMutation(original,original.pay(2,100,"foreign",day))) }.isFailure)
        val other=FirestoreLoanCloud(auth.currentUser!!.uid,db)
        assertEquals(loan().let { fresh -> other.apply(CloudMutation(null,fresh)) }.remaining,10_000L)
    }

    @Test fun realTransactionsDeduplicateRejectConflictsAndReverseUnderRules()=withEmulators { auth,db ->
        val cloud=FirestoreLoanCloud(auth.currentUser!!.uid,db)
        val original=loan();cloud.apply(CloudMutation(null,original))
        val paid=original.pay(0,1000,"p1",LocalDate.of(2026,1,1))
        val mutation=CloudMutation(original,paid)
        assertEquals(paid,cloud.apply(mutation));assertEquals(paid,cloud.apply(mutation))
        val competing=CloudMutation(original,original.pay(0,2000,"p2",LocalDate.of(2026,1,1)))
        assertTrue(runCatching { cloud.apply(competing) }.isFailure)
        val reversed=paid.reverse("p1",LocalDate.of(2026,1,2),"Incorrect")
        assertEquals(10_000L,cloud.apply(CloudMutation(paid,reversed)).remaining)
        val snapshot=Tasks.await(db.collection("users").document(auth.currentUser!!.uid).collection("loans").document(original.id).get(Source.SERVER),10,TimeUnit.SECONDS)
        assertEquals(3L,snapshot.getLong("revision"));assertEquals(1,(snapshot.get("payments") as List<*>).size)
    }
    @Test fun realOfflineQueueSurvivesRepositoryRestartAndSyncsOnRetry()=withEmulators { auth,db ->
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val uid=auth.currentUser!!.uid
        val namespace="e2e_${java.util.UUID.randomUUID()}"
        var online=false
        fun repository()=CloudLoanRepository(context,uid,{auth.currentUser?.uid},FirestoreLoanCloud(uid,db),namespace,{online})
        val first=repository();val original=loan()
        Tasks.await(db.disableNetwork(),10,TimeUnit.SECONDS)
        assertTrue(first.enable());assertTrue(first.save(listOf(original)))
        assertEquals(1,first.state.value.pending);first.close()
        val restored=repository();assertEquals(original,restored.load().single())
        Tasks.await(db.enableNetwork(),10,TimeUnit.SECONDS);online=true;restored.retry()
        val syncDeadline=System.currentTimeMillis()+20_000
        while(restored.state.value.pending>0 && System.currentTimeMillis()<syncDeadline) Thread.sleep(25)
        assertEquals(restored.state.value.message,0,restored.state.value.pending)
        assertTrue(Tasks.await(db.collection("users").document(uid).collection("loans").document(original.id).get(Source.SERVER),10,TimeUnit.SECONDS).exists())
        restored.close()
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }
        context.deleteSharedPreferences("${namespace}_$hash")
    }    @Test fun deletionMarkerIsMinimalIdempotentAndPreventsResurrection()=withEmulators {auth,db->
        val uid=auth.currentUser!!.uid;val cloud=FirestoreLoanCloud(uid,db);val original=loan()
        cloud.apply(CloudMutation(null,original));val deletion=CloudMutation(original,original,true)
        cloud.apply(deletion);cloud.apply(deletion)
        val ref=db.collection("users").document(uid).collection("loans").document(original.id)
        val data=Tasks.await(ref.get(Source.SERVER),10,TimeUnit.SECONDS).data!!
        assertEquals(setOf("schemaVersion","revision","action","deleted"),data.keys);assertEquals(true,data["deleted"])
        assertTrue(runCatching {cloud.apply(CloudMutation(null,original))}.isFailure)
        assertTrue(runCatching {cloud.apply(CloudMutation(original,original.copy(party="Old device")))}.isFailure)
        val observed=java.util.concurrent.CountDownLatch(1);var records=listOf(original)
        val listener=cloud.listen({loans,server->if(server){records=loans;observed.countDown()}},{throw it})
        assertTrue(observed.await(10,TimeUnit.SECONDS));assertTrue(records.isEmpty());listener.close()
    }
    @Test fun deleteCannotOverwriteConcurrentServerPayment()=withEmulators {auth,db->
        val cloud=FirestoreLoanCloud(auth.currentUser!!.uid,db);val original=loan()
        cloud.apply(CloudMutation(null,original));val paid=original.pay(0,1000,"paid",LocalDate.of(2026,1,1))
        cloud.apply(CloudMutation(original,paid))
        val result=runCatching {cloud.apply(CloudMutation(original,original,true))}
        assertTrue(result.isFailure)
        val conflict=generateSequence(result.exceptionOrNull()){it.cause}.filterIsInstance<CloudConflict>().first()
        assertEquals(paid,conflict.current)
    }
    @Test fun realOfflineDeletionRestartsAndOtherDevicesRememberMarker()=withEmulators {auth,db->
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val uid=auth.currentUser!!.uid
        val namespace="delete_e2e_${java.util.UUID.randomUUID()}";var online=true
        val cloud=FirestoreLoanCloud(uid,db);val original=loan();cloud.apply(CloudMutation(null,original))
        fun repo()=CloudLoanRepository(context,uid,{auth.currentUser?.uid},cloud,namespace,{online})
        fun awaitReady(predicate:()->Boolean){val deadline=System.currentTimeMillis()+20000;while(!predicate()&&System.currentTimeMillis()<deadline)Thread.sleep(25);assertTrue(predicate())}
        val first=repo();first.enable();awaitReady {first.receivedServer.value&&first.load().size==1}
        online=false;Tasks.await(db.disableNetwork(),10,TimeUnit.SECONDS)
        assertTrue(first.deleteUnpaid(original));assertTrue(first.load().isEmpty());first.close()
        val restored=repo();assertTrue(restored.load().isEmpty());assertEquals(1,restored.state.value.pending)
        Tasks.await(db.enableNetwork(),10,TimeUnit.SECONDS);online=true;restored.retry();awaitReady {restored.state.value.pending==0}
        assertTrue(restored.load().isEmpty());restored.close()
        val otherNamespace="delete_other_${java.util.UUID.randomUUID()}"
        val other=CloudLoanRepository(context,uid,{auth.currentUser?.uid},cloud,otherNamespace,{true});other.enable();awaitReady {other.receivedServer.value}
        assertTrue(other.load().isEmpty());assertFalse(other.restoreBackup(listOf(original)));other.close()
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString(""){"%02x".format(it)}
        context.deleteSharedPreferences("${namespace}_$hash");context.deleteSharedPreferences("${otherNamespace}_$hash")
    }

}
