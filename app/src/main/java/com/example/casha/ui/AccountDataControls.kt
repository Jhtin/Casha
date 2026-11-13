package com.example.casha.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.casha.R

private const val SUPPORT_EMAIL = "jhustinegregorio07@gmail.com"

/** A user-reviewed support request, never a local or cloud deletion command. */
@Composable
internal fun AccountDataControls(openEmail: ((Intent) -> Unit)? = null) {
    val context = LocalContext.current
    var privacy by remember { mutableStateOf(false) }
    var deletion by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column {
        TextButton(onClick = { privacy = true }) { Text("Privacy policy") }
        TextButton(onClick = { error = null; deletion = true }) { Text("Request account deletion") }
    }
    if (privacy) {
        val policy = remember { context.resources.openRawResource(R.raw.privacy_policy).bufferedReader().use { it.readText() } }
        PilotModal(onDismissRequest = { privacy = false }, title = { Text("Casha privacy policy") },
            category = "Your data", icon = "Security",
            text = { Text(policy, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { privacy = false }) { Text("Close privacy policy") } })
    }
    if (deletion) {
        PilotModal(onDismissRequest = { deletion = false }, title = { Text("Request account and cloud data deletion") },
            category = "Your account", icon = "Security",
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Send a request to $SUPPORT_EMAIL from the Google email used for Casha. Include that account email, but no passwords, bank details or financial records. jtin will verify ownership and confirm deletion timing.")
                Text("This opens an email draft for you to review and send. No records are deleted now. An accepted request covers your Casha sign-in account and associated cloud records. Device-only records and exported backups stay under your control. Export anything you want to keep before requesting deletion.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { Button(onClick = {
                val subject = Uri.encode("Casha account and cloud data deletion request")
                val body = Uri.encode("Please delete my Casha sign-in account and associated cloud records.\n\nMy Casha Google account email:\n\nPlease confirm any ownership verification steps and the expected deletion timing.")
                val uri = Uri.parse("mailto:$SUPPORT_EMAIL?subject=$subject&body=$body")
                runCatching {
                    val draft = Intent(Intent.ACTION_SENDTO, uri)
                    if (openEmail != null) openEmail(draft) else context.startActivity(draft)
                }
                    .onSuccess { deletion = false }
                    .onFailure { error = "No email app is available. Email $SUPPORT_EMAIL directly to request deletion." }
            }) { Text("Open email draft") } },
            dismissButton = { TextButton(onClick = { deletion = false }) { Text("Cancel") } })
    }
}
