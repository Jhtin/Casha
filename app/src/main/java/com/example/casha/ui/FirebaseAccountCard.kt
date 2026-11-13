package com.example.casha.ui

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.casha.data.FirebaseAccount
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun FirebaseAccountCard(activity: Activity) {
    val account = remember { FirebaseAccount(activity.applicationContext) }
    var user by remember { mutableStateOf(account.auth.currentUser) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(account) {
        val listener = FirebaseAuth.AuthStateListener { user = it.currentUser }
        account.auth.addAuthStateListener(listener)
        onDispose { account.auth.removeAuthStateListener(listener) }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cloud account", style = MaterialTheme.typography.titleMedium)
            Text(user?.let { "Signed in as ${it.email ?: it.displayName ?: "Google user"}" } ?: "Not signed in", style = MaterialTheme.typography.bodyMedium)
            Text("Device-only and account records stay separate. Manage cloud sync in Account synchronization.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (account.webClientId == null) Text("Google sign-in setup is incomplete. Enable Google in Firebase Authentication, then download an updated google-services.json.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (user == null) {
                Button(enabled = !busy && account.webClientId != null, onClick = {
                    busy = true; message = null
                    scope.launch {
                        try { account.signIn(activity) }
                        catch (_: GetCredentialCancellationException) { /* User closed the account chooser. */ }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            message = e.localizedMessage ?: e.message ?: "Sign-in could not finish. Check your connection, Google account and Firebase provider setup, then retry."
                        }
                        finally { busy = false }
                    }
                },shape=MaterialTheme.shapes.medium) { Text(if (busy) "Signing in…" else "Continue with Google") }
            } else {
                OutlinedButton(enabled = !busy, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), onClick = {
                    busy = true; message = null
                    scope.launch {
                        try { account.signOut() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { message = "Signed out of Firebase. The Google account chooser could not be reset." }
                        finally { busy = false }
                    }
                },shape=MaterialTheme.shapes.medium) {
                    PilotIcon("Close", Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sign out")
                }
            }
            AccountDataControls()
        }
    }
}
