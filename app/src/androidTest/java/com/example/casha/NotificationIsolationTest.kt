package com.example.casha

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.CapturedPayment
import com.example.casha.data.FinanceStore
import com.example.casha.domain.MoneyAccount
import com.example.casha.reminders.UndoExpenseReceiver
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class NotificationIsolationTest {
    @Test fun staleActionsCannotChangeInactiveLedger() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "test_inactive_${UUID.randomUUID()}"
        val store = FinanceStore(context, scope)
        try {
            store.addCapturedPayment(CapturedPayment("pending", 5200, "Synthetic merchant", "test",
                MoneyAccount.DEFAULT_CASH, LocalDate.now()))
            val receiver = UndoExpenseReceiver()
            listOf("CONFIRM_CAPTURED", "DISMISS_CAPTURED", "UNDO_EXPENSE").forEach { action ->
                receiver.onReceive(context, Intent(context, UndoExpenseReceiver::class.java)
                    .setAction(action).putExtra("scope", scope).putExtra("capturedId", "pending").putExtra("cashId", "pending"))
            }
            assertEquals(1, store.capturedPayments().size)
            assertTrue(store.cashEntries().isEmpty())
        } finally { context.deleteSharedPreferences("finance_$scope") }
    }
}
