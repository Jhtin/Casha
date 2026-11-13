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
import com.example.casha.data.*
import com.example.casha.domain.*
import com.example.casha.ui.AutomationScreen
import com.example.casha.ui.ManageAccountsDialog
import com.example.casha.ui.PaydayScreen
import com.example.casha.ui.theme.CashaTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class UiFeedbackVerificationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = "ui_feedback_${UUID.randomUUID()}"
    private val loanStore by lazy { LoanStore(context, scope) }
    private val finance by lazy { FinanceStore(context, scope) }

    @After fun cleanup() {
        context.deleteSharedPreferences(scope)
        context.deleteSharedPreferences("finance_$scope")
        context.deleteSharedPreferences("gemini_$scope")
        context.deleteSharedPreferences("assistant_chat_$scope")
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

    @Test fun testAssistantNewChatLayoutUncompressed() {
        // Setup AI key, consent, and chat turns
        val keys = GeminiKeyStore(context, scope)
        keys.save("ai_test_key_1234567890")
        finance.setFlag("aiConsent", true)
        finance.setFlag("freeKeyConfirmed", true)

        val chatStore = AssistantChatStore(context, scope)
        chatStore.save(listOf(
            AssistantChatTurn(
                question = "whats my money right now?",
                answer = "You have ₱4,000.00 cash on hand and ₱4,868.00 in Gotyme."
            )
        ))

        val loans: List<Loan> = emptyList()

        compose.setContent {
            CashaTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.safeDrawingPadding()) {
                        AutomationScreen(
                            store = loanStore,
                            loans = loans,
                            finance = finance
                        )
                    }
                }
            }
        }

        // Verify chips and New chat button
        compose.onNodeWithText("Key configured (Free)").assertIsDisplayed()
        compose.onNodeWithText("1 saved").assertIsDisplayed()
        compose.onNodeWithText("English").assertIsDisplayed()
        compose.onNodeWithText("New chat").assertIsDisplayed()
        compose.onNodeWithText("whats my money right now?").assertIsDisplayed()

        picture("feedback-01-assistant-new-chat.png")
    }

    @Test fun testManageAccountsHorizontalBadge() {
        val wallets = listOf(
            MoneyAccount(id = "acc_cash", name = "Cash", isSavings = false, icon = "Wallet"),
            MoneyAccount(id = "acc_savings", name = "Savings", isSavings = true, icon = "Shield"),
            MoneyAccount(id = "acc_gotyme", name = "Gotyme", isSavings = false, icon = "Bank"),
            MoneyAccount(id = "acc_gcash", name = "Gcash", isSavings = false, icon = "Wallet"),
            MoneyAccount(id = "acc_maya", name = "Maya", isSavings = false, icon = "Wallet"),
            MoneyAccount(id = "acc_ub", name = "Union bank", isSavings = false, icon = "Bank")
        )
        wallets.forEach { finance.addMoneyAccount(it) }
        finance.setDefaultSalaryAccountId("acc_gotyme")

        compose.setContent {
            CashaTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.safeDrawingPadding()) {
                        ManageAccountsDialog(
                            finance = finance,
                            onDismiss = {}
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("Accounts & Wallets").assertIsDisplayed()
        compose.onNodeWithText("Default salary wallet").assertIsDisplayed()
        compose.onNodeWithText("Default Salary").assertIsDisplayed()

        picture("feedback-02-accounts-horizontal-badge.png")
    }

    @Test fun testActivityHistoryStatusBadges() {
        val today = LocalDate.now()
        finance.saveProfile(PaydayProfile(today.minusDays(5), today.plusDays(10), 50_000_00, 0))
        val accGotyme = MoneyAccount(id = "acc_gotyme_dev", name = "Gotyme (Device)", isSavings = false, icon = "Bank")
        finance.addMoneyAccount(accGotyme)

        // 1. Reversed cash entry & Reversal (Correction) entry
        val entry1 = CashEntry(id = "entry1", income = true, amount = 5_000_00, date = today.minusDays(1), note = "Income", channel = "Gotyme (Device)")
        finance.addCash(entry1)
        finance.reverseCash(entry1.id)

        val loans: List<Loan> = emptyList()

        compose.setContent {
            CashaTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.safeDrawingPadding()) {
                        PaydayScreen(
                            loans = loans,
                            finance = finance,
                            openLoan = {}
                        )
                    }
                }
            }
        }

        // Expand Activity section
        compose.onNodeWithTag("payday-list").performScrollToNode(hasText("Activity", substring = true))
        compose.onNodeWithText("Activity", substring = true).performClick()

        // Scroll up to bring Activity content into view
        compose.onNodeWithTag("payday-list").performTouchInput { swipeUp() }
        compose.waitForIdle()

        compose.onNodeWithText("Recent cutoff activity").assertIsDisplayed()
        compose.onNodeWithText("Correction").assertIsDisplayed()
        compose.onNodeWithText("Reversed").assertIsDisplayed()

        picture("feedback-03-activity-badges.png")
    }
}
