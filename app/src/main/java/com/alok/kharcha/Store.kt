package com.alok.kharcha

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Cat(val id: String, val name: String, val emoji: String, val kind: String, val tip: String)

object Cats {
    val list = listOf(
        Cat("grocery", "Groceries", "🥬", "need", "Make a weekly list and buy once; local markets are cheaper."),
        Cat("eatout", "Eating out / Delivery", "🍔", "want", "Delivery fees make food 30-40% costlier. Keep it to twice a week, cook the rest."),
        Cat("chai", "Tea / Snacks", "☕", "want", "2-3 tea/snack stops a day add up to thousands a month. Carry snacks or a flask."),
        Cat("travel", "Travel / Fuel", "🚗", "need", "For short trips use bus, e-rickshaw or metro instead of Rapido/cab, or share a ride."),
        Cat("shop", "Shopping", "🛍️", "want", "24-hour rule: wait a day before buying; buy only if you still want it."),
        Cat("bills", "Bills / Rent / EMI", "🧾", "need", "Avoid late fees with due-date reminders."),
        Cat("fun", "Entertainment", "🎬", "want", "Give movies and outings a fixed monthly limit."),
        Cat("health", "Health", "💊", "need", "Generic medicines (Jan Aushadhi) are much cheaper; ask your doctor."),
        Cat("subs", "Recharge / Subscriptions", "📱", "want", "Cancel subscriptions you don't use."),
        Cat("transfer", "Money sent to people", "👤", "want", "Note what each transfer was for so you can see where the money goes."),
        Cat("other", "Other", "📦", "want", "Give big 'Other' expenses a proper category to see where money goes.")
    )

    fun of(id: String): Cat = list.firstOrNull { it.id == id } ?: list.last()
}

object Kinds {
    val order = listOf("need", "want", "waste")
    fun label(k: String) = when (k) {
        "need" -> "Essential"
        "want" -> "Optional"
        else -> "Avoidable"
    }
    fun color(k: String) = when (k) {
        "need" -> 0xFF2F6FDB.toInt()
        "want" -> 0xFFE0A21A.toInt()
        else -> 0xFFD8472F.toInt()
    }
    fun next(k: String) = order[(order.indexOf(k) + 1) % order.size]
}

/**
 * src is "sms" (bank SMS), "email" (bank alert mail), "receipt" (Swiggy/Rapido/... receipt mail) or "manual",
 * or a combination like "sms+email+receipt" once duplicates of one payment are merged.
 */
data class Txn(
    val id: String,
    val ts: Long,
    val amt: Double,
    var merchant: String,
    var cat: String,
    var kind: String,
    var src: String,
    var confirmed: Boolean,
    var ref: String = ""
)

data class Saving(val id: String, val ts: Long, val amt: Double, val note: String)

object Store {
    private lateinit var sp: SharedPreferences
    private val lock = Any()
    private var ready = false

    private var txns = mutableListOf<Txn>()
    var savings = mutableListOf<Saving>()
    private var seen = mutableSetOf<String>()
    var budget = 500
    var mailEmail = ""
    var mailPassEnc = ""
    var lastMailSync = 0L

    fun init(ctx: Context) {
        synchronized(lock) {
            if (ready) return
            sp = ctx.getSharedPreferences("kharcha", Context.MODE_PRIVATE)
            load()
            ready = true
        }
    }

    fun snapTxns(): List<Txn> = synchronized(lock) { txns.toList() }

    private fun load() {
        budget = sp.getInt("budget", 500)
        mailEmail = sp.getString("mailEmail", "") ?: ""
        mailPassEnc = sp.getString("mailPass", "") ?: ""
        lastMailSync = sp.getLong("lastMailSync", 0L)
        txns = mutableListOf()
        savings = mutableListOf()
        seen = HashSet(sp.getStringSet("seen", emptySet()) ?: emptySet())
        try {
            val a = JSONArray(sp.getString("txns", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                txns.add(
                    Txn(
                        o.getString("id"), o.getLong("ts"), o.getDouble("amt"),
                        o.getString("m"), o.getString("c"), o.getString("k"),
                        o.getString("s"), o.getBoolean("ok"), o.optString("r", "")
                    )
                )
            }
            val b = JSONArray(sp.getString("savings", "[]"))
            for (i in 0 until b.length()) {
                val o = b.getJSONObject(i)
                savings.add(Saving(o.getString("id"), o.getLong("ts"), o.getDouble("amt"), o.getString("n")))
            }
        } catch (e: Exception) {
            // corrupted data: start fresh rather than crash
        }
    }

    fun save() {
        synchronized(lock) {
            val a = JSONArray()
            for (t in txns) {
                a.put(
                    JSONObject().put("id", t.id).put("ts", t.ts).put("amt", t.amt)
                        .put("m", t.merchant).put("c", t.cat).put("k", t.kind)
                        .put("s", t.src).put("ok", t.confirmed).put("r", t.ref)
                )
            }
            val b = JSONArray()
            for (s in savings) {
                b.put(JSONObject().put("id", s.id).put("ts", s.ts).put("amt", s.amt).put("n", s.note))
            }
            sp.edit()
                .putString("txns", a.toString())
                .putString("savings", b.toString())
                .putStringSet("seen", HashSet(seen))
                .putInt("budget", budget)
                .putString("mailEmail", mailEmail)
                .putString("mailPass", mailPassEnc)
                .putLong("lastMailSync", lastMailSync)
                .apply()
        }
    }

    private fun sameTxn(a: Txn, b: Txn): Boolean {
        if (a.ref.isNotEmpty() && b.ref.isNotEmpty()) return a.ref == b.ref
        // Receipt mails (food delivery, rides, shopping) can arrive well after the bank alert.
        val win = if (a.src.contains("receipt") || b.src.contains("receipt")) 3 * 60 * 60 * 1000L else 15 * 60 * 1000L
        return Math.abs(a.amt - b.amt) < 0.01 && Math.abs(a.ts - b.ts) <= win
    }

    /**
     * Adds a transaction unless it was already seen. If the same payment was already recorded from
     * the other source (SMS vs Gmail), the two are merged into one entry instead of being added twice.
     * Returns true only when a brand-new entry was added.
     */
    fun addTxnIfNew(t: Txn): Boolean {
        synchronized(lock) {
            if (seen.contains(t.id)) return false
            if (t.src != "manual") {
                val dup = txns.firstOrNull { o ->
                    o.src != "manual" && !o.src.split("+").contains(t.src) && sameTxn(o, t)
                }
                if (dup != null) {
                    seen.add(t.id)
                    dup.src = dup.src + "+" + t.src
                    if (dup.ref.isEmpty()) dup.ref = t.ref
                    if (!dup.confirmed && (t.src == "receipt" || (dup.cat == "other" && t.cat != "other"))) {
                        // receipt mails / better-labelled alerts give the real merchant name (Swiggy, Rapido...)
                        dup.cat = t.cat
                        dup.merchant = t.merchant
                        dup.kind = t.kind
                    }
                    save()
                    return false
                }
                // A receipt mail is not a debit: it only names a payment already seen from the bank.
                // Not marked seen, so it can still be matched if the bank alert arrives later.
                if (t.src == "receipt") return false
            }
            seen.add(t.id)
            txns.add(t)
            save()
            return true
        }
    }

    fun removeTxn(t: Txn) {
        synchronized(lock) {
            txns.remove(t)
            save()
        }
    }

    fun addSaving(s: Saving) {
        synchronized(lock) {
            savings.add(s)
            save()
        }
    }
}
