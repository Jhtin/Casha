package com.example.casha.reminders

import java.util.regex.Pattern

data class ParsedPaymentNotification(
    val amountCentavos: Long,
    val merchant: String,
    val sourcePackage: String,
    val suggestedAccountName: String,
    val withdrawal: Boolean = false,
    val feeCentavos: Long = 0L
)

object PaymentNotificationParser {
    // Packages that must NEVER be treated as payment or banking apps (messaging, social, email, browsers)
    private val BLACKLISTED_PACKAGES = setOf(
        "com.viber.voip",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "com.facebook.orca",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.instagram.android",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.samsung.android.messaging",
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.discord",
        "com.slack",
        "com.zhiliaoapp.musically",
        "com.twitter.android",
        "com.x.android"
    )

    // Known payment & banking app package prefixes and their default mapped accounts
    private val KNOWN_PACKAGES = mapOf(
        // Google Wallet / NFC Contactless
        "com.google.android.apps.walletnfcrel" to "Cash",
        "com.google.android.gms" to "Cash",

        // GoTyme Bank PH (Google Play id: ph.com.gotyme)
        "ph.com.gotyme" to "Gotyme",
        "com.gotyme.bank" to "Gotyme",
        "com.tyme.bank" to "Gotyme",

        // GCash & Maya
        "com.globe.gcash.android" to "Gcash",
        "com.paymaya" to "Maya",
        "ph.maya.bank" to "Maya",

        // Philippine Banks
        "ph.bpi.digital" to "Bank",
        "com.bpi" to "Bank",
        "com.bdo.mobile" to "Bank",
        "com.bdo.pay" to "Bank",
        "com.bdo.digital" to "Bank",
        "com.unionbankph.online" to "Union bank",
        "com.metrobank.mbonline" to "Bank",
        "com.mplus.rcbc" to "Bank",
        "ph.seabank.app" to "Bank",
        "com.securitybank.betterbanking" to "Bank",
        "com.landbank.mbs" to "Bank",
        "com.cimb.cimbph" to "Bank",
        "ph.com.eastwest.komo" to "Bank",
        "com.eastwest.mobile" to "Bank",
        "com.pnb.mobilebanking" to "Bank",

        // E-Wallets & Transport / Delivery
        "com.grabtaxi.passenger" to "Cash",
        "com.shopee.ph" to "Cash",
        "com.transferwise.android" to "Bank",
        "com.paypal.android.p2pmobile" to "Cash"
    )

    // Marketing, promotion, discount, and loan offer phrases that indicate ads rather than real completed transactions
    private val MARKETING_KEYWORDS = listOf(
        "get up to", "up to ₱", "up to p", "up to 10%", "up to 20%", "up to 30%", "up to 40%", "up to 50%",
        "off your", "discount", "voucher", "coupon", "promo", "promotion", "special offer",
        "exclusive offer", "apply now", "apply for", "repay less", "repayment plan", "interest discount",
        "cash loan", "earn interest", "pre-approved", "congratulations", "valid until", "valid period",
        "min. ₱", "min. p", "min. loan", "minimum loan", "loan amount (min", "request loan", "borrow now"
    )

    // Keywords signaling an actual outgoing payment, transfer, or debit event
    private val PAYMENT_KEYWORDS = listOf(
        "transfer successful", "you sent", "sent", "transferred", "send money",
        "you paid", "paid", "payment successful", "payment sent", "payment of", "payment to",
        "spent", "purchased", "purchase at", "purchase of", "tap to pay", "contactless",
        "debit", "debited", "deducted", "of goods to", "express send", "withdrawn", "withdrawal",
        "pos", "approved", "naibayad", "nagbayad"
    )

    // Regex to match Philippine Peso amounts: ₱52.00, PHP 500, Php 120.50, P52.00, P 52.00
    private val AMOUNT_PATTERN = Pattern.compile(
        """(?:₱|PHP|Php|Php\.|PHP\.|\bP)\s*((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\.[0-9]{1,2})?(?![0-9.,]))""",
        Pattern.CASE_INSENSITIVE
    )

    private val REVERSE_AMOUNT_PATTERN = Pattern.compile(
        """((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\.[0-9]{1,2})?(?![0-9.,]))\s*(?:₱|PHP|Php|pesos)""",
        Pattern.CASE_INSENSITIVE
    )

    // Regex to extract merchant / recipient after words like "to", "at", "for", "of goods to"
    private val MERCHANT_PATTERN = Pattern.compile(
        """(?:\bat|\bto|\bfor|\bof Goods to|\bmerchant:?)\s+([A-Za-z0-9&'’\s\-\.]+?)(?:\s+(?:with|via|using|from|on)|[\.,\s]*$)""",
        Pattern.CASE_INSENSITIVE
    )

    private val SMS_PACKAGES = setOf("com.google.android.apps.messaging", "com.android.mms",
        "com.samsung.android.messaging", "com.transsion.message", "com.transsion.mms")
    fun isSmsApp(packageName: String) = packageName in SMS_PACKAGES
    private val COMPLETED_SMS = Regex("(?i)\\byour payment of\\s+(?:PHP\\.?|₱|P)\\s*[0-9,.]+\\s+to\\s+(.+?)\\s+has been successfully processed\\b")

    fun isBlacklisted(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return BLACKLISTED_PACKAGES.any { lower.startsWith(it) }
    }

