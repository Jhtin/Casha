package com.example.casha

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CashaBrandingTest {
    @Test fun installedBrandUsesNewNameAndKeepsUpgradeIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.casha", context.packageName)
        assertEquals("Casha", context.applicationInfo.loadLabel(context.packageManager).toString())
        assertTrue(context.applicationContext is CashaApplication)
        assertNotNull(context.getDrawable(R.drawable.casha_logo))
        assertNotNull(context.getDrawable(R.drawable.ic_launcher_foreground))
        val policy = context.resources.openRawResource(R.raw.privacy_policy).bufferedReader().use { it.readText() }
        assertTrue(policy.startsWith("Casha privacy policy"))
        assertFalse(policy.contains("LoanPilot"))
        assertFalse(policy.contains("Pip"))
    }
}
