package com.example.casha

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.FinanceStore
import com.example.casha.data.LoanStore
import com.example.casha.domain.*
import com.example.casha.ui.CashaApp
import com.example.casha.ui.theme.CashaTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class UiComprehensiveAuditTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = "ui_audit_${UUID.randomUUID()}"
    private val loanStore by lazy { LoanStore(context, scope) }
    private val finance by lazy { FinanceStore(context, scope) }

    @After fun cleanup() {
        context.deleteSharedPreferences(scope)
        context.deleteSharedPreferences("finance_$scope")
    }

    private fun picture(name: String) {
        val folder = File(context.filesDir, "phase_previews").apply { mkdirs() }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(150)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { b ->
            File(folder, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun verifyFullUserJourneysAndCapturePreviews() {
        // 1. Fresh install state (No loans recorded)
        compose.setContent {
            CashaTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.safeDrawingPadding()) {
                        CashaApp(loanStore, scope)
                    }
                }
            }
        }

        compose.waitUntil(5000) { compose.onAllNodesWithText("No loans recorded").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("No loans recorded").assertIsDisplayed()
        compose.onNodeWithText("Add your first loan").assertIsDisplayed()
        picture("ui-audit-01-loans-empty.png")

        // 2. Open Add Loan Dialog
        compose.onNodeWithText("Add your first loan").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Person or lender").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Person or lender").assertIsDisplayed()
        compose.onNodeWithText("Total payable (PHP)").assertIsDisplayed()
        picture("ui-audit-02-add-loan-dialog.png")

        // Fill and save loan
        compose.onNodeWithText("Person or lender").performTextInput("Home Credit")
        compose.onNodeWithText("Total payable (PHP)").performTextInput("3000.00")
        compose.onNodeWithText("Installments (1–120)").performTextReplacement("3")
        compose.onNodeWithTag("loan-form").performScrollToNode(hasText("First due: YYYY-MM-DD"))
        compose.onNodeWithText("First due: YYYY-MM-DD").performTextReplacement(LocalDate.now().plusDays(7).toString())
        compose.onNodeWithText("Save loan").performClick()

        // 3. Active Loan Details
        compose.waitUntil(5000) { compose.onAllNodesWithText("Home Credit").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Home Credit").onFirst().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Record payment").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Record payment").assertIsDisplayed()
        picture("ui-audit-03-loan-details.png")

        // 4. Payment Entry & Confirmation Review
        // Add a secondary wallet so wallet chips are visible
        finance.addMoneyAccount(MoneyAccount(id = "acc_gotyme", name = "Gotyme", isSavings = false, icon = "Bank"))
        finance.addMoneyAccount(MoneyAccount(id = "acc_gcash", name = "GCash", isSavings = false, icon = "Wallet"))

        compose.onNodeWithText("Record payment").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Payment (PHP)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Payment (PHP)").assertIsDisplayed()
        picture("ui-audit-04-payment-entry.png")

        compose.onNodeWithText("Payment (PHP)").performTextInput("1000.00")
        compose.onNodeWithText("Review payment").performClick()

        // Verify explicit financial confirmation review
        compose.waitUntil(5000) { compose.onAllNodesWithText("Save payment").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Check the amount and date, then save to your records.").assertIsDisplayed()
        picture("ui-audit-05-payment-review-confirm.png")

        compose.onNodeWithText("Save payment").performClick()
        compose.waitUntil(5000) { loanStore.load().single().payments.isNotEmpty() }
        compose.onNodeWithText("Close").performClick()

        // 5. Payday Strategist Screen
        val today = LocalDate.now()
        finance.saveProfile(PaydayProfile(today, today.plusDays(15), 30_000_00, 10_000_00, 5_000_00, salaryAccountId = "acc_gotyme"))
        compose.onNodeWithText("Payday").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Payday overview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Payday overview").assertIsDisplayed()
        compose.onNodeWithText("Safe to spend").assertIsDisplayed()
        picture("ui-audit-06-payday-overview.png")

        // 6. Expense Quick Log
        compose.onNodeWithText("Expense").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Amount (PHP)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Amount (PHP)").assertIsDisplayed()
        picture("ui-audit-07-quick-expense.png")
        compose.onNodeWithText("Cancel").performClick()

        // 7. Settings Screen
        compose.onNodeWithText("Settings").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Appearance").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Accounts & Wallets"))
        compose.onNodeWithText("Accounts & Wallets").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Manage accounts & wallets"))
        compose.onNodeWithText("Manage accounts & wallets").assertIsDisplayed()
        picture("ui-audit-08-settings-screen.png")
    }

    @Test fun appLockScreenRendersSecurityBadgeAndAction() {
        var unlocked = false
        compose.setContent {
            CashaTheme(darkTheme = true) {
                com.example.casha.ui.AppLockScreen(
                    message = "Unlock cancelled or unavailable. Use your device's enrolled unlock method and retry.",
                    onUnlock = { unlocked = true }
                )
            }
        }
        compose.onNodeWithText("Casha is locked").assertIsDisplayed()
        compose.onNodeWithText("Authenticate with your device PIN or biometric to view your financial records.").assertIsDisplayed()
        compose.onNodeWithText("Unlock cancelled or unavailable. Use your device's enrolled unlock method and retry.").assertIsDisplayed()
        compose.onNodeWithText("Unlock Casha").assertIsDisplayed()
        picture("ui-audit-09-app-lock-gate.png")

        compose.onNodeWithText("Unlock Casha").performClick()
        compose.runOnIdle { assertTrue(unlocked) }
    }
}