    fun isPaymentApp(packageName: String): Boolean {
        if (isBlacklisted(packageName)) return false
        if (KNOWN_PACKAGES.keys.any { packageName.startsWith(it) }) return true
        val lower = packageName.lowercase()
        return lower.contains("wallet") || lower.contains("pay") || lower.contains("bank")
    }

    fun parse(
        packageName: String,
        title: String?,
        text: String?,
        bigText: String? = null,
        availableAccountNames: List<String> = emptyList()
    ): ParsedPaymentNotification? {
        // SMS apps have a separate strict completed-payment path; other chat apps remain blocked.
        val sms = isSmsApp(packageName)
        if (isBlacklisted(packageName) && !sms) return null

        val combined = "${title.orEmpty()} ${text.orEmpty()} ${bigText.orEmpty()}".trim()
        if (combined.isBlank()) return null

        // Must be a recognized payment or banking app
        if (sms) {
            if (!COMPLETED_SMS.containsMatchIn(combined) ||
                !Regex("(?i)ref\\.?\\s*(?:no\\.?)?\\s*[:#]?\\s*[0-9]{6,}").containsMatchIn(combined) ||
                Regex("(?i)\\b(OTP|one.time password|verification code|failed|declined|reversed|refunded)\\b").containsMatchIn(combined)) return null
        } else if (!isPaymentApp(packageName)) return null

        // Immediately reject marketing promotions, discount vouchers, or loan ads
        if (MARKETING_KEYWORDS.any { combined.contains(it, ignoreCase = true) }) return null

        // Must contain an actual outgoing payment or transfer completion keyword
        val hasPaymentKeyword = PAYMENT_KEYWORDS.any { combined.contains(it, ignoreCase = true) }
        if (!hasPaymentKeyword) return null

        // Extract amount
        var matcher = AMOUNT_PATTERN.matcher(combined)
        var amountString: String? = null
        if (matcher.find()) {
            amountString = matcher.group(1)
        } else {
            matcher = REVERSE_AMOUNT_PATTERN.matcher(combined)
            if (matcher.find()) {
                amountString = matcher.group(1)
            }
        }

        if (amountString == null) return null

        val cleanAmount = amountString.replace(",", "")
        val centavos = runCatching { cleanAmount.toBigDecimal().movePointRight(2).longValueExact() }.getOrNull() ?: return null
        if (centavos > 50_000_000L) return null
        if (centavos <= 0L) return null

        // Extract merchant / recipient
        var merchant = ""
        val merchantMatcher = MERCHANT_PATTERN.matcher(combined)
        if (merchantMatcher.find()) {
            merchant = merchantMatcher.group(1)?.trim().orEmpty()
        }

        if (sms) merchant = COMPLETED_SMS.find(combined)?.groupValues?.get(1).orEmpty()

        merchant = merchant.trimEnd('.', ',').trim()

        if (merchant.isBlank()) {
            val cleanTitle = title.orEmpty().trim()
            if (cleanTitle.isNotBlank() && !isGenericAppName(cleanTitle)) {
                merchant = cleanTitle
            } else {
                merchant = KNOWN_PACKAGES[packageName] ?: "Contactless Payment"
            }
        }

        merchant = merchant.take(40).trim()

        val matchedAccount = resolveAccount(packageName, combined, availableAccountNames)

        return ParsedPaymentNotification(
            amountCentavos = centavos,
            merchant = merchant,
            sourcePackage = packageName,
            suggestedAccountName = matchedAccount,
            withdrawal = Regex("(?i)\\b(withdrawn|withdrawal)\\b").containsMatchIn(combined),
            feeCentavos = feeCentavos(combined)
        )
    }

    fun feeCentavos(text: String): Long {
        val fee = Regex("(?i)(?:₱|PHP\\.?|\\bP)\\s*((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?)(?![0-9.,])\\s+fee").find(text) ?: return 0L
        return runCatching { fee.groupValues[1].replace(",", "").toBigDecimal().movePointRight(2).longValueExact() }.getOrDefault(0L)
    }

    private fun isGenericAppName(title: String): Boolean {
        val lower = title.lowercase()
        return lower in listOf(
            "google wallet", "wallet", "gotyme", "gotyme bank", "gotyme ph",
            "gcash", "maya", "bdo", "bpi", "transfer successful", "payment successful"
        )
    }

    private fun resolveAccount(
        packageName: String,
        text: String,
        availableAccountNames: List<String>
    ): String {
        // 1. Check known mapped package name first if user has matching account (e.g. ph.com.gotyme -> Gotyme)
        KNOWN_PACKAGES[packageName]?.let { pkgName ->
            availableAccountNames.firstOrNull { it.equals(pkgName, ignoreCase = true) }?.let { return it }
        }

        // 2. Sort accounts by length descending so "Gcash" matches before "Cash"
        val sortedAccounts = availableAccountNames.filter { it.isNotBlank() }.sortedByDescending { it.length }
        sortedAccounts.firstOrNull { acc ->
            val pattern = Pattern.compile("""\b${Pattern.quote(acc)}\b""", Pattern.CASE_INSENSITIVE)
            pattern.matcher(text).find()
        }?.let { return it }

        // 3. Fallback to first non-savings account or "Cash"
        return availableAccountNames.firstOrNull { !it.equals("Savings", ignoreCase = true) } ?: "Cash"
    }
}
