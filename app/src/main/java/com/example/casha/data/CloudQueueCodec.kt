package com.example.casha.data

import com.example.casha.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Store a chain's starting snapshot once, then small commands rather than repeated full ledgers. */
object CloudQueueCodec {
    fun encode(queue: List<CloudMutation>): JSONArray {
        val previous = mutableMapOf<String, Loan>()
        val result = JSONArray()
        val deleted=mutableSetOf<String>()
        queue.forEach { mutation ->
            val after = mutation.after
            require(after.id !in deleted) {"A deleted loan cannot receive more queued changes"}
            val item = JSONObject().put("loanId", after.id).put("kind", mutation.kind.name)
            if (!previous.containsKey(after.id)) item.put("initial", LoanCodec.encode(listOfNotNull(mutation.before)))
            else require(previous[after.id] == mutation.before)
            when (mutation.kind) {
                MutationKind.DELETE -> {deleted+=after.id}
                MutationKind.CREATE -> item.put("loan", LoanCodec.encode(listOf(after)))
                MutationKind.EDIT -> item.put("party", after.party).put("total", after.total).put("count", after.count)
                    .put("firstDue", after.firstDue.toString()).put("frequency", after.frequency.name)
                MutationKind.PAYMENT -> after.payments.last().let { p -> item.put("id",p.id).put("installment",p.installment).put("amount",p.amount).put("date",p.date.toString()) }
                MutationKind.REVERSAL -> after.reversals.last().let { r -> item.put("paymentId",r.paymentId).put("date",r.date.toString()).put("reason",r.reason) }
            }
            previous[after.id] = after; result.put(item)
        }
        return result
    }
    fun decode(array: JSONArray): List<CloudMutation> {
        val previous = mutableMapOf<String, Loan>()
        val deleted=mutableSetOf<String>()
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val mutation = if (item.has("after")) {
                // Read the initial development journal format without discarding queued records.
                CloudMutation(LoanCodec.decode(item.getString("before")).firstOrNull(),LoanCodec.decode(item.getString("after")).single())
            } else {
                val id = item.getString("loanId")
                val before = if (item.has("initial")) LoanCodec.decode(item.getString("initial")).firstOrNull() else previous[id]
                val after = when (MutationKind.valueOf(item.getString("kind"))) {
                    MutationKind.DELETE -> requireNotNull(before)
                    MutationKind.CREATE -> LoanCodec.decode(item.getString("loan")).single()
                    MutationKind.EDIT -> requireNotNull(before).edit(item.getString("party"),item.getLong("total"),item.getInt("count"),LocalDate.parse(item.getString("firstDue")),Frequency.valueOf(item.getString("frequency")))
                    MutationKind.PAYMENT -> requireNotNull(before).pay(item.getInt("installment"),item.getLong("amount"),item.getString("id"),LocalDate.parse(item.getString("date")))
                    MutationKind.REVERSAL -> requireNotNull(before).reverse(item.getString("paymentId"),LocalDate.parse(item.getString("date")),item.getString("reason"))
                }
                require(after.id == id)
                CloudMutation(before, after, item.getString("kind")==MutationKind.DELETE.name)
            }
            require(mutation.after.id !in deleted)
            if(mutation.kind==MutationKind.DELETE)deleted+=mutation.after.id
            mutation.kind; previous[mutation.after.id] = mutation.after; mutation
        }
    }
}
