package com.example.casha

import com.example.casha.reminders.PaymentNotificationParser
import org.junit.Assert.*
import org.junit.Test

class PaymentNotificationParserTest {

    @Test fun completedPaymentSmsIsReviewableWithoutOpeningOtherChatApps() {
        val text = "Your payment of P192.21 to PINDUODUO has been successfully processed on 11-11-26 04:13:59 AM. Ref. No. 053992039"
        for (pkg in listOf("com.google.android.apps.messaging", "com.transsion.message", "com.android.mms")) {
            val parsed = PaymentNotificationParser.parse(pkg, "GCash", text, availableAccountNames = listOf("Cash", "Gcash"))
            assertEquals(19221L, parsed?.amountCentavos)
            assertEquals("PINDUODUO", parsed?.merchant)
            assertEquals("Gcash", parsed?.suggestedAccountName)
        }
        assertNull(PaymentNotificationParser.parse("com.whatsapp", "GCash", text))
        assertNull(PaymentNotificationParser.parse("com.google.android.apps.messaging.fake", "GCash", text))
    }

    @Test fun smsRejectsCasualPaymentsOtpFailuresAndMissingReference() {
        val prefix = "Your payment of P192.21 to PINDUODUO has been successfully processed"
        for (text in listOf("I paid P192.21 to a friend", "$prefix. OTP 123456. Ref. No. 053992039",
            "$prefix. Ref. No. 053992039. Reversed", prefix,
            "Your payment of P192.21 to PINDUODUO failed. Ref. No. 053992039")) {
            assertNull(text, PaymentNotificationParser.parse("com.google.android.apps.messaging", "GCash", text))
        }
    }

    private val userAccounts = listOf("Cash", "Gotyme", "Gcash", "Maya", "Savings", "Union bank")

    @Test
    fun parsesGotymeTransferPayment() {
        val parsed = PaymentNotificationParser.parse(
            packageName = "ph.com.gotyme",
            title = "Transfer successful",
            text = "You sent P52.00 to DAN NICO D.",
            availableAccountNames = userAccounts
        )
        assertNotNull(parsed)
        assertEquals(5200L, parsed?.amountCentavos)
        assertEquals("DAN NICO D", parsed?.merchant)
        assertEquals("Gotyme", parsed?.suggestedAccountName)
    }

    @Test
    fun ignoresViberPromotionalMessage() {
        val parsed = PaymentNotificationParser.parse(
            packageName = "com.viber.voip",
            title = "Repay less on Atome Cash!",
            text = "Get up to 30% off your Atome Cash interest payments. 1. Head to Atome Cash and tap Request Loan 2. Enter your loan amount (min. ₱3,000) and repayment plan... Note: Up to ₱150 off. Apply now!",
            availableAccountNames = userAccounts
        )
        assertNull(parsed)
    }

    @Test
    fun ignoresBankingAppPromotionalMessage() {
        val parsed = PaymentNotificationParser.parse(
            packageName = "com.globe.gcash.android",
            title = "Exclusive Offer!",
            text = "Get up to 30% discount on your next loan payment with promo code SAVE30. Apply now!",
            availableAccountNames = userAccounts
        )
        assertNull(parsed)
    }

    @Test
    fun ignoresChatAndSocialApps() {
        val apps = listOf(
            "com.viber.voip",
            "com.whatsapp",
            "org.telegram.messenger",
            "com.facebook.orca",
            "com.google.android.apps.messaging",
            "com.android.chrome"
        )
        for (pkg in apps) {
            val parsed = PaymentNotificationParser.parse(
                packageName = pkg,
                title = "Payment",
                text = "Paid ₱150 to friend",
                availableAccountNames = userAccounts
            )
            assertNull("Should ignore $pkg", parsed)
        }
    }

    @Test
    fun parsesGoogleWalletNfcPayment() {
        val parsed = PaymentNotificationParser.parse(
            packageName = "com.google.android.apps.walletnfcrel",
            title = "Google Wallet",
            text = "Paid ₱150.00 at Starbucks with Visa •••• 1234",
            availableAccountNames = userAccounts
        )
        assertNotNull(parsed)
        assertEquals(15000L, parsed?.amountCentavos)
        assertEquals("Starbucks", parsed?.merchant)
    }

    @Test
    fun parsesGotymePosPayment() {
        val parsed = PaymentNotificationParser.parse(
            packageName = "ph.com.gotyme",
            title = "GoTyme Bank",
            text = "POS transaction of PHP 450.00 approved at Robinsons",
            availableAccountNames = userAccounts
        )
        assertNotNull(parsed)
        assertEquals(45000L, parsed?.amountCentavos)
        assertEquals("Robinsons", parsed?.merchant)
        assertEquals("Gotyme", parsed?.suggestedAccountName)
    }

    @Test
    fun parsesGcashGoodsPayment() {
        val parsed = PaymentNotificationParser.parse(
            packageName = "com.globe.gcash.android",
            title = "GCash",
            text = "You have paid PHP 150.00 of Goods to Jollibee",
            availableAccountNames = userAccounts
        )
        assertNotNull(parsed)
        assertEquals(15000L, parsed?.amountCentavos)
        assertEquals("Jollibee", parsed?.merchant)
        assertEquals("Gcash", parsed?.suggestedAccountName)
    }

    @Test
    fun parsesMayaPayment() {
        val parsed = PaymentNotificationParser.parse(
            packageName = "com.paymaya",
            title = "Maya",
            text = "You paid ₱320.00 to 7-Eleven",
            availableAccountNames = userAccounts
        )
        assertNotNull(parsed)
        assertEquals(32000L, parsed?.amountCentavos)
        assertEquals("7-Eleven", parsed?.merchant)
        assertEquals("Maya", parsed?.suggestedAccountName)
    }

    @Test
    fun ignoresNonPaymentNotification() {
        val parsed = PaymentNotificationParser.parse(
            packageName = "com.facebook.katana",
            title = "Facebook",
            text = "Someone liked your photo",
            availableAccountNames = userAccounts
        )
        assertNull(parsed)
    }

    @Test
    fun ignoresPaymentAppNotificationWithoutAmount() {
        val parsed = PaymentNotificationParser.parse(
            packageName = "com.google.android.apps.walletnfcrel",
            title = "Google Wallet",
            text = "Your card is ready to use for tap to pay",
            availableAccountNames = userAccounts
        )
        assertNull(parsed)
    }

    @Test fun withdrawalKeepsAllDigitsAndSeparatesFee() {
        val parsed = PaymentNotificationParser.parse("ph.com.gotyme", "Money withdrawn",
            "P4818.00 withdrawn from your account at ST. MARTIN TCDC-GUIGUI on 11-04-2026 18:38:34. A P18.00 fee was charged for this transaction.", availableAccountNames = userAccounts)!!
        assertEquals(481800L, parsed.amountCentavos)
        assertEquals(1800L, parsed.feeCentavos)
        assertTrue(parsed.withdrawal)
        assertEquals("Gotyme", parsed.suggestedAccountName)
    }
    @Test fun preservesGroupedAndUngroupedAmountsWithoutPartialMatches() {
        for (amount in listOf("4818.00", "4,818.00", "4800", "12345.67")) {
            val parsed = PaymentNotificationParser.parse("ph.com.gotyme", "Payment successful", "Paid PHP $amount at Shop")!!
            assertEquals(amount.replace(",", "").toBigDecimal().movePointRight(2).longValueExact(), parsed.amountCentavos)
            assertFalse(parsed.withdrawal)
        }
    }
}
