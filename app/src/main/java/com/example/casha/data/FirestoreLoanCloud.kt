package com.example.casha.data

import com.example.casha.domain.*
import com.google.firebase.firestore.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CloudConflict(val current: Loan?) : Exception("Cloud record changed")
interface LoanCloud {
    fun listen(onRecords: (List<Loan>, Boolean) -> Unit, onError: (Exception) -> Unit): AutoCloseable
    fun listenChanges(onRecords:(List<Loan>,Set<String>,Boolean)->Unit,onError:(Exception)->Unit):AutoCloseable = listen({records,server->onRecords(records,emptySet(),server)},onError)
    suspend fun apply(mutation: CloudMutation): Loan
}

class FirestoreLoanCloud(private val uid: String, private val db: FirebaseFirestore) : LoanCloud {
    private val collection get() = db.collection("users").document(uid).collection("loans")
    override fun listen(onRecords: (List<Loan>, Boolean) -> Unit, onError: (Exception) -> Unit): AutoCloseable = listenChanges({records,_,server->onRecords(records,server)},onError)
    override fun listenChanges(onRecords:(List<Loan>,Set<String>,Boolean)->Unit,onError:(Exception)->Unit):AutoCloseable {
        val registration = collection.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) onError(error)
            else if (snapshot != null) try {
                onRecords(snapshot.documents.filter {it.getBoolean("deleted")!=true}.map { CloudLoanDocument.decode(it.id, it.data!!) }, snapshot.documents.filter {it.getBoolean("deleted")==true}.map {it.id}.toSet(), !snapshot.metadata.isFromCache)
            } catch (e: Exception) { onError(e) }
        }
        return AutoCloseable { registration.remove() }
    }
    override suspend fun apply(mutation: CloudMutation): Loan = suspendCancellableCoroutine { continuation ->
        mutation.kind
        val reference = collection.document(mutation.after.id)
        db.runTransaction { transaction ->
            val document = transaction.get(reference)
            if(document.getBoolean("deleted")==true) {
                if(mutation.kind==MutationKind.DELETE)return@runTransaction mutation.after
                throw CloudConflict(null)
            }
            val current = document.data?.let { CloudLoanDocument.decode(document.id, it) }
            if (current != null && mutation.kind!=MutationKind.DELETE && mutation.alreadyApplied(current)) return@runTransaction current
            if (current != mutation.before) throw CloudConflict(current)
            val revision = (document.getLong("revision") ?: 0L) + 1
            mutation.kind // Validate deletion and ordinary mutations before writing.
            val data=if(mutation.kind==MutationKind.DELETE)mapOf("schemaVersion" to 1L,"revision" to revision,"action" to "DELETE","deleted" to true)
                else CloudLoanDocument.encode(mutation.after, revision, mutation.kind.name)
            transaction.set(reference, data)
            mutation.after
        }.addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    }
}
