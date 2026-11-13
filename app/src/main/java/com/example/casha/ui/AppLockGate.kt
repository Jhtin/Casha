package com.example.casha.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.view.WindowManager

@Composable
fun AppLockGate(activity:FragmentActivity,content:@Composable ()->Unit) {
    val prefs=remember { activity.getSharedPreferences("app_privacy",Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("appLock",false)) }
    var unlocked by remember { mutableStateOf(!enabled) }
    var foreground by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    val prompt=remember { BiometricPrompt(activity,ContextCompat.getMainExecutor(activity),object:BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) {unlocked=true;message=""}
        override fun onAuthenticationError(errorCode:Int,errString:CharSequence) {message="Unlock cancelled or unavailable. Use your device's enrolled unlock method and retry."}
    }) }
    SideEffect {
        if(enabled)activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    fun unlock() {prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Casha")
        .setSubtitle("Use your device unlock method")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())}
    DisposableEffect(activity) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_STOP) {foreground=false;if(enabled)unlocked=false}
            if(event==Lifecycle.Event.ON_START)foreground=true
        }
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key -> if(key=="appLock") {enabled=prefs.getBoolean("appLock",false);unlocked=!enabled} }
        activity.lifecycle.addObserver(observer);prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {activity.lifecycle.removeObserver(observer);prefs.unregisterOnSharedPreferenceChangeListener(listener);prompt.cancelAuthentication()}
    }
    LaunchedEffect(enabled,unlocked,foreground) {if(enabled&&!unlocked&&foreground)unlock()}
    if(!enabled||unlocked)content()
    else AppLockScreen(message = message, onUnlock = { unlock() })
}

@Composable
internal fun AppLockScreen(
    message: String = "",
    onUnlock: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        shape = CircleShape,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            PilotIcon("Security", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Casha is locked",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "Authenticate with your device PIN or biometric to view your financial records.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (message.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                message,
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Button(
                        onClick = onUnlock,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        PilotIcon("Security", Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Unlock Casha")
                    }
                }
            }
        }
    }
}
