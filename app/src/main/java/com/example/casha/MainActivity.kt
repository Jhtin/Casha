package com.example.casha

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.mutableStateOf
import android.content.Intent
import com.example.casha.ui.AppLockGate
import com.example.casha.reminders.BackgroundWork
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.casha.ui.AccountLoanApp
import com.example.casha.ui.theme.CashaTheme

class MainActivity : FragmentActivity() {
    private val widgetRequest = mutableStateOf<Pair<String,String>?>(null)
    private val reminderRequest = mutableStateOf<Pair<String, String>?>(null)
    private val capturedRequest = mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntents(intent)
        if(savedInstanceState!=null)widgetRequest.value=savedInstanceState.getString("widgetScope")?.let {scope->savedInstanceState.getString("widgetAction")?.let {scope to it}}
        BackgroundWork.initialize(applicationContext)
        setContent {
            CashaTheme {
                AppLockGate(this@MainActivity) {
                    AccountLoanApp(this@MainActivity, reminderRequest.value, capturedRequest.value,widgetRequest.value) {
                        widgetRequest.value=null

                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntents(intent)
    }

    override fun onSaveInstanceState(outState:Bundle) {
        widgetRequest.value?.let {outState.putString("widgetScope",it.first);outState.putString("widgetAction",it.second)}
        super.onSaveInstanceState(outState)
    }

    private fun readIntents(intent: Intent?) {
        val widgetAction=intent?.getStringExtra("widgetAction")
        val widgetScope=intent?.getStringExtra("widgetScope")
        widgetRequest.value=if(widgetScope!=null && widgetAction in com.example.casha.widget.CashaWidget.actions)widgetScope to widgetAction!! else null
        reminderRequest.value = null
        capturedRequest.value = null
        val id = intent?.getStringExtra("openLoan")
        val scope = intent?.getStringExtra("financeScope")
        if (id != null && scope != null) reminderRequest.value = scope to id

        val capId = intent?.getStringExtra("openCapturedId")
        if (capId != null && scope != null) capturedRequest.value = scope to capId
        // A widget command lives in pending state, not in the Activity's permanent launch intent.
        // Normalize immediately, before resume, and preserve pending commands in savedInstanceState.
        if(widgetRequest.value!=null)setIntent(Intent(this,MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
    }
}
