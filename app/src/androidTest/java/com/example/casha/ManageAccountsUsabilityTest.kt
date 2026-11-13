package com.example.casha

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.FinanceStore
import com.example.casha.domain.MoneyAccount
import com.example.casha.ui.ManageAccountsDialog
import com.example.casha.ui.theme.CashaTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ManageAccountsUsabilityTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "manage_acc_${UUID.randomUUID()}"
    private val finance get() = FinanceStore(context, name)

    @After fun cleanup() {
        context.deleteSharedPreferences("finance_$name")
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

    private fun setupRealisticWallets() {
        val store = finance
        val wallets = listOf(
            MoneyAccount(id = "acc_gotyme", name = "Gotyme", isSavings = false, icon = "Bank"),
            MoneyAccount(id = "acc_gcash", name = "Gcash", isSavings = false, icon = "Wallet"),
            MoneyAccount(id = "acc_maya", name = "Maya", isSavings = false, icon = "Wallet"),
            MoneyAccount(id = "acc_ub", name = "Union bank", isSavings = false, icon = "Bank"),
            MoneyAccount(id = "acc_gcash_dev", name = "Gcash (Device)", isSavings = false, icon = "Wallet"),
            MoneyAccount(id = "acc_ub_dev", name = "Union Bank (Device)", isSavings = false, icon = "Bank"),
            MoneyAccount(id = "acc_gotyme_dev", name = "Gotyme (Device)", isSavings = false, icon = "Bank"),
            MoneyAccount(id = "acc_maya_dev", name = "Maya (Device)", isSavings = false, icon = "Wallet")
        )
        wallets.forEach { store.addMoneyAccount(it) }
        store.setDefaultSalaryAccountId("acc_gotyme")
        store.deleteMoneyAccount("acc_maya_dev") // archives Maya (Device)
    }

    @Test fun manyWalletsKeepsSalaryWalletHorizontalAndActiveSettingsVisibleInDarkTheme() {
        setupRealisticWallets()
        var dismissed = false

        compose.setContent {
            CashaTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.safeDrawingPadding()) {
                        ManageAccountsDialog(
                            finance = finance,
                            onDismiss = { dismissed = true }
                        )
                    }
                }
            }
        }

        // Header & default salary chip row
        compose.onNodeWithText("Accounts & Wallets").assertIsDisplayed()
        compose.onNodeWithText("Default salary wallet").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Gotyme").fetchSemanticsNodes().isNotEmpty())

        // Initial view: single-row scrollable default salary wallet and top active wallets
        picture("manage-accounts-many-wallets-dark.png")

        // Scroll to and verify "+ Add new account" button is reachable
        compose.onNodeWithTag("manage-accounts-column").performScrollToNode(hasText("Add new account"))
        compose.onNodeWithText("Add new account").assertIsDisplayed()

        // Verify archived wallets collapsible card at bottom
        compose.onNodeWithTag("manage-accounts-column").performScrollToNode(hasText("Archived Wallets (1)"))
        compose.onNodeWithText("Archived Wallets (1)").assertIsDisplayed()

        // Expanding archived wallets shows archived items
        compose.onNodeWithText("Archived Wallets (1)").performClick()
        compose.onNodeWithTag("manage-accounts-column").performScrollToNode(hasText("Maya (Device)"))
        compose.onNodeWithText("Maya (Device)").assertIsDisplayed()
        compose.onNodeWithText("Restore").assertIsDisplayed()

        picture("manage-accounts-archived-expanded-dark.png")
    }

    @Test fun manyWalletsWithLargeFontInLightThemeDoesNotOverflow() {
        setupRealisticWallets()
        var dismissed = false

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3.375f, 1.3f)) {
                CashaTheme(darkTheme = false) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.safeDrawingPadding()) {
                            ManageAccountsDialog(
                                finance = finance,
                                onDismiss = { dismissed = true }
                            )
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Accounts & Wallets").assertIsDisplayed()
        compose.onNodeWithText("Default salary wallet").assertIsDisplayed()
        picture("manage-accounts-large-font-light.png")

        compose.onNodeWithTag("manage-accounts-column").performScrollToNode(hasText("Add new account"))
        compose.onNodeWithText("Add new account").assertIsDisplayed()

        compose.onNodeWithTag("manage-accounts-column").performScrollToNode(hasText("Archived Wallets (1)"))
        compose.onNodeWithText("Archived Wallets (1)").assertIsDisplayed()

        // Dismiss via Close button
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
