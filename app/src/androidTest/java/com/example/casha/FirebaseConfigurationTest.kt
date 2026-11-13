package com.example.casha

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithText
import com.example.casha.data.FirebaseAccount
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class FirebaseConfigurationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun correctProjectInitializesAndGoogleSignInIsConfigured() {
        assertEquals(compose.activity.getString(com.example.casha.R.string.project_id), FirebaseApp.getInstance().options.projectId)
        assertEquals("com.example.casha", compose.activity.packageName)
        compose.waitForIdle()
        if (compose.onAllNodesWithText("Skip tutorial").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Skip tutorial").performClick()
        }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Cloud account"))
        compose.onNodeWithText("Cloud account").assertIsDisplayed()
        val account = FirebaseAccount(compose.activity)
        assertNotNull("A real web OAuth client must be generated from google-services.json", account.webClientId)
        compose.onNodeWithText("Google sign-in setup is incomplete.", substring = true).assertDoesNotExist()
        if (account.auth.currentUser == null) compose.onNodeWithText("Continue with Google").assertIsEnabled()
        else compose.onNodeWithText("Sign out").assertIsEnabled()
    }
}
