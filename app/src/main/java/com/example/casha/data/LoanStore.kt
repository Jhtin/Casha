package com.example.casha.data

import android.content.Context
import com.example.casha.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal fun JSONObject.exactLong(key:String):Long {
    val value=get(key);require(value is Number) {"$key must be an integer"}
    return value.toString().toBigDecimal().longValueExact()
}
internal fun JSONObject.exactInt(key:String):Int {
    val value=exactLong(key);require(value in Int.MIN_VALUE..Int.MAX_VALUE);return value.toInt()
}

interface LoanRepository {
    val changes: StateFlow<Long>
    fun load(): List<Loan>
    fun save(loans: List<Loan>, expected: List<Loan>? = null): Boolean
    fun deleteUnpaid(loan:Loan):Boolean = false
    fun isReady(): Boolean = true
    fun journalSnapshot(): String? = null
    fun restoreSnapshot(snapshotJson: String): Boolean = false
    fun rollbackRestore(addedLoans: List<Loan>): Boolean = false
}

/** Local prototype persistence; cloud synchronization will replace this boundary. */
class LoanStore(context: Context? = null, preferenceName: String = "manual_loans", preferencesOverride: android.content.SharedPreferences? = null) : LoanRepository {
    private val preferences = preferencesOverride ?: context!!.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    override val changes = MutableStateFlow(0L)
    override fun load(): List<Loan> = LoanCodec.decode(preferences.getString("loans", "[]")!!)
    @Synchronized override fun deleteUnpaid(loan:Loan):Boolean {
        val records=load()
        if(loan.payments.isNotEmpty()||loan.reversals.isNotEmpty()||records.find {it.id==loan.id}!=loan)return false
        return save(records.filterNot {it.id==loan.id},records)
    }
    @Synchronized override fun save(loans: List<Loan>, expected: List<Loan>?): Boolean {
        if (expected != null && load() != expected) return false
        val saved = preferences.edit().putString("loans", LoanCodec.encode(loans)).commit()
        if (saved) changes.value++
        return saved
    }
    override fun isReady(): Boolean = true
    override fun journalSnapshot(): String? = null
    @Synchronized override fun restoreSnapshot(snapshotJson: String): Boolean {
        val decoded = runCatching { LoanCodec.decode(snapshotJson) }.getOrNull() ?: return false
        return save(decoded)
    }
    @Synchronized override fun rollbackRestore(addedLoans: List<Loan>): Boolean {
        val addedIds = addedLoans.map { it.id }.toSet()
        val current = load()
        return save(current.filterNot { it.id in addedIds })
    }
}

object LoanCodec {
    fun decode(text: String): List<Loan> {
        val array = JSONArray(text)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val payments = item.getJSONArray("payments")
            val reversals = item.optJSONArray("reversals") ?: JSONArray()
            Loan(item.getString("id"), item.getString("party"), LoanMode.valueOf(item.getString("mode")),
                item.exactLong("total"), item.exactInt("count"), LocalDate.parse(item.getString("firstDue")),
                Frequency.valueOf(item.getString("frequency")), (0 until payments.length()).map {
                    val p = payments.getJSONObject(it)
                    Payment(p.getString("id"), p.exactInt("installment"), p.exactLong("amount"), LocalDate.parse(p.getString("date")))
                }, (0 until reversals.length()).map {
                    val r = reversals.getJSONObject(it)
                    PaymentReversal(r.getString("paymentId"), LocalDate.parse(r.getString("date")), r.getString("reason"))
                })
        }
    }
    fun encode(loans: List<Loan>): String {
        val array = JSONArray()
        loans.forEach { loan ->
            val payments = JSONArray()
            val reversals = JSONArray()
            loan.reversals.forEach { r -> reversals.put(JSONObject().put("paymentId", r.paymentId)
                .put("date", r.date.toString()).put("reason", r.reason)) }
            loan.payments.forEach { p -> payments.put(JSONObject().put("id", p.id).put("installment", p.installment)
                .put("amount", p.amount).put("date", p.date.toString())) }
            array.put(JSONObject().put("id", loan.id).put("party", loan.party).put("mode", loan.mode.name)
                .put("total", loan.total).put("count", loan.count).put("firstDue", loan.firstDue.toString())
                .put("frequency", loan.frequency.name).put("payments", payments).put("reversals", reversals))
        }
        return array.toString()
    }
}
