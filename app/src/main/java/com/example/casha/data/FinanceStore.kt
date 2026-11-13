package com.example.casha.data

import android.content.Context
import com.example.casha.domain.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

data class PaymentSuggestion(val id:String,val amount:Long?,val date:LocalDate?,val reference:String?,val source:String,val receipt:String?=null,val note:String="Review before saving")

data class CapturedPayment(
    val id: String,
    val amountCentavos: Long,
    val merchant: String,
    val sourcePackage: String,
    val channel: String,
    val date: LocalDate,
    val rawText: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)

data class PendingPaymentIntent(
    val intentId: String,
    val paymentId: String,
    val loanId: String,
    val installmentIndex: Int,
    val accountId: String,
    val accountName: String,
    val amountCentavos: Long,
    val date: LocalDate,
    val isReceivable: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class CancelIntentResult {
    CANCELLED,
    PROMOTED,
    BLOCKED_UNREADY,
    BLOCKED_CONFLICT,
    BLOCKED_EMPTY,
    NOT_FOUND;
    val isCancelled get() = this == CANCELLED
}

class FinanceStore(
    val context: Context? = null,
    val scope: String,
    val preferences: android.content.SharedPreferences = context!!.getSharedPreferences("finance_$scope", Context.MODE_PRIVATE)
) {
    val changes=MutableStateFlow(0L)
    fun notifyChanges() { changes.value++ }
    private val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,_->changes.value++ }
    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
        recoverFromRestoreCheckpointIfNeeded()
        runCatching { MigrationJournalStore(context).recoverIfInterrupted(this) }
        migrateSchemaIfNeeded()
    }
    fun storeRevision(): Long = preferences.getLong("_store_revision", 0L)

    internal fun commit(expectedRevision: Long? = null, block:(android.content.SharedPreferences.Editor)->Unit):Boolean {
        synchronized(lockForScope(scope)) {
            val currentRev = preferences.getLong("_store_revision", 0L)
            if (expectedRevision != null && currentRev != expectedRevision) {
                return false
            }
            val editor=preferences.edit()
            block(editor)
            editor.putLong("_store_revision", currentRev + 1)
            val ok=editor.commit()
            if(ok) changes.value++
            return ok
        }
    }
    fun migrateSchemaIfNeeded(): Boolean {
        val version = preferences.getInt("schemaVersion", 1)
        if (version >= 2) return true
        return commit { editor ->
            val rawProfile = preferences.getString("profile", null)
            if (rawProfile != null) {
                runCatching {
                    val p = JSONObject(rawProfile)
                    val salary = p.exactLong("salary")
                    val startDate = LocalDate.parse(p.getString("start"))
                    val savedAccId = p.optString("salaryAccountId").takeIf { it.isNotBlank() } ?: defaultSalaryAccountId()
                    val targetAccount = moneyAccounts().firstOrNull { it.id == savedAccId } ?: moneyAccounts().first()
                    val salaryEntryId = "salary_cutoff_${startDate}_${targetAccount.id}"

                    val existingEntries = cashEntries()
                    if (salary > 0L && existingEntries.none { it.id == salaryEntryId || it.id.startsWith("salary_") }) {
                        val salaryEntry = CashEntry(
                            id = salaryEntryId,
                            income = true,
                            amount = salary,
                            date = startDate,
                            note = "Salary · Initial cutoff",
                            channel = targetAccount.name
                        )
                        val a = JSONArray()
                        (existingEntries + salaryEntry).forEach { e ->
                            a.put(JSONObject().put("id", e.id).put("income", e.income).put("amount", e.amount).put("date", e.date.toString()).put("note", e.note).put("channel", e.channel))
                        }
                        editor.putString("cash", a.toString())
                    }
                }
            }
            editor.putInt("schemaVersion", 2)
        }
    }
    fun profile():PaydayProfile {
        val today=LocalDate.now();val defaultSalaryId=defaultSalaryAccountId()
        val raw=preferences.getString("profile",null) ?: return PaydayProfile(today,PaydayPlanner.nextPayday(today,Frequency.SEMIMONTHLY),0,0,salaryAccountId=defaultSalaryId)
        val p=JSONObject(raw)
        val savedAccId=p.optString("salaryAccountId").takeIf { it.isNotBlank() } ?: defaultSalaryId
        val savedCarryover=p.optLong("carryover", 0L)
        return PaydayProfile(LocalDate.parse(p.getString("start")),LocalDate.parse(p.getString("next")),p.exactLong("salary"),p.exactLong("essentials"),p.exactLong("emergency"),DebtStrategy.valueOf(p.getString("strategy")),savedAccId,savedCarryover)
    }
    fun saveProfile(p:PaydayProfile)=commit {
        val obj=JSONObject().put("start",p.start.toString()).put("next",p.next.toString()).put("salary",p.salary).put("essentials",p.essentials).put("emergency",p.emergency).put("strategy",p.strategy.name).put("carryover",p.carryover)
        if(p.salaryAccountId!=null) obj.put("salaryAccountId",p.salaryAccountId)
        it.putString("profile",obj.toString())
    }

    /**
     * Synchronizes a budget profile update with optional explicit recorded salary adjustment.
     *
     * If adjustRecordedDeposit is false, only PaydayProfile is updated (budget allowance changes,
     * but actual recorded deposits and transaction history remain untouched).
     *
     * If adjustRecordedDeposit is true, and the planned salary amount changed, an explicit,
     * auditable compensating CashEntry is added for the difference without overwriting history.
     *
     * Target wallet changes are saved for future routing and never fabricate inter-wallet transfers.
     */
    @Synchronized fun updatePaydayProfile(
        newProfile: PaydayProfile,
        adjustRecordedDeposit: Boolean = false
    ): Boolean {
        val currentProfile = runCatching { profile() }.getOrNull()
        val oldSalary = currentProfile?.salary ?: 0L
        val newSalary = newProfile.salary

        val profileSaved = saveProfile(newProfile)
        if (!profileSaved) return false

        if (adjustRecordedDeposit && oldSalary != newSalary && newSalary > 0L) {
            val diff = newSalary - oldSalary
            val targetId = newProfile.salaryAccountId ?: defaultSalaryAccountId()
            val targetAccount = allMoneyAccounts().firstOrNull { it.id == targetId } ?: allMoneyAccounts().first()
            val oldFmt = java.math.BigDecimal.valueOf(oldSalary, 2).toPlainString()
            val newFmt = java.math.BigDecimal.valueOf(newSalary, 2).toPlainString()
            val adjustmentEntry = CashEntry(
                id = "salary_adj_${newProfile.start}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}",
                income = diff > 0,
                amount = kotlin.math.abs(diff),
                date = LocalDate.now(),
                note = "Salary adjustment (PHP $oldFmt -> PHP $newFmt)",
                channel = targetAccount.name
            )
            addCash(adjustmentEntry)
        }
        return true
    }
    fun cashEntries():List<CashEntry> {
        val a=JSONArray(preferences.getString("cash","[]"));return (0 until a.length()).map { val e=a.getJSONObject(it)
            CashEntry(e.getString("id"),e.getBoolean("income"),e.exactLong("amount"),LocalDate.parse(e.getString("date")),e.getString("note"),e.optString("channel",MoneyAccount.DEFAULT_CASH).ifBlank { MoneyAccount.DEFAULT_CASH }) }
    }
    @Synchronized fun addCash(entry:CashEntry):Boolean {
        val entries=cashEntries();entries.find { it.id==entry.id }?.let { return it==entry }
        val a=JSONArray();(entries+entry).forEach { e->a.put(JSONObject().put("id",e.id).put("income",e.income).put("amount",e.amount).put("date",e.date).put("note",e.note).put("channel",e.channel)) }
        return commit { it.putString("cash",a.toString()) }
    }
    @Synchronized fun updateCash(entry:CashEntry):Boolean {
        val entries=cashEntries()
        if (entry.isWalletTransfer || entry.id.startsWith("atm_fee_")) return false
        val index=entries.indexOfFirst { it.id==entry.id }
        if(index==-1) return false
        val updated=entries.toMutableList()
        updated[index]=entry
        val a=JSONArray();updated.forEach { e->a.put(JSONObject().put("id",e.id).put("income",e.income).put("amount",e.amount).put("date",e.date).put("note",e.note).put("channel",e.channel)) }
        return commit { it.putString("cash",a.toString()) }
    }
    @Synchronized fun deleteCash(id:String):Boolean {
        if (id.removePrefix("undo_").let { it.startsWith("transfer_") || it.startsWith("atm_fee_") }) return false
        val entries=cashEntries()
        val updated=entries.filterNot { it.id==id || it.id=="undo_$id" || (id.startsWith("undo_") && it.id==id.removePrefix("undo_")) }
        if(updated.size==entries.size) return false
        val a=JSONArray();updated.forEach { e->a.put(JSONObject().put("id",e.id).put("income",e.income).put("amount",e.amount).put("date",e.date).put("note",e.note).put("channel",e.channel)) }
        return commit { it.putString("cash",a.toString()) }
    }
    fun canReverseCash(id:String)=!id.startsWith("undo_")&&cashEntries().none {it.id=="undo_$id"}
    @Synchronized fun reverseCash(id:String):Boolean {
        if(!canReverseCash(id))return false
        val original=cashEntries().find {it.id==id}?:return false
        if (original.isWalletTransfer || original.id.startsWith("atm_fee_")) {
            val key = original.id.substringAfterLast('_')
            val entries = cashEntries()
            val group = entries.filter { it.id in setOf("transfer_in_$key", "transfer_out_$key", "atm_fee_$key") }
            if (group.count { it.isWalletTransfer } != 2 || group.any { !canReverseCash(it.id) }) return false
            val a = JSONArray()
            (entries + group.map { CashEntry("undo_${it.id}", !it.income, it.amount, LocalDate.now(), "Reversal: ${it.note.take(185)}", it.channel) }).forEach { e ->
                a.put(JSONObject().put("id",e.id).put("income",e.income).put("amount",e.amount).put("date",e.date).put("note",e.note).put("channel",e.channel))
            }
            return commit { it.putString("cash", a.toString()) }
        }
        val ok = addCash(CashEntry("undo_$id",!original.income,original.amount,LocalDate.now(),"Reversal: ${original.note.take(185)}",original.channel))
        if (ok && original.id.startsWith("salary_adj_")) {
            val currentProfile = runCatching { profile() }.getOrNull()
            if (currentProfile != null && original.id.contains("_${currentProfile.start}_")) {
                val restoredSalary = if (original.income) {
                    currentProfile.salary - original.amount
                } else {
                    currentProfile.salary + original.amount
                }
                if (restoredSalary >= 0L) {
                    saveProfile(currentProfile.copy(salary = restoredSalary))
                }
            }
        }
        return ok
    }
    fun moneyAccounts(includeArchived: Boolean = false): List<MoneyAccount> {
        val raw = preferences.getString("money_accounts", null) ?: return MoneyAccount.defaultAccounts()
        return runCatching {
            val a = JSONArray(raw)
            val list = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                MoneyAccount(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    isSavings = o.optBoolean("isSavings", false),
                    icon = o.optString("icon", if (o.optBoolean("isSavings", false)) "Savings" else "Wallet"),
                    isArchived = o.optBoolean("isArchived", false)
                )
            }
            val filtered = if (includeArchived) list else list.filter { !it.isArchived }
            if (filtered.isEmpty()) {
                val fallback = list.filter { !it.isArchived }
                if (fallback.isEmpty()) MoneyAccount.defaultAccounts() else fallback
            } else filtered
        }.getOrDefault(MoneyAccount.defaultAccounts())
    }

    fun allMoneyAccounts(): List<MoneyAccount> = moneyAccounts(includeArchived = true)

    @Synchronized fun saveMoneyAccounts(accounts: List<MoneyAccount>): Boolean {
        require(accounts.isNotEmpty()) { "At least one account is required" }
        val a = JSONArray()
        accounts.forEach { acc ->
            a.put(JSONObject().put("id", acc.id).put("name", acc.name).put("isSavings", acc.isSavings).put("icon", acc.icon).put("isArchived", acc.isArchived))
        }
        return commit { it.putString("money_accounts", a.toString()) }
    }
    @Synchronized fun archiveMoneyAccount(id: String): Boolean {
        val current = allMoneyAccounts()
        val target = current.find { it.id == id } ?: return false
        val activeAccounts = current.filter { !it.isArchived && it.id != id }
        if (activeAccounts.isEmpty()) return false
        val fallback = activeAccounts.firstOrNull { !it.isSavings } ?: activeAccounts.first()
        val updated = current.map { if (it.id == id) it.copy(isArchived = true) else it }
        val ok = saveMoneyAccounts(updated)
        if (ok) {
            if (preferences.getString("default_salary_account", null) == id) {
                commit { it.putString("default_salary_account", fallback.id) }
            }
            if (preferences.contains("profile")) {
                val currentProfile = runCatching { profile() }.getOrNull()
                if (currentProfile?.salaryAccountId == id) {
                    saveProfile(currentProfile.copy(salaryAccountId = fallback.id))
                }
            }
        }
        return ok
    }
    @Synchronized fun unarchiveMoneyAccount(id: String): Boolean {
        val current = allMoneyAccounts()
        val target = current.find { it.id == id } ?: return false
        if (!target.isArchived) return true
        val updated = current.map { if (it.id == id) it.copy(isArchived = false) else it }
        return saveMoneyAccounts(updated)
    }
    @Synchronized fun addMoneyAccount(account: MoneyAccount): Boolean {
        val current = allMoneyAccounts()
        if (current.any { it.id == account.id || it.name.trim().equals(account.name.trim(), ignoreCase = true) }) return false
        return saveMoneyAccounts(current + account.copy(name = account.name.trim()))
    }
    @Synchronized fun updateMoneyAccount(account: MoneyAccount): Boolean {
        val current = allMoneyAccounts()
        val index = current.indexOfFirst { it.id == account.id }
        if (index == -1) return false
        if (current.any { it.id != account.id && it.name.trim().equals(account.name.trim(), ignoreCase = true) }) return false
        val oldName = current[index].name
        val newName = account.name.trim()
        val updated = current.toMutableList()
        updated[index] = account.copy(name = newName)
        val ok = saveMoneyAccounts(updated)
        if (ok && !oldName.equals(newName, ignoreCase = true)) {
            val entries = cashEntries()
            if (entries.any { it.channel.equals(oldName, ignoreCase = true) }) {
                val newEntries = entries.map {
                    if (it.channel.equals(oldName, ignoreCase = true)) it.copy(channel = newName) else it
                }
                val a = JSONArray()
                newEntries.forEach { e ->
                    a.put(JSONObject().put("id", e.id).put("income", e.income).put("amount", e.amount).put("date", e.date).put("note", e.note).put("channel", e.channel))
                }
                commit { it.putString("cash", a.toString()) }
            }
        }
        return ok
    }
    fun defaultSalaryAccountId(): String {
        val saved = preferences.getString("default_salary_account", null)
        val accounts = moneyAccounts(includeArchived = false)
        if (saved != null && accounts.any { it.id == saved }) {
            return saved
        }
        return accounts.firstOrNull { it.id == "acc_bank" || it.name.equals(MoneyAccount.DEFAULT_BANK, ignoreCase = true) }?.id
            ?: accounts.firstOrNull { !it.isSavings }?.id
            ?: accounts.firstOrNull()?.id
            ?: "acc_bank"
    }
    @Synchronized fun setDefaultSalaryAccountId(id: String): Boolean {
        val accounts = moneyAccounts(includeArchived = false)
        if (accounts.none { it.id == id }) return false
        return commit { it.putString("default_salary_account", id) }
    }
    @Synchronized fun deleteMoneyAccount(id: String): Boolean {
        // Non-destructive preservation: archival instead of deletion to protect historical transaction ledger
        return archiveMoneyAccount(id)
    }
    fun savingsAccountNames(): Set<String> = allMoneyAccounts().filter { it.isSavings }.map { it.name }.toSet()
    fun policy(id:String):DebtPolicy {
        val p=JSONObject(preferences.getString("policy_$id","{}")!!)
        return DebtPolicy(p.optInt("apr",0),p.optInt("risk",1),p.optBoolean("split",false))
    }
    fun savePolicy(id:String,p:DebtPolicy)=commit { it.putString("policy_$id",JSONObject().put("apr",p.annualRateBps).put("risk",p.risk).put("split",p.allowSplit).toString()) }
    @Synchronized fun suggestions():List<PaymentSuggestion> {
        val a=JSONArray(preferences.getString("suggestions","[]"));return (0 until a.length()).map { val s=a.getJSONObject(it)
            PaymentSuggestion(s.getString("id"),if(s.isNull("amount"))null else s.exactLong("amount"),s.optString("date").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
                s.optString("reference").takeIf { it.isNotBlank() },s.getString("source"),s.optString("receipt").takeIf { it.isNotBlank() },s.getString("note")) }
    }
    @Synchronized fun addSuggestion(s:PaymentSuggestion):Boolean {
        if(preferences.getBoolean("handled_${s.id}",false)||suggestions().any { it.id==s.id }) return true
        val existing=JSONArray(preferences.getString("suggestions","[]"))
        if(existing.length()>=50) return false
        existing.put(JSONObject().put("id",s.id).put("amount",s.amount?:JSONObject.NULL).put("date",s.date?.toString()?:"").put("reference",s.reference?:"").put("source",s.source).put("receipt",s.receipt?:"").put("note",s.note))
        return commit { it.putString("suggestions",existing.toString()) }
    }
    /** Cash, proof and review completion share one durable write; retries never duplicate a receipt. */
    fun recordReceiptExpense(suggestion:PaymentSuggestion,entry:CashEntry,reference:String?):Boolean = synchronized(receiptReviewLock(scope)) review@ { synchronized(this) { synchronized(lockForScope(scope)) {
        if(entry.id!="receipt_${suggestion.id}" || entry.income || entry.amount<=0 || entry.date.isAfter(LocalDate.now())) return@review false
        if(preferences.contains("proof_suggestion_${suggestion.id}") || paymentLink("suggestion_${suggestion.id}")!=null || pendingPaymentIntents().any {it.paymentId=="suggestion_${suggestion.id}"}) return@review false
        val entries=cashEntries()
        val existing=entries.find {it.id==entry.id}
        if(existing!=null && existing!=entry) return@review false
        val cash=JSONArray()
        (if(existing==null) entries+entry else entries).forEach {e->cash.put(JSONObject().put("id",e.id).put("income",e.income).put("amount",e.amount).put("date",e.date).put("note",e.note).put("channel",e.channel))}
        completeReceiptReview(suggestion,entry.id,reference) {it.putString("cash",cash.toString())}
    } } }

    internal fun completeReceiptReview(suggestion:PaymentSuggestion,recordId:String,reference:String?,extra:(android.content.SharedPreferences.Editor)->Unit = {}):Boolean = commit {editor->
        val kept=JSONArray()
        suggestions().filterNot {it.id==suggestion.id}.forEach {s->kept.put(JSONObject().put("id",s.id).put("amount",s.amount?:JSONObject.NULL).put("date",s.date?.toString()?:"").put("reference",s.reference?:"").put("source",s.source).put("receipt",s.receipt?:"").put("note",s.note))}
        extra(editor)
        editor.putString("suggestions",kept.toString()).putBoolean("handled_${suggestion.id}",true)
            .putString("proof_$recordId",JSONObject().put("reference",reference?:"").put("receipt",suggestion.receipt?:"").toString())
    }

    @Synchronized fun dismissSuggestion(id:String):Boolean {
        val old=JSONArray(preferences.getString("suggestions","[]"));val kept=JSONArray()
        (0 until old.length()).forEach { if(old.getJSONObject(it).getString("id")!=id) kept.put(old.getJSONObject(it)) }
        return commit { it.putString("suggestions",kept.toString()).putBoolean("handled_$id",true) }
    }
    @Synchronized fun capturedPayments(): List<CapturedPayment> {
        val raw = preferences.getString("captured_payments", "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                CapturedPayment(
                    id = o.getString("id"),
                    amountCentavos = o.exactLong("amountCentavos"),
                    merchant = o.getString("merchant"),
                    sourcePackage = o.optString("sourcePackage", ""),
                    channel = o.optString("channel", MoneyAccount.DEFAULT_CASH),
                    date = LocalDate.parse(o.getString("date")),
                    rawText = o.optString("rawText", ""),
                    timestampMs = o.optLong("timestampMs", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }
    @Synchronized fun addCapturedPayment(p: CapturedPayment): Boolean {
        if (preferences.getBoolean("handled_captured_${p.id}", false) || capturedPayments().any { it.id == p.id }) return true
        val existing = capturedPayments()
        if (existing.size >= 50) return false
        val a = JSONArray()
        (existing + p).forEach { item ->
            a.put(JSONObject()
                .put("id", item.id)
                .put("amountCentavos", item.amountCentavos)
                .put("merchant", item.merchant)
                .put("sourcePackage", item.sourcePackage)
                .put("channel", item.channel)
                .put("date", item.date.toString())
                .put("rawText", item.rawText)
                .put("timestampMs", item.timestampMs)
            )
        }
        return commit { it.putString("captured_payments", a.toString()) }
    }
    fun withdrawalDetails(payment: CapturedPayment) = com.example.casha.reminders.PaymentNotificationParser.parse(
        payment.sourcePackage, null, payment.rawText, availableAccountNames = moneyAccounts().map { it.name })
        ?.takeIf { it.withdrawal }

    fun withdrawalCashAccount(): MoneyAccount? {
        val accounts = moneyAccounts().filter { !it.isSavings }
        return accounts.firstOrNull { it.id == "acc_cash" }
            ?: accounts.firstOrNull { it.name.equals(MoneyAccount.DEFAULT_CASH, true) }
            ?: accounts.firstOrNull { it.name.equals("Cash", true) }
    }

    @Synchronized fun confirmCapturedPayment(
        id: String,
        note: String = "",
        channel: String = "",
        amountCentavos: Long? = null
    ): Boolean {
        return synchronized(lockForScope(scope)) {
        val items = capturedPayments()
        val found = items.find { it.id == id } ?: return@synchronized preferences.getBoolean("handled_captured_$id", false)
        val finalAmount = amountCentavos ?: found.amountCentavos
        val finalNote = note.ifBlank { found.merchant }.take(200)
        val finalChannel = channel.ifBlank { found.channel }

        val withdrawal = withdrawalDetails(found)
        val entries = cashEntries()
        val additions: List<CashEntry>
        if (withdrawal != null) {
            // A withdrawal must be explicitly reviewed; a stale notification cannot book an expense.
            if (amountCentavos == null) return@synchronized false
            val fee = withdrawal.feeCentavos
            val debit = withdrawal.amountCentavos
            if (finalAmount <= 0 || finalAmount + fee != debit) return@synchronized false
            val source = moneyAccounts().firstOrNull { it.name.equals(finalChannel, true) && !it.isSavings }
                ?: return@synchronized false
            val cashAccount = withdrawalCashAccount()
                ?: return@synchronized false
            if (source.id == cashAccount.id) return@synchronized false
            val key = fingerprint(id).take(40)
            additions = listOf(
                CashEntry("transfer_out_$key", false, finalAmount, found.date, "ATM transfer to cash · $finalNote".take(200), source.name),
                CashEntry("transfer_in_$key", true, finalAmount, found.date, "ATM transfer from ${source.name}".take(200), cashAccount.name)
            ) + if (fee > 0) listOf(CashEntry("atm_fee_$key", false, fee, found.date, "ATM withdrawal fee", source.name)) else emptyList()
        } else {
            additions = listOf(CashEntry("nfc_" + fingerprint(id).take(48), false, finalAmount, found.date, finalNote, finalChannel))
        }
        if (additions.any { entry -> entries.any { it.id == entry.id && it != entry } }) return@synchronized false
        val cash = JSONArray()
        (entries + additions.filter { entry -> entries.none { it.id == entry.id } }).forEach { e ->
            cash.put(JSONObject().put("id",e.id).put("income",e.income).put("amount",e.amount)
                .put("date",e.date).put("note",e.note).put("channel",e.channel))
        }

        val remaining = items.filterNot { it.id == id }
        val a = JSONArray()
        remaining.forEach { item ->
            a.put(JSONObject()
                .put("id", item.id)
                .put("amountCentavos", item.amountCentavos)
                .put("merchant", item.merchant)
                .put("sourcePackage", item.sourcePackage)
                .put("channel", item.channel)
                .put("date", item.date.toString())
                .put("rawText", item.rawText)
                .put("timestampMs", item.timestampMs)
            )
        }
        commit {
            it.putString("cash", cash.toString())
            it.putString("captured_payments", a.toString())
            it.putBoolean("handled_captured_$id", true)
        }
        }
    }
    @Synchronized fun dismissCapturedPayment(id: String): Boolean {
        val items = capturedPayments()
        val remaining = items.filterNot { it.id == id }
        val a = JSONArray()
        remaining.forEach { item ->
            a.put(JSONObject()
                .put("id", item.id)
                .put("amountCentavos", item.amountCentavos)
                .put("merchant", item.merchant)
                .put("sourcePackage", item.sourcePackage)
                .put("channel", item.channel)
                .put("date", item.date.toString())
                .put("rawText", item.rawText)
                .put("timestampMs", item.timestampMs)
            )
        }
        return commit {
            it.putString("captured_payments", a.toString())
            it.putBoolean("handled_captured_$id", true)
        }
    }
    fun saveProof(paymentId:String,reference:String?,receipt:String?)=commit { it.putString("proof_$paymentId",JSONObject().put("reference",reference?:"").put("receipt",receipt?:"").toString()) }
    fun proof(paymentId:String)=preferences.getString("proof_$paymentId",null)?.let { JSONObject(it) }
    fun referenceUsed(reference:String):Boolean = preferences.all.filterKeys { it.startsWith("proof_") }.values.any {
        runCatching { JSONObject(it as String).optString("reference")==reference }.getOrDefault(false)
    }
    fun cacheLoans(loans:List<Loan>)=commit { it.putString("reminder_loans",LoanCodec.encode(loans)) }
    fun cachedLoans()=LoanCodec.decode(preferences.getString("reminder_loans","[]")!!)
    fun flag(key:String,default:Boolean=false)=preferences.getBoolean(key,default)
    fun setFlag(key:String,value:Boolean)=commit { it.putBoolean(key,value) }
    fun contact(id:String)=preferences.getString("contact_$id","")!!
    fun setContact(id:String,phone:String)=commit { it.putString("contact_$id",phone) }
    fun archive(id:String,value:Boolean)=setFlag("archived_$id",value)
    fun assistantLanguage(): AssistantLanguage {
        val raw = preferences.getString("ai_language", null)
        return if (raw != null) runCatching { AssistantLanguage.valueOf(raw) }.getOrDefault(AssistantLanguage.DEFAULT) else AssistantLanguage.DEFAULT
    }
    fun setAssistantLanguage(lang: AssistantLanguage): Boolean = commit { it.putString("ai_language", lang.name) }

    fun allPaymentLinks(): Map<String, PaymentAccountLink> {
        val result = mutableMapOf<String, PaymentAccountLink>()
        preferences.all.filterKeys { it.startsWith("payment_account_") }.forEach { (k, v) ->
            runCatching {
                val paymentId = k.removePrefix("payment_account_")
                val obj = JSONObject(v as String)
                result[paymentId] = PaymentAccountLink(
                    paymentId = paymentId,
                    loanId = obj.getString("loanId"),
                    accountId = obj.getString("accountId"),
                    accountName = obj.getString("accountName"),
                    isReceivable = obj.optBoolean("isReceivable", false),
                    amountCentavos = obj.optLong("amountCentavos", 0L),
                    date = obj.optString("date").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                )
            }
        }
        return result
    }

    fun paymentLink(paymentId: String): PaymentAccountLink? {
        val raw = preferences.getString("payment_account_$paymentId", null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            PaymentAccountLink(
                paymentId = paymentId,
                loanId = obj.getString("loanId"),
                accountId = obj.getString("accountId"),
                accountName = obj.getString("accountName"),
                isReceivable = obj.optBoolean("isReceivable", false),
                amountCentavos = obj.optLong("amountCentavos", 0L),
                date = obj.optString("date").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            )
        }.getOrNull()
    }

    @Synchronized fun savePaymentLink(link: PaymentAccountLink): Boolean = commit {
        val obj = JSONObject()
            .put("loanId", link.loanId)
            .put("accountId", link.accountId)
            .put("accountName", link.accountName)
            .put("isReceivable", link.isReceivable)
            .put("amountCentavos", link.amountCentavos)
        if (link.date != null) obj.put("date", link.date.toString())
        it.putString("payment_account_${link.paymentId}", obj.toString())
    }

    @Synchronized fun removePaymentLink(paymentId: String): Boolean = commit {
        it.remove("payment_account_$paymentId")
    }

    /** Replace a provisional deduction with cash in one commit; retries cannot change the decision. */
    fun settleProvisionalPayment(paymentId: String, cash: CashEntry?): Boolean = synchronized(lockForScope(scope)) {
        val decision = cash?.let { "external:${it.id}" } ?: "duplicate"
        val key = "settlement_$paymentId"
        preferences.getString(key, null)?.let { return@synchronized it == decision }
        val entries = cashEntries()
        if (cash != null && entries.any { it.id == cash.id && it != cash }) return@synchronized false
        val all = if (cash != null && entries.none { it.id == cash.id }) entries + cash else entries
        val array = JSONArray()
        all.forEach { e -> array.put(JSONObject().put("id",e.id).put("income",e.income).put("amount",e.amount)
            .put("date",e.date).put("note",e.note).put("channel",e.channel)) }
        commit { editor ->
            editor.putString("cash", array.toString()).remove("payment_account_$paymentId").putString(key, decision)
        }
    }

    @Synchronized fun savePaymentIntent(intent: PendingPaymentIntent): Boolean = commit {
        val obj = JSONObject()
            .put("intentId", intent.intentId)
            .put("paymentId", intent.paymentId)
            .put("loanId", intent.loanId)
            .put("installmentIndex", intent.installmentIndex)
            .put("accountId", intent.accountId)
            .put("accountName", intent.accountName)
            .put("amountCentavos", intent.amountCentavos)
            .put("date", intent.date.toString())
            .put("isReceivable", intent.isReceivable)
            .put("timestampMs", intent.timestampMs)
        it.putString("pending_intent_${intent.intentId}", obj.toString())
    }

    fun pendingPaymentIntents(): List<PendingPaymentIntent> {
        val list = mutableListOf<PendingPaymentIntent>()
        preferences.all.filterKeys { it.startsWith("pending_intent_") }.forEach { (_, v) ->
            runCatching {
                val obj = JSONObject(v as String)
                list.add(
                    PendingPaymentIntent(
                        intentId = obj.getString("intentId"),
                        paymentId = obj.getString("paymentId"),
                        loanId = obj.getString("loanId"),
                        installmentIndex = obj.getInt("installmentIndex"),
                        accountId = obj.getString("accountId"),
                        accountName = obj.getString("accountName"),
                        amountCentavos = obj.exactLong("amountCentavos"),
                        date = LocalDate.parse(obj.getString("date")),
                        isReceivable = obj.optBoolean("isReceivable", false),
                        timestampMs = obj.optLong("timestampMs", System.currentTimeMillis())
                    )
                )
            }
        }
        return list
    }

    @Synchronized fun promotePaymentIntent(intentId: String): Boolean {
        val raw = preferences.getString("pending_intent_$intentId", null) ?: return false
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        val link = PaymentAccountLink(
            paymentId = obj.getString("paymentId"),
            loanId = obj.getString("loanId"),
            accountId = obj.getString("accountId"),
            accountName = obj.getString("accountName"),
            isReceivable = obj.optBoolean("isReceivable", false),
            amountCentavos = obj.optLong("amountCentavos", 0L),
            date = obj.optString("date").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        )
        val ok = savePaymentLink(link)
        if (ok) {
            commit { it.remove("pending_intent_$intentId") }
        }
        return ok
    }

    @Synchronized fun purgePaymentIntent(intentId: String): Boolean = commit {
        it.remove("pending_intent_$intentId")
    }

    @Synchronized fun reconcileStartupIntents(loans: List<Loan>, cloudQueuePayments: Set<String> = emptySet()) {
        val intents = pendingPaymentIntents()
        if (intents.isEmpty()) return
        val existingLoanPaymentIds = loans.flatMap { it.payments.map { p -> p.id } }.toSet()
        intents.forEach { intent ->
            if (intent.paymentId in existingLoanPaymentIds || intent.paymentId in cloudQueuePayments) {
                promotePaymentIntent(intent.intentId)
            }
        }
    }

    fun unresolvedPaymentIntents(loans: List<Loan>, cloudQueuePayments: Set<String> = emptySet()): List<PendingPaymentIntent> {
        val existingLoanPaymentIds = loans.flatMap { it.payments.map { p -> p.id } }.toSet() + cloudQueuePayments
        return pendingPaymentIntents().filter { it.paymentId !in existingLoanPaymentIds }
    }

    @Synchronized fun cancelPendingPaymentIntent(
        intentId: String,
        store: LoanRepository
    ): CancelIntentResult {
        val intent = pendingPaymentIntents().find { it.intentId == intentId } ?: return CancelIntentResult.NOT_FOUND
        if (!store.isReady()) {
            return CancelIntentResult.BLOCKED_UNREADY
        }
        if (store is CloudLoanRepository) {
            if (store.state.value.enabled && !store.receivedServer.value) {
                return CancelIntentResult.BLOCKED_UNREADY
            }
            if (store.state.value.conflictLoan == intent.loanId) {
                return CancelIntentResult.BLOCKED_CONFLICT
            }
        }
        val loans = store.load()
        if (loans.isEmpty()) {
            if (store is CloudLoanRepository && store.state.value.enabled) {
                return CancelIntentResult.BLOCKED_EMPTY
            }
        }
        val paymentExists = loans.any { l -> l.payments.any { p -> p.id == intent.paymentId } }
        if (paymentExists) {
            promotePaymentIntent(intentId)
            return CancelIntentResult.PROMOTED
        }
        val purged = purgePaymentIntent(intentId)
        return if (purged) CancelIntentResult.CANCELLED else CancelIntentResult.NOT_FOUND
    }

    @Synchronized fun cancelPendingPaymentIntent(
        intentId: String,
        loans: List<Loan>
    ): CancelIntentResult {
        val intent = pendingPaymentIntents().find { it.intentId == intentId } ?: return CancelIntentResult.NOT_FOUND
        val paymentExists = loans.any { l -> l.payments.any { p -> p.id == intent.paymentId } }
        if (paymentExists) {
            promotePaymentIntent(intentId)
            return CancelIntentResult.PROMOTED
        }
        val purged = purgePaymentIntent(intentId)
        return if (purged) CancelIntentResult.CANCELLED else CancelIntentResult.NOT_FOUND
    }

    fun paydayHistory(): List<HistoricalCutoff> {
        val raw = preferences.getString("payday_history", "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val obj = a.getJSONObject(i)
                HistoricalCutoff(
                    id = obj.getString("id"),
                    start = LocalDate.parse(obj.getString("start")),
                    next = LocalDate.parse(obj.getString("next")),
                    baseSalary = obj.exactLong("baseSalary"),
                    carryover = obj.optLong("carryover", 0L),
                    essentials = obj.exactLong("essentials"),
                    emergency = obj.exactLong("emergency"),
                    strategy = DebtStrategy.valueOf(obj.getString("strategy")),
                    salaryAccountId = obj.optString("salaryAccountId").takeIf { it.isNotBlank() },
                    closedAtMs = obj.optLong("closedAtMs", System.currentTimeMillis()),
                    finalSafeToSpend = obj.optLong("finalSafeToSpend", 0L),
                    salaryEntryId = obj.optString("salaryEntryId").takeIf { it.isNotBlank() }
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun archiveCutoff(cutoff: HistoricalCutoff): Boolean {
        val history = paydayHistory()
        if (history.any { it.start == cutoff.start && it.next == cutoff.next }) return true
        val updated = history + cutoff
        val a = JSONArray()
        updated.forEach { h ->
            a.put(JSONObject()
                .put("id", h.id)
                .put("start", h.start.toString())
                .put("next", h.next.toString())
                .put("baseSalary", h.baseSalary)
                .put("carryover", h.carryover)
                .put("essentials", h.essentials)
                .put("emergency", h.emergency)
                .put("strategy", h.strategy.name)
                .put("salaryAccountId", h.salaryAccountId ?: "")
                .put("closedAtMs", h.closedAtMs)
                .put("finalSafeToSpend", h.finalSafeToSpend)
                .put("salaryEntryId", h.salaryEntryId ?: "")
            )
        }
        return commit { it.putString("payday_history", a.toString()) }
    }

    fun exportLocal():JSONObject {
        check(pendingPaymentIntents().isEmpty()) { "Resolve pending payment intents before exporting a portable backup." }
        val metadata=JSONObject()
        // Export exactly what the importer understands. Operational state stays on this device.
        preferences.all.forEach { (k,v)-> metadata.put(k,v) }
        val envelope = JSONObject().put("schemaVersion",2).put("localFinance",metadata)
        val portable = JSONObject()
        validatedBackupMetadata(envelope).forEach { (k,v) -> portable.put(k,v) }
        return JSONObject().put("schemaVersion",2).put("localFinance",portable)
    }

    fun validatedBackupMetadata(data:JSONObject):Map<String,Any> {
        require(data.getInt("schemaVersion") in 1..2)
        val source=data.getJSONObject("localFinance");val result=mutableMapOf<String,Any>()
        source.keys().forEach {key->
            val value=source.get(key)
            when {
                key=="profile" -> {
                    require(value is String);val p=JSONObject(value)
                    PaydayProfile(LocalDate.parse(p.getString("start")),LocalDate.parse(p.getString("next")),p.exactLong("salary"),p.exactLong("essentials"),p.exactLong("emergency"),DebtStrategy.valueOf(p.getString("strategy")),p.optString("salaryAccountId").takeIf { it.isNotBlank() }, p.optLong("carryover", 0L))
                    result[key]=value
                }
                key=="payday_history" -> {
                    require(value is String);val a=JSONArray(value)
                    (0 until a.length()).forEach { i ->
                        val obj = a.getJSONObject(i)
                        HistoricalCutoff(obj.getString("id"), LocalDate.parse(obj.getString("start")), LocalDate.parse(obj.getString("next")), obj.exactLong("baseSalary"), obj.optLong("carryover", 0L), obj.exactLong("essentials"), obj.exactLong("emergency"), DebtStrategy.valueOf(obj.getString("strategy")), obj.optString("salaryAccountId").takeIf { it.isNotBlank() })
                    }
                    result[key]=value
                }
                key=="default_salary_account" -> {
                    require(value is String && value.length <= 50)
                    result[key]=value
                }
                key=="cash" -> {
                    require(value is String);val list=JSONArray(value);require(list.length()<=10000)
                    val ids=mutableSetOf<String>();(0 until list.length()).forEach {i->val e=list.getJSONObject(i)
                        require(ids.add(e.getString("id")));CashEntry(e.getString("id"),e.getBoolean("income"),e.exactLong("amount"),LocalDate.parse(e.getString("date")),e.getString("note"),e.optString("channel",MoneyAccount.DEFAULT_CASH).ifBlank { MoneyAccount.DEFAULT_CASH })}
                    result[key]=value
                }
                key=="money_accounts" -> {
                    require(value is String);val list=JSONArray(value);require(list.length() in 1..50)
                    val ids = mutableSetOf<String>(); val names = mutableSetOf<String>()
                    (0 until list.length()).forEach {i->val o=list.getJSONObject(i)
                        require(ids.add(o.getString("id")) && names.add(o.getString("name").trim().lowercase(java.util.Locale.ROOT)))
                        MoneyAccount(o.getString("id"),o.getString("name"),o.optBoolean("isSavings",false),o.optString("icon","Wallet"),o.optBoolean("isArchived",false))
                    }
                    result[key]=value
                }
                key.startsWith("payment_account_") -> {
                    require(value is String);val p=JSONObject(value)
                    require(p.getString("loanId").isNotBlank() && p.getString("accountId").isNotBlank())
                    require(key.removePrefix("payment_account_").isNotBlank())
                    require(p.getString("accountName").isNotBlank())
                    p.getBoolean("isReceivable")
                    result[key]=value
                }
                key.startsWith("policy_") -> {
                    require(value is String);val p=JSONObject(value);DebtPolicy(p.exactInt("apr"),p.exactInt("risk"),p.getBoolean("split"));result[key]=value
                }
                key.startsWith("contact_") -> {require(value is String&&value.length<=100);result[key]=value}
                key.startsWith("archived_") -> {require(value is Boolean);result[key]=value}
                key=="ai_language" -> {require(value is String && runCatching { AssistantLanguage.valueOf(value) }.isSuccess);result[key]=value}
            }
        }
        return result
    }
    fun restoreMetadata(values:Map<String,Any>):Boolean=commit {editor->
        values.forEach {(key,value)->if(value is Boolean)editor.putBoolean(key,value) else editor.putString(key,value as String)}
    }

    fun inspectLedger(): LedgerSnapshot {
        return LedgerSnapshot(
            scope = scope,
            hasProfile = preferences.contains("profile"),
            profile = runCatching { profile() }.getOrNull(),
            wallets = allMoneyAccounts(),
            cashEntries = cashEntries(),
            paymentLinks = allPaymentLinks(),
            paydayHistory = paydayHistory(),
            defaultSalaryAccountId = preferences.getString("default_salary_account", null)
        )
    }

    fun exportPreferencesSnapshot(): String {
        val obj = JSONObject()
        preferences.all.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    fun restorePreferencesSnapshot(prefsJson: String): Boolean {
        return runCatching {
            val root = JSONObject(prefsJson)
            val prefsObj = root.optJSONObject("preferences") ?: root
            commit { editor ->
                editor.clear()
                val keys = prefsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = prefsObj.get(k)
                    when (v) {
                        is Boolean -> editor.putBoolean(k, v)
                        is Int -> editor.putInt(k, v)
                        is Long -> editor.putLong(k, v)
                        is String -> editor.putString(k, v)
                    }
                }
            }
        }.getOrDefault(false)
    }
    private fun checkpointFile(): File {
        val baseDir = context?.filesDir ?: File(System.getProperty("java.io.tmpdir", "."), "casha_checkpoints")
        val dir = File(baseDir, "checkpoints").apply { mkdirs() }
        return File(dir, "restore_checkpoint_${fingerprint(scope)}.json")
    }

    fun createMultiStoreCheckpoint(
        preRestoreLoansJson: String? = null,
        cloudJournalJson: String? = null
    ): Boolean {
        return runCatching {
            val file = checkpointFile()
            check(!File(file.parentFile, "restore_checkpoint.json").exists()) {
                "A legacy restore checkpoint needs account ownership review before a new restore."
            }
            val snapshot = JSONObject()
            snapshot.put("status", "IN_PROGRESS")
            snapshot.put("scope", scope)
            snapshot.put("timestamp", System.currentTimeMillis())
            val prefsObj = JSONObject()
            preferences.all.forEach { (k, v) ->
                prefsObj.put(k, v)
            }
            snapshot.put("preferences", prefsObj)
            if (preRestoreLoansJson != null) {
                snapshot.put("loans", preRestoreLoansJson)
            }
            if (cloudJournalJson != null) {
                snapshot.put("cloudJournal", cloudJournalJson)
            }
            // Atomic replacement leaves the previous recovery record intact on write failure.
            val temporary = File(file.parentFile, "${file.name}.tmp")
            java.io.FileOutputStream(temporary).use { output ->
                output.write(snapshot.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            file.exists() && file.length() > 0
        }.getOrDefault(false)
    }

    fun createRestoreCheckpoint(): Boolean = createMultiStoreCheckpoint(null, null)

    fun hasRestoreCheckpoint(): Boolean = checkpointFile().exists()

    fun preRestoreLoansSnapshot(): String? {
        return runCatching {
            val file = checkpointFile()
            if (file.exists()) {
                val snapshot = JSONObject(file.readText())
                if (snapshot.optString("status") == "IN_PROGRESS") {
                    return snapshot.optString("loans").takeIf { it.isNotBlank() }
                }
            }
            null
        }.getOrNull()
    }

    fun completeRestoreCheckpoint(): Boolean {
        return runCatching {
            val file = checkpointFile()
            if (file.exists()) {
                return file.delete()
            }
            true
        }.getOrDefault(false)
    }

    fun recoverFromRestoreCheckpointIfNeeded(store: LoanRepository? = null): Boolean {
        return runCatching {
            val file = checkpointFile()
            if (!file.exists()) return false
            val text = file.readText()
            val snapshot = JSONObject(text)
            if (snapshot.optString("scope") != scope) return false
            if (snapshot.optString("status") != "IN_PROGRESS") return false

            var loansRolledBack = true
            if (store != null) {
                if (snapshot.has("cloudJournal") && (store is CloudLoanRepository || store.journalSnapshot() != null)) {
                    loansRolledBack = store.restoreSnapshot(snapshot.getString("cloudJournal"))
                } else if (snapshot.has("loans")) {
                    loansRolledBack = store.restoreSnapshot(snapshot.getString("loans"))
                }
            } else if (snapshot.has("loans") || snapshot.has("cloudJournal")) {
                loansRolledBack = false
            }

            var prefsRestored = false
            val prefsObj = snapshot.optJSONObject("preferences")
            if (prefsObj != null) {
                prefsRestored = commit { editor ->
                    editor.clear()
                    val keys = prefsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = prefsObj.get(key)
                        when (value) {
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Float -> editor.putFloat(key, value)
                            is Double -> editor.putFloat(key, value.toFloat())
                            is String -> editor.putString(key, value)
                            else -> editor.putString(key, value.toString())
                        }
                    }
                }
            } else {
                prefsRestored = true
            }

            if (prefsRestored && loansRolledBack) {
                file.delete()
                return true
            }
            prefsRestored
        }.getOrDefault(false)
    }
    companion object {
        private val receiptReviewLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        internal fun receiptReviewLock(scope:String):Any = receiptReviewLocks.computeIfAbsent(scope){Any()}
        private val scopeLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        fun lockForScope(scope: String): Any = scopeLocks.computeIfAbsent(scope) { Any() }

        fun scopeFor(uid:String?)=uid?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b->"%02x".format(b) } } ?: "device"
        fun current(context:Context)=FinanceStore(context,scopeFor(FirebaseAuth.getInstance().currentUser?.uid))
        fun fingerprint(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
