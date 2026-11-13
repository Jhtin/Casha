package com.example.casha.data

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Identity only: signing in never imports or changes the local ledger. */
class FirebaseAccount(private val context: Context) {
    val auth: FirebaseAuth = FirebaseAuth.getInstance()
    val webClientId: String? get() {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id == 0) null else context.getString(id).takeIf { it.isNotBlank() }
    }

    suspend fun signIn(activity: Activity) {
        val clientId = requireNotNull(webClientId) { "Google sign-in needs an updated Firebase configuration." }
        val option = GetSignInWithGoogleOption.Builder(clientId).build()
        val response = CredentialManager.create(context).getCredential(activity,
            GetCredentialRequest.Builder().addCredentialOption(option).build())
        val credential = response.credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
        suspendCancellableCoroutine<Unit> { continuation ->
            auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(Unit) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        }
    }

    suspend fun signOut() {
        auth.signOut()
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    }
}
