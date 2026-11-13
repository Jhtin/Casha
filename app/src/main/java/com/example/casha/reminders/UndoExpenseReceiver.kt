package com.example.casha.reminders

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.casha.data.FinanceStore

class UndoExpenseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val scope = intent.getStringExtra("scope") ?: return
        // A notification can outlive sign-out. Never open an inactive account store.
        if (scope != FinanceStore.scopeFor(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid)) return
        val store = FinanceStore(context, scope)
        val notificationId = intent.getIntExtra("notificationId", 0)
        if (notificationId != 0) {
            context.getSystemService(NotificationManager::class.java)?.cancel(notificationId)
        }

        when (intent.action) {
            "DISMISS_CAPTURED" -> {
                val capturedId = intent.getStringExtra("capturedId") ?: return
                store.dismissCapturedPayment(capturedId)
            }
            "CONFIRM_CAPTURED" -> {
                if (context.getSharedPreferences("app_privacy", Context.MODE_PRIVATE).getBoolean("appLock", false)) return
                val capturedId = intent.getStringExtra("capturedId") ?: return
                val amountFormatted = intent.getStringExtra("amountFormatted") ?: "payment"
                val confirmed = store.confirmCapturedPayment(capturedId)
                if (confirmed) {
                    Toast.makeText(context, "Confirmed $amountFormatted: recorded in ledger.", Toast.LENGTH_SHORT).show()
                }
            }
            "UNDO_EXPENSE" -> {
                if (context.getSharedPreferences("app_privacy", Context.MODE_PRIVATE).getBoolean("appLock", false)) return
                val cashId = intent.getStringExtra("cashId") ?: return
                val amountFormatted = intent.getStringExtra("amountFormatted") ?: "expense"
                val removed = store.deleteCash(cashId)
                if (removed) {
                    Toast.makeText(context, "Undone $amountFormatted: removed from ledger.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
