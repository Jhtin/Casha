package com.example.casha.data

import com.example.casha.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** A portable snapshot contains financial records, never device/session credentials or queues. */
object PortableBackup {
    fun encode(store: LoanRepository, finance: FinanceStore): JSONObject {
        check(store !is CloudLoanRepository || store.reconciliationStore.loadUnresolvedRecords().isEmpty()) {
            "Resolve disputed payments before exporting a portable backup."
        }
        val data = JSONObject().put("application", "Casha").put("assistant", "Petot").put("schemaVersion", 2).put("scope", finance.scope)
            .put("exportedAt", java.time.Instant.now().toString())
            .put("loans", JSONArray(LoanCodec.encode(store.load()))).put("finance", finance.exportLocal())
        validate(data, finance)
        return data
    }

    fun validate(data: JSONObject, finance: FinanceStore): List<Loan> {
        require(data.getInt("schemaVersion") in 1..2) { "Unsupported backup version." }
        require(data.getString("scope").isNotBlank())
        // Old exports did not promise portability. Preserve their account restriction.
        if (data.getInt("schemaVersion") == 1) require(data.getString("scope") == finance.scope)
        val loans = LoanCodec.decode(data.getJSONArray("loans").toString())
        require(loans.map { it.id }.distinct().size == loans.size)
        loans.forEach { CloudLoanDocument.encode(it, 1, "CREATE") }
        val metadata = finance.validatedBackupMetadata(data.getJSONObject("finance"))
        val accountsRaw = metadata["money_accounts"] as? String
        val accounts = if (accountsRaw == null) MoneyAccount.defaultAccounts() else JSONArray(accountsRaw).let { a ->
            (0 until a.length()).map { a.getJSONObject(it).let { o -> MoneyAccount(o.getString("id"), o.getString("name")) } }
        }
        val accountIds = accounts.map { it.id }.toSet()
        (metadata["default_salary_account"] as? String)?.let { require(it in accountIds) }
        (metadata["profile"] as? String)?.let { raw ->
            JSONObject(raw).optString("salaryAccountId").takeIf { it.isNotBlank() }?.let { require(it in accountIds) }
        }
        metadata.filterKeys { it.startsWith("payment_account_") }.forEach { (key, raw) ->
            val link = JSONObject(raw as String)
            val loan = loans.singleOrNull { it.id == link.getString("loanId") }
                ?: error("A payment wallet link has no loan. Resolve pending or disputed payments before export.")
            val payment = loan.payments.singleOrNull { "payment_account_${it.id}" == key }
                ?: error("A payment wallet link has no matching payment.")
            require(link.getString("accountId") in accountIds)
            require(link.getString("accountName") == accounts.single { it.id == link.getString("accountId") }.name)
            require(link.getBoolean("isReceivable") == (loan.mode == LoanMode.OWED_TO_ME))
            if (link.has("amountCentavos")) require(link.getLong("amountCentavos") in listOf(0L, payment.amount))
        }
        return loans
    }

    fun financeIsEmpty(finance: FinanceStore): Boolean =
        !finance.preferences.contains("profile") && finance.cashEntries().isEmpty() &&
            finance.allPaymentLinks().isEmpty() && finance.pendingPaymentIntents().isEmpty() &&
            finance.capturedPayments().isEmpty() && finance.paydayHistory().isEmpty() &&
            !finance.preferences.contains("money_accounts")
}
