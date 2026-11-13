package com.example.casha.data

import com.google.firebase.firestore.FirebaseFirestoreException

/** Give a useful category without displaying account IDs, record contents or raw server messages. */
fun syncFailureMessage(error: Exception): String {
    val causes = generateSequence<Throwable>(error) { it.cause }.toList()
    val detail = causes.mapNotNull { it.message }.joinToString(" ").lowercase()
    if (detail.contains("firestore api") && (detail.contains("disabled") || detail.contains("not been used")))
        return "Firebase setup needed: enable the Cloud Firestore API for Casha, then retry. Queued changes remain on this device."
    if (detail.contains("database") && (detail.contains("does not exist") || detail.contains("not found")))
        return "Firebase setup needed: check that the default Firestore database exists. Queued changes remain on this device."
    return when (causes.filterIsInstance<FirebaseFirestoreException>().firstOrNull()?.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> "Firebase denied access. Check this account's sign-in and apply the tested Casha Firestore rules, then retry. Queued changes are retained."
        FirebaseFirestoreException.Code.UNAUTHENTICATED -> "Sign in again to resume cloud synchronization. Queued changes remain on this device."
        FirebaseFirestoreException.Code.UNAVAILABLE -> "Cloud is unavailable. Changes remain on this device; retry when connected."
        else -> "Changes are saved on this device. Cloud sync failed; check your connection and Firebase setup, then retry."
    }
}
